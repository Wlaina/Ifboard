using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using IfboardWin.Models;
using Microsoft.Win32;

namespace IfboardWin.Services;

public static class AppSettingsService
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "IfboardDesktopTray";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    public static AppSettings Load()
    {
        var settings = new AppSettings();
        try
        {
            if (File.Exists(Paths.SettingsFile))
            {
                var json = File.ReadAllText(Paths.SettingsFile, Encoding.UTF8);
                var parsed = JsonSerializer.Deserialize<AppSettings>(json, JsonOptions);
                if (parsed != null)
                    settings = parsed;
            }
        }
        catch
        {
            // 损坏时回退默认配置
        }

        // 从实际生效的 weasel.custom.yaml 同步一次当前皮肤与字体设置，保持状态一致
        WeaselSkinService.ReadCurrentIntoSettings(settings);
        return settings;
    }

    public static void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Paths.IfboardDataDir);
        var json = JsonSerializer.Serialize(settings, JsonOptions);
        File.WriteAllText(Paths.SettingsFile, json, new UTF8Encoding(false));
        SyncAutoStartRegistry(settings.AutoFollowSystemAccentTray);
    }

    /// <summary>
    /// 按需在 HKCU\...\Run 注册或清理托盘监听项（仅传入 --tray，不弹窗）
    /// </summary>
    public static void SyncAutoStartRegistry(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
            if (key == null) return;

            if (!enabled)
            {
                if (key.GetValue(RunValueName) != null)
                    key.DeleteValue(RunValueName, throwOnMissingValue: false);
                return;
            }

            var exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
            if (string.IsNullOrWhiteSpace(exePath) ||
                exePath.EndsWith("dotnet.exe", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }

            key.SetValue(RunValueName, $"\"{exePath}\" --tray", RegistryValueKind.String);
        }
        catch
        {
            // 忽略注册表受限场景
        }
    }
}
