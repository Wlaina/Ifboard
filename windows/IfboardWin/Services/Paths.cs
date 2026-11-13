using System.IO;
using Microsoft.Win32;

namespace IfboardWin.Services;

public static class Paths
{
    public const string AppVersion = "1.2.2";
    public static string UserAgent => $"Ifboard-Windows/{AppVersion}";

    public static string RimeUserDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Rime");

    public static string IfboardDataDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Ifboard");

    public static string SettingsFile => Path.Combine(IfboardDataDir, "settings.json");
    public static string WebDavFile => Path.Combine(IfboardDataDir, "webdav.dat");
    public static string GrammarMetaFile => Path.Combine(IfboardDataDir, "grammar_meta.json");

    public static string GrammarFile => Path.Combine(RimeUserDir, "wanxiang-lts-zh-hans.gram");
    public static string GrammarPartFile => Path.Combine(RimeUserDir, "wanxiang-lts-zh-hans.gram.part");
    public static string InstallationYaml => Path.Combine(RimeUserDir, "installation.yaml");
    public static string DefaultCustomYaml => Path.Combine(RimeUserDir, "default.custom.yaml");
    public static string DefaultYaml => Path.Combine(RimeUserDir, "default.yaml");
    public static string FlypyCustomYaml => Path.Combine(RimeUserDir, "double_pinyin_flypy.custom.yaml");
    public static string RimeIceCustomYaml => Path.Combine(RimeUserDir, "rime_ice.custom.yaml");
    public static string WeaselCustomYaml => Path.Combine(RimeUserDir, "weasel.custom.yaml");

    public static string? FindWeaselDir()
    {
        var candidates = new List<string>();

        foreach (var root in new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86)
        })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var rime = Path.Combine(root, "Rime");
            if (!Directory.Exists(rime)) continue;
            candidates.AddRange(
                Directory.GetDirectories(rime, "weasel-*")
                    .OrderByDescending(d => d, StringComparer.OrdinalIgnoreCase)
            );
        }

        foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
        {
            try
            {
                using var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
                using var key = baseKey.OpenSubKey(@"SOFTWARE\Rime\Weasel");
                var root = key?.GetValue("WeaselRoot") as string;
                if (!string.IsNullOrWhiteSpace(root))
                    candidates.Insert(0, root);
            }
            catch
            {
                // 忽略无权限或键不存在
            }
        }

        return candidates.FirstOrDefault(d => File.Exists(Path.Combine(d, "WeaselDeployer.exe")));
    }

    public static string? FindWeaselDeployer()
    {
        var dir = FindWeaselDir();
        return dir == null ? null : Path.Combine(dir, "WeaselDeployer.exe");
    }

    public static string? FindWeaselServer()
    {
        var dir = FindWeaselDir();
        return dir == null ? null : Path.Combine(dir, "WeaselServer.exe");
    }

    /// <summary>尝试定位历史上小狼毫同步目录中的旧版 webdav_config.json，方便一键导入迁移</summary>
    public static string? FindLegacyWebDavJson()
    {
        var candidates = new List<string>();
        var inst = ReadInstallationSyncDir();
        if (!string.IsNullOrWhiteSpace(inst))
            candidates.Add(Path.Combine(inst, "webdav_config.json"));

        var docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        candidates.Add(Path.Combine(docs, "RimeSync", "webdav_config.json"));

        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        candidates.Add(Path.Combine(userProfile, "OneDrive", "文档", "RimeSync", "webdav_config.json"));
        candidates.Add(Path.Combine(userProfile, "OneDrive", "Documents", "RimeSync", "webdav_config.json"));

        return candidates.FirstOrDefault(File.Exists);
    }

    public static string? ReadInstallationSyncDir() => ReadInstallationValue("sync_dir");

    public static string? ReadInstallationId() => ReadInstallationValue("installation_id");

    private static string? ReadInstallationValue(string key)
    {
        if (!File.Exists(InstallationYaml)) return null;
        var prefix = key + ":";
        foreach (var line in File.ReadLines(InstallationYaml))
        {
            var trimmed = line.Trim();
            if (!trimmed.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                continue;

            var raw = trimmed[prefix.Length..].Trim();
            if (raw.Length == 0) return null;

            // 支持单/双引号包裹值及行尾 # 注释
            var quote = raw[0];
            if (quote is '\'' or '"')
            {
                var close = raw.IndexOf(quote, 1);
                if (close > 0)
                    return raw[1..close].Trim();
            }

            var commentIdx = raw.IndexOf('#');
            if (commentIdx >= 0)
                raw = raw[..commentIdx].Trim();

            return raw.Trim('\'', '"');
        }
        return null;
    }
}
