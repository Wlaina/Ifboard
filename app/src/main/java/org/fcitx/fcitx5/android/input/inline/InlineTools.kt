/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: Gboard style inline translation and inline voice typing.
 * Both live inside the keyboard area, the keys stay usable.
 */
package org.fcitx.fcitx5.android.input.inline

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
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
    /** 编辑框光标 / composing 区域变化（拦截期间由拦截者自行处理，不交给 fcitx 重置） */
    fun onEditorSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {}
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
    /** 当前以 composing（下划线）形式放在编辑框里的译文，Gboard 同款：回车 / 关闭翻译时才定稿 */
    private var lastInserted = ""
    /** lastInserted 对应的原文，用于判断译文是否已是最新 */
    private var translatedFor = ""
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
            buffer.clear(); composing = ""; lastInserted = ""; translatedFor = ""
            service.textInterceptor = this
        } else {
            if (service.textInterceptor === this) service.textInterceptor = null
            job?.cancel()
            // 关闭翻译时保留已经出现在编辑框里的译文
            commitOutput()
            buffer.clear(); composing = ""
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

    /**
     * Gboard 方式：翻译框有原文时退格删原文（编辑框里的译文随之更新，原文删空时译文一并移除）；
     * 翻译框为空时退格交给编辑框，正常删除已定稿的内容。
     */
    override fun onBackspace(): Boolean {
        if (buffer.isEmpty()) {
            // 原文已空但编辑框里还留着未定稿译文（例如网络返回晚了）：先把它删掉
            if (lastInserted.isNotEmpty()) {
                job?.cancel()
                write("")
                return true
            }
            return false
        }
        buffer.deleteCharAt(buffer.length - 1)
        updateInput(); retranslate()
        return true
    }

    override fun onReturn(): Boolean {
        if (buffer.isEmpty()) return false
        // 定稿当前译文，开始新的一句；若译文还没回来，等它回来再定稿
        val q = buffer.toString()
        buffer.clear(); composing = ""
        updateInput()
        job?.cancel()
        if (q == translatedFor) {
            commitOutput()
        } else {
            job = scope.launch {
                val out = withContext(Dispatchers.IO) { translate(q, src, dst) }
                if (out != null) write(out, q)
                commitOutput()
            }
        }
        return true
    }

    override fun onEditorSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
        // 应用或用户把 composing 结束了（点了别处、应用自行定稿）：视为译文已定稿，下一句重新开始
        if (lastInserted.isNotEmpty() && composingStart < 0 && composingEnd < 0) {
            job?.cancel()
            lastInserted = ""; translatedFor = ""
            buffer.clear(); composing = ""
            updateInput()
        }
    }

    private fun retranslate() {
        job?.cancel()
        val q = buffer.toString()
        if (q.isBlank()) {
            // 原文删空：立即移除编辑框里的译文，不等防抖
            write("")
            return
        }
        job = scope.launch {
            delay(350)
            val out = withContext(Dispatchers.IO) { translate(q, src, dst) } ?: return@launch
            write(out, q)
        }
    }

    private fun write(out: String, source: String = "") {
        val ic = service.currentInputConnection ?: return
        if (out.isEmpty() && lastInserted.isEmpty()) return
        // 用 composing 区域承载译文，替换 / 删除不再依赖 getTextBeforeCursor 比对（微信等编辑框常比对失败）
        ic.setComposingText(out, 1)
        lastInserted = out
        translatedFor = if (out.isEmpty()) "" else source
    }

    private fun commitOutput() {
        if (lastInserted.isNotEmpty()) service.currentInputConnection?.finishComposingText()
        lastInserted = ""; translatedFor = ""
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
    /** 回收查询 / 下载用的临时识别器；stop() 不清它，避免临时识别器泄漏 */
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var ready = false
    private var triedExplicitFallback = false
    private var sessionSeq = 0
    private var finalizedSeq = -1
    private var lastPartial = ""
    private var eosMs = 0L
    private var currentLang = ""
    /** 本次听写走本机离线识别（sherpa-onnx），不经系统识别服务和网络 */
    private var offlineMode = false

    private val offlineListener = object : org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.Listener {
        override fun onReady() {
            ready = true
            segPrefix = null
            status.text = "正在听取…（离线）"
            Timber.i("IfboardVoiceT offline ready +%dms", System.currentTimeMillis() - sessionStartMs)
        }

        /** 本句前要补的分隔（空格或逗号），在这一句开始出字时根据光标前的文字定下来 */
        private var segPrefix: String? = null

        override fun onPartial(text: String) {
            if (!active) return
            lastPartial = text
            if (text.isEmpty()) return
            val ic = service.currentInputConnection
            val prefix = segPrefix ?: org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr
                .separator(ic?.getTextBeforeCursor(1, 0), text).also { segPrefix = it }
            ic?.setComposingText(prefix + text, 1)
            status.text = text
        }

        override fun onFinal(text: String) {
            if (!active) return
            lastPartial = ""
            val ic = service.currentInputConnection
            val prefix = segPrefix ?: org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr
                .separator(ic?.getTextBeforeCursor(1, 0), text)
            segPrefix = null
            ic?.commitText(prefix + text, 1)
            status.text = "正在听取…（离线）"
        }

        override fun onLevel(level: Float) = pulseTo(level)

        override fun onError(message: String) {
            if (!active) return
            Timber.w("IfboardVoice offline: %s", message)
            org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.stop()
            offlineMode = false
            listening = false
            service.currentInputConnection?.finishComposingText()
            lastPartial = ""
            if (message.contains("权限")) {
                status.text = message
                return
            }
            // 离线识别不可用（模型损坏、麦克风被占用等）：改用系统识别服务
            status.text = "离线语音不可用，改用在线识别"
            handler.postDelayed({ if (active && !listening) listen() }, 300)
        }
    }

    /** 离线识别只支持中文、英文 */
    private fun offlineFits(lang: String) =
        org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.usable(service) &&
            (lang.startsWith("zh") || lang.startsWith("en") || lang.startsWith("cmn") || lang.startsWith("yue"))

    private fun pulseTo(rmsdB: Float) {
        // at most 38dp across, the bar is 40dp high
        val s = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * (MaxPulse - 1f)
        // 每秒回调 ~10~20 次：变化不明显时不再新建动画，省掉无效的重绘
        if (kotlin.math.abs(s - lastPulseScale) < 0.04f) return
        lastPulseScale = s
        pulse.animate().scaleX(s).scaleY(s).setDuration(90).start()
    }

    /** 识别服务不支持分段会话（立即报错）时回退到逐句模式 */
    private var segmentedUnsupported = false
    private var sessionStartMs = 0L
    private var segmentedSession = false
    private var lastPulseScale = 1f
    /** 当前分段内是否收到过实时部分结果 */
    private var partialSeenInSegment = false
    // 仅记录耗时（毫秒），不记录识别内容
    private var speechBeginMs = 0L
    private var firstPartialLogged = false
    private var lastPartialMs = 0L

    private val endFallbackRunnable = Runnable {
        if (!active || finalizedSeq == sessionSeq) return@Runnable
        val text = lastPartial
        if (text.isNotEmpty()) {
            finalizedSeq = sessionSeq
            listening = false
            lastPartial = ""
            service.currentInputConnection?.finishComposingText()
            Timber.i("IfboardVoiceT fast-commit +%dms after end of speech", System.currentTimeMillis() - eosMs)
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
            Timber.i("IfboardVoiceT ready +%dms offline=%s segmented=%s", System.currentTimeMillis() - sessionStartMs, preferOffline, segmentedSession)
        }
        override fun onBeginningOfSpeech() {
            speechBeginMs = System.currentTimeMillis()
            firstPartialLogged = false
        }
        override fun onRmsChanged(rmsdB: Float) = pulseTo(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            eosMs = System.currentTimeMillis()
            pulse.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            // 分段会话由 onSegmentResults 负责上屏，不能提前 cancel 掉整段会话
            if (!segmentedSession && lastPartial.isNotEmpty() && finalizedSeq != sessionSeq) {
                handler.removeCallbacks(endFallbackRunnable)
                handler.postDelayed(endFallbackRunnable, 120L)
            }
        }
        override fun onError(error: Int) {
            handler.removeCallbacks(endFallbackRunnable)
            listening = false
            if (!ready && !segmentedUnsupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) &&
                System.currentTimeMillis() - sessionStartMs < 1500
            ) {
                segmentedUnsupported = true
                Timber.w("IfboardVoice: segmented session rejected (%d), fall back to per-utterance mode", error)
                if (active) handler.postDelayed({ if (active && !listening) listen() }, 40)
                return
            }
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
                    // the recognition service could not be reached (e.g. voice_recognition_service unset on OriginOS)
                    recognizer?.destroy(); recognizer = null
                    if (!triedExplicitFallback && active) {
                        triedExplicitFallback = true
                        listen(forceExplicitComponent = true)
                    } else {
                        showUnavailable()
                    }
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
            Timber.i("IfboardVoiceT final +%dms after end of speech", if (eosMs > 0) System.currentTimeMillis() - eosMs else -1)
            if (active) handler.postDelayed({ if (active && !listening) listen() }, 40)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val stable = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val unstable = partialResults?.getString("android.speech.extra.UNSTABLE_TEXT").orEmpty()
            val text = stable + unstable
            if (text.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (!firstPartialLogged && speechBeginMs > 0) {
                    firstPartialLogged = true
                    Timber.i("IfboardVoiceT first-partial +%dms after speech begin", now - speechBeginMs)
                } else if (lastPartialMs > 0 && now - lastPartialMs > 700) {
                    Timber.i("IfboardVoiceT partial gap %dms", now - lastPartialMs)
                }
                lastPartialMs = now
                partialSeenInSegment = true
                lastPartial = text
                service.currentInputConnection?.setComposingText(text, 1)
                status.text = text
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // Android 13+ 连续听写：一次会话内按停顿分段输出，不再每句话销毁重启识别器（Gboard 同款思路）
        override fun onSegmentResults(segmentResults: Bundle) {
            handler.removeCallbacks(endFallbackRunnable)
            val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().ifEmpty { lastPartial }
            if (text.isNotEmpty() && !partialSeenInSegment) {
                // 识别服务在分段模式下不推送实时文字时，出字反而要等整段说完：之后改回逐句模式（有实时文字）
                segmentedUnsupported = true
                Timber.w("IfboardVoice: no partial results in segmented session, fall back to per-utterance mode")
                runCatching { recognizer?.cancel() }
                listening = false
                handler.postDelayed({ if (active && !listening) listen() }, 40)
            }
            Timber.i("IfboardVoiceT segment +%dms after last partial", if (lastPartialMs > 0) System.currentTimeMillis() - lastPartialMs else -1)
            partialSeenInSegment = false
            firstPartialLogged = false
            lastPartial = ""
            val ic = service.currentInputConnection
            if (text.isNotEmpty()) ic?.commitText(text, 1) else ic?.finishComposingText()
            status.text = "正在听取…"
        }

        override fun onEndOfSegmentedSession() {
            handler.removeCallbacks(endFallbackRunnable)
            listening = false
            finalizedSeq = sessionSeq
            if (lastPartial.isNotEmpty()) service.currentInputConnection?.finishComposingText()
            lastPartial = ""
            if (active) handler.postDelayed({ if (active && !listening) listen() }, 40)
        }
    }

    /**
     * 键盘语言（输入法）切换后，用新语言重新开始识别；已识别出的内容先上屏，避免切换后无法输出。
     */
    fun onLanguageChanged() {
        if (!active) return
        val lang = languageProvider()
        if (lang == currentLang) return
        // 离线模型本身中英混合，中英之间切换不用重开
        if (offlineMode && offlineFits(lang)) {
            currentLang = lang
            return
        }
        handler.removeCallbacks(endFallbackRunnable)
        if (lastPartial.isNotEmpty()) service.currentInputConnection?.commitText(lastPartial, 1)
        else service.currentInputConnection?.finishComposingText()
        lastPartial = ""
        finalizedSeq = sessionSeq
        runCatching { recognizer?.cancel() }
        listening = false
        handler.postDelayed({ if (active && !listening) listen() }, 60)
    }

    fun start() {
        if (active) { stop(); return }
        active = true
        triedExplicitFallback = false
        status.setOnClickListener(null)
        status.isClickable = false
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        mic.scaleX = 0.5f; mic.scaleY = 0.5f
        mic.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        status.text = "请稍候…"
        onActiveChanged?.invoke(true)
        offlineMode = offlineFits(languageProvider())
        if (!offlineMode) probeOfflineSupport()
        listen()
    }

    private var preferOffline = false

    /**
     * Android 13+：查询识别服务已安装的离线语言；当前语言支持离线但还没下载时，请求后台下载一次。
     * 用独立的识别器查询，避免与正在进行的听写互相占用。
     */
    private fun probeOfflineSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val lang = languageProvider()
        if (offlineLangs != null && lang in probedLangs) return
        probedLangs += lang
        val probe = createSpeechRecognizer(service) ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        runCatching {
            probe.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(service),
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.toSet()
                        offlineLangs = installed
                        val supported = support.supportedOnDeviceLanguages
                        val pending = support.pendingOnDeviceLanguages
                        Timber.i(
                            "IfboardVoiceT support lang=%s installed=%s pending=%s supportedHasLang=%s",
                            lang, installed, pending, supported.any { langMatches(it, lang) }
                        )
                        if (!installed.any { langMatches(it, lang) } && !pending.any { langMatches(it, lang) } &&
                            supported.any { langMatches(it, lang) } && lang !in downloadRequested
                        ) {
                            downloadRequested += lang
                            // 用新的识别器发下载请求（查询用的识别器可能已因回调报错被回收）
                            createSpeechRecognizer(service)?.let { dl ->
                                val ok = runCatching {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        dl.triggerModelDownload(intent, ContextCompat.getMainExecutor(service),
                                            object : android.speech.ModelDownloadListener {
                                                private var lastLogged = -10
                                                override fun onProgress(completedPercent: Int) {
                                                    if (completedPercent - lastLogged >= 10) {
                                                        lastLogged = completedPercent
                                                        Timber.i("IfboardVoiceT model %s download %d%%", lang, completedPercent)
                                                    }
                                                }
                                                override fun onSuccess() {
                                                    Timber.i("IfboardVoiceT model %s downloaded", lang)
                                                    // 下载完成后下次听写即走离线
                                                    offlineLangs = (offlineLangs ?: emptySet()) + lang
                                                }
                                                override fun onScheduled() {
                                                    Timber.i("IfboardVoiceT model %s download scheduled", lang)
                                                }
                                                override fun onError(error: Int) {
                                                    Timber.i("IfboardVoiceT model %s download error %d", lang, error)
                                                    downloadRequested -= lang
                                                }
                                            })
                                    } else {
                                        dl.triggerModelDownload(intent)
                                    }
                                }.isSuccess
                                Timber.i("IfboardVoiceT requested offline model download for %s ok=%s", lang, ok)
                                cleanupHandler.postDelayed({ runCatching { dl.destroy() } }, 10 * 60_000L)
                            }
                        }
                        cleanupHandler.postDelayed({ runCatching { probe.destroy() } }, 3000)
                        // 首次会话已按在线方式开始：还没出字就立即换成离线识别
                        if (active && listening && !preferOffline && lastPartial.isEmpty() && isOfflineInstalled(currentLang)) {
                            runCatching { recognizer?.cancel() }
                            listening = false
                            handler.postDelayed({ if (active && !listening) listen() }, 40)
                        }
                    }

                    override fun onError(error: Int) {
                        Timber.i("IfboardVoiceT support check error %d", error)
                        // 部分服务先报错再给结果，延后回收
                        cleanupHandler.postDelayed({ runCatching { probe.destroy() } }, 3000)
                    }
                })
        }.onFailure { runCatching { probe.destroy() } }
    }

    private fun isOfflineInstalled(lang: String) = offlineLangs?.any { langMatches(it, lang) } == true

    private fun listen(forceExplicitComponent: Boolean = false) {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // a fresh install has no microphone permission yet: ask for it like Gboard does
            status.text = "需要麦克风权限，请在弹出的窗口中允许"
            runCatching { org.fcitx.fcitx5.android.ui.main.MicPermissionActivity.launch(service) }
            handler.postDelayed({ stop() }, 1500)
            return
        }
        if (offlineMode) {
            currentLang = languageProvider()
            status.text = "正在加载离线语音…"
            ready = false
            listening = true
            lastPartial = ""
            sessionStartMs = System.currentTimeMillis()
            org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.start(service, offlineListener)
            return
        }
        val r = recognizer ?: createSpeechRecognizer(service, forceExplicitComponent)?.also {
            it.setRecognitionListener(listener); recognizer = it
        } ?: run {
            showUnavailable()
            return
        }
        val lang = languageProvider()
        currentLang = lang
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
            // 本机已装离线模型时直接走离线识别：不经网络往返，实时文字明显更快（Gboard 默认也是离线优先）
            preferOffline = isOfflineInstalled(lang)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // 实测（小米 14 / Google 语音服务，中英文）：分段会话模式说话期间经常完全不推送实时文字，
            // 整句说完才一次性出字，体感很慢；逐句模式能边说边出字，所以默认使用逐句模式 + 快速续听
            segmentedSession = UseSegmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !segmentedUnsupported
            if (segmentedSession) {
                // 分段会话：停顿只产生分段结果，整段会话持续收音，省掉每句话重启识别器的 0.5~1 秒空窗
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SegmentedSessionMillis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            } else {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 240L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 120L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
            }
        }
        status.setOnClickListener(null)
        status.isClickable = false
        status.text = "正在听取…"
        val seq = ++sessionSeq
        lastPartial = ""
        eosMs = 0L
        listening = true
        ready = false
        sessionStartMs = System.currentTimeMillis()
        partialSeenInSegment = false
        lastPartialMs = 0L
        speechBeginMs = 0L
        firstPartialLogged = false
        r.startListening(intent)
        // a recognizer that cannot be reached (e.g. hidden or unconfigured by the ROM) never calls back
        handler.postDelayed({
            if (active && listening && !ready && sessionSeq == seq) {
                recognizer?.destroy(); recognizer = null
                if (!triedExplicitFallback) {
                    triedExplicitFallback = true
                    listen(forceExplicitComponent = true)
                } else {
                    showUnavailable()
                }
            }
        }, 3500)
    }

    private fun showUnavailable() {
        listening = false
        if (resolveRecognitionService(service) == null) {
            status.text = "未安装语音识别服务（点此安装 Google 语音服务）"
            status.setOnClickListener { openSpeechServiceStore() }
            handler.postDelayed({ if (active) stop() }, 6000)
        } else {
            status.setOnClickListener(null)
            status.isClickable = false
            status.text = "语音识别服务暂不可用"
            handler.postDelayed({ if (active) stop() }, 3000)
        }
    }

    private fun openSpeechServiceStore() {
        val uris = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GoogleTtsPackage"))
                .setPackage("com.android.vending"),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GoogleTtsPackage")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GoogleTtsPackage"))
        )
        for (i in uris) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { service.startActivity(i); true }.getOrDefault(false)) {
                stop()
                return
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        status.setOnClickListener(null)
        status.isClickable = false
        if (offlineMode) {
            org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.stop()
            offlineMode = false
        }
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
        lastPartial = ""
        service.currentInputConnection?.finishComposingText()
        pulse.animate().cancel()
        pulse.scaleX = 1f; pulse.scaleY = 1f
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(140).withEndAction { if (!active) root.visibility = View.GONE }.start()
        onActiveChanged?.invoke(false)
    }

    companion object {
        private const val MicSize = 30
        private const val UseSegmentedSession = false
        /** 分段会话最长持续时间，到时自动续开下一段 */
        // Google 语音服务按 Integer 读取该值（传 Long 会被当成 0 并忽略分段会话）
        private const val SegmentedSessionMillis: Int = 5 * 60_000
        private const val GoogleTtsPackage = "com.google.android.tts"
        private const val GoogleTtsRecognizer =
            "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
        private const val GooglePackage = "com.google.android.googlequicksearchbox"
        private const val GoogleRecognizer = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        private const val MaxPulse = 38f / MicSize

        /** 进程内缓存：识别服务已安装的离线语言 */
        private var offlineLangs: Set<String>? = null
        private val probedLangs = mutableSetOf<String>()
        private val downloadRequested = mutableSetOf<String>()

        /** zh-CN / cmn-Hans-CN / zh 之类写法互相匹配 */
        private fun langMatches(a: String, b: String): Boolean {
            fun norm(x: String) = x.lowercase().replace('_', '-')
                .replace("cmn-hans", "zh").replace("cmn", "zh").replace("-hans", "")
            val x = norm(a); val y = norm(b)
            if (x == y) return true
            val xl = x.substringBefore('-'); val yl = y.substringBefore('-')
            if (xl != yl) return false
            // 只给了语言没给地区时视为匹配；都带地区则要求地区一致
            val xr = x.substringAfter('-', ""); val yr = y.substringAfter('-', "")
            return xr.isEmpty() || yr.isEmpty() || xr == yr
        }

        fun isAvailable(ctx: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(ctx) ||
                resolveRecognitionService(ctx) != null ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

        fun resolveRecognitionService(ctx: Context): ComponentName? {
            val pm = ctx.packageManager
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                ?.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
                ?.takeIf { isServiceEnabled(pm, it) }
                ?.let { return it }

            val candidates = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { ri ->
                    val si = ri.serviceInfo ?: return@mapNotNull null
                    ComponentName(si.packageName, si.name).takeIf { isServiceEnabled(pm, it) }
                }
            if (candidates.isNotEmpty()) {
                return candidates.minBy {
                    when (it.packageName) {
                        GoogleTtsPackage -> 0
                        GooglePackage -> 1
                        else -> 2
                    }
                }
            }

            val known = listOf(
                ComponentName(GoogleTtsPackage, GoogleTtsRecognizer),
                ComponentName(GooglePackage, GoogleRecognizer)
            )
            return known.firstOrNull { isServiceEnabled(pm, it) }
        }

        fun createSpeechRecognizer(ctx: Context, forceExplicitComponent: Boolean = false): SpeechRecognizer? =
            runCatching {
                val pm = ctx.packageManager
                val configured = Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ComponentName::unflattenFromString)
                    ?.takeIf { isServiceEnabled(pm, it) }
                when {
                    !forceExplicitComponent && configured != null && SpeechRecognizer.isRecognitionAvailable(ctx) ->
                        SpeechRecognizer.createSpeechRecognizer(ctx)
                    else -> resolveRecognitionService(ctx)?.let {
                        SpeechRecognizer.createSpeechRecognizer(ctx, it)
                    } ?: when {
                        !forceExplicitComponent && SpeechRecognizer.isRecognitionAvailable(ctx) ->
                            SpeechRecognizer.createSpeechRecognizer(ctx)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) ->
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                        else -> null
                    }
                }
            }.getOrNull()

        private fun isServiceEnabled(pm: PackageManager, cn: ComponentName): Boolean = runCatching {
            val si = pm.getServiceInfo(cn, 0)
            val svcEnabled = when (pm.getComponentEnabledSetting(cn)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> si.enabled
            }
            val appEnabled = si.applicationInfo?.enabled != false
            svcEnabled && appEnabled
        }.getOrDefault(false)
    }
}
