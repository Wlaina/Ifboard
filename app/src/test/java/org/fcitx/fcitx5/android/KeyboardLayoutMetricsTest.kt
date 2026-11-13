/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutMetricsTest {

    @Test
    fun toolbar_and_top_padding_align_with_gboard_portrait_chrome() {
        assertEquals(44, KeyboardLayoutMetrics.barHeightDp(landscape = false))
        assertEquals(40, KeyboardLayoutMetrics.barHeightDp(landscape = true))

        assertEquals(2f, KeyboardLayoutMetrics.topPaddingDp(landscape = false, effectiveVMarginDp = 6f), 0.01f)
        assertEquals(3f, KeyboardLayoutMetrics.topPaddingDp(landscape = false, effectiveVMarginDp = 5f), 0.01f)
        assertEquals(3.32f, KeyboardLayoutMetrics.topPaddingDp(landscape = false, effectiveVMarginDp = 4.68f), 0.01f)
        assertEquals(0f, KeyboardLayoutMetrics.topPaddingDp(landscape = true, effectiveVMarginDp = 4f), 0.01f)
    }

    @Test
    fun wide_screen_portrait_preserves_custom_and_default_metrics() {
        val defaults = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 396f,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = 3f,
            prefVMarginDp = 6f,
            prefRadiusDp = 7f
        )
        assertEquals(3f, defaults.hMarginDp, 0.01f)
        assertEquals(6f, defaults.vMarginDp, 0.01f)
        assertEquals(7f, defaults.radiusDp, 0.01f)

        val custom = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 396f,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = 4f,
            prefVMarginDp = 8f,
            prefRadiusDp = 10f
        )
        assertEquals(4f, custom.hMarginDp, 0.01f)
        assertEquals(8f, custom.vMarginDp, 0.01f)
        assertEquals(10f, custom.radiusDp, 0.01f)
    }

    @Test
    fun compact_360dp_screen_tightens_margins_and_radius_like_gboard() {
        val compact = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 356f,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = 3f,
            prefVMarginDp = 6f,
            prefRadiusDp = 7f
        )
        assertEquals(2.34f, compact.hMarginDp, 0.01f)
        assertEquals(5f, compact.vMarginDp, 0.01f)
        assertEquals(6f, compact.radiusDp, 0.01f)
    }

    @Test
    fun floating_narrow_window_scales_vertical_margin_to_avoid_tall_keys() {
        val floatingDefault = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 330f,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = 3f,
            prefVMarginDp = 6f,
            prefRadiusDp = 7f
        )
        assertEquals(2.34f, floatingDefault.hMarginDp, 0.01f)
        assertEquals(4.68f, floatingDefault.vMarginDp, 0.01f)
        assertEquals(6f, floatingDefault.radiusDp, 0.01f)

        val floatingLegacy = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 330f,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = 3f,
            prefVMarginDp = 5f,
            prefRadiusDp = 6f
        )
        assertEquals(4.65f, floatingLegacy.vMarginDp, 0.01f)
    }

    @Test
    fun landscape_and_single_row_skip_multi_row_vertical_shrinkage() {
        val landscape = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 330f,
            landscape = true,
            rowCount = 4,
            prefHMarginDp = 3f,
            prefVMarginDp = 4f,
            prefRadiusDp = 7f
        )
        assertEquals(3f, landscape.hMarginDp, 0.01f)
        assertEquals(4f, landscape.vMarginDp, 0.01f)
        assertEquals(7f, landscape.radiusDp, 0.01f)

        val singleRow = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = 330f,
            landscape = false,
            rowCount = 1,
            prefHMarginDp = 3f,
            prefVMarginDp = 6f,
            prefRadiusDp = 7f
        )
        assertEquals(2.34f, singleRow.hMarginDp, 0.01f)
        assertEquals(6f, singleRow.vMarginDp, 0.01f)
    }

    @Test
    fun floating_side_padding_clamps_to_5dp_through_7dp() {
        assertEquals(15, KeyboardLayoutMetrics.computeFloatingSidePaddingPx(0, 15, 21))
        assertEquals(18, KeyboardLayoutMetrics.computeFloatingSidePaddingPx(18, 15, 21))
        assertEquals(21, KeyboardLayoutMetrics.computeFloatingSidePaddingPx(30, 15, 21))
    }
}
