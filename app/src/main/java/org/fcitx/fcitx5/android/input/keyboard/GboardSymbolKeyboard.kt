/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Gboard style "?123" symbol keyboards (2 pages).
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant

/** A symbol key that commits its text directly (also finishes any pending composition) */
class GbSymKey(
    symbol: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    textSize: Float = 23f
) : KeyDef(
    Appearance.Text(
        displayText = symbol,
        textSize = textSize,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(Behavior.Press(KeyAction.CommitAction(symbol))),
    arrayOf(Popup.Preview(symbol))
)

class GboardSymbolKeyboard(
    context: Context,
    theme: Theme,
    page: Int
) : BaseKeyboard(context, theme, if (page == 1) Page1 else Page2) {

    companion object {
        const val Page1Name = "GbSymbol1"
        const val Page2Name = "GbSymbol2"

        private fun row(vararg s: String) = s.map { GbSymKey(it) }

        private fun bottomRow(left: String, right: String) = listOf(
            LayoutSwitchKey("返回", TextKeyboard.Name, 0.15f, Variant.Alternative),
            GbSymKey(left, 0.1f, Variant.Alternative),
            LayoutSwitchKey("12\n34", NumberKeyboard.Name, 0.1f, Variant.Normal),
            SpaceKey(),
            GbSymKey(right, 0.1f, Variant.Alternative),
            ReturnKey()
        )

        val Page1: List<List<KeyDef>> = listOf(
            row("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            row("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
            listOf<KeyDef>(LayoutSwitchKey("=\\<", Page2Name, 0.15f, Variant.Alternative)) +
                    row("、", "“", "”", ":", ";", "!", "?") +
                    BackspaceKey(),
            bottomRow(",", ".")
        )

        val Page2: List<List<KeyDef>> = listOf(
            row("~", "`", "|", "•", "√", "π", "÷", "×", "*", "§"),
            row("£", "¢", "€", "¥", "^", "°", "=", "「", "」", "\\"),
            listOf<KeyDef>(LayoutSwitchKey("?123", Page1Name, 0.15f, Variant.Alternative)) +
                    row("%", "‘", "’", "™", "✓", "[", "]") +
                    BackspaceKey(),
            bottomRow("<", ">")
        )
    }

    init {
        // "12 / 34" key: two small lines like Gboard (AutoScaleTextView only draws one line)
        post {
            allViewsCompat(this).filterIsInstance<TextKeyView>()
                .filter { it.mainText.text.toString() == "12\n34" }
                .forEach { key ->
                    key.mainText.visibility = android.view.View.INVISIBLE
                    key.addView(android.widget.TextView(context).apply {
                        text = "12\n34"
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setLineSpacing(0f, 0.9f)
                        setTextColor(theme.keyTextColor)
                        GboardFont.get(context)?.let { typeface = it }
                        isClickable = false
                        isFocusable = false
                    }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
                }
        }
    }

    private fun allViewsCompat(v: android.view.View): List<android.view.View> =
        if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViewsCompat(v.getChildAt(it)) }
        else listOf(v)

    val space: TextKeyView by lazy { findViewById(org.fcitx.fcitx5.android.R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(org.fcitx.fcitx5.android.R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.setImageResource(returnDrawable)
    }

    override fun onInputMethodUpdate(ime: org.fcitx.fcitx5.android.core.InputMethodEntry) {
        space.mainText.text = if (ime.uniqueName.startsWith("keyboard-")) "English" else "雾凇拼音"
        space.setSignatureDot(0xB3D97757.toInt(), 3f)
    }
}
