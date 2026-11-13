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
    textSize: Float = 26.8f
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
            GbSymKey(left, 0.1f, Variant.Alternative, 26f),
            LayoutSwitchKey("12\n34", NumberKeyboard.Name, 0.1f, Variant.Normal),
            SpaceKey(),
            GbSymKey(right, 0.1f, Variant.Alternative, 26f),
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
                    key.addView(DigitGridView(context, theme.keyTextColor).apply {
                        isClickable = false
                        isFocusable = false
                    }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
                }
        }
    }

    /** Gboard's "1 2 / 3 4" label: every digit centered in its own cell of a tight 2x2 grid */
    private class DigitGridView(context: android.content.Context, color: Int) : android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 13.5f * context.resources.displayMetrics.scaledDensity
            textAlign = android.graphics.Paint.Align.CENTER
            GboardFont.get(context)?.let { typeface = it }
        }
        private val cellW = paint.textSize * 0.68f
        private val cellH = paint.textSize * 1.02f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension((cellW * 2).toInt() + 2, (cellH * 2).toInt() + 2)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val baseline = -(paint.ascent() + paint.descent()) / 2f
            val x0 = width / 2f - cellW / 2f
            val y0 = height / 2f - cellH / 2f
            "1234".forEachIndexed { i, c ->
                canvas.drawText(c.toString(), x0 + (i % 2) * cellW, y0 + (i / 2) * cellH + baseline, paint)
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
