/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * (test copy of the 1.2.4 implementation, for benchmark comparison) Ifboard: 小鹤双拼邻键容错纠错（基于按键物理坐标与 QWERTY 邻键图 + 词频先验）
 * 例：误把 "guge"(谷歌) 按成 "gige" 时，自动识别相邻误触并在候选栏给出正确词条。
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.core.CandidateWord
import kotlin.math.hypot
import kotlin.math.max

/**
 * 小鹤双拼邻键误触纠错器：
 * 1. 记录每次按键在键盘网格坐标系 (keyW, keyH) 中的真实落点；
 * 2. 当处于小鹤双拼模式且当前输入串中存在相邻误触（如 `gige` -> `guge`「谷歌」），
 *    在主候选/次候选中注入高置信纠错词条。
 */
internal object LegacyFlypyTypoCorrector {

    data class Tap(val ch: Char, val gx: Float, val gy: Float)

    data class Suggestion(
        val word: String,
        val code: String,
        val promotePrimary: Boolean,
        val score: Float
    )

    // 标准 QWERTY 默认中心点（当未拿到动态 Geometry 时兜底），单位为按键宽/高网格
    private val defaultGx = FloatArray(26)
    private val defaultGy = FloatArray(26)
    private val neighbors: Array<CharArray>

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
        neighbors = Array(26) { i ->
            val x0 = defaultGx[i]; val y0 = defaultGy[i]
            (0 until 26).filter { j ->
                j != i && hypot(defaultGx[j] - x0, (defaultGy[j] - y0) * 1.15f) <= 1.55f
            }.sortedBy { j ->
                hypot(defaultGx[j] - x0, (defaultGy[j] - y0) * 1.15f)
            }.map { ('a' + it) }.toCharArray()
        }
    }

    private val taps = ArrayList<Tap>(12)
    private var lastGeometry: GlideTyping.Geometry? = null

    @Synchronized
    fun prepare(context: Context) {
        GlideTyping.prepare(context, GlideTyping.Mode.Flypy)
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
        } else if (geom != null && geom.present[ki] && geom.keyW > 0f && geom.keyH > 0f) {
            (geom.cx[ki] / geom.keyW) to (geom.cy[ki] / geom.keyH)
        } else {
            defaultGx[ki] to defaultGy[ki]
        }
        taps.add(Tap(lower, gx, gy))
    }

    @Synchronized
    fun popLast() {
        if (taps.isNotEmpty()) taps.removeAt(taps.lastIndex)
    }

    @Synchronized
    fun clear() {
        taps.clear()
    }

    @Synchronized
    fun onEngineCandidateSelected(word: String) {
        if (word.isEmpty()) return
        val drop = word.length * 2
        if (drop >= taps.size) {
            taps.clear()
        } else {
            repeat(drop) { taps.removeAt(0) }
        }
    }

    private fun altCenter(alt: Char, g: GlideTyping.Geometry?): Pair<Float, Float> {
        val ki = alt - 'a'
        return if (g != null && g.present[ki] && g.keyW > 0f && g.keyH > 0f) {
            (g.cx[ki] / g.keyW) to (g.cy[ki] / g.keyH)
        } else {
            defaultGx[ki] to defaultGy[ki]
        }
    }

    /**
     * 基于当前按键序列与触摸落点计算邻键纠错建议（Rime 无匹配或含非法音节时最多返回 4 个，否则 2 个）。
     */
    @Synchronized
    fun suggest(rimeCandidates: Array<CandidateWord>, g: GlideTyping.Geometry? = null): List<Suggestion> {
        val dict = GlideTyping.flypyDict ?: return emptyList()
        val codeToIdx = dict.codeToIdx ?: return emptyList()
        val validSyl = dict.validSyllables ?: return emptyList()
        val geom = g ?: lastGeometry
        val aspect = if (geom != null && geom.keyW > 0f && geom.keyH > 0f) geom.keyH / geom.keyW else 1.40f
        val n = taps.size
        // 小鹤双拼按 2 键一字组合；只在 2 / 4 / 6 / 8 个完整音节处触发
        if (n !in listOf(2, 4, 6, 8)) return emptyList()

        val typed = buildString(n) { taps.forEach { append(it.ch) } }
        var hasInvalidSyllable = false
        for (k in 0 until n / 2) {
            val pair = typed.substring(2 * k, 2 * k + 2)
            if (pair !in validSyl) {
                hasInvalidSyllable = true
                break
            }
        }
        // 单字(2键)仅当音节本身非法时才尝试纠错，避免正常单字被干扰
        if (n == 2 && !hasInvalidSyllable) return emptyList()

        val expectedChars = n / 2
        val topText = rimeCandidates.firstOrNull()?.text.orEmpty()
        val topCjkCount = topText.count { it.code >= 0x4E00 && it.code <= 0x9FFF }
        val rimeTopIncomplete = rimeCandidates.isEmpty() || topCjkCount < expectedChars

        // 原串本身在词库里的最高频词条，用于过滤掉明显弱于合法本词的纠错
        val exactFreq = codeToIdx[typed]?.let { dict.freq[it] } ?: 0f
        val promotePrimary = hasInvalidSyllable || rimeTopIncomplete

        val found = ArrayList<Suggestion>(6)
        val chars = typed.toCharArray()
        val seenWords = HashSet<String>(4)
        rimeCandidates.take(3).forEach { seenWords.add(it.text) }

        // 生成单键邻键替换候选
        for (pos in 0 until n) {
            val orig = chars[pos]
            val origTap = taps[pos]
            val partner = if (pos % 2 == 0) chars[pos + 1] else chars[pos - 1]
            for (alt in neighbors[orig - 'a']) {
                val syl = if (pos % 2 == 0) "$alt$partner" else "$partner$alt"
                if (syl !in validSyl) continue
                chars[pos] = alt
                val candCode = String(chars)
                val idx = codeToIdx[candCode]
                if (idx != null) {
                    val word = dict.outputs[idx]
                    if (word !in seenWords && word.length == expectedChars) {
                        val freq = dict.freq[idx]
                        // 若 Rime 已有完整高频合法词条，只保留超高频且非常贴近边界的备选
                        if (promotePrimary || freq >= max(exactFreq - 0.5f, 10.6f)) {
                            val (ax, ay) = altCenter(alt, geom)
                            val touchDist = hypot(origTap.gx - ax, (origTap.gy - ay) * aspect)
                            val score = -2.8f * touchDist + 0.24f * freq
                            found.add(Suggestion(word, candCode, promotePrimary, score))
                        }
                    }
                }
            }
            chars[pos] = orig
        }

        if (found.isEmpty()) return emptyList()
        found.sortByDescending { it.score }
        val maxOut = if (hasInvalidSyllable || rimeCandidates.isEmpty()) 4 else 2
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
}
