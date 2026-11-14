/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: runner-up words of the last Chinese glide gesture.
 *
 * Gboard keeps the other likely words of a gesture in the candidate bar, so a wrong guess costs one
 * tap instead of deleting and gliding again. For 全拼 / 小鹤双拼 the best code is sent to Rime (which
 * picks the characters); the alternates are merged into Rime's candidate list right after its first
 * candidate until the next key press or commit.
 */
package org.fcitx.fcitx5.android.input.keyboard

object GlideAlternates {

    class Alt(val word: String, val code: String)

    @Volatile
    var current: List<Alt> = emptyList()
        private set

    /** code that was sent to Rime for the best guess */
    @Volatile
    var bestCode: String = ""
        private set

    /** Rime already has a composition (kept up to date by the candidate bar) */
    @Volatile
    var composing = false

    fun set(best: GlideTyping.Result, results: List<GlideTyping.Result>, max: Int = 3) {
        // picking an alternate replaces the whole composition: only offer them for a fresh one
        if (composing) {
            clear()
            return
        }
        val seen = HashSet<String>()
        seen.add(best.output)
        val alts = ArrayList<Alt>(max)
        for (r in results) {
            if (r === best || r.code == best.code || !seen.add(r.output)) continue
            alts.add(Alt(r.output, r.code))
            if (alts.size >= max) break
        }
        bestCode = best.code
        current = alts
    }

    fun clear() {
        if (current.isNotEmpty()) current = emptyList()
        bestCode = ""
    }
}
