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
            http.newCall(req(root + path.trimEnd('/') + "/").method("MKCOL", null).build()).execute().close()
        }

        fun put(path: String, bytes: ByteArray) {
            http.newCall(req(root + path).put(bytes.toRequestBody("text/plain".toMediaType())).build()).execute().use {
                if (!it.isSuccessful) error("上传失败（${it.code}）")
            }
        }

        fun get(path: String): ByteArray? =
            http.newCall(req(root + path).get().build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }

        /** names of direct children; folders end with "/" */
        fun list(path: String): List<String> {
            val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
            val url = root + path.trimEnd('/') + "/"
            http.newCall(
                req(url).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
            ).execute().use { resp ->
                if (resp.code == 401) error("账号或密码不对")
                if (!resp.isSuccessful) return emptyList()
                val xml = resp.body?.string().orEmpty()
                val hrefs = Regex("<(?:\\w+:)?href>([^<]+)</(?:\\w+:)?href>").findAll(xml).map { it.groupValues[1] }.toList()
                val self = java.net.URI(url).path
                return hrefs.map { java.net.URLDecoder.decode(it, "UTF-8") }
                    .map { if (it.startsWith("http")) java.net.URI(it).path else it }
                    .filter { it.trimEnd('/') != self.trimEnd('/') }
                    .map { it.trimEnd('/').substringAfterLast('/') + if (it.endsWith("/")) "/" else "" }
            }
        }
    }

    suspend fun webdavSync(ctx: Context, url: String, user: String, pass: String, dir: String): Result =
        withContext(Dispatchers.IO) {
            require(url.startsWith("http")) { "请先填写 WebDAV 地址" }
            val id = installationId(ctx) ?: error("还没有生成本机的 Rime 设备号，请先打一会儿字")
            rimeSync()
            val dav = Dav(url, user, pass)
            val base = dir.trim('/').ifEmpty { "Ifboard" }
            dav.mkcol(base)
            dav.mkcol("$base/$id")
            // upload this device's snapshot
            val mine = File(rimeDir(ctx), "sync/$id").listFiles { f -> f.name.endsWith(".userdb.txt") } ?: emptyArray()
            mine.forEach { dav.put("$base/$id/${it.name}", it.readBytes()) }
            // download other devices
            var down = 0
            val devices = dav.list(base).filter { it.endsWith("/") }.map { it.trimEnd('/') }.filter { it != id }
            devices.forEach { dev ->
                dav.list("$base/$dev").filter { it.endsWith(".userdb.txt") }.forEach { name ->
                    dav.get("$base/$dev/$name")?.let { bytes ->
                        File(rimeDir(ctx), "sync/$dev").mkdirs()
                        File(rimeDir(ctx), "sync/$dev/$name").writeBytes(bytes)
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
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
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
