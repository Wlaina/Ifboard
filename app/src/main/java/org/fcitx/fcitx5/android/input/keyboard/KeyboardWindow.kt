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
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

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
        if (englishAssist.beforeKeyAction(it)) return@KeyActionListener
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
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

        private fun setPreview(kb: BaseKeyboard?, v: KeyView?) {
            if (v === previewing) return
            previewing?.let { kb?.dismissSlidePreview(it); it.isPressed = false }
            previewing = v
            v?.let { it.isPressed = true; kb?.showSlidePreview(it) }
        }

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val kb = currentKeyboard
                    val hit = if (kb is TextKeyboard) kb.findKeyAt(ev.rawX, ev.rawY) else null
                    val act = hit?.second
                    if (act is KeyAction.LayoutSwitchAction && act.act == GboardSymbolKeyboard.Page1Name) {
                        sliding = true
                        moved = false
                        downX = ev.rawX
                        downY = ev.rawY
                        org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(hit.first)
                        switchLayout(GboardSymbolKeyboard.Page1Name)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> if (sliding) {
                    if (!moved && kotlin.math.hypot(ev.rawX - downX, ev.rawY - downY) > threshold) moved = true
                    if (moved) {
                        val kb = currentKeyboard
                        val hit = kb?.findKeyAt(ev.rawX, ev.rawY)
                        setPreview(kb, hit?.takeIf { it.second is KeyAction.CommitAction }?.first)
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (sliding) {
                    sliding = false
                    val kb = currentKeyboard
                    setPreview(kb, null)
                    if (moved && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        val hit = kb?.findKeyAt(ev.rawX, ev.rawY)
                        val act = hit?.second
                        if (act is KeyAction.CommitAction) {
                            org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(hit.first)
                            kb.dispatchSlideAction(act)
                            switchLayout(TextKeyboard.Name, remember = false)
                        }
                    }
                    return true
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