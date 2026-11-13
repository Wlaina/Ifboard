using System.Diagnostics;
using System.IO;

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
}
