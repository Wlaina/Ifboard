/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, theme: Theme, switchKey: KeyDef, current: PickerWindow.Key? = null) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef, current: PickerWindow.Key?) : BaseKeyboard(
        context, theme, listOf(
            if (current == PickerWindow.Key.Emoji || current == PickerWindow.Key.Emoticon) listOf(
                // Gboard style emoji bar: 返回 / emoji / :-) / space / backspace
                LayoutSwitchKey("返回", TextKeyboard.Name, 0.15f, KeyDef.Appearance.Variant.Alternative),
                ImagePickerSwitchKey(
                    R.drawable.ic_gb_sentiment_satisfied, PickerWindow.Key.Emoji, 0.15f,
                    if (current == PickerWindow.Key.Emoji) KeyDef.Appearance.Variant.Accent else KeyDef.Appearance.Variant.Normal
                ),
                TextPickerSwitchKey(
                    ":-)", PickerWindow.Key.Emoticon, 0.15f,
                    if (current == PickerWindow.Key.Emoticon) KeyDef.Appearance.Variant.Accent else KeyDef.Appearance.Variant.Normal
                ),
                SpaceKey(),
                BackspaceKey()
            ) else listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                PunctuationKey(","),
                switchKey,
                SpaceKey(),
                PunctuationKey("."),
                ReturnKey()
            )
        )
    ) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

        override fun onReturnDrawableUpdate(returnDrawable: Int) {
            findViewById<ImageKeyView>(R.id.button_return)?.img?.imageResource = returnDrawable
        }
    }

    val embeddedKeyboard = Keyboard(context, theme, switchKey, current)

    val pager = view(::ViewPager2) { }

    val tabsUi = PickerTabsUi(context, theme)

    val paginationUi = PickerPaginationUi(context, theme)

    init {
        add(pager, lParams {
            topOfParent()
            centerHorizontally()
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(pager)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
        add(paginationUi.root, lParams(matchConstraints, dp(2)) {
            centerHorizontally()
            below(pager, dp(-1))
        })
    }
}