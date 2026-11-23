/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: offline streaming voice typing (Chinese + English) with k2-fsa/sherpa-onnx (Apache-2.0).
 *
 * The model (~134 MB) is not bundled in the APK. On request it is downloaded from the official release
 *   https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 * pinned to one file and verified against its SHA-256 before it is unpacked into the app's private dir.
 * Recognition runs entirely on the phone: audio never leaves the device.
 */
package org.fcitx.fcitx5.android.ifboard.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ifboard.IfboardSync
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/** 离线语音模型：下载、校验、解压、删除 */
object OfflineVoiceModel {
    const val NAME = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NAME.tar.bz2"
    const val SIZE = 133_895_136L
    private const val SHA256 = "fa5f63d618e5a01526e275a358bb7772e403f84808a4769fba52cffd8160bf74"

    /** 偏好：有模型时是否用离线识别（默认是） */
    const val PREF_ENABLED = "ifb_offline_voice"

    sealed class State {
        data object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State()
        data object Unpacking : State()
        data class Failed(val message: String) : State()
        data object Done : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    val busy get() = job?.isActive == true

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun root(ctx: Context) = File(ctx.filesDir, "offline_voice")
    fun dir(ctx: Context) = File(root(ctx), NAME)
    private fun part(ctx: Context) = File(root(ctx), "$NAME.tar.bz2.part")

    /** 模型文件齐全（编码器 / 解码器 / 连接器 / 词表） */
    fun installed(ctx: Context) = files(ctx) != null

    fun enabled(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(PREF_ENABLED, on).apply()

    /** 离线识别能否使用：已下载且开启 */
    fun usable(ctx: Context) = enabled(ctx) && installed(ctx)

    class Files(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    /** 按文件名找出各部分（优先 int8 量化版），不依赖压缩包里的具体命名 */
    fun files(ctx: Context): Files? {
        val all = dir(ctx).listFiles()?.filter { it.isFile } ?: return null
        fun pick(key: String) = all.filter { it.name.endsWith(".onnx") && it.name.contains(key) }
            .sortedByDescending { it.name.contains("int8") }.firstOrNull()
        val tokens = all.firstOrNull { it.name == "tokens.txt" } ?: return null
        return Files(pick("encoder") ?: return null, pick("decoder") ?: return null, pick("joiner") ?: return null, tokens)
    }

    fun sizeOnDisk(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun start(ctx: Context) {
        if (busy) return
        val app = ctx.applicationContext
        job = FcitxApplication.getInstance().coroutineScope.launch {
            val wakeLock = runCatching {
                val pm = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ifboard:voice_dl")?.apply {
                    setReferenceCounted(false); acquire(30 * 60_000L)
                }
            }.getOrNull()
            try {
                runCatching { download(app) }
                    .onSuccess {
                        if (!isActive) return@onSuccess
                        _state.value = State.Done
                        android.widget.Toast.makeText(app, "离线语音已就绪", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) return@onFailure
                        Timber.w(it, "offline voice model download failed")
                        _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
                        android.widget.Toast.makeText(app, "离线语音模型下载失败：${it.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun delete(ctx: Context) {
        if (busy) cancel()
        OfflineAsr.release()
        root(ctx).deleteRecursively()
        _state.value = State.Idle
    }

    private suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        val root = root(ctx).apply { mkdirs() }
        val part = part(ctx)
        if (part.length() > SIZE) part.delete()
        // 压缩包 + 解压后的模型，大约需要两倍空间
        val need = (SIZE - part.length()) + SIZE * 2
        if (root.usableSpace < need) error("手机剩余空间不足，需要约 ${need / 1024 / 1024} MB")

        var attempt = 0
        var lastError: Throwable? = null
        while (part.length() < SIZE && attempt < 6 && isActive) {
            attempt++
            var done = part.length()
            _state.value = State.Downloading(done, SIZE)
            try {
                val req = Request.Builder().url(URL).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
                IfboardSync.executeFriendly(http, req).use { resp ->
                    if (!resp.isSuccessful) error("下载失败（${resp.code}）")
                    val append = done > 0 && resp.code == 206
                    if (!append) done = 0
                    val body = resp.body ?: error("下载失败：没有内容")
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        var lastUi = 0L
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastUi >= 1024 * 1024) {
                                lastUi = done
                                _state.value = State.Downloading(done, SIZE)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                Timber.w(e, "voice model transfer attempt $attempt failed at ${part.length()}/$SIZE")
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        if (!isActive) return@withContext
        if (part.length() != SIZE) error("下载不完整（${lastError?.message ?: "连接中断"}），请重试，已保留断点")

        _state.value = State.Unpacking
        if (sha256(part) != SHA256) {
            part.delete()
            error("校验失败（sha256 不一致），已删除，请重试")
        }
        val staging = File(root, "$NAME.staging").apply { deleteRecursively(); mkdirs() }
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(part.inputStream(), 1 shl 16))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    // 只取识别要用的文件（跳过示例音频等），且只用文件名本身，避免压缩包里的路径越界
                    if (!(name.endsWith(".onnx") || name == "tokens.txt")) continue
                    if (name.isEmpty() || name.startsWith(".")) continue
                    FileOutputStream(File(staging, name)).use { out -> tar.copyTo(out, 1 shl 16) }
                }
            }
            val target = dir(ctx)
            target.deleteRecursively()
            if (!staging.renameTo(target)) error("无法保存模型文件")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        part.delete()
        if (!installed(ctx)) error("压缩包里缺少模型文件")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun mb(bytes: Long) = "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * 离线流式识别：麦克风 16 kHz 单声道 → sherpa-onnx，边说边出字，停顿处断句。
 * 识别器加载一次（约 1 秒）后常驻，键盘停用语音 3 分钟后释放内存。
 */
object OfflineAsr {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** 0..10，与系统识别服务的 rmsdB 范围大致一致 */
        fun onLevel(level: Float)
        fun onError(message: String)
    }

    private const val SAMPLE_RATE = 16000
    /** 键盘收起后多久释放模型内存（约 200 MB） */
    private const val RELEASE_DELAY_MS = 5 * 60_000L

    @Volatile
    private var preloading = false

    /**
     * 弹出键盘时在后台预先加载模型（首次约 5 秒），按麦克风时就能立即开始听。
     * 只在已下载并开启离线语音时加载。
     */
    fun preload(ctx: Context) {
        main.removeCallbacks(releaseRunnable)
        if (recognizer != null || preloading || !OfflineVoiceModel.usable(ctx)) return
        preloading = true
        val app = ctx.applicationContext
        Thread({
            try {
                load(app)
            } catch (e: Throwable) {
                Timber.w(e, "offline asr preload failed")
            } finally {
                preloading = false
            }
        }, "ifb-offline-asr-preload").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    /** 键盘收起：一段时间不用就释放模型 */
    fun onKeyboardHidden() {
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    @Volatile
    private var generation = 0
    @Volatile
    private var worker: Thread? = null
    private val releaseRunnable = Runnable { if (worker == null) release() }

    val running get() = worker != null

    private val spaceAroundCjkPunct = Regex("\\s*([，。！？、；：“”‘’（）《》])\\s*")
    private val spaceBetweenCjk = Regex("(?<=[\\u3400-\\u9fff])\\s+(?=[\\u3400-\\u9fff])")

    private val multiSpace = Regex(" {2,}")

    /**
     * 去掉中文标点前后、汉字之间多余的空格（模型输出常带空格），
     * 再把中文逗号、句号换成英文标点加一个空格（「, 」「. 」）。
     */
    fun tidy(text: String): String =
        text.trim().replace(spaceAroundCjkPunct, "$1").replace(spaceBetweenCjk, "")
            .replace("，", ", ").replace("。", ". ")
            .replace(multiSpace, " ").trim()

    private fun isCjk(c: Char) = c in '\u3400'..'\u9fff'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() && !isCjk(c)

    /**
     * 新一句接在已有文字后面时用的分隔：英文/数字之间补空格，汉字后面补逗号，
     * 已有标点或空白时不补。
     */
    fun separator(before: CharSequence?, next: String): String {
        val prev = before?.lastOrNull() ?: return ""
        val first = next.firstOrNull() ?: return ""
        return when {
            prev.isWhitespace() -> ""
            isWordChar(prev) && isWordChar(first) -> " "
            isCjk(prev) && !(first in "，。！？、；：,.!?;:") -> ", "
            prev in ",.!?;:" && !first.isWhitespace() -> " "
            isWordChar(prev) && isCjk(first) -> " "
            else -> ""
        }
    }

    fun start(ctx: Context, listener: Listener) {
        val prev = worker
        stop()
        main.removeCallbacks(releaseRunnable)
        val gen = ++generation
        val app = ctx.applicationContext
        worker = Thread({
            // 上一段的录音线程最多再读 100 ms 就会退出；等它释放麦克风，避免新录音初始化失败
            runCatching { prev?.join(1000) }
            loop(app, gen, listener)
        }, "ifb-offline-asr").apply { start() }
    }

    /** 停止收音；已说完但还没断句的内容由调用方（上屏的组合文字）处理 */
    fun stop() {
        generation++
        worker = null
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
    }

    fun release() = synchronized(lock) {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun load(ctx: Context): OnlineRecognizer = synchronized(lock) {
        recognizer?.let { return it }
        val f = OfflineVoiceModel.files(ctx) ?: error("离线语音模型未下载")
        val t0 = System.currentTimeMillis()
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply { sampleRate = SAMPLE_RATE; featureDim = 80 }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = f.encoder.absolutePath
                    decoder = f.decoder.absolutePath
                    joiner = f.joiner.absolutePath
                }
                tokens = f.tokens.absolutePath
                numThreads = 2
                provider = "cpu"
            }
            // 断句：说过话后静音 0.8 秒即断开一句；没说话时 2.4 秒；单句最长 20 秒
            endpointConfig = EndpointConfig().apply {
                rule1 = EndpointRule(false, 2.4f, 0f)
                rule2 = EndpointRule(true, 0.8f, 0f)
                rule3 = EndpointRule(false, 0f, 20f)
            }
            enableEndpoint = true
            decodingMethod = "greedy_search"
        }
        OnlineRecognizer(null, config).also {
            recognizer = it
            Timber.i("IfboardVoiceT offline model loaded in %dms", System.currentTimeMillis() - t0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context, gen: Int, l: Listener) {
        fun post(block: () -> Unit) = main.post { if (generation == gen) block() }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            post { l.onError("需要麦克风权限") }
            return
        }
        val rec = try {
            load(ctx)
        } catch (e: Throwable) {
            Timber.w(e, "offline asr load failed")
            post { l.onError("离线语音加载失败：${e.message}") }
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5 * 2)
            )
        }.getOrNull()
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { ar?.release() }
            post { l.onError("麦克风被占用或不可用") }
            return
        }
        val stream = rec.createStream("")
        try {
            ar.startRecording()
            post { l.onReady() }
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
            var last = ""
            var firstText = 0L
            val startMs = System.currentTimeMillis()
            while (generation == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) {
                    post { l.onError("录音中断（$n）") }
                    break
                }
                if (n == 0) continue
                val samples = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768f
                    samples[i] = v
                    sum += v * v
                }
                val db = 20 * log10(sqrt(sum / n).coerceAtLeast(1e-6))
                val level = ((db + 55) / 4).toFloat().coerceIn(0f, 10f)
                post { l.onLevel(level) }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (rec.isReady(stream)) rec.decode(stream)
                val text = tidy(rec.getResult(stream).text)
                if (text != last) {
                    if (firstText == 0L && text.isNotEmpty()) {
                        firstText = System.currentTimeMillis()
                        Timber.i("IfboardVoiceT offline first-text +%dms after start", firstText - startMs)
                    }
                    last = text
                    post { l.onPartial(text) }
                }
                if (rec.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        val done = text
                        post { l.onFinal(done) }
                    }
                    rec.reset(stream)
                    last = ""
                }
            }
        } catch (e: Throwable) {
            Timber.w(e, "offline asr loop failed")
            post { l.onError("离线识别出错：${e.message}") }
        } finally {
            runCatching { ar.stop() }
            runCatching { ar.release() }
            runCatching { stream.release() }
            if (worker === Thread.currentThread()) worker = null
        }
    }
}
