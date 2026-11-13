package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHeightTest {

    @Test
    fun `portrait matches Gboard physical height across different ydpi at same 480dpi`() {
        // 小米 14 (1200x2670, density=3.0, ydpi=460.407)：Gboard Header 132 + Body 732 = 864px；扣除工具栏与上下留白 156px -> 708px
        val xiaomiKeyArea = KeyboardHeightPercentBase.computeGboardHeightPx(
            density = 3.0f,
            ydpi = 460.407f,
            percent = 27,
            landscape = false
        )
        assertEquals(708, xiaomiKeyArea)

        // vivo (1080x2376, density=3.0, ydpi=397.042)：Gboard Header 132 + Body 633 = 765px；扣除 156px -> 608px
        val vivoKeyArea = KeyboardHeightPercentBase.computeGboardHeightPx(
            density = 3.0f,
            ydpi = 397.042f,
            percent = 27,
            landscape = false
        )
        assertEquals(608, vivoKeyArea)
        assertTrue(xiaomiKeyArea > vivoKeyArea)
    }

    @Test
    fun `landscape matches Gboard physical height`() {
        // 小米 14 横屏：Gboard Header 123 + Body 516 = 639px；扣除工具栏 120px -> 519px
        assertEquals(
            519,
            KeyboardHeightPercentBase.computeGboardHeightPx(
                density = 3.0f,
                ydpi = 459.728f,
                percent = 49,
                landscape = true
            )
        )
        assertEquals(
            449,
            KeyboardHeightPercentBase.computeGboardHeightPx(
                density = 3.0f,
                ydpi = 397.565f,
                percent = 49,
                landscape = true
            )
        )
    }

    @Test
    fun `invalid ydpi falls back cleanly to logical density`() {
        val fromZero = KeyboardHeightPercentBase.computeGboardHeightPx(2.0f, 0f, 27, landscape = false)
        val fromNaN = KeyboardHeightPercentBase.computeGboardHeightPx(2.0f, Float.NaN, 27, landscape = false)
        val fromNominal = KeyboardHeightPercentBase.computeGboardHeightPx(2.0f, 320f, 27, landscape = false)
        assertEquals(493, fromNominal)
        assertEquals(fromNominal, fromZero)
        assertEquals(fromNominal, fromNaN)
    }

    @Test
    fun `percent scaling remains linear`() {
        val base = KeyboardHeightPercentBase.computeGboardHeightPx(2.0f, 320f, 27, landscape = false)
        val taller = KeyboardHeightPercentBase.computeGboardHeightPx(2.0f, 320f, 54, landscape = false)
        assertEquals(base * 2, taller)
    }
}
