/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Thin async wrapper around the system spell checker (Gboard's on this device),
 * used for English correction suggestions in the candidate bar.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import timber.log.Timber
import java.util.Locale

class GboardSpell(private val context: Context) {

    data class Result(val word: String, val looksLikeTypo: Boolean, val recommended: Boolean, val suggestions: List<String>)

    private val main = Handler(Looper.getMainLooper())
    private var session: SpellCheckerSession? = null
    private var pending: Pair<String, (Result) -> Unit>? = null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val (word, cb) = pending ?: return
            pending = null
            val info = results?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }?.getSuggestionsInfoAt(0)
            val attrs = info?.suggestionsAttributes ?: 0
            val list = if (info == null) emptyList() else
                (0 until info.suggestionsCount).mapNotNull { info.getSuggestionAt(it) }
                    .filter { !it.contains(' ') && !it.equals(word, ignoreCase = true) }
                    .map { s -> if (word[0].isUpperCase()) s.replaceFirstChar { it.titlecase(Locale.US) } else s }
                    .distinct()
            val r = Result(
                word,
                attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
                list
            )
            main.post { cb(r) }
        }
    }

    fun query(word: String, cb: (Result) -> Unit) {
        val s = session ?: runCatching {
            val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            tsm.newSpellCheckerSession(null, Locale.US, listener, false)
        }.onFailure { Timber.w(it, "spell checker unavailable") }.getOrNull()?.also { session = it }
        ?: return
        pending = word to cb
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(word)), 5) }
            .onFailure { Timber.w(it, "spell query failed"); close() }
    }

    fun close() {
        pending = null
        session?.close()
        session = null
    }
}
