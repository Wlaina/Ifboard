using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Runtime;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using System.Windows.Shell;
using System.Windows.Threading;
using IfboardWin.Models;
using IfboardWin.Services;
using IfboardWin.Theme;
using Microsoft.Win32;
using WinForms = System.Windows.Forms;

namespace IfboardWin;

public partial class App : System.Windows.Application
{
    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Point
    {
        public int X;
        public int Y;
    }

    [DllImport("kernel32.dll")]
    private static extern bool SetProcessWorkingSetSize(nint hProcess, nint dwMinimumWorkingSetSize, nint dwMaximumWorkingSetSize);

    [DllImport("psapi.dll")]
    private static extern bool EmptyWorkingSet(nint hProcess);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    private static extern nint GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(nint hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(nint hWnd);

    [DllImport("user32.dll")]
    private static extern bool BringWindowToTop(nint hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(nint hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(nint hWnd);

    [DllImport("user32.dll")]
    private static extern bool AllowSetForegroundWindow(int dwProcessId);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out Win32Point lpPoint);

    [DllImport("user32.dll")]
    private static extern nint MonitorFromPoint(Win32Point pt, uint dwFlags);

    [DllImport("shcore.dll")]
    private static extern int GetDpiForMonitor(nint hmonitor, int dpiType, out uint dpiX, out uint dpiY);

    private const int SwRestore = 9;
    private const int AsfwAny = -1;
    private const uint MonitorDefaultToNearest = 2;

    private const string MutexName = @"Local\IfboardWin_SingleInstance_Mutex";
    private const string WakeEventName = @"Local\IfboardWin_WakeWindow_Event";
    private const string IpcPipeName = "IfboardWin_Ipc_v1";

    /// <summary>托盘动作与主窗口共用互斥锁，避免并发写入 YAML 或并发调用 /deploy。</summary>
    public static readonly SemaphoreSlim OperationGate = new(1, 1);

    private Mutex? _singleMutex;
    private EventWaitHandle? _wakeEvent;
    private RegisteredWaitHandle? _wakeWaitHandle;
    private CancellationTokenSource? _ipcCts;
    private WinForms.NotifyIcon? _trayIcon;
    private Window? _trayMenuHost;
    private ContextMenu? _trayMenu;
    private MainWindow? _mainWindow;
    private CancellationTokenSource? _themeDebounceCts;
    private CancellationTokenSource? _trimCts;
    private bool _isExplicitExit;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (e.Args.Any(a => string.Equals(a, "--quit", StringComparison.OrdinalIgnoreCase)))
        {
            ForwardArgsToExistingInstance(e.Args);
            Shutdown();
            return;
        }

        _singleMutex = new Mutex(true, MutexName, out var createdNew);
        if (!createdNew)
        {
            ForwardArgsToExistingInstance(e.Args);
            Shutdown();
            return;
        }

        _wakeEvent = new EventWaitHandle(false, EventResetMode.AutoReset, WakeEventName);
        _wakeWaitHandle = ThreadPool.RegisterWaitForSingleObject(
            _wakeEvent,
            (_, timedOut) =>
            {
                if (!timedOut && !_isExplicitExit)
                    Dispatcher.BeginInvoke(ShowMainWindow);
            },
            null,
            Timeout.Infinite,
            executeOnlyOnce: false
        );

        _ipcCts = new CancellationTokenSource();
        StartIpcPipeServer(_ipcCts.Token);

        var settings = AppSettingsService.Load();
        WarmPaperPalette.ApplyToResources(Resources, settings.UiMode);
        AppSettingsService.SyncAutoStartRegistry(settings.AutoFollowSystemAccentTray);

        ConfigureJumpList();
        InitializeTrayIcon();
        SystemEvents.UserPreferenceChanged += OnSystemPreferenceChanged;

        var startInTray = e.Args.Any(a => string.Equals(a, "--tray", StringComparison.OrdinalIgnoreCase));
        if (startInTray)
        {
            // 冷启动纯托盘模式下延迟裁剪工作集，保持极低后台占用
            ScheduleTrayTrim(aggressive: true, delayMs: 1200);
        }
        else
        {
            ExecuteStartupCommand(e.Args);
        }
    }

    public void ShowMainWindow()
    {
        _trimCts?.Cancel();

        if (_mainWindow == null)
        {
            var win = new MainWindow();
            win.Closing += (_, args) =>
            {
                if (_isExplicitExit) return;

                // 若当前有下载或同步任务运行，暂隐窗口保留进度；否则真实销毁释放在线视觉树与 GPU 缓存
                if (win.IsOperationBusy || OperationGate.CurrentCount == 0)
                {
                    args.Cancel = true;
                    win.Hide();
                    ScheduleTrayTrim(aggressive: false, delayMs: 250);
                }
            };
            win.Closed += (_, _) =>
            {
                if (ReferenceEquals(_mainWindow, win))
                    _mainWindow = null;

                if (!_isExplicitExit)
                    ScheduleTrayTrim(aggressive: true, delayMs: 450);
            };
            _mainWindow = win;
        }

        _mainWindow.ShowInTaskbar = true;
        ForceForegroundWindow(_mainWindow);
    }

    public void ScheduleHiddenWindowRelease()
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_mainWindow is { IsVisible: false } win && !win.IsOperationBusy && OperationGate.CurrentCount > 0)
                win.Close();
        }, DispatcherPriority.Background);
    }

    public static void ForceForegroundWindow(Window window)
    {
        var hwnd = new WindowInteropHelper(window).EnsureHandle();

        if (!window.IsVisible)
            window.Show();

        if (IsIconic(hwnd) || window.WindowState == WindowState.Minimized)
        {
            ShowWindow(hwnd, SwRestore);
            window.WindowState = WindowState.Normal;
        }

        var currentThread = GetCurrentThreadId();
        var foregroundHwnd = GetForegroundWindow();
        var foregroundThread = foregroundHwnd != nint.Zero
            ? GetWindowThreadProcessId(foregroundHwnd, out _)
            : 0;

        if (foregroundThread != 0 && foregroundThread != currentThread)
        {
            AttachThreadInput(currentThread, foregroundThread, true);
            try
            {
                BringWindowToTop(hwnd);
                SetForegroundWindow(hwnd);
            }
            finally
            {
                AttachThreadInput(currentThread, foregroundThread, false);
            }
        }
        else
        {
            BringWindowToTop(hwnd);
            SetForegroundWindow(hwnd);
        }

        var prevTopmost = window.Topmost;
        window.Topmost = true;
        window.Activate();
        window.Topmost = prevTopmost;
        window.Focus();
    }

    public void ScheduleTrayTrim(bool aggressive, int delayMs)
    {
        _trimCts?.Cancel();
        var cts = new CancellationTokenSource();
        _trimCts = cts;

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(delayMs, cts.Token);
                if (_isExplicitExit) return;

                var hasVisibleWindow = await Dispatcher.InvokeAsync(() =>
                    _mainWindow is { IsVisible: true } || _trayMenu is { IsOpen: true });
                if (!hasVisibleWindow)
                    TrimTrayMemory(aggressive);
            }
            catch (OperationCanceledException)
            {
                // 窗体重新打开或后续操作覆盖时取消裁剪
            }
        });
    }

    public static void TrimTrayMemory(bool aggressive = false)
    {
        try
        {
            if (aggressive)
            {
                GCSettings.LargeObjectHeapCompactionMode = GCLargeObjectHeapCompactionMode.CompactOnce;
                GC.Collect(GC.MaxGeneration, GCCollectionMode.Aggressive, blocking: true, compacting: true);
                GC.WaitForPendingFinalizers();
                GC.Collect(GC.MaxGeneration, GCCollectionMode.Aggressive, blocking: true, compacting: true);
            }
            else
            {
                GC.Collect(1, GCCollectionMode.Optimized, blocking: false);
            }

            using var proc = Process.GetCurrentProcess();
            _ = EmptyWorkingSet(proc.Handle);
            _ = SetProcessWorkingSetSize(proc.Handle, -1, -1);
        }
        catch
        {
            // 低功耗整理工作集失败不影响正常驻留
        }
    }

    public void ExitApplication()
    {
        _isExplicitExit = true;
        SystemEvents.UserPreferenceChanged -= OnSystemPreferenceChanged;
        _themeDebounceCts?.Cancel();
        _trimCts?.Cancel();
        _ipcCts?.Cancel();

        if (_trayMenu != null)
            _trayMenu.IsOpen = false;
        _trayMenuHost?.Close();
        _trayMenuHost = null;
        _trayMenu = null;

        if (_trayIcon != null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _trayIcon = null;
        }

        _mainWindow?.Close();
        _mainWindow = null;

        _wakeWaitHandle?.Unregister(null);
        _wakeWaitHandle = null;
        _wakeEvent?.Dispose();
        _wakeEvent = null;

        try { _singleMutex?.ReleaseMutex(); } catch { }
        _singleMutex?.Dispose();
        Shutdown();
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        // 响应安装器 RestartManager 或系统注销/关机信号，立即清理托盘并退出进程
        e.Cancel = false;
        ExitApplication();
        base.OnSessionEnding(e);
    }

    private void ExecuteStartupCommand(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--quit", StringComparison.OrdinalIgnoreCase)))
        {
            ExitApplication();
            return;
        }
        if (args.Any(a => string.Equals(a, "--sync", StringComparison.OrdinalIgnoreCase)))
        {
            _ = RunTrayWebDavSyncAsync();
            return;
        }
        if (args.Any(a => string.Equals(a, "--theme-accent", StringComparison.OrdinalIgnoreCase)))
        {
            _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.SystemAccent);
            return;
        }
        if (args.Any(a => string.Equals(a, "--theme-terracotta", StringComparison.OrdinalIgnoreCase)))
        {
            _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.ClaudeTerracotta);
            return;
        }
        if (args.Any(a => string.Equals(a, "--theme-amber", StringComparison.OrdinalIgnoreCase)))
        {
            _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.WarmAmber);
            return;
        }
        if (args.Any(a => string.Equals(a, "--deploy", StringComparison.OrdinalIgnoreCase)))
        {
            _ = SafeDeployFromTrayAsync();
            return;
        }

        ShowMainWindow();
    }

    private static void ForwardArgsToExistingInstance(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--tray", StringComparison.OrdinalIgnoreCase)))
            return;

        var isQuit = args.Any(a => string.Equals(a, "--quit", StringComparison.OrdinalIgnoreCase));
        var cmd = "--show";
        if (isQuit)
            cmd = "--quit";
        else if (args.Any(a => string.Equals(a, "--sync", StringComparison.OrdinalIgnoreCase)))
            cmd = "--sync";
        else if (args.Any(a => string.Equals(a, "--theme-accent", StringComparison.OrdinalIgnoreCase)))
            cmd = "--theme-accent";
        else if (args.Any(a => string.Equals(a, "--theme-terracotta", StringComparison.OrdinalIgnoreCase)))
            cmd = "--theme-terracotta";
        else if (args.Any(a => string.Equals(a, "--theme-amber", StringComparison.OrdinalIgnoreCase)))
            cmd = "--theme-amber";
        else if (args.Any(a => string.Equals(a, "--deploy", StringComparison.OrdinalIgnoreCase)))
            cmd = "--deploy";

        try
        {
            using var client = new NamedPipeClientStream(".", IpcPipeName, PipeDirection.Out);
            client.Connect(800);
            using var writer = new StreamWriter(client, Encoding.UTF8) { AutoFlush = true };
            writer.WriteLine(cmd);
            return;
        }
        catch
        {
            if (isQuit) return;
            // 若 NamedPipe 繁忙或未就绪，回退到事件对象唤醒主窗口
        }

        try
        {
            using var ev = EventWaitHandle.OpenExisting(WakeEventName);
            ev.Set();
        }
        catch
        {
            // 忽略跨会话唤醒异常
        }
    }

    private void StartIpcPipeServer(CancellationToken ct)
    {
        _ = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested && !_isExplicitExit)
            {
                try
                {
                    await using var server = new NamedPipeServerStream(
                        IpcPipeName,
                        PipeDirection.In,
                        NamedPipeServerStream.MaxAllowedServerInstances,
                        PipeTransmissionMode.Byte,
                        PipeOptions.Asynchronous);

                    await server.WaitForConnectionAsync(ct);
                    using var reader = new StreamReader(server, Encoding.UTF8);
                    var line = await reader.ReadLineAsync(ct);
                    var cmd = string.IsNullOrWhiteSpace(line) ? "--show" : line.Trim();

                    await Dispatcher.InvokeAsync(() => ExecuteStartupCommand([cmd]));
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    await Task.Delay(150, ct);
                }
            }
        }, ct);
    }

    private void ConfigureJumpList()
    {
        try
        {
            var exePath = Process.GetCurrentProcess().MainModule?.FileName ?? Environment.ProcessPath;
            if (string.IsNullOrEmpty(exePath)) return;

            var jumpList = new JumpList();
            jumpList.JumpItems.Add(new JumpTask
            {
                Title = "打开 Ifboard 控制中心",
                Description = "打开主面板、词库设置与皮肤预览",
                ApplicationPath = exePath,
                Arguments = "--show",
                IconResourcePath = exePath,
                CustomCategory = "快捷操作"
            });
            jumpList.JumpItems.Add(new JumpTask
            {
                Title = "同步云端词库（WebDAV）",
                Description = "导出本机词频快照并与云端多设备词库合并",
                ApplicationPath = exePath,
                Arguments = "--sync",
                IconResourcePath = exePath,
                CustomCategory = "快捷操作"
            });
            jumpList.JumpItems.Add(new JumpTask
            {
                Title = "切换：Claude 陶土橙皮肤",
                Description = "应用默认暖纸陶土橙小狼毫候选窗",
                ApplicationPath = exePath,
                Arguments = "--theme-terracotta",
                IconResourcePath = exePath,
                CustomCategory = "候选窗皮肤"
            });
            jumpList.JumpItems.Add(new JumpTask
            {
                Title = "切换：跟随 Windows 系统主题色",
                Description = "采集系统强调色与深浅模式生成候选窗皮肤",
                ApplicationPath = exePath,
                Arguments = "--theme-accent",
                IconResourcePath = exePath,
                CustomCategory = "候选窗皮肤"
            });
            jumpList.JumpItems.Add(new JumpTask
            {
                Title = "重新部署小狼毫（/deploy）",
                Description = "热重载小狼毫输入方案、词库与外观皮肤",
                ApplicationPath = exePath,
                Arguments = "--deploy",
                IconResourcePath = exePath,
                CustomCategory = "候选窗皮肤"
            });
            jumpList.Apply();
            JumpList.SetJumpList(this, jumpList);
        }
        catch
        {
            // 部分精简或沙盒环境不支持任务栏 JumpList 时静默忽略
        }
    }

    private void InitializeTrayIcon()
    {
        Icon? icon = null;
        try
        {
            var stream = GetResourceStream(new Uri("pack://application:,,,/Assets/ifboard.ico"))?.Stream;
            if (stream != null)
            {
                using (stream)
                    icon = new Icon(stream, WinForms.SystemInformation.SmallIconSize);
            }
        }
        catch
        {
            // 回退系统默认图标
        }

        _trayIcon = new WinForms.NotifyIcon
        {
            Icon = icon ?? SystemIcons.Application,
            Text = "Ifboard 控制中心（小狼毫伴侣）",
            Visible = true
        };
        _trayIcon.MouseUp += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left)
            {
                Dispatcher.BeginInvoke(ShowMainWindow);
            }
            else if (e.Button == WinForms.MouseButtons.Right)
            {
                Dispatcher.BeginInvoke(OpenWpfTrayContextMenu);
            }
        };
        _trayIcon.DoubleClick += (_, _) => Dispatcher.BeginInvoke(ShowMainWindow);
    }

    private void OpenWpfTrayContextMenu()
    {
        if (!GetCursorPos(out var pt)) return;

        var settings = AppSettingsService.Load();
        WarmPaperPalette.ApplyToResources(Resources, settings.UiMode);
        var busy = OperationGate.CurrentCount == 0;

        _trayMenuHost?.Close();

        var menuHost = new Window
        {
            Width = 1,
            Height = 1,
            WindowStyle = WindowStyle.None,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            ShowActivated = true,
            AllowsTransparency = true,
            Background = System.Windows.Media.Brushes.Transparent,
            Topmost = true,
            Left = -32000,
            Top = -32000
        };

        var menu = new ContextMenu
        {
            Style = (Style)Resources["WpTrayContextMenuStyle"]
        };

        MenuItem MakeItem(string header, Action onClick, bool bold = false, bool isEnabled = true, bool isChecked = false)
        {
            var item = new MenuItem
            {
                Header = header,
                Style = (Style)Resources["WpTrayMenuItemStyle"],
                FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
                IsEnabled = isEnabled,
                IsChecked = isChecked
            };
            item.Click += (_, _) => onClick();
            return item;
        }

        Separator MakeSep() => new() { Style = (Style)Resources["WpTraySeparatorStyle"] };

        menu.Items.Add(MakeItem("打开 Ifboard 控制中心", ShowMainWindow, bold: true));
        menu.Items.Add(MakeSep());
        menu.Items.Add(MakeItem("同步云端词库（WebDAV）", () => _ = RunTrayWebDavSyncAsync(), isEnabled: !busy));
        menu.Items.Add(MakeSep());
        menu.Items.Add(MakeItem("切换至「跟随系统主题色」皮肤", () => _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.SystemAccent), isEnabled: !busy, isChecked: settings.WeaselTheme == ThemePresetKind.SystemAccent));
        menu.Items.Add(MakeItem("切换至「Claude 陶土橙」皮肤", () => _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.ClaudeTerracotta), isEnabled: !busy, isChecked: settings.WeaselTheme == ThemePresetKind.ClaudeTerracotta));
        menu.Items.Add(MakeItem("切换至「暖纸琥珀」皮肤", () => _ = SwitchWeaselThemeFromTrayAsync(ThemePresetKind.WarmAmber), isEnabled: !busy, isChecked: settings.WeaselTheme == ThemePresetKind.WarmAmber));
        menu.Items.Add(MakeSep());
        menu.Items.Add(MakeItem("重新部署小狼毫（/deploy）", () => _ = SafeDeployFromTrayAsync(), isEnabled: !busy));
        menu.Items.Add(MakeSep());
        menu.Items.Add(MakeItem("退出托盘", ExitApplication));

        menu.Closed += (_, _) =>
        {
            Dispatcher.BeginInvoke(() =>
            {
                menuHost.Close();
                if (ReferenceEquals(_trayMenuHost, menuHost)) _trayMenuHost = null;
                if (ReferenceEquals(_trayMenu, menu)) _trayMenu = null;
                ScheduleTrayTrim(aggressive: false, delayMs: 300);
            }, DispatcherPriority.Background);
        };

        _trayMenuHost = menuHost;
        _trayMenu = menu;

        var (scaleX, scaleY) = GetMonitorDpiScale(pt);
        var dipX = pt.X / scaleX;
        var dipY = pt.Y / scaleY;

        menuHost.Left = dipX - 1;
        menuHost.Top = dipY - 1;
        menuHost.Show();

        var hostHwnd = new WindowInteropHelper(menuHost).EnsureHandle();
        AllowSetForegroundWindow(AsfwAny);
        SetForegroundWindow(hostHwnd);

        menu.PlacementTarget = menuHost;
        menu.Placement = PlacementMode.AbsolutePoint;
        menu.HorizontalOffset = dipX;
        menu.VerticalOffset = dipY;
        menu.IsOpen = true;
    }

    private static (double ScaleX, double ScaleY) GetMonitorDpiScale(Win32Point pt)
    {
        try
        {
            var hMon = MonitorFromPoint(pt, MonitorDefaultToNearest);
            if (hMon != nint.Zero && GetDpiForMonitor(hMon, 0, out var dpiX, out var dpiY) == 0 && dpiX > 0 && dpiY > 0)
                return (dpiX / 96.0, dpiY / 96.0);
        }
        catch
        {
            // 非 Windows 8.1+ 或无 shcore 时回退 GDI DPI
        }

        try
        {
            using var g = Graphics.FromHwnd(nint.Zero);
            return (Math.Max(1.0, g.DpiX / 96.0), Math.Max(1.0, g.DpiY / 96.0));
        }
        catch
        {
            return (1.0, 1.0);
        }
    }

    private async Task RunTrayWebDavSyncAsync()
    {
        if (!await OperationGate.WaitAsync(0))
        {
            _trayIcon?.ShowBalloonTip(2500, "Ifboard", "当前已有任务正在执行，请稍后再试。", WinForms.ToolTipIcon.Info);
            return;
        }

        try
        {
            var cfg = WebDavService.LoadConfig();
            if (string.IsNullOrWhiteSpace(cfg.User) || string.IsNullOrWhiteSpace(cfg.Password))
            {
                _trayIcon?.ShowBalloonTip(3000, "Ifboard", "尚未配置 WebDAV 账号，请在控制中心填写。", WinForms.ToolTipIcon.Info);
                await Dispatcher.InvokeAsync(ShowMainWindow);
                return;
            }

            var res = await WebDavService.SyncUserDbAsync(cfg);
            _trayIcon?.ShowBalloonTip(
                3000,
                "Ifboard 云端同步完成",
                $"上传 {res.Uploaded} 份，合并下载 {res.Downloaded} 份",
                WinForms.ToolTipIcon.Info
            );
            await Dispatcher.InvokeAsync(() => _mainWindow?.RefreshExternalState());
        }
        catch (Exception ex)
        {
            _trayIcon?.ShowBalloonTip(4000, "Ifboard 同步失败", ex.Message, WinForms.ToolTipIcon.Error);
        }
        finally
        {
            OperationGate.Release();
            ScheduleHiddenWindowRelease();
            ScheduleTrayTrim(aggressive: false, delayMs: 400);
        }
    }

    private async Task SwitchWeaselThemeFromTrayAsync(ThemePresetKind kind)
    {
        if (!await OperationGate.WaitAsync(0))
        {
            _trayIcon?.ShowBalloonTip(2500, "Ifboard", "当前已有部署或同步任务正在执行，请稍后再试。", WinForms.ToolTipIcon.Info);
            return;
        }

        try
        {
            var settings = AppSettingsService.Load();
            settings.WeaselTheme = kind;
            AppSettingsService.Save(settings);
            WeaselSkinService.WriteWeaselCustomYaml(settings);
            await RimeProcessService.DeployAsync();
            await Dispatcher.InvokeAsync(() => _mainWindow?.RefreshExternalState());
        }
        catch (Exception ex)
        {
            _trayIcon?.ShowBalloonTip(3500, "Ifboard 切换皮肤失败", ex.Message, WinForms.ToolTipIcon.Error);
        }
        finally
        {
            OperationGate.Release();
            ScheduleHiddenWindowRelease();
            ScheduleTrayTrim(aggressive: false, delayMs: 400);
        }
    }

    private async Task SafeDeployFromTrayAsync()
    {
        if (!await OperationGate.WaitAsync(0))
        {
            _trayIcon?.ShowBalloonTip(2500, "Ifboard", "当前已有任务正在执行，请稍后再试。", WinForms.ToolTipIcon.Info);
            return;
        }

        try
        {
            await RimeProcessService.DeployAsync();
            _trayIcon?.ShowBalloonTip(2500, "Ifboard", "小狼毫已重新部署生效。", WinForms.ToolTipIcon.Info);
        }
        catch (Exception ex)
        {
            _trayIcon?.ShowBalloonTip(3500, "Ifboard 部署失败", ex.Message, WinForms.ToolTipIcon.Error);
        }
        finally
        {
            OperationGate.Release();
            ScheduleHiddenWindowRelease();
            ScheduleTrayTrim(aggressive: false, delayMs: 400);
        }
    }

    private void OnSystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category is not (UserPreferenceCategory.General or UserPreferenceCategory.Color or UserPreferenceCategory.VisualStyle))
            return;

        _themeDebounceCts?.Cancel();
        var cts = new CancellationTokenSource();
        _themeDebounceCts = cts;

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(900, cts.Token);
                await OperationGate.WaitAsync(cts.Token);
                try
                {
                    var settings = AppSettingsService.Load();
                    await Dispatcher.InvokeAsync(() =>
                    {
                        WarmPaperPalette.ApplyToResources(Resources, settings.UiMode);
                        _mainWindow?.RefreshSystemThemePreview();
                    });

                    if (settings.WeaselTheme == ThemePresetKind.SystemAccent && settings.AutoFollowSystemAccentTray)
                    {
                        WeaselSkinService.WriteWeaselCustomYaml(settings);
                        await RimeProcessService.DeployAsync();
                    }
                }
                finally
                {
                    OperationGate.Release();
                    ScheduleHiddenWindowRelease();
                }
            }
            catch (OperationCanceledException)
            {
                // 防抖取消正常忽略
            }
            catch
            {
                // 系统主题变更静默容错
            }
        });
    }
}
