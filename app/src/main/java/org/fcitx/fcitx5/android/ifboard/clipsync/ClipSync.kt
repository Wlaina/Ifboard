/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard clipboard sync, phone side (client). The PC (IfboardWin) is the server.
 */
package org.fcitx.fcitx5.android.ifboard.clipsync

import android.content.ClipData
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ifboard.IfboardSync
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

/**
 * 剪贴板同步（手机端 = 客户端，电脑端 IfboardWin = 服务端）：
 * · 所有连接都由手机发起（手机常在另一层路由器后面，电脑连不进来）。
 * · 找电脑：上次成功的地址 → 局域网广播 / 组播（带 HMAC，只有同账号设备回应）→ 网盘里电脑发布的地址。
 * · 键盘弹出时连上、取回电脑最新复制的内容；键盘开着时保持连接（电脑一复制立刻收到）；收起 30 秒后断开，不常驻后台。
 * · 复制后立即发给电脑；连不上时加密写入 WebDAV，电脑轮询取回。
 * · 内容全部端到端加密（见 [ClipSyncCrypto]），网盘与局域网里只有密文。
 */
object ClipSync {
    const val TCP_PORT = 47321
    const val UDP_PORT = 47322
    private const val MULTICAST = "239.255.73.21"
    private const val MAX_LAN_ITEM = 16 * 1024 * 1024
    /** 经网盘中转的单条上限；超过时图片先压缩成 JPEG */
    private const val MAX_DAV_ITEM = 20 * 1024 * 1024
    private const val MAX_FRAME = 24 * 1024 * 1024
    private const val IDLE_CLOSE_MS = 30_000L
    private const val PING_MS = 25_000L
    /** 超过这么久没收到电脑任何消息（含心跳回应）就认定连接已死 */
    private const val STALE_MS = PING_MS + 12_000L
    private const val DAV_PULL_INTERVAL_MS = 15_000L
    private const val PEER_REFRESH_MS = 60_000L
    /** 经网盘补收时只接收这段时间内的内容 */
    private const val FRESH_MS = 30 * 60_000L
    private const val HISTORY_SIZE = 5

    private class HistItem(val id: String, val mime: String, val ts: Long, val data: ByteArray, val file: String? = null)

    /** 本机最近复制的几条，经网盘中转时一并上传，电脑能补收离线期间的全部内容 */
    private val history = ArrayDeque<HistItem>()

    private fun addHistory(item: HistItem) = synchronized(history) {
        history.removeAll { it.id == item.id }
        history.addLast(item)
        while (history.size > HISTORY_SIZE) history.removeFirst()
    }
    /** 局域网找不到电脑后，同一网络下这段时间内不再重复尝试（省电，弹键盘不再白等约 1.5 秒） */
    private const val LAN_RETRY_MS = 45_000L
    /** 「我不在局域网」标记最多 10 分钟写一次网盘 */
    private const val AWAY_MARK_MS = 10 * 60_000L

    private const val PREF_ENABLED = "ifb_clip_sync"
    private const val PREF_ID = "ifb_clip_sync_id"
    private const val PREF_PC_ADDRS = "ifb_clip_sync_pc_addrs"
    private const val PREF_LAST_PC = "ifb_clip_sync_last_pc"
    private const val PREF_KEYS = "ifb_clip_sync_keys"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val davMutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var status: String = "未开启"
        private set

    /** 菜单等界面可订阅状态变化（主线程回调） */
    var onStatusChanged: ((String) -> Unit)? = null

    private class Session(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val s: String,
        val c: String,
        val peerName: String
    ) {
        @Volatile
        var alive = true

        /** 最近一次收到电脑消息的时间：电脑每次心跳都会回应，太久没消息说明是「半开」的死连接 */
        @Volatile
        var lastRecvMs = System.currentTimeMillis()

        /** 主动发送文件：发送中的等待状态 / 接收中的文件 */
        val xfers = ConcurrentHashMap<String, XferWait>()
        val incoming = ConcurrentHashMap<String, Incoming>()
    }

    @Volatile
    private var session: Session? = null
    private var closeJob: Job? = null
    private var davPutJob: Job? = null

    @Volatile
    private var keyboardVisible = false

    @Volatile
    private var suppressHash: String? = null
    private val seenIds = LinkedHashMap<String, Long>()
    private var cachedKeys: Pair<String, ClipSyncKeys>? = null
    private var lastDavPull = 0L
    private var lastPeerFetch = 0L
    private val davEtags = HashMap<String, String>()
    private var lastLanFailMs = 0L
    private var lastLanFailNet: String? = null
    private var lastAwayMark = 0L
    /** 发出的内容等待对方回执：网络切换后 TCP 连接可能已死但写入不报错，没有回执就改走网盘 */
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private const val ACK_TIMEOUT_MS = 2500L
    @Volatile
    private var netCallbackRegistered = false

    private val app: Context get() = FcitxApplication.getInstance()
    private val sp get() = PreferenceManager.getDefaultSharedPreferences(app)

    // ------------------------------------------------------------------ public API

    fun isEnabled(): Boolean = sp.getBoolean(PREF_ENABLED, false)

    fun setEnabled(on: Boolean) {
        sp.edit().putBoolean(PREF_ENABLED, on).apply()
        if (on) {
            setStatus("正在连接…")
            lastLanFailMs = 0L
            scope.launch {
                if (ensureSession() == null) {
                    pullDav(force = true)
                    if (session == null && creds() != null) setStatus("未找到电脑，暂用网盘同步")
                }
                if (!keyboardVisible) scheduleIdleClose()
                val result = status
                main.post { android.widget.Toast.makeText(app, "剪贴板同步：$result", android.widget.Toast.LENGTH_SHORT).show() }
            }
        } else {
            closeSession()
            setStatus("未开启")
        }
    }

    /** 用于界面核对两端是否同一把钥匙（不泄露密钥）。会做一次密钥推导，勿在主线程调用 */
    fun fingerprint(): String? = runCatching { keys()?.fingerprint }.getOrNull()

    fun onKeyboardShown() {
        keyboardVisible = true
        closeJob?.cancel()
        if (!isEnabled()) return
        scope.launch {
            if (ensureSession() == null) pullDav(force = false)
        }
    }

    fun onKeyboardHidden() {
        keyboardVisible = false
        if (session != null) scheduleIdleClose()
    }

    /** 本机复制了文字（由剪贴板监听调用） */
    fun onLocalText(text: String) {
        if (!isEnabled() || text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LAN_ITEM) return
        publish("text/plain", bytes)
    }

    /** 本机复制了图片（由剪贴板监听调用） */
    fun onLocalImage(uri: Uri, mime: String) {
        if (!isEnabled() || uri == lastAppliedFileUri) return // 刚收到的图片文件，不再发回
        scope.launch {
            val bytes = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_LAN_ITEM) return@use null
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
            }.getOrNull() ?: return@launch
            publish(mime, bytes)
        }
    }

    /** 刚收到并放进剪贴板的文件，避免监听到后又发回去 */
    @Volatile
    private var lastAppliedFileUri: Uri? = null

    /** 本机复制了文件（文件管理器等复制的 content:// 链接；由剪贴板监听调用） */
    fun onLocalFile(clip: ClipData) {
        if (!isEnabled()) return
        val own = "${app.packageName}.clipimage"
        val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
            ?.takeIf { it.scheme == "content" && it.authority != own && it != lastAppliedFileUri } ?: return
        scope.launch {
            runCatching {
                val cr = app.contentResolver
                var name: String? = null
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val fileName = safeFileName(name ?: return@launch)
                if (size > MAX_LAN_ITEM) {
                    setStatus("文件超过 16 MB，未同步")
                    return@launch
                }
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val bytes = cr.openInputStream(uri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_LAN_ITEM) return@use null
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: return@launch
                if (bytes.isEmpty()) return@launch
                publish(mime, bytes, fileName)
            }.onFailure { Timber.w(it, "clip sync: read file failed") }
        }
    }

    /** 只保留文件名本身，去掉路径与非法字符 */
    private fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim().trimStart('.')
        return base.take(120).ifEmpty { "file" }
    }

    // ------------------------------------------------------------------ publish / receive

    private fun publish(mime: String, data: ByteArray, file: String? = null) {
        val hash = if (file != null) hashOf("file", data) else hashOf(mime, data)
        if (hash == suppressHash) return // 刚从电脑收到并写入的内容，不再发回
        val id = hex(ClipSyncCrypto.randomBytes(8))
        val ts = System.currentTimeMillis()
        rememberSeen(id)
        addHistory(HistItem(id, mime, ts, data, file))
        scope.launch {
            val s = ensureSession()
            if (s != null && sendWithAck(s, clipMessage(s, id, mime, ts, data, file), id)) {
                Timber.i("clip sync: sent via lan %s %dB", mime, data.size)
                setStatus("已同步到 ${s.peerName}（局域网）")
                if (!keyboardVisible) scheduleIdleClose()
            } else {
                Timber.i("clip sync: lan unavailable, queue dav put %s %dB", mime, data.size)
                scheduleDavPut(id, mime, ts, data, file)
            }
        }
    }

    /** 发送并等待对方回执；超时视为连接已死（例如刚从 Wi-Fi 切到流量），断开以便改走网盘 */
    private suspend fun sendWithAck(s: Session, m: ClipMessage, id: String): Boolean {
        val ack = CompletableDeferred<Unit>()
        pendingAcks[id] = ack
        try {
            if (!send(s, m)) return false
            if (withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } != null) return true
            Timber.i("clip sync: no ack in %dms, drop lan session", ACK_TIMEOUT_MS)
            dropSession(s)
            return false
        } finally {
            pendingAcks.remove(id)
        }
    }

    private fun applyRemote(m: ClipMessage, via: String) {
        val id = m.str("id") ?: return
        val mime = m.str("mime") ?: "text/plain"
        val body = m.body
        if (body.isEmpty() || body.size > MAX_LAN_ITEM) return
        if (!rememberSeen(id)) return
        val from = m.str("from")
        if (from != null && from != deviceId()) sp.edit().putString(PREF_LAST_PC, id).apply()
        val name = m.str("name") ?: "电脑"
        val file = m.str("file")?.let { safeFileName(it) }
        val hash = if (file != null) hashOf("file", body) else hashOf(mime, body)
        val clip: ClipData = if (file != null) {
            val uri = runCatching { saveFile(id, file, mime, body) }.getOrElse {
                Timber.w(it, "clip sync: save file failed"); return
            }
            lastAppliedFileUri = uri
            ClipData.newUri(app.contentResolver, file, uri)
        } else if (mime.startsWith("image/")) {
            val uri = runCatching { saveImage(id, mime, body) }.getOrElse {
                Timber.w(it, "clip sync: save image failed"); return
            }
            ClipData.newUri(app.contentResolver, "image", uri)
        } else {
            ClipData.newPlainText("Ifboard", String(body, Charsets.UTF_8))
        }
        main.post {
            runCatching {
                suppressHash = hash
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                Timber.i("clip sync: applied %s %dB via %s", mime, body.size, via)
                val kind = when {
                    file != null -> "文件 $file（已存到 下载/Ifboard）"
                    mime.startsWith("image/") -> "图片"
                    else -> "文字"
                }
                setStatus("已收到 $name 的$kind（$via）")
            }.onFailure { Timber.w(it, "clip sync: setPrimaryClip failed") }
        }
    }

    /**
     * 收到的文件存到「下载/Ifboard」（Android 10 起无需存储权限），剪贴板里放它的链接，
     * 可直接在聊天框或文件管理器里粘贴；Android 9 及以下存到缓存目录。
     */
    private fun saveFile(id: String, file: String, mime: String, body: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = app.contentResolver
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Ifboard")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                cr.openOutputStream(uri)?.use { it.write(body) } ?: error("open output failed")
                cr.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(app.cacheDir, "clip_images/files_$id").apply { mkdirs() }
        val f = File(dir, file)
        f.writeBytes(body)
        return FileProvider.getUriForFile(app, "${app.packageName}.clipimage", f)
    }

    private fun saveImage(id: String, mime: String, body: ByteArray): Uri {
        val dir = File(app.cacheDir, "clip_images").apply { mkdirs() }
        val expire = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.name.startsWith("sync_") && it.lastModified() < expire) it.delete() }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
        val file = File(dir, "sync_$id.$ext")
        file.writeBytes(body)
        return FileProvider.getUriForFile(app, "${app.packageName}.clipimage", file)
    }

    // ------------------------------------------------------------------ LAN session

    private suspend fun ensureSession(): Session? = connectMutex.withLock {
        session?.let { s ->
            if (s.alive && System.currentTimeMillis() - s.lastRecvMs < STALE_MS) return s
            // 连接在电脑那边早已断开（例如走 Tailscale / 切网后），手机这边写入不报错：丢掉重连
            Timber.i("clip sync: stale lan session, reconnect")
            dropSession(s)
        }
        val keys = keys() ?: run {
            setStatus("需要先填写 WebDAV 账号（用于加密配对）")
            return null
        }
        registerNetworkCallback()
        val net = currentNetKey()
        // 刚在同一网络下找过且失败：短时间内不再重复尝试局域网
        if (net == lastLanFailNet && System.currentTimeMillis() - lastLanFailMs < LAN_RETRY_MS) return null
        val cached = sp.getString(PREF_PC_ADDRS, null)?.split(',')?.mapNotNull { parseAddr(it) }.orEmpty()
        val s = tryConnectAll(cached, keys)
            ?: tryConnectAll(discover(keys), keys)
            // 广播到不了（例如手机在另一层路由器后面）：用电脑写在网盘里的地址直连
            ?: tryConnectAll(fetchPeerAddrs(keys), keys)
        if (s != null) {
            Timber.i("clip sync: lan connected")
            lastLanFailMs = 0L
            // 连上时键盘可能已经收起：同样按时断开，避免心跳把连接一直挂着
            if (!keyboardVisible) scheduleIdleClose()
            return s
        }
        lastLanFailMs = System.currentTimeMillis()
        lastLanFailNet = net
        Timber.i("clip sync: pc not reachable on this network")
        markAway(keys)
        null
    }

    /** 网络切换（Wi-Fi ⇄ 流量）时立刻丢弃旧连接、清除退避，下次操作重新寻找电脑 */
    private fun registerNetworkCallback() {
        if (netCallbackRegistered) return
        netCallbackRegistered = true
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (current != null && current != network) onNetworkChanged()
                    current = network
                }

                override fun onLost(network: android.net.Network) {
                    if (current == network) onNetworkChanged()
                }
            })
        }.onFailure { netCallbackRegistered = false }
    }

    private fun onNetworkChanged() {
        Timber.i("clip sync: network changed, reset lan session")
        lastLanFailMs = 0L
        lastAwayMark = 0L // 换了网络：若找不到电脑，立刻重新告诉电脑「我不在局域网」
        session?.let { dropSession(it) }
        if (keyboardVisible && isEnabled()) scope.launch {
            if (ensureSession() == null) pullDav(force = true)
        }
    }

    /** 当前网络的标识（换了 Wi-Fi / 切到移动数据时立即重新尝试局域网） */
    private fun currentNetKey(): String? = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(n)
        "${n}|${lp?.interfaceName}|${lp?.linkAddresses?.joinToString()}"
    }.getOrNull()

    /**
     * 告诉电脑「我现在不在局域网」：电脑只有看到这个标记，才把复制内容经网盘中转，
     * 手机在家时电脑每次复制都不必上传网盘。标记只含时间戳，同样加密。
     */
    private fun markAway(keys: ClipSyncKeys) {
        val c = creds() ?: return
        val now = System.currentTimeMillis()
        if (now - lastAwayMark < AWAY_MARK_MS) return
        lastAwayMark = now
        scope.launch {
            val sealed = ClipSyncCrypto.seal(keys.enc, ClipMessage.of("away", "ts" to now, "from" to deviceId()).encode())
            davMutex.withLock {
                runCatching {
                    val dav = IfboardSync.Dav(c.url, c.user, c.pass)
                    putWithFallback(dav, c, "away-${deviceId()}.dat", sealed)
                    Timber.i("clip sync: away mark written")
                }.onFailure {
                    lastAwayMark = 0L
                    Timber.w(it, "clip sync: away mark failed")
                }
            }
            // 电脑最多 20～60 秒后才看到标记并把最新内容传到网盘：键盘还开着就再取一次
            delay(45_000)
            if (keyboardVisible && session == null) pullDav(force = true)
        }
    }

    private fun tryConnectAll(addrs: List<InetSocketAddress>, keys: ClipSyncKeys): Session? {
        for (addr in addrs.distinctBy { it.hostString + ":" + it.port }) {
            val s = runCatching { connect(addr, keys) }.getOrNull() ?: continue
            sp.edit().putString(PREF_PC_ADDRS, "${addr.hostString}:${addr.port}").apply()
            return s
        }
        return null
    }

    private fun connect(addr: InetSocketAddress, keys: ClipSyncKeys): Session? {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(addr, 800)
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val hello = readMsg(input, keys) ?: return null.also { socket.close() }
            val s = hello.str("s")
            if (hello.op != "hello" || s.isNullOrEmpty()) return null.also { socket.close() }
            val c = Base64.getEncoder().encodeToString(ClipSyncCrypto.randomBytes(16))
            writeMsg(
                output, keys, ClipMessage.of(
                    "hello", "s" to s, "c" to c, "from" to deviceId(), "name" to Build.MODEL,
                    "last" to sp.getString(PREF_LAST_PC, ""),
                    // 声明支持主动收发文件（旧版不带，电脑就不会给它发文件）
                    "xfer" to 1
                )
            )
            socket.soTimeout = 0
            val session = Session(socket, input, output, s, c, hello.str("name") ?: "电脑")
            this.session = session
            setStatus("已连接 ${session.peerName}（局域网）")
            startReader(session, keys)
            startKeepAlive(session, keys)
            return session
        } catch (e: Exception) {
            runCatching { socket.close() }
            return null
        }
    }

    private fun startReader(s: Session, keys: ClipSyncKeys) = scope.launch {
        try {
            while (s.alive && isActive) {
                val m = readMsg(s.input, keys) ?: break
                if (m.str("s") != s.s || m.str("c") != s.c) break // 会话随机数不符：拒绝（防重放）
                s.lastRecvMs = System.currentTimeMillis()
                when (m.op) {
                    "clip" -> {
                        m.str("id")?.let { send(s, ClipMessage.of("ack", "s" to s.s, "c" to s.c, "id" to it)) }
                        applyRemote(m, "局域网")
                    }
                    "ack" -> m.str("id")?.let { pendingAcks[it]?.complete(Unit) }
                    else -> handleXfer(s, m)
                }
            }
        } catch (_: Exception) {
        } finally {
            dropSession(s)
        }
    }

    private fun startKeepAlive(s: Session, keys: ClipSyncKeys) = scope.launch {
        while (s.alive && isActive) {
            delay(PING_MS)
            if (!s.alive) break
            if (System.currentTimeMillis() - s.lastRecvMs > STALE_MS) {
                Timber.i("clip sync: no reply from pc, drop lan session")
                dropSession(s)
                break
            }
            if (!send(s, ClipMessage.of("ping", "s" to s.s, "c" to s.c))) break
        }
    }

    private fun send(s: Session, m: ClipMessage): Boolean {
        val keys = keys() ?: return false
        return runCatching {
            synchronized(s) { writeMsg(s.output, keys, m) }
            true
        }.getOrElse {
            dropSession(s)
            false
        }
    }

    private fun clipMessage(s: Session, id: String, mime: String, ts: Long, data: ByteArray, file: String? = null) = ClipMessage.of(
        "clip", "s" to s.s, "c" to s.c, "id" to id, "mime" to mime, "ts" to ts,
        "from" to deviceId(), "name" to Build.MODEL, "file" to file, body = data
    )

    private fun dropSession(s: Session) {
        val wasAlive = s.alive
        val wasCurrent = session === s && wasAlive
        s.alive = false
        runCatching { s.socket.close() }
        // 任何一条连接断开都要收尾它的传输（旧连接也可能还挂着接收中的文件），否则计数不归零、键盘收起后不会断开
        if (wasAlive) cleanupXfers(s)
        if (session === s) {
            session = null
            if (isEnabled()) setStatus("已断开，正在重连")
        }
        // 键盘还开着时连接意外断开（例如 Tailscale 掉线）：稍后自动重连，保持实时
        if (wasCurrent && keyboardVisible && isEnabled()) scope.launch {
            delay(1500)
            if (keyboardVisible && session == null) ensureSession()
        }
    }

    /** 菜单里显示的简短状态 */
    fun shortState(): String = when {
        !isEnabled() -> "未开启"
        session?.alive == true -> "已连电脑"
        creds() == null -> "缺 WebDAV 账号"
        lastLanFailMs > 0 -> "网盘中转"
        else -> "待机"
    }

    private fun closeSession() {
        closeJob?.cancel()
        session?.let { s ->
            scope.launch {
                runCatching { synchronized(s) { keys()?.let { writeMsg(s.output, it, ClipMessage.of("bye", "s" to s.s, "c" to s.c)) } } }
                dropSession(s)
                if (isEnabled()) setStatus("待机（弹出键盘时连接电脑）")
            }
        }
    }

    private fun scheduleIdleClose() {
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            // 正在收发文件或刚点了「接收文件」：再等一轮
            if (!keyboardVisible) {
                if (keepOpen()) scheduleIdleClose() else closeSession()
            }
        }
    }

    private fun writeMsg(out: DataOutputStream, keys: ClipSyncKeys, m: ClipMessage) {
        val sealed = ClipSyncCrypto.seal(keys.enc, m.encode())
        out.writeInt(sealed.size)
        out.write(sealed)
        out.flush()
    }

    private fun readMsg(input: DataInputStream, keys: ClipSyncKeys): ClipMessage? {
        val n = try {
            input.readInt()
        } catch (e: SocketTimeoutException) {
            return null
        }
        if (n < 28 || n > MAX_FRAME) return null
        val buf = ByteArray(n)
        input.readFully(buf)
        val plain = ClipSyncCrypto.open(keys.enc, buf) ?: return null
        return ClipMessage.decode(plain)
    }

    // ------------------------------------------------------------------ discovery

    /** 请求：IFBD1 + nonce(16) + HMAC(disc,"q"+nonce)[16]；只接受带正确 HMAC 的回应 */
    private fun discover(keys: ClipSyncKeys): List<InetSocketAddress> {
        val nonce = ClipSyncCrypto.randomBytes(16)
        val q = "IFBD1".toByteArray() + nonce + ClipSyncKeys.hmac(keys.disc, "q".toByteArray() + nonce).copyOf(16)
        val expect = ClipSyncKeys.hmac(keys.disc, "r".toByteArray() + nonce).copyOf(16)
        val found = mutableListOf<InetSocketAddress>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 350
                val targets = (broadcastAddresses() + InetAddress.getByName("255.255.255.255") +
                    InetAddress.getByName(MULTICAST)).distinct()
                repeat(2) {
                    targets.forEach { t -> runCatching { sock.send(DatagramPacket(q, q.size, t, UDP_PORT)) } }
                    val buf = ByteArray(64)
                    val deadline = System.currentTimeMillis() + 350
                    while (System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(p)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val d = p.data.copyOf(p.length)
                        if (d.size == 23 && String(d, 0, 5) == "IFBR1" &&
                            MessageDigest.isEqual(d.copyOfRange(5, 21), expect)
                        ) {
                            val port = ((d[21].toInt() and 0xff) shl 8) or (d[22].toInt() and 0xff)
                            found += InetSocketAddress(p.address, port)
                        }
                    }
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return found
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        lp.linkAddresses.mapNotNull { la ->
            val a = la.address as? Inet4Address ?: return@mapNotNull null
            val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
            val bc = ip or mask.inv()
            InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
        }
    }.getOrDefault(emptyList())

    private fun parseAddr(s: String): InetSocketAddress? = runCatching {
        val host = s.substringBeforeLast(':')
        val port = s.substringAfterLast(':').toInt()
        InetSocketAddress(host, port)
    }.getOrNull()

    // ------------------------------------------------------------------ WebDAV fallback

    private data class Creds(val url: String, val user: String, val pass: String, val dir: String)

    private fun creds(): Creds? {
        val url = sp.getString("ifb_webdav_url", IfboardSync.DEFAULT_WEBDAV_URL).orEmpty().trim()
            .ifEmpty { IfboardSync.DEFAULT_WEBDAV_URL }
        val user = sp.getString("ifb_webdav_user", "").orEmpty()
        val pass = sp.getString("ifb_webdav_password", "").orEmpty()
        val dir = sp.getString("ifb_webdav_dir", "Ifboard").orEmpty().trim('/').ifEmpty { "Ifboard" }
        if (user.isBlank() || pass.isBlank()) return null
        return Creds(url, user, pass, dir)
    }

    private fun clipDir(c: Creds) = "${c.dir}/_clipboard"

    /**
     * 写入同步文件；网盘残留锁（HTTP 423，例如上次上传被中断）导致覆盖失败时，改写备用文件名 -2 / -3。
     * 读取方按前缀把它们视为同一组，按内容里的时间戳取最新。
     */
    private fun putWithFallback(dav: IfboardSync.Dav, c: Creds, name: String, data: ByteArray) {
        val stem = name.removeSuffix(".dat")
        var last: Throwable? = null
        for (candidate in listOf(name, "$stem-2.dat", "$stem-3.dat")) {
            val path = "${clipDir(c)}/$candidate"
            val ok = runCatching { dav.put(path, data) }.recoverCatching {
                dav.mkcol(clipDir(c))
                dav.put(path, data)
            }
            if (ok.isSuccess) return
            last = ok.exceptionOrNull()
        }
        throw last ?: IllegalStateException("upload failed")
    }

    private fun fetchPeerAddrs(keys: ClipSyncKeys): List<InetSocketAddress> {
        val now = System.currentTimeMillis()
        if (now - lastPeerFetch < PEER_REFRESH_MS) return emptyList()
        lastPeerFetch = now
        val c = creds() ?: return emptyList()
        return runCatching {
            val dav = IfboardSync.Dav(c.url, c.user, c.pass)
            dav.list(clipDir(c)).filter { it.startsWith("peer-") && !it.endsWith("/") }.mapNotNull { name ->
                val bytes = dav.get("${clipDir(c)}/$name") ?: return@mapNotNull null
                val m = ClipSyncCrypto.open(keys.enc, bytes)?.let { ClipMessage.decode(it) } ?: return@mapNotNull null
                m.takeIf { it.op == "peer" }
            }.sortedByDescending { it.long("ts") }.flatMap { m ->
                // 同一台电脑可能因网盘锁留有多个地址文件：按时间从新到旧尝试
                val port = m.long("port").toInt().takeIf { it in 1..65535 } ?: TCP_PORT
                m.str("ips").orEmpty().split(',').filter { it.isNotBlank() }.map { InetSocketAddress(it.trim(), port) }
            }
        }.getOrElse {
            Timber.w(it, "clip sync: fetch peer failed")
            emptyList()
        }
    }

    /** 超过网盘上限的图片：逐步缩小尺寸、降低 JPEG 质量，直到不超过上限 */
    private fun shrinkImage(data: ByteArray): ByteArray? = runCatching {
        val src = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        try {
            for ((scale, quality) in listOf(1.0f to 90, 1.0f to 80, 0.75f to 80, 0.5f to 80, 0.35f to 75)) {
                val w = (src.width * scale).toInt().coerceAtLeast(1)
                val h = (src.height * scale).toInt().coerceAtLeast(1)
                val bmp = if (scale == 1.0f) src else android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                if (bmp !== src) bmp.recycle()
                if (out.size() <= MAX_DAV_ITEM) return out.toByteArray()
            }
            null
        } finally {
            src.recycle()
        }
    }.getOrNull()

    private fun scheduleDavPut(id: String, mime: String, ts: Long, data: ByteArray, file: String? = null) {
        val c = creds() ?: return
        var mimeOut = mime
        var dataOut = data
        if (dataOut.size > MAX_DAV_ITEM && file == null && mime.startsWith("image/")) {
            shrinkImage(data)?.let { dataOut = it; mimeOut = "image/jpeg" }
        }
        if (dataOut.size > MAX_DAV_ITEM) {
            setStatus("未找到电脑；内容超过 20 MB，未经网盘同步")
            return
        }
        davPutJob?.cancel()
        davPutJob = scope.launch {
            delay(1500) // 连续复制只上传最后一次
            val keys = keys() ?: return@launch
            // 网盘里放最近几条（从旧到新），离线期间连续复制的内容电脑都能补收
            val cutoff = System.currentTimeMillis() - FRESH_MS
            val recent = synchronized(history) {
                history.filter { it.id != id && it.ts >= cutoff && it.data.size <= MAX_DAV_ITEM }
            }.toMutableList()
            recent += HistItem(id, mimeOut, ts, dataOut, file)
            while (recent.size > 1 && recent.sumOf { it.data.size.toLong() } > MAX_DAV_ITEM) recent.removeAt(0)
            val m = ClipMessage.of(
                "batch", "ts" to System.currentTimeMillis(), "from" to deviceId(), "n" to recent.size,
                body = ClipMessage.encodeBatch(recent.map {
                    ClipMessage.of(
                        "clip", "id" to it.id, "mime" to it.mime, "ts" to it.ts, "from" to deviceId(), "name" to Build.MODEL,
                        "file" to it.file, body = it.data
                    )
                })
            )
            val sealed = ClipSyncCrypto.seal(keys.enc, m.encode())
            davMutex.withLock {
                runCatching {
                    val dav = IfboardSync.Dav(c.url, c.user, c.pass)
                    putWithFallback(dav, c, "clip-${deviceId()}.dat", sealed)
                    Timber.i("clip sync: dav put ok %s %dB", mimeOut, dataOut.size)
                    setStatus("已通过网盘同步（未找到电脑）")
                }.onFailure {
                    Timber.w(it, "clip sync: dav put failed")
                    setStatus("同步失败：${it.message ?: "网络不可用"}")
                }
            }
        }
    }

    /** 从网盘取回电脑最新复制的内容（条件请求，未变化时不下载） */
    private suspend fun pullDav(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDavPull < DAV_PULL_INTERVAL_MS) return
        lastDavPull = now
        val c = creds() ?: return
        val keys = keys() ?: return
        davMutex.withLock {
            runCatching {
                val dav = IfboardSync.Dav(c.url, c.user, c.pass)
                val mine = "clip-${deviceId()}"
                for (name in dav.list(clipDir(c))) {
                    if (!name.startsWith("clip-") || name.endsWith("/") || name.startsWith(mine)) continue
                    val known = davEtags[name]
                    val (bytes, etag) = dav.getIfChanged("${clipDir(c)}/$name", known)
                    if (etag != null) davEtags[name] = etag
                    bytes ?: continue
                    val m = ClipSyncCrypto.open(keys.enc, bytes)?.let { ClipMessage.decode(it) } ?: continue
                    // 兼容旧格式（单条 clip）与新格式（batch：最近几条，从旧到新）
                    val items = when (m.op) {
                        "batch" -> ClipMessage.decodeBatch(m.body)
                        "clip" -> listOf(m)
                        else -> emptyList()
                    }
                    for (it in items) {
                        if (it.op != "clip") continue
                        if (now - it.long("ts") > FRESH_MS) continue // 太久以前的复制不再写进剪贴板
                        applyRemote(it, "网盘")
                        delay(150) // 逐条写入，让剪贴板历史都记下
                    }
                }
            }.onFailure { Timber.w(it, "clip sync: pull failed") }
            Timber.i("clip sync: dav pull done")
        }
    }

    // ------------------------------------------------------------------ 主动发送文件（类似 LocalSend）
    //
    // 走同一条加密连接，分块传输、断点续传、整文件 SHA-256 校验；与 windows/IfboardWin/Services/FileTransfer.cs 一致：
    //   发送方 fbeg{id,name,size,sha,mime} → 接收方 fok{id,off}（off < 0 拒收，附 why）
    //   发送方 fdat{id,off}+正文（≤ 512 KB）→ 接收方 fack{id,off}；发送方最多领先 6 块
    //   发送方 fend{id} → 接收方 fdone{id,ok,file}

    private const val CHUNK = 512 * 1024
    private const val WINDOW_CHUNKS = 6
    private const val XFER_STEP_MS = 30_000L

    private class XferWait {
        val ready = CompletableDeferred<Pair<Long, String?>>()
        val done = CompletableDeferred<Boolean>()
        val acked = java.util.concurrent.atomic.AtomicLong(0)
        val progress = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    }

    private class Incoming(
        val id: String, val name: String, val mime: String, val size: Long, val sha: String,
        val part: File, val out: java.io.FileOutputStream, val md: MessageDigest, var got: Long, val peer: String
    ) {
        var lastUi = 0L
    }

    /** 正在进行的传输数：有传输时不因键盘收起而断开 */
    private val activeTransfers = java.util.concurrent.atomic.AtomicInteger(0)

    /** 点了「接收文件」后保持连接到这个时间 */
    @Volatile
    private var holdUntilMs = 0L

    private fun keepOpen() = activeTransfers.get() > 0 || System.currentTimeMillis() < holdUntilMs

    private fun xmsg(s: Session, op: String, vararg pairs: Pair<String, Any?>, body: ByteArray = ByteArray(0)) =
        ClipMessage.of(op, "s" to s.s, "c" to s.c, *pairs, body = body)

    /** 连接里收到的文件传输消息（在读取线程里执行） */
    private fun handleXfer(s: Session, m: ClipMessage) {
        val id = m.str("id") ?: return
        when (m.op) {
            "fok" -> s.xfers[id]?.ready?.complete(m.long("off") to m.str("why"))
            "fack" -> s.xfers[id]?.let {
                it.acked.set(m.long("off"))
                it.progress.trySend(Unit)
            }
            "fdone" -> s.xfers[id]?.done?.complete(
                (m.header["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            )
            "fbeg" -> beginIncoming(s, m, id)
            "fdat" -> {
                val inc = s.incoming[id]
                if (inc != null && m.long("off") == inc.got) {
                    inc.out.write(m.body)
                    inc.md.update(m.body)
                    inc.got += m.body.size
                    val now = System.currentTimeMillis()
                    if (now - inc.lastUi > 700) {
                        inc.lastUi = now
                        val pct = (inc.got * 100 / inc.size.coerceAtLeast(1)).toInt()
                        XferNotifier.progress(app, inc.id.hashCode(), "正在接收 ${inc.peer} 的文件", inc.name, pct)
                    }
                }
                send(s, xmsg(s, "fack", "id" to id, "off" to (s.incoming[id]?.got ?: -1L)))
            }
            "fend" -> finishIncoming(s, id)
        }
    }

    private fun beginIncoming(s: Session, m: ClipMessage, id: String) {
        val size = m.long("size")
        val sha = m.str("sha")?.lowercase().orEmpty()
        val name = safeFileName(m.str("name") ?: "file")
        val mime = m.str("mime") ?: "application/octet-stream"
        fun reject(why: String) {
            Timber.i("clip sync: file rejected (%s)", why)
            send(s, xmsg(s, "fok", "id" to id, "off" to -1L, "why" to why))
        }
        if (id.length < 16 || !id.all { it in '0'..'9' || it in 'a'..'f' } || sha.length != 64 || size < 0) {
            reject("参数错误")
            return
        }
        // 同一个文件重发（电脑超时后重试）：先收尾旧的那份，计数要一起减掉
        s.incoming.remove(id)?.let {
            closeIncoming(it)
            activeTransfers.decrementAndGet()
        }
        try {
            val dir = File(app.cacheDir, "xfer").apply { mkdirs() }
            // 7 天都没续传的半截文件：删掉
            val expire = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.name.endsWith(".part") && it.lastModified() < expire) it.delete() }
            val part = File(dir, "$id.part")
            var have = if (part.exists()) part.length() else 0L
            if (have > size) {
                part.delete(); have = 0L
            }
            // 先收到缓存，校验后再复制到「下载」，所以要两份空间
            if (dir.usableSpace < (size - have) + size + 64L * 1024 * 1024) {
                reject("手机存储空间不足")
                return
            }
            val md = MessageDigest.getInstance("SHA-256")
            if (have > 0) part.inputStream().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val inc = Incoming(id, name, mime, size, sha, part, java.io.FileOutputStream(part, true), md, have, s.peerName)
            s.incoming[id] = inc
            activeTransfers.incrementAndGet()
            closeJob?.cancel()
            Timber.i("clip sync: receiving file %dB (resume at %d)", size, have)
            send(s, xmsg(s, "fok", "id" to id, "off" to have))
            XferNotifier.progress(app, id.hashCode(), "正在接收 ${s.peerName} 的文件", name, (have * 100 / size.coerceAtLeast(1)).toInt())
        } catch (e: Exception) {
            Timber.w(e, "clip sync: begin receive failed")
            reject("手机无法保存：${e.message}")
        }
    }

    private fun finishIncoming(s: Session, id: String) {
        val inc = s.incoming.remove(id)
        if (inc == null) {
            send(s, xmsg(s, "fdone", "id" to id, "ok" to false))
            return
        }
        activeTransfers.decrementAndGet()
        runCatching { inc.out.close() }
        val actual = hex(inc.md.digest())
        if (inc.got != inc.size || actual != inc.sha) {
            inc.part.delete()
            send(s, xmsg(s, "fdone", "id" to id, "ok" to false))
            XferNotifier.failed(app, id.hashCode(), "文件接收失败", "${inc.name}：校验不通过，请重新发送")
        } else {
            runCatching { publishDownload(inc.part, inc.name, inc.mime) }
                .onSuccess { uri ->
                    inc.part.delete()
                    Timber.i("clip sync: received file %dB", inc.size)
                    send(s, xmsg(s, "fdone", "id" to id, "ok" to true, "file" to inc.name))
                    setStatus("已收到 ${inc.peer} 的文件 ${inc.name}（下载/Ifboard）")
                    XferNotifier.received(app, id.hashCode(), inc.peer, inc.name, uri, inc.mime)
                }
                .onFailure {
                    Timber.w(it, "clip sync: save received file failed")
                    send(s, xmsg(s, "fdone", "id" to id, "ok" to false))
                    XferNotifier.failed(app, id.hashCode(), "文件保存失败", "${inc.name}：${it.message}")
                }
        }
        if (!keyboardVisible) scheduleIdleClose()
    }

    private fun closeIncoming(inc: Incoming) {
        runCatching { inc.out.close() }
    }

    /** 连接断开：保留已收到的部分（下次续传），唤醒等待中的发送 */
    private fun cleanupXfers(s: Session) {
        s.incoming.values.forEach {
            closeIncoming(it)
            activeTransfers.decrementAndGet()
            XferNotifier.failed(app, it.id.hashCode(), "文件接收中断", "${it.name}：连接断开，电脑会在下次连上时接着发")
        }
        s.incoming.clear()
        s.xfers.values.forEach {
            it.ready.complete(-1L to "连接已断开")
            it.done.complete(false)
            it.progress.trySend(Unit)
        }
    }

    /** 收到的文件放进「下载/Ifboard」（Android 10 起用 MediaStore，无需存储权限） */
    private fun publishDownload(part: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = app.contentResolver
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Ifboard")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
            try {
                cr.openOutputStream(uri)?.use { out -> part.inputStream().use { it.copyTo(out, CHUNK) } }
                    ?: error("open output failed")
                cr.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Ifboard").apply { mkdirs() }
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, "$stem ($i)$ext").also { i++ }
        part.copyTo(target)
        return FileProvider.getUriForFile(app, "${app.packageName}.clipimage", target)
    }

    class SendResult(val ok: Int, val failed: Int, val message: String)

    /**
     * 分享菜单「发送到电脑」：逐个发送文件，progress(序号, 总数, 文件名, 百分比)。
     * 连不上电脑时，小文件（合计 ≤ 20 MB）改为经网盘中转（会出现在电脑的「下载\Ifboard」与剪贴板）。
     */
    suspend fun sendFiles(uris: List<Uri>, progress: (Int, Int, String, Int) -> Unit): SendResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext SendResult(0, uris.size, "请先在键盘菜单里开启「剪贴板同步」（发送文件走同一条加密连接）")
            lastLanFailMs = 0L // 用户主动发送：马上重新找电脑
            val s = ensureSession() ?: return@withContext fallbackDav(uris)
            activeTransfers.incrementAndGet()
            closeJob?.cancel()
            var ok = 0
            var failed = 0
            var lastErr: String? = null
            try {
                for ((i, uri) in uris.withIndex()) {
                    try {
                        sendOne(s, uri, i + 1, uris.size, progress)
                        ok++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        lastErr = e.message
                        Timber.w(e, "clip sync: send file failed")
                        if (!s.alive) {
                            failed += uris.size - i - 1
                            break
                        }
                    }
                }
            } finally {
                activeTransfers.decrementAndGet()
                if (!keyboardVisible) scheduleIdleClose()
            }
            if (failed == 0) SendResult(ok, 0, "已发送 $ok 个文件到 ${s.peerName}（电脑「下载\\Ifboard」）")
            else SendResult(ok, failed, "成功 $ok 个，失败 $failed 个：${lastErr ?: "未知错误"}")
        }

    private suspend fun sendOne(s: Session, uri: Uri, index: Int, count: Int, progress: (Int, Int, String, Int) -> Unit) {
        val cr = app.contentResolver
        var name: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val fileName = safeFileName(name ?: uri.lastPathSegment ?: "file")
        val mime = cr.getType(uri) ?: "application/octet-stream"
        progress(index, count, fileName, 0)
        // 先算 SHA-256，同时得到真实大小（部分来源不报告大小）
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(CHUNK)
        (cr.openInputStream(uri) ?: error("无法读取 $fileName")).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        val sha = hex(md.digest())
        val id = sha.substring(0, 24)
        val w = XferWait()
        s.xfers[id] = w
        try {
            if (!send(s, xmsg(s, "fbeg", "id" to id, "name" to fileName, "size" to total, "sha" to sha, "mime" to mime)))
                error("连接已断开")
            val (off, why) = withTimeoutOrNull(XFER_STEP_MS) { w.ready.await() } ?: error("电脑没有回应")
            if (off < 0) error(why ?: "电脑拒收")
            w.acked.set(off)
            (cr.openInputStream(uri) ?: error("无法读取 $fileName")).use { input ->
                var skipped = 0L
                while (skipped < off) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), off - skipped).toInt())
                    if (n < 0) error("读取文件失败")
                    skipped += n
                }
                var sent = off
                var lastUi = 0L
                while (sent < total) {
                    while (sent - w.acked.get() >= WINDOW_CHUNKS.toLong() * CHUNK) {
                        withTimeoutOrNull(XFER_STEP_MS) { w.progress.receive() } ?: error("电脑没有回应")
                        if (!s.alive) error("连接已断开")
                    }
                    val want = minOf(CHUNK.toLong(), total - sent).toInt()
                    var n = 0
                    while (n < want) {
                        val r = input.read(buf, n, want - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n <= 0) error("读取文件失败")
                    if (!send(s, xmsg(s, "fdat", "id" to id, "off" to sent, body = buf.copyOf(n)))) error("连接已断开")
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 500) {
                        lastUi = now
                        progress(index, count, fileName, (sent * 100 / total.coerceAtLeast(1)).toInt())
                    }
                }
            }
            if (!send(s, xmsg(s, "fend", "id" to id))) error("连接已断开")
            val ok = withTimeoutOrNull(120_000L) { w.done.await() } ?: error("电脑没有确认")
            if (!ok) error("电脑校验失败")
            progress(index, count, fileName, 100)
            Timber.i("clip sync: sent file %dB", total)
        } finally {
            s.xfers.remove(id)
        }
    }

    /** 连不上电脑：小文件经网盘中转（同剪贴板文件同步） */
    private fun fallbackDav(uris: List<Uri>): SendResult {
        if (creds() == null) return SendResult(0, uris.size, "没找到电脑：请让手机和电脑连同一网络")
        val cr = app.contentResolver
        val items = mutableListOf<Triple<String, String, ByteArray>>()
        var total = 0L
        for (uri in uris) {
            var name: String? = null
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
            val bytes = runCatching {
                cr.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        if (total + out.size() > MAX_DAV_ITEM) return SendResult(
                            0, uris.size, "没找到电脑，文件合计超过 20 MB，无法经网盘中转：请让手机和电脑连同一网络"
                        )
                    }
                    out.toByteArray()
                }
            }.getOrNull() ?: return SendResult(0, uris.size, "无法读取文件")
            total += bytes.size
            items += Triple(safeFileName(name ?: uri.lastPathSegment ?: "file"), cr.getType(uri) ?: "application/octet-stream", bytes)
        }
        items.forEach { (n, mime, bytes) -> publish(mime, bytes, n) }
        return SendResult(items.size, 0, "没找到电脑，已经网盘中转 ${items.size} 个文件（电脑会存到「下载\\Ifboard」并放进剪贴板）")
    }

    /** 通知栏「接收文件」开关：马上连电脑，保持 2 分钟，让电脑排队的文件发过来 */
    fun receiveNow() {
        if (!isEnabled()) {
            main.post { android.widget.Toast.makeText(app, "请先在键盘菜单里开启「剪贴板同步」", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        holdUntilMs = System.currentTimeMillis() + 120_000L
        lastLanFailMs = 0L
        scope.launch {
            val s = ensureSession()
            val msg = if (s != null) "已连上 ${s.peerName}：电脑排队要发的文件会自动收下（保持 2 分钟）"
            else "没找到电脑：请确认手机和电脑在同一网络"
            main.post { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show() }
            if (!keyboardVisible) scheduleIdleClose()
        }
    }

    // ------------------------------------------------------------------ helpers

    @Synchronized
    private fun keys(): ClipSyncKeys? {
        val c = creds() ?: return null
        val tag = hex(MessageDigest.getInstance("SHA-256").digest((c.user.trim().lowercase() + "\u0000" + c.pass).toByteArray()))
        cachedKeys?.let { if (it.first == tag) return it.second }
        // 推导结果缓存起来：进程重启后不必再做 12 万次迭代（WebDAV 密码本身也保存在同一份私有设置里）
        sp.getString(PREF_KEYS, null)?.split('|')?.takeIf { it.size == 4 && it[0] == tag }?.let { p ->
            runCatching {
                val d = Base64.getDecoder()
                ClipSyncKeys.restore(d.decode(p[1]), d.decode(p[2]), p[3])
            }.getOrNull()?.let { k -> cachedKeys = tag to k; return k }
        }
        return ClipSyncKeys.derive(c.user, c.pass).also { k ->
            cachedKeys = tag to k
            val e = Base64.getEncoder()
            sp.edit().putString(PREF_KEYS, "$tag|${e.encodeToString(k.enc)}|${e.encodeToString(k.disc)}|${k.fingerprint}").apply()
        }
    }

    private fun deviceId(): String = sp.getString(PREF_ID, null) ?: ("ad" + hex(ClipSyncCrypto.randomBytes(5))).also {
        sp.edit().putString(PREF_ID, it).apply()
    }

    /** @return true 表示首次见到该 id */
    @Synchronized
    private fun rememberSeen(id: String): Boolean {
        if (seenIds.containsKey(id)) return false
        seenIds[id] = System.currentTimeMillis()
        if (seenIds.size > 256) seenIds.keys.iterator().let { it.next(); it.remove() }
        return true
    }

    private fun hashOf(mime: String, data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").apply { update("$mime\n".toByteArray()); update(data) }.digest())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun setStatus(s: String) {
        status = s
        main.post { onStatusChanged?.invoke(s) }
    }
}
