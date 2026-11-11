/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: Gboard style inline translation and inline voice typing.
 * Both live inside the keyboard area, the keys stay usable.
 */
package org.fcitx.fcitx5.android.input.inline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.GboardFont
import org.json.JSONArray
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Redirects text produced by the keyboard away from the editor (used by inline translation). */
interface TextInterceptor {
    fun onCommit(text: String)
    fun onComposing(text: String)
    /** @return true if consumed */
    fun onBackspace(): Boolean
    /** @return true if consumed */
    fun onReturn(): Boolean
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density
private fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
}

/* ------------------------------------------------------------------ */
/*  Inline translation                                                  */
/* ------------------------------------------------------------------ */

class TranslatePanel(
    private val ctx: Context,
    private val theme: Theme,
    private val service: FcitxInputMethodService,
    private val scope: CoroutineScope
) : TextInterceptor {

    private data class Lang(val code: String, val name: String)

    private val langs = listOf(
        Lang("zh-CN", "中文"), Lang("en", "英语"), Lang("ja", "日语"), Lang("ko", "韩语"),
        Lang("fr", "法语"), Lang("de", "德语"), Lang("es", "西班牙语"), Lang("ru", "俄语"),
        Lang("hu", "匈牙利语")
    )

    private val prefs = ctx.getSharedPreferences("ifboard_translate", Context.MODE_PRIVATE)
    private var src = prefs.getString("src", "zh-CN")!!
    private var dst = prefs.getString("dst", "en")!!

    private val buffer = StringBuilder()
    private var composing = ""
    private var lastInserted = ""
    private var job: Job? = null

    var active = false
        private set

    var onActiveChanged: ((Boolean) -> Unit)? = null

    private fun name(code: String) = langs.firstOrNull { it.code == code }?.name ?: code

    private val srcButton = TextView(ctx)
    private val dstButton = TextView(ctx)

    private fun styleLangButton(tv: TextView) = tv.apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(theme.genericActiveForegroundColor)
        background = pill(theme.genericActiveBackgroundColor, ctx.dpf(16f))
        setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0)
        GboardFont.get(ctx)?.let { typeface = it }
    }

    private val input = TextView(ctx).apply {
        textSize = 15f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.START
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        setHintTextColor(theme.candidateCommentColor)
        hint = "在这里输入要翻译的内容"
        background = GradientDrawable().apply {
            cornerRadius = ctx.dpf(20f)
            setColor(theme.keyBackgroundColor)
            setStroke(ctx.dpi(1), theme.genericActiveBackgroundColor)
        }
        setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0)
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(theme.keyTextColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(theme.barColor)
        setPadding(ctx.dpi(8), ctx.dpi(6), ctx.dpi(8), ctx.dpi(6))
        val langRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_baseline_arrow_back_24, "关闭翻译") { setActive(false) },
                LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(36)))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(styleLangButton(srcButton), LinearLayout.LayoutParams(-2, ctx.dpi(32)))
            addView(iconButton(R.drawable.ic_gb_swap, "交换语言") { swap() },
                LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(36)))
            addView(styleLangButton(dstButton), LinearLayout.LayoutParams(-2, ctx.dpi(32)))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(ctx.dpi(40), 1))
        }
        addView(langRow, LinearLayout.LayoutParams(-1, ctx.dpi(38)))
        addView(input, LinearLayout.LayoutParams(-1, ctx.dpi(42)).apply { topMargin = ctx.dpi(4) })
    }

    init {
        srcButton.setOnClickListener { src = next(src, allowSame = false); save(); retranslate() }
        dstButton.setOnClickListener { dst = next(dst, allowSame = false); save(); retranslate() }
        refreshLabels()
    }

    private fun next(cur: String, allowSame: Boolean): String {
        val i = langs.indexOfFirst { it.code == cur }
        var n = langs[(i + 1) % langs.size].code
        if (!allowSame && (n == src || n == dst)) n = langs[(langs.indexOfFirst { it.code == n } + 1) % langs.size].code
        return n
    }

    private fun swap() {
        val t = src; src = dst; dst = t; save(); retranslate()
    }

    private fun save() {
        prefs.edit().putString("src", src).putString("dst", dst).apply()
        refreshLabels()
    }

    private fun refreshLabels() {
        srcButton.text = name(src)
        dstButton.text = name(dst)
    }

    fun toggle() = setActive(!active)

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            buffer.clear(); composing = ""; lastInserted = ""
            service.textInterceptor = this
        } else {
            if (service.textInterceptor === this) service.textInterceptor = null
            job?.cancel()
        }
        root.visibility = if (on) View.VISIBLE else View.GONE
        updateInput()
        onActiveChanged?.invoke(on)
    }

    private fun updateInput() {
        input.text = buffer.toString() + composing
    }

    override fun onCommit(text: String) {
        buffer.append(text); composing = ""; updateInput(); retranslate()
    }

    override fun onComposing(text: String) {
        composing = text; updateInput()
    }

    override fun onBackspace(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        updateInput(); retranslate()
        return true
    }

    override fun onReturn(): Boolean {
        if (buffer.isEmpty()) return false
        // keep the translation in the editor, start a new sentence
        buffer.clear(); composing = ""; lastInserted = ""
        updateInput()
        return true
    }

    private fun retranslate() {
        job?.cancel()
        val q = buffer.toString()
        job = scope.launch {
            delay(350)
            val out = if (q.isBlank()) "" else withContext(Dispatchers.IO) { translate(q, src, dst) } ?: return@launch
            write(out)
        }
    }

    private fun write(out: String) {
        val ic = service.currentInputConnection ?: return
        ic.beginBatchEdit()
        if (lastInserted.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(lastInserted.length, 0)?.toString()
            if (before == lastInserted) ic.deleteSurroundingText(lastInserted.length, 0)
        }
        if (out.isNotEmpty()) ic.commitText(out, 1)
        ic.endBatchEdit()
        lastInserted = out
    }

    companion object {
        /** Google Translate public endpoint, the same service Gboard uses */
        fun translate(q: String, sl: String, tl: String): String? = runCatching {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                        "&sl=${URLEncoder.encode(sl, "UTF-8")}&tl=${URLEncoder.encode(tl, "UTF-8")}" +
                        "&q=${URLEncoder.encode(q, "UTF-8")}"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { r ->
                val arr = JSONArray(r.readText()).getJSONArray(0)
                buildString { for (i in 0 until arr.length()) append(arr.getJSONArray(i).optString(0)) }
            }
        }.onFailure { Timber.w(it, "translate failed") }.getOrNull()
    }
}

/* ------------------------------------------------------------------ */
/*  Inline voice typing                                                 */
/* ------------------------------------------------------------------ */

class VoiceBar(
    private val ctx: Context,
    private val theme: Theme,
    private val service: FcitxInputMethodService,
    private val languageProvider: () -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    var active = false
        private set

    private val status = TextView(ctx).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        setTextColor(theme.keyTextColor)
        GboardFont.get(ctx)?.let { typeface = it }
    }

    private val mic = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_gb_mic)
        imageTintList = ColorStateList.valueOf(theme.genericActiveForegroundColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.genericActiveBackgroundColor)
        }
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = ctx.dpi(6); setPadding(p, p, p, p)
        contentDescription = "停止语音输入"
        setOnClickListener { stop() }
    }

    private val back = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_baseline_arrow_back_24)
        imageTintList = ColorStateList.valueOf(theme.keyTextColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "关闭语音输入"
        setOnClickListener { stop() }
    }

    val root: View = FrameLayout(ctx).apply {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(theme.barColor)
        addView(back, FrameLayout.LayoutParams(ctx.dpi(48), -1, Gravity.START))
        addView(status, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = ctx.dpi(56); rightMargin = ctx.dpi(56)
        })
        addView(mic, FrameLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = ctx.dpi(7)
        })
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "正在听取…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + (rmsdB.coerceIn(0f, 10f)) / 40f
            mic.scaleX = s; mic.scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { mic.scaleX = 1f; mic.scaleY = 1f }
        override fun onError(error: Int) {
            listening = false
            service.currentInputConnection?.finishComposingText()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (active) handler.postDelayed({ if (active) listen() }, 200)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> status.text = "需要麦克风权限"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status.text = "网络不可用"
                else -> status.text = "语音识别暂不可用"
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val ic = service.currentInputConnection
            if (text.isNotEmpty()) ic?.commitText(text, 1) else ic?.finishComposingText()
            if (active) handler.postDelayed({ if (active) listen() }, 200)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                service.currentInputConnection?.setComposingText(text, 1)
                status.text = text
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start() {
        if (active) { stop(); return }
        active = true
        root.visibility = View.VISIBLE
        status.text = "正在听取…"
        listen()
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "需要麦克风权限"
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageProvider())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
        }
        listening = true
        r.startListening(intent)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        service.currentInputConnection?.finishComposingText()
        mic.scaleX = 1f; mic.scaleY = 1f
        root.visibility = View.GONE
    }
}
