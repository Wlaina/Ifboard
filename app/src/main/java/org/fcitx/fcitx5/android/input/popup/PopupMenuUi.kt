/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.imageDrawable
import kotlin.math.floor

class PopupMenuUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val items: Array<KeyDef.Popup.Menu.Item>
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    private val keySize = ctx.dp(48)

    // Gboard style: icons on a pill container, focused icon on a round accent background
    private val inactiveBackground: android.graphics.drawable.Drawable? = null

    private val activeBackground = InsetDrawable(
        ShapeDrawable(OvalShape()).apply {
            paint.color = theme.genericActiveBackgroundColor
        },
        (keySize - ctx.dp(40)) / 2
    )

    private val idleTint = theme.keyTextColor
    private val focusTint = theme.genericActiveForegroundColor

    private val columnCount = items.size
    private val focusColumn =
        calcInitialFocusedColumn(columnCount, keySize, outerBounds, triggerBounds)

    override val offsetX = ((triggerBounds.width() - keySize) / 2) - (keySize * focusColumn)
    override val offsetY = ctx.dp(-52)

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val keyViews = items.map {
        imageView {
            background = inactiveBackground
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageDrawable = drawable(it.icon)!!.mutate().apply {
                setTint(idleTint)
            }
        }
    }

    init {
        markFocus(focusedIndex)
    }

    override val root = horizontalLayout root@{
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = keySize / 2f
            if (org.fcitx.fcitx5.android.input.Glass.enabled) {
                setColor(org.fcitx.fcitx5.android.input.Glass.tint(theme.popupBackgroundColor, 0.78f))
                setStroke(ctx.dp(1), 0x1FFFFFFF)
            } else setColor(theme.popupBackgroundColor)
        }
        elevation = ctx.dp(3f)
        val pad = ctx.dp(4)
        setPadding(pad, 0, pad, 0)
        for (i in 0 until columnCount) {
            val view = keyViews[columnOrder[i]]
            add(view, lParams(keySize, keySize))
        }
    }

    private fun markFocus(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = activeBackground
            drawable?.setTint(focusTint)
        }
    }

    private fun markInactive(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = inactiveBackground
            drawable?.setTint(idleTint)
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        var newColumn = floor(x / keySize).toInt()
        if (newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = columnOrder[newColumn]
        if (newFocus < keyViews.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.action
}
