using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using IfboardWin.Models;
using IfboardWin.Services;
using IfboardWin.Theme;

namespace IfboardWin;

public partial class MainWindow : Window
{
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attr, ref int attrValue, int attrSize);

    private AppSettings _settings = new();
    private RimePreferences _rimePrefs = new();
    private WebDavConfig _webDav = new();
    private GrammarRemoteInfo? _cachedRemoteGrammar;
    private CancellationTokenSource? _grammarDownloadCts;

    private bool _isBindingUi;
    private bool _isSyncingProvider;
    private bool _passwordVisible;
    private bool _isOperationBusy;

    public bool IsOperationBusy => _isOperationBusy;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += async (_, _) =>
        {
            LoadAllStateIntoUi();
            await RefreshSystemAsyncState();
        };
    }

    public void RefreshExternalState()
    {
        LoadAllStateIntoUi();
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
            DeviceBadgeText.Text = string.IsNullOrWhiteSpace(devId)
                ? $"公开版 v{Paths.AppVersion}"
                : $"公开版 v{Paths.AppVersion} · {devId}";

            // WebDAV
            DavUrlBox.Text = _webDav.Url;
            DavDirBox.Text = _webDav.Dir;
            DavUserBox.Text = _webDav.User;
            DavPassBox.Password = _webDav.Password;
            DavPassVisibleBox.Text = _webDav.Password;
            SyncProviderRadioFromUrl(_webDav.Url);

            LastSyncText.Text = _webDav.LastSyncUnixMs > 0
                ? $"上次同步：{DateTimeOffset.FromUnixTimeMilliseconds(_webDav.LastSyncUnixMs).LocalDateTime:MM-dd HH:mm}"
                : "上次同步：尚未同步";

            // 仅当本机尚无 WebDAV 密码且存在旧版配置文件时提示一键导入
            var hasSavedPass = !string.IsNullOrWhiteSpace(_webDav.User) && !string.IsNullOrWhiteSpace(_webDav.Password);
            LegacyImportBanner.Visibility = !hasSavedPass && WebDavService.TryReadLegacyConfig() != null
                ? Visibility.Visible
                : Visibility.Collapsed;

            // Rime 输入偏好
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

            // 外观设定
            UiAutoRadio.IsChecked = _settings.UiMode == UiThemeMode.Auto;
            UiLightRadio.IsChecked = _settings.UiMode == UiThemeMode.Light;
            UiDarkRadio.IsChecked = _settings.UiMode == UiThemeMode.Dark;

            SkinTerracottaRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.ClaudeTerracotta;
            SkinSystemAccentRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.SystemAccent;
            SkinAmberRadio.IsChecked = _settings.WeaselTheme == ThemePresetKind.WarmAmber;

            DensityCompactRadio.IsChecked = _settings.Density == LayoutDensity.Compact;
            DensityStandardRadio.IsChecked = _settings.Density == LayoutDensity.Standard;

            FontSlider.Value = Math.Clamp(_settings.FontPoint, 10, 18);
            FontValueText.Text = $"{(int)FontSlider.Value} pt";
            CornerSlider.Value = Math.Clamp(_settings.CornerRadius, 0, 14);
            CornerValueText.Text = $"{(int)CornerSlider.Value} px";

            AutoAccentTrayCheck.IsChecked = _settings.AutoFollowSystemAccentTray;
            DisableForcedAsciiCheck.IsChecked = _settings.DisableAppForcedAscii;

            RefreshLocalGrammarCard();
            UpdateSkinPreview();
            InputDirtyBadge.Visibility = Visibility.Collapsed;
            SkinDirtyBadge.Visibility = Visibility.Collapsed;
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
            MsPinyinStatusText.Text = "检测到系统当前仍保留「微软拼音输入法」，建议移除以避免 Ctrl+Space / Win+Space 抢占中文输入状态。";
            RemoveMsPinyinBtn.IsEnabled = true;
            RemoveMsPinyinBtn.Content = "一键移除微软拼音";
        }
        else
        {
            MsPinyinStatusText.Text = "系统简体中文下已仅保留小狼毫输入法，切窗干净无冲突。";
            RemoveMsPinyinBtn.IsEnabled = false;
            RemoveMsPinyinBtn.Content = "已精简干净";
        }
    }

    #region Navigation & Status Helpers

    private void NavTab_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        PageSync.Visibility = NavSyncRadio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PageInput.Visibility = NavInputRadio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PageSkin.Visibility = NavSkinRadio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        PageAbout.Visibility = NavAboutRadio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
    }

    private void SetStatus(string message, bool isBusy = false, bool isError = false, double? progressPercent = null, string detail = "")
    {
        StatusBannerText.Text = message;
        StatusDetailText.Text = detail;

        var dotBrushKey = isError ? "WpErrorBrush" : (isBusy ? "WpBrandBrush" : "WpSageBrush");
        StatusDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, dotBrushKey);

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

    private async Task RunExclusiveAsync(Func<Task> action)
    {
        if (_isOperationBusy || !await App.OperationGate.WaitAsync(0))
        {
            SetStatus("当前已有部署或同步任务正在运行，请稍候...", isBusy: true);
            return;
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
        ApplyRimeBtn.IsEnabled = enabled;
        ApplySkinBtn.IsEnabled = enabled;
        RemoveMsPinyinBtn.IsEnabled = enabled;
        RestartWeaselBtn.IsEnabled = enabled;
        CheckAppUpdateBtn.IsEnabled = enabled;
    }

    private void MinimizeToTray_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private async void QuickDeploy_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在重新部署小狼毫引擎（WeaselDeployer /deploy）...", isBusy: true, progressPercent: -1);
                var code = await RimeProcessService.DeployAsync();
                SetStatus(
                    code == 0 ? "小狼毫部署完成，配置与词库已热重载生效。" : $"小狼毫部署结束（返回码 {code}）。",
                    isError: code != 0,
                    detail: DateTime.Now.ToString("HH:mm:ss")
                );
            }
            catch (Exception ex)
            {
                SetStatus($"部署失败：{ex.Message}", isError: true);
            }
        });
    }

    #endregion

    #region Tab 1: WebDAV & Grammar Model

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
            SetStatus("未找到可用的旧版 WebDAV 配置。", isError: true);
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
            SetStatus("已导入旧版 WebDAV 配置并转存为本机 DPAPI 加密凭据。");
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
                SetStatus("正在连接 WebDAV 云盘验证账号与目录...", isBusy: true, progressPercent: -1);
                var msg = await WebDavService.TestConnectionAsync(cfg);
                SetStatus(msg, detail: DateTime.Now.ToString("HH:mm:ss"));
            }
            catch (Exception ex)
            {
                SetStatus($"连接测试失败：{ex.Message}", isError: true);
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
                LastSyncText.Text = $"上次同步：{DateTime.Now:MM-dd HH:mm}";
                var others = res.OtherDevices.Count > 0 ? string.Join("、", res.OtherDevices) : "无";
                SetStatus(
                    $"双向词库同步完成：上传 {res.Uploaded} 份，合并下载 {res.Downloaded} 份（云端其他设备：{others}）。",
                    detail: DateTime.Now.ToString("HH:mm:ss")
                );
            }
            catch (Exception ex)
            {
                SetStatus($"同步失败：{ex.Message}", isError: true);
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
                LastSyncText.Text = $"上次同步：{DateTime.Now:MM-dd HH:mm}";
                SetStatus($"完整电脑备份成功：{res.Summary}。", detail: DateTime.Now.ToString("HH:mm:ss"));
            }
            catch (Exception ex)
            {
                SetStatus($"备份失败：{ex.Message}", isError: true);
            }
        });
    }

    private async void RestoreProfile_Click(object sender, RoutedEventArgs e)
    {
        var answer = System.Windows.MessageBox.Show(
            this,
            "即将从 WebDAV 拉取电脑端个性化方案、模糊音、皮肤、自定义短语，并自动合并云端全设备词频。\n（覆盖前会自动在本机 Rime 目录创建 _backup_before_restore 安全备份。）\n\n是否继续恢复？",
            "从 WebDAV 一键恢复配置",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question
        );
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
                SetStatus($"个人配置与词库恢复完成：{res.Summary}。", detail: DateTime.Now.ToString("HH:mm:ss"));
            }
            catch (Exception ex)
            {
                SetStatus($"恢复失败：{ex.Message}", isError: true);
            }
        });
    }

    private void RefreshLocalGrammarCard()
    {
        var valid = GrammarModelService.IsLocalModelValid();
        var size = GrammarModelService.GetLocalModelSizeBytes();
        var partSize = GrammarModelService.GetPendingPartSizeBytes();
        var meta = GrammarModelService.ReadLocalMeta();

        if (valid)
        {
            GrammarBadge.SetResourceReference(Border.BackgroundProperty, "WpSageSoftBrush");
            GrammarBadgeText.SetResourceReference(TextBlock.ForegroundProperty, "WpSageBrush");
            GrammarBadgeText.Text = $"已就绪 · {size / 1024d / 1024d:F1} MB";

            var shaTip = meta?.Sha256 is { Length: >= 12 } s ? $"SHA-256: {s[..12]}..." : "随公开版离线预置";
            var dateTip = !string.IsNullOrWhiteSpace(meta?.UpdatedAt) ? $"构建时间：{meta.UpdatedAt}" : "";
            GrammarDetailText.Text = $"本地模型校验通过（Rime::Grammar/ 头正常 · {shaTip}）{dateTip}".Trim();
            DeleteGrammarBtn.IsEnabled = true;
            DownloadGrammarBtn.Content = "检查并覆盖更新模型";
        }
        else if (partSize > 0)
        {
            GrammarBadge.SetResourceReference(Border.BackgroundProperty, "WpBrandSoftBrush");
            GrammarBadgeText.SetResourceReference(TextBlock.ForegroundProperty, "WpBrandInkBrush");
            GrammarBadgeText.Text = $"断点缓存 {partSize / 1024d / 1024d:F1} MB";
            GrammarDetailText.Text = "检测到未完成的下载缓存，点击右侧「继续断点下载」即可接着传输，无需从头再来。";
            DeleteGrammarBtn.IsEnabled = true;
            DownloadGrammarBtn.Content = "继续断点下载";
        }
        else
        {
            GrammarBadge.SetResourceReference(Border.BackgroundProperty, "WpErrorSoftBrush");
            GrammarBadgeText.SetResourceReference(TextBlock.ForegroundProperty, "WpErrorBrush");
            GrammarBadgeText.Text = "未安装";
            GrammarDetailText.Text = "尚未安装 wanxiang-lts-zh-hans.gram。点击下方可从官方 GitHub LTS 仓库自动拉取（约 398 MB，支持断点续传）。";
            DeleteGrammarBtn.IsEnabled = false;
            DownloadGrammarBtn.Content = "下载并安装语法模型";
        }
    }

    private async void CheckGrammar_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在查询 GitHub amzxyz/rime-wanxiang-data LTS 仓库...", isBusy: true, progressPercent: -1);
                _cachedRemoteGrammar = await GrammarModelService.FetchRemoteInfoAsync();
                var hasUpd = GrammarModelService.HasUpdateAvailable(_cachedRemoteGrammar);
                var sizeMb = _cachedRemoteGrammar.SizeBytes / 1024d / 1024d;
                if (hasUpd)
                {
                    GrammarDetailText.Text =
                        $"云端最新 LTS：{sizeMb:F1} MB · 更新于 {_cachedRemoteGrammar.UpdatedAt}\n建议点击「下载 / 更新语法模型」获取新版权重。";
                    DownloadGrammarBtn.Content = "更新至最新 LTS";
                    SetStatus($"检测到新版或未安装语法模型（{sizeMb:F1} MB）。", detail: _cachedRemoteGrammar.UpdatedAt);
                }
                else
                {
                    GrammarDetailText.Text =
                        $"本地语法模型与云端 LTS 一致（{sizeMb:F1} MB · 更新于 {_cachedRemoteGrammar.UpdatedAt}），无需更新。";
                    SetStatus("万象语法模型已是最新 LTS 版本。");
                }
            }
            catch (Exception ex)
            {
                SetStatus($"检查更新失败：{ex.Message}", isError: true);
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
                    var ratioTxt = p.Total > 0
                        ? $"{p.Downloaded / 1024d / 1024d:F1} / {p.Total / 1024d / 1024d:F1} MB"
                        : "";
                    SetStatus(p.Stage, isBusy: true, progressPercent: pct, detail: $"{ratioTxt} {speedTxt}".Trim());
                });

                await GrammarModelService.DownloadAndInstallAsync(_cachedRemoteGrammar, progress, _grammarDownloadCts.Token);
                RefreshLocalGrammarCard();
                SetStatus("万象语法模型已安装校验完成并部署生效。", detail: DateTime.Now.ToString("HH:mm:ss"));
            }
            catch (OperationCanceledException)
            {
                RefreshLocalGrammarCard();
                SetStatus("下载已暂停，已保留 .part 断点续传缓存。");
            }
            catch (Exception ex)
            {
                RefreshLocalGrammarCard();
                SetStatus($"下载/安装语法模型失败：{ex.Message}", isError: true);
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

    private void CancelGrammar_Click(object sender, RoutedEventArgs e)
    {
        _grammarDownloadCts?.Cancel();
    }

    private async void DeleteGrammar_Click(object sender, RoutedEventArgs e)
    {
        var answer = System.Windows.MessageBox.Show(
            this,
            "将临时停止小狼毫服务，删除本地 wanxiang-lts-zh-hans.gram 模型（及未完成缓存）并重新部署，可立即释放约 398 MB 磁盘空间。\n\n确认卸载吗？",
            "卸载语法模型",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (answer != MessageBoxResult.Yes) return;

        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在卸载语法模型并重建部署...", isBusy: true, progressPercent: -1);
                await GrammarModelService.DeleteLocalModelAsync();
                RefreshLocalGrammarCard();
                SetStatus("已卸载语法模型并重新部署小狼毫。");
            }
            catch (Exception ex)
            {
                SetStatus($"卸载失败：{ex.Message}", isError: true);
            }
        });
    }

    #endregion

    #region Tab 2: Rime Input Preferences

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
        GrammarEnabled = GrammarEnabledCheck.IsChecked == true
    };

    private void InputControl_Changed(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        InputDirtyBadge.Visibility = Visibility.Visible;
    }

    private async void ApplyRimeConfig_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在写入输入方案、模糊音与快捷键并部署...", isBusy: true, progressPercent: -1);
                _rimePrefs = CollectRimePrefsFromUi();
                RimeConfigService.ApplyPreferences(_rimePrefs);
                var code = await RimeProcessService.DeployAsync();
                if (code == 0)
                    InputDirtyBadge.Visibility = Visibility.Collapsed;
                SetStatus(
                    code == 0 ? "输入方案设置已写入并成功部署。" : $"配置已写入，部署返回码 {code}。",
                    isError: code != 0,
                    detail: DateTime.Now.ToString("HH:mm:ss")
                );
            }
            catch (Exception ex)
            {
                SetStatus($"保存输入方案失败：{ex.Message}", isError: true);
            }
        });
    }

    private void ReloadState_Click(object sender, RoutedEventArgs e)
    {
        LoadAllStateIntoUi();
        SetStatus("已从磁盘 YAML 与设置文件重新加载当前状态。");
    }

    #endregion

    #region Tab 3: Appearance & Skin Preview

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
        SetStatus("控制中心暖纸界面明暗模式已更新。");
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
            // DWMWA_CAPTION_COLOR expects COLORREF 0x00BBGGRR
            int captionColor = dark ? 0x00131414 : 0x00EDF4F5;
            _ = DwmSetWindowAttribute(hwnd, 35, ref captionColor, sizeof(int));
        }
        catch
        {
            // 非 Win11 或旧系统自动忽略
        }
    }

    private void SkinControl_Changed(object sender, RoutedEventArgs e)
    {
        if (_isBindingUi || !IsLoaded) return;
        SkinDirtyBadge.Visibility = Visibility.Visible;
        UpdateSkinPreview();
    }

    private void SkinSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!IsLoaded) return;
        FontValueText.Text = $"{(int)FontSlider.Value} pt";
        CornerValueText.Text = $"{(int)CornerSlider.Value} px";
        if (!_isBindingUi)
        {
            SkinDirtyBadge.Visibility = Visibility.Visible;
            UpdateSkinPreview();
        }
    }

    private static SolidColorBrush FrozenBrush(System.Windows.Media.Color c)
    {
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }

    private void UpdateSkinPreview()
    {
        var accent = SystemThemeService.GetWindowsAccentColor();
        AccentSwatchDot.Fill = FrozenBrush(accent);
        AccentHexText.Text = $"当前系统主体色 #{accent.R:X2}{accent.G:X2}{accent.B:X2}";

        var kind = SelectedSkinFromRadios();
        var (light, dark) = WeaselSkinService.ResolveActiveSchemes(kind);

        var fontPt = Math.Clamp((int)FontSlider.Value, 10, 18);
        var radius = Math.Clamp((int)CornerSlider.Value, 0, 14);
        var innerRadius = Math.Max(2, radius - 2);
        var isCompact = DensityCompactRadio.IsChecked == true;
        var scale = Math.Clamp(fontPt * 1.18, 12, 22);

        ApplySchemeToPreviewBox(
            light, scale, radius, innerRadius, isCompact,
            PreviewLightBox, PreviewLightPreedit, PreviewLightHilitePill,
            PreviewLightHiliteLbl, PreviewLightHiliteTxt, PreviewLightCand2, PreviewLightCand3
        );
        ApplySchemeToPreviewBox(
            dark, scale, radius, innerRadius, isCompact,
            PreviewDarkBox, PreviewDarkPreedit, PreviewDarkHilitePill,
            PreviewDarkHiliteLbl, PreviewDarkHiliteTxt, PreviewDarkCand2, PreviewDarkCand3
        );
    }

    private static void ApplySchemeToPreviewBox(
        WeaselColorSchemeSpec spec,
        double fontPx,
        double radius,
        double innerRadius,
        bool compact,
        Border box,
        TextBlock preedit,
        Border hilitePill,
        TextBlock hiliteLbl,
        TextBlock hiliteTxt,
        TextBlock cand2,
        TextBlock cand3)
    {
        box.Background = FrozenBrush(spec.BackColor);
        box.BorderBrush = FrozenBrush(spec.BorderColor);
        box.CornerRadius = new CornerRadius(radius);
        box.Padding = compact ? new Thickness(10, 6, 10, 6) : new Thickness(13, 9, 13, 9);

        var accentBrush = FrozenBrush(spec.HilitedBackColor);
        preedit.Foreground = accentBrush;
        preedit.FontSize = Math.Max(10.5, fontPx - 1.5);

        hilitePill.Background = accentBrush;
        hilitePill.CornerRadius = new CornerRadius(innerRadius);
        hilitePill.Padding = compact ? new Thickness(7, 2.5, 7, 2.5) : new Thickness(9, 4, 9, 4);

        hiliteLbl.Foreground = FrozenBrush(spec.HilitedLabelColor);
        hiliteLbl.FontSize = Math.Max(10, fontPx - 2);
        hiliteTxt.Foreground = FrozenBrush(spec.HilitedTextColor);
        hiliteTxt.FontSize = fontPx;

        var textBrush = FrozenBrush(spec.TextColor);
        cand2.Foreground = textBrush;
        cand2.FontSize = fontPx;
        cand3.Foreground = textBrush;
        cand3.FontSize = fontPx;
    }

    private async void ApplySkin_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在写入小狼毫配色皮肤方案并重新部署...", isBusy: true, progressPercent: -1);
                _settings.UiMode = SelectedUiModeFromRadios();
                _settings.WeaselTheme = SelectedSkinFromRadios();
                _settings.Density = DensityStandardRadio.IsChecked == true ? LayoutDensity.Standard : LayoutDensity.Compact;
                _settings.FontPoint = Math.Clamp((int)FontSlider.Value, 10, 18);
                _settings.CornerRadius = Math.Clamp((int)CornerSlider.Value, 0, 14);
                _settings.AutoFollowSystemAccentTray = AutoAccentTrayCheck.IsChecked == true;
                _settings.DisableAppForcedAscii = DisableForcedAsciiCheck.IsChecked == true;

                AppSettingsService.Save(_settings);
                WeaselSkinService.WriteWeaselCustomYaml(_settings);
                UpdateSkinPreview();

                var code = await RimeProcessService.DeployAsync();
                if (code == 0)
                    SkinDirtyBadge.Visibility = Visibility.Collapsed;
                SetStatus(
                    code == 0 ? "小狼毫皮肤与排版参数已保存并热生效！" : $"皮肤已保存，部署返回码 {code}。",
                    isError: code != 0,
                    detail: DateTime.Now.ToString("HH:mm:ss")
                );
            }
            catch (Exception ex)
            {
                SetStatus($"应用皮肤失败：{ex.Message}", isError: true);
            }
        });
    }

    private async void RefreshSystemColor_Click(object sender, RoutedEventArgs e)
    {
        UpdateSkinPreview();
        if (SelectedSkinFromRadios() == ThemePresetKind.SystemAccent)
        {
            ApplySkin_Click(sender, e);
        }
        else
        {
            await Task.CompletedTask;
            SetStatus("已重新采样当前 Windows 系统主体色，选择「跟随 Windows 主体色」即可应用。");
        }
    }

    #endregion

    #region Tab 4: Tools & About

    private async void RemoveMsPinyin_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在从 Windows 简体中文语言列表移除微软拼音...", isBusy: true, progressPercent: -1);
                var removed = await RimeProcessService.RemoveMicrosoftPinyinAsync();
                await RefreshSystemAsyncState();
                SetStatus(removed ? "已移除微软拼音，系统仅保留小狼毫输入法。" : "当前未检测到微软拼音输入法。");
            }
            catch (Exception ex)
            {
                SetStatus($"移除微软拼音失败：{ex.Message}", isError: true);
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

    private void OpenPhraseFile(string fileName)
    {
        RimeConfigService.EnsureCustomPhrasePlaceholders();
        var path = Path.Combine(Paths.RimeUserDir, fileName);
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    private async void RestartWeasel_Click(object sender, RoutedEventArgs e)
    {
        await RunExclusiveAsync(async () =>
        {
            try
            {
                SetStatus("正在重启 WeaselServer.exe 输入法驻留引擎...", isBusy: true, progressPercent: -1);
                await RimeProcessService.StopWeaselAsync();
                await RimeProcessService.EnsureWeaselServerRunningAsync();
                SetStatus("WeaselServer 服务已重新启动。", detail: DateTime.Now.ToString("HH:mm:ss"));
            }
            catch (Exception ex)
            {
                SetStatus($"重启服务失败：{ex.Message}", isError: true);
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
                SetStatus("正在检查 GitHub Wlaina/Ifboard 最新 Release...", isBusy: true, progressPercent: -1);
                var (_, msg, url) = await WebDavService.CheckAppReleaseAsync();
                AppUpdateInfoText.Text = msg;
                SetStatus(msg);
                pendingUrl = url;
                pendingMsg = msg;
            }
            catch (Exception ex)
            {
                SetStatus($"检查版本失败：{ex.Message}", isError: true);
            }
        });

        if (!string.IsNullOrEmpty(pendingUrl))
        {
            var open = System.Windows.MessageBox.Show(
                this,
                $"{pendingMsg}\n\n是否前往 GitHub Release 页面查看？",
                "Ifboard 版本检查",
                MessageBoxButton.YesNo,
                MessageBoxImage.Information
            );
            if (open == MessageBoxResult.Yes)
                Process.Start(new ProcessStartInfo(pendingUrl) { UseShellExecute = true });
        }
    }

    private void OpenGithub_Click(object sender, RoutedEventArgs e)
    {
        Process.Start(new ProcessStartInfo(WebDavService.ProjectHomeUrl) { UseShellExecute = true });
    }

    #endregion
}
