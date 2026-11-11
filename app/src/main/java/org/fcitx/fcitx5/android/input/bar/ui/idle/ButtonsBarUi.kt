/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    // Gboard style: evenly spaced icons
    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_EVENLY
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        root.addView(it, FlexboxLayout.LayoutParams(size, size))
    }

    val translateButton = toolButton(R.drawable.ic_gb_g_translate).apply {
        contentDescription = "翻译"
    }

    val undoButton = toolButton(R.drawable.ic_gb_undo).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val clipboardButton = toolButton(R.drawable.ic_gb_content_paste).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val emojiButton = toolButton(R.drawable.ic_gb_sentiment_satisfied).apply {
        contentDescription = ctx.getString(R.string.emoji_and_symbols)
    }

    val settingsButton = toolButton(R.drawable.ic_gb_settings).apply {
        contentDescription = "设置"
    }

}
