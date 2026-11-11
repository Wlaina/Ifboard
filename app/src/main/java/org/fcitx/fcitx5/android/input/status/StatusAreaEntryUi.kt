/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.icu.text.BreakIterator
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerOn
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable

/** Gboard style feature tile: pill with icon + label, two per row */
class StatusAreaEntryUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val bkgDrawable = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = ctx.dp(26f)
    }

    val icon = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val textIcon = view(::AutoScaleTextView) {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            typeface = Typeface.create(typeface, 600, false)
        } else {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    val label = textView {
        textSize = 14f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
        org.fcitx.fcitx5.android.input.keyboard.GboardFont.get(ctx)?.let { typeface = it }
    }

    private val iconBox = frameLayout {
        add(icon, lParams(dp(22), dp(22), android.view.Gravity.CENTER))
        add(textIcon, lParams(wrapContent, wrapContent, android.view.Gravity.CENTER))
    }

    override val root = object : CustomGestureView(ctx) {
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = bkgDrawable
            setPadding(dp(18), 0, dp(14), 0)
            addView(iconBox, android.widget.LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(label, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        }

        init {
            add(content, lParams(matchParent, dp(52), android.view.Gravity.CENTER).apply {
                leftMargin = dp(5); rightMargin = dp(5)
            })
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
        }
    }

    fun setEntry(entry: StatusAreaEntry) {
        val contentColor =
            if (entry.active) theme.genericActiveForegroundColor else theme.keyTextColor
        if (entry.icon != 0) {
            icon.visibility = View.VISIBLE
            textIcon.visibility = View.GONE
            icon.imageDrawable = ctx.drawable(entry.icon)!!.mutate().apply {
                setTint(contentColor)
            }
        } else {
            icon.visibility = View.GONE
            textIcon.visibility = View.VISIBLE
            textIcon.text = getFirstCharacter(entry.label)
            textIcon.setTextColor(contentColor)
        }
        bkgDrawable.setColor(
            if (entry.active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor
        )
        label.setTextColor(contentColor)
        label.text = entry.label
    }

    private fun getFirstCharacter(s: String): String {
        if (s.isEmpty()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(s)
            s.substring(iterator.first(), iterator.next())
        } else {
            s.substring(0, s.offsetByCodePoints(0, 1))
        }
    }
}
