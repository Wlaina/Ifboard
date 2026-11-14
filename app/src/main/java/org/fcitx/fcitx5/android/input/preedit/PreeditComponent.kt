/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.Glass
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    companion object {
        const val CORNER_RADIUS_DP = 8f
    }

    private val context by manager.context()
    private val theme by manager.theme()

    val bkgColor: Int by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
    }

    val ui by lazy {
        PreeditUi(context, theme).apply {
            val cornerPx = context.dp(CORNER_RADIUS_DP)
            root.background = GradientDrawable().apply {
                setColor(bkgColor)
                cornerRadii = floatArrayOf(cornerPx, cornerPx, cornerPx, cornerPx, 0f, 0f, 0f, 0f)
            }
            root.setPadding(context.dp(10), context.dp(2), context.dp(10), context.dp(2))
            root.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) return
                    val r = cornerPx.toInt()
                    // 底部向下延伸圆角半径，仅裁剪出上圆下平轮廓以无缝贴合键盘顶边
                    outline.setRoundRect(0, 0, view.width, view.height + r, cornerPx)
                }
            }
            root.clipToOutline = true
            root.visibility = View.INVISIBLE
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.visibility == View.VISIBLE) {
                    v.invalidateOutline()
                    Glass.invalidateBlur(v)
                }
            }
        }
    }

    private var glidePreviewing = false
    private var lastInputPanelData: FcitxEvent.InputPanelEvent.Data? = null

    private fun syncRootVisibility() {
        val visible = ui.visible
        ui.root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (visible) {
            ui.root.invalidateOutline()
            Glass.invalidateBlur(ui.root)
        }
    }

    fun showGlidePreview(text: String) {
        if (text.isNotEmpty()) {
            glidePreviewing = true
            ui.updateOverride(text)
            syncRootVisibility()
            return
        }
        // 非滑行预览期间（例如展开候选栏导致 KeyboardWindow 卸载）不要误清活跃的拼音 preedit
        if (!glidePreviewing) return
        glidePreviewing = false
        val cached = lastInputPanelData
        if (cached != null) {
            ui.update(cached)
        } else {
            ui.updateOverride("")
        }
        syncRootVisibility()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (glidePreviewing && data.preedit.isEmpty() && data.auxUp.isEmpty() && data.auxDown.isEmpty()) {
            return
        }
        glidePreviewing = false
        lastInputPanelData = data
        ui.update(data)
        syncRootVisibility()
    }
}
