/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.dimensions.dp
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout.also { KeyMarginOverride.dp = 2.4f }) {

    init {
        KeyMarginOverride.dp = null
    }


    companion object {
        const val Name = "Number"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                SpacerKey(),
                NumPadKey("1", 0xffb1, 30f, 0f),
                NumPadKey("2", 0xffb2, 30f, 0f),
                NumPadKey("3", 0xffb3, 30f, 0f),
                NumPadKey("%", 0x25, 26f, 0.15f, KeyDef.Appearance.Variant.Alternative),
            ),
            listOf(
                SpacerKey(),
                NumPadKey("4", 0xffb4, 30f, 0f),
                NumPadKey("5", 0xffb5, 30f, 0f),
                NumPadKey("6", 0xffb6, 30f, 0f),
                MiniSpaceKey()
            ),
            listOf(
                SpacerKey(),
                NumPadKey("7", 0xffb7, 30f, 0f),
                NumPadKey("8", 0xffb8, 30f, 0f),
                NumPadKey("9", 0xffb9, 30f, 0f),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("返回", TextKeyboard.Name, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey(",", 0xffac, 26f, 0.1f, KeyDef.Appearance.Variant.Alternative),
                LayoutSwitchKey("!?#", GboardSymbolKeyboard.Page1Name, 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey("0", 0xffb0, 30f, 0.23334f),
                NumPadKey("=", 0xffbd, 26f, 0.13333f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey(".", 0xffae, 26f, 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_mini_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    /** invisible placeholder occupying the left column; the scrollable strip is drawn over it */
    class SpacerKey : KeyDef(
        Appearance.Text(
            displayText = "",
            textSize = 1f,
            percentWidth = 0.15f,
            variant = Appearance.Variant.Alternative,
            border = Appearance.Border.Off
        ),
        emptySet()
    )

    // ---- Gboard style scrollable operator strip on the left (spans 3 rows) ----
    private val stripSymbols = listOf("+", "−", "*", "/", "(", ")", ",", ":", "=", "#", "^", "~", "!", "×", "÷")

    private val strip by lazy {
        val radius = dp(org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.keyRadius.getValue()).toFloat()
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = object : android.widget.ScrollView(context) {
            var cellHeight = 0
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                // like Gboard, a little of the next symbol peeks out to hint that it scrolls
                val ch = (h / 4.4f).toInt()
                if (ch != cellHeight) {
                    cellHeight = ch
                    for (i in 0 until column.childCount) column.getChildAt(i).layoutParams.height = ch
                    column.requestLayout()
                }
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                setColor(theme.altKeyBackgroundColor)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(column, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        stripSymbols.forEach { sym ->
            column.addView(android.widget.TextView(context).apply {
                text = sym
                textSize = 25.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
                GboardFont.get(context)?.let { typeface = it }
                paint.applyKeycapStroke(context.resources.displayMetrics.density, 0.28f)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(theme.keyPressHighlightColor), null, null
                )
                isClickable = true
                setOnClickListener { v ->
                    org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(v)
                    val text = when (sym) { "−" -> "-"; else -> sym }
                    onAction(KeyAction.CommitAction(text))
                }
            }, android.widget.LinearLayout.LayoutParams(-1, dp(40)))
        }
        scroll
    }

    private val stripRect = android.graphics.Rect()
    private var stripTouch = false

    private fun layoutStrip() {
        val firstRowKey = (getChildAt(0) as? android.view.ViewGroup)?.getChildAt(0) as? KeyView ?: return
        val thirdRowKey = (getChildAt(2) as? android.view.ViewGroup)?.getChildAt(0) as? KeyView ?: return
        val me = IntArray(2).also { getLocationInWindow(it) }
        val a = IntArray(2).also { firstRowKey.getLocationInWindow(it) }
        val b = IntArray(2).also { thirdRowKey.getLocationInWindow(it) }
        val left = a[0] - me[0] + firstRowKey.hMargin
        val top = a[1] - me[1] + firstRowKey.vMargin
        val right = a[0] - me[0] + firstRowKey.width - firstRowKey.hMargin
        val bottom = b[1] - me[1] + thirdRowKey.height - thirdRowKey.vMargin
        if (right <= left || bottom <= top) return
        stripRect.set(left, top, right, bottom)
        val lp = LayoutParams(right - left, bottom - top).apply {
            topToTop = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            leftMargin = left
            topMargin = top
        }
        if (strip.parent == null) addView(strip, lp) else strip.layoutParams = lp
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || strip.parent == null) post { layoutStrip() }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            stripTouch = strip.parent != null && stripRect.contains(ev.x.toInt(), ev.y.toInt())
        }
        if (stripTouch) {
            // hand the whole gesture to the strip: drag scrolls, tap types
            val e = android.view.MotionEvent.obtain(ev)
            e.offsetLocation(-stripRect.left.toFloat(), -stripRect.top.toFloat())
            strip.dispatchTouchEvent(e)
            e.recycle()
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                stripTouch = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // leave empty on purpose to disable popup in NumberKeyboard
    }

}