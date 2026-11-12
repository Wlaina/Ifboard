/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 竖向滑选语言气泡（暖纸 · 液态玻璃）：对齐按键长按弹窗交互与动画
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.Glass
import org.fcitx.fcitx5.android.input.keyboard.GboardFont
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.verticalLayout
import kotlin.math.floor

/**
 * 竖向长按滑选弹窗：用于翻译“原语言/翻译语言”等长按后上下滑动切换，
 * 复用 [PopupComponent] 的液态玻璃背景、弹出/收起回弹动画与震动反馈。
 */
class PopupChoiceMenuUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val items: List<String>,
    private val initialIndex: Int = 0,
    private val onSelect: (Int) -> Unit
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    private val padX = ctx.dp(6)
    private val padY = ctx.dp(6)
    private val gap = ctx.dp(4)

    private val availBelow = (outerBounds.bottom - ctx.dp(4)) - (triggerBounds.bottom + gap)
    private val availAbove = (triggerBounds.top - gap) - (outerBounds.top + ctx.dp(4))
    private val placeBelow = availBelow >= availAbove || availBelow >= items.size * ctx.dp(24) + padY * 2
    private val availHeight = (if (placeBelow) availBelow else availAbove).coerceAtLeast(ctx.dp(120))

    private val rowHeight = ((availHeight - padY * 2) / items.size.coerceAtLeast(1))
        .coerceIn(ctx.dp(24), ctx.dp(32))
    private val containerHeight = padY * 2 + rowHeight * items.size

    private val rowWidth: Int = run {
        val paint = Paint().apply {
            textSize = ctx.resources.displayMetrics.scaledDensity * 14f
            GboardFont.get(ctx)?.let { typeface = it }
        }
        val maxText = items.maxOfOrNull { paint.measureText(it).toInt() } ?: ctx.dp(40)
        maxOf(triggerBounds.width() + ctx.dp(16), maxText + ctx.dp(28), ctx.dp(92))
    }
    private val containerWidth = rowWidth + padX * 2

    override val offsetX: Int = run {
        val desired = (triggerBounds.width() - containerWidth) / 2
        val minOff = (outerBounds.left + ctx.dp(4)) - triggerBounds.left
        val maxOff = (outerBounds.right - ctx.dp(4) - containerWidth) - triggerBounds.left
        desired.coerceIn(minOf(minOff, maxOff), maxOf(minOff, maxOff))
    }

    override val offsetY: Int = if (placeBelow) {
        triggerBounds.height() + gap
    } else {
        -(containerHeight + gap)
    }

    private val activeBackground = GradientDrawable().apply {
        cornerRadius = rowHeight / 2f
        setColor(theme.genericActiveBackgroundColor)
    }
    private val idleTextColor = theme.keyTextColor
    private val activeTextColor = theme.genericActiveForegroundColor

    private var focusedIndex = initialIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    private var hasEnteredList = false
    private var focusAnimate = false

    private val rowViews = items.map { label ->
        TextView(ctx).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setTextColor(idleTextColor)
            GboardFont.get(ctx)?.let { typeface = it }
        }
    }

    override val root = verticalLayout {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(18f)
            if (Glass.enabled) {
                setColor(Glass.tint(theme.popupBackgroundColor, 0.84f))
                setStroke(ctx.dp(1), 0x26FFFFFF)
            } else {
                setColor(theme.popupBackgroundColor)
            }
        }
        elevation = ctx.dp(4f)
        setPadding(padX, padY, padX, padY)
        rowViews.forEach { tv ->
            add(tv, lParams(rowWidth, rowHeight))
        }
    }

    init {
        markFocus(focusedIndex)
    }

    private fun markFocus(index: Int) {
        rowViews.getOrNull(index)?.apply {
            background = activeBackground
            setTextColor(activeTextColor)
            if (focusAnimate) {
                animate().cancel()
                scaleX = 0.88f
                scaleY = 0.84f
                animate().scaleX(1f).scaleY(1f).setDuration(150)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            } else {
                scaleX = 1f
                scaleY = 1f
            }
        }
        focusAnimate = true
    }

    private fun markInactive(index: Int) {
        rowViews.getOrNull(index)?.apply {
            background = null
            setTextColor(idleTextColor)
            animate().cancel()
            animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        if (items.isEmpty()) return true
        val horizPad = ctx.dp(96).toFloat()
        if (x < -horizPad || x > containerWidth + horizPad) {
            onDismissSelf(this)
            return true
        }
        if (placeBelow) {
            if (y < -(triggerBounds.height() + ctx.dp(48)) || y > containerHeight + ctx.dp(56)) {
                onDismissSelf(this)
                return true
            }
            if (!hasEnteredList && y < 0f) {
                return false
            }
            if (y >= 0f) hasEnteredList = true
        } else {
            if (y > containerHeight + triggerBounds.height() + ctx.dp(48) || y < -ctx.dp(56)) {
                onDismissSelf(this)
                return true
            }
            if (!hasEnteredList && y > containerHeight) {
                return false
            }
            if (y <= containerHeight) hasEnteredList = true
        }

        val rawRow = floor((y - padY) / rowHeight).toInt().coerceIn(0, items.lastIndex)
        if (rawRow != focusedIndex) {
            InputFeedbacks.hapticFeedback(root)
            markInactive(focusedIndex)
            markFocus(rawRow)
            focusedIndex = rawRow
        }
        return false
    }

    override fun onTrigger(): KeyAction? {
        if (focusedIndex in items.indices) {
            onSelect(focusedIndex)
        }
        return null
    }
}
