using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace IfboardWin.Services;

public static class RimeProcessService
{
    private const string MsPinyinTip =
        "0804:{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}{FA550B04-5AD7-411F-A5AC-CA038EC515D7}";

    public static async Task EnsureWeaselServerRunningAsync()
    {
        if (Process.GetProcessesByName("WeaselServer").Length > 0)
            return;

        var server = Paths.FindWeaselServer();
        if (server == null || !File.Exists(server))
            return;

        Process.Start(new ProcessStartInfo
        {
            FileName = server,
            WorkingDirectory = Path.GetDirectoryName(server)!,
            UseShellExecute = true
        });
        await Task.Delay(900);
    }

    public static async Task StopWeaselAsync()
    {
        var server = Paths.FindWeaselServer();
        if (server != null && File.Exists(server) && Process.GetProcessesByName("WeaselServer").Length > 0)
        {
            try
            {
                using var p = Process.Start(new ProcessStartInfo
                {
                    FileName = server,
                    Arguments = "/quit",
                    WorkingDirectory = Path.GetDirectoryName(server)!,
                    UseShellExecute = false,
                    CreateNoWindow = true
                });
                p?.WaitForExit(3000);
            }
            catch
            {
                // 回退到强制结束进程
            }
        }

        await Task.Delay(300);
        foreach (var name in new[] { "WeaselDeployer", "WeaselServer" })
        {
            foreach (var proc in Process.GetProcessesByName(name))
            {
                try { proc.Kill(); proc.WaitForExit(1500); } catch { }
                finally { proc.Dispose(); }
            }
        }
        await Task.Delay(250);
    }

    public static async Task<int> RunDeployerAsync(string argument, int timeoutMs = 45000)
    {
        var deployer = Paths.FindWeaselDeployer()
            ?? throw new InvalidOperationException("未找到 WeaselDeployer.exe，请先安装或修复小狼毫输入法。");

        await EnsureWeaselServerRunningAsync();

        return await Task.Run(() =>
        {
            using var p = Process.Start(new ProcessStartInfo
            {
                FileName = deployer,
                Arguments = argument,
                WorkingDirectory = Path.GetDirectoryName(deployer)!,
                UseShellExecute = false,
                CreateNoWindow = true
            }) ?? throw new InvalidOperationException("无法启动 WeaselDeployer.exe");

            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(); } catch { }
                throw new TimeoutException($"WeaselDeployer {argument} 执行超时（{timeoutMs / 1000} 秒）。");
            }
            return p.ExitCode;
        });
    }

    public static Task<int> DeployAsync() => RunDeployerAsync("/deploy", 45000);

    public static Task<int> SyncAsync() => RunDeployerAsync("/sync", 30000);

    public static Task<bool> IsMicrosoftPinyinPresentAsync()
    {
        return Task.FromResult(IsMicrosoftPinyinPresentFromRegistry());
    }

    private static bool IsMicrosoftPinyinPresentFromRegistry()
    {
        try
        {
            using var profileKey = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Control Panel\International\User Profile");
            if (profileKey == null) return false;

            foreach (var subName in profileKey.GetSubKeyNames())
            {
                if (!subName.StartsWith("zh", StringComparison.OrdinalIgnoreCase)) continue;
                using var langKey = profileKey.OpenSubKey(subName);
                if (langKey == null) continue;
                foreach (var valName in langKey.GetValueNames())
                {
                    if (string.Equals(valName, MsPinyinTip, StringComparison.OrdinalIgnoreCase))
                        return true;
                }
            }
            return false;
        }
        catch
        {
            return false;
        }
    }

    public static async Task<bool> RemoveMicrosoftPinyinAsync()
    {
        return await Task.Run(() =>
        {
            var script = $@"
$langs = Get-WinUserLanguageList
$zh = $langs | Where-Object {{ $_.LanguageTag -like 'zh*' }}
if ($zh) {{
    [void]$zh.InputMethodTips.Remove('{MsPinyinTip}')
    Set-WinUserLanguageList $langs -Force
    'REMOVED'
}} else {{ 'NO_ZH' }}";
            var res = RunPowerShell(script);
            return res.Contains("REMOVED", StringComparison.OrdinalIgnoreCase);
        });
    }

    private static string RunPowerShell(string script)
    {
        using var p = Process.Start(new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"{script.Replace("\"", "\\\"")}\"",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        }) ?? throw new InvalidOperationException("无法启动 PowerShell。");

        var output = p.StandardOutput.ReadToEnd();
        p.WaitForExit(15000);
        return output;
    }

    private const string LangBarRegPath = @"Software\Microsoft\CTF\LangBar";
    private static readonly Guid ClsidTfLangBarMgr = new("ebb08c45-6c4a-4fdc-ae53-4eb8c4c7db8e");

    // 取值与 Windows SDK ctfutb.h 保持一致（10.0.26100.0）
    private const uint TF_SFT_HIDDEN = 0x00000008;
    private const uint TF_SFT_EXTRAICONSONMINIMIZED = 0x00000200;
    private const uint TF_SFT_NOEXTRAICONSONMINIMIZED = 0x00000400;

    [ComImport]
    [Guid("87955690-e627-11d2-8ddb-00105a2799b5")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITfLangBarMgr
    {
        [PreserveSig]
        int AdviseEventSink([MarshalAs(UnmanagedType.Interface)] object pSink, nint hwnd, uint dwFlags, out uint pdwCookie);
        [PreserveSig]
        int UnadviseEventSink(uint dwCookie);
        [PreserveSig]
        int GetThreadMarshalInterface(uint dwThreadId, uint dwType, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppunk);
        [PreserveSig]
        int GetThreadLangBarItemMgr(uint dwThreadId, [MarshalAs(UnmanagedType.IUnknown)] out object pplbi, out uint pdwThreadid);
        [PreserveSig]
        int GetInputProcessorProfiles(uint dwThreadId, [MarshalAs(UnmanagedType.IUnknown)] out object ppaip, out uint pdwThreadid);
        [PreserveSig]
        int RestoreLastFocus(out uint dwThreadId, int fPrev);
        [PreserveSig]
        int SetModalInput([MarshalAs(UnmanagedType.Interface)] object pSink, uint dwThreadId, uint dwFlags);
        [PreserveSig]
        int ShowFloating(uint dwFlags);
        [PreserveSig]
        int GetShowFloatingStatus(out uint pdwFlags);
    }

    /// <summary>
    /// 控制 Windows 任务栏右下角输入指示器中的小狼毫「中 / 英」输入模式图标。
    /// Windows 11 的新版输入指示器总会显示 TSF 输入模式按钮，ITfLangBarMgr::ShowFloating 对它无效，
    /// 且本机实测不接受 MINIMIZED / DOCK，只接受 SHOWNORMAL 与 HIDDEN。
    /// 因此隐藏 = 切到「桌面语言栏」模式并将语言栏设为 HIDDEN | NOEXTRAICONS；
    /// 显示 = 关闭「桌面语言栏」，恢复系统默认输入指示器。两步均可逆，且保留用户原有的切换模式设置。
    /// </summary>
    public static void ApplyTaskbarImeIconState(bool show)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(LangBarRegPath, writable: true);
            if (key != null)
            {
                // 语言栏本体始终保持隐藏，避免关闭新版指示器后在桌面上浮出旧式语言栏
                key.SetValue("ShowStatus", 3, RegistryValueKind.DWord);
                key.SetValue("ExtraIconsOnMinimized", show ? 1 : 0, RegistryValueKind.DWord);
            }
        }
        catch
        {
            // 忽略注册表受限异常，继续尝试 COM 接口控制
        }

        // 先把旧式语言栏设为隐藏，再切换指示器模式，避免切换瞬间浮出语言栏
        RunInSta(() =>
        {
            object? comObj = null;
            try
            {
                var mgrType = Type.GetTypeFromCLSID(ClsidTfLangBarMgr);
                if (mgrType == null) return;
                comObj = Activator.CreateInstance(mgrType);
                if (comObj is not ITfLangBarMgr mgr) return;
                _ = mgr.ShowFloating(TF_SFT_HIDDEN |
                                     (show ? TF_SFT_EXTRAICONSONMINIMIZED : TF_SFT_NOEXTRAICONSONMINIMIZED));
            }
            catch
            {
                // 部分精简或远程会话环境不支持 TSF COM 时静默忽略
            }
            finally
            {
                if (comObj != null && Marshal.IsComObject(comObj))
                {
                    try { Marshal.FinalReleaseComObject(comObj); } catch { }
                }
            }
        });

        if (IsLegacyLanguageBarEnabled() == !show)
            return;

        var legacyBar = show ? string.Empty : " -UseLegacyLanguageBar";
        var script =
            "$o = Get-WinLanguageBarOption; " +
            "if ($o.IsLegacySwitchingMode) { Set-WinLanguageBarOption -UseLegacySwitchMode" + legacyBar + " } " +
            "else { Set-WinLanguageBarOption" + legacyBar + " }; 'OK'";
        try
        {
            RunPowerShell(script);
        }
        catch
        {
            // PowerShell 不可用时静默忽略
        }
    }

    /// <summary>
    /// 查询任务栏「中 / 英」输入模式图标当前是否可见（即未启用「桌面语言栏」隐藏模式）。
    /// </summary>
    public static bool IsTaskbarImeIconVisible() => IsLegacyLanguageBarEnabled() != true;

    /// <summary>
    /// 读取「使用桌面语言栏」开关：Set-WinLanguageBarOption 将其保存在
    /// HKCU\Control Panel\Desktop\UserPreferencesMask 第 6 字节的最低位（本机实测）。
    /// </summary>
    private static bool? IsLegacyLanguageBarEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Control Panel\Desktop");
            if (key?.GetValue("UserPreferencesMask") is byte[] mask && mask.Length > 5)
                return (mask[5] & 0x01) != 0;
        }
        catch
        {
            // 读取失败时交由调用方按未知处理
        }
        return null;
    }

    private static void RunInSta(Action action)
    {
        if (Thread.CurrentThread.GetApartmentState() == ApartmentState.STA)
        {
            action();
            return;
        }

        var thread = new Thread(() => action())
        {
            IsBackground = true
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join(3000);
    }
}
