/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard clipboard sync, phone side (client). The PC (IfboardWin) is the server.
 */
package org.fcitx.fcitx5.android.ifboard.clipsync

import android.content.ClipData
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
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
    private const val MAX_DAV_ITEM = 5 * 1024 * 1024
    private const val MAX_FRAME = 24 * 1024 * 1024
    private const val IDLE_CLOSE_MS = 30_000L
    private const val PING_MS = 25_000L
    private const val DAV_PULL_INTERVAL_MS = 15_000L
    private const val PEER_REFRESH_MS = 60_000L
    private const val FRESH_MS = 10 * 60_000L
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
        if (!isEnabled()) return
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

    // ------------------------------------------------------------------ publish / receive

    private fun publish(mime: String, data: ByteArray) {
        val hash = hashOf(mime, data)
        if (hash == suppressHash) return // 刚从电脑收到并写入的内容，不再发回
        val id = hex(ClipSyncCrypto.randomBytes(8))
        val ts = System.currentTimeMillis()
        rememberSeen(id)
        scope.launch {
            val s = ensureSession()
            if (s != null && sendWithAck(s, clipMessage(s, id, mime, ts, data), id)) {
                Timber.i("clip sync: sent via lan %s %dB", mime, data.size)
                setStatus("已同步到 ${s.peerName}（局域网）")
                if (!keyboardVisible) scheduleIdleClose()
            } else {
                Timber.i("clip sync: lan unavailable, queue dav put %s %dB", mime, data.size)
                scheduleDavPut(id, mime, ts, data)
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
        val hash = hashOf(mime, body)
        val clip: ClipData = if (mime.startsWith("image/")) {
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
                setStatus("已收到 $name 的${if (mime.startsWith("image/")) "图片" else "文字"}（$via）")
            }.onFailure { Timber.w(it, "clip sync: setPrimaryClip failed") }
        }
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
        session?.takeIf { it.alive }?.let { return it }
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
                    "last" to sp.getString(PREF_LAST_PC, "")
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
                when (m.op) {
                    "clip" -> {
                        m.str("id")?.let { send(s, ClipMessage.of("ack", "s" to s.s, "c" to s.c, "id" to it)) }
                        applyRemote(m, "局域网")
                    }
                    "ack" -> m.str("id")?.let { pendingAcks[it]?.complete(Unit) }
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

    private fun clipMessage(s: Session, id: String, mime: String, ts: Long, data: ByteArray) = ClipMessage.of(
        "clip", "s" to s.s, "c" to s.c, "id" to id, "mime" to mime, "ts" to ts,
        "from" to deviceId(), "name" to Build.MODEL, body = data
    )

    private fun dropSession(s: Session) {
        s.alive = false
        runCatching { s.socket.close() }
        if (session === s) {
            session = null
            if (isEnabled()) setStatus("已断开，下次弹出键盘自动重连")
        }
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
            if (!keyboardVisible) closeSession()
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

    private fun scheduleDavPut(id: String, mime: String, ts: Long, data: ByteArray) {
        val c = creds() ?: return
        if (data.size > MAX_DAV_ITEM) {
            setStatus("未找到电脑；内容超过 5 MB，未经网盘同步")
            return
        }
        davPutJob?.cancel()
        davPutJob = scope.launch {
            delay(1500) // 连续复制只上传最后一次
            val keys = keys() ?: return@launch
            val m = ClipMessage.of(
                "clip", "id" to id, "mime" to mime, "ts" to ts, "from" to deviceId(), "name" to Build.MODEL, body = data
            )
            val sealed = ClipSyncCrypto.seal(keys.enc, m.encode())
            davMutex.withLock {
                runCatching {
                    val dav = IfboardSync.Dav(c.url, c.user, c.pass)
                    putWithFallback(dav, c, "clip-${deviceId()}.dat", sealed)
                    Timber.i("clip sync: dav put ok %s %dB", mime, data.size)
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
                    if (m.op != "clip") continue
                    if (now - m.long("ts") > FRESH_MS) {
                        Timber.i("clip sync: dav item too old, skip")
                        continue // 太久以前的复制不再写进剪贴板
                    }
                    applyRemote(m, "网盘")
                }
            }.onFailure { Timber.w(it, "clip sync: pull failed") }
            Timber.i("clip sync: dav pull done")
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
