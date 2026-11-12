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
        animateExpand(on)
        updateInput()
        onActiveChanged?.invoke(on)
    }

    private var heightAnimator: android.animation.ValueAnimator? = null

    /** grow / shrink the panel height together with a fade, so the keyboard below moves smoothly both ways */
    private fun animateExpand(on: Boolean) {
        heightAnimator?.cancel()
        root.animate().cancel()
        val lp = root.layoutParams
        val parentWidth = (root.parent as? View)?.width ?: 0
        if (lp == null || parentWidth == 0) {
            root.visibility = if (on) View.VISIBLE else View.GONE
            root.alpha = 1f
            return
        }
        val wasVisible = root.visibility == View.VISIBLE
        val from = if (wasVisible) root.height else 0
        val a0 = if (wasVisible) root.alpha else 0f
        val to = if (on) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.measuredHeight
        } else 0
        root.visibility = View.VISIBLE
        lp.height = from
        root.layoutParams = lp
        heightAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) 240 else 200
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.height = (from + (to - from) * t).toInt()
                root.layoutParams = lp
                root.alpha = if (on) a0 + (1f - a0) * t else a0 * (1f - t)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    root.layoutParams = lp
                    root.alpha = 1f
                    if (!active) root.visibility = View.GONE
                }
            })
            start()
        }
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

    /** Ifboard: lets the input view hide the toolbar underneath while listening */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    private val status = TextView(ctx).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        setTextColor(theme.keyTextColor)
        GboardFont.get(ctx)?.let { typeface = it }
    }

    // Gboard style: a soft ring behind the mic follows the voice level (stays inside the bar)
    private val pulse = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.genericActiveBackgroundColor)
        }
        alpha = 0.3f
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
        // transparent: the toolbar underneath is hidden while listening, so this shows exactly the keyboard background
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(back, FrameLayout.LayoutParams(ctx.dpi(48), -1, Gravity.START))
        addView(status, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = ctx.dpi(56); rightMargin = ctx.dpi(56)
        })
        addView(pulse, FrameLayout.LayoutParams(ctx.dpi(MicSize), ctx.dpi(MicSize), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = ctx.dpi(9)
        })
        addView(mic, FrameLayout.LayoutParams(ctx.dpi(MicSize), ctx.dpi(MicSize), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = ctx.dpi(9)
        })
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "正在听取…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // at most 38dp across, the bar is 40dp high
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * (MaxPulse - 1f)
            pulse.animate().scaleX(s).scaleY(s).setDuration(90).start()
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { pulse.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }
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
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        mic.scaleX = 0.5f; mic.scaleY = 0.5f
        mic.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        status.text = "正在听取…"
        onActiveChanged?.invoke(true)
        listen()
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // a fresh install has no microphone permission yet: ask for it like Gboard does
            status.text = "需要麦克风权限，请在弹出的窗口中允许"
            runCatching { org.fcitx.fcitx5.android.ui.main.MicPermissionActivity.launch(service) }
            handler.postDelayed({ stop() }, 1500)
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
        pulse.animate().cancel()
        pulse.scaleX = 1f; pulse.scaleY = 1f
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(140).withEndAction { if (!active) root.visibility = View.GONE }.start()
        onActiveChanged?.invoke(false)
    }

    companion object {
        private const val MicSize = 30
        private const val MaxPulse = 38f / MicSize
    }
}
