using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using IfboardWin.Models;

namespace IfboardWin.Services;

public static class WebDavService
{
    public const string ProjectHomeUrl = "https://github.com/Wlaina/Ifboard";

    public const string DefaultAppReleaseApi =
        "https://api.github.com/repos/Wlaina/Ifboard/releases/latest";

    private sealed record DavEntry(string Name, bool IsDirectory, long Size, DateTimeOffset LastModified);

    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private static readonly string[] PersonalRootFiles =
    [
        "default.custom.yaml",
        "double_pinyin_flypy.custom.yaml",
        "rime_ice.custom.yaml",
        "weasel.custom.yaml",
        "custom_phrase.txt",
        "custom_phrase_double.txt"
    ];

    #region DPAPI Credential Storage

    public static WebDavConfig LoadConfig()
    {
        try
        {
            if (!File.Exists(Paths.WebDavFile))
                return new WebDavConfig();

            var protectedBytes = File.ReadAllBytes(Paths.WebDavFile);
            var rawBytes = DpapiUnprotect(protectedBytes);
            var json = Encoding.UTF8.GetString(rawBytes);
            var cfg = JsonSerializer.Deserialize<WebDavConfig>(json, JsonOptions);
            return cfg ?? new WebDavConfig();
        }
        catch
        {
            return new WebDavConfig();
        }
    }

    public static void SaveConfig(WebDavConfig cfg)
    {
        Directory.CreateDirectory(Paths.IfboardDataDir);
        cfg.Url = string.IsNullOrWhiteSpace(cfg.Url) ? WebDavConfig.KoofrUrl : cfg.Url.Trim().TrimEnd('/');
        cfg.Dir = string.IsNullOrWhiteSpace(cfg.Dir) ? "Ifboard" : cfg.Dir.Trim().Trim('/');
        cfg.User = cfg.User.Trim();

        var json = JsonSerializer.Serialize(cfg, JsonOptions);
        var rawBytes = Encoding.UTF8.GetBytes(json);
        var protectedBytes = DpapiProtect(rawBytes);
        File.WriteAllBytes(Paths.WebDavFile, protectedBytes);
    }

    /// <summary>尝试从历史安装中留存的旧版 webdav_config.json 读取账号（仅当本地尚无凭据时使用）</summary>
    public static WebDavConfig? TryReadLegacyConfig()
    {
        try
        {
            var path = Paths.FindLegacyWebDavJson();
            if (path == null) return null;
            using var doc = JsonDocument.Parse(File.ReadAllText(path, Encoding.UTF8));
            var root = doc.RootElement;
            var url = root.TryGetProperty("url", out var u) ? u.GetString() : null;
            var user = root.TryGetProperty("user", out var usr) ? usr.GetString() : null;
            var pass = root.TryGetProperty("password", out var pwd)
                ? pwd.GetString()
                : (root.TryGetProperty("pass", out var pwd2) ? pwd2.GetString() : null);
            var dir = root.TryGetProperty("dir", out var d) ? d.GetString() : null;
            if (string.IsNullOrWhiteSpace(user) || string.IsNullOrWhiteSpace(pass))
                return null;

            return new WebDavConfig
            {
                Url = string.IsNullOrWhiteSpace(url) ? WebDavConfig.KoofrUrl : url.Trim().TrimEnd('/'),
                User = user.Trim(),
                Password = pass,
                Dir = string.IsNullOrWhiteSpace(dir) ? "Ifboard" : dir.Trim().Trim('/')
            };
        }
        catch
        {
            return null;
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DATA_BLOB
    {
        public int cbData;
        public nint pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DATA_BLOB pDataIn,
        string? szDataDescr,
        nint pOptionalEntropy,
        nint pvReserved,
        nint pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut
    );

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DATA_BLOB pDataIn,
        nint ppszDataDescr,
        nint pOptionalEntropy,
        nint pvReserved,
        nint pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut
    );

    [DllImport("kernel32.dll")]
    private static extern nint LocalFree(nint hMem);

    private static byte[] DpapiProtect(byte[] plain)
    {
        var inBlob = new DATA_BLOB();
        var outBlob = new DATA_BLOB();
        var inPtr = Marshal.AllocHGlobal(plain.Length);
        try
        {
            Marshal.Copy(plain, 0, inPtr, plain.Length);
            inBlob.cbData = plain.Length;
            inBlob.pbData = inPtr;

            if (!CryptProtectData(ref inBlob, "IfboardWebDav", nint.Zero, nint.Zero, nint.Zero, 0x1, ref outBlob))
                throw new InvalidOperationException($"DPAPI 加密失败: 0x{Marshal.GetLastWin32Error():X}");

            var result = new byte[outBlob.cbData];
            Marshal.Copy(outBlob.pbData, result, 0, result.Length);
            return result;
        }
        finally
        {
            Marshal.FreeHGlobal(inPtr);
            if (outBlob.pbData != nint.Zero) LocalFree(outBlob.pbData);
        }
    }

    private static byte[] DpapiUnprotect(byte[] cipher)
    {
        var inBlob = new DATA_BLOB();
        var outBlob = new DATA_BLOB();
        var inPtr = Marshal.AllocHGlobal(cipher.Length);
        try
        {
            Marshal.Copy(cipher, 0, inPtr, cipher.Length);
            inBlob.cbData = cipher.Length;
            inBlob.pbData = inPtr;

            if (!CryptUnprotectData(ref inBlob, nint.Zero, nint.Zero, nint.Zero, nint.Zero, 0x1, ref outBlob))
                throw new InvalidOperationException($"DPAPI 解密失败: 0x{Marshal.GetLastWin32Error():X}");

            var result = new byte[outBlob.cbData];
            Marshal.Copy(outBlob.pbData, result, 0, result.Length);
            return result;
        }
        finally
        {
            Marshal.FreeHGlobal(inPtr);
            if (outBlob.pbData != nint.Zero) LocalFree(outBlob.pbData);
        }
    }

    #endregion

    #region WebDAV Protocol & Sync

    public static async Task<string> TestConnectionAsync(WebDavConfig cfg, CancellationToken ct = default)
    {
        ValidateConfig(cfg);
        using var http = CreateClient(cfg);
        var baseUrl = await EnsureMkcolPathAsync(http, cfg, ct);
        var items = await PropFindAsync(http, baseUrl, ct);
        var deviceNames = items
            .Where(i => i.IsDirectory && !i.Name.StartsWith("_", StringComparison.Ordinal))
            .Select(i => i.Name)
            .OrderBy(n => n, StringComparer.OrdinalIgnoreCase)
            .ToList();
        var hasProfile = items.Any(i => i.IsDirectory && i.Name == "_pc_profile");
        var listStr = deviceNames.Count > 0 ? $"（{string.Join(" · ", deviceNames)}）" : "";
        return hasProfile
            ? $"连接成功：云端已存在 {deviceNames.Count} 台同步设备{listStr} 与个人配置快照"
            : $"连接成功：云端已存在 {deviceNames.Count} 台同步设备{listStr}";
    }

    public static async Task<SyncResult> SyncUserDbAsync(
        WebDavConfig cfg,
        IProgress<string>? progress = null,
        CancellationToken ct = default)
    {
        ValidateConfig(cfg);
        using var http = CreateClient(cfg);

        progress?.Report("1/4 正在触发小狼毫导出本地词频快照...");
        Directory.CreateDirectory(GetSyncRoot());
        var preSyncCode = await RimeProcessService.SyncAsync();
        if (preSyncCode != 0)
            throw new InvalidOperationException($"小狼毫本地词库导出失败（WeaselDeployer /sync 返回码 {preSyncCode}）。");

        var syncRoot = GetSyncRoot();
        var deviceId = ResolveDeviceId(syncRoot);
        var localDeviceDir = Path.Combine(syncRoot, deviceId);
        Directory.CreateDirectory(localDeviceDir);

        var remoteRoot = await EnsureMkcolPathAsync(http, cfg, ct);
        var remoteSelfDir = $"{remoteRoot}/{Uri.EscapeDataString(deviceId)}";
        await EnsureMkcolAsync(http, remoteSelfDir, ct);

        progress?.Report($"2/4 正在上传本机词库（{deviceId}）...");
        int uploaded = 0;
        if (Directory.Exists(localDeviceDir))
        {
            foreach (var file in Directory.GetFiles(localDeviceDir, "*.userdb.txt"))
            {
                if (new FileInfo(file).Length == 0) continue;
                var name = Path.GetFileName(file);
                await PutFileAsync(http, $"{remoteSelfDir}/{Uri.EscapeDataString(name)}", file, ct);
                uploaded++;
            }
        }

        progress?.Report("3/4 正在扫描并拉取其他设备词库...");
        var topEntries = await PropFindAsync(http, remoteRoot, ct);
        var otherDevices = new List<string>();
        int downloaded = 0;

        foreach (var dir in topEntries)
        {
            if (!dir.IsDirectory) continue;
            // 跳过本机目录及下划线开头的系统配置快照目录（如 _pc_profile）
            if (string.Equals(dir.Name, deviceId, StringComparison.Ordinal)) continue;
            if (dir.Name.StartsWith("_", StringComparison.Ordinal)) continue;

            otherDevices.Add(dir.Name);
            var targetLocalDir = Path.Combine(syncRoot, dir.Name);
            Directory.CreateDirectory(targetLocalDir);

            var remoteDeviceUrl = $"{remoteRoot}/{Uri.EscapeDataString(dir.Name)}";
            var files = await PropFindAsync(http, remoteDeviceUrl, ct);
            foreach (var f in files)
            {
                if (f.IsDirectory || !f.Name.EndsWith(".userdb.txt", StringComparison.OrdinalIgnoreCase))
                    continue;

                var localPath = Path.Combine(targetLocalDir, f.Name);
                if (await DownloadNonEmptyFileAsync(http, $"{remoteDeviceUrl}/{Uri.EscapeDataString(f.Name)}", localPath, ct))
                    downloaded++;
            }
        }

        if (downloaded > 0)
        {
            progress?.Report($"4/4 已获取 {downloaded} 份云端词库，正在合并生效...");
            var mergeCode = await RimeProcessService.SyncAsync();
            if (mergeCode != 0)
                throw new InvalidOperationException($"云端词库已拉取，但合并时返回码 {mergeCode}，请点击左侧「立即部署小狼毫」重试。");
        }
        else
        {
            progress?.Report("4/4 云端暂无更多其他设备词库需要合并");
        }

        cfg.LastSyncUnixMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        SaveConfig(cfg);
        return new SyncResult(uploaded, downloaded, otherDevices);
    }

    public static async Task<ProfileTransferResult> BackupProfileAsync(
        WebDavConfig cfg,
        IProgress<string>? progress = null,
        CancellationToken ct = default)
    {
        ValidateConfig(cfg);
        using var http = CreateClient(cfg);

        var remoteRoot = await EnsureMkcolPathAsync(http, cfg, ct);
        var remoteProfile = $"{remoteRoot}/_pc_profile";
        var remoteIcons = $"{remoteProfile}/icons";
        await EnsureMkcolAsync(http, remoteProfile, ct);

        progress?.Report("正在备份电脑端个性化方案、模糊音与皮肤配置...");
        int count = 0;
        var backedFiles = new List<string>();

        foreach (var file in PersonalRootFiles)
        {
            var local = Path.Combine(Paths.RimeUserDir, file);
            if (!File.Exists(local) || new FileInfo(local).Length == 0) continue;
            await PutFileAsync(http, $"{remoteProfile}/{Uri.EscapeDataString(file)}", local, ct);
            backedFiles.Add(file);
            count++;
        }

        if (File.Exists(Paths.SettingsFile) && new FileInfo(Paths.SettingsFile).Length > 0)
        {
            await PutFileAsync(http, $"{remoteProfile}/ifboard_settings.json", Paths.SettingsFile, ct);
            backedFiles.Add("ifboard_settings.json");
            count++;
        }

        var iconsDir = Path.Combine(Paths.RimeUserDir, "icons");
        if (Directory.Exists(iconsDir))
        {
            var icoFiles = Directory.GetFiles(iconsDir, "*.ico").Where(p => new FileInfo(p).Length > 0).ToArray();
            if (icoFiles.Length > 0)
            {
                await EnsureMkcolAsync(http, remoteIcons, ct);
                foreach (var ico in icoFiles)
                {
                    var name = Path.GetFileName(ico);
                    await PutFileAsync(http, $"{remoteIcons}/{Uri.EscapeDataString(name)}", ico, ct);
                    backedFiles.Add($"icons/{name}");
                    count++;
                }
            }
        }

        var manifest = JsonSerializer.Serialize(new
        {
            updated_at = DateTimeOffset.Now.ToString("O"),
            installation_id = ResolveDeviceId(GetSyncRoot()),
            machine = Environment.MachineName,
            files = backedFiles
        }, JsonOptions);
        await PutBytesAsync(
            http,
            $"{remoteProfile}/ifboard_profile.json",
            Encoding.UTF8.GetBytes(manifest),
            "application/json",
            ct
        );
        count++;

        progress?.Report("正在同步词频与用户短语到云端...");
        var syncRes = await SyncUserDbAsync(cfg, null, ct);

        return new ProfileTransferResult(
            count,
            $"已上传 {count} 个电脑配置文件与 {syncRes.Uploaded} 份词库"
        );
    }

    public static async Task<ProfileTransferResult> RestoreProfileAsync(
        WebDavConfig cfg,
        IProgress<string>? progress = null,
        CancellationToken ct = default)
    {
        ValidateConfig(cfg);
        using var http = CreateClient(cfg);

        var remoteRoot = BuildBaseDirUrl(cfg);
        var remoteProfile = $"{remoteRoot}/_pc_profile";

        progress?.Report("1/4 正在读取云端个人配置快照...");
        IReadOnlyList<DavEntry> profileEntries = [];
        var hasProfile = true;
        try
        {
            profileEntries = await PropFindAsync(http, remoteProfile, ct);
        }
        catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
        {
            hasProfile = false;
        }

        int restoredCount = 0;
        int skippedEmptyCount = 0;
        if (hasProfile)
        {
            // 先保留还原前备份，避免用户误操作后悔
            var safetyBackupDir = Path.Combine(Paths.RimeUserDir, "_backup_before_restore");
            Directory.CreateDirectory(safetyBackupDir);
            foreach (var file in PersonalRootFiles)
            {
                var existing = Path.Combine(Paths.RimeUserDir, file);
                if (File.Exists(existing))
                    File.Copy(existing, Path.Combine(safetyBackupDir, file), overwrite: true);
            }

            var allowedSet = new HashSet<string>(PersonalRootFiles, StringComparer.OrdinalIgnoreCase);

            foreach (var entry in profileEntries)
            {
                if (entry.IsDirectory) continue;
                if (allowedSet.Contains(entry.Name))
                {
                    var target = Path.Combine(Paths.RimeUserDir, entry.Name);
                    if (await DownloadNonEmptyFileAsync(http, $"{remoteProfile}/{Uri.EscapeDataString(entry.Name)}", target, ct))
                        restoredCount++;
                    else
                        skippedEmptyCount++;
                }
                else if (string.Equals(entry.Name, "ifboard_settings.json", StringComparison.OrdinalIgnoreCase))
                {
                    Directory.CreateDirectory(Paths.IfboardDataDir);
                    if (await DownloadNonEmptyFileAsync(http, $"{remoteProfile}/{Uri.EscapeDataString(entry.Name)}", Paths.SettingsFile, ct))
                        restoredCount++;
                    else
                        skippedEmptyCount++;
                }
            }

            if (profileEntries.Any(e => e.IsDirectory && string.Equals(e.Name, "icons", StringComparison.OrdinalIgnoreCase)))
            {
                var remoteIcons = $"{remoteProfile}/icons";
                var iconEntries = await PropFindAsync(http, remoteIcons, ct);
                var localIcons = Path.Combine(Paths.RimeUserDir, "icons");
                Directory.CreateDirectory(localIcons);
                foreach (var ic in iconEntries)
                {
                    if (ic.IsDirectory || !ic.Name.EndsWith(".ico", StringComparison.OrdinalIgnoreCase)) continue;
                    if (await DownloadNonEmptyFileAsync(http, $"{remoteIcons}/{Uri.EscapeDataString(ic.Name)}", Path.Combine(localIcons, ic.Name), ct))
                        restoredCount++;
                    else
                        skippedEmptyCount++;
                }
            }
        }

        // 确保 Tab 翻页、内置扩展皮肤（含动态系统皮肤）与小狼毫配置规范完整写入
        progress?.Report("2/4 正在整合个人设定与系统皮肤方案...");
        var currentSettings = AppSettingsService.Load();
        WeaselSkinService.WriteWeaselCustomYaml(currentSettings);
        var currentPrefs = RimeConfigService.LoadPreferences();
        RimeConfigService.PatchDefaultYamlTabBinding(currentPrefs.TabPageFlip);
        RimeConfigService.EnsureCustomPhrasePlaceholders();

        // 从云端拉取所有设备词库（即使新装机器与旧机器同名也能无损吸收云端历史）
        progress?.Report("3/4 正在下载并合并全部云端设备词库...");
        var syncRoot = GetSyncRoot();
        Directory.CreateDirectory(syncRoot);
        var localId = ResolveDeviceId(syncRoot);
        var topEntries = await PropFindAsync(http, remoteRoot, ct);
        int dbCount = 0;
        var tempImportDirs = new List<string>();

        foreach (var dir in topEntries)
        {
            if (!dir.IsDirectory || dir.Name.StartsWith("_", StringComparison.Ordinal))
                continue;

            var remoteDeviceUrl = $"{remoteRoot}/{Uri.EscapeDataString(dir.Name)}";
            var dbFiles = await PropFindAsync(http, remoteDeviceUrl, ct);
            var isTempSelfImport = string.Equals(dir.Name, localId, StringComparison.Ordinal);
            var localTargetFolder = isTempSelfImport
                ? $"_cloud_import_{dir.Name}"
                : dir.Name;

            var targetDir = Path.Combine(syncRoot, localTargetFolder);
            Directory.CreateDirectory(targetDir);
            if (isTempSelfImport)
                tempImportDirs.Add(targetDir);

            foreach (var f in dbFiles)
            {
                if (f.IsDirectory || !f.Name.EndsWith(".userdb.txt", StringComparison.OrdinalIgnoreCase))
                    continue;
                if (await DownloadNonEmptyFileAsync(http, $"{remoteDeviceUrl}/{Uri.EscapeDataString(f.Name)}", Path.Combine(targetDir, f.Name), ct))
                    dbCount++;
                else
                    skippedEmptyCount++;
            }
        }

        progress?.Report("4/4 正在合并词频并重新部署小狼毫...");
        try
        {
            await RimeProcessService.SyncAsync();
            await RimeProcessService.DeployAsync();
        }
        finally
        {
            foreach (var tmpDir in tempImportDirs)
            {
                try { if (Directory.Exists(tmpDir)) Directory.Delete(tmpDir, recursive: true); } catch { }
            }
        }

        cfg.LastSyncUnixMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        SaveConfig(cfg);

        var summary = hasProfile
            ? $"已还原 {restoredCount} 份配置并合并 {dbCount} 份云端词库"
            : $"云端暂无电脑个人快照，已拉取并合并 {dbCount} 份手机/设备词库";
        if (skippedEmptyCount > 0)
            summary += $"（已跳过 {skippedEmptyCount} 份 0 字节异常文件）";
        return new ProfileTransferResult(restoredCount + dbCount, summary);
    }

    public static async Task<(bool Success, string Message, string? DownloadPage)> CheckAppReleaseAsync(CancellationToken ct = default)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(Paths.UserAgent);
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");

        HttpResponseMessage resp;
        try
        {
            resp = await http.GetAsync(DefaultAppReleaseApi, ct);
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            throw new InvalidOperationException("连接 GitHub 查询最新版本超时，请检查网络连接。", ex);
        }
        catch (HttpRequestException ex)
        {
            throw new InvalidOperationException($"无法连接 GitHub 发布服务：{ex.Message}", ex);
        }

        using (resp)
        {
            if (resp.StatusCode == HttpStatusCode.NotFound)
                return (true, $"当前版本：v{Paths.AppVersion}（暂未检测到更高公开 Release）", null);

            if (!resp.IsSuccessStatusCode)
            {
                var code = (int)resp.StatusCode;
                if (resp.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)
                    throw new InvalidOperationException($"GitHub API 访问受限（HTTP {code}），请稍后再试。");
                throw new InvalidOperationException($"查询版本失败（HTTP {code} {resp.ReasonPhrase}）。");
            }

            using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
            var tag = doc.RootElement.TryGetProperty("tag_name", out var t) ? t.GetString() : null;
            var htmlUrl = doc.RootElement.TryGetProperty("html_url", out var h) ? h.GetString() : null;
            if (string.IsNullOrWhiteSpace(tag))
                return (true, $"当前版本：v{Paths.AppVersion}", null);

            var cleanTag = tag.Trim().TrimStart('v', 'V').Split('-', '+')[0];
            var currentVer = Version.Parse(Paths.AppVersion);
            if (Version.TryParse(cleanTag, out var remoteVer) && remoteVer > currentVer)
                return (true, $"发现新版本：{tag}（当前本地 v{Paths.AppVersion}）", htmlUrl);

            return (true, $"当前已是最新版本：{tag}（本地 v{Paths.AppVersion}）", null);
        }
    }

    #endregion

    #region Helpers

    private static void ValidateConfig(WebDavConfig cfg)
    {
        if (string.IsNullOrWhiteSpace(cfg.Url))
            throw new InvalidOperationException("请先填写 WebDAV 服务器地址。");
        if (string.IsNullOrWhiteSpace(cfg.User) || string.IsNullOrWhiteSpace(cfg.Password))
            throw new InvalidOperationException("请填写 WebDAV 账号与应用专用密码。");
    }

    private static IEnumerable<string> GetDirSegments(WebDavConfig cfg)
    {
        var dir = string.IsNullOrWhiteSpace(cfg.Dir) ? "Ifboard" : cfg.Dir;
        return dir.Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    }

    private static string BuildBaseDirUrl(WebDavConfig cfg)
    {
        var url = cfg.Url.Trim().TrimEnd('/');
        var segments = GetDirSegments(cfg).Select(Uri.EscapeDataString).ToArray();
        return segments.Length == 0 ? url : $"{url}/{string.Join("/", segments)}";
    }

    private static async Task<string> EnsureMkcolPathAsync(HttpClient http, WebDavConfig cfg, CancellationToken ct)
    {
        var current = cfg.Url.Trim().TrimEnd('/');
        foreach (var seg in GetDirSegments(cfg))
        {
            current = $"{current}/{Uri.EscapeDataString(seg)}";
            await EnsureMkcolAsync(http, current, ct);
        }
        return current;
    }

    private static HttpClient CreateClient(WebDavConfig cfg)
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
        var raw = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{cfg.User.Trim()}:{cfg.Password}"));
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", raw);
        client.DefaultRequestHeaders.UserAgent.ParseAdd(Paths.UserAgent);
        // 关闭 Expect: 100-continue，避免部分 WebDAV 网关（如 Koofr/HAProxy）拦截 PUT 请求
        client.DefaultRequestHeaders.ExpectContinue = false;
        return client;
    }

    private static string GetSyncRoot() =>
        Paths.ReadInstallationSyncDir() ?? Path.Combine(Paths.RimeUserDir, "sync");

    private static string ResolveDeviceId(string syncRoot)
    {
        var fromYaml = Paths.ReadInstallationId();
        if (!string.IsNullOrWhiteSpace(fromYaml)) return fromYaml;

        if (Directory.Exists(syncRoot))
        {
            foreach (var sub in Directory.GetDirectories(syncRoot))
            {
                if (Directory.GetFiles(sub, "*.userdb.txt").Length > 0)
                    return Path.GetFileName(sub);
            }
        }
        return $"PC-{Environment.MachineName}";
    }

    private static async Task EnsureMkcolAsync(HttpClient http, string url, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(
            http,
            () => new HttpRequestMessage(new HttpMethod("MKCOL"), url + "/"),
            HttpCompletionOption.ResponseContentRead,
            "创建远程目录",
            ct);
        var code = (int)resp.StatusCode;
        if (code is >= 200 and < 400 or 405) return;

        // 兼容某些不接受尾部斜杠的 WebDAV 服务端，回退一次无斜杠请求
        using var resp2 = await SendWithRetryAsync(
            http,
            () => new HttpRequestMessage(new HttpMethod("MKCOL"), url),
            HttpCompletionOption.ResponseContentRead,
            "创建远程目录",
            ct);
        var code2 = (int)resp2.StatusCode;
        if (code2 is >= 200 and < 400 or 405) return;

        ThrowFriendlyDavError(resp2, "创建远程目录", url);
    }

    private static async Task PutFileAsync(HttpClient http, string url, string localPath, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(
            http,
            () =>
            {
                var fs = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                var content = new StreamContent(fs);
                content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                content.Headers.ContentLength = fs.Length;
                return new HttpRequestMessage(HttpMethod.Put, url) { Content = content };
            },
            HttpCompletionOption.ResponseContentRead,
            "上传文件",
            ct);
        EnsureSuccessOrThrowDav(resp, "上传文件", url);
    }

    private static async Task PutBytesAsync(HttpClient http, string url, byte[] data, string mime, CancellationToken ct)
    {
        using var resp = await SendWithRetryAsync(
            http,
            () =>
            {
                var content = new ByteArrayContent(data);
                content.Headers.ContentType = new MediaTypeHeaderValue(mime);
                content.Headers.ContentLength = data.Length;
                return new HttpRequestMessage(HttpMethod.Put, url) { Content = content };
            },
            HttpCompletionOption.ResponseContentRead,
            "上传配置",
            ct);
        EnsureSuccessOrThrowDav(resp, "上传配置", url);
    }

    private static async Task<bool> DownloadNonEmptyFileAsync(HttpClient http, string url, string localPath, CancellationToken ct)
    {
        var dir = Path.GetDirectoryName(localPath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        var tmpPath = localPath + ".tmp";

        const int maxAttempts = 2;
        for (int attempt = 1; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                using var resp = await SendWithRetryAsync(
                    http,
                    () => new HttpRequestMessage(HttpMethod.Get, url),
                    HttpCompletionOption.ResponseHeadersRead,
                    "下载文件",
                    ct);
                EnsureSuccessOrThrowDav(resp, "下载文件", url);

                await using (var src = await resp.Content.ReadAsStreamAsync(ct))
                await using (var dst = new FileStream(tmpPath, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    await src.CopyToAsync(dst, ct);
                }

                if (new FileInfo(tmpPath).Length == 0)
                {
                    try { File.Delete(tmpPath); } catch { }
                    return false;
                }

                File.Move(tmpPath, localPath, overwrite: true);
                return true;
            }
            catch (Exception ex) when (attempt < maxAttempts && !ct.IsCancellationRequested && IsTransientNetworkException(ex))
            {
                try { if (File.Exists(tmpPath)) File.Delete(tmpPath); } catch { }
                await Task.Delay(500 * attempt, ct);
            }
            catch
            {
                try { if (File.Exists(tmpPath)) File.Delete(tmpPath); } catch { }
                throw;
            }
        }
    }

    private static async Task<List<DavEntry>> PropFindAsync(HttpClient http, string folderUrl, CancellationToken ct)
    {
        const string bodyXml =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
            "</D:prop></D:propfind>";

        var targetUrl = folderUrl.EndsWith('/') ? folderUrl : folderUrl + "/";
        using var resp = await SendWithRetryAsync(
            http,
            () =>
            {
                var req = new HttpRequestMessage(new HttpMethod("PROPFIND"), targetUrl)
                {
                    Content = new StringContent(bodyXml, Encoding.UTF8, "application/xml")
                };
                req.Headers.Add("Depth", "1");
                return req;
            },
            HttpCompletionOption.ResponseContentRead,
            "读取远程目录",
            ct);
        if (resp.StatusCode == HttpStatusCode.NotFound)
            throw new HttpRequestException("远程目录不存在 (404)", null, HttpStatusCode.NotFound);
        EnsureSuccessOrThrowDav(resp, "读取远程目录", targetUrl);

        var xmlText = await resp.Content.ReadAsStringAsync(ct);
        var doc = XDocument.Parse(xmlText);
        var baseUriPath = Uri.TryCreate(targetUrl, UriKind.Absolute, out var bu)
            ? Uri.UnescapeDataString(bu.AbsolutePath).TrimEnd('/')
            : "";

        var list = new List<DavEntry>();
        foreach (var node in doc.Descendants().Where(x => x.Name.LocalName == "response"))
        {
            var href = node.Elements().FirstOrDefault(x => x.Name.LocalName == "href")?.Value?.Trim();
            if (string.IsNullOrEmpty(href)) continue;

            var decodedPath = Uri.TryCreate(href, UriKind.Absolute, out var absUri)
                ? Uri.UnescapeDataString(absUri.AbsolutePath)
                : Uri.UnescapeDataString(href);

            var cleanPath = decodedPath.TrimEnd('/');
            if (string.Equals(cleanPath, baseUriPath, StringComparison.OrdinalIgnoreCase))
                continue;

            var name = cleanPath.Contains('/') ? cleanPath[(cleanPath.LastIndexOf('/') + 1)..] : cleanPath;
            if (string.IsNullOrEmpty(name)) continue;

            var isDir = decodedPath.EndsWith('/') ||
                        node.Descendants().Any(x => x.Name.LocalName == "collection");

            long size = 0;
            var lenStr = node.Descendants().FirstOrDefault(x => x.Name.LocalName == "getcontentlength")?.Value;
            if (long.TryParse(lenStr, out var parsedLen)) size = parsedLen;

            var mod = DateTimeOffset.MinValue;
            var modStr = node.Descendants().FirstOrDefault(x => x.Name.LocalName == "getlastmodified")?.Value;
            if (DateTimeOffset.TryParse(modStr, out var parsedMod)) mod = parsedMod;

            list.Add(new DavEntry(name, isDir, size, mod));
        }

        return list;
    }

    private static async Task<HttpResponseMessage> SendWithRetryAsync(
        HttpClient http,
        Func<HttpRequestMessage> requestFactory,
        HttpCompletionOption completionOption,
        string actionDesc,
        CancellationToken ct)
    {
        const int maxAttempts = 2;
        for (int attempt = 1; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                using var req = requestFactory();
                var resp = await http.SendAsync(req, completionOption, ct);
                if (attempt < maxAttempts && IsTransientStatusCode(resp.StatusCode))
                {
                    resp.Dispose();
                    await Task.Delay(500 * attempt, ct);
                    continue;
                }
                return resp;
            }
            catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
            {
                if (attempt < maxAttempts)
                {
                    await Task.Delay(500 * attempt, ct);
                    continue;
                }
                throw new HttpRequestException(
                    $"{actionDesc}超时：远端服务器响应时间过长（已自动重试 {maxAttempts} 次），请检查网络或稍后重试。",
                    ex,
                    HttpStatusCode.RequestTimeout);
            }
            catch (Exception ex) when (attempt < maxAttempts && !ct.IsCancellationRequested && IsTransientNetworkException(ex))
            {
                await Task.Delay(500 * attempt, ct);
            }
        }
    }

    private static bool IsTransientStatusCode(HttpStatusCode code) => code is
        HttpStatusCode.RequestTimeout or
        HttpStatusCode.TooManyRequests or
        HttpStatusCode.InternalServerError or
        HttpStatusCode.BadGateway or
        HttpStatusCode.ServiceUnavailable or
        HttpStatusCode.GatewayTimeout;

    private static bool IsTransientNetworkException(Exception ex)
    {
        if (ex is TaskCanceledException or IOException or System.Net.Sockets.SocketException)
            return true;
        if (ex is HttpRequestException hre &&
            (hre.StatusCode is null || IsTransientStatusCode(hre.StatusCode.Value)))
            return true;
        return ex.InnerException is not null && IsTransientNetworkException(ex.InnerException);
    }

    private static void EnsureSuccessOrThrowDav(HttpResponseMessage resp, string actionDesc, string url)
    {
        if (resp.IsSuccessStatusCode) return;
        ThrowFriendlyDavError(resp, actionDesc, url);
    }

    [System.Diagnostics.CodeAnalysis.DoesNotReturn]
    private static void ThrowFriendlyDavError(HttpResponseMessage resp, string actionDesc, string url)
    {
        var code = (int)resp.StatusCode;
        var message = resp.StatusCode switch
        {
            HttpStatusCode.Unauthorized =>
                "WebDAV 认证失败 (401)：请检查账号或应用专用密码是否正确。",
            HttpStatusCode.Forbidden =>
                $"WebDAV 访问被拒绝 (403)：当前账号没有权限执行{actionDesc}。",
            HttpStatusCode.NotFound =>
                $"远程目录或文件不存在 (404)：{url}",
            HttpStatusCode.MethodNotAllowed =>
                $"WebDAV 服务端拒绝{actionDesc} (405 Method Not Allowed)：远端当前可能处于只读维护状态或不支持该操作，请稍后重试。",
            HttpStatusCode.RequestTimeout or HttpStatusCode.GatewayTimeout =>
                $"WebDAV 网关响应超时 ({code})：{actionDesc}未完成，请稍后重试。",
            HttpStatusCode.TooManyRequests =>
                "WebDAV 请求过于频繁 (429)：服务端临时限流，请稍后再试。",
            HttpStatusCode.InternalServerError or HttpStatusCode.BadGateway or HttpStatusCode.ServiceUnavailable =>
                $"WebDAV 服务端暂时不可用 ({code})：远端服务器正在维护或过载（已自动重试），请稍后再试。",
            _ =>
                $"{actionDesc}失败 (HTTP {code} {resp.ReasonPhrase})：{url}"
        };

        throw new HttpRequestException(message, null, resp.StatusCode);
    }

    #endregion
}
