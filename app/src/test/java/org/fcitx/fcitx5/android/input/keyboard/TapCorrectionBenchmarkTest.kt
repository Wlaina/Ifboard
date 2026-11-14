/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * Ifboard: offline benchmark of 小鹤双拼 tap typo correction, old (1.2.4) vs new spatial model.
 * Taps for common words are scattered with gaussian noise; a tap is "typed" as the nearest key.
 * Needs staged rime-data and IFB_BENCH=1, otherwise skipped.
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

class TapCorrectionBenchmarkTest {

    private val dataDir = File("src/main/assets/usr/share/rime-data")

    private fun nearestKey(x: Float, y: Float, g: GlideTyping.Geometry): Char {
        var best = 0
        var bd = Float.MAX_VALUE
        for (k in 0 until 26) {
            val dx = (x - g.cx[k]) / g.keyW
            val dy = (y - g.cy[k]) / g.keyH
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = k }
        }
        return 'a' + best
    }

    private interface Impl {
        fun clear()
        fun tap(ch: Char, x: Float, y: Float, g: GlideTyping.Geometry)
        fun suggest(c: Array<CandidateWord>): List<Pair<String, Boolean>> // code, promoted
    }

    private val legacy = object : Impl {
        override fun clear() = LegacyFlypyTypoCorrector.clear()
        override fun tap(ch: Char, x: Float, y: Float, g: GlideTyping.Geometry) =
            LegacyFlypyTypoCorrector.recordTap(ch, x, y, g)
        override fun suggest(c: Array<CandidateWord>) =
            LegacyFlypyTypoCorrector.suggest(c).map { it.code to it.promotePrimary }
    }

    private val current = object : Impl {
        override fun clear() = TypoCorrector.clear()
        override fun tap(ch: Char, x: Float, y: Float, g: GlideTyping.Geometry) =
            TypoCorrector.recordTap(ch, x, y, g)
        override fun suggest(c: Array<CandidateWord>) =
            TypoCorrector.suggest(c).map { it.code to it.promotePrimary }
    }

    private fun bench(impl: Impl, label: String, noise: Float, codes: List<String>, quiet: Boolean = false): FloatArray {
        val d = GlideTyping.flypyDict!!
        val valid = d.validSyllables!!
        val codeToIdx = d.codeToIdx!!
        val g = KeyboardGeometryForTest.qwerty()
        val rnd = Random(5)
        var err = 0; var fixedFirst = 0; var shown = 0
        var clean = 0; var fpPromote = 0; var fpInsert = 0
        for (code in codes) {
            impl.clear()
            val sb = StringBuilder()
            for (ch in code) {
                val (x, y) = KeyboardGeometryForTest.synthTap(ch, g, rnd, noise)
                val typed = nearestKey(x, y, g)
                sb.append(typed)
                impl.tap(typed, x, y, g)
            }
            val literal = sb.toString()
            val allValid = (0 until literal.length / 2).all { literal.substring(2 * it, 2 * it + 2) in valid }
            // what Rime would show first: the word for the literal code, a composed sentence, or nothing complete
            val rime = when {
                codeToIdx[literal] != null -> arrayOf(CandidateWord("", d.outputs[codeToIdx[literal]!!], ""))
                allValid -> arrayOf(CandidateWord("", "啊".repeat(literal.length / 2), ""))
                else -> emptyArray()
            }
            val sugs = impl.suggest(rime)
            if (literal != code) {
                err++
                if (sugs.firstOrNull()?.let { it.first == code && it.second } == true) fixedFirst++
                if (sugs.any { it.first == code }) shown++
            } else {
                clean++
                if (sugs.any { it.second }) fpPromote++
                if (sugs.isNotEmpty()) fpInsert++
            }
        }
        val r = floatArrayOf(fixedFirst.toFloat() / err, shown.toFloat() / err, fpPromote.toFloat() / clean, fpInsert.toFloat() / clean)
        if (quiet) return r
        println("[tap] $label noise=$noise mistyped=$err fixed@1=${"%.3f".format(fixedFirst.toFloat() / err)} " +
            "shown=${"%.3f".format(shown.toFloat() / err)} | clean=$clean " +
            "falsePromote=${"%.3f".format(fpPromote.toFloat() / clean)} falseInsert=${"%.3f".format(fpInsert.toFloat() / clean)}")
        return r
    }

    @Test
    fun tap_correction_benchmark() {
        assumeTrue(System.getenv("IFB_BENCH") != null && File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Flypy)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Flypy) { File(dataDir, it).inputStream() }
        }
        TypoCorrector.setModeForTest(GlideTyping.Mode.Flypy)
        TypoCorrector.resetOffsets()
        val d = GlideTyping.flypyDict!!
        val ids = d.freq.indices.sortedByDescending { d.freq[it] }
            .filter { d.codes[it].length in 4..8 }
            .take(4000).shuffled(Random(3)).take(600).map { d.codes[it] }
        // two common single characters typed one after the other: Rime composes them, nothing should be "fixed"
        val singles = d.freq.indices.filter { d.codes[it].length == 2 }.sortedByDescending { d.freq[it] }.take(300)
        val sr = Random(9)
        val sentences = (0 until 600).map { d.codes[singles[sr.nextInt(300)]] + d.codes[singles[sr.nextInt(300)]] }
            .filter { d.codeToIdx!![it] == null }
        for (noise in floatArrayOf(0.20f, 0.26f, 0.32f)) {
            bench(legacy, "legacy", noise, ids)
            bench(current, "new   ", noise, ids)
            bench(legacy, "legacy-sentences", noise, sentences)
            bench(current, "new   -sentences", noise, sentences)
        }
        if (System.getenv("IFB_SWEEP") != null) {
            for (ins in floatArrayOf(-1.5f, -0.5f, 0.5f)) for (pd in floatArrayOf(1.5f, 2.5f, 3.5f)) for (pr in floatArrayOf(2f, 3f, 4.5f)) {
                TypoCorrector.INSERT_MIN_RATIO = ins
                TypoCorrector.PROMOTE_MAX_DROP = pd
                TypoCorrector.PROMOTE_MIN_RATIO = pr
                val w = bench(current, "", 0.26f, ids, quiet = true)
                val s2 = bench(current, "", 0.26f, sentences, quiet = true)
                println("[sweep] ins=$ins pd=$pd pr=$pr words fixed@1=${"%.3f".format(w[0])} shown=${"%.3f".format(w[1])} " +
                    "fpIns=${"%.3f".format(w[3])} | sent fixed@1=${"%.3f".format(s2[0])} fpPromote=${"%.3f".format(s2[2])} fpIns=${"%.3f".format(s2[3])}")
            }
        }
    }

    /**
     * 全拼: what Rime would show first is the word for the literal code, a composed sentence when the
     * letters split into pinyin syllables, or nothing complete. No old implementation to compare with:
     * without a corrector nothing is ever fixed, so fixed@1 / shown are the gain and falseX the cost.
     */
    @Test
    fun tap_correction_benchmark_pinyin() {
        assumeTrue(System.getenv("IFB_BENCH") != null && File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Pinyin)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Pinyin) { File(dataDir, it).inputStream() }
        }
        TypoCorrector.setModeForTest(GlideTyping.Mode.Pinyin)
        TypoCorrector.resetOffsets()
        val d = GlideTyping.dictFor(GlideTyping.Mode.Pinyin)!!
        val codeToIdx = d.codeToIdx!!
        val g = KeyboardGeometryForTest.qwerty()
        val words = d.freq.indices.sortedByDescending { d.freq[it] }
            .filter { d.codes[it].length in 4..12 && TypoCorrector.pinyinSyllableCount(d.codes[it]) >= 2 }
            .take(4000).shuffled(Random(3)).take(600).map { d.codes[it] }
        val singles = d.freq.indices.filter { TypoCorrector.pinyinSyllableCount(d.codes[it]) == 1 }
            .sortedByDescending { d.freq[it] }.take(300)
        val sr = Random(9)
        val sentences = (0 until 600).map { d.codes[singles[sr.nextInt(300)]] + d.codes[singles[sr.nextInt(300)]] }
            .filter { codeToIdx[it] == null }
        for (noise in floatArrayOf(0.20f, 0.26f, 0.32f)) {
            for ((label, codes) in listOf("words" to words, "sentences" to sentences)) {
                val rnd = Random(5)
                var err = 0; var fixedFirst = 0; var shown = 0
                var clean = 0; var fpPromote = 0; var fpInsert = 0
                var t = 0L
                for (code in codes) {
                    TypoCorrector.clear()
                    val sb = StringBuilder()
                    for (ch in code) {
                        val (x, y) = KeyboardGeometryForTest.synthTap(ch, g, rnd, noise)
                        val typed = nearestKey(x, y, g)
                        sb.append(typed)
                        TypoCorrector.recordTap(typed, x, y, g)
                    }
                    val literal = sb.toString()
                    val syl = TypoCorrector.pinyinSyllableCount(literal)
                    val rime = when {
                        codeToIdx[literal] != null -> arrayOf(CandidateWord("", d.outputs[codeToIdx[literal]!!], ""))
                        syl > 0 -> arrayOf(CandidateWord("", "啊".repeat(syl), ""))
                        else -> emptyArray()
                    }
                    val t0 = System.nanoTime()
                    val sugs = TypoCorrector.suggest(rime)
                    t += System.nanoTime() - t0
                    if (literal != code) {
                        err++
                        if (sugs.firstOrNull()?.let { it.code == code && it.promotePrimary } == true) fixedFirst++
                        if (sugs.any { it.code == code }) shown++
                    } else {
                        clean++
                        if (sugs.any { it.promotePrimary }) fpPromote++
                        if (sugs.isNotEmpty()) fpInsert++
                    }
                }
                println("[pinyin] $label noise=$noise mistyped=$err fixed@1=${"%.3f".format(fixedFirst.toFloat() / err)} " +
                    "shown=${"%.3f".format(shown.toFloat() / err)} | clean=$clean " +
                    "falsePromote=${"%.3f".format(fpPromote.toFloat() / clean)} " +
                    "falseInsert=${"%.3f".format(fpInsert.toFloat() / clean)} avg=${"%.2f".format(t / 1e6 / codes.size)}ms")
            }
        }
        TypoCorrector.setModeForTest(GlideTyping.Mode.Flypy)
    }
}
