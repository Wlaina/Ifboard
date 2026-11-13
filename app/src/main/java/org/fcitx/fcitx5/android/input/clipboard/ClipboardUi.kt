/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.view.View
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val textRecyclerView = recyclerView {
        clipToPadding = false
        bottomPadding = dp(6)
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val imageRecyclerView = recyclerView {
        clipToPadding = false
        bottomPadding = dp(6)
        addItemDecoration(SpacesItemDecoration(dp(4)))
        visibility = View.GONE
    }

    var bottomInset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            val pad = ctx.dp(6) + value
            textRecyclerView.bottomPadding = pad
            imageRecyclerView.bottomPadding = pad
        }

    val columnsContainer = horizontalLayout {
        add(textRecyclerView, lParams(0, matchParent) { weight = 1f })
        add(imageRecyclerView, lParams(0, matchParent) { weight = 1f })
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator = view(::ViewAnimator) {
        add(columnsContainer, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    // Gboard style: clipboard on/off switch next to the action button
    val listeningSwitch = android.widget.Switch(ctx).apply {
        val isLightTheme = androidx.core.graphics.ColorUtils.calculateLuminance(theme.keyTextColor) < 0.5
        val uncheckedTrack = if (isLightTheme) 0xFFD4CBC0.toInt() else 0xFF54534D.toInt()
        val thumbColor = if (isLightTheme) android.graphics.Color.WHITE else 0xFFFAF9F5.toInt()
        org.fcitx.fcitx5.android.ui.common.WarmPaperUi.applyPillSwitchDrawables(
            this,
            activeTrackColor = theme.genericActiveBackgroundColor,
            inactiveTrackColor = uncheckedTrack,
            thumbColor = thumbColor
        )
        contentDescription = "剪贴板开关"
    }

    val extension = horizontalLayout {
        gravity = android.view.Gravity.CENTER_VERTICAL
        add(listeningSwitch, lParams(wrapContent, dp(40)))
        add(deleteAllButton, lParams(dp(40), dp(40)))
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private var lastState: ClipboardStateMachine.State? = null

    /** 有文字记录或无图片时显示左栏；仅有截图/图片时隐藏空白文字栏 */
    var hasText = true
        set(value) {
            if (field == value) return
            field = value
            updateColumnVisibility()
        }

    /** Gboard style: separate text and screenshot columns when screenshots are present */
    var hasImages = false
        set(value) {
            if (field == value) return
            field = value
            updateColumnVisibility()
            lastState?.let { switchUiByState(it, animate = false) }
        }

    private fun updateColumnVisibility() {
        textRecyclerView.visibility = if (hasText || !hasImages) View.VISIBLE else View.GONE
        imageRecyclerView.visibility = if (hasImages) View.VISIBLE else View.GONE
        syncColumnLayoutManagers()
    }

    /**
     * 仅文本时文本区采用 2 列瀑布流；图文并存时文本与图片各占 1 列；
     * 仅图片时图片区采用 2 列瀑布流，切换过程双向自适应。
     */
    fun syncColumnLayoutManagers() {
        if (!hasImages) {
            if (textRecyclerView.layoutManager !is androidx.recyclerview.widget.StaggeredGridLayoutManager) {
                textRecyclerView.recycledViewPool.clear()
                textRecyclerView.layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(
                    2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL
                )
            }
        } else if (textRecyclerView.layoutManager !is androidx.recyclerview.widget.LinearLayoutManager) {
            textRecyclerView.recycledViewPool.clear()
            textRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        }

        if (hasImages && !hasText) {
            if (imageRecyclerView.layoutManager !is androidx.recyclerview.widget.StaggeredGridLayoutManager) {
                imageRecyclerView.recycledViewPool.clear()
                imageRecyclerView.layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(
                    2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL
                )
            }
        } else if (imageRecyclerView.layoutManager !is androidx.recyclerview.widget.LinearLayoutManager) {
            imageRecyclerView.recycledViewPool.clear()
            imageRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        }
    }

    fun switchUiByState(state: ClipboardStateMachine.State, animate: Boolean = true) {
        lastState = state
        Timber.d("Switch clipboard to $state")
        if (animate && !disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = if (hasImages) 0 else 1
                setDeleteButtonShown(hasImages)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
            }
        }
    }
}