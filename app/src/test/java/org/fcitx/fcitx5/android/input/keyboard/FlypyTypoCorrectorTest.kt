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

class FlypyTypoCorrectorTest {

    private val dataDir = File("src/main/assets/usr/share/rime-data")
    private val g = KeyboardGeometryForTest.qwerty()

    @Before
    fun setUp() {
        FlypyTypoCorrector.clear()
        FlypyTypoCorrector.resetOffsets()
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
        FlypyTypoCorrector.recordTap(ch, g.cx[k] + dx * g.keyW, g.cy[k] + dy * g.keyH, g)
    }

    @Test
    fun invalid_syllable_near_key_border_is_corrected_first() {
        loadDict()
        // 谷歌 = guge; the "u" press landed on the right edge of "i"... i.e. "gi" is not a 小鹤 syllable
        tap('g'); tap('i', dx = -0.42f); tap('g'); tap('e')
        val s = FlypyTypoCorrector.suggest(emptyArray())
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
        val s = FlypyTypoCorrector.suggest(arrayOf(CandidateWord("", "或个", ""), CandidateWord("", word, "")))
        assertEquals(word, s.firstOrNull()?.word)
        assertTrue(s[0].promotePrimary)
    }

    @Test
    fun two_slips_in_one_word_are_both_corrected() {
        loadDict()
        // 谷歌 typed as "gihe": i for u and h for g, both near the shared key border
        tap('g'); tap('i', dx = -0.40f); tap('h', dx = -0.42f); tap('e')
        val s = FlypyTypoCorrector.suggest(emptyArray())
        assertTrue(s.any { it.code == "guge" })
    }

    @Test
    fun clean_centered_taps_get_no_corrections() {
        loadDict()
        tap('g'); tap('u'); tap('g'); tap('e')
        val word = GlideTyping.flypyDict!!.let { it.outputs[it.codeToIdx!!["guge"]!!] }
        val s = FlypyTypoCorrector.suggest(arrayOf(CandidateWord("", word, "")))
        assertTrue(s.isEmpty())
    }

    @Test
    fun single_valid_character_is_never_touched() {
        loadDict()
        tap('n'); tap('i', dx = 0.45f)
        assertTrue(FlypyTypoCorrector.suggest(emptyArray()).isEmpty())
    }

    @Test
    fun correction_teaches_the_touch_model() {
        loadDict()
        val before = FlypyTypoCorrector.dumpOffsets()
        repeat(5) {
            tap('g'); tap('i', dx = -0.40f); tap('g'); tap('e')
            FlypyTypoCorrector.onCorrectionSelected("guge")
        }
        val after = FlypyTypoCorrector.dumpOffsets().split(',').map { it.toFloat() }
        // the "u" key learned that this user hits it towards its right side (offset > 0)
        assertTrue(after['u' - 'a'] > 0.05f)
        assertFalse(before == FlypyTypoCorrector.dumpOffsets())
        // and the stored model round-trips
        val dumped = FlypyTypoCorrector.dumpOffsets()
        FlypyTypoCorrector.resetOffsets()
        FlypyTypoCorrector.loadOffsets(dumped)
        assertEquals(dumped, FlypyTypoCorrector.dumpOffsets())
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
