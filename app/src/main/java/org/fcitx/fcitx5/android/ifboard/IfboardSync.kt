/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: WebDAV sync of Rime user dictionaries + GitHub release update check.
 *
 * Rime keeps one snapshot per device in  <rime user dir>/sync/<installation_id>/ (one .userdb.txt per dictionary)
 * and merges every other device folder found there when it syncs. WebDAV mirrors that layout:
 *   <server>/<dir>/<installation_id>/<name>.userdb.txt
 */
package org.fcitx.fcitx5.android.ifboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.keyboard.TypoCorrector
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object IfboardSync {

    const val REPO = "Wlaina/Ifboard"
    const val URL_KOOFR = "https://app.koofr.net/dav/Koofr"
    const val URL_JIANGUOYUN = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_WEBDAV_URL = URL_KOOFR
    private const val TOUCH_MODEL_DIR = "_touch_model"
    private const val TOUCH_MODEL_FILE = "touch_model.json"
    private const val AUTO_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000L
    private const val AUTO_SYNC_RETRY_COOLDOWN_MS = 30L * 60 * 1000L

    data class Result(
        val uploaded: Int,
        val downloaded: Int,
        val devices: List<String>,
        val touchSynced: Boolean = false,
        val touchSamples: Int = 0
    )

    data class ConnectionTestResult(
        val deviceDirs: List<String>,
        val hasCloudTouchModel: Boolean,
        val cloudTouchSamples: Int
    )

    private val autoSyncRunning = AtomicBoolean(false)
    private val lastAutoAttemptMs = AtomicLong(0L)

    internal val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun rimeDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "data/rime")

    fun installationId(ctx: Context): String? =
        File(rimeDir(ctx), "installation.yaml").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.trim().startsWith("installation_id:") }
            ?.substringAfter(":")?.trim()?.trim('"', '\'')

    fun executeFriendly(client: OkHttpClient, req: Request): okhttp3.Response {
        return try {
            client.newCall(req).execute()
        } catch (e: java.io.IOException) {
            val msg = when (e) {
                is java.net.UnknownHostException -> "网络无法连接或域名解析失败，请检查网络"
                is java.net.SocketTimeoutException -> "连接超时，请稍后再试"
                is javax.net.ssl.SSLException -> "安全连接（TLS）失败，请检查网络环境或系统时间"
                is java.net.ConnectException -> "无法连接到服务器，请检查地址与网络"
                else -> "网络请求失败：${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            throw IllegalStateException(msg, e)
        }
    }

    /** Ask Rime to export this device's snapshot and merge the others (same as the keyboard's 同步) */
    suspend fun rimeSync() {
        // librime 合并同步快照时会建临时库「.temp」，Android 上合并后删不干净；
        // 残留的空 .temp.userdb 会在下次同步时被当成用户词库去备份并报
        // 「failed synchronizing 1/2 user dicts」，表现为先报错再成功。同步前先清掉它。
        removeStaleTempUserDb()
        runCatching {
            val conn = FcitxDaemon.connect("IfboardSync")
            conn.runOnReady {
                val sync = statusArea().flatMap { listOf(it) + (it.menu?.toList() ?: emptyList()) }
                    .firstOrNull { it.name.contains("sync", ignoreCase = true) }
                if (sync != null) activateAction(sync.id)
            }
            FcitxDaemon.disconnect("IfboardSync")
        }
        delay(2500)
    }

    private fun removeStaleTempUserDb() = runCatching {
        val dir = File(rimeDir(FcitxApplication.getInstance()), ".temp.userdb")
        if (dir.isDirectory) dir.deleteRecursively()
    }

    internal class Dav(base: String, private val user: String, private val pass: String) {
        val root = base.trimEnd('/') + "/"
        private fun req(url: String) = Request.Builder().url(url).header("Authorization", Credentials.basic(user, pass))

        fun mkcol(path: String) {
            val clean = path.trim('/').ifEmpty { return }
            var current = ""
            for (seg in clean.split('/')) {
                if (seg.isBlank()) continue
                current = if (current.isEmpty()) seg else "$current/$seg"
                executeFriendly(http, req("$root$current/").method("MKCOL", null).build()).use {
                    if (it.code == 401) error("账号或密码不对")
                    if (it.code !in 200..399 && it.code != 405) error("创建目录失败（${it.code}）")
                }
            }
        }

        fun put(path: String, bytes: ByteArray) {
            executeFriendly(http, req(root + path).put(bytes.toRequestBody("text/plain".toMediaType())).build()).use {
                if (it.code == 401) error("账号或密码不对")
                if (!it.isSuccessful) error("上传失败（${it.code}）")
            }
        }

        /** 条件下载：内容未变（304）时返回 (null, 原 etag)，省流量 */
        fun getIfChanged(path: String, etag: String?): Pair<ByteArray?, String?> {
            val b = req(root + path).get()
            if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
            executeFriendly(http, b.build()).use {
                if (it.code == 304) return null to etag
                if (!it.isSuccessful) return null to null
                return it.body?.bytes() to it.header("ETag")
            }
        }

        fun get(path: String): ByteArray? =
            executeFriendly(http, req(root + path).get().build()).use { if (it.isSuccessful) it.body?.bytes() else null }

        /** names of direct children; folders end with "/" */
        fun list(path: String): List<String> {
            val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
            val clean = path.trim('/')
            val url = if (clean.isEmpty()) root else "$root$clean/"
            executeFriendly(
                http,
                req(url).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
            ).use { resp ->
                if (resp.code == 401) error("账号或密码不对")
                if (resp.code == 404) return emptyList()
                if (!resp.isSuccessful) error("服务器响应异常（${resp.code}）")
                val xml = resp.body?.string().orEmpty()
                val self = runCatching {
                    java.net.URLDecoder.decode(java.net.URI(url).rawPath, "UTF-8")
                }.getOrDefault(java.net.URI(url).path).trimEnd('/')
                val parser = android.util.Xml.newPullParser().apply {
                    setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    setInput(xml.reader())
                }
                val out = mutableListOf<String>()
                var inResponse = false
                var href: String? = null
                var isCollection = false
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                            "response" -> {
                                inResponse = true
                                href = null
                                isCollection = false
                            }
                            "href" -> if (inResponse) href = parser.nextText().trim()
                            "collection" -> if (inResponse) isCollection = true
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name.lowercase() == "response" && inResponse) {
                            inResponse = false
                            val raw = href
                            if (!raw.isNullOrBlank()) {
                                val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                                val pathOnly = if (decoded.startsWith("http")) {
                                    runCatching { java.net.URI(decoded).path }.getOrDefault(decoded)
                                } else {
                                    decoded
                                }
                                val trimmed = pathOnly.trimEnd('/')
                                if (trimmed != self && trimmed.isNotEmpty()) {
                                    val name = trimmed.substringAfterLast('/')
                                    if (name.isNotEmpty()) {
                                        val dir = isCollection || pathOnly.endsWith("/")
                                        out += if (dir) "$name/" else name
                                    }
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
                return out.distinct()
            }
        }
    }

    suspend fun testConnection(url: String, user: String, pass: String, dir: String): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim().ifEmpty { DEFAULT_WEBDAV_URL }
            require(cleanUrl.startsWith("http")) { "请先填写 WebDAV 地址" }
            require(user.isNotBlank() && pass.isNotBlank()) { "请先填写 WebDAV 账号和密码" }
            val dav = Dav(cleanUrl, user, pass)
            val base = dir.trim('/').ifEmpty { "Ifboard" }
            // 先探测根目录验证认证是否通过，再检查目标目录与触摸习惯文件
            dav.list("")
            val dirs = dav.list(base)
                .filter { it.endsWith("/") }
                .map { it.trimEnd('/') }
                .filter { !it.startsWith("_") && !it.startsWith(".") }
            val touchJson = dav.get("$base/$TOUCH_MODEL_DIR/$TOUCH_MODEL_FILE")?.toString(Charsets.UTF_8)
            val parsedTouch = touchJson?.let { TypoCorrector.parsePayload(it) }
            ConnectionTestResult(
                deviceDirs = dirs,
                hasCloudTouchModel = parsedTouch != null,
                cloudTouchSamples = parsedTouch?.samples ?: 0
            )
        }

    suspend fun webdavSync(ctx: Context, url: String, user: String, pass: String, dir: String): Result =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim().ifEmpty { DEFAULT_WEBDAV_URL }
            require(cleanUrl.startsWith("http")) { "请先填写 WebDAV 地址" }
            require(user.isNotBlank() && pass.isNotBlank()) { "请先填写 WebDAV 账号和密码" }
            val id = installationId(ctx) ?: error("还没有生成本机的 Rime 设备号，请先打一会儿字")
            rimeSync()
            val dav = Dav(cleanUrl, user, pass)
            val base = dir.trim('/').ifEmpty { "Ifboard" }
            dav.mkcol(base)
            dav.mkcol("$base/$id")
            // upload this device's snapshot
            val mine = File(rimeDir(ctx), "sync/$id")
                .listFiles { f -> f.isFile && f.name.endsWith(".userdb.txt") && f.canRead() && f.length() > 0L }
                ?: emptyArray()
            mine.forEach { dav.put("$base/$id/${it.name}", it.readBytes()) }
            // download other devices (skip internal metadata folders like _pc_profile / _touch_model)
            var down = 0
            val devices = dav.list(base)
                .filter { it.endsWith("/") }
                .map { it.trimEnd('/') }
                .filter { it != id && !it.startsWith("_") && !it.startsWith(".") }
            devices.forEach { dev ->
                dav.list("$base/$dev").filter { it.endsWith(".userdb.txt") }.forEach { name ->
                    dav.get("$base/$dev/$name")?.takeIf { it.isNotEmpty() }?.let { bytes ->
                        val dirDev = File(rimeDir(ctx), "sync/$dev").apply { mkdirs() }
                        val target = File(dirDev, name)
                        val tmp = File(dirDev, "$name.tmp")
                        tmp.writeBytes(bytes)
                        if (target.exists() && !target.canWrite()) target.delete()
                        if (!tmp.renameTo(target)) {
                            target.writeBytes(bytes)
                            tmp.delete()
                        }
                        down++
                    }
                }
            }
            // merge what was downloaded
            if (down > 0) rimeSync()

            // sync TypoCorrector touch model (_touch_model/touch_model.json)
            var touchSynced = false
            runCatching {
                dav.mkcol("$base/$TOUCH_MODEL_DIR")
                val touchPath = "$base/$TOUCH_MODEL_DIR/$TOUCH_MODEL_FILE"
                val remoteBytes = dav.get(touchPath)
                if (remoteBytes != null && remoteBytes.isNotEmpty()) {
                    val remoteStr = remoteBytes.toString(Charsets.UTF_8)
                    val (outcome, toPush) = TypoCorrector.mergeFromSyncJson(ctx, remoteStr)
                    if (toPush != null) {
                        dav.put(touchPath, toPush.toByteArray(Charsets.UTF_8))
                        TypoCorrector.parsePayload(toPush)?.updatedAt?.let {
                            TypoCorrector.markRemoteSynced(ctx, it)
                        }
                        touchSynced = true
                    } else if (outcome == TypoCorrector.SyncMergeOutcome.UPDATED_LOCAL) {
                        touchSynced = true
                    }
                } else if (TypoCorrector.snapshot(ctx).samples > 0) {
                    val json = TypoCorrector.exportSyncJson(ctx)
                    dav.put(touchPath, json.toByteArray(Charsets.UTF_8))
                    TypoCorrector.parsePayload(json)?.updatedAt?.let {
                        TypoCorrector.markRemoteSynced(ctx, it)
                    }
                    touchSynced = true
                }
            }.onFailure { Timber.w(it, "touch model webdav sync failed") }

            val samplesNow = TypoCorrector.snapshot(ctx).samples
            Result(mine.size, down, devices, touchSynced, samplesNow)
        }

    /** 检查是否满足每日（24小时）自动同步条件并在后台静默执行 */
    fun maybeAutoSync(ctx: Context) {
        val app = ctx.applicationContext
        val sp = PreferenceManager.getDefaultSharedPreferences(app)
        if (!sp.getBoolean("ifb_auto_sync_daily", true)) return
        val url = sp.getString("ifb_webdav_url", DEFAULT_WEBDAV_URL).orEmpty()
        val user = sp.getString("ifb_webdav_user", "").orEmpty()
        val pass = sp.getString("ifb_webdav_password", "").orEmpty()
        val dir = sp.getString("ifb_webdav_dir", "Ifboard").orEmpty()
        if (user.isBlank() || pass.isBlank()) return

        val now = System.currentTimeMillis()
        val lastOk = sp.getLong("ifb_webdav_last", 0L)
        if (now - lastOk < AUTO_SYNC_INTERVAL_MS) return
        if (now - lastAutoAttemptMs.get() < AUTO_SYNC_RETRY_COOLDOWN_MS) return
        if (!hasNetworkConnection(app)) return
        if (!autoSyncRunning.compareAndSet(false, true)) return
        lastAutoAttemptMs.set(now)

        FcitxApplication.getInstance().coroutineScope.launch(Dispatchers.IO) {
            try {
                webdavSync(app, url, user, pass, dir)
                sp.edit().putLong("ifb_webdav_last", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Timber.w(e, "daily auto webdav sync failed")
            } finally {
                autoSyncRunning.set(false)
            }
        }
    }

    private fun hasNetworkConnection(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching true
        val net = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    data class Release(val version: String, val notes: String, val url: String, val apkUrl: String?)

    /** 从 GitHub Releases 列表 JSON 中选出包含 `.apk` 资产的最高正式版本（跳过纯桌面端 `.exe` 发版） */
    internal fun parseLatestAndroidRelease(jsonArrayStr: String, currentVersion: String): Release? {
        val arr = Json.parseToJsonElement(jsonArrayStr) as? JsonArray ?: return null
        val curClean = currentVersion.removePrefix("v")
        var best: Release? = null
        for (el in arr) {
            val j = el as? JsonObject ?: continue
            val draft = (j["draft"] as? JsonPrimitive)?.booleanOrNull == true
            val pre = (j["prerelease"] as? JsonPrimitive)?.booleanOrNull == true
            if (draft || pre) continue

            val assets = j["assets"] as? JsonArray ?: continue
            var apkUrl: String? = null
            for (assetEl in assets) {
                val a = assetEl as? JsonObject ?: continue
                val name = (a["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = (a["browser_download_url"] as? JsonPrimitive)?.contentOrNull
                    break
                }
            }
            // 没有 APK 安装包的 Release（例如仅发布 Windows 安装包）对手机端不可见
            if (apkUrl.isNullOrBlank()) continue

            val tag = (j["tag_name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("v") ?: continue
            if (compare(tag, curClean) <= 0) continue
            if (best == null || compare(tag, best.version) > 0) {
                best = Release(
                    version = tag,
                    notes = (j["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    url = (j["html_url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    apkUrl = apkUrl
                )
            }
        }
        return best
    }

    /** newer Android release (.apk) on GitHub, or null when up to date */
    suspend fun checkUpdate(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=10")
            .header("User-Agent", "Ifboard-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .build()
        executeFriendly(http, req).use { resp ->
            if (resp.code == 404) return@withContext null
            if (resp.code == 403 || resp.code == 429) error("GitHub API 访问受限（${resp.code}），请稍后再试")
            if (!resp.isSuccessful) error("GitHub 访问失败（${resp.code}）")
            parseLatestAndroidRelease(resp.body!!.string(), BuildConfig.VERSION_NAME)
        }
    }

    private fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
