using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using IfboardWin.Models;

namespace IfboardWin.Services;

public static class GrammarModelService
{
    public const string ReleaseApiUrl =
        "https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/tags/LTS";

    public const string DirectFallbackUrl =
        "https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram";

    private const string ModelFileName = "wanxiang-lts-zh-hans.gram";
    private const string HeaderMagic = "Rime::Grammar/";

    private static readonly HttpClient Http = CreateHttpClient();

    private static HttpClient CreateHttpClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate
        };
        var client = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromMinutes(20)
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(Paths.UserAgent);
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }

    public static bool IsLocalModelValid()
    {
        var path = Paths.GrammarFile;
        if (!File.Exists(path)) return false;
        var info = new FileInfo(path);
        if (info.Length < 1024 * 1024) return false;
        return VerifyGrammarHeader(path);
    }

    public static long GetLocalModelSizeBytes() =>
        File.Exists(Paths.GrammarFile) ? new FileInfo(Paths.GrammarFile).Length : 0;

    public static long GetPendingPartSizeBytes() =>
        File.Exists(Paths.GrammarPartFile) ? new FileInfo(Paths.GrammarPartFile).Length : 0;

    public static GrammarMeta? ReadLocalMeta()
    {
        try
        {
            if (!File.Exists(Paths.GrammarMetaFile)) return null;
            var json = File.ReadAllText(Paths.GrammarMetaFile, Encoding.UTF8);
            return JsonSerializer.Deserialize<GrammarMeta>(json);
        }
        catch
        {
            return null;
        }
    }

    public static async Task<GrammarRemoteInfo> FetchRemoteInfoAsync(CancellationToken ct = default)
    {
        HttpResponseMessage resp;
        try
        {
            resp = await Http.GetAsync(ReleaseApiUrl, ct);
        }
        catch (TaskCanceledException ex) when (!ct.IsCancellationRequested)
        {
            throw new HttpRequestException("连接 GitHub 查询模型版本超时，请检查网络连接后重试。", ex);
        }
        catch (HttpRequestException ex)
        {
            throw new HttpRequestException($"无法连接 GitHub 仓库：{ex.Message}", ex);
        }

        using (resp)
        {
            if (!resp.IsSuccessStatusCode)
            {
                var code = (int)resp.StatusCode;
                if (resp.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)
                    throw new HttpRequestException($"GitHub API 访问受限（HTTP {code}），请稍后再试或直接点击下载（将走直链断点下载）。", null, resp.StatusCode);
                if (resp.StatusCode == HttpStatusCode.NotFound)
                    throw new HttpRequestException("未找到 amzxyz/rime-wanxiang-data LTS Release 页面（HTTP 404）。", null, resp.StatusCode);
                throw new HttpRequestException($"查询云端语法模型失败（HTTP {code} {resp.ReasonPhrase}）。", null, resp.StatusCode);
            }

            var body = await resp.Content.ReadAsStringAsync(ct);

            using var doc = JsonDocument.Parse(body);
            if (!doc.RootElement.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                throw new InvalidOperationException("GitHub Release 返回中未找到 assets 字段。");

            foreach (var asset in assets.EnumerateArray())
            {
                var name = asset.GetProperty("name").GetString();
                if (!string.Equals(name, ModelFileName, StringComparison.Ordinal))
                    continue;

                var url = asset.GetProperty("browser_download_url").GetString() ?? DirectFallbackUrl;
                var size = asset.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0L;
                var updatedAt = asset.TryGetProperty("updated_at", out var upd) ? (upd.GetString() ?? "") : "";
                string? sha256 = null;
                if (asset.TryGetProperty("digest", out var dig) && dig.ValueKind == JsonValueKind.String)
                {
                    var raw = dig.GetString();
                    if (!string.IsNullOrEmpty(raw) && raw.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                        sha256 = raw["sha256:".Length..].Trim().ToLowerInvariant();
                }

                return new GrammarRemoteInfo(url, size, sha256, updatedAt);
            }

            throw new InvalidOperationException($"未在 amzxyz/rime-wanxiang-data LTS 中找到 {ModelFileName}。");
        }
    }

    public static bool HasUpdateAvailable(GrammarRemoteInfo remote)
    {
        if (!IsLocalModelValid()) return true;
        var localMeta = ReadLocalMeta();
        if (localMeta == null)
        {
            // 若是安装器预装的离线模型且无元数据时，用精确字节数粗略对照一次；字节不一致则提示更新
            var localLen = GetLocalModelSizeBytes();
            return remote.SizeBytes > 0 && localLen != remote.SizeBytes;
        }

        if (!string.IsNullOrEmpty(remote.Sha256) && !string.IsNullOrEmpty(localMeta.Sha256))
            return !string.Equals(remote.Sha256, localMeta.Sha256, StringComparison.OrdinalIgnoreCase);

        return remote.SizeBytes > 0 && localMeta.SizeBytes != remote.SizeBytes;
    }

    public static async Task DownloadAndInstallAsync(
        GrammarRemoteInfo? remoteInfo = null,
        IProgress<(long Downloaded, long Total, double BytesPerSec, string Stage)>? progress = null,
        CancellationToken ct = default)
    {
        Directory.CreateDirectory(Paths.RimeUserDir);
        Directory.CreateDirectory(Paths.IfboardDataDir);

        progress?.Report((0, 0, 0, "正在查询万象语法模型版本信息..."));
        var info = remoteInfo ?? await TryFetchOrFallbackAsync(ct);

        var partPath = Paths.GrammarPartFile;
        var partTagPath = partPath + ".tag";
        var remoteTag = !string.IsNullOrEmpty(info.Sha256) ? info.Sha256 : info.UpdatedAt;
        if (File.Exists(partPath) && !string.IsNullOrEmpty(remoteTag))
        {
            var prevTag = File.Exists(partTagPath) ? File.ReadAllText(partTagPath, Encoding.UTF8).Trim() : "";
            if (!string.Equals(prevTag, remoteTag, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(partPath);
                if (File.Exists(partTagPath)) File.Delete(partTagPath);
            }
        }
        if (!string.IsNullOrEmpty(remoteTag))
            File.WriteAllText(partTagPath, remoteTag, new UTF8Encoding(false));

        long existingBytes = File.Exists(partPath) ? new FileInfo(partPath).Length : 0L;
        if (info.SizeBytes > 0 && existingBytes > info.SizeBytes)
        {
            File.Delete(partPath);
            existingBytes = 0L;
        }

        using var req = new HttpRequestMessage(HttpMethod.Get, info.DownloadUrl);
        if (existingBytes > 0)
            req.Headers.Range = new RangeHeaderValue(existingBytes, null);

        using var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        if (resp.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable)
        {
            // 若已完整位于 .part，则直接进入校验阶段
            if (existingBytes == 0)
                throw new InvalidOperationException("服务器返回 Range 无效，请删除缓存后重试。");
        }
        else
        {
            resp.EnsureSuccessStatusCode();
            var append = resp.StatusCode == HttpStatusCode.PartialContent && existingBytes > 0;
            if (!append) existingBytes = 0;

            var contentLen = resp.Content.Headers.ContentLength ?? 0L;
            var totalBytes = info.SizeBytes > 0
                ? info.SizeBytes
                : (contentLen > 0 ? existingBytes + contentLen : 0L);

            await using var src = await resp.Content.ReadAsStreamAsync(ct);
            await using var dst = new FileStream(
                partPath,
                append ? FileMode.Append : FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                bufferSize: 131072,
                useAsync: true
            );

            var buf = new byte[131072];
            long written = existingBytes;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            long lastReportMs = 0;
            long bytesSinceSample = 0;
            double speed = 0;

            progress?.Report((written, totalBytes, 0, "正在下载万象语法模型（支持断点续传）..."));

            while (true)
            {
                var n = await src.ReadAsync(buf.AsMemory(0, buf.Length), ct);
                if (n <= 0) break;
                await dst.WriteAsync(buf.AsMemory(0, n), ct);
                written += n;
                bytesSinceSample += n;

                var elapsedMs = sw.ElapsedMilliseconds;
                if (elapsedMs - lastReportMs >= 250)
                {
                    var dt = (elapsedMs - lastReportMs) / 1000.0;
                    if (dt > 0) speed = bytesSinceSample / dt;
                    lastReportMs = elapsedMs;
                    bytesSinceSample = 0;
                    progress?.Report((written, totalBytes, speed, "正在下载万象语法模型（支持断点续传）..."));
                }
            }

            await dst.FlushAsync(ct);
        }

        var partSize = new FileInfo(partPath).Length;
        var verifyTotal = info.SizeBytes > 0 ? info.SizeBytes : partSize;
        progress?.Report((verifyTotal, verifyTotal, 0, "正在校验文件头与 SHA-256 指纹..."));
        if (!VerifyGrammarHeader(partPath))
        {
            File.Delete(partPath);
            if (File.Exists(partTagPath)) File.Delete(partTagPath);
            throw new InvalidOperationException("下载的文件不是有效的 Rime 语法模型（缺少 Rime::Grammar/ 头）。");
        }

        var actualSha = await ComputeSha256Async(partPath, ct);
        if (!string.IsNullOrEmpty(info.Sha256) &&
            !string.Equals(actualSha, info.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(partPath);
            if (File.Exists(partTagPath)) File.Delete(partTagPath);
            throw new InvalidOperationException(
                $"SHA-256 校验失败：期望 {info.Sha256[..12]}...，实际 {actualSha[..12]}...");
        }

        progress?.Report((verifyTotal, verifyTotal, 0, "正在替换模型并重新部署小狼毫..."));
        await RimeProcessService.StopWeaselAsync();

        long finalLen;
        try
        {
            File.Move(partPath, Paths.GrammarFile, overwrite: true);
            if (File.Exists(partTagPath)) File.Delete(partTagPath);
            finalLen = new FileInfo(Paths.GrammarFile).Length;
            SaveMeta(new GrammarMeta
            {
                Sha256 = actualSha,
                UpdatedAt = info.UpdatedAt,
                SizeBytes = finalLen
            });
        }
        finally
        {
            await RimeProcessService.EnsureWeaselServerRunningAsync();
        }

        await RimeProcessService.DeployAsync();
        progress?.Report((finalLen, finalLen, 0, "万象语法模型已就绪并部署完成"));
    }

    public static async Task DeleteLocalModelAsync()
    {
        await RimeProcessService.StopWeaselAsync();
        try
        {
            if (File.Exists(Paths.GrammarFile)) File.Delete(Paths.GrammarFile);
            if (File.Exists(Paths.GrammarPartFile)) File.Delete(Paths.GrammarPartFile);
            var tagPath = Paths.GrammarPartFile + ".tag";
            if (File.Exists(tagPath)) File.Delete(tagPath);
            if (File.Exists(Paths.GrammarMetaFile)) File.Delete(Paths.GrammarMetaFile);
        }
        finally
        {
            await RimeProcessService.EnsureWeaselServerRunningAsync();
        }
        await RimeProcessService.DeployAsync();
    }

    private static async Task<GrammarRemoteInfo> TryFetchOrFallbackAsync(CancellationToken ct)
    {
        try
        {
            return await FetchRemoteInfoAsync(ct);
        }
        catch
        {
            return new GrammarRemoteInfo(DirectFallbackUrl, 0, null, "");
        }
    }

    private static bool VerifyGrammarHeader(string path)
    {
        try
        {
            using var fs = File.OpenRead(path);
            var header = new byte[HeaderMagic.Length];
            var n = fs.Read(header, 0, header.Length);
            if (n < HeaderMagic.Length) return false;
            return Encoding.ASCII.GetString(header) == HeaderMagic;
        }
        catch
        {
            return false;
        }
    }

    private static async Task<string> ComputeSha256Async(string path, CancellationToken ct)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 131072, useAsync: true);
        using var sha = SHA256.Create();
        var hash = await sha.ComputeHashAsync(fs, ct);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static void SaveMeta(GrammarMeta meta)
    {
        Directory.CreateDirectory(Paths.IfboardDataDir);
        var json = JsonSerializer.Serialize(meta, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(Paths.GrammarMetaFile, json, new UTF8Encoding(false));
    }
}
