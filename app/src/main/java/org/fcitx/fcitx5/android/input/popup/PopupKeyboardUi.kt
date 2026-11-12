/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import splitties.views.gravityEnd
import splitties.views.gravityStart
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * @param ctx [Context]
 * @param theme [Theme]
 * @param outerBounds bound [Rect] of [PopupComponent] root view.
 * @param triggerBounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine column order. See [focusColumn] and [columnOrder].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param radius popup keyboard and key radius
 * @param keyWidth key width in popup keyboard
 * @param keyHeight key height in popup keyboard
 * @param popupHeight popup preview view height. Used to transform gesture coordinate from
 * trigger view to popup keyboard view. See [offsetX] and [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 */
class PopupKeyboardUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private val keyHeight: Int,
    private val popupHeight: Int,
    private val keys: Array<String>,
    private val labels: Array<String>,
    /** >0: fixed grid with this many columns, laid out row by row from top (Gboard symbol panel) */
    private val grid: Int = 0,
    private val initialFocus: Int = 0
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    class PopupKeyUi(override val ctx: Context, val theme: Theme, val text: String) : Ui {

        val textView = view(::AutoScaleTextView) {
            text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            textSize = 22f
            setTextColor(theme.keyTextColor)
            org.fcitx.fcitx5.android.input.keyboard.GboardFont.get(ctx)?.let { typeface = it }
        }

        override val root = frameLayout {
            add(textView, lParams {
                gravity = gravityCenter
            })
        }
    }

    // Gboard style: pill shaped container and a round focus highlight
    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = keyHeight / 2f
        if (org.fcitx.fcitx5.android.input.Glass.enabled) {
            // liquid glass: translucent with a faint light edge
            setColor(org.fcitx.fcitx5.android.input.Glass.tint(theme.popupBackgroundColor, 0.78f))
            setStroke(ctx.dp(1), 0x1FFFFFFF)
        } else setColor(theme.popupBackgroundColor)
    }

    private val focusBackground = android.graphics.drawable.InsetDrawable(
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.genericActiveBackgroundColor)
        },
        maxOf((keyWidth - keyHeight * 4 / 5) / 2, 0), keyHeight / 10,
        maxOf((keyWidth - keyHeight * 4 / 5) / 2, 0), keyHeight / 10
    )

    private val rowCount: Int
    private val columnCount: Int

    // those 2 variables meas initial focus row/column during initialization
    private val focusRow: Int
    private val focusColumn: Int

    init {
        val keyCount: Float = keys.size.toFloat()
        if (grid > 0) {
            columnCount = grid
            rowCount = ceil(keyCount / grid).toInt()
            focusRow = rowCount - 1 - initialFocus / grid
            focusColumn = initialFocus % grid
        } else {
            rowCount = ceil(keyCount / 5).toInt()
            columnCount = (keyCount / rowCount).roundToInt()
            focusRow = 0
            focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
        }
    }

    /**
     * Offset on X axis made up of 2 parts:
     *  1. from trigger view bounds left to popup entry view left
     *  2. from left-most column to initial focused column
     *
     * Offset on Y axis made up of 2 parts as well:
     *  1. from trigger view top to popup entry view top
     *  2. from top-most row to initial focused row (bottom row)
     *
     * ```
     *                    c───┬───┬───┐
     *                    │   │ 4 │ 5 │
     *                 ┌─ ├───p───┼───┤ ─┐
     *   popupKeyHeight│  │ 3 │ 1 │ 2 │  │
     *                 └─ └───┼───┼───┘  │
     *                        │   │      │popupHeight
     *                 ┌───── │o─┐│      │
     *  bounds.height()│      ││a││      │
     *                 └───── └┴─┴┘ ─────┘
     * ```
     * o: trigger view top-left origin
     *
     * p: popup preview ([PopupEntryUi]) top-left origin
     *
     * c: container view top-left origin
     *
     * Applying only `1.` parts of both X and Y offset, the origin should transform from `o` to `p`.
     * `2.` parts of both offset transform it from `p` to `c`.
     */
    override val offsetX = ((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)
    override val offsetY = (triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1))

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    /**
     * row with smaller index displays at bottom.
     * for example, keyOrders array:
     * ```
     * [[2, 0, 1, 3], [6, 4, 5, 7]]
     * ```
     * displays as
     * ```
     * | 6 | 4 | 5 | 7 |
     * | 2 | 0 | 1 | 3 |
     * ```
     * in which `0` indicates default focus
     */
    private val keyOrders = Array(rowCount) { row ->
        if (grid > 0) IntArray(columnCount) { col -> (rowCount - 1 - row) * columnCount + col }
        else IntArray(columnCount) { col -> row * columnCount + columnOrder[col] }
    }

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private val keyUis = labels.map {
        PopupKeyUi(ctx, theme, it)
    }

    init {
        markFocus(focusedIndex, animate = false)
    }

    private val focusInterpolator = android.view.animation.OvershootInterpolator(3f)

    override val root = verticalLayout root@{
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(3f)
        val pad = dp(6)
        setPadding(pad, 0, pad, 0)
        // add rows in reverse order, because newly added view shows at bottom
        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            add(horizontalLayout row@{
                for (j in 0 until columnCount) {
                    val keyUi = keyUis.getOrNull(order[j])
                    if (keyUi == null) {
                        // align columns to right (end) when first column is empty, eg.
                        // |   | 6 | 5 | 4 |(no free space)
                        // | 3 | 2 | 1 | 0 |(no free space)
                        gravity = if (j == 0) gravityEnd else gravityStart
                    } else {
                        add(keyUi.root, lParams(keyWidth, keyHeight))
                    }
                }
            }, lParams(width = matchParent))
        }
    }

    private fun markFocus(index: Int, animate: Boolean = true) {
        keyUis.getOrNull(index)?.apply {
            root.background = focusBackground
            textView.setTextColor(theme.genericActiveForegroundColor)
            // Gboard style: the focused character pops up a little, the highlight springs in
            textView.animate().cancel()
            root.animate().cancel()
            if (animate) {
                // the highlight grows to its final size without overshooting, so the row never clips it
                root.scaleX = 0.6f
                root.scaleY = 0.6f
                root.animate().scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                textView.animate().scaleX(FocusScale).scaleY(FocusScale).translationY(-ctx.dp(2f))
                    .setDuration(200).setInterpolator(focusInterpolator).start()
            } else {
                textView.scaleX = FocusScale
                textView.scaleY = FocusScale
                textView.translationY = -ctx.dp(2f)
            }
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            textView.setTextColor(theme.popupTextColor)
            textView.animate().cancel()
            root.animate().cancel()
            root.scaleX = 1f
            root.scaleY = 1f
            textView.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(140).start()
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        // move to next row when gesture moves above 30% from bottom of current row
        var newRow = rowCount - (y / keyHeight - 0.2).roundToInt()
        // move to next column when gesture moves out of current column
        var newColumn = floor(x / keyWidth).toInt()
        // retain focus when gesture moves between ±2 rows/columns of range
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus < keyUis.size && newFocus != focusedIndex) {
            org.fcitx.fcitx5.android.data.InputFeedbacks.hapticFeedback(root)
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    companion object {
        private const val FocusScale = 1.25f
    }

    override fun onTrigger(): KeyAction? {
        val key = keys.getOrNull(focusedIndex) ?: return null
        // multi-character entries (emoticons, "……") are committed as text
        return if (key.codePointCount(0, key.length) > 1) KeyAction.CommitAction(key)
        else KeyAction.FcitxKeyAction(key)
    }

}
