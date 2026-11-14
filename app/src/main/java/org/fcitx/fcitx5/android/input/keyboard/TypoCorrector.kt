/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 邻键容错纠错（小鹤双拼 / 雾凇全拼；概率空间模型 + 词频先验 + 用户触摸习惯自适应）
 *
 * 思路参考触屏键盘的统计解码（Goodman 2002、Weir et al. CHI 2014、VelociTap CHI 2015、Gboard 空间模型）：
 *   P(词 | 触点序列) ∝ P(词) · Π P(触点_i | 键_i)
 * 其中 P(触点 | 键) 取以「键中心 + 用户个人偏移」为均值的二维高斯分布；
 * 小鹤双拼每两键一个音节，候选音节必须是合法的小鹤键对，从而把搜索空间压得很小，
 * 可以同时纠正一个词里的多处误触（如 `gige`/`gihe` → `guge` 谷歌）。
 * 全拼没有固定的两键一音节，改为逐字母束搜索，并用排好序的词典编码做前缀剪枝（`zhongguo` 类长编码也能纠）。
 * 用户每次确认上屏后，用实际落点在线更新每个键的平均偏移（EMA），越用越贴合个人手感。
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.core.CandidateWord
import timber.log.Timber
import kotlin.math.abs

object TypoCorrector {

    /** 当前方案：小鹤双拼或全拼（与 GlideTyping 共用词典） */
    @Volatile
    var mode: GlideTyping.Mode = GlideTyping.Mode.Flypy
        private set

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
    // 累计学习过的输入次数（每次上屏算一次），只用于设置页展示
    private var learnedTotal = 0
    private var prefs: android.content.SharedPreferences? = null
    private const val PREFS = "ifb_touch_model"
    private const val PREFS_KEY = "offsets_v1"
    private const val PREFS_SAMPLES = "samples_v1"
    private const val LEARN_RATE = 0.06f
    private const val MAX_OFFSET = 0.32f

    private const val MAX_TAPS = 16
    private val taps = ArrayList<Tap>(MAX_TAPS)
    private var lastGeometry: GlideTyping.Geometry? = null

    @Synchronized
    fun prepare(context: Context, mode: GlideTyping.Mode) {
        if (mode != this.mode) taps.clear()
        this.mode = mode
        GlideTyping.prepare(context, mode)
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadOffsets(prefs?.getString(PREFS_KEY, null))
            learnedTotal = prefs?.getInt(PREFS_SAMPLES, 0) ?: 0
        }
    }

    /** 设置页展示用：已学到的每键平均偏移（单位：键宽 / 键高）与累计学习次数 */
    class TouchModelSnapshot(val samples: Int, val offX: FloatArray, val offY: FloatArray)

    @Synchronized
    fun snapshot(context: Context): TouchModelSnapshot {
        if (prefs != null) return TouchModelSnapshot(learnedTotal, offX.copyOf(), offY.copyOf())
        // 输入法还没在本进程里加载过：直接读已保存的数据
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = FloatArray(26)
        val y = FloatArray(26)
        p.getString(PREFS_KEY, null)?.split(',')?.takeIf { it.size == 52 }?.let { parts ->
            for (i in 0 until 26) {
                x[i] = parts[i].toFloatOrNull() ?: 0f
                y[i] = parts[26 + i].toFloatOrNull() ?: 0f
            }
        }
        return TouchModelSnapshot(p.getInt(PREFS_SAMPLES, 0), x, y)
    }

    /** 清空已学到的触摸习惯（内存与本机存储） */
    @Synchronized
    fun resetTouchModel(context: Context) {
        resetOffsets()
        learnedTotal = 0
        val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { p.edit().remove(PREFS_KEY).remove(PREFS_SAMPLES).apply() }
            .onFailure { Timber.w(it, "reset touch model failed") }
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
        if (taps.size >= MAX_TAPS) taps.removeAt(0)
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

    /** 测试用：切换方案（不加载词典、不读写存储） */
    @Synchronized
    internal fun setModeForTest(mode: GlideTyping.Mode) {
        this.mode = mode
        taps.clear()
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
        if (mode == GlideTyping.Mode.Pinyin) {
            // 全拼无法把选中的字对回到具体按键：只在它恰好覆盖整串输入时学习，其余情况清空重新跟踪
            if (pinyinSyllableCount(typedNow()) == word.length) learn(typedNow())
            taps.clear()
            return
        }
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
        if (mode == GlideTyping.Mode.Pinyin) {
            if (taps.size >= 2 && pinyinSyllableCount(typedNow()) > 0) learn(typedNow())
            return
        }
        if (taps.size in 2..8 && taps.size % 2 == 0 && literalAllValid()) {
            learn(typedNow())
        }
    }

    private fun typedNow() = String(CharArray(taps.size) { taps[it].ch })

    /**
     * 全拼串能切成几个完整音节；切不开返回 -1。
     * [allowPartialLast] 时允许最后一段只是某个音节的前缀（还在打），这种情况返回 0。
     */
    internal fun pinyinSyllableCount(code: String, allowPartialLast: Boolean = false): Int {
        val syl = GlideTyping.dictFor(GlideTyping.Mode.Pinyin)?.validSyllables ?: return -1
        val n = code.length
        // best[i]: 前 i 个字母最少切成几个完整音节（-1 表示切不开）
        val best = IntArray(n + 1) { -1 }
        best[0] = 0
        for (i in 1..n) {
            for (len in 1..minOf(6, i)) {
                val j = i - len
                if (best[j] >= 0 && code.substring(j, i) in syl) {
                    val c = best[j] + 1
                    if (best[i] < 0 || c < best[i]) best[i] = c
                }
            }
        }
        if (best[n] >= 0) return best[n]
        if (!allowPartialLast) return -1
        val prefixes = pinyinSyllablePrefixes() ?: return -1
        for (j in n - 1 downTo maxOf(0, n - 5)) {
            if (best[j] >= 0 && code.substring(j) in prefixes) return 0
        }
        return -1
    }

    @Volatile
    private var syllablePrefixCache: Pair<Set<String>, HashSet<String>>? = null

    /** 全拼音节的所有真前缀（如 zh / zho / zhon），用来区分"还在打"和"打错了" */
    private fun pinyinSyllablePrefixes(): HashSet<String>? {
        val syl = GlideTyping.dictFor(GlideTyping.Mode.Pinyin)?.validSyllables ?: return null
        syllablePrefixCache?.let { if (it.first === syl) return it.second }
        val out = HashSet<String>(1024)
        for (x in syl) for (k in 1 until x.length) out.add(x.substring(0, k))
        syllablePrefixCache = syl to out
        return out
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
        if (!changed) return
        learnedTotal++
        // 每次都写：apply() 是异步的，开销很小；攒批保存会在重装或进程被杀时丢掉最近的学习结果
        val p = prefs ?: return
        runCatching { p.edit().putString(PREFS_KEY, dumpOffsets()).putInt(PREFS_SAMPLES, learnedTotal).apply() }
            .onFailure { Timber.w(it, "save touch model failed") }
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
    fun suggest(rimeCandidates: Array<CandidateWord>, g: GlideTyping.Geometry? = null): List<Suggestion> =
        if (mode == GlideTyping.Mode.Pinyin) suggestPinyin(rimeCandidates, g) else suggestFlypy(rimeCandidates, g)

    private fun suggestFlypy(rimeCandidates: Array<CandidateWord>, g: GlideTyping.Geometry?): List<Suggestion> {
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

        return decide(
            rimeCandidates, promotePrimary, typed, literalLl, literalFreq,
            beam.map { it.code to it.ll }, dict
        ) { word -> word.length == expectedChars }
    }

    /**
     * 全拼：逐字母束搜索。每个触点取附近几个可能的键，只保留仍是某个词典编码前缀的部分串（排好序的编码二分查找），
     * 最后与词典完全匹配的才算候选。整串切不成拼音（打错）时置顶，正在打一个音节的中途不打扰。
     */
    private fun suggestPinyin(rimeCandidates: Array<CandidateWord>, g: GlideTyping.Geometry?): List<Suggestion> {
        val dict = GlideTyping.dictFor(GlideTyping.Mode.Pinyin) ?: return emptyList()
        val codeToIdx = dict.codeToIdx ?: return emptyList()
        val sorted = dict.sortedCodes ?: return emptyList()
        val geom = g ?: lastGeometry
        val n = taps.size
        if (n !in 2..14) return emptyList()
        val typed = typedNow()

        val complete = pinyinSyllableCount(typed)
        val invalid = complete < 0 && pinyinSyllableCount(typed, allowPartialLast = true) < 0
        // 还在打最后一个音节（如 zhon）：等打完再说
        if (complete < 0 && !invalid) return emptyList()
        // 单个合法音节不纠（与小鹤单字规则一致）
        if (complete == 1) return emptyList()

        val topText = rimeCandidates.firstOrNull()?.text.orEmpty()
        val topCjkCount = topText.count { it.code in 0x4E00..0x9FFF }
        val rimeTopIncomplete = rimeCandidates.isEmpty() || (complete > 0 && topCjkCount < complete)
        val promotePrimary = invalid || rimeTopIncomplete

        var literalLl = 0f
        for (i in 0 until n) literalLl += tapLogLik(taps[i], typed[i] - 'a', geom)
        val literalFreq = codeToIdx[typed]?.let { dict.freq[it] }

        val cands = Array(n) { keyCandidates(taps[it], geom) }
        var beam = listOf(Partial("", 0f))
        for (i in 0 until n) {
            val next = ArrayList<Partial>(beam.size * 4)
            for (p in beam) for (kc in cands[i]) {
                val code = p.code + ('a' + kc.k)
                if (hasPrefix(sorted, code)) next.add(Partial(code, p.ll + kc.ll))
            }
            if (next.isEmpty()) return emptyList()
            next.sortByDescending { it.ll }
            beam = if (next.size > PINYIN_BEAM) next.subList(0, PINYIN_BEAM) else next
        }
        return decide(
            rimeCandidates, promotePrimary, typed, literalLl, literalFreq,
            beam.map { it.code to it.ll }, dict
        ) { true }
    }

    private const val PINYIN_BEAM = 300

    /** 有没有词典编码以 [prefix] 开头 */
    private fun hasPrefix(sorted: Array<String>, prefix: String): Boolean {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo < sorted.size && sorted[lo].startsWith(prefix)
    }

    /** 两种方案共用的取舍：按「纠错词 / 原串」的后验比决定置顶、插第 2 位或不给 */
    private inline fun decide(
        rimeCandidates: Array<CandidateWord>,
        promotePrimary: Boolean,
        typed: String,
        literalLl: Float,
        literalFreq: Float?,
        beam: List<Pair<String, Float>>,
        dict: GlideTyping.Dict,
        wordOk: (String) -> Boolean
    ): List<Suggestion> {
        val codeToIdx = dict.codeToIdx ?: return emptyList()
        // 已经能看到的词不再重复给出：要置顶时只排除 Rime 首选（Rime 自带纠错可能把正确词排在后面，
        // 这时仍要把它提到首位）；只插第 2 位时排除 Rime 前三
        val seenWords = HashSet<String>(4)
        rimeCandidates.take(if (promotePrimary) 1 else 3).forEach { seenWords.add(it.text) }
        // 原串不是词典词条时，Rime 多半是在拿单字拼句子：给它一个中等偏低的先验
        val literalPrior = literalFreq ?: SENTENCE_PRIOR
        val found = ArrayList<Suggestion>(8)
        for ((code, ll) in beam) {
            if (code == typed) continue
            val idx = codeToIdx[code] ?: continue
            val word = dict.outputs[idx]
            if (!wordOk(word) || word in seenWords) continue
            val freq = dict.freq[idx]
            val spatialDrop = literalLl - ll // 通常 >= 0；越小说明触点越像这个词
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
            found.add(Suggestion(word, code, promote, logRatio))
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
