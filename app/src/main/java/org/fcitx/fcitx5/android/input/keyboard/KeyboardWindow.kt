/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val preedit: PreeditComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = androidx.transition.Fade().apply {
        duration = 120
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching to the symbol picker
            !(nextWindow is PickerWindow && nextWindow.key == PickerWindow.Key.Symbol)
        }

    override fun animateEnter(lastWindow: InputWindow) =
        !(lastWindow is PickerWindow && lastWindow.key == PickerWindow.Key.Symbol)

    private lateinit var keyboardView: FrameLayout

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme),
            GboardSymbolKeyboard.Page1Name to GboardSymbolKeyboard(context, theme, 1),
            GboardSymbolKeyboard.Page2Name to GboardSymbolKeyboard(context, theme, 2)
        )
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val englishAssist by lazy { EnglishAssist(service) }

    private fun updateAutoCaps() {
        (currentKeyboard as? TextKeyboard)?.setAutoCaps(englishAssist.shouldAutoCap())
    }

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.GlidePreviewAction -> {
                preedit.showGlidePreview(it.code)
                bar.showGlideCandidates(it.candidates)
                return@KeyActionListener
            }
            is KeyAction.GlidePreviewClearAction -> {
                preedit.showGlidePreview("")
                bar.clearGlideCandidates()
                return@KeyActionListener
            }
            else -> {}
        }
        // a new key after an English glide: its replacement suggestions are gone
        if (glideAltWord != null) {
            glideAltWord = null
            bar.clearGlideCandidates()
        }
        if (englishAssist.beforeKeyAction(it)) return@KeyActionListener
        if (it is KeyAction.GlideWordAction) {
            // Gboard: a glided word gets a space in front when it follows another word
            val before = service.currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
            val space = before != null && (before.isLetterOrDigit() || before in ".,!?;:")
            commonKeyActionListener.listener.onKeyAction(
                KeyAction.CommitAction(if (space) " ${it.word}" else it.word), source
            )
            if (it.alternates.isNotEmpty()) {
                glideAltWord = it.word
                bar.showGlideAlternates(it.word, it.alternates) { alt -> replaceGlidedWord(alt) }
            }
            return@KeyActionListener
        }
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    // the last English glided word while its alternates are shown in the bar
    private var glideAltWord: String? = null

    private fun replaceGlidedWord(alt: String) {
        val old = glideAltWord ?: return
        glideAltWord = null
        bar.clearGlideCandidates()
        val ic = service.currentInputConnection ?: return
        // only replace when the cursor is still right after the glided word
        if (ic.getTextBeforeCursor(old.length, 0)?.toString() != old) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(old.length, 0)
        ic.commitText(alt, 1)
        ic.endBatchEdit()
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = SlideFrame(context).apply { id = R.id.keyboard_view }
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            if (keyboards.containsKey(target)) {
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
        if (glideAltWord != null) {
            glideAltWord = null
            bar.clearGlideCandidates()
        }
        ContextCompat.getMainExecutor(service).execute { updateAutoCaps() }
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        updateAutoCaps()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        englishAssist.onImeUpdate(ime)
        currentKeyboard?.onInputMethodUpdate(ime)
        updateAutoCaps()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
        updateAutoCaps()
    }

    override fun onDetached() {
        preedit.showGlidePreview("")
        glideAltWord = null
        bar.clearGlideCandidates()
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    /**
     * Gboard style slide input: press "?123", slide onto a symbol and release to type it,
     * then go back to letters. A plain tap on "?123" just opens the symbol keyboard.
     */
    private inner class SlideFrame(ctx: android.content.Context) : FrameLayout(ctx) {
        private var sliding = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var previewing: KeyView? = null
        private val threshold = ctx.resources.displayMetrics.density * 12
        /** 按住「?123」超过这个时长再松手（没滑动、没用另一只手）也自动回到字母键盘 */
        private val HoldReturnMillis = 450L

        // Gboard: a thick rounded line follows the finger from where "?123" was pressed
        private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = ctx.resources.displayMetrics.density * 5f
            color = theme.genericActiveBackgroundColor or 0xFF000000.toInt()
        }
        private var lineAlpha = 0f
        private var lineFade: android.animation.ValueAnimator? = null
        private val loc = IntArray(2)
        private var lineX0 = 0f
        private var lineY0 = 0f
        private var lineX1 = 0f
        private var lineY1 = 0f

        init {
            setWillNotDraw(false)
        }

        private fun lineTo(rawX: Float, rawY: Float) {
            getLocationOnScreen(loc)
            lineX1 = rawX - loc[0]
            lineY1 = rawY - loc[1]
            lineFade?.cancel()
            lineAlpha = 1f
            invalidate()
        }

        private fun fadeLine() {
            if (lineAlpha <= 0f) return
            lineFade?.cancel()
            lineFade = android.animation.ValueAnimator.ofFloat(lineAlpha, 0f).apply {
                duration = 140
                addUpdateListener { lineAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            if (lineAlpha <= 0f) return
            linePaint.alpha = (235 * lineAlpha).toInt()
            canvas.drawLine(lineX0, lineY0, lineX1, lineY1, linePaint)
        }

        private fun setPreview(kb: BaseKeyboard?, v: KeyView?) {
            if (v === previewing) return
            previewing?.let { kb?.dismissSlidePreview(it); it.isPressed = false }
            previewing = v
            v?.let { it.isPressed = true; kb?.showSlidePreview(it) }
        }

        // 按住「?123」时另一根手指点击符号键盘：单独转发给键盘的那根手指
        private var slidePointerId = -1
        private var chordPointerId = -1
        private var chordDownTime = 0L
        private var chorded = false
        private var slideDownTime = 0L

        private fun rawXOf(ev: android.view.MotionEvent, i: Int): Float {
            getLocationOnScreen(loc); return ev.getX(i) + loc[0]
        }

        private fun rawYOf(ev: android.view.MotionEvent, i: Int): Float {
            getLocationOnScreen(loc); return ev.getY(i) + loc[1]
        }

        /** 用第二根手指的坐标合成一条单指事件交给键盘（屏幕坐标保留在 raw 里，键盘按 rawX/rawY 命中按键） */
        private fun forwardChord(ev: android.view.MotionEvent, index: Int, action: Int) {
            getLocationOnScreen(loc)
            val synth = android.view.MotionEvent.obtain(
                chordDownTime, ev.eventTime, action,
                ev.getX(index) + loc[0], ev.getY(index) + loc[1], ev.metaState
            )
            synth.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
            super.dispatchTouchEvent(synth)
            synth.recycle()
        }

        private fun endChordIfAny(ev: android.view.MotionEvent, commit: Boolean) {
            if (chordPointerId < 0) return
            val i = ev.findPointerIndex(chordPointerId)
            if (i >= 0) forwardChord(ev, i, if (commit) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_CANCEL)
            chordPointerId = -1
        }

        private fun finishSlide(ev: android.view.MotionEvent, released: Boolean) {
            sliding = false
            fadeLine()
            val kb = currentKeyboard
            setPreview(kb, null)
            val i = ev.findPointerIndex(slidePointerId).coerceAtLeast(0)
            if (moved) {
                // 滑动选符号：松手处是符号键就上屏；无论是否命中（包括滑出键盘范围、被系统取消），都回到字母键盘
                if (released) {
                    val hit = kb?.findKeyAt(rawXOf(ev, i), rawYOf(ev, i))
                    val act = hit?.second
                    if (act is KeyAction.CommitAction) {
                        org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(hit.first)
                        kb.dispatchSlideAction(act)
                    }
                }
                switchLayout(TextKeyboard.Name, remember = false)
            } else if (released && (chorded || ev.eventTime - slideDownTime > HoldReturnMillis)) {
                // 按住「?123」期间用另一只手输入了符号（或明显是长按），松手自动回到字母键盘
                switchLayout(TextKeyboard.Name, remember = false)
            }
            slidePointerId = -1
            chorded = false
        }

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    chordPointerId = -1
                    val kb = currentKeyboard
                    val hit = if (kb is TextKeyboard) kb.findKeyAt(ev.rawX, ev.rawY) else null
                    val act = hit?.second
                    if (act is KeyAction.LayoutSwitchAction && act.act == GboardSymbolKeyboard.Page1Name) {
                        sliding = true
                        moved = false
                        chorded = false
                        slidePointerId = ev.getPointerId(0)
                        slideDownTime = ev.eventTime
                        downX = ev.rawX
                        downY = ev.rawY
                        getLocationOnScreen(loc)
                        lineX0 = ev.rawX - loc[0]
                        lineY0 = ev.rawY - loc[1]
                        org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(hit.first)
                        switchLayout(GboardSymbolKeyboard.Page1Name)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> if (sliding) {
                    // 已经在滑动选符号时不接第二根手指，避免两种操作冲突；同一时间只转发一根
                    if (!moved && chordPointerId < 0) {
                        val i = ev.actionIndex
                        chordPointerId = ev.getPointerId(i)
                        chordDownTime = ev.eventTime
                        chorded = true
                        forwardChord(ev, i, android.view.MotionEvent.ACTION_DOWN)
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> if (sliding) {
                    if (chordPointerId >= 0) {
                        val ci = ev.findPointerIndex(chordPointerId)
                        if (ci >= 0) forwardChord(ev, ci, android.view.MotionEvent.ACTION_MOVE)
                    }
                    val si = ev.findPointerIndex(slidePointerId)
                    if (si >= 0 && !chorded) {
                        val rx = rawXOf(ev, si)
                        val ry = rawYOf(ev, si)
                        if (!moved && kotlin.math.hypot(rx - downX, ry - downY) > threshold) moved = true
                        if (moved) {
                            lineTo(rx, ry)
                            val kb = currentKeyboard
                            val hit = kb?.findKeyAt(rx, ry)
                            setPreview(kb, hit?.takeIf { it.second is KeyAction.CommitAction }?.first)
                        }
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> if (sliding) {
                    when (ev.getPointerId(ev.actionIndex)) {
                        chordPointerId -> {
                            forwardChord(ev, ev.actionIndex, android.view.MotionEvent.ACTION_UP)
                            chordPointerId = -1
                        }
                        slidePointerId -> {
                            // 先松开了「?123」：手里那个符号照常上屏，然后回到字母键盘
                            endChordIfAny(ev, commit = true)
                            finishSlide(ev, released = true)
                        }
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val cancel = ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    if (sliding) {
                        endChordIfAny(ev, commit = !cancel)
                        finishSlide(ev, released = !cancel)
                        return true
                    }
                }
                else -> if (sliding) return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}