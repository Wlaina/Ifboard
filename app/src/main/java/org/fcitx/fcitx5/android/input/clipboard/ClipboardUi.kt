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
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val imageRecyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
        visibility = View.GONE
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

    /** Gboard style: separate text and screenshot columns when screenshots are present */
    var hasImages = false
        set(value) {
            field = value
            imageRecyclerView.visibility = if (value) View.VISIBLE else View.GONE
            lastState?.let { switchUiByState(it, animate = false) }
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