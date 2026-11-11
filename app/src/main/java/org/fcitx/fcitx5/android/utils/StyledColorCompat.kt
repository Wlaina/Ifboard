/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Material 3 themes expose some color attributes as ColorStateLists;
 * splitties' styledColor() only accepts plain colors, so resolve both here.
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

@ColorInt
fun Context.styledColorCompat(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return Color.MAGENTA
    return if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data
    else if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId)
    else Color.MAGENTA
}

@ColorInt
fun View.styledColorCompat(@AttrRes attr: Int): Int = context.styledColorCompat(attr)
