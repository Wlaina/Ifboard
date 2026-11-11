/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Google Sans (SIL OFL 1.1, see assets/licenses/GoogleSans-OFL.txt), the typeface Gboard uses.
 * CJK glyphs fall back to the system font automatically.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import org.fcitx.fcitx5.android.R

object GboardFont {
    private var cached: Typeface? = null
    private var failed = false

    fun get(ctx: Context): Typeface? {
        if (cached != null || failed) return cached
        cached = runCatching { ResourcesCompat.getFont(ctx, R.font.google_sans) }.getOrNull()
        if (cached == null) failed = true
        return cached
    }
}
