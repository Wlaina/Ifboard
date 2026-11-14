/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.ifboard.Bootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TypoCorrectorTest {

    private val dataDir = File("src/main/assets/usr/share/rime-data")
    private val g = KeyboardGeometryForTest.qwerty()

    @Before
    fun setUp() {
        TypoCorrector.setModeForTest(GlideTyping.Mode.Flypy)
        TypoCorrector.clear()
        TypoCorrector.resetOffsets()
    }

    private fun loadDict() {
        assumeTrue(File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Flypy)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Flypy) { File(dataDir, it).inputStream() }
        }
    }

    /** tap on [ch], shifted by (dx, dy) key widths / heights from its center */
    private fun tap(ch: Char, dx: Float = 0f, dy: Float = 0f) {
        val k = ch - 'a'
        TypoCorrector.recordTap(ch, g.cx[k] + dx * g.keyW, g.cy[k] + dy * g.keyH, g)
    }

    @Test
    fun invalid_syllable_near_key_border_is_corrected_first() {
        loadDict()
        // 谷歌 = guge; the "u" press landed on the left edge of "i", and "gi" is not a 小鹤 syllable
        tap('g'); tap('i', dx = -0.42f); tap('g'); tap('e')
        val s = TypoCorrector.suggest(emptyArray())
        assertTrue(s.isNotEmpty())
        assertEquals("guge", s[0].code)
        assertTrue(s[0].promotePrimary)
    }

    @Test
    fun correction_is_promoted_even_when_rime_lists_it_lower() {
        loadDict()
        // device case: Rime's own corrector shows 或个 | 谷歌 | ... for "gige"; 谷歌 must still go first
        tap('g'); tap('i', dx = -0.42f); tap('g'); tap('e')
        val word = GlideTyping.flypyDict!!.let { it.outputs[it.codeToIdx!!["guge"]!!] }
        val s = TypoCorrector.suggest(arrayOf(CandidateWord("", "或个", ""), CandidateWord("", word, "")))
        assertEquals(word, s.firstOrNull()?.word)
        assertTrue(s[0].promotePrimary)
    }

    @Test
    fun two_slips_in_one_word_are_both_corrected() {
        loadDict()
        // 谷歌 typed as "gihe": i for u and h for g, both near the shared key border
        tap('g'); tap('i', dx = -0.40f); tap('h', dx = -0.42f); tap('e')
        val s = TypoCorrector.suggest(emptyArray())
        assertTrue(s.any { it.code == "guge" })
    }

    @Test
    fun clean_centered_taps_get_no_corrections() {
        loadDict()
        tap('g'); tap('u'); tap('g'); tap('e')
        val word = GlideTyping.flypyDict!!.let { it.outputs[it.codeToIdx!!["guge"]!!] }
        val s = TypoCorrector.suggest(arrayOf(CandidateWord("", word, "")))
        assertTrue(s.isEmpty())
    }

    @Test
    fun single_valid_character_is_never_touched() {
        loadDict()
        tap('n'); tap('i', dx = 0.45f)
        assertTrue(TypoCorrector.suggest(emptyArray()).isEmpty())
    }

    @Test
    fun correction_teaches_the_touch_model() {
        loadDict()
        val before = TypoCorrector.dumpOffsets()
        repeat(5) {
            tap('g'); tap('i', dx = -0.40f); tap('g'); tap('e')
            TypoCorrector.onCorrectionSelected("guge")
        }
        val after = TypoCorrector.dumpOffsets().split(',').map { it.toFloat() }
        // the "u" key learned that this user hits it towards its right side (offset > 0)
        assertTrue(after['u' - 'a'] > 0.05f)
        assertFalse(before == TypoCorrector.dumpOffsets())
        // and the stored model round-trips
        val dumped = TypoCorrector.dumpOffsets()
        TypoCorrector.resetOffsets()
        TypoCorrector.loadOffsets(dumped)
        assertEquals(dumped, TypoCorrector.dumpOffsets())
    }

    private fun loadPinyin() {
        assumeTrue(File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Pinyin)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Pinyin) { File(dataDir, it).inputStream() }
        }
        TypoCorrector.setModeForTest(GlideTyping.Mode.Pinyin)
    }

    private fun pinyinWord(code: String) =
        GlideTyping.dictFor(GlideTyping.Mode.Pinyin)!!.let { it.outputs[it.codeToIdx!![code]!!] }

    @Test
    fun full_pinyin_slip_that_breaks_the_syllables_is_corrected_first() {
        loadPinyin()
        // 你好 = nihao, the "a" press landed on the left edge of "s": "nihso" cannot be split into pinyin
        for (c in "nih") tap(c)
        tap('s', dx = -0.42f)
        tap('o')
        val s = TypoCorrector.suggest(emptyArray())
        assertEquals("nihao", s.firstOrNull()?.code)
        assertTrue(s[0].promotePrimary)
    }

    @Test
    fun full_pinyin_long_word_with_two_slips() {
        loadPinyin()
        // 中国 = zhongguo typed as "zhonfgui": the first g hit near the right edge of f, the last o near the right edge of i
        for (c in "zhon") tap(c)
        tap('f', dx = 0.42f)
        for (c in "gu") tap(c)
        tap('i', dx = 0.42f)
        val s = TypoCorrector.suggest(emptyArray())
        assertTrue(s.any { it.code == "zhongguo" })
    }

    @Test
    fun full_pinyin_clean_word_and_unfinished_syllable_are_left_alone() {
        loadPinyin()
        for (c in "nihao") tap(c)
        assertTrue(TypoCorrector.suggest(arrayOf(CandidateWord("", pinyinWord("nihao"), ""))).isEmpty())
        TypoCorrector.clear()
        // still typing the second syllable of 中国
        for (c in "zhongg") tap(c)
        assertTrue(TypoCorrector.suggest(arrayOf(CandidateWord("", "中", ""))).isEmpty())
    }

    @Test
    fun full_pinyin_segmentation() {
        loadPinyin()
        assertEquals(2, TypoCorrector.pinyinSyllableCount("zhongguo"))
        assertEquals(-1, TypoCorrector.pinyinSyllableCount("nihso"))
        assertEquals(0, TypoCorrector.pinyinSyllableCount("zhongg", allowPartialLast = true))
        assertEquals(-1, TypoCorrector.pinyinSyllableCount("nihso", allowPartialLast = true))
    }

    @Test
    fun rime_correction_patch_is_added_once_and_keeps_user_files() {
        val dir = Files.createTempDirectory("rime").toFile()
        try {
            val flypy = File(dir, "double_pinyin_flypy.custom.yaml")
            flypy.writeText("patch:\n  speller/algebra:\n    - erase/^xx$/") // no trailing newline
            val other = File(dir, "rime_ice.custom.yaml")
            Bootstrap.ensureRimeCorrection(dir)
            assertEquals(
                "patch:\n  speller/algebra:\n    - erase/^xx$/\n  translator/enable_correction: true\n",
                flypy.readText()
            )
            assertEquals("patch:\n  translator/enable_correction: true\n", other.readText())
            // a user who removed it again is respected
            flypy.writeText("patch:\n  translator/enable_correction: false\n")
            other.delete()
            Bootstrap.ensureRimeCorrection(dir)
            assertEquals("patch:\n  translator/enable_correction: false\n", flypy.readText())
            assertFalse(other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
