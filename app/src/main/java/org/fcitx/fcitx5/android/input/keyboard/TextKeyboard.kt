/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("?123", GboardSymbolKeyboard.Page1Name),
                CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                LanguageKey(),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    // whether current CapsState.Once was set automatically (Gboard-like auto capitalization)
    private var autoCapped = false

    fun setAutoCaps(on: Boolean) {
        if (on && capsState == CapsState.None) {
            capsState = CapsState.Once
            autoCapped = true
            updateCapsButtonIcon()
            updateAlphabetKeys()
        } else if (!on && capsState == CapsState.Once && autoCapped) {
            capsState = CapsState.None
            autoCapped = false
            updateCapsButtonIcon()
            updateAlphabetKeys()
        }
    }

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    // ---- glide typing
    private var imeName = ""
    private var imeSubMode = ""

    override val glideMode: GlideTyping.Mode?
        get() = when {
            imeName == "rime" -> when {
                // Rime reports the current schema name as sub mode
                imeSubMode.contains("小鹤") || imeSubMode.contains("flypy", ignoreCase = true) -> GlideTyping.Mode.Flypy
                imeSubMode.contains("双拼") -> null // other double pinyin layouts are not supported
                else -> GlideTyping.Mode.Pinyin
            }
            imeName.startsWith("keyboard-us") -> GlideTyping.Mode.English
            else -> null
        }

    private val glidePreviewSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val glidePreviewExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "glide-preview").apply { isDaemon = true }
    }

    private fun applyCapsToEnglishWord(word: String, caps: CapsState) = when (caps) {
        CapsState.None -> word
        CapsState.Once -> word.replaceFirstChar { it.uppercaseChar() }
        CapsState.Lock -> word.uppercase()
    }

    override fun onGlideProgress(xy: FloatArray, n: Int, geometry: GlideTyping.Geometry) {
        val mode = glideMode ?: return
        if (!GlideTyping.isReady(mode)) return
        val seq = glidePreviewSeq.incrementAndGet()
        val caps = capsState
        glidePreviewExecutor.execute {
            if (seq != glidePreviewSeq.get()) return@execute
            val results = GlideTyping.decode(xy, n, geometry, mode, limit = 5)
            val best = results.firstOrNull() ?: return@execute
            val codeText = when (mode) {
                GlideTyping.Mode.Pinyin, GlideTyping.Mode.Flypy -> best.code
                GlideTyping.Mode.English -> applyCapsToEnglishWord(best.output, caps)
            }
            val cands = results.map { r ->
                if (mode == GlideTyping.Mode.English) applyCapsToEnglishWord(r.output, caps) else r.output
            }.distinct()
            post {
                if (seq == glidePreviewSeq.get()) {
                    keyActionListener?.onKeyAction(
                        KeyAction.GlidePreviewAction(codeText, cands),
                        KeyActionListener.Source.Keyboard
                    )
                }
            }
        }
    }

    override fun onGlideCancel() {
        glidePreviewSeq.incrementAndGet()
        keyActionListener?.onKeyAction(KeyAction.GlidePreviewClearAction, KeyActionListener.Source.Keyboard)
    }

    override fun onGlideGesture(xy: FloatArray, n: Int, geometry: GlideTyping.Geometry) {
        val mode = glideMode ?: return
        if (!GlideTyping.isReady(mode)) {
            // first gesture right after the keyboard started: decode it as soon as the words are loaded
            GlideTyping.whenReady(context, mode) { post { if (glideMode == mode) commitGlide(xy, n, geometry, mode) } }
            return
        }
        commitGlide(xy, n, geometry, mode)
    }

    private fun commitGlide(xy: FloatArray, n: Int, geometry: GlideTyping.Geometry, mode: GlideTyping.Mode) {
        FlypyTypoCorrector.clear()
        val best = GlideTyping.decode(xy, n, geometry, mode).firstOrNull() ?: return
        when (mode) {
            GlideTyping.Mode.Pinyin, GlideTyping.Mode.Flypy -> best.code.forEach {
                // bypass caps handling: Rime always gets lowercase key presses
                super.onAction(KeyAction.FcitxKeyAction(it.toString()), KeyActionListener.Source.Keyboard)
            }
            GlideTyping.Mode.English -> {
                val word = applyCapsToEnglishWord(best.output, capsState)
                if (capsState == CapsState.Once) switchCapsState()
                super.onAction(KeyAction.GlideWordAction(word), KeyActionListener.Source.Keyboard)
            }
        }
    }

    private fun prepareGlide() {
        val mode = glideMode
        if (mode == GlideTyping.Mode.Flypy) {
            FlypyTypoCorrector.prepare(context)
        }
        if (!AppPrefs.getInstance().keyboard.glideTyping.getValue()) return
        mode?.let { GlideTyping.prepare(context, it) }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        if (glideMode == GlideTyping.Mode.Flypy && transformed is KeyAction.FcitxKeyAction) {
            if (source == KeyActionListener.Source.Keyboard &&
                transformed.act.length == 1 &&
                transformed.act[0] in 'a'..'z'
            ) {
                FlypyTypoCorrector.recordTap(
                    transformed.act[0],
                    lastTapX,
                    lastTapY,
                    currentGlideGeometry()
                )
            } else {
                FlypyTypoCorrector.clear()
            }
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        prepareGlide()
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        imeName = ime.uniqueName
        imeSubMode = ime.subMode.run { "$name $label" }
        FlypyTypoCorrector.clear()
        prepareGlide()
        // Gboard style space bar label, plus a small signature so it is not mistaken for Gboard:
        // "雾凇拼音" and a tiny terracotta dot (same accent as the PC Weasel theme)
        val label = when {
            ime.uniqueName == "rime" -> "雾凇拼音"
            ime.uniqueName == "pinyin" -> "拼音"
            ime.uniqueName.startsWith("keyboard-us") -> "English"
            else -> buildString {
                append(ime.displayName)
                ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
            }
        }
        space.mainText.text = label
        // subtle terracotta dot, only noticeable when looking closely
        space.setSignatureDot(0xB3D97757.toInt(), 3f)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        autoCapped = false
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            val def = it.def as? KeyDef.Appearance.AltText ?: return@forEach
            val str = def.displayText
            if (str.length != 1 || !str[0].isLetter()) return@forEach
            val displayed = if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            it.mainText.text = displayed
            // 大写字形占满升部高度，略微收小字号即可与右上角数字保持与 Gboard 一致的留白
            val sizeSp = if (displayed.firstOrNull()?.isUpperCase() == true) 21.5f else def.textSize
            it.mainText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeSp)
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}