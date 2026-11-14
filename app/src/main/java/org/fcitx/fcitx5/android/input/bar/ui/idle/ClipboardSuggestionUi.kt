/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.text.TextUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

class ClipboardSuggestionUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val icon = imageView {
        imageDrawable = drawable(R.drawable.ic_clipboard)!!.apply {
            setTint(theme.altKeyTextColor)
        }
    }

    private val thumb = imageView {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
            }
        }
        visibility = android.view.View.GONE
    }

    val text = textView {
        isSingleLine = true
        maxWidth = dp(168)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    private val layout = constraintLayout {
        val spacing = dp(4)
        add(icon, lParams(dp(20), dp(20)) {
            startOfParent(spacing)
            before(thumb)
            centerVertically()
        })
        add(thumb, lParams(dp(24), dp(24)) {
            after(icon, spacing)
            before(text)
            centerVertically()
        })
        add(text, lParams(wrapContent, wrapContent) {
            after(thumb, spacing)
            endOfParent(spacing)
            centerVertically()
        })
    }

    val suggestionView = CustomGestureView(ctx).apply {
        add(layout, lParams(wrapContent, matchParent))
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    override val root = constraintLayout {
        add(suggestionView, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }

    fun showText(content: CharSequence) {
        thumb.setImageDrawable(null)
        thumb.visibility = android.view.View.GONE
        icon.visibility = android.view.View.VISIBLE
        text.text = content
        playEnterAnimation()
    }

    fun showImage(bitmap: android.graphics.Bitmap?, label: CharSequence) {
        if (bitmap != null) {
            thumb.setImageBitmap(bitmap)
            thumb.visibility = android.view.View.VISIBLE
            icon.visibility = android.view.View.GONE
        } else {
            thumb.setImageDrawable(null)
            thumb.visibility = android.view.View.GONE
            icon.visibility = android.view.View.VISIBLE
        }
        text.text = label
        playEnterAnimation()
    }

    private fun playEnterAnimation() {
        suggestionView.animate().cancel()
        suggestionView.alpha = 0f
        suggestionView.scaleX = 0.94f
        suggestionView.scaleY = 0.94f
        suggestionView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .start()
    }
}
