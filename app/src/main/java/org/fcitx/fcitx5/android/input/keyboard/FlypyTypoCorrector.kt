/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 小鹤双拼邻键容错纠错（概率空间模型 + 词频先验 + 用户触摸习惯自适应）
 *
 * 思路参考触屏键盘的统计解码（Goodman 2002、Weir et al. CHI 2014、VelociTap CHI 2015、Gboard 空间模型）：
 *   P(词 | 触点序列) ∝ P(词) · Π P(触点_i | 键_i)
 * 其中 P(触点 | 键) 取以「键中心 + 用户个人偏移」为均值的二维高斯分布；
 * 小鹤双拼每两键一个音节，候选音节必须是合法的小鹤键对，从而把搜索空间压得很小，
 * 可以同时纠正一个词里的多处误触（如 `gige`/`fuhe` → `guge` 谷歌）。
 * 用户每次确认上屏后，用实际落点在线更新每个键的平均偏移（EMA），越用越贴合个人手感。
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.core.CandidateWord
import timber.log.Timber
import kotlin.math.abs

object FlypyTypoCorrector {

    data class Tap(val ch: Char, val gx: Float, val gy: Float)

    data class Suggestion(
        val word: String,
        val code: String,
        val promotePrimary: Boolean,
        val score: Float
    )

    // ------------------------------------------------------------------ spatial model

    /** 触点散布标准差（单位：键宽 / 键高）；取自触屏键盘实验的典型值并略放宽 */
    internal const val SIGMA_X = 0.30f
    internal const val SIGMA_Y = 0.27f

    /** 只考虑离触点不太远的键：对数似然比最佳键差不超过该值（约 2.8σ） */
    private const val MAX_TAP_DROP = 4.0f

    /** 词频先验权重：freq 为 GlideTyping 词典中的对数尺度先验 */
    internal const val FREQ_WEIGHT = 0.55f

    private const val MAX_PAIRS_PER_SYLLABLE = 8

    // 决策阈值（单位：对数似然）；由 TapCorrectionBenchmarkTest 离线评估得出
    internal var SENTENCE_PRIOR = 9.0f
    internal var INSERT_MAX_DROP = 4.5f
    internal var INSERT_MIN_RATIO = -0.5f
    internal var PROMOTE_MAX_DROP = 2.5f
    internal var PROMOTE_MIN_RATIO = 2.0f

    // 标准 QWERTY 默认中心点（当未拿到动态 Geometry 时兜底），单位为按键宽/高网格
    private val defaultGx = FloatArray(26)
    private val defaultGy = FloatArray(26)

    init {
        val rows = arrayOf(
            Triple("qwertyuiop", 0.5f, 0.5f),
            Triple("asdfghjkl", 1.0f, 1.5f),
            Triple("zxcvbnm", 2.0f, 2.5f)
        )
        for ((letters, startX, rowY) in rows) {
            letters.forEachIndexed { idx, ch ->
                val i = ch - 'a'
                defaultGx[i] = startX + idx
                defaultGy[i] = rowY
            }
        }
    }

    // 用户个人触摸偏移（每个键的平均落点 - 键中心，单位：键宽/键高），在线学习
    private val offX = FloatArray(26)
    private val offY = FloatArray(26)
    private var learnedSinceSave = 0
    private var prefs: android.content.SharedPreferences? = null
    private const val PREFS = "ifb_touch_model"
    private const val PREFS_KEY = "offsets_v1"
    private const val LEARN_RATE = 0.06f
    private const val MAX_OFFSET = 0.32f

    private val taps = ArrayList<Tap>(12)
    private var lastGeometry: GlideTyping.Geometry? = null

    @Synchronized
    fun prepare(context: Context) {
        GlideTyping.prepare(context, GlideTyping.Mode.Flypy)
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadOffsets(prefs?.getString(PREFS_KEY, null))
        }
    }

    internal fun loadOffsets(s: String?) {
        val parts = s?.split(',') ?: return
        if (parts.size != 52) return
        for (i in 0 until 26) {
            offX[i] = parts[i].toFloatOrNull()?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0f
            offY[i] = parts[26 + i].toFloatOrNull()?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0f
        }
    }

    internal fun dumpOffsets(): String =
        (offX.asList() + offY.asList()).joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    @Synchronized
    internal fun resetOffsets() {
        offX.fill(0f); offY.fill(0f)
    }

    @Synchronized
    fun recordTap(ch: Char, rawX: Float, rawY: Float, g: GlideTyping.Geometry?) {
        val lower = ch.lowercaseChar()
        if (lower !in 'a'..'z') return
        if (g != null && g.keyW > 0f && g.keyH > 0f) {
            lastGeometry = g
        }
        val geom = g ?: lastGeometry
        if (taps.size >= 10) taps.removeAt(0)
        val ki = lower - 'a'
        val (gx, gy) = if (geom != null && geom.keyW > 0f && geom.keyH > 0f && !rawX.isNaN() && !rawY.isNaN()) {
            (rawX / geom.keyW) to (rawY / geom.keyH)
        } else {
            // 没有真实落点：当作正中按下，不触发纠错
            val (cx, cy) = center(ki, geom)
            (cx + offX[ki]) to (cy + offY[ki])
        }
        taps.add(Tap(lower, gx, gy))
    }

    /** 测试用：直接写入网格坐标系下的触点 */
    @Synchronized
    internal fun recordGridTap(ch: Char, gx: Float, gy: Float, g: GlideTyping.Geometry?) {
        if (g != null) lastGeometry = g
        taps.add(Tap(ch, gx, gy))
    }

    @Synchronized
    fun popLast() {
        if (taps.isNotEmpty()) taps.removeAt(taps.lastIndex)
    }

    @Synchronized
    fun clear() {
        taps.clear()
    }

    /** 选中了 Rime 自己的候选：若它恰好覆盖全部按键且全是合法音节，就当作按键无误来学习落点习惯 */
    @Synchronized
    fun onEngineCandidateSelected(word: String) {
        if (word.isEmpty()) return
        val drop = word.length * 2
        if (drop == taps.size && literalAllValid()) learn(String(CharArray(taps.size) { taps[it].ch }))
        if (drop >= taps.size) {
            taps.clear()
        } else {
            repeat(drop) { taps.removeAt(0) }
        }
    }

    /** 选中了纠错词条：按纠正后的编码学习（这是最有价值的样本：用户真实的偏差方向） */
    @Synchronized
    fun onCorrectionSelected(code: String) {
        if (code.length == taps.size) learn(code)
        taps.clear()
    }

    /** 空格直接上屏 Rime 首选前调用 */
    @Synchronized
    fun onLiteralCommitted() {
        if (taps.size in 2..8 && taps.size % 2 == 0 && literalAllValid()) {
            learn(String(CharArray(taps.size) { taps[it].ch }))
        }
    }

    private fun literalAllValid(): Boolean {
        val valid = GlideTyping.flypyDict?.validSyllables ?: return false
        if (taps.size % 2 != 0) return false
        for (k in 0 until taps.size / 2) {
            if ("${taps[2 * k].ch}${taps[2 * k + 1].ch}" !in valid) return false
        }
        return true
    }

    private fun learn(code: String) {
        val geom = lastGeometry
        var changed = false
        for (i in code.indices) {
            val k = code[i] - 'a'
            if (k !in 0..25) continue
            val (cx, cy) = center(k, geom)
            val dx = taps[i].gx - cx
            val dy = taps[i].gy - cy
            // 离谱的落点（很可能不是在按这个键）不参与学习
            if (abs(dx) > 0.9f || abs(dy) > 0.9f) continue
            offX[k] = (offX[k] + LEARN_RATE * (dx - offX[k])).coerceIn(-MAX_OFFSET, MAX_OFFSET)
            offY[k] = (offY[k] + LEARN_RATE * (dy - offY[k])).coerceIn(-MAX_OFFSET, MAX_OFFSET)
            changed = true
        }
        if (changed && ++learnedSinceSave >= 8) {
            learnedSinceSave = 0
            val p = prefs ?: return
            runCatching { p.edit().putString(PREFS_KEY, dumpOffsets()).apply() }
                .onFailure { Timber.w(it, "save touch model failed") }
        }
    }

    private fun center(k: Int, g: GlideTyping.Geometry?): Pair<Float, Float> =
        if (g != null && g.present[k] && g.keyW > 0f && g.keyH > 0f) {
            (g.cx[k] / g.keyW) to (g.cy[k] / g.keyH)
        } else {
            defaultGx[k] to defaultGy[k]
        }

    /** ln P(tap | key)（省略常数项） */
    private fun tapLogLik(t: Tap, k: Int, g: GlideTyping.Geometry?): Float {
        val (cx, cy) = center(k, g)
        val dx = (t.gx - cx - offX[k]) / SIGMA_X
        val dy = (t.gy - cy - offY[k]) / SIGMA_Y
        return -0.5f * (dx * dx + dy * dy)
    }

    private class KeyCand(val k: Int, val ll: Float)
    private class Pair2(val code: String, val ll: Float)
    private class Partial(val code: String, val ll: Float)

    /** 每个触点的可能键（按似然降序） */
    private fun keyCandidates(t: Tap, g: GlideTyping.Geometry?): List<KeyCand> {
        val all = ArrayList<KeyCand>(8)
        var best = -Float.MAX_VALUE
        for (k in 0 until 26) {
            if (g != null && !g.present[k]) continue
            val ll = tapLogLik(t, k, g)
            if (ll > best) best = ll
            all.add(KeyCand(k, ll))
        }
        return all.filter { it.ll >= best - MAX_TAP_DROP }.sortedByDescending { it.ll }
    }

    /**
     * 基于当前按键序列与触摸落点计算邻键纠错建议。
     * 原串含非法音节（或 Rime 首选没能覆盖全部音节）时，最佳纠错直接顶到首位，最多给 4 个；
     * 原串本身合法时只在明显更可能的情况下插到第 2 位，最多 2 个。
     */
    @Synchronized
    fun suggest(rimeCandidates: Array<CandidateWord>, g: GlideTyping.Geometry? = null): List<Suggestion> {
        val dict = GlideTyping.flypyDict ?: return emptyList()
        val codeToIdx = dict.codeToIdx ?: return emptyList()
        val validSyl = dict.validSyllables ?: return emptyList()
        val geom = g ?: lastGeometry
        val n = taps.size
        // 小鹤双拼按 2 键一字组合；只在 2 / 4 / 6 / 8 个完整音节处触发
        if (n !in listOf(2, 4, 6, 8)) return emptyList()

        val typed = buildString(n) { taps.forEach { append(it.ch) } }
        var hasInvalidSyllable = false
        for (k in 0 until n / 2) {
            if (typed.substring(2 * k, 2 * k + 2) !in validSyl) {
                hasInvalidSyllable = true
                break
            }
        }
        // 单字(2键)仅当音节本身非法时才尝试纠错，避免正常单字被干扰
        if (n == 2 && !hasInvalidSyllable) return emptyList()

        val expectedChars = n / 2
        val topText = rimeCandidates.firstOrNull()?.text.orEmpty()
        val topCjkCount = topText.count { it.code in 0x4E00..0x9FFF }
        val rimeTopIncomplete = rimeCandidates.isEmpty() || topCjkCount < expectedChars
        val promotePrimary = hasInvalidSyllable || rimeTopIncomplete

        // 原样输入的空间似然与词频，作为比较基准
        var literalLl = 0f
        for (i in 0 until n) literalLl += tapLogLik(taps[i], typed[i] - 'a', geom)
        val literalIdx = codeToIdx[typed]
        val literalFreq = literalIdx?.let { dict.freq[it] }

        val cands = Array(n) { keyCandidates(taps[it], geom) }

        // 逐音节枚举合法键对，再做束搜索组合
        var beam = listOf(Partial("", 0f))
        for (s in 0 until expectedChars) {
            val pairs = ArrayList<Pair2>()
            for (a in cands[2 * s]) for (b in cands[2 * s + 1]) {
                val syl = "${'a' + a.k}${'a' + b.k}"
                if (syl in validSyl) pairs.add(Pair2(syl, a.ll + b.ll))
            }
            pairs.sortByDescending { it.ll }
            val top = pairs.take(MAX_PAIRS_PER_SYLLABLE)
            if (top.isEmpty()) return emptyList()
            val next = ArrayList<Partial>(beam.size * top.size)
            for (p in beam) for (q in top) next.add(Partial(p.code + q.code, p.ll + q.ll))
            next.sortByDescending { it.ll }
            beam = next.take(400)
        }

        // 已经能看到的词不再重复给出：要置顶时只排除 Rime 首选（Rime 自带纠错可能把正确词排在后面，
        // 这时仍要把它提到首位）；只插第 2 位时排除 Rime 前三
        val seenWords = HashSet<String>(4)
        rimeCandidates.take(if (promotePrimary) 1 else 3).forEach { seenWords.add(it.text) }
        // 原串不是词典词条时，Rime 多半是在拿单字拼句子：给它一个中等偏低的先验
        val literalPrior = literalFreq ?: SENTENCE_PRIOR
        val found = ArrayList<Suggestion>(8)
        for (p in beam) {
            if (p.code == typed) continue
            val idx = codeToIdx[p.code] ?: continue
            val word = dict.outputs[idx]
            if (word.length != expectedChars || word in seenWords) continue
            val freq = dict.freq[idx]
            val spatialDrop = literalLl - p.ll // 通常 >= 0；越小说明触点越像这个词
            // ln [P(纠错词 | 触点) / P(原串 | 触点)]
            val logRatio = -spatialDrop + FREQ_WEIGHT * (freq - literalPrior)
            val promote = when {
                promotePrimary -> spatialDrop <= 9f
                // 原串只是 Rime 拼出来的句子，而触点几乎压在键边界上、纠错词又常用得多：直接顶到首位
                literalFreq == null -> spatialDrop <= PROMOTE_MAX_DROP && logRatio >= PROMOTE_MIN_RATIO
                else -> false
            }
            if (!promote) {
                if (promotePrimary) continue
                // 原串本身合法：触点要足够模糊、纠错词要足够可能，才插到第 2 位供选择
                if (spatialDrop > INSERT_MAX_DROP || logRatio < INSERT_MIN_RATIO) continue
            }
            found.add(Suggestion(word, p.code, promote, logRatio))
        }

        if (found.isEmpty()) return emptyList()
        // 置顶的在前，其余按后验比排序
        found.sortWith(compareByDescending<Suggestion> { it.promotePrimary }.thenByDescending { it.score })
        val maxOut = if (found[0].promotePrimary) 4 else 2
        val unique = ArrayList<Suggestion>(maxOut)
        val addedWords = HashSet<String>()
        for (s in found) {
            if (addedWords.add(s.word)) {
                unique.add(s)
                if (unique.size >= maxOut) break
            }
        }
        return unique
    }

    /** 测试用：当前已记录的按键 */
    internal fun typedForTest(): String = synchronized(this) { String(CharArray(taps.size) { taps[it].ch }) }
}
