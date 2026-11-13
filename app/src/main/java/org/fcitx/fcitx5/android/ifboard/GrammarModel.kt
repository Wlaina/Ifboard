/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: the wanxiang grammar model (~400 MB) is no longer bundled in the APK.
 * It is downloaded on demand from the official release
 *   https://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS
 * into the Rime user dir (files/data/rime), which librime-octagram searches before the shared dir.
 * That dir survives APK upgrades, so the model is downloaded only once (or when it is updated upstream).
 *
 * Without the file octagram just skips grammar scoring, so typing works either way.
 */
package org.fcitx.fcitx5.android.ifboard

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object GrammarModel {

    const val FILE = "wanxiang-lts-zh-hans.gram"
    private const val API = "https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/tags/LTS"
    private const val MAGIC = "Rime::Grammar/"

    // SharedPreferences keys
    const val PREF_ASKED = "ifb_gram_asked"
    private const val PREF_DIGEST = "ifb_gram_digest"
    private const val PREF_UPDATED = "ifb_gram_updated"

    data class Remote(val url: String, val size: Long, val sha256: String?, val updated: String)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Downloading(val done: Long, val total: Long) : State()
        data object Verifying : State()
        data class Failed(val message: String) : State()
        data object Done : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "data/rime")
    fun file(ctx: Context) = File(dir(ctx), FILE)
    private fun part(ctx: Context) = File(dir(ctx), "$FILE.part")
    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun installed(ctx: Context) = file(ctx).let { it.isFile && it.length() > 0 }

    /** upstream "updated_at" of the installed copy, e.g. "2026-11-12T07:34:33Z" */
    fun installedVersion(ctx: Context): String? = prefs(ctx).getString(PREF_UPDATED, null)

    val busy get() = job?.isActive == true

    suspend fun fetchRemote(): Remote = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API)
            .header("User-Agent", "Ifboard-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .build()
        IfboardSync.executeFriendly(http, req).use { resp ->
            if (resp.code == 403 || resp.code == 429) error("GitHub API 访问受限（${resp.code}），请稍后再试")
            if (!resp.isSuccessful) error("无法访问 GitHub（${resp.code}）")
            val assets = JSONObject(resp.body!!.string()).getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == FILE) {
                    return@withContext Remote(
                        url = a.getString("browser_download_url"),
                        size = a.getLong("size"),
                        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                        updated = a.optString("updated_at")
                    )
                }
            }
            error("官方发布页里没有找到 $FILE")
        }
    }

    /** true when upstream has a different file than the installed one */
    fun isNewer(ctx: Context, r: Remote): Boolean {
        if (!installed(ctx)) return true
        val digest = prefs(ctx).getString(PREF_DIGEST, null)
        // a copy installed without metadata (e.g. carried over from an old version): compare by size
        if (digest == null) return file(ctx).length() != r.size
        return r.sha256 != null && r.sha256 != digest
    }

    /**
     * Download (or update) in the application scope, so it keeps going when the page is closed.
     * An interrupted download resumes from the .part file. Progress is reported through [state].
     */
    fun start(ctx: Context) {
        if (busy) return
        val app = ctx.applicationContext
        job = FcitxApplication.getInstance().coroutineScope.launch {
            // the application scope runs on the main thread, so toasts are fine here
            runCatching { download(app) }
                .onSuccess {
                    if (!isActive) return@onSuccess
                    _state.value = State.Done
                    android.widget.Toast.makeText(app, "语法模型已就绪", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    Timber.w(it, "grammar model download failed")
                    _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
                    android.widget.Toast.makeText(app, "语法模型下载失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    private suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val r = fetchRemote()
        val dir = dir(ctx).apply { mkdirs() }
        val part = part(ctx)
        val pp = prefs(ctx)
        // a partial file from another upstream version can't be resumed
        val partKey = "ifb_gram_part_digest"
        if (part.exists() && pp.getString(partKey, null) != (r.sha256 ?: r.updated)) part.delete()
        pp.edit().putString(partKey, r.sha256 ?: r.updated).apply()
        if (part.length() > r.size) part.delete()

        val need = r.size - part.length() + 16L * 1024 * 1024
        if (dir.usableSpace < need) error("手机剩余空间不足，需要约 ${need / 1024 / 1024} MB")

        var done = part.length()
        _state.value = State.Downloading(done, r.size)
        if (done < r.size) {
            val req = Request.Builder().url(r.url).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
            IfboardSync.executeFriendly(http, req).use { resp ->
                if (!resp.isSuccessful) error("下载失败（${resp.code}）")
                // server ignored Range: start over
                val append = done > 0 && resp.code == 206
                if (!append) done = 0
                val body = resp.body ?: error("下载失败：没有内容")
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    val input = body.byteStream()
                    var last = 0L
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last >= 1024 * 1024) {
                            last = done
                            _state.value = State.Downloading(done, r.size)
                        }
                    }
                }
            }
        }
        if (!isActive) return@withContext
        if (part.length() != r.size) error("下载不完整，请重试（会接着上次的进度）")

        _state.value = State.Verifying
        if (!part.inputStream().use { s -> ByteArray(MAGIC.length).also { s.read(it) } }.decodeToString().startsWith(MAGIC)) {
            part.delete(); error("文件格式不对，已删除")
        }
        val sha = sha256(part)
        if (r.sha256 != null && sha != r.sha256) {
            part.delete(); error("校验失败（sha256 不一致），已删除，请重试")
        }
        // rename(2) replaces the old file atomically; a running Rime keeps its mapping of the old one
        val target = file(ctx)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) error("无法保存模型文件")
        }
        pp.edit()
            .putString(PREF_DIGEST, sha)
            .putString(PREF_UPDATED, r.updated)
            .remove(partKey)
            .apply()
        // reload Rime so the new model is picked up now
        runCatching { FcitxDaemon.restartFcitx() }.onFailure { Timber.w(it, "restart fcitx failed") }
    }

    fun delete(ctx: Context) {
        if (busy) cancel()
        file(ctx).delete()
        part(ctx).delete()
        prefs(ctx).edit().remove(PREF_DIGEST).remove(PREF_UPDATED).apply()
        _state.value = State.Idle
        runCatching { FcitxDaemon.restartFcitx() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun mb(bytes: Long) = "%.0f MB".format(bytes / 1024.0 / 1024.0)
}
