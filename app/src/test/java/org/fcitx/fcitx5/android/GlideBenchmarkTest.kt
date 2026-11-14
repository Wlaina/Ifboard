/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * Ifboard: offline accuracy benchmark for glide typing and tap typo correction.
 * Synthesizes noisy gestures / taps for common words over a standard QWERTY geometry and
 * reports top-1 / top-3 accuracy. Needs the staged rime-data in src/main/assets (run
 * ifboard/stage-data.sh first) and IFB_BENCH=1 in the environment, otherwise it is skipped.
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.GlideTyping
import org.fcitx.fcitx5.android.input.keyboard.KeyboardGeometryForTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

class GlideBenchmarkTest {

    private val dataDir = File("src/main/assets/usr/share/rime-data")

    private fun ready(): Boolean =
        System.getenv("IFB_BENCH") != null && File(dataDir, "cn_dicts/base.dict.yaml").exists()

    private fun load(mode: GlideTyping.Mode) {
        if (!GlideTyping.isReady(mode)) GlideTyping.loadBlocking(mode) { File(dataDir, it).inputStream() }
    }

    private fun sample(mode: GlideTyping.Mode, topN: Int, count: Int, seed: Long): List<Int> {
        val d = GlideTyping.dictFor(mode)!!
        val order = d.freq.indices.sortedByDescending { d.freq[it] }
            .filter { i -> val c = d.codes[i]; c.length >= 2 && c.toSet().size >= 2 }
            .take(topN)
        val rnd = Random(seed)
        return order.shuffled(rnd).take(count)
    }

    private fun run(mode: GlideTyping.Mode, noise: Float, label: String, count: Int = 300) {
        load(mode)
        val d = GlideTyping.dictFor(mode)!!
        val g = KeyboardGeometryForTest.qwerty()
        val ids = sample(mode, 4000, count, 11)
        val rnd = Random(42)
        var top1 = 0; var top3 = 0; var tot = 0
        val misses = ArrayList<String>()
        val t0 = System.nanoTime()
        for (id in ids) {
            val code = d.codes[id]
            val xy = KeyboardGeometryForTest.synthGlide(code, g, rnd, noise) ?: continue
            val res = GlideTyping.decode(xy, xy.size / 2, g, mode, limit = 5)
            tot++
            val rank = res.indexOfFirst { it.code == code }
            if (rank == 0) top1++
            if (rank in 0..2) top3++
            if (rank != 0 && misses.size < 12) misses.add("$code(${d.outputs[id]})->${res.firstOrNull()?.code}")
        }
        val ms = (System.nanoTime() - t0) / 1e6 / tot
        println("[bench] $label noise=$noise n=$tot top1=${"%.3f".format(top1.toFloat() / tot)} " +
            "top3=${"%.3f".format(top3.toFloat() / tot)} avg=${"%.1f".format(ms)}ms")
        println("[bench]   misses: ${misses.joinToString(" ")}")
    }

    /** mean top-1 over the three modes, noise drawn uniformly from 0.15..0.35 key widths */
    private fun objective(seedIds: Long, seedNoise: Long, count: Int): FloatArray {
        val g = KeyboardGeometryForTest.qwerty()
        val out = FloatArray(3)
        GlideTyping.Mode.entries.forEachIndexed { mi, mode ->
            load(mode)
            val d = GlideTyping.dictFor(mode)!!
            val rnd = Random(seedNoise)
            var ok = 0; var tot = 0
            for (id in sample(mode, 4000, count, seedIds)) {
                val code = d.codes[id]
                val noise = 0.15f + 0.20f * rnd.nextFloat()
                val xy = KeyboardGeometryForTest.synthGlide(code, g, rnd, noise) ?: continue
                val res = GlideTyping.decode(xy, xy.size / 2, g, mode, limit = 1)
                tot++
                if (res.firstOrNull()?.code == code) ok++
            }
            out[mi] = ok.toFloat() / tot
        }
        return out
    }

    @Test
    fun glide_tune() {
        assumeTrue(ready() && System.getenv("IFB_TUNE") != null)
        var best = GlideTyping.w.copy()
        fun eval(): Float = objective(21, 77, 220).average().toFloat()
        var bestScore = eval()
        println("[tune] start $bestScore $best")
        val fields = listOf("loc", "shp", "trace", "miss", "end", "len", "freqFlypy", "freqPinyin", "freqEnglish", "coarseFreq")
        repeat(2) { round ->
            for (f in fields) {
                for (mul in floatArrayOf(0.6f, 1.5f)) {
                    val cand = best.copy()
                    val fld = GlideTyping.Weights::class.java.getField(f)
                    fld.setFloat(cand, fld.getFloat(cand) * mul)
                    GlideTyping.w = cand
                    val sc = eval()
                    if (sc > bestScore + 0.002f) {
                        bestScore = sc; best = cand
                        println("[tune] r$round $f x$mul -> $sc")
                    }
                }
                GlideTyping.w = best
            }
        }
        for (cl in intArrayOf(200, 300)) {
            val cand = best.copy().also { it.coarseLimit = cl }
            GlideTyping.w = cand
            val sc = eval()
            println("[tune] coarseLimit=$cl -> $sc")
            if (sc > bestScore + 0.002f) { bestScore = sc; best = cand }
        }
        GlideTyping.w = best
        println("[tune] best $bestScore $best")
        // held-out words and noise
        val held = objective(99, 1234, 300)
        GlideTyping.w = GlideTyping.Weights()
        val heldBase = objective(99, 1234, 300)
        println("[tune] held-out base=${heldBase.joinToString()} tuned=${held.joinToString()}")
    }

    private fun englishTop(codes: List<String>, seed: Long): Pair<Float, Float> {
        val g = KeyboardGeometryForTest.qwerty()
        val rnd = Random(seed)
        var t1 = 0; var t3 = 0
        for (code in codes) {
            val noise = 0.15f + 0.20f * rnd.nextFloat()
            val xy = KeyboardGeometryForTest.synthGlide(code, g, rnd, noise)!!
            val res = GlideTyping.decode(xy, xy.size / 2, g, GlideTyping.Mode.English, limit = 3)
            val r = res.indexOfFirst { it.code == code }
            if (r == 0) t1++
            if (r in 0..2) t3++
        }
        return t1.toFloat() / codes.size to t3.toFloat() / codes.size
    }

    @Test
    fun english_frequency_before_after() {
        assumeTrue(ready() && File(dataDir, "en_dicts/ifb_en_freq.tsv").exists())
        // words people actually glide: the 5000 most frequent of the LatinIME list that rime-ice also has
        GlideTyping.loadBlocking(GlideTyping.Mode.English) { File(dataDir, it).inputStream() }
        val withFreq = GlideTyping.dictFor(GlideTyping.Mode.English)!!
        GlideTyping.loadBlocking(GlideTyping.Mode.English) {
            if (it.endsWith(".tsv")) throw java.io.FileNotFoundException(it) else File(dataDir, it).inputStream()
        }
        val old = GlideTyping.dictFor(GlideTyping.Mode.English)!!
        val oldCodes = old.codes.toHashSet()
        val top = withFreq.freq.indices.sortedByDescending { withFreq.freq[it] }.map { withFreq.codes[it] }
            .filter { it in oldCodes && it.toSet().size >= 2 }.take(5000)
        val tuneSet = top.shuffled(Random(5)).take(300)
        val heldSet = top.shuffled(Random(6)).take(300)
        val saved = GlideTyping.w.freqEnglish
        println("[en] old-dict tune=${englishTop(tuneSet, 1)} held=${englishTop(heldSet, 2)}")
        GlideTyping.loadBlocking(GlideTyping.Mode.English) { File(dataDir, it).inputStream() }
        for (fe in floatArrayOf(0.20f, 0.32f, 0.45f, 0.60f, 0.80f)) {
            GlideTyping.w.freqEnglish = fe
            println("[en] freq-dict fE=$fe tune=${englishTop(tuneSet, 1)} held=${englishTop(heldSet, 2)}")
        }
        GlideTyping.w.freqEnglish = saved
    }

    /** fixed code lists per mode, so every configuration is scored on exactly the same words */
    private fun chineseCodes(seedIds: Long, count: Int) = listOf(GlideTyping.Mode.Flypy, GlideTyping.Mode.Pinyin)
        .associateWith { mode -> sample(mode, 4000, count, seedIds).map { GlideTyping.dictFor(mode)!!.codes[it] } }

    private fun chineseTop1(codes: Map<GlideTyping.Mode, List<String>>, seedNoise: Long): Pair<Float, Float> {
        val g = KeyboardGeometryForTest.qwerty()
        val r = FloatArray(2)
        listOf(GlideTyping.Mode.Flypy, GlideTyping.Mode.Pinyin).forEachIndexed { mi, mode ->
            val rnd = Random(seedNoise)
            var ok = 0; var tot = 0
            for (code in codes[mode]!!) {
                val noise = 0.15f + 0.20f * rnd.nextFloat()
                val xy = KeyboardGeometryForTest.synthGlide(code, g, rnd, noise) ?: continue
                tot++
                if (GlideTyping.decode(xy, xy.size / 2, g, mode, limit = 1).firstOrNull()?.code == code) ok++
            }
            r[mi] = ok.toFloat() / tot
        }
        return r[0] to r[1]
    }

    private fun reloadChinese() {
        for (mode in listOf(GlideTyping.Mode.Flypy, GlideTyping.Mode.Pinyin)) {
            GlideTyping.loadBlocking(mode) { File(dataDir, it).inputStream() }
        }
    }

    @Test
    fun prior_cap_sweep() {
        assumeTrue(ready() && System.getenv("IFB_TUNE") != null)
        GlideTyping.priorCap = Float.MAX_VALUE
        reloadChinese()
        val tuneSet = chineseCodes(21, 600)
        val heldSet = chineseCodes(99, 600)
        for (cap in floatArrayOf(Float.MAX_VALUE, 17f, 16f, 15f, 14.5f, 14f)) {
            GlideTyping.priorCap = cap
            reloadChinese()
            println("[cap] cap=$cap tune=${chineseTop1(tuneSet, 77)} held=${chineseTop1(heldSet, 1234)}")
        }
        GlideTyping.priorCap = Float.MAX_VALUE
    }

    /** why do Chinese glides miss: same key path (only frequency can decide), frequency, or geometry */
    @Test
    fun glide_miss_analysis() {
        assumeTrue(ready() && System.getenv("IFB_TUNE") != null)
        val g = KeyboardGeometryForTest.qwerty()
        for (mode in listOf(GlideTyping.Mode.Flypy, GlideTyping.Mode.Pinyin)) {
            load(mode)
            val d = GlideTyping.dictFor(mode)!!
            val idx = HashMap<String, Int>(d.codes.size * 2).apply { d.codes.forEachIndexed { i, c -> put(c, i) } }
            fun path(c: String) = buildString { for (ch in c) if (isEmpty() || last() != ch) append(ch) }
            val rnd = Random(77)
            var tot = 0; var miss = 0; var samePath = 0; var winnerMoreFreq = 0; var notInTop5 = 0
            var lenDiff = 0
            val ex = ArrayList<String>()
            for (id in sample(mode, 4000, 400, 21)) {
                val code = d.codes[id]
                val noise = 0.15f + 0.20f * rnd.nextFloat()
                val xy = KeyboardGeometryForTest.synthGlide(code, g, rnd, noise) ?: continue
                val res = GlideTyping.decode(xy, xy.size / 2, g, mode, limit = 5)
                tot++
                val w = res.firstOrNull() ?: continue
                if (w.code == code) continue
                miss++
                if (path(w.code) == path(code)) samePath++
                val wf = d.freq[idx[w.code]!!]; val cf = d.freq[id]
                if (wf > cf) winnerMoreFreq++
                if (res.none { it.code == code }) notInTop5++
                if (path(w.code).length != path(code).length) lenDiff++
                if (ex.size < 25) ex.add("$code(${d.outputs[id]},${"%.1f".format(cf)})->${w.code}(${w.output},${"%.1f".format(wf)})")
            }
            println("[miss] $mode tot=$tot miss=$miss samePath=$samePath winnerMoreFreq=$winnerMoreFreq " +
                "keyCountDiffers=$lenDiff notInTop5=$notInTop5")
            println("[miss]   " + ex.joinToString(" "))
        }
    }

    @Test
    fun glide_benchmark() {
        assumeTrue(ready())
        for (noise in floatArrayOf(0.15f, 0.25f, 0.35f)) {
            run(GlideTyping.Mode.Flypy, noise, "flypy")
            run(GlideTyping.Mode.Pinyin, noise, "pinyin")
            run(GlideTyping.Mode.English, noise, "english")
        }
    }
}
