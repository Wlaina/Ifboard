using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Drawing = System.Drawing;
using WinForms = System.Windows.Forms;
using System.Windows.Threading;
using IfboardWin.Models;

namespace IfboardWin.Services;

/// <summary>
/// 剪贴板同步（电脑端 = 服务端）：
/// · 局域网：TCP 47321 接收手机连接（端到端 AES-GCM，会话双方随机数防重放）；UDP 47322 应答带 HMAC 的发现请求。
/// · 手机在另一层路由器后面时广播到不了：把本机局域网地址加密写进 WebDAV（peer-*.dat），手机读到后直连。
/// · WebDAV 兜底：没有手机在线时，复制内容加密写入 clip-*.dat；轮询手机写入的内容（自适应 20~60 秒）。
/// 空闲时只有监听线程与剪贴板事件，几乎不占资源。
/// </summary>
public sealed class ClipboardSyncService : IDisposable
{
    public const int TcpPort = 47321;
    public const int UdpPort = 47322;
    public static readonly IPAddress MulticastGroup = IPAddress.Parse("239.255.73.21");

    private const int MaxFrame = 24 * 1024 * 1024;
    private const int MaxLanItem = 16 * 1024 * 1024;
    private const int MaxDavItem = 5 * 1024 * 1024;
    private const int MaxSessions = 8;
    private static readonly TimeSpan RemoteFreshWindow = TimeSpan.FromMinutes(10);

    public static ClipboardSyncService? Current { get; private set; }

    public static event Action<string>? StatusChanged;
    public static string Status { get; private set; } = "未开启";

    public string Fingerprint => _keys.Fingerprint;

    private readonly ClipSyncKeys _keys;
    private readonly WebDavConfig _dav;
    private readonly bool _davReady;
    private readonly string _deviceId;
    private readonly string _deviceName = Environment.MachineName;
    private readonly Dispatcher _ui;
    private readonly CancellationTokenSource _cts = new();
    private readonly ConcurrentDictionary<Session, byte> _sessions = new();
    private readonly ConcurrentDictionary<string, long> _seenIds = new();
    private readonly Dictionary<string, DateTimeOffset> _davSeen = new();
    private readonly SemaphoreSlim _davLock = new(1, 1);

    private TcpListener? _tcp;
    private UdpClient? _udp;
    private ClipWindow? _clipWindow;
    private DispatcherTimer? _debounce;
    private Timer? _pollTimer;
    private Timer? _peerTimer;
    private HttpClient? _http;
    private ClipItem? _latest;
    private string? _suppressHash;
    private DateTime _lastRemoteChangeUtc = DateTime.UtcNow;
    /// <summary>最近一次与手机局域网通信的时间</summary>
    private DateTime _lastLanContactUtc = DateTime.MinValue;
    /// <summary>手机最近一次写下「不在局域网」标记的时间（来自网盘）</summary>
    private DateTimeOffset _phoneAwayMarkUtc = DateTimeOffset.MinValue;
    private CancellationTokenSource? _davPutCts;
    private bool _disposed;

    private sealed record ClipItem(string Id, string Mime, byte[] Data, long Ts, string From);

    private sealed class Session
    {
        public required TcpClient Client { get; init; }
        public required NetworkStream Stream { get; init; }
        public required string SNonce { get; init; }
        public string CNonce { get; set; } = "";
        public string PeerName { get; set; } = "手机";
        public SemaphoreSlim WriteLock { get; } = new(1, 1);
        public ConcurrentDictionary<string, TaskCompletionSource> PendingAcks { get; } = new();
    }

    private ClipboardSyncService(ClipSyncKeys keys, WebDavConfig dav, string deviceId, Dispatcher ui)
    {
        _keys = keys;
        _dav = dav;
        _davReady = !string.IsNullOrWhiteSpace(dav.Url) && !string.IsNullOrWhiteSpace(dav.User) &&
                    !string.IsNullOrWhiteSpace(dav.Password);
        _deviceId = deviceId;
        _ui = ui;
    }

    // ---------------------------------------------------------------- lifecycle

    /// <summary>按设置启动（已在运行则先停止）。需要 WebDAV 账号密码用于推导密钥。</summary>
    public static async Task<string> StartAsync(Dispatcher ui)
    {
        Stop();
        var settings = AppSettingsService.Load();
        if (!settings.ClipboardSyncEnabled)
        {
            SetStatus("未开启");
            return Status;
        }
        var dav = WebDavService.LoadConfig();
        if (string.IsNullOrWhiteSpace(dav.User) || string.IsNullOrWhiteSpace(dav.Password))
        {
            SetStatus("需要先填写 WebDAV 账号（用于加密配对）");
            return Status;
        }
        if (string.IsNullOrWhiteSpace(settings.ClipSyncDeviceId))
        {
            settings.ClipSyncDeviceId = "pc" + Convert.ToHexString(RandomNumberGenerator.GetBytes(5)).ToLowerInvariant();
            AppSettingsService.Save(settings);
        }
        var keys = await Task.Run(() => ClipSyncKeys.Derive(dav.User, dav.Password));
        var svc = new ClipboardSyncService(keys, dav, settings.ClipSyncDeviceId!, ui);
        try
        {
            await ui.InvokeAsync(svc.StartClipboardListener);
            svc.StartNetwork();
            Current = svc;
            SetStatus($"等待手机连接（钥匙 {keys.Fingerprint}）");
            Log($"started id={svc._deviceId} dav={svc._davReady} fp={keys.Fingerprint}");
        }
        catch (Exception ex)
        {
            svc.Dispose();
            SetStatus($"启动失败：{ex.Message}");
            Log("start failed " + Err(ex));
        }
        return Status;
    }

    public static void Stop()
    {
        var cur = Current;
        Current = null;
        cur?.Dispose();
        if (cur != null) SetStatus("未开启");
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Cancel();
        try { _tcp?.Stop(); } catch { }
        try { _udp?.Dispose(); } catch { }
        foreach (var s in _sessions.Keys) { try { s.Client.Dispose(); } catch { } }
        _pollTimer?.Dispose();
        _peerTimer?.Dispose();
        _http?.Dispose();
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        _ui.BeginInvoke(() =>
        {
            _debounce?.Stop();
            if (_clipWindow != null)
            {
                try { RemoveClipboardFormatListener(_clipWindow.Handle); } catch { }
                _clipWindow.DestroyHandle();
                _clipWindow = null;
            }
        });
    }

    private static readonly object LogLock = new();

    /// <summary>诊断日志：只记录事件类型与错误信息，绝不写入剪贴板内容或账号信息；超过 256 KB 自动截断</summary>
    internal static void Log(string line)
    {
        try
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Ifboard", "clipsync.log");
            lock (LogLock)
            {
                var fi = new FileInfo(path);
                if (fi.Exists && fi.Length > 256 * 1024) fi.Delete();
                File.AppendAllText(path, $"{DateTime.Now:MM-dd HH:mm:ss} {line}{Environment.NewLine}");
            }
        }
        catch
        {
            // 日志失败不影响同步
        }
    }

    private static string Err(Exception e) => $"{e.GetType().Name}: {e.Message}";

    private static void SetStatus(string s)
    {
        Status = s;
        StatusChanged?.Invoke(s);
    }

    private void UpdateIdleStatus()
    {
        if (_disposed) return;
        var peer = _sessions.Keys.FirstOrDefault();
        SetStatus(peer != null
            ? $"已连接：{peer.PeerName}（局域网）"
            : $"等待手机连接（钥匙 {_keys.Fingerprint}）");
    }

    // ---------------------------------------------------------------- clipboard (UI thread)

    private const int WM_CLIPBOARDUPDATE = 0x031D;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    /// <summary>
    /// 只收消息的 Win32 窗口（HWND_MESSAGE）。刻意不用 WPF 的 HwndSource：
    /// 它会顺带初始化 WPF 渲染引擎，加载 Direct3D 与显卡驱动，常驻内存多出约 40 MB。
    /// </summary>
    private sealed class ClipWindow : WinForms.NativeWindow
    {
        private readonly Action _onUpdate;

        public ClipWindow(Action onUpdate)
        {
            _onUpdate = onUpdate;
            CreateHandle(new WinForms.CreateParams { Caption = "IfboardClipSync", Parent = new IntPtr(-3) });
        }

        protected override void WndProc(ref WinForms.Message m)
        {
            if (m.Msg == WM_CLIPBOARDUPDATE) _onUpdate();
            base.WndProc(ref m);
        }
    }

    private void StartClipboardListener()
    {
        _debounce = new DispatcherTimer(DispatcherPriority.Background, _ui) { Interval = TimeSpan.FromMilliseconds(200) };
        _debounce.Tick += (_, _) =>
        {
            _debounce!.Stop();
            ReadLocalClipboard();
        };
        _clipWindow = new ClipWindow(() =>
        {
            if (_disposed) return;
            _debounce?.Stop();
            _debounce?.Start();
        });
        if (!AddClipboardFormatListener(_clipWindow.Handle))
            throw new InvalidOperationException("无法注册剪贴板监听");
    }

    private void ReadLocalClipboard()
    {
        byte[]? data = null;
        var mime = "";
        Drawing.Bitmap? bitmap = null;
        for (var attempt = 0; attempt < 4 && data == null && bitmap == null; attempt++)
        {
            try
            {
                var dobj = WinForms.Clipboard.GetDataObject();
                if (dobj == null) return;
                if (dobj.GetDataPresent(WinForms.DataFormats.UnicodeText))
                {
                    if (dobj.GetData(WinForms.DataFormats.UnicodeText) is string t && t.Length > 0)
                    {
                        data = Encoding.UTF8.GetBytes(t);
                        mime = "text/plain";
                    }
                    else return;
                }
                else if (dobj.GetDataPresent("PNG") && dobj.GetData("PNG") is MemoryStream png)
                {
                    data = png.ToArray();
                    mime = "image/png";
                }
                else if (WinForms.Clipboard.ContainsImage())
                {
                    // 只在界面线程上取出位图，PNG 编码放到后台，大截图也不会卡住控制中心
                    using var img = WinForms.Clipboard.GetImage();
                    if (img == null) return;
                    bitmap = new Drawing.Bitmap(img);
                    mime = "image/png";
                }
                else return; // 文件列表等其他格式暂不同步
            }
            catch (ExternalException)
            {
                Thread.Sleep(50); // 剪贴板被其他程序占用，稍后重试
            }
            catch
            {
                return;
            }
        }
        var suppress = _suppressHash;
        _ = Task.Run(() =>
        {
            var bytes = data;
            if (bitmap != null)
            {
                using (bitmap)
                using (var ms = new MemoryStream())
                {
                    bitmap.Save(ms, Drawing.Imaging.ImageFormat.Png);
                    bytes = ms.ToArray();
                }
            }
            if (bytes == null || bytes.Length == 0 || bytes.Length > MaxLanItem) return Task.CompletedTask;

            var hash = HashOf(mime, bytes);
            if (hash == suppress) return Task.CompletedTask; // 刚从手机写入的内容，不再发回
            var latest = _latest;
            if (latest != null && latest.From == _deviceId && HashOf(latest.Mime, latest.Data) == hash) return Task.CompletedTask;

            var item = new ClipItem(NewId(), mime, bytes, NowMs(), _deviceId);
            _latest = item;
            return PublishLocalAsync(item);
        });
    }

    /// <summary>手机写过「不在局域网」标记，且之后没再通过局域网连上：此时复制内容才经网盘中转</summary>
    private bool PhoneAway => _phoneAwayMarkUtc.UtcDateTime > _lastLanContactUtc;

    private async Task PublishLocalAsync(ClipItem item)
    {
        var sent = false;
        foreach (var s in _sessions.Keys)
        {
            // 必须收到手机回执才算送达：手机切到流量后旧连接可能已死但写入不报错
            var ack = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            s.PendingAcks[item.Id] = ack;
            try
            {
                await SendClipAsync(s, item, _cts.Token);
                await ack.Task.WaitAsync(TimeSpan.FromMilliseconds(2500), _cts.Token);
                sent = true;
                Log($"sent via lan {item.Mime} {item.Data.Length}B");
            }
            catch (Exception e)
            {
                Log("lan send failed (" + (e is TimeoutException ? "no ack" : e.GetType().Name) + "), drop session");
                try { s.Client.Dispose(); } catch { }
                // 手机刚离开局域网：按「不在局域网」处理，改走网盘
                _phoneAwayMarkUtc = DateTimeOffset.UtcNow;
            }
            finally
            {
                s.PendingAcks.TryRemove(item.Id, out _);
            }
        }
        if (sent)
        {
            SetStatus($"已同步到{_sessions.Keys.FirstOrDefault()?.PeerName ?? "手机"}（局域网）");
            return;
        }
        if (PhoneAway)
            ScheduleDavPut(item);
        else
            SetStatus("已复制；手机弹出键盘时会通过局域网取回");
    }

    private void ApplyRemoteClip(ClipMessage m, string peer)
    {
        var id = m.Str("id");
        var mime = m.Str("mime") ?? "text/plain";
        var body = m.Body;
        if (string.IsNullOrEmpty(id) || body.Length == 0 || body.Length > MaxLanItem) return;
        if (!_seenIds.TryAdd(id, NowMs())) return;
        TrimSeen();
        _lastRemoteChangeUtc = DateTime.UtcNow;

        _ui.BeginInvoke(() =>
        {
            for (var attempt = 0; attempt < 4; attempt++)
            {
                try
                {
                    _suppressHash = HashOf(mime, body);
                    if (mime.StartsWith("image/", StringComparison.Ordinal))
                    {
                        using var src = Drawing.Image.FromStream(new MemoryStream(body));
                        var bmp = new Drawing.Bitmap(src);
                        var dobj = new WinForms.DataObject();
                        dobj.SetData(WinForms.DataFormats.Bitmap, true, bmp);
                        if (mime == "image/png") dobj.SetData("PNG", false, new MemoryStream(body));
                        WinForms.Clipboard.SetDataObject(dobj, true);
                    }
                    else
                    {
                        WinForms.Clipboard.SetText(Encoding.UTF8.GetString(body), WinForms.TextDataFormat.UnicodeText);
                    }
                    _latest = new ClipItem(id, mime, body, m.Long("ts"), m.Str("from") ?? "");
                    Log($"applied {mime} {body.Length}B from {peer}");
                    SetStatus($"已收到{peer}的{(mime.StartsWith("image/") ? "图片" : "文字")}");
                    return;
                }
                catch (ExternalException)
                {
                    Thread.Sleep(50);
                }
                catch
                {
                    return;
                }
            }
        });
    }

    // ---------------------------------------------------------------- LAN

    private void StartNetwork()
    {
        _tcp = new TcpListener(IPAddress.Any, TcpPort);
        _tcp.Start();
        _ = Task.Run(AcceptLoopAsync);

        try
        {
            _udp = new UdpClient(AddressFamily.InterNetwork);
            _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udp.Client.Bind(new IPEndPoint(IPAddress.Any, UdpPort));
            foreach (var ip in LocalIPv4())
            {
                try { _udp.JoinMulticastGroup(MulticastGroup, ip); } catch { }
            }
            _ = Task.Run(UdpLoopAsync);
        }
        catch
        {
            // 发现服务失败不影响直连与网盘地址发布
        }

        if (_davReady)
        {
            _http = WebDavService.CreateClipClient(_dav);
            NetworkChange.NetworkAddressChanged += OnNetworkChanged;
            _peerTimer = new Timer(_ => _ = PublishPeerAsync(), null, TimeSpan.FromSeconds(2), TimeSpan.FromHours(6));
            _pollTimer = new Timer(_ => _ = PollDavAsync(), null, TimeSpan.FromSeconds(8), Timeout.InfiniteTimeSpan);
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _tcp!.AcceptTcpClientAsync(_cts.Token); }
            catch { return; }
            if (_sessions.Count >= MaxSessions) { client.Dispose(); continue; }
            _ = Task.Run(() => HandleClientAsync(client));
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        client.NoDelay = true;
        var stream = client.GetStream();
        var ss = new Session
        {
            Client = client,
            Stream = stream,
            SNonce = Convert.ToBase64String(ClipSyncCrypto.RandomBytes(16))
        };
        var ct = _cts.Token;
        try
        {
            await SendAsync(ss, ClipMessage.Of("hello",
                [new("s", ss.SNonce), new("from", _deviceId), new("name", _deviceName)]), ct);

            // 握手须在 5 秒内完成，且必须回显本次会话随机数（防重放），解密失败直接断开
            using var hs = CancellationTokenSource.CreateLinkedTokenSource(ct);
            hs.CancelAfter(TimeSpan.FromSeconds(5));
            var first = await ReadMsgAsync(stream, hs.Token);
            if (first == null || first.Op != "hello" || first.Str("s") != ss.SNonce ||
                string.IsNullOrEmpty(first.Str("c")))
                return;
            ss.CNonce = first.Str("c")!;
            ss.PeerName = first.Str("name") ?? "手机";
            _sessions[ss] = 0;
            _lastLanContactUtc = DateTime.UtcNow;
            Log($"lan session from {(client.Client.RemoteEndPoint as IPEndPoint)?.Address}");
            UpdateIdleStatus();

            var latest = _latest;
            if (latest != null && latest.Id != first.Str("last") && latest.From != first.Str("from"))
                await SendClipAsync(ss, latest, ct);

            while (!ct.IsCancellationRequested)
            {
                using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
                // 手机每 25 秒发一次心跳：40 秒没有消息就认定连接已死（例如手机刚切到流量）
                idle.CancelAfter(TimeSpan.FromSeconds(40));
                var m = await ReadMsgAsync(stream, idle.Token);
                if (m == null) break;
                if (m.Str("s") != ss.SNonce || m.Str("c") != ss.CNonce) break;
                _lastLanContactUtc = DateTime.UtcNow;
                switch (m.Op)
                {
                    case "clip":
                        if (m.Str("id") is { } cid)
                            await SendAsync(ss, SessionMsg(ss, "ack", [new("id", cid)]), ct);
                        ApplyRemoteClip(m, ss.PeerName);
                        break;
                    case "ack":
                        if (m.Str("id") is { } aid && ss.PendingAcks.TryGetValue(aid, out var tcs)) tcs.TrySetResult();
                        break;
                    case "ping":
                        await SendAsync(ss, SessionMsg(ss, "pong"), ct);
                        break;
                    case "bye":
                        return;
                }
            }
        }
        catch
        {
            // 连接断开 / 超时
        }
        finally
        {
            _sessions.TryRemove(ss, out _);
            try { client.Dispose(); } catch { }
            UpdateIdleStatus();
        }
    }

    private async Task UdpLoopAsync()
    {
        var magicQ = "IFBD1"u8.ToArray();
        while (!_cts.IsCancellationRequested && _udp != null)
        {
            UdpReceiveResult r;
            try { r = await _udp.ReceiveAsync(_cts.Token); }
            catch { return; }
            var b = r.Buffer;
            var reply = BuildDiscoveryReply(r.Buffer, magicQ);
            if (reply == null) continue; // 非同一账号的设备：不回应
            try { await _udp.SendAsync(reply, r.RemoteEndPoint, _cts.Token); } catch { }
        }
    }

    /// <summary>请求：IFBD1 + nonce(16) + HMAC(disc,"q"+nonce)[16]；回应：IFBR1 + HMAC(disc,"r"+nonce)[16] + 端口(2)</summary>
    private byte[]? BuildDiscoveryReply(byte[] b, byte[] magicQ)
    {
        if (b.Length != 37 || !b.AsSpan(0, 5).SequenceEqual(magicQ)) return null;
        var nonce = b.AsSpan(5, 16).ToArray();
        var expect = ClipSyncKeys.Hmac(_keys.Disc, Concat("q"u8.ToArray(), nonce));
        if (!CryptographicOperations.FixedTimeEquals(expect.AsSpan(0, 16), b.AsSpan(21, 16))) return null;
        var proof = ClipSyncKeys.Hmac(_keys.Disc, Concat("r"u8.ToArray(), nonce));
        var reply = new byte[5 + 16 + 2];
        "IFBR1"u8.CopyTo(reply);
        Array.Copy(proof, 0, reply, 5, 16);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(21), TcpPort);
        return reply;
    }

    private ClipMessage SessionMsg(Session s, string op, IEnumerable<KeyValuePair<string, object?>>? extra = null, byte[]? body = null)
    {
        var fields = new List<KeyValuePair<string, object?>> { new("s", s.SNonce), new("c", s.CNonce) };
        if (extra != null) fields.AddRange(extra);
        return ClipMessage.Of(op, fields, body);
    }

    private Task SendClipAsync(Session s, ClipItem item, CancellationToken ct) =>
        SendAsync(s, SessionMsg(s, "clip",
            [new("id", item.Id), new("mime", item.Mime), new("ts", item.Ts), new("from", item.From), new("name", _deviceName)],
            item.Data), ct);

    private async Task SendAsync(Session s, ClipMessage m, CancellationToken ct)
    {
        var sealedData = ClipSyncCrypto.Seal(_keys.Enc, m.Encode());
        var len = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(len, sealedData.Length);
        await s.WriteLock.WaitAsync(ct);
        try
        {
            await s.Stream.WriteAsync(len, ct);
            await s.Stream.WriteAsync(sealedData, ct);
            await s.Stream.FlushAsync(ct);
        }
        finally
        {
            s.WriteLock.Release();
        }
    }

    private async Task<ClipMessage?> ReadMsgAsync(Stream s, CancellationToken ct)
    {
        var len = new byte[4];
        if (!await ReadExactAsync(s, len, ct)) return null;
        var n = BinaryPrimitives.ReadInt32BigEndian(len);
        if (n < 28 || n > MaxFrame) return null;
        var buf = new byte[n];
        if (!await ReadExactAsync(s, buf, ct)) return null;
        var plain = ClipSyncCrypto.Open(_keys.Enc, buf);
        return plain == null ? null : ClipMessage.Decode(plain);
    }

    private static async Task<bool> ReadExactAsync(Stream s, byte[] buf, CancellationToken ct)
    {
        var off = 0;
        while (off < buf.Length)
        {
            var r = await s.ReadAsync(buf.AsMemory(off), ct);
            if (r <= 0) return false;
            off += r;
        }
        return true;
    }

    // ---------------------------------------------------------------- WebDAV

    private string PeerFileName => $"peer-{_deviceId}.dat";
    private string ClipFileName => $"clip-{_deviceId}.dat";

    private void OnNetworkChanged(object? sender, EventArgs e) =>
        _peerTimer?.Change(TimeSpan.FromSeconds(5), TimeSpan.FromHours(6));

    /// <summary>把本机局域网地址加密写进网盘，供在另一层路由器后面、收不到广播的手机直连</summary>
    private async Task PublishPeerAsync()
    {
        if (_http == null || _cts.IsCancellationRequested) return;
        try
        {
            var ips = string.Join(",", LocalIPv4().Select(i => i.ToString()));
            if (ips.Length == 0) return;
            var msg = ClipMessage.Of("peer",
                [new("ips", ips), new("port", TcpPort), new("from", _deviceId), new("name", _deviceName), new("ts", NowMs())]);
            await _davLock.WaitAsync(_cts.Token);
            string written;
            try { written = await WebDavService.ClipPutWithLockFallbackAsync(_http, _dav, PeerFileName, ClipSyncCrypto.Seal(_keys.Enc, msg.Encode()), _cts.Token); }
            finally { _davLock.Release(); }
            Log($"peer published ({ips.Split(',').Length} addr) -> {written}");
        }
        catch (Exception e)
        {
            Log("peer publish failed " + Err(e));
            // 开机时网络可能还没就绪：1 分钟后重试，而不是等 6 小时
            if (!_cts.IsCancellationRequested)
                try { _peerTimer?.Change(TimeSpan.FromMinutes(1), TimeSpan.FromHours(6)); } catch { }
        }
    }

    private void ScheduleDavPut(ClipItem item)
    {
        if (_http == null || item.Data.Length > MaxDavItem) return;
        _davPutCts?.Cancel();
        var cts = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        _davPutCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(1500, cts.Token); // 连续复制只上传最后一次
                var msg = ClipMessage.Of("clip",
                    [new("id", item.Id), new("mime", item.Mime), new("ts", item.Ts), new("from", item.From), new("name", _deviceName)],
                    item.Data);
                await _davLock.WaitAsync(cts.Token);
                try { await WebDavService.ClipPutWithLockFallbackAsync(_http, _dav, ClipFileName, ClipSyncCrypto.Seal(_keys.Enc, msg.Encode()), cts.Token); }
                finally { _davLock.Release(); }
                SetStatus("已通过网盘同步（手机不在局域网）");
                Log($"dav put {item.Mime} {item.Data.Length}B");
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                Log("dav put failed " + Err(e));
            }
            catch
            {
                // 取消或网络失败
            }
        });
    }

    /// <summary>没有手机在线时轮询网盘：最近有变化 20 秒一次，否则 60 秒一次</summary>
    private async Task PollDavAsync()
    {
        if (_http == null || _cts.IsCancellationRequested) return;
        try
        {
            // 即使有局域网连接也照常检查：手机切网后旧连接可能看似还在，内容已改走网盘
            {
                var list = await WebDavService.ClipListAsync(_http, _dav, _cts.Token);
                var wasAway = PhoneAway;
                foreach (var (name, modified, size) in list)
                {
                    if (name.StartsWith("away-", StringComparison.Ordinal))
                    {
                        if (modified > _phoneAwayMarkUtc) _phoneAwayMarkUtc = modified;
                        continue;
                    }
                    if (!name.StartsWith("clip-", StringComparison.Ordinal) ||
                        name.StartsWith($"clip-{_deviceId}", StringComparison.Ordinal)) continue;
                    if (size > MaxDavItem + 1024) continue;
                    if (_davSeen.TryGetValue(name, out var seen) && modified <= seen) continue;
                    var first = !_davSeen.ContainsKey(name);
                    _davSeen[name] = modified;
                    var bytes = await WebDavService.ClipGetAsync(_http, _dav, name, _cts.Token);
                    var plain = bytes == null ? null : ClipSyncCrypto.Open(_keys.Enc, bytes);
                    var m = plain == null ? null : ClipMessage.Decode(plain);
                    if (m == null || m.Op != "clip") continue;
                    // 启动时只接收 10 分钟内的内容，避免开机把很久以前的复制写进剪贴板
                    if (first && NowMs() - m.Long("ts") > (long)RemoteFreshWindow.TotalMilliseconds)
                    {
                        Log($"dav item {name} too old, skip");
                        continue;
                    }
                    ApplyRemoteClip(m, (m.Str("name") ?? "手机") + "（网盘）");
                }
                // 手机刚离开局域网：把电脑当前最新复制的内容补传一份到网盘，手机在外面也能取到
                if (!wasAway && PhoneAway && _latest is { } latest && latest.From == _deviceId)
                    ScheduleDavPut(latest);
            }
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            Log("dav poll failed " + Err(e));
        }
        finally
        {
            if (!_cts.IsCancellationRequested)
            {
                var recent = DateTime.UtcNow - _lastRemoteChangeUtc < RemoteFreshWindow || PhoneAway;
                try { _pollTimer?.Change(TimeSpan.FromSeconds(recent ? 20 : 60), Timeout.InfiniteTimeSpan); } catch { }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static IEnumerable<IPAddress> LocalIPv4()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var b = ua.Address.GetAddressBytes();
                if (b[0] == 169 && b[1] == 254) continue; // 链路本地地址
                yield return ua.Address;
            }
        }
    }

    private void TrimSeen()
    {
        if (_seenIds.Count < 512) return;
        foreach (var kv in _seenIds.OrderBy(k => k.Value).Take(256)) _seenIds.TryRemove(kv.Key, out _);
    }

    private static string HashOf(string mime, byte[] data) =>
        Convert.ToHexString(SHA256.HashData(Concat(Encoding.UTF8.GetBytes(mime + "\n"), data)));

    private static byte[] Concat(byte[] a, byte[] b)
    {
        var r = new byte[a.Length + b.Length];
        a.CopyTo(r, 0);
        b.CopyTo(r, a.Length);
        return r;
    }

    private static string NewId() => Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
