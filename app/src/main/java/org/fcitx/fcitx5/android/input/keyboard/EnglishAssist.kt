/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Gboard-like English typing helpers for the English (keyboard-us) input method:
 *  - auto capitalization following the editor's caps mode
 *  - double-space inserts ". "
 *  - autocorrect on space using the system spell checker (Gboard's), backspace undoes it
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
import java.util.Locale

class EnglishAssist(private val service: FcitxInputMethodService) {

    var enabled = false
        private set

    private val prefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().keyboard

    private val handler = Handler(Looper.getMainLooper())
    private var lastSpaceTime = 0L
    private var lastCorrection: Pair<String, String>? = null
    private var session: SpellCheckerSession? = null
    private var pendingWord: String? = null

    private val wordBeforeSpace = Regex("(?:^|[^A-Za-z'])([A-Za-z][A-Za-z']{1,30}) $")

    fun onImeUpdate(ime: InputMethodEntry) {
        enabled = ime.uniqueName.startsWith("keyboard-")
        lastCorrection = null
        if (!enabled) closeSession()
    }

    private fun editorAllowsCorrection(): Boolean {
        val info: EditorInfo = service.currentInputEditorInfo ?: return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /** whether the cursor is at a position where Gboard would capitalize automatically */
    fun shouldAutoCap(): Boolean {
        if (!enabled || !prefs.englishAutoCapitalize.getValue()) return false
        val info = service.currentInputEditorInfo ?: return false
        val ic = service.currentInputConnection ?: return false
        return runCatching { ic.getCursorCapsMode(info.inputType) != 0 }.getOrDefault(false)
    }

    /** @return true if the action has been fully handled here */
    fun beforeKeyAction(action: KeyAction): Boolean {
        if (!enabled) return false
        if (action !is KeyAction.SymAction) {
            lastCorrection = null
            return false
        }
        when (action.sym.sym) {
            FcitxKeyMapping.FcitxKey_space -> {
                val ic = service.currentInputConnection ?: return false
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (prefs.englishDoubleSpacePeriod.getValue() && now - lastSpaceTime < 600 && before.length == 2 && before[1] == ' ' &&
                    before[0].isLetterOrDigit() && editorAllowsCorrection()
                ) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    ic.endBatchEdit()
                    lastSpaceTime = 0L
                    lastCorrection = null
                    return true
                }
                lastSpaceTime = now
                lastCorrection = null
                if (prefs.englishAutoCorrect.getValue() && editorAllowsCorrection()) {
                    // let the engine commit the word first, then check it
                    handler.postDelayed({ checkLastWord() }, 80)
                }
                return false
            }
            FcitxKeyMapping.FcitxKey_BackSpace -> {
                val corr = lastCorrection ?: return false
                lastCorrection = null
                val ic = service.currentInputConnection ?: return false
                val (orig, fixed) = corr
                val before = ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString()
                if (before == "$fixed ") {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(fixed.length + 1, 0)
                    ic.commitText(orig, 1)
                    ic.endBatchEdit()
                    return true
                }
                return false
            }
            else -> {
                lastCorrection = null
                return false
            }
        }
    }

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val word = pendingWord ?: return
            pendingWord = null
            val info = results?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }
                ?.getSuggestionsInfoAt(0) ?: return
            val attrs = info.suggestionsAttributes
            val typo = attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
            val recommended = attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0
            if (!typo || !recommended || info.suggestionsCount < 1) return
            var fixed = info.getSuggestionAt(0) ?: return
            if (fixed.equals(word, ignoreCase = true) || fixed.contains(' ')) return
            if (word[0].isUpperCase()) fixed = fixed.replaceFirstChar { it.titlecase(Locale.US) }
            handler.post { applyCorrection(word, fixed) }
        }
    }

    private fun applyCorrection(word: String, fixed: String) {
        if (!enabled) return
        val ic = service.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(word.length + 1, 0)?.toString()
        if (before != "$word ") return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length + 1, 0)
        ic.commitText("$fixed ", 1)
        ic.endBatchEdit()
        lastCorrection = word to fixed
    }

    private fun checkLastWord() {
        if (!enabled) return
        val ic = service.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        val word = wordBeforeSpace.find(before)?.groupValues?.get(1) ?: return
        val s = session ?: runCatching {
            val tsm = service.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            tsm.newSpellCheckerSession(null, Locale.US, listener, false)
        }.onFailure { Timber.w(it, "spell checker unavailable") }.getOrNull()?.also { session = it }
        ?: return
        pendingWord = word
        s.getSentenceSuggestions(arrayOf(TextInfo(word)), 3)
    }

    fun closeSession() {
        handler.removeCallbacksAndMessages(null)
        session?.close()
        session = null
    }
}
