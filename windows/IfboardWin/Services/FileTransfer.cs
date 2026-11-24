using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;

namespace IfboardWin.Services;

/// <summary>
/// 主动发送文件（类似 LocalSend）：走剪贴板同步同一条加密连接，分块传输、断点续传、整文件 SHA-256 校验。
/// 协议（每条消息都带会话随机数 s / c）：
///   发送方 fbeg{id,name,size,sha,mime} → 接收方 fok{id,off}（off = 已有字节数，可续传；off &lt; 0 表示拒收并附 why）
///   发送方 fdat{id,off}+正文（≤ 512 KB）→ 接收方 fack{id,off}（收到后的总字节数）；发送方最多领先 6 块
///   发送方 fend{id} → 接收方校验后 fdone{id,ok,file}
/// 手机在另一层路由器后时电脑连不到手机，所以电脑要发的文件先排队，手机一连上（弹出键盘 / 点「接收文件」）就发。
/// </summary>
public sealed partial class ClipboardSyncService
{
    private const int ChunkSize = 512 * 1024;
    private const int WindowChunks = 6;
    private static readonly TimeSpan XferStepTimeout = TimeSpan.FromSeconds(30);

    /// <summary>托盘通知：标题、内容、点击后要在资源管理器里选中的文件（可空）</summary>
    public static event Action<string, string, string?>? Notify;

    private static void RaiseNotify(string title, string text, string? path = null)
    {
        try { Notify?.Invoke(title, text, path); } catch { }
    }

    /// <summary>发送方等待接收方回应的状态</summary>
    private sealed class XferWait
    {
        public TaskCompletionSource<(long Off, string? Why)> Ready { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<(bool Ok, string? File)> Done { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public SemaphoreSlim Progress { get; } = new(0);
        public long Acked;
    }

    /// <summary>接收中的文件</summary>
    private sealed class Incoming
    {
        public required string Id { get; init; }
        public required string Name { get; init; }
        public required long Size { get; init; }
        public required string Sha { get; init; }
        public required string PartPath { get; init; }
        public required FileStream Stream { get; init; }
        public required IncrementalHash Hash { get; init; }
        public long Got;
        public DateTime LastUi = DateTime.MinValue;
    }

    // ---------------------------------------------------------------- 发送队列（电脑 → 手机）

    private static readonly List<string> PendingSends = new();

    /// <summary>右键「发送到 → Ifboard 手机」或拖放：把文件加入队列；手机在线就立即发</summary>
    public static void EnqueueSend(IEnumerable<string> paths)
    {
        var list = paths.Where(p => !string.IsNullOrWhiteSpace(p)).ToList();
        var files = list.Where(File.Exists).Select(Path.GetFullPath).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var dirs = list.Count(Directory.Exists);
        if (files.Count == 0)
        {
            RaiseNotify("没有可发送的文件", dirs > 0 ? "暂不支持直接发送文件夹，请选中里面的文件。" : "找不到要发送的文件。");
            return;
        }
        var cur = Current;
        if (cur == null)
        {
            if (AppSettingsService.Load().ClipboardSyncEnabled)
                RaiseNotify("剪贴板同步没有运行", $"发送文件和剪贴板同步走同一条加密连接，当前状态：{Status}。请在控制中心检查 WebDAV 账号。");
            else
                RaiseNotify("剪贴板同步未开启", "发送文件和剪贴板同步走同一条加密连接，请先在控制中心开启剪贴板同步。");
            return;
        }
        lock (PendingSends)
        {
            foreach (var f in files)
                if (!PendingSends.Contains(f, StringComparer.OrdinalIgnoreCase)) PendingSends.Add(f);
        }
        Log($"send queued {files.Count} file(s)");
        var skipped = dirs > 0 ? $"（跳过 {dirs} 个文件夹）" : "";
        if (!cur.KickOutbox())
            RaiseNotify("已加入发送队列", cur._sessions.Keys.Any(x => !x.CanXfer)
                ? $"{files.Count} 个文件{skipped}。在线的手机版本太旧收不了文件，请把手机上的 Ifboard 更新到 1.3.3 或以上。"
                : $"{files.Count} 个文件{skipped}。手机弹出键盘，或点手机通知栏的「接收文件」开关，就会自动发过去。");
        else if (skipped.Length > 0)
            RaiseNotify("开始发送", $"{files.Count} 个文件{skipped}");
    }

    /// <summary>有手机在线就开始发；返回是否已开始</summary>
    private bool KickOutbox()
    {
        // 只发给声明支持收文件的手机（旧版手机收不了，会白等超时）
        var target = _sessions.Keys.FirstOrDefault(s => s.CanXfer && !s.Sending);
        if (target == null) return _sessions.Keys.Any(s => s.CanXfer && s.Sending);
        _ = Task.Run(() => DeliverOutboxAsync(target));
        return true;
    }

    private async Task DeliverOutboxAsync(Session s)
    {
        List<string> files;
        lock (PendingSends)
        {
            if (PendingSends.Count == 0 || s.Sending) return;
            s.Sending = true;
            files = PendingSends.ToList();
            PendingSends.Clear();
        }
        var ok = 0;
        var failed = new List<string>();
        string? lastError = null;
        try
        {
            RaiseNotify("正在发送到 " + s.PeerName, files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} 个文件");
            for (var i = 0; i < files.Count; i++)
            {
                try
                {
                    await SendFileAsync(s, files[i], i + 1, files.Count);
                    ok++;
                }
                catch (Exception e)
                {
                    lastError = e.Message;
                    failed.Add(files[i]);
                    Log("send file failed: " + Err(e));
                    if (!_sessions.ContainsKey(s)) { failed.AddRange(files.Skip(i + 1)); break; }
                }
            }
        }
        finally
        {
            s.Sending = false;
        }
        // 发送途中又加入队列的文件：接着发
        bool more;
        lock (PendingSends) more = PendingSends.Count > 0;
        if (failed.Count > 0)
        {
            // 没发完的放回队列，手机下次连上时接着发（已传的部分会续传）
            lock (PendingSends) PendingSends.InsertRange(0, failed.Where(f => File.Exists(f)));
            // 还有别的手机在线：马上换一台试
            if (_sessions.Keys.Any(x => x != s && x.CanXfer && !x.Sending)) KickOutbox();
        }
        UpdateIdleStatus();
        if (more && failed.Count == 0) KickOutbox();
        if (failed.Count == 0)
            RaiseNotify("已发送到 " + s.PeerName, ok == 1 ? Path.GetFileName(files[0]) : $"{ok} 个文件");
        else
            RaiseNotify("部分文件未发送", $"成功 {ok} 个，{failed.Count} 个已放回队列（{lastError}）。手机重新连上后会接着发。");
    }

    private async Task SendFileAsync(Session s, string path, int index, int count)
    {
        var ct = _cts.Token;
        var fi = new FileInfo(path);
        var name = SafeFileName(fi.Name);
        string sha;
        await using (var hs = File.OpenRead(path))
            sha = Convert.ToHexString(await SHA256.HashDataAsync(hs, ct)).ToLowerInvariant();
        var id = sha[..24];
        var w = new XferWait();
        s.Xfers[id] = w;
        try
        {
            await SendAsync(s, SessionMsg(s, "fbeg",
                [new("id", id), new("name", name), new("size", fi.Length), new("sha", sha), new("mime", MimeOfFile(name))]), ct);
            var (off, why) = await w.Ready.Task.WaitAsync(XferStepTimeout, ct);
            if (off < 0) throw new IOException(why ?? "对方拒收");
            Volatile.Write(ref w.Acked, off);
            await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, ChunkSize, true);
            fs.Seek(off, SeekOrigin.Begin);
            var buf = new byte[ChunkSize];
            var sent = off;
            var lastUi = DateTime.MinValue;
            while (sent < fi.Length)
            {
                // 最多领先接收方 6 块，避免把内存与网络缓冲撑满
                while (sent - Volatile.Read(ref w.Acked) >= (long)WindowChunks * ChunkSize)
                    if (!await w.Progress.WaitAsync(XferStepTimeout, ct)) throw new TimeoutException("手机没有回应");
                var n = await fs.ReadAsync(buf.AsMemory(0, (int)Math.Min(ChunkSize, fi.Length - sent)), ct);
                if (n <= 0) throw new IOException("读取文件失败");
                await SendAsync(s, SessionMsg(s, "fdat", [new("id", id), new("off", sent)], buf[..n]), ct);
                sent += n;
                if (DateTime.UtcNow - lastUi > TimeSpan.FromMilliseconds(500))
                {
                    lastUi = DateTime.UtcNow;
                    SetStatus($"正在发送 {index}/{count}：{name} {sent * 100 / Math.Max(1, fi.Length)}%");
                }
            }
            await SendAsync(s, SessionMsg(s, "fend", [new("id", id)]), ct);
            var (ok, _) = await w.Done.Task.WaitAsync(TimeSpan.FromSeconds(120), ct);
            if (!ok) throw new IOException("手机校验失败");
            Log($"sent file {fi.Length}B");
        }
        finally
        {
            s.Xfers.TryRemove(id, out _);
        }
    }

    // ---------------------------------------------------------------- 接收（手机 → 电脑）

    private static string PartDir()
    {
        var dir = Path.Combine(DownloadsDir(), "Ifboard", ".partial");
        var di = Directory.CreateDirectory(dir);
        try { di.Attributes |= FileAttributes.Hidden; } catch { }
        // 7 天都没续传的半截文件：删掉
        try
        {
            foreach (var f in di.EnumerateFiles("*.part"))
                if (f.LastWriteTimeUtc < DateTime.UtcNow.AddDays(-7)) f.Delete();
        }
        catch { }
        return dir;
    }

    /// <summary>处理文件传输相关消息；返回 false 表示不是文件消息</summary>
    private async Task<bool> HandleXferAsync(Session s, ClipMessage m, CancellationToken ct)
    {
        var id = m.Str("id") ?? "";
        switch (m.Op)
        {
            // —— 发送方收到的回应
            case "fok":
                if (s.Xfers.TryGetValue(id, out var w1)) w1.Ready.TrySetResult((m.Long("off"), m.Str("why")));
                return true;
            case "fack":
                if (s.Xfers.TryGetValue(id, out var w2))
                {
                    Volatile.Write(ref w2.Acked, m.Long("off"));
                    w2.Progress.Release();
                }
                return true;
            case "fdone":
                if (s.Xfers.TryGetValue(id, out var w3))
                    w3.Done.TrySetResult((m.Header["ok"]?.GetValue<bool>() == true, m.Str("file")));
                return true;

            // —— 接收方
            case "fbeg":
                await BeginIncomingAsync(s, m, ct);
                return true;
            case "fdat":
                if (s.Incoming.TryGetValue(id, out var inc) && m.Long("off") == inc.Got)
                {
                    await inc.Stream.WriteAsync(m.Body, ct);
                    inc.Hash.AppendData(m.Body);
                    inc.Got += m.Body.Length;
                    if (DateTime.UtcNow - inc.LastUi > TimeSpan.FromMilliseconds(500))
                    {
                        inc.LastUi = DateTime.UtcNow;
                        SetStatus($"正在接收 {inc.Name} {inc.Got * 100 / Math.Max(1, inc.Size)}%");
                    }
                }
                await SendAsync(s, SessionMsg(s, "fack",
                    [new("id", id), new("off", s.Incoming.TryGetValue(id, out var cur) ? cur.Got : -1L)]), ct);
                return true;
            case "fend":
                await FinishIncomingAsync(s, id, ct);
                return true;
        }
        return false;
    }

    private async Task BeginIncomingAsync(Session s, ClipMessage m, CancellationToken ct)
    {
        var id = m.Str("id") ?? "";
        var size = m.Long("size");
        var sha = (m.Str("sha") ?? "").ToLowerInvariant();
        var name = SafeFileName(m.Str("name") ?? "file");
        async Task Reject(string why)
        {
            Log("file rejected: " + why);
            await SendAsync(s, SessionMsg(s, "fok", [new("id", id), new("off", -1L), new("why", why)]), ct);
        }
        if (id.Length < 16 || !id.All(char.IsAsciiHexDigitLower) || sha.Length != 64 || size < 0)
        {
            await Reject("参数错误");
            return;
        }
        if (s.Incoming.TryRemove(id, out var old)) CloseIncoming(old);
        // 网络闪断后手机已用新连接续传，旧连接还没超时、仍占着同一个 .part：先把它关掉
        foreach (var other in _sessions.Keys)
            if (other != s && other.DeviceId == s.DeviceId && other.Incoming.TryRemove(id, out var stale)) CloseIncoming(stale);
        try
        {
            var dir = PartDir();
            var part = Path.Combine(dir, $"{SafeFileName(s.DeviceId)}-{id}.part");
            var have = File.Exists(part) ? new FileInfo(part).Length : 0;
            if (have > size) { File.Delete(part); have = 0; }
            var free = new DriveInfo(Path.GetPathRoot(dir)!).AvailableFreeSpace;
            if (free < size - have + 64L * 1024 * 1024)
            {
                await Reject("电脑磁盘空间不足");
                return;
            }
            var fs = new FileStream(part, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, ChunkSize, true);
            var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            // 续传：先把已有部分算进校验
            if (have > 0)
            {
                var buf = new byte[ChunkSize];
                int n;
                while ((n = await fs.ReadAsync(buf, ct)) > 0) hash.AppendData(buf, 0, n);
            }
            fs.Seek(have, SeekOrigin.Begin);
            s.Incoming[id] = new Incoming { Id = id, Name = name, Size = size, Sha = sha, PartPath = part, Stream = fs, Hash = hash, Got = have };
            Log($"receiving file {size}B (resume at {have})");
            await SendAsync(s, SessionMsg(s, "fok", [new("id", id), new("off", have)]), ct);
        }
        catch (Exception e)
        {
            Log("begin receive failed: " + Err(e));
            await Reject("电脑无法保存：" + e.Message);
        }
    }

    private async Task FinishIncomingAsync(Session s, string id, CancellationToken ct)
    {
        if (!s.Incoming.TryRemove(id, out var inc))
        {
            await SendAsync(s, SessionMsg(s, "fdone", [new("id", id), new("ok", false)]), ct);
            return;
        }
        string? final = null;
        try
        {
            await inc.Stream.FlushAsync(ct);
            inc.Stream.Dispose();
            var actual = Convert.ToHexString(inc.Hash.GetHashAndReset()).ToLowerInvariant();
            inc.Hash.Dispose();
            if (inc.Got != inc.Size || actual != inc.Sha)
            {
                File.Delete(inc.PartPath);
                Log("receive verify failed");
                await SendAsync(s, SessionMsg(s, "fdone", [new("id", id), new("ok", false)]), ct);
                return;
            }
            final = UniqueTarget(Path.Combine(DownloadsDir(), "Ifboard"), inc.Name);
            File.Move(inc.PartPath, final);
            Log($"received file {inc.Size}B");
            await SendAsync(s, SessionMsg(s, "fdone", [new("id", id), new("ok", true), new("file", Path.GetFileName(final))]), ct);
            UpdateIdleStatus();
            RaiseNotify($"收到 {s.PeerName} 的文件", $"{Path.GetFileName(final)}（已存到 下载\\Ifboard，点此打开）", final);
        }
        catch (Exception e)
        {
            Log("finish receive failed: " + Err(e));
            await SendAsync(s, SessionMsg(s, "fdone", [new("id", id), new("ok", false)]), ct);
        }
    }

    private static string UniqueTarget(string dir, string name)
    {
        Directory.CreateDirectory(dir);
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 0; i < 10000; i++)
        {
            var p = Path.Combine(dir, i == 0 ? name : $"{stem} ({i}){ext}");
            if (!File.Exists(p)) return p;
        }
        return Path.Combine(dir, $"{stem} ({Guid.NewGuid():N}){ext}");
    }

    private static void CloseIncoming(Incoming inc)
    {
        try { inc.Stream.Dispose(); } catch { }
        try { inc.Hash.Dispose(); } catch { }
    }

    /// <summary>连接断开：关掉接收中的文件（保留 .part 以便续传），唤醒等待中的发送</summary>
    private static void CleanupXfers(Session s)
    {
        foreach (var inc in s.Incoming.Values) CloseIncoming(inc);
        s.Incoming.Clear();
        foreach (var w in s.Xfers.Values)
        {
            w.Ready.TrySetResult((-1, "连接已断开"));
            w.Done.TrySetResult((false, null));
            w.Progress.Release();
        }
    }
}
