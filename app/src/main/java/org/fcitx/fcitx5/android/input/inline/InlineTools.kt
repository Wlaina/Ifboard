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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.GboardFont
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
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
    var popupListener: PopupActionListener? = null

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
        // 直接透明复用 keyboardView 的模糊/纯色/自定义图片背景，避免与键盘背景产生透明度或色差
        setBackgroundColor(Color.TRANSPARENT)
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
        attachLangSlidePicker(srcButton, { src }) { picked ->
            if (picked == dst) swap() else { src = picked; save(); retranslate() }
        }
        attachLangSlidePicker(dstButton, { dst }) { picked ->
            if (picked == src) swap() else { dst = picked; save(); retranslate() }
        }
        refreshLabels()
    }

    /** 长按语言胶囊弹出竖向列表，上下滑动切换语言（复用按键长按气泡动画） */
    private fun attachLangSlidePicker(btn: TextView, currentCode: () -> String, onPicked: (String) -> Unit) {
        val viewId = View.generateViewId().also { btn.id = it }
        var menuShown = false
        var menuWasShown = false
        val triggerRect = Rect()
        val loc = intArrayOf(0, 0)
        btn.setOnLongClickListener { v ->
            val listener = popupListener ?: return@setOnLongClickListener false
            v.getLocationInWindow(loc)
            triggerRect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            menuShown = true
            menuWasShown = true
            v.parent?.requestDisallowInterceptTouchEvent(true)
            InputFeedbacks.hapticFeedback(v)
            val idx = langs.indexOfFirst { it.code == currentCode() }.coerceAtLeast(0)
            listener.onPopupAction(
                PopupAction.ShowChoiceAction(viewId, langs.map { it.name }, triggerRect, idx) { pickedIdx ->
                    langs.getOrNull(pickedIdx)?.code?.let(onPicked)
                }
            )
            true
        }
        btn.setOnTouchListener { v, event ->
            val listener = popupListener ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    menuShown = false
                    menuWasShown = false
                    false
                }
                MotionEvent.ACTION_MOVE -> if (menuShown) {
                    val dismissed = PopupAction.ChangeFocusAction(viewId, event.x, event.y)
                        .also { listener.onPopupAction(it) }.outResult
                    if (dismissed) menuShown = false
                    true
                } else false
                MotionEvent.ACTION_UP -> if (menuWasShown) {
                    if (menuShown) {
                        PopupAction.TriggerAction(viewId).also { listener.onPopupAction(it) }
                        listener.onPopupAction(PopupAction.DismissAction(viewId))
                    }
                    menuShown = false
                    menuWasShown = false
                    true
                } else false
                MotionEvent.ACTION_CANCEL -> if (menuWasShown) {
                    if (menuShown) listener.onPopupAction(PopupAction.DismissAction(viewId))
                    menuShown = false
                    menuWasShown = false
                    true
                } else false
                else -> false
            }
        }
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
            popupListener?.apply {
                onPopupAction(PopupAction.DismissAction(srcButton.id))
                onPopupAction(PopupAction.DismissAction(dstButton.id))
            }
        }
        animateExpand(on)
        updateInput()
        onActiveChanged?.invoke(on)
    }

    /** 仅动画属性合成（alpha/scaleY/translationY + GPU layer），避免逐帧改 height 引发 ConstraintLayout 全键盘重排卡顿 */
    private fun animateExpand(on: Boolean) {
        root.animate().cancel()
        if (on) {
            root.visibility = View.VISIBLE
            root.alpha = 0f
            OneShotPreDrawListener.add(root) {
                val h = root.height.toFloat().coerceAtLeast(1f)
                root.pivotX = root.width * 0.5f
                root.pivotY = h
                root.scaleY = 0.86f
                root.translationY = ctx.dpf(10f)
                root.animate()
                    .alpha(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(190)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .withLayer()
                    .start()
            }
        } else {
            if (root.visibility != View.VISIBLE) return
            val h = root.height.toFloat().coerceAtLeast(1f)
            root.pivotX = root.width * 0.5f
            root.pivotY = h
            root.animate()
                .alpha(0f)
                .scaleY(0.86f)
                .translationY(ctx.dpf(10f))
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withLayer()
                .withEndAction {
                    if (!active) {
                        root.visibility = View.GONE
                        root.alpha = 1f
                        root.scaleY = 1f
                        root.translationY = 0f
                    }
                }
                .start()
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
    private var ready = false
    private var sessionSeq = 0
    private var finalizedSeq = -1
    private var lastPartial = ""
    private var eosMs = 0L

    private val endFallbackRunnable = Runnable {
        if (!active || finalizedSeq == sessionSeq) return@Runnable
        val text = lastPartial
        if (text.isNotEmpty()) {
            finalizedSeq = sessionSeq
            listening = false
            lastPartial = ""
            service.currentInputConnection?.finishComposingText()
            Timber.d("IfboardVoice: fast-commit partial (+%dms): %s", System.currentTimeMillis() - eosMs, text)
            runCatching { recognizer?.cancel() }
            if (active) handler.postDelayed({ if (active && !listening) listen() }, 40)
        }
    }

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
        override fun onReadyForSpeech(params: Bundle?) {
            ready = true
            status.text = "正在听取…"
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // at most 38dp across, the bar is 40dp high
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * (MaxPulse - 1f)
            pulse.animate().scaleX(s).scaleY(s).setDuration(90).start()
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            eosMs = System.currentTimeMillis()
            pulse.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            if (lastPartial.isNotEmpty() && finalizedSeq != sessionSeq) {
                handler.removeCallbacks(endFallbackRunnable)
                handler.postDelayed(endFallbackRunnable, 120L)
            }
        }
        override fun onError(error: Int) {
            handler.removeCallbacks(endFallbackRunnable)
            listening = false
            val text = lastPartial
            if (text.isNotEmpty() && finalizedSeq != sessionSeq) {
                finalizedSeq = sessionSeq
                service.currentInputConnection?.finishComposingText()
            } else {
                service.currentInputConnection?.finishComposingText()
            }
            lastPartial = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    status.text = "正在听取…"
                    if (active) handler.postDelayed({ if (active) listen() }, 40)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> status.text = "需要麦克风权限"
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    // the recognition service could not be reached (e.g. hidden by the system)
                    recognizer?.destroy(); recognizer = null
                    showUnavailable()
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status.text = "网络不可用"
                else -> status.text = "语音识别暂不可用"
            }
        }
        override fun onResults(results: Bundle?) {
            handler.removeCallbacks(endFallbackRunnable)
            listening = false
            if (finalizedSeq == sessionSeq) {
                lastPartial = ""
                if (active && !listening) handler.postDelayed({ if (active && !listening) listen() }, 40)
                return
            }
            val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val text = finalText.ifEmpty { lastPartial }
            finalizedSeq = sessionSeq
            lastPartial = ""
            val ic = service.currentInputConnection
            if (text.isNotEmpty()) ic?.commitText(text, 1) else ic?.finishComposingText()
            Timber.d("IfboardVoice: onResults (+%dms after eos): %s", if (eosMs > 0) System.currentTimeMillis() - eosMs else 0, text)
            if (active) handler.postDelayed({ if (active && !listening) listen() }, 40)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val stable = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val unstable = partialResults?.getString("android.speech.extra.UNSTABLE_TEXT").orEmpty()
            val text = stable + unstable
            if (text.isNotEmpty()) {
                lastPartial = text
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
        status.text = "请稍候…"
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
        val r = recognizer ?: createRecognizer()?.also {
            it.setRecognitionListener(listener); recognizer = it
        } ?: run {
            showUnavailable()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageProvider())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 240L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 120L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
        }
        status.text = "正在听取…"
        sessionSeq++
        lastPartial = ""
        eosMs = 0L
        listening = true
        ready = false
        r.startListening(intent)
        // a recognizer that cannot be reached (e.g. hidden by the system) never calls back
        handler.postDelayed({
            if (active && listening && !ready) {
                recognizer?.destroy(); recognizer = null
                showUnavailable()
            }
        }, 3500)
    }

    /** system recognizer, else the on-device one, else Google's service addressed directly */
    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        when {
            SpeechRecognizer.isRecognitionAvailable(service) -> SpeechRecognizer.createSpeechRecognizer(service)
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(service) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
            googleRecognizerInstalled() -> SpeechRecognizer.createSpeechRecognizer(
                service, android.content.ComponentName(GooglePackage, GoogleRecognizer)
            )
            else -> null
        }
    }.getOrNull()

    private fun googleRecognizerInstalled() = runCatching {
        service.packageManager.getPackageInfo(GooglePackage, 0); true
    }.getOrDefault(false)

    private fun showUnavailable() {
        listening = false
        status.text = "这台手机没有可用的语音识别服务"
        handler.postDelayed({ if (active) stop() }, 2500)
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
        private const val GooglePackage = "com.google.android.googlequicksearchbox"
        private const val GoogleRecognizer = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        private const val MaxPulse = 38f / MicSize
    }
}
