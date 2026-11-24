using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using IfboardWin.Models;
using IfboardWin.Services;
using IfboardWin.Theme;

namespace IfboardWin;

public partial class MainWindow : Window
{
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attr, ref int attrValue, int attrSize);

    private static readonly string[] SampleCandidates = ["输入法", "输入", "书", "数", "舒", "树", "输", "属", "术"];

    private AppSettings _settings = new();
    private RimePreferences _rimePrefs = new();
    private WebDavConfig _webDav = new();
    private GrammarRemoteInfo? _cachedRemoteGrammar;
    private CancellationTokenSource? _grammarDownloadCts;

    private bool _isBindingUi;
    private bool _isSyncingProvider;
    private bool _passwordVisible;
    private bool _isOperationBusy;
    private int _fontPoint = 12;

    // 自动保存：改动后停顿一会儿再写配置并部署，连续拨动只部署一次
    private readonly DispatcherTimer _rimeApplyTimer = new() { Interval = TimeSpan.FromMilliseconds(900) };
    private readonly DispatcherTimer _skinApplyTimer = new() { Interval = TimeSpan.FromMilliseconds(700) };
    private bool _rimePending;
    private bool _skinPending;

    private readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromMilliseconds(1500) };
    private Storyboard? _glowStoryboard;
    private Storyboard? _statusPulse;
    private readonly HashSet<StackPanel> _visitedPages = [];
    private StackPanel? _currentPage;
    private int _currentNavIndex;

    public bool IsOperationBusy => _isOperationBusy || _rimePending || _skinPending;

    /// <summary>App 真正释放窗口时置位；平时点关闭只是隐藏，下次打开秒开</summary>
    public bool ReleaseRequested { get; private set; }

    public MainWindow()
    {
        InitializeComponent();
        _rimeApplyTimer.Tick += async (_, _) => { _rimeApplyTimer.Stop(); await ApplyRimeNowAsync(); };
        _skinApplyTimer.Tick += async (_, _) => { _skinApplyTimer.Stop(); await ApplySkinNowAsync(); };
        _toastTimer.Tick += (_, _) => { _toastTimer.Stop(); HideToast(); };

        _currentPage = PageCommon;
        Loaded += (_, _) =>
        {
            LoadAllStateIntoUi();
            UpdateRowSeparators();
            PlayCardEntrance(PageCommon);
            _visitedPages.Add(PageCommon);
            // 次要信息（微软拼音检测、系统取色）放到首帧之后，窗口先出来
            Dispatcher.BeginInvoke(async () =>
            {
                await RefreshSystemAsyncState();
                StartGlowAnimation();
            }, DispatcherPriority.Background);
        };
        Activated += (_, _) => _glowStoryboard?.Resume(this);
        Deactivated += (_, _) => _glowStoryboard?.Pause(this);
    }

    /// <summary>由 App 调用：真正关闭并释放窗口</summary>
    public void ReleaseNow()
    {
        ReleaseRequested = true;
        Close();
    }

    public void RefreshExternalState() => LoadAllStateIntoUi();

    public void RefreshImeIconChecksFromSettings(AppSettings? external = null)
    {
        if (external != null)
        {
            _settings.ShowTaskbarImeIcon = external.ShowTaskbarImeIcon;
            _settings.ShowAsciiSwitchTip = external.ShowAsciiSwitchTip;
        }

        var prev = _isBindingUi;
        _isBindingUi = true;
        try
        {
            ShowTaskbarImeIconCheck.IsChecked = _settings.ShowTaskbarImeIcon;
            ShowAsciiSwitchTipCheck.IsChecked = _settings.ShowAsciiSwitchTip;
        }
        finally
        {
            _isBindingUi = prev;
        }
    }

    public void RefreshSystemThemePreview()
    {
        ApplyNativeTitleBarTheme();
        UpdateSkinPreview();
    }

    private void LoadAllStateIntoUi()
    {
        _isBindingUi = true;
        try
        {
            _settings = AppSettingsService.Load();
            _rimePrefs = RimeConfigService.LoadPreferences();
            _webDav = WebDavService.LoadConfig();

            WarmPaperPalette.ApplyToResources(System.Windows.Application.Current.Resources, _settings.UiMode);
            ApplyNativeTitleBarTheme();

            var devId = Paths.ReadInstallationId();
            DeviceBadgeText.Text = string.IsNullOrWhiteSpace(devId) ? $"v{Paths.AppVersion}" : $"v{Paths.AppVersion} · {devId}";
            DeviceBadgeText.ToolTip = DeviceBadgeText.Text;
            AboutVersionText.Text = $"公开版 v{Paths.AppVersion}";

            // 同步
            DavUrlBox.Text = _webDav.Url;
            DavDirBox.Text = _webDav.Dir;
            DavUserBox.Text = _webDav.User;
            DavPassBox.Password = _webDav.Password;
            DavPassVisibleBox.Text = _webDav.Password;
            SyncProviderRadioFromUrl(_webDav.Url);
            LastSyncText.Text = _webDav.LastSyncUnixMs > 0
                ? $"上次同步 {DateTimeOffset.FromUnixTimeMilliseconds(_webDav.LastSyncUnixMs).LocalDateTime:MM-dd HH:mm}"
                : "尚未同步";
            DailyAutoSyncCheck.IsChecked = _settings.AutoSyncDaily;
            RefreshClipboardSyncUi();
            AutoCheckUpdateCheck.IsChecked = _settings.AutoCheckUpdate;

            var hasSavedPass = !string.IsNullOrWhiteSpace(_webDav.User) && !string.IsNullOrWhiteSpace(_webDav.Password);
            var showLegacy = !hasSavedPass && WebDavService.TryReadLegacyConfig() != null;
            LegacyImportBanner.Visibility = showLegacy ? Visibility.Visible : Visibility.Collapsed;
            LegacyImportBanner.Tag = showLegacy;

            // 输入方案
            SchemaRimeIceRadio.IsChecked = _rimePrefs.PrimarySchema != "double_pinyin_flypy";
            SchemaFlypyRadio.IsChecked = _rimePrefs.PrimarySchema == "double_pinyin_flypy";
            KeepBothSchemasCheck.IsChecked = _rimePrefs.KeepBothSchemas;
            PageSize5Radio.IsChecked = _rimePrefs.PageSize <= 5;
            PageSize7Radio.IsChecked = _rimePrefs.PageSize is > 5 and <= 7;
            PageSize9Radio.IsChecked = _rimePrefs.PageSize > 7;
            GrammarEnabledCheck.IsChecked = _rimePrefs.GrammarEnabled;
            FuzzyEnEngCheck.IsChecked = _rimePrefs.FuzzyEnEng;
            FuzzyInIngCheck.IsChecked = _rimePrefs.FuzzyInIng;
            FuzzyZhChShCheck.IsChecked = _rimePrefs.FuzzyZhChSh;
            KeepRawFlypyCheck.IsChecked = _rimePrefs.KeepRawFlypyPreedit;
            CtrlSpaceOnlyCheck.IsChecked = _rimePrefs.CtrlSpaceOnlySwitch;
            TabPageFlipCheck.IsChecked = _rimePrefs.TabPageFlip;
            EnableCorrectionCheck.IsChecked = _rimePrefs.EnableCorrection;
            AsciiAutoSpaceCheck.IsChecked = _rimePrefs.AsciiAutoSpace;
            AsciiPunctDefaultCheck.IsChecked = _rimePrefs.AsciiPunctDefault;

            // 外观
            UiAutoRadio.IsChecked = _settings.UiMode == UiThemeMode.Auto;
            UiLightRadio.IsChecked = _settings.UiMode == UiThemeMode.Light;
            UiDarkRadio.IsChecked = _settings.UiMode == UiThemeMode.Dark;
            AnimatedGlowCheck.IsChecked = _settings.AnimatedBackground;

            SkinTerracottaRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.ClaudeTerracotta;
            SkinSystemAccentRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.SystemAccent;
            SkinAmberRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.WarmAmber;
            DensityCompactRadio.IsChecked = _settings.Density == LayoutDensity.Compact;
            DensityStandardRadio.IsChecked = _settings.Density == LayoutDensity.Standard;

            _fontPoint = Math.Clamp(_settings.FontPoint, 10, 18);
            FontValueText.Text = $"{_fontPoint} pt";
            CornerSlider.Value = Math.Clamp(_settings.CornerRadius, 0, 14);
            CornerValueText.Text = $"{(int)CornerSlider.Value} px";

            AutoAccentTrayCheck.IsChecked = _settings.AutoFollowSystemAccentTray;
            DisableForcedAsciiCheck.IsChecked = _settings.DisableAppForcedAscii;
            RefreshImeIconChecksFromSettings(_settings);

            RefreshLocalGrammarCard();
            BuildSkinCards();
            UpdateSkinPreview();
        }
        finally
        {
            _isBindingUi = false;
        }
    }

    private async Task RefreshSystemAsyncState()
    {
        var hasMs = await RimeProcessService.IsMicrosoftPinyinPresentAsync();
        if (hasMs)
        {
            MsPinyinRow.Hint = "系统里还留着微软拼音，它会和 Ctrl+Space、Win+Space 抢中文状态，建议移除。";
            RemoveMsPinyinBtn.IsEnabled = true;
            RemoveMsPinyinBtn.Content = "移除微软拼音";
        }
        else
        {
            MsPinyinRow.Hint = "简体中文下只剩小狼毫一个输入法，切换不会冲突。";
            RemoveMsPinyinBtn.IsEnabled = false;
            RemoveMsPinyinBtn.Content = "已移除";
        }
    }

    #region 导航、搜索、动效

    private StackPanel[] Pages => [PageCommon, PageKeys, PageCandidates, PageLook, PageSync, PageDict, PageAdvanced, PageAbout];
    private RadioButton[] NavItems => [NavCommon, NavKeys, NavCandidates, NavLook, NavSync, NavDict, NavAdvanced, NavAbout];

    private void NavTab_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        var index = Array.IndexOf(NavItems, sender);
        if (index < 0) return;
        ShowPage(index, animate: true);
    }

    private void ShowPage(int index, bool animate)
    {
        if (!string.IsNullOrEmpty(SearchBox.Text))
        {
            SearchBox.Text = ""; // 退出搜索模式（会恢复页面可见性）
        }

        var pages = Pages;
        var target = pages[index];
        var direction = index >= _currentNavIndex ? 1 : -1;
        _currentNavIndex = index;
        for (var i = 0; i < pages.Length; i++)
            pages[i].Visibility = i == index ? Visibility.Visible : Visibility.Collapsed;
        _currentPage = target;
        ContentScroll.ScrollToTop();
        MoveNavIndicator(index, animate);

        if (!animate) return;
        if (_visitedPages.Add(target))
        {
            PlayCardEntrance(target);
        }
        else
        {
            // 切换分类：新内容按导航方向轻推 30px 并淡入
            var shift = new TranslateTransform(30 * direction, 0);
            target.RenderTransform = shift;
            target.Opacity = 0;
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            shift.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(300)) { EasingFunction = ease });
            target.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(300)));
        }
    }

    private void MoveNavIndicator(int index, bool animate)
    {
        var item = NavItems[index];
        var y = item.TranslatePoint(new Point(0, 0), NavHost).Y;
        if (!animate)
        {
            NavIndicatorShift.BeginAnimation(TranslateTransform.YProperty, null);
            NavIndicatorShift.Y = y;
            return;
        }
        // 滑块带一点回弹
        var anim = new DoubleAnimation(y, TimeSpan.FromMilliseconds(360))
        {
            EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.35 }
        };
        NavIndicatorShift.BeginAnimation(TranslateTransform.YProperty, anim);
    }

    /// <summary>卡片入场：上移 14px、从 .98 放大并淡入，依次错开；每个分类只在第一次进入时播放</summary>
    private void PlayCardEntrance(StackPanel page)
    {
        if (!SystemParameters.ClientAreaAnimation) return;
        var delay = 0;
        foreach (var child in page.Children.OfType<FrameworkElement>())
        {
            if (child is not Border && child is not Grid) continue;
            var group = new TransformGroup();
            var scale = new ScaleTransform(0.98, 0.98);
            var move = new TranslateTransform(0, 14);
            group.Children.Add(scale);
            group.Children.Add(move);
            child.RenderTransformOrigin = new Point(0.5, 0);
            child.RenderTransform = group;
            child.Opacity = 0;

            var begin = TimeSpan.FromMilliseconds(delay);
            var dur = TimeSpan.FromMilliseconds(400);
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            move.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, dur) { BeginTime = begin, EasingFunction = ease });
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(1, dur) { BeginTime = begin, EasingFunction = ease });
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(1, dur) { BeginTime = begin, EasingFunction = ease });
            child.BeginAnimation(OpacityProperty, new DoubleAnimation(1, dur) { BeginTime = begin });
            delay += 40;
        }
    }

    /// <summary>背景三团色晕缓慢漂移（约 30 秒一周期，低帧率）；失焦时暂停，系统关了动画时不播放</summary>
    private void StartGlowAnimation()
    {
        _glowStoryboard?.Stop(this);
        _glowStoryboard = null;
        if (!_settings.AnimatedBackground || !SystemParameters.ClientAreaAnimation) return;

        var sb = new Storyboard { RepeatBehavior = RepeatBehavior.Forever };
        Timeline.SetDesiredFrameRate(sb, 20);
        void Drift(System.Windows.Shapes.Ellipse glow, double dx, double dy, double seconds)
        {
            var ease = new SineEase { EasingMode = EasingMode.EaseInOut };
            var ax = new DoubleAnimation(0, dx, TimeSpan.FromSeconds(seconds / 2)) { AutoReverse = true, EasingFunction = ease };
            var ay = new DoubleAnimation(0, dy, TimeSpan.FromSeconds(seconds / 2)) { AutoReverse = true, EasingFunction = ease };
            Storyboard.SetTarget(ax, glow);
            Storyboard.SetTargetProperty(ax, new PropertyPath("RenderTransform.X"));
            Storyboard.SetTarget(ay, glow);
            Storyboard.SetTargetProperty(ay, new PropertyPath("RenderTransform.Y"));
            sb.Children.Add(ax);
            sb.Children.Add(ay);
        }
        Drift(Glow1, 90, 60, 30);
        Drift(Glow2, -80, 70, 30);
        Drift(Glow3, 70, -60, 30);
        sb.Begin(this, true);
        if (!IsActive) sb.Pause(this);
        _glowStoryboard = sb;
    }

    private void AnimatedGlow_Click(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi) return;
        _settings.AnimatedBackground = AnimatedGlowCheck.IsChecked == true;
        AppSettingsService.Save(_settings);
        if (_settings.AnimatedBackground)
        {
            StartGlowAnimation();
        }
        else
        {
            _glowStoryboard?.Stop(this);
            _glowStoryboard = null;
            foreach (var g in new[] { Glow1, Glow2, Glow3 })
                if (g.RenderTransform is TranslateTransform t) { t.X = 0; t.Y = 0; }
        }
        ShowToast("已保存");
    }

    private IEnumerable<SettingRow> AllRows(Panel root) =>
        LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>().SelectMany(Descend);

    private static IEnumerable<SettingRow> Descend(DependencyObject node)
    {
        if (node is SettingRow row)
        {
            yield return row;
            yield break;
        }
        foreach (var child in LogicalTreeHelper.GetChildren(node).OfType<DependencyObject>())
            foreach (var r in Descend(child))
                yield return r;
    }

    /// <summary>每张卡片里最后一个可见的行不画分隔线</summary>
    private void UpdateRowSeparators()
    {
        foreach (var page in Pages)
        {
            foreach (var card in LogicalTreeHelper.GetChildren(page).OfType<Border>())
            {
                if (card.Child is not Panel panel) continue;
                var rows = panel.Children.OfType<SettingRow>().ToList();
                if (rows.Count == 0) continue;
                var lastVisible = rows.LastOrDefault(r => r.Visibility == Visibility.Visible);
                foreach (var r in rows)
                    r.ShowSeparator = !ReferenceEquals(r, lastVisible);
            }
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!IsLoaded) return;
        var q = SearchBox.Text.Trim().ToLowerInvariant();
        var pages = Pages;

        if (q.Length == 0)
        {
            foreach (var page in pages)
            {
                foreach (var child in page.Children.OfType<UIElement>()) child.Visibility = Visibility.Visible;
                foreach (var row in AllRows(page)) row.Visibility = Visibility.Visible;
                page.Visibility = ReferenceEquals(page, _currentPage) ? Visibility.Visible : Visibility.Collapsed;
            }
            LegacyImportBanner.Visibility = Visibility.Collapsed;
            if (LegacyImportBanner.Tag is true) LegacyImportBanner.Visibility = Visibility.Visible;
            SearchEmptyText.Visibility = Visibility.Collapsed;
            NavIndicator.Opacity = 1;
            UpdateRowSeparators();
            return;
        }

        NavIndicator.Opacity = 0.35;
        var any = false;
        foreach (var page in pages)
        {
            var pageHit = false;
            var children = page.Children.OfType<FrameworkElement>().ToList();
            for (var i = 0; i < children.Count; i++)
            {
                var child = children[i];
                if (child is TextBlock) { child.Visibility = Visibility.Collapsed; continue; } // 页标题、卡片标题
                var rows = Descend(child).ToList();
                if (rows.Count == 0) { child.Visibility = Visibility.Collapsed; continue; }
                var hit = false;
                foreach (var row in rows)
                {
                    var match = row.SearchText.Contains(q, StringComparison.Ordinal);
                    row.Visibility = match ? Visibility.Visible : Visibility.Collapsed;
                    hit |= match;
                }
                child.Visibility = hit ? Visibility.Visible : Visibility.Collapsed;
                // 命中卡片的标题跟着显示
                if (hit && i > 0 && children[i - 1] is TextBlock title && title.Style == (Style)FindResource("CardTitleStyle"))
                    title.Visibility = Visibility.Visible;
                pageHit |= hit;
            }
            page.Visibility = pageHit ? Visibility.Visible : Visibility.Collapsed;
            any |= pageHit;
        }
        SearchEmptyText.Visibility = any ? Visibility.Collapsed : Visibility.Visible;
        UpdateRowSeparators();
        ContentScroll.ScrollToTop();
    }

    /// <summary>搜索框里按回车：跳到第一个结果所在的分类并让那一行闪一下主题色底</summary>
    private void SearchBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            SearchBox.Text = "";
            e.Handled = true;
            return;
        }
        if (e.Key != Key.Enter || string.IsNullOrWhiteSpace(SearchBox.Text)) return;
        e.Handled = true;
        var pages = Pages;
        for (var i = 0; i < pages.Length; i++)
        {
            var row = AllRows(pages[i]).FirstOrDefault(r => r.Visibility == Visibility.Visible && pages[i].Visibility == Visibility.Visible);
            if (row == null) continue;
            SearchBox.Text = "";
            NavItems[i].IsChecked = true;
            ShowPage(i, animate: false);
            Dispatcher.BeginInvoke(() => FlashRow(row), DispatcherPriority.Loaded);
            return;
        }
    }

    private void FlashRow(SettingRow row)
    {
        row.BringIntoView();
        row.IsFlashing = true;
        var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1100) };
        t.Tick += (_, _) => { t.Stop(); row.IsFlashing = false; };
        t.Start();
    }

    private void GoDictPage_Click(object sender, RoutedEventArgs e) => NavDict.IsChecked = true;

    #endregion

    #region 状态胶囊与 Toast

    private void SetStatus(string message, bool isBusy = false, bool isError = false, double? progressPercent = null, string detail = "")
    {
        StatusBannerText.Text = message;
        StatusPill.ToolTip = string.IsNullOrEmpty(detail) ? message : $"{message}\n{detail}";
        StatusDetailText.Text = detail;

        var dotBrushKey = isError ? "WpErrorBrush" : (isBusy ? "WpAmberBrush" : "WpSageBrush");
        StatusDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, dotBrushKey);

        // 进行中：琥珀色小圆点呼吸闪烁
        if (isBusy)
        {
            if (_statusPulse == null)
            {
                var a = new DoubleAnimation(1, 0.35, TimeSpan.FromMilliseconds(700)) { AutoReverse = true };
                _statusPulse = new Storyboard { RepeatBehavior = RepeatBehavior.Forever };
                Storyboard.SetTarget(a, StatusDot);
                Storyboard.SetTargetProperty(a, new PropertyPath(OpacityProperty));
                _statusPulse.Children.Add(a);
                _statusPulse.Begin(this, true);
            }
        }
        else if (_statusPulse != null)
        {
            _statusPulse.Stop(this);
            _statusPulse = null;
            StatusDot.Opacity = 1;
        }

        if (progressPercent.HasValue)
        {
            GlobalProgressBar.Visibility = Visibility.Visible;
            GlobalProgressBar.IsIndeterminate = progressPercent.Value < 0;
            if (progressPercent.Value >= 0)
                GlobalProgressBar.Value = Math.Clamp(progressPercent.Value, 0, 100);
        }
        else
        {
            GlobalProgressBar.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>底部浮出玻璃胶囊提示，1.5 秒后淡出</summary>
    private void ShowToast(string text, bool isError = false, bool sticky = false)
    {
        ToastText.Text = text;
        ToastDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, isError ? "WpErrorBrush" : (sticky ? "WpAmberBrush" : "WpSageBrush"));
        var spring = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.4 };
        ToastShift.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(320)) { EasingFunction = spring });
        ToastHost.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(200)));
        _toastTimer.Stop();
        if (!sticky)
        {
            _toastTimer.Interval = TimeSpan.FromMilliseconds(isError ? 3200 : 1500);
            _toastTimer.Start();
        }
    }

    private void HideToast()
    {
        ToastShift.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(12, TimeSpan.FromMilliseconds(250)));
        ToastHost.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(250)));
    }

    #endregion

    #region 互斥任务

    private async Task<bool> RunExclusiveAsync(Func<Task> action)
    {
        if (_isOperationBusy || !await App.OperationGate.WaitAsync(0))
        {
            SetStatus("另一项任务还在进行，请稍候…", isBusy: true);
            return false;
        }

        _isOperationBusy = true;
        SetBusyButtons(true);
        try
        {
            await action();
        }
        finally
        {
            _isOperationBusy = false;
            SetBusyButtons(false);
            App.OperationGate.Release();
            if (!IsVisible && System.Windows.Application.Current is App app)
                app.ScheduleHiddenWindowRelease();
        }
        return true;
    }

    private void SetBusyButtons(bool busy)
    {
        var enabled = !busy;
        QuickDeployBtn.IsEnabled = enabled;
        SyncNowBtn.IsEnabled = enabled;
        TestDavBtn.IsEnabled = enabled;
        RestoreProfileBtn.IsEnabled = enabled;
        BackupProfileBtn.IsEnabled = enabled;
        CheckGrammarBtn.IsEnabled = enabled;
        DownloadGrammarBtn.IsEnabled = enabled;
        DeleteGrammarBtn.IsEnabled = enabled && (GrammarModelService.IsLocalModelValid() || GrammarModelService.GetPendingPartSizeBytes() > 0);
        RemoveMsPinyinBtn.IsEnabled = enabled && RemoveMsPinyinBtn.Content as string != "已移除";
        RestartWeaselBtn.IsEnabled = enabled;
        CheckAppUpdateBtn.IsEnabled = enabled;
    }

    private async void QuickDeploy_Click(object sender, RoutedEventArgs e)
    {
        if (_rimePending) { _rimeApplyTimer.Stop(); await ApplyRimeNowAsync(); return; }
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在重新部署…", isBusy: true, progressPercent: -1);
                var code = await RimeProcessService.DeployAsync();
                SetStatus(code == 0 ? "部署完成" : $"部署结束（返回码 {code}）", isError: code != 0, detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast(code == 0 ? "已重新部署" : $"部署返回码 {code}", isError: code != 0);
            }
            catch (Exception ex)
            {
                SetStatus($"部署失败：{ex.Message}", isError: true);
                ShowToast("部署失败", isError: true);
            }
        });
    }

    private void MinimizeToTray_Click(object sender, RoutedEventArgs e) => Close();

    #endregion

    #region 输入方案（自动保存）

    private RimePreferences CollectRimePrefsFromUi() => new()
    {
        PrimarySchema = SchemaFlypyRadio.IsChecked == true ? "double_pinyin_flypy" : "rime_ice",
        KeepBothSchemas = KeepBothSchemasCheck.IsChecked == true,
        PageSize = PageSize9Radio.IsChecked == true ? 9 : (PageSize7Radio.IsChecked == true ? 7 : 5),
        FuzzyEnEng = FuzzyEnEngCheck.IsChecked == true,
        FuzzyInIng = FuzzyInIngCheck.IsChecked == true,
        FuzzyZhChSh = FuzzyZhChShCheck.IsChecked == true,
        KeepRawFlypyPreedit = KeepRawFlypyCheck.IsChecked == true,
        CtrlSpaceOnlySwitch = CtrlSpaceOnlyCheck.IsChecked == true,
        TabPageFlip = TabPageFlipCheck.IsChecked == true,
        GrammarEnabled = GrammarEnabledCheck.IsChecked == true,
        EnableCorrection = EnableCorrectionCheck.IsChecked == true,
        AsciiAutoSpace = AsciiAutoSpaceCheck.IsChecked == true,
        AsciiPunctDefault = AsciiPunctDefaultCheck.IsChecked == true
    };

    private void RimeControl_Changed(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        UpdateSkinPreview(); // 候选个数会影响预览
        _rimePending = true;
        _rimeApplyTimer.Stop();
        _rimeApplyTimer.Start();
        SetStatus("有改动，马上保存…", isBusy: true);
    }

    private async Task ApplyRimeNowAsync()
    {
        if (!_rimePending) return;
        var ran = await RunExclusiveAsync(async () =>
        {
            _rimePending = false;
            try
            {
                ShowToast("已保存，正在部署…", sticky: true);
                SetStatus("正在部署输入方案…", isBusy: true, progressPercent: -1);
                _rimePrefs = CollectRimePrefsFromUi();
                RimeConfigService.ApplyPreferences(_rimePrefs);
                var code = await RimeProcessService.DeployAsync();
                SetStatus(code == 0 ? "已生效" : $"已保存，部署返回码 {code}", isError: code != 0, detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast(code == 0 ? "已生效" : $"部署返回码 {code}", isError: code != 0);
            }
            catch (Exception ex)
            {
                SetStatus($"保存失败：{ex.Message}", isError: true);
                ShowToast("保存失败", isError: true);
            }
        });
        // 有别的任务占着：稍后再试，不丢改动
        if (!ran) { _rimePending = true; _rimeApplyTimer.Interval = TimeSpan.FromMilliseconds(1500); _rimeApplyTimer.Start(); }
        else _rimeApplyTimer.Interval = TimeSpan.FromMilliseconds(900);
    }

    private void ReloadState_Click(object sender, RoutedEventArgs e)
    {
        LoadAllStateIntoUi();
        ShowToast("已重新读取");
        SetStatus("已从磁盘重新读取设置");
    }

    private void AppOptionCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        _settings.AutoSyncDaily = DailyAutoSyncCheck.IsChecked == true;
        _settings.AutoCheckUpdate = AutoCheckUpdateCheck.IsChecked == true;
        AppSettingsService.Save(_settings);
        ShowToast("已保存");
    }

    private async void ImeIconSetting_Click(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;

        if (_isOperationBusy || App.OperationGate.CurrentCount == 0)
        {
            RefreshImeIconChecksFromSettings(_settings);
            ShowToast("另一项任务还在进行，请稍后再试", isError: true);
            return;
        }

        if (ReferenceEquals(sender, ShowTaskbarImeIconCheck))
            _settings.ShowTaskbarImeIcon = ShowTaskbarImeIconCheck.IsChecked == true;
        else if (ReferenceEquals(sender, ShowAsciiSwitchTipCheck))
            _settings.ShowAsciiSwitchTip = ShowAsciiSwitchTipCheck.IsChecked == true;

        await RunExclusiveAsync(async () =>
        {
            try
            {
                ShowToast("已保存，正在部署…", sticky: true);
                SetStatus("正在部署…", isBusy: true, progressPercent: -1);
                AppSettingsService.Save(_settings);
                var showTaskbarIcon = _settings.ShowTaskbarImeIcon;
                await Task.Run(() => RimeProcessService.ApplyTaskbarImeIconState(showTaskbarIcon));
                WeaselSkinService.WriteWeaselCustomYaml(_settings);
                var code = await RimeProcessService.DeployAsync();
                SetStatus(code == 0 ? "已生效" : $"已保存，部署返回码 {code}", isError: code != 0, detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast(code == 0 ? "已生效" : $"部署返回码 {code}", isError: code != 0);
            }
            catch (Exception ex)
            {
                SetStatus($"更新图标设置失败：{ex.Message}", isError: true);
                ShowToast("保存失败", isError: true);
            }
        });
    }

    #endregion

    #region 外观与皮肤（自动保存）

    private UiThemeMode SelectedUiModeFromRadios()
    {
        if (UiLightRadio.IsChecked == true) return UiThemeMode.Light;
        if (UiDarkRadio.IsChecked == true) return UiThemeMode.Dark;
        return UiThemeMode.Auto;
    }

    private ThemePresetKind SelectedSkinFromRadios()
    {
        if (SkinSystemAccentRadio.IsChecked == true) return ThemePresetKind.SystemAccent;
        if (SkinAmberRadio.IsChecked == true) return ThemePresetKind.WarmAmber;
        return ThemePresetKind.ClaudeTerracotta;
    }

    private void UiModeRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        _settings.UiMode = SelectedUiModeFromRadios();
        WarmPaperPalette.ApplyToResources(System.Windows.Application.Current.Resources, _settings.UiMode);
        ApplyNativeTitleBarTheme();
        AppSettingsService.Save(_settings);
        BuildSkinCards();
        ShowToast("已保存");
    }

    private void ApplyNativeTitleBarTheme()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == nint.Zero) return;
            var dark = WarmPaperPalette.IsDarkActive(_settings.UiMode);
            int useDark = dark ? 1 : 0;
            _ = DwmSetWindowAttribute(hwnd, 20, ref useDark, sizeof(int)); // DWMWA_USE_IMMERSIVE_DARK_MODE
            int captionColor = dark ? 0x00131414 : 0x00EDF4F5; // COLORREF 0x00BBGGRR
            _ = DwmSetWindowAttribute(hwnd, 35, ref captionColor, sizeof(int));
        }
        catch
        {
            // 非 Win11 自动忽略
        }
    }

    private void ScheduleSkinApply()
    {
        _skinPending = true;
        _skinApplyTimer.Stop();
        _skinApplyTimer.Start();
        SetStatus("有改动，马上保存…", isBusy: true);
    }

    private void SkinControl_Changed(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        UpdateSkinPreview();
        ScheduleSkinApply();
    }

    private void SkinSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!IsLoaded) return;
        CornerValueText.Text = $"{(int)CornerSlider.Value} px";
        if (_isBindingUi) return;
        UpdateSkinPreview();
        ScheduleSkinApply();
    }

    private void FontStep_Click(object sender, RoutedEventArgs e)
    {
        var next = Math.Clamp(_fontPoint + (ReferenceEquals(sender, FontPlusBtn) ? 1 : -1), 10, 18);
        if (next == _fontPoint) return;
        _fontPoint = next;
        FontValueText.Text = $"{_fontPoint} pt";
        UpdateSkinPreview();
        ScheduleSkinApply();
    }

    private async Task ApplySkinNowAsync()
    {
        if (!_skinPending) return;
        var ran = await RunExclusiveAsync(async () =>
        {
            _skinPending = false;
            try
            {
                ShowToast("已保存，正在部署…", sticky: true);
                SetStatus("正在部署皮肤…", isBusy: true, progressPercent: -1);
                _settings.WeaselTheme = SelectedSkinFromRadios();
                _settings.Density = DensityStandardRadio.IsChecked == true ? LayoutDensity.Standard : LayoutDensity.Compact;
                _settings.FontPoint = _fontPoint;
                _settings.CornerRadius = Math.Clamp((int)CornerSlider.Value, 0, 14);
                _settings.AutoFollowSystemAccentTray = AutoAccentTrayCheck.IsChecked == true;
                _settings.DisableAppForcedAscii = DisableForcedAsciiCheck.IsChecked == true;

                AppSettingsService.Save(_settings);
                WeaselSkinService.WriteWeaselCustomYaml(_settings);
                var code = await RimeProcessService.DeployAsync();
                SetStatus(code == 0 ? "已生效" : $"已保存，部署返回码 {code}", isError: code != 0, detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast(code == 0 ? "已生效" : $"部署返回码 {code}", isError: code != 0);
            }
            catch (Exception ex)
            {
                SetStatus($"保存皮肤失败：{ex.Message}", isError: true);
                ShowToast("保存失败", isError: true);
            }
        });
        if (!ran) { _skinPending = true; _skinApplyTimer.Interval = TimeSpan.FromMilliseconds(1500); _skinApplyTimer.Start(); }
        else _skinApplyTimer.Interval = TimeSpan.FromMilliseconds(700);
    }

    private void RefreshSystemColor_Click(object sender, RoutedEventArgs e)
    {
        BuildSkinCards();
        UpdateSkinPreview();
        if (SelectedSkinFromRadios() == ThemePresetKind.SystemAccent)
            ScheduleSkinApply();
        else
            ShowToast("已重新取色");
    }

    private static SolidColorBrush Frozen(Color c)
    {
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }

    /// <summary>皮肤卡片：每张卡片本身就是一个迷你候选窗缩略图</summary>
    private void BuildSkinCards()
    {
        var dark = WarmPaperPalette.IsDarkActive(_settings.UiMode);
        void Fill(RadioButton radio, ThemePresetKind kind, string name)
        {
            var (light, darkSpec) = WeaselSkinService.ResolveActiveSchemes(kind);
            var spec = dark ? darkSpec : light;
            var mini = new Border
            {
                Width = 150,
                CornerRadius = new CornerRadius(7),
                BorderThickness = new Thickness(1),
                Background = Frozen(spec.BackColor),
                BorderBrush = Frozen(spec.BorderColor),
                Padding = new Thickness(8, 6, 8, 7)
            };
            var col = new StackPanel();
            col.Children.Add(new TextBlock { Text = "ni'hao", FontSize = 10.5, Foreground = Frozen(spec.HilitedBackColor), Margin = new Thickness(0, 0, 0, 4) });
            var line = new StackPanel { Orientation = Orientation.Horizontal };
            line.Children.Add(new Border
            {
                CornerRadius = new CornerRadius(5),
                Background = Frozen(spec.HilitedBackColor),
                Padding = new Thickness(6, 1, 6, 2),
                Child = new TextBlock { Text = "1.你好", FontSize = 12, Foreground = Frozen(spec.HilitedTextColor) }
            });
            line.Children.Add(new TextBlock { Text = "2.拟好", FontSize = 12, Margin = new Thickness(7, 1, 0, 0), Foreground = Frozen(spec.TextColor) });
            col.Children.Add(line);
            mini.Child = col;

            var card = new StackPanel();
            card.Children.Add(mini);
            var label = new TextBlock { Text = name, FontSize = 12.5, Margin = new Thickness(2, 8, 0, 0) };
            label.SetResourceReference(TextBlock.ForegroundProperty, "WpTextSecondaryBrush");
            card.Children.Add(label);
            radio.Content = card;
            AutomationProperties_SetName(radio, name);
        }

        Fill(SkinTerracottaRadio, ThemePresetKind.ClaudeTerracotta, "陶土橙（默认）");
        Fill(SkinSystemAccentRadio, ThemePresetKind.SystemAccent, "跟随 Windows 主体色");
        Fill(SkinAmberRadio, ThemePresetKind.WarmAmber, "暖纸琥珀");
    }

    private static void AutomationProperties_SetName(DependencyObject d, string name) =>
        System.Windows.Automation.AutomationProperties.SetName(d, name);

    /// <summary>候选窗预览：拼音串 + 当前候选个数，跟着字号、圆角、间距、皮肤实时变化（浅色 / 深色并排）</summary>
    private void UpdateSkinPreview()
    {
        var accent = SystemThemeService.GetWindowsAccentColor();
        AccentSwatchDot.Fill = Frozen(accent);
        AccentHexText.Text = $"#{accent.R:X2}{accent.G:X2}{accent.B:X2}";

        var kind = SelectedSkinFromRadios();
        var (light, dark) = WeaselSkinService.ResolveActiveSchemes(kind);
        var count = PageSize9Radio.IsChecked == true ? 9 : (PageSize7Radio.IsChecked == true ? 7 : 5);
        var fontPx = Math.Clamp(_fontPoint * 1.18, 12, 22);
        var radius = Math.Clamp((int)CornerSlider.Value, 0, 14);
        var compact = DensityCompactRadio.IsChecked == true;

        CandPreviewHost.Child = BuildPreviewPair(light, dark, count, fontPx, radius, compact);
        LookPreviewHost.Child = BuildPreviewPair(light, dark, count, fontPx, radius, compact);
    }

    private FrameworkElement BuildPreviewPair(WeaselColorSchemeSpec light, WeaselColorSchemeSpec dark, int count, double fontPx, int radius, bool compact)
    {
        var root = new StackPanel();
        var head = new TextBlock { Text = "候选窗预览 · 浅色 / 深色", FontSize = 12.5, Margin = new Thickness(0, 0, 0, 10) };
        head.SetResourceReference(TextBlock.ForegroundProperty, "WpMutedBrush");
        root.Children.Add(head);
        var wrap = new WrapPanel();
        wrap.Children.Add(BuildCandidateWindow(light, count, fontPx, radius, compact));
        wrap.Children.Add(BuildCandidateWindow(dark, count, fontPx, radius, compact));
        root.Children.Add(wrap);
        return root;
    }

    private static Border BuildCandidateWindow(WeaselColorSchemeSpec spec, int count, double fontPx, int radius, bool compact)
    {
        var box = new Border
        {
            Background = Frozen(spec.BackColor),
            BorderBrush = Frozen(spec.BorderColor),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = compact ? new Thickness(10, 6, 10, 7) : new Thickness(13, 9, 13, 10),
            Margin = new Thickness(0, 0, 12, 8),
            HorizontalAlignment = HorizontalAlignment.Left
        };
        var col = new StackPanel();
        col.Children.Add(new TextBlock
        {
            Text = "shu'ru'fa",
            FontSize = Math.Max(10.5, fontPx - 1.5),
            Foreground = Frozen(spec.HilitedBackColor),
            Margin = new Thickness(0, 0, 0, compact ? 4 : 6)
        });
        var line = new StackPanel { Orientation = Orientation.Horizontal };
        for (var i = 0; i < count; i++)
        {
            var label = new TextBlock { Text = $"{i + 1}.", FontSize = Math.Max(10, fontPx - 2), Margin = new Thickness(0, 0, 3, 0), VerticalAlignment = VerticalAlignment.Center };
            var text = new TextBlock { Text = SampleCandidates[i], FontSize = fontPx, VerticalAlignment = VerticalAlignment.Center };
            var item = new StackPanel { Orientation = Orientation.Horizontal };
            item.Children.Add(label);
            item.Children.Add(text);
            if (i == 0)
            {
                label.Foreground = Frozen(spec.HilitedLabelColor);
                text.Foreground = Frozen(spec.HilitedTextColor);
                line.Children.Add(new Border
                {
                    Background = Frozen(spec.HilitedBackColor),
                    CornerRadius = new CornerRadius(Math.Max(2, radius - 2)),
                    Padding = compact ? new Thickness(6, 1.5, 6, 2) : new Thickness(8, 3, 8, 4),
                    Margin = new Thickness(0, 0, 8, 0),
                    Child = item
                });
            }
            else
            {
                label.Foreground = Frozen(spec.LabelColor);
                text.Foreground = Frozen(spec.TextColor);
                item.Margin = new Thickness(0, 0, 9, 0);
                line.Children.Add(item);
            }
        }
        col.Children.Add(line);
        box.Child = col;
        return box;
    }

    #endregion

    #region 同步

    private void SyncProviderRadioFromUrl(string url)
    {
        _isSyncingProvider = true;
        try
        {
            var trimmed = url.Trim().TrimEnd('/');
            if (trimmed.Equals(WebDavConfig.KoofrUrl.TrimEnd('/'), StringComparison.OrdinalIgnoreCase))
                ProviderKoofrRadio.IsChecked = true;
            else if (trimmed.Equals(WebDavConfig.JianguoyunUrl.TrimEnd('/'), StringComparison.OrdinalIgnoreCase))
                ProviderJgyRadio.IsChecked = true;
            else
                ProviderCustomRadio.IsChecked = true;
        }
        finally
        {
            _isSyncingProvider = false;
        }
    }

    private void ProviderRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || _isSyncingProvider || !IsLoaded) return;
        _isSyncingProvider = true;
        try
        {
            if (ProviderKoofrRadio.IsChecked == true)
                DavUrlBox.Text = WebDavConfig.KoofrUrl;
            else if (ProviderJgyRadio.IsChecked == true)
                DavUrlBox.Text = WebDavConfig.JianguoyunUrl;
        }
        finally
        {
            _isSyncingProvider = false;
        }
    }

    private void DavField_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isBindingUi || _isSyncingProvider || !IsLoaded) return;
        SyncProviderRadioFromUrl(DavUrlBox.Text);
    }

    private void TogglePassword_Click(object sender, RoutedEventArgs e)
    {
        _passwordVisible = !_passwordVisible;
        if (_passwordVisible)
        {
            DavPassVisibleBox.Text = DavPassBox.Password;
            DavPassBox.Visibility = Visibility.Collapsed;
            DavPassVisibleBox.Visibility = Visibility.Visible;
            TogglePassBtn.Content = "隐藏";
        }
        else
        {
            DavPassBox.Password = DavPassVisibleBox.Text;
            DavPassVisibleBox.Visibility = Visibility.Collapsed;
            DavPassBox.Visibility = Visibility.Visible;
            TogglePassBtn.Content = "显示";
        }
    }

    private WebDavConfig CollectWebDavFromUi()
    {
        _webDav.Url = DavUrlBox.Text.Trim();
        _webDav.Dir = string.IsNullOrWhiteSpace(DavDirBox.Text) ? "Ifboard" : DavDirBox.Text.Trim();
        _webDav.User = DavUserBox.Text.Trim();
        _webDav.Password = _passwordVisible ? DavPassVisibleBox.Text : DavPassBox.Password;
        return _webDav;
    }

    private void ImportLegacyWebDav_Click(object sender, RoutedEventArgs e)
    {
        var legacy = WebDavService.TryReadLegacyConfig();
        if (legacy == null)
        {
            ShowToast("没有找到可用的旧配置", isError: true);
            return;
        }

        _isBindingUi = true;
        try
        {
            DavUrlBox.Text = legacy.Url;
            DavDirBox.Text = legacy.Dir;
            DavUserBox.Text = legacy.User;
            DavPassBox.Password = legacy.Password;
            DavPassVisibleBox.Text = legacy.Password;
            SyncProviderRadioFromUrl(legacy.Url);
            WebDavService.SaveConfig(legacy);
            _webDav = legacy;
            LegacyImportBanner.Visibility = Visibility.Collapsed;
            LegacyImportBanner.Tag = false;
            ShowToast("已填入并加密保存");
        }
        finally
        {
            _isBindingUi = false;
        }
    }

    private async void TestWebDav_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                var cfg = CollectWebDavFromUi();
                WebDavService.SaveConfig(cfg);
                SetStatus("正在连接网盘…", isBusy: true, progressPercent: -1);
                var msg = await WebDavService.TestConnectionAsync(cfg);
                SetStatus(msg, detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast("已保存，连接正常");
            }
            catch (Exception ex)
            {
                SetStatus($"连接失败：{ex.Message}", isError: true);
                ShowToast("连接失败", isError: true);
            }
        });
    }

    private async void SyncNow_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                var cfg = CollectWebDavFromUi();
                WebDavService.SaveConfig(cfg);
                var prog = new Progress<string>(msg => SetStatus(msg, isBusy: true, progressPercent: -1));
                var res = await WebDavService.SyncUserDbAsync(cfg, prog);
                LastSyncText.Text = $"上次同步 {DateTime.Now:MM-dd HH:mm}";
                var others = res.OtherDevices.Count > 0 ? string.Join("、", res.OtherDevices) : "无";
                SetStatus($"同步完成：上传 {res.Uploaded} 份，合并 {res.Downloaded} 份", detail: $"其他设备：{others}");
                ShowToast($"同步完成 · 上传 {res.Uploaded} · 合并 {res.Downloaded}");
            }
            catch (Exception ex)
            {
                SetStatus($"同步失败：{ex.Message}", isError: true);
                ShowToast("同步失败", isError: true);
            }
        });
    }

    private async void BackupProfile_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                var cfg = CollectWebDavFromUi();
                WebDavService.SaveConfig(cfg);
                var prog = new Progress<string>(msg => SetStatus(msg, isBusy: true, progressPercent: -1));
                var res = await WebDavService.BackupProfileAsync(cfg, prog);
                LastSyncText.Text = $"上次同步 {DateTime.Now:MM-dd HH:mm}";
                SetStatus($"备份完成：{res.Summary}", detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast("已备份到网盘");
            }
            catch (Exception ex)
            {
                SetStatus($"备份失败：{ex.Message}", isError: true);
                ShowToast("备份失败", isError: true);
            }
        });
    }

    private async void RestoreProfile_Click(object sender, RoutedEventArgs e)
    {
        var answer = System.Windows.MessageBox.Show(
            this,
            "将从网盘取回电脑的方案、模糊音、皮肤和自定义短语，并合并所有设备的词频。\n覆盖前会在本机 Rime 目录留一份 _backup_before_restore 备份。",
            "从网盘恢复配置",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (answer != MessageBoxResult.Yes) return;

        await RunExclusiveAsync(async () =>
        {
            try
            {
                var cfg = CollectWebDavFromUi();
                WebDavService.SaveConfig(cfg);
                var prog = new Progress<string>(msg => SetStatus(msg, isBusy: true, progressPercent: -1));
                var res = await WebDavService.RestoreProfileAsync(cfg, prog);
                LoadAllStateIntoUi();
                SetStatus($"恢复完成：{res.Summary}", detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast("已从网盘恢复");
            }
            catch (Exception ex)
            {
                SetStatus($"恢复失败：{ex.Message}", isError: true);
                ShowToast("恢复失败", isError: true);
            }
        });
    }

    private bool _clipStatusHooked;

    public void RefreshClipboardSyncUi()
    {
        var on = AppSettingsService.Load().ClipboardSyncEnabled;
        ClipboardSyncCheck.IsChecked = on;
        UpdateClipStatus(on ? ClipboardSyncService.Status : "未开启", on);
        if (!_clipStatusHooked)
        {
            _clipStatusHooked = true;
            ClipboardSyncService.StatusChanged += s => Dispatcher.BeginInvoke(() =>
                UpdateClipStatus(s, ClipboardSyncCheck.IsChecked == true));
        }
    }

    private void UpdateClipStatus(string text, bool on)
    {
        ClipSyncStatusText.Text = text;
        string key;
        if (!on) key = "WpMutedBrush";
        else if (text.Contains("失败") || text.Contains("错误")) key = "WpErrorBrush";
        else if (text.Contains("连接中") || text.Contains("正在")) key = "WpAmberBrush";
        else key = "WpSageBrush";
        ClipSyncDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, key);
    }

    private async void ClipboardSyncCheck_Click(object sender, RoutedEventArgs e)
    {
        if (System.Windows.Application.Current is App app)
            await app.ToggleClipboardSyncAsync();
        RefreshClipboardSyncUi();
    }

    #endregion

    #region 词库：万象语法模型与短语

    private void SetGrammarBadge(string text, string bgKey, string fgKey)
    {
        GrammarBadge.SetResourceReference(Border.BackgroundProperty, bgKey);
        GrammarBadgeText.SetResourceReference(TextBlock.ForegroundProperty, fgKey);
        GrammarBadgeText.Text = text;
        CommonGrammarBadge.SetResourceReference(Border.BackgroundProperty, bgKey);
        CommonGrammarBadgeText.SetResourceReference(TextBlock.ForegroundProperty, fgKey);
        CommonGrammarBadgeText.Text = text;
    }

    private void RefreshLocalGrammarCard()
    {
        var valid = GrammarModelService.IsLocalModelValid();
        var size = GrammarModelService.GetLocalModelSizeBytes();
        var partSize = GrammarModelService.GetPendingPartSizeBytes();
        var meta = GrammarModelService.ReadLocalMeta();

        if (valid)
        {
            SetGrammarBadge($"已就绪 · {size / 1024d / 1024d:F0} MB", "WpSageSoftBrush", "WpSageBrush");
            var shaTip = meta?.Sha256 is { Length: >= 12 } s ? $"SHA-256 {s[..12]}…" : "随安装包预置";
            var dateTip = !string.IsNullOrWhiteSpace(meta?.UpdatedAt) ? $" · 构建于 {meta.UpdatedAt}" : "";
            GrammarDetailText.Text = $"本地模型校验通过（{shaTip}{dateTip}）。";
            DeleteGrammarBtn.IsEnabled = true;
            DownloadGrammarBtn.Content = "重新下载";
        }
        else if (partSize > 0)
        {
            SetGrammarBadge($"已下载 {partSize / 1024d / 1024d:F0} MB", "WpAmberSoftBrush", "WpAmberBrush");
            GrammarDetailText.Text = "有一份没下完的缓存，点「继续下载」会接着传，不用从头再来。";
            DeleteGrammarBtn.IsEnabled = true;
            DownloadGrammarBtn.Content = "继续下载";
        }
        else
        {
            SetGrammarBadge("未安装", "WpErrorSoftBrush", "WpErrorBrush");
            GrammarDetailText.Text = "还没有安装 wanxiang-lts-zh-hans.gram，可以从官方 GitHub 下载（约 398 MB）。";
            DeleteGrammarBtn.IsEnabled = false;
            DownloadGrammarBtn.Content = "下载语法模型";
        }
    }

    private async void CheckGrammar_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在查询最新的语法模型…", isBusy: true, progressPercent: -1);
                _cachedRemoteGrammar = await GrammarModelService.FetchRemoteInfoAsync();
                var hasUpd = GrammarModelService.HasUpdateAvailable(_cachedRemoteGrammar);
                var sizeMb = _cachedRemoteGrammar.SizeBytes / 1024d / 1024d;
                if (hasUpd)
                {
                    GrammarDetailText.Text = $"有新版本：{sizeMb:F0} MB，更新于 {_cachedRemoteGrammar.UpdatedAt}（{_cachedRemoteGrammar.Source}）。";
                    DownloadGrammarBtn.Content = "更新到最新版";
                    SetStatus("语法模型有新版本", detail: _cachedRemoteGrammar.UpdatedAt);
                    ShowToast("有新版本");
                }
                else
                {
                    GrammarDetailText.Text = $"已是最新版（{sizeMb:F0} MB，更新于 {_cachedRemoteGrammar.UpdatedAt}）。";
                    SetStatus("语法模型已是最新");
                    ShowToast("已是最新版");
                }
            }
            catch (Exception ex)
            {
                SetStatus($"检查失败：{ex.Message}", isError: true);
                ShowToast("检查失败", isError: true);
            }
        });
    }

    private async void DownloadGrammar_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            _grammarDownloadCts?.Cancel();
            _grammarDownloadCts = new CancellationTokenSource();

            DownloadGrammarBtn.IsEnabled = false;
            CheckGrammarBtn.IsEnabled = false;
            DeleteGrammarBtn.IsEnabled = false;
            CancelGrammarBtn.Visibility = Visibility.Visible;

            try
            {
                var progress = new Progress<(long Downloaded, long Total, double BytesPerSec, string Stage)>(p =>
                {
                    var pct = p.Total > 0 ? Math.Clamp(p.Downloaded * 100.0 / p.Total, 0, 100) : -1;
                    var speedTxt = p.BytesPerSec > 0 ? $"{p.BytesPerSec / 1024d / 1024d:F1} MB/s" : "";
                    var ratioTxt = p.Total > 0 ? $"已下载 {p.Downloaded / 1024d / 1024d:N0} / {p.Total / 1024d / 1024d:N0} MB" : "";
                    SetStatus(string.IsNullOrEmpty(ratioTxt) ? p.Stage : ratioTxt, isBusy: true, progressPercent: pct, detail: $"{p.Stage} {speedTxt}".Trim());
                    if (!string.IsNullOrEmpty(ratioTxt)) GrammarDetailText.Text = $"{ratioTxt}　{speedTxt}";
                });

                await GrammarModelService.DownloadAndInstallAsync(_cachedRemoteGrammar, progress, _grammarDownloadCts.Token);
                RefreshLocalGrammarCard();
                SetStatus("语法模型已安装并生效", detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast("语法模型已就绪");
                if (!IsVisible && System.Windows.Application.Current is App okApp)
                    okApp.ShowTrayNotification("万象语法模型已就绪", "下载、校验和部署都已完成。");
            }
            catch (OperationCanceledException)
            {
                RefreshLocalGrammarCard();
                SetStatus("下载已暂停，进度已保留");
                ShowToast("已暂停，进度已保留");
            }
            catch (Exception ex)
            {
                RefreshLocalGrammarCard();
                SetStatus($"下载失败：{ex.Message}", isError: true);
                ShowToast("下载失败", isError: true);
                if (!IsVisible && System.Windows.Application.Current is App errApp)
                    errApp.ShowTrayNotification("语法模型下载中断", ex.Message, isError: true);
            }
            finally
            {
                DownloadGrammarBtn.IsEnabled = true;
                CheckGrammarBtn.IsEnabled = true;
                DeleteGrammarBtn.IsEnabled = GrammarModelService.IsLocalModelValid() || GrammarModelService.GetPendingPartSizeBytes() > 0;
                CancelGrammarBtn.Visibility = Visibility.Collapsed;
            }
        });
    }

    private void CancelGrammar_Click(object sender, RoutedEventArgs e) => _grammarDownloadCts?.Cancel();

    private async void DeleteGrammar_Click(object sender, RoutedEventArgs e)
    {
        var answer = System.Windows.MessageBox.Show(
            this,
            "会暂停输入法服务，删除本地语法模型（约 398 MB）和没下完的缓存，然后重新部署。删除后整句首选会变差，需要时可以再下载。",
            "卸载语法模型",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;

        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在卸载语法模型…", isBusy: true, progressPercent: -1);
                await GrammarModelService.DeleteLocalModelAsync();
                RefreshLocalGrammarCard();
                SetStatus("语法模型已卸载");
                ShowToast("已卸载");
            }
            catch (Exception ex)
            {
                SetStatus($"卸载失败：{ex.Message}", isError: true);
                ShowToast("卸载失败", isError: true);
            }
        });
    }

    private void OpenRimeFolder_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(Paths.RimeUserDir);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Paths.RimeUserDir}\"") { UseShellExecute = true });
    }

    private void EditFullPhrase_Click(object sender, RoutedEventArgs e) => OpenPhraseFile("custom_phrase.txt");

    private void EditDoublePhrase_Click(object sender, RoutedEventArgs e) => OpenPhraseFile("custom_phrase_double.txt");

    private static void OpenPhraseFile(string fileName)
    {
        RimeConfigService.EnsureCustomPhrasePlaceholders();
        var path = Path.Combine(Paths.RimeUserDir, fileName);
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    #endregion

    #region 高级与关于

    private async void RemoveMsPinyin_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在移除微软拼音…", isBusy: true, progressPercent: -1);
                var removed = await RimeProcessService.RemoveMicrosoftPinyinAsync();
                await RefreshSystemAsyncState();
                SetStatus(removed ? "已移除微软拼音" : "没有检测到微软拼音");
                ShowToast(removed ? "已移除微软拼音" : "没有检测到微软拼音");
            }
            catch (Exception ex)
            {
                SetStatus($"移除失败：{ex.Message}", isError: true);
                ShowToast("移除失败", isError: true);
            }
        });
    }

    private async void RestartWeasel_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在重启输入法服务…", isBusy: true, progressPercent: -1);
                await RimeProcessService.StopWeaselAsync();
                await RimeProcessService.EnsureWeaselServerRunningAsync();
                SetStatus("输入法服务已重启", detail: DateTime.Now.ToString("HH:mm:ss"));
                ShowToast("已重启");
            }
            catch (Exception ex)
            {
                SetStatus($"重启失败：{ex.Message}", isError: true);
                ShowToast("重启失败", isError: true);
            }
        });
    }

    private async void CheckAppUpdate_Click(object sender, RoutedEventArgs e)
    {
        string? pendingUrl = null;
        string? pendingMsg = null;

        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在检查新版本…", isBusy: true, progressPercent: -1);
                var (_, msg, url) = await WebDavService.CheckAppReleaseAsync();
                AppUpdateInfoText.Text = msg;
                SetStatus(msg);
                pendingUrl = url;
                pendingMsg = msg;
                if (string.IsNullOrEmpty(url)) ShowToast("已是最新版");
            }
            catch (Exception ex)
            {
                SetStatus($"检查失败：{ex.Message}", isError: true);
                ShowToast("检查失败", isError: true);
            }
        });

        if (!string.IsNullOrEmpty(pendingUrl))
        {
            var open = System.Windows.MessageBox.Show(this, $"{pendingMsg}\n\n要打开 GitHub 发布页看看吗？", "Ifboard 新版本",
                MessageBoxButton.YesNo, MessageBoxImage.Information);
            if (open == MessageBoxResult.Yes)
                Process.Start(new ProcessStartInfo(pendingUrl) { UseShellExecute = true });
        }
    }

    private void OpenGithub_Click(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(WebDavService.ProjectHomeUrl) { UseShellExecute = true });

    #endregion

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        // 关窗前把还没来得及部署的改动立刻写下去
        if (!e.Cancel && (_rimePending || _skinPending))
        {
            _rimeApplyTimer.Stop();
            _skinApplyTimer.Stop();
            if (_rimePending) _ = ApplyRimeNowAsync();
            if (_skinPending) _ = ApplySkinNowAsync();
        }
        base.OnClosing(e);
    }
}
