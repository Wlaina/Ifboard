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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object IfboardSync {

    const val REPO = "Wlaina/Ifboard"
    const val URL_KOOFR = "https://app.koofr.net/dav/Koofr"
    const val URL_JIANGUOYUN = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_WEBDAV_URL = URL_KOOFR

    data class Result(val uploaded: Int, val downloaded: Int, val devices: List<String>)

    private val http by lazy {
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

    private class Dav(base: String, private val user: String, private val pass: String) {
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
                if (!resp.isSuccessful) return emptyList()
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
            // download other devices (skip internal metadata folders like _pc_profile)
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
            Result(mine.size, down, devices)
        }

    data class Release(val version: String, val notes: String, val url: String, val apkUrl: String?)

    /** newer release on GitHub, or null when up to date */
    suspend fun checkUpdate(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("User-Agent", "Ifboard-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .build()
        executeFriendly(http, req).use { resp ->
            if (resp.code == 404) return@withContext null
            if (resp.code == 403 || resp.code == 429) error("GitHub API 访问受限（${resp.code}），请稍后再试")
            if (!resp.isSuccessful) error("GitHub 访问失败（${resp.code}）")
            val j = JSONObject(resp.body!!.string())
            val tag = j.getString("tag_name").removePrefix("v")
            if (compare(tag, BuildConfig.VERSION_NAME.removePrefix("v")) <= 0) return@withContext null
            val assets = j.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
            }
            Release(tag, j.optString("body"), j.getString("html_url"), apk)
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
