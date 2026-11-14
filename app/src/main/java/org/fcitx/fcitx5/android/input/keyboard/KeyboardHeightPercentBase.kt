/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Point
import android.os.Build
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.utils.windowManager
import kotlin.math.roundToInt

enum class KeyboardHeightPercentBase(override val stringRes: Int) : ManagedPreferenceEnum {
    // Follow Gboard's physical sizing (~40.4mm portrait / ~28.5mm landscape body + header in dp)
    // using displayMetrics.ydpi, so phones with identical densityDpi (e.g. both 480dpi) but
    // different physical DPI match Gboard's height on each device.
    Gboard(R.string.ifb_height_base_gboard),
    DisplayMetrics(R.string.display_metrics),
    RealSize(R.string.real_size);

    /** 计算给定百分比与屏幕方向下的按键区域高度（像素）。 */
    fun computeKeyboardHeightPx(context: Context, percent: Int, landscape: Boolean): Int {
        val dm = context.resources.displayMetrics
        if (this == Gboard) {
            // 横屏下读取竖屏高度（悬浮模式）时切换为 xdpi，与竖屏实测 ydpi 一致
            val currentLandscape = dm.widthPixels > dm.heightPixels && dm.heightPixels > 0
            val targetYdpi = if (currentLandscape == landscape) dm.ydpi else dm.xdpi
            return computeGboardHeightPx(dm.density, targetYdpi, percent, landscape)
        }
        val (w, h) = when (this) {
            DisplayMetrics -> dm.widthPixels to dm.heightPixels
            RealSize -> Point().also {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    context.windowManager.defaultDisplay
                }.getRealSize(it)
            }.let { it.x to it.y }
            Gboard -> error("unreachable")
        }
        val base = if (landscape) minOf(w, h) else maxOf(w, h)
        return base * percent / 100
    }

    companion object {
        // 实测 Gboard：Xiaomi 14 (460.4 ydpi) 竖屏 Header 44dp + Body 732px (40.38mm)；
        // vivo (397.0 ydpi) 竖屏 Header 44dp + Body 633px (40.49mm)；横屏 Header 41dp + Body 28.5mm
        private const val GBOARD_PORTRAIT_HEADER_DP = 44f
        private const val GBOARD_PORTRAIT_BODY_MM = 40.4f
        private const val GBOARD_LANDSCAPE_HEADER_DP = 41f
        private const val GBOARD_LANDSCAPE_BODY_MM = 28.5f

        // IFboard 竖屏 chrome：工具栏 44dp + 顶部呼吸留白（约 2~3dp）+ 底部留白 5dp = 52dp（与 Gboard 顶沿到第 1 行留白对齐）
        private const val IFB_PORTRAIT_CHROME_DP = 52f
        private const val IFB_LANDSCAPE_CHROME_DP = 40f
        private const val MM_PER_INCH = 25.4f

        /**
         * 复刻 Gboard 按物理尺寸（`ydpi`）+ 逻辑密度（`density`）计算键盘默认高度的方式，
         * 使不同物理 DPI 手机上 IFboard 默认总高度均与 Gboard 保持一致。
         */
        internal fun computeGboardHeightPx(
            density: Float,
            ydpi: Float,
            percent: Int,
            landscape: Boolean
        ): Int {
            val effectiveYdpi = if (ydpi.isFinite() && ydpi in 72f..960f) ydpi else density * 160f
            val gboardHeaderDp = if (landscape) GBOARD_LANDSCAPE_HEADER_DP else GBOARD_PORTRAIT_HEADER_DP
            val gboardBodyMm = if (landscape) GBOARD_LANDSCAPE_BODY_MM else GBOARD_PORTRAIT_BODY_MM
            val ifbChromeDp = if (landscape) IFB_LANDSCAPE_CHROME_DP else IFB_PORTRAIT_CHROME_DP
            val defaultPercent = if (landscape) 49f else 27f

            val gboardTotalPx = gboardHeaderDp * density + (gboardBodyMm / MM_PER_INCH) * effectiveYdpi
            val defaultKeyAreaPx = (gboardTotalPx - ifbChromeDp * density).coerceAtLeast(80f * density)
            return (defaultKeyAreaPx * (percent / defaultPercent)).roundToInt()
        }
    }
}
