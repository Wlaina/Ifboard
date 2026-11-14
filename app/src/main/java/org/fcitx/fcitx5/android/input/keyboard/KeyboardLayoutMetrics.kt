/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePrefs

/**
 * 统一计算与 Gboard 对齐的键盘工具栏高度、顶部呼吸留白，
 * 以及窄屏/悬浮窗口下的自适应按键边距与圆角大小。
 */
object KeyboardLayoutMetrics {
    const val BAR_HEIGHT_PORTRAIT_DP = 44
    const val BAR_HEIGHT_LANDSCAPE_DP = 40

    // 实测 Gboard 竖屏：顶沿到第 1 行键帽顶边约 52dp（44dp 工具栏 + 8dp 顶部留白）
    private const val PORTRAIT_TOP_TO_FIRST_ROW_DP = 52f
    private const val STANDARD_MIN_WIDTH_DP = 380f
    private const val FLOATING_MAX_WIDTH_DP = 345f
    private const val COMPACT_H_MARGIN_SCALE = 0.78f
    private const val COMPACT_V_MARGIN_SCALE = 5f / 6f
    private const val FLOATING_V_MARGIN_SCALE = 0.78f
    private const val COMPACT_RADIUS_SCALE = 6f / 7f

    data class KeyMetricsDp(
        val hMarginDp: Float,
        val vMarginDp: Float,
        val radiusDp: Float
    )

    /** 工具栏高度（dp）：竖屏 44dp 对齐 Gboard，横屏保持 40dp。 */
    fun barHeightDp(landscape: Boolean): Int =
        if (landscape) BAR_HEIGHT_LANDSCAPE_DP else BAR_HEIGHT_PORTRAIT_DP

    /**
     * 工具栏下方、按键区上方的顶部呼吸留白（dp）。
     * 与首行按键上边距合计约为 8dp（总计顶沿到第 1 行键帽约 52dp），横屏固定为 0dp。
     */
    fun topPaddingDp(landscape: Boolean, effectiveVMarginDp: Float): Float {
        if (landscape) return 0f
        val barDp = barHeightDp(false).toFloat()
        return (PORTRAIT_TOP_TO_FIRST_ROW_DP - barDp - effectiveVMarginDp).coerceIn(2f, 4f)
    }

    /** 悬浮模式内部左右留白（px）。 */
    fun computeFloatingSidePaddingPx(
        baseSidePaddingPx: Int,
        dp5Px: Int,
        dp7Px: Int
    ): Int = baseSidePaddingPx.coerceIn(dp5Px, dp7Px)

    /** 悬浮窗口宽度（px）：横竖屏统一按屏幕短边（竖屏宽度）的 85% 计算并限制在 [280dp, 344dp]，保持横竖屏比例一致。 */
    fun computeFloatingWidthPx(
        availableWidthPx: Int,
        displayWidthPx: Int,
        displayHeightPx: Int,
        dp280Px: Int,
        dp344Px: Int
    ): Int {
        val portraitWidthPx = minOf(displayWidthPx, displayHeightPx).let {
            if (it > 0) it else availableWidthPx
        }
        val minBoundPx = minOf(dp280Px, availableWidthPx)
        val maxBoundPx = maxOf(minBoundPx, dp344Px)
        return (portraitWidthPx * 0.85f).toInt().coerceIn(minBoundPx, maxBoundPx)
    }

    /** 悬浮窗口按键区域高度（px）：横竖屏统一基于竖屏按键区高度的 0.84x 计算。 */
    fun computeFloatingKeyboardHeightPx(
        portraitKeyboardHeightPx: Int,
        dp150Px: Int
    ): Int = (portraitKeyboardHeightPx * 0.84f).toInt()
        .coerceAtLeast(minOf(portraitKeyboardHeightPx, dp150Px))

    /**
     * 根据容器实际宽度（dp）和行数解析按键有效边距与圆角：
     * - 横屏或宽屏（>= 380dp）：1:1 遵循设置值
     * - 窄屏竖屏（345~379dp，如 360dp 机型）：横向收窄至 ~0.78x，纵向默认 6dp 档收至 5dp 档，圆角 7dp 收至 6dp
     * - 悬浮窄窗（< 345dp，多行键盘）：横向 ~0.78x、纵向 ~0.78x，避免窄窗内键帽显得细长
     */
    fun resolveKeyMetricsDp(
        containerWidthDp: Float,
        landscape: Boolean,
        rowCount: Int,
        prefHMarginDp: Float,
        prefVMarginDp: Float,
        prefRadiusDp: Float
    ): KeyMetricsDp {
        if (landscape || containerWidthDp <= 0f || containerWidthDp >= STANDARD_MIN_WIDTH_DP) {
            return KeyMetricsDp(prefHMarginDp, prefVMarginDp, prefRadiusDp)
        }
        val hMargin = prefHMarginDp * COMPACT_H_MARGIN_SCALE
        val radius = prefRadiusDp * COMPACT_RADIUS_SCALE
        val vMargin = when {
            rowCount <= 1 -> prefVMarginDp
            containerWidthDp < FLOATING_MAX_WIDTH_DP -> {
                if (prefVMarginDp > 5f) prefVMarginDp * FLOATING_V_MARGIN_SCALE
                else prefVMarginDp * 0.93f
            }
            else -> {
                if (prefVMarginDp > 5f) (prefVMarginDp * COMPACT_V_MARGIN_SCALE).coerceAtLeast(5f)
                else prefVMarginDp
            }
        }
        return KeyMetricsDp(hMargin, vMargin, radius)
    }

    /**
     * 从 v1.2.3 及更早默认间距升级时，仅在用户仍处于旧默认值（vMargin=5, radius=6）时迁移一次，
     * 使已装机设备同步获得与 Gboard 对齐的自适应基准，且不覆盖用户后续手动调整。
     */
    fun migrateLegacyDefaultsIfNeeded(
        appPrefs: AppPrefs,
        themePrefs: ThemePrefs,
        rawPrefs: SharedPreferences
    ) {
        val versionPref = appPrefs.internal.layoutMetricsVersion
        if (versionPref.getValue() >= 1) return
        if (rawPrefs.contains(themePrefs.keyVerticalMargin.key) && themePrefs.keyVerticalMargin.getValue() == 5) {
            themePrefs.keyVerticalMargin.setValue(6)
        }
        if (rawPrefs.contains(themePrefs.keyRadius.key) && themePrefs.keyRadius.getValue() == 6) {
            themePrefs.keyRadius.setValue(7)
        }
        versionPref.setValue(1)
    }
}
