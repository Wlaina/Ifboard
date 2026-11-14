/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: High-accuracy Gboard style glide typing (Banded DTW + Monotonic Key Alignment + Trace Coverage).
 *
 * Word lists come from the rime-ice data already bundled in the APK:
 *  - Pinyin: cn_dicts/8105 (single characters, rank-normalized) + cn_dicts/base (2-4 character words),
 *    as full pinyin letters (rime_ice) or with each syllable converted to its 小鹤双拼 key pair
 *    (double_pinyin_flypy, also storing top Chinese words for typo correction & live preview)
 *  - English: en_dicts/en (~21k words), with a boost list of very common words
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GlideTyping {

    enum class Mode { Pinyin, Flypy, English }

    class Result(val code: String, val output: String, val score: Float)

    class IdealPath(
        val keys: IntArray,
        val kpts: FloatArray,
        val soft: FloatArray,
        val us: FloatArray,
        val un: FloatArray,
        val iLen: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    /** key centers in pixels (index 0..25 = a..z), plus the typical key size */
    class Geometry(val cx: FloatArray, val cy: FloatArray, val present: BooleanArray, val keyW: Float, val keyH: Float) {
        internal val cache = ConcurrentHashMap<String, IdealPath>()
        val hRatio: Float = if (keyW > 0f) keyH / keyW else 1.35f
        val normCx = FloatArray(26) { cx[it] / keyW.coerceAtLeast(1f) }
        val normCy = FloatArray(26) { cy[it] / keyW.coerceAtLeast(1f) }
    }

    internal class Dict(
        val codes: Array<String>,
        val outputs: Array<String>,
        val freq: FloatArray,
        val index: HashMap<Int, IntArray>,
        val codeToIdx: HashMap<String, Int>? = null,
        val validSyllables: HashSet<String>? = null
    )

    /** scoring weights of the fine pass; tuned offline with GlideBenchmarkTest */
    internal class Weights {
        @JvmField var loc = 1.0f
        @JvmField var shp = 1.0f
        @JvmField var trace = 2.8f
        @JvmField var miss = 3.2f
        @JvmField var end = 1.65f
        @JvmField var len = 2.4f
        @JvmField var freqFlypy = 0.34f
        @JvmField var freqPinyin = 0.32f
        @JvmField var freqEnglish = 0.60f
        @JvmField var coarseLimit = 120
        @JvmField var coarseFreq = 0.30f
        fun copy() = Weights().also {
            it.loc = loc; it.shp = shp; it.trace = trace; it.miss = miss; it.end = end; it.len = len
            it.freqFlypy = freqFlypy; it.freqPinyin = freqPinyin; it.freqEnglish = freqEnglish
            it.coarseLimit = coarseLimit; it.coarseFreq = coarseFreq
        }
        override fun toString() = "loc=$loc shp=$shp trace=$trace miss=$miss end=$end len=$len " +
            "fF=$freqFlypy fP=$freqPinyin fE=$freqEnglish coarse=$coarseLimit cF=$coarseFreq"
    }

    @Volatile
    internal var w = Weights()

    private const val M = 28
    private const val DTW_BAND = 5
    private const val NEAR = 1.65f

    @Volatile
    private var pinyin: Dict? = null

    @Volatile
    internal var flypyDict: Dict? = null
        private set

    @Volatile
    private var english: Dict? = null
    private val loading = HashSet<Mode>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "glide-dict").apply { priority = Thread.MIN_PRIORITY } }

    internal fun dictFor(mode: Mode): Dict? = when (mode) {
        Mode.Pinyin -> pinyin
        Mode.Flypy -> flypyDict
        Mode.English -> english
    }

    fun isReady(mode: Mode) = when (mode) {
        Mode.Pinyin -> pinyin != null
        Mode.Flypy -> flypyDict != null
        Mode.English -> english != null
    }

    /** run [block] on the loader thread once [mode]'s word list is loaded (immediately if it is) */
    fun whenReady(context: Context, mode: Mode, block: () -> Unit) {
        prepare(context, mode)
        executor.execute { if (isReady(mode)) block() }
    }

    fun prepare(context: Context, mode: Mode) {
        if (isReady(mode)) return
        synchronized(loading) { if (!loading.add(mode)) return }
        val app = context.applicationContext
        executor.execute {
            val t = System.currentTimeMillis()
            runCatching {
                loadBlocking(mode) { app.assets.open("usr/share/rime-data/$it") }
            }.onFailure { Timber.w(it, "glide dictionary $mode failed to load") }
            Timber.d("glide dictionary $mode loaded in ${System.currentTimeMillis() - t}ms")
            synchronized(loading) { loading.remove(mode) }
        }
    }

    /** load [mode]'s word list synchronously; [open] maps a path inside rime-data to its stream (also used by tests) */
    internal fun loadBlocking(mode: Mode, open: (String) -> InputStream) {
        when (mode) {
            Mode.Pinyin -> pinyin = loadPinyin(open, false)
            Mode.Flypy -> flypyDict = loadPinyin(open, true)
            Mode.English -> english = loadEnglish(open)
        }
    }

    // ---------------------------------------------------------------- dictionaries

    private fun openDict(open: (String) -> InputStream, path: String) =
        BufferedReader(InputStreamReader(open(path), Charsets.UTF_8), 1 shl 16)

    private inline fun forEachEntry(noinline open: (String) -> InputStream, path: String, block: (List<String>) -> Unit) {
        openDict(open, path).use { r ->
            var body = false
            while (true) {
                val line = r.readLine() ?: break
                if (!body) {
                    if (line.startsWith("...")) body = true
                    continue
                }
                if (line.isEmpty() || line[0] == '#') continue
                block(line.split('\t'))
            }
        }
    }

    private val flypyFinals = mapOf(
        "a" to "a", "o" to "o", "e" to "e", "i" to "i", "u" to "u", "v" to "v",
        "ai" to "d", "ei" to "w", "ui" to "v", "ao" to "c", "ou" to "z", "iu" to "q",
        "ie" to "p", "ve" to "t", "ue" to "t", "er" to "r", "an" to "j", "en" to "f",
        "in" to "b", "un" to "y", "vn" to "y", "ang" to "h", "eng" to "g", "ing" to "k",
        "ong" to "s", "ia" to "x", "ua" to "x", "uo" to "o", "iao" to "n", "ian" to "m",
        "uan" to "r", "van" to "r", "uai" to "k", "iang" to "l", "uang" to "l", "iong" to "s"
    )
    private val flypyZero = mapOf(
        "a" to "aa", "o" to "oo", "e" to "ee", "ai" to "ai", "ei" to "ei", "ao" to "ao",
        "ou" to "ou", "an" to "an", "en" to "en", "ang" to "ah", "eng" to "eg", "er" to "er"
    )

    /** full pinyin syllable -> 小鹤双拼 key pair */
    fun flypy(s: String): String? {
        flypyZero[s]?.let { return it }
        if (s.isEmpty()) return null
        val ini: String
        val fin: String
        when {
            s.startsWith("zh") -> { ini = "v"; fin = s.substring(2) }
            s.startsWith("ch") -> { ini = "i"; fin = s.substring(2) }
            s.startsWith("sh") -> { ini = "u"; fin = s.substring(2) }
            s[0] in "bpmfdtnlgkhjqxrzcsyw" -> { ini = s.substring(0, 1); fin = s.substring(1) }
            else -> return null
        }
        return flypyFinals[fin]?.let { ini + it }
    }

    private fun rimePrior(weight: Long, rank: Int, isSingle: Boolean): Float {
        if (isSingle) {
            // 8105.dict.yaml uses a different scale (up to 80M); normalize by rank into [7.5 .. 12.8]
            // so straight 2-key singles (的/上/你) win cleanly against collinear 3-key repeats (南极 njji)
            return 12.8f - 0.80f * ln(1f + rank / 35f)
        }
        var base = ln(max(weight, 1L).toFloat() + 1f)
        if (weight > 500_000L) {
            base += 0.42f * ln(1f + (weight - 500_000L).toFloat() / 120f)
        }
        return base
    }

    private fun loadPinyin(open: (String) -> InputStream, doublePinyin: Boolean): Dict {
        class W(val weight: Long, val word: String, val syllables: List<String>)
        val singles = ArrayList<W>(12000)
        val words = ArrayList<W>(400000)
        forEachEntry(open, "cn_dicts/8105.dict.yaml") { p ->
            if (p.size >= 3) singles.add(W(p[2].trim().toLongOrNull() ?: 0L, p[0].trim(), listOf(p[1].trim())))
        }
        forEachEntry(open, "cn_dicts/base.dict.yaml") { p ->
            if (p.size < 3) return@forEachEntry
            val sy = p[1].split(' ')
            if (sy.size !in 2..4) return@forEachEntry
            words.add(W(p[2].trim().toLongOrNull() ?: 0L, p[0].trim(), sy))
        }
        singles.sortByDescending { it.weight }
        words.sortByDescending { it.weight }

        val bestPrior = HashMap<String, Float>(80000)
        val bestWord = HashMap<String, String>(80000)
        val syllables = if (doublePinyin) HashSet<String>(512) else null

        fun add(list: List<W>, limit: Int, isSingle: Boolean) {
            val maxN = min(limit, list.size)
            for (rank in 0 until maxN) {
                val w = list[rank]
                val pr = rimePrior(w.weight, rank, isSingle)
                val code = if (doublePinyin) {
                    val sb = StringBuilder(w.syllables.size * 2)
                    var ok = true
                    for (s in w.syllables) {
                        val fp = flypy(s)
                        if (fp == null) { ok = false; break }
                        syllables?.add(fp)
                        sb.append(fp)
                    }
                    if (!ok || sb.length != w.syllables.size * 2) continue
                    sb.toString()
                } else {
                    val c = w.syllables.joinToString("")
                    if (c.length !in 2..18 || c.any { it !in 'a'..'z' }) continue
                    c
                }
                val prev = bestPrior[code]
                if (prev == null || pr > prev) {
                    bestPrior[code] = pr
                    bestWord[code] = w.word
                }
            }
        }
        add(singles, 4000, isSingle = true)
        add(words, 60000, isSingle = false)

        val codes = bestPrior.keys.toTypedArray()
        val outputs = Array(codes.size) { bestWord[codes[it]] ?: codes[it] }
        val freq = FloatArray(codes.size) { bestPrior[codes[it]]!! }
        val codeToIdx = if (doublePinyin) {
            HashMap<String, Int>(codes.size * 2).apply { codes.forEachIndexed { i, c -> put(c, i) } }
        } else null
        return buildDict(codes, outputs, freq, codeToIdx, syllables)
    }

    private val common = ("the of and to a in is it you that he was for on are with as i his they be at one have " +
            "this from or had by not word but what some we can out other were all there when up use your how said an " +
            "each she which do their time if will way about many then them write would like so these her long make " +
            "thing see him two has look more day could go come did number sound no most people my over know water " +
            "than call first who may down side been now find any new work part take get place made live where after " +
            "back little only round man year came show every good me give our under name very through just form " +
            "sentence great think say help low line differ turn cause much mean before move right boy old too same " +
            "tell does set three want air well also play small end put home read hand port large spell add even land " +
            "here must big high such follow act why ask men change went light kind off need house picture try us " +
            "again animal point mother world near build self earth father hello thanks thank yes okay ok love happy " +
            "sorry please really today tomorrow night morning going doing being got nice sure maybe " +
            "translate google keyboard android message phone search friend school family").split(' ')

    /**
     * English words: real usage frequencies from en_dicts/ifb_en_freq.tsv (AOSP LatinIME en_US list,
     * Apache-2.0, staged by ifboard/stage-data.sh), plus rime-ice's en.dict.yaml for coverage.
     * Falls back to the old small boost list when the frequency file is missing.
     */
    private fun loadEnglish(open: (String) -> InputStream): Dict {
        val outputs = LinkedHashMap<String, String>(64000)
        val prior = HashMap<String, Float>(64000)
        val hasFreq = runCatching {
            BufferedReader(InputStreamReader(open("en_dicts/ifb_en_freq.tsv"), Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val w = line.substring(0, tab)
                    val f = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                    val code = englishCode(w)
                    if (code.length < 2) continue
                    // LatinIME frequencies are already on a log scale (0..255)
                    val pr = 5.5f + 9f * f / 255f
                    val old = prior[code]
                    if (old == null || pr > old) {
                        prior[code] = pr
                        outputs[code] = w
                    }
                }
            }
            true
        }.getOrElse { Timber.w(it, "English frequency list missing, using the boost list"); false }
        val rank = HashMap<String, Int>().apply { common.forEachIndexed { i, w -> putIfAbsent(w, i) } }
        forEachEntry(open, "en_dicts/en.dict.yaml") { p ->
            val w = p[0].trim()
            val code = englishCode(w)
            if (code.length < 2) return@forEachEntry
            if (hasFreq) {
                if (code !in prior) {
                    outputs[code] = w
                    prior[code] = 5.0f - 0.10f * code.length
                }
            } else {
                val old = outputs[code]
                if (old == null || (w == w.lowercase() && old != old.lowercase())) outputs[code] = w
            }
        }
        val codes = outputs.keys.toTypedArray()
        val freq = FloatArray(codes.size) {
            val c = codes[it]
            prior[c] ?: run {
                val r = rank[c]
                if (r != null) 13f - 3.5f * r / common.size else 8.5f - 0.12f * c.length
            }
        }
        return buildDict(codes, Array(codes.size) { outputs[codes[it]]!! }, freq)
    }

    private fun englishCode(w: String) = buildString { for (c in w.lowercase()) if (c in 'a'..'z') append(c) }

    private fun buildDict(
        codes: Array<String>,
        outputs: Array<String>,
        freq: FloatArray,
        codeToIdx: HashMap<String, Int>? = null,
        validSyllables: HashSet<String>? = null
    ): Dict {
        val buckets = HashMap<Int, ArrayList<Int>>()
        codes.forEachIndexed { i, c ->
            buckets.getOrPut(bucket(c[0], c[c.length - 1])) { ArrayList() }.add(i)
        }
        val index = HashMap<Int, IntArray>(buckets.size)
        buckets.forEach { (k, v) -> index[k] = v.toIntArray() }
        return Dict(codes, outputs, freq, index, codeToIdx, validSyllables)
    }

    private fun bucket(a: Char, b: Char) = (a - 'a') * 26 + (b - 'a')

    // ---------------------------------------------------------------- geometry helpers

    private fun pathLength(xy: FloatArray, n: Int): Float {
        var l = 0f
        for (i in 1 until n) l += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        return l
    }

    private fun resample(xy: FloatArray, n: Int): FloatArray {
        val out = FloatArray(2 * M)
        val total = pathLength(xy, n)
        if (n < 2 || total <= 0f) {
            for (i in 0 until M) { out[2 * i] = xy[0]; out[2 * i + 1] = xy[1] }
            return out
        }
        val step = total / (M - 1)
        out[0] = xy[0]; out[1] = xy[1]
        var k = 1
        var acc = 0f
        var px = xy[0]
        var py = xy[1]
        var i = 1
        while (i < n && k < M) {
            val qx = xy[2 * i]
            val qy = xy[2 * i + 1]
            val d = hypot(qx - px, qy - py)
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                px += t * (qx - px)
                py += t * (qy - py)
                out[2 * k] = px; out[2 * k + 1] = py
                k++
                acc = 0f
            } else {
                acc += d
                px = qx; py = qy
                i++
            }
        }
        while (k < M) {
            out[2 * k] = xy[2 * n - 2]; out[2 * k + 1] = xy[2 * n - 1]
            k++
        }
        return out
    }

    private fun normalize(p: FloatArray, minSide: Float = 1.5f): FloatArray {
        var sx = 0f
        var sy = 0f
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until M) {
            val x = p[2 * i]
            val y = p[2 * i + 1]
            sx += x; sy += y
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        val cx = sx / M
        val cy = sy / M
        val s = max(max(x1 - x0, y1 - y0), minSide)
        return FloatArray(2 * M) { if (it % 2 == 0) (p[it] - cx) / s else (p[it] - cy) / s }
    }

    private fun keysOf(code: String, g: Geometry): IntArray? {
        val keys = ArrayList<Int>(code.length)
        for (c in code) {
            val k = c - 'a'
            if (k !in 0..25 || !g.present[k]) return null
            if (keys.isEmpty() || keys.last() != k) keys.add(k)
        }
        return if (keys.size in 2..32) keys.toIntArray() else null
    }

    private fun buildIdealPath(code: String, g: Geometry): IdealPath? {
        val keys = keysOf(code, g) ?: return null
        val L = keys.size
        val kpts = FloatArray(2 * L)
        val soft = FloatArray(2 * L)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (j in 0 until L) {
            val kx = g.normCx[keys[j]]
            val ky = g.normCy[keys[j]]
            kpts[2 * j] = kx; kpts[2 * j + 1] = ky
            if (kx < minX) minX = kx; if (kx > maxX) maxX = kx
            if (ky < minY) minY = ky; if (ky > maxY) maxY = ky
        }
        soft[0] = kpts[0]; soft[1] = kpts[1]
        for (j in 1 until L - 1) {
            soft[2 * j] = 0.84f * kpts[2 * j] + 0.08f * (kpts[2 * (j - 1)] + kpts[2 * (j + 1)])
            soft[2 * j + 1] = 0.84f * kpts[2 * j + 1] + 0.08f * (kpts[2 * (j - 1) + 1] + kpts[2 * (j + 1) + 1])
        }
        soft[2 * L - 2] = kpts[2 * L - 2]; soft[2 * L - 1] = kpts[2 * L - 1]
        val us = resample(soft, L)
        val un = normalize(us, 1.5f)
        val iLen = pathLength(kpts, L)
        return IdealPath(keys, kpts, soft, us, un, iLen, minX, maxX, minY, maxY)
    }

    private fun pointToSegDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val l2 = vx * vx + vy * vy
        if (l2 <= 1e-6f) {
            val dx = px - ax; val dy = py - ay
            return dx * dx + dy * dy
        }
        var t = ((px - ax) * vx + (py - ay) * vy) / l2
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val dx = px - (ax + t * vx)
        val dy = py - (ay + t * vy)
        return dx * dx + dy * dy
    }

    private fun traceToIdealErr(gs: FloatArray, segPts: FloatArray, L: Int, step: Int): Float {
        var sum = 0f
        var cnt = 0
        var gi = 0
        while (gi < M) {
            val px = gs[2 * gi]
            val py = gs[2 * gi + 1]
            var m2 = Float.MAX_VALUE
            for (seg in 0 until L - 1) {
                val d2 = pointToSegDist2(
                    px, py,
                    segPts[2 * seg], segPts[2 * seg + 1],
                    segPts[2 * seg + 2], segPts[2 * seg + 3]
                )
                if (d2 < m2) m2 = d2
            }
            sum += sqrt(m2)
            cnt++
            gi += step
        }
        return if (cnt > 0) sum / cnt else 0f
    }

    /** 顺序受限动态规划：强制候选字母按顺序贴近滑行轨迹，避免乱序假阳性 */
    private fun monotonicMiss(
        kpts: FloatArray,
        L: Int,
        gs: FloatArray,
        h: Float,
        freeR: Float,
        dpCur: FloatArray,
        dpNext: FloatArray
    ): Float {
        val kx0 = kpts[0]; val ky0 = kpts[1]
        for (i in 0 until M) dpCur[i] = 1e9f
        for (i in 0 until min(3, M)) {
            val d = hypot(gs[2 * i] - kx0, (gs[2 * i + 1] - ky0) / h)
            val ex = max(0f, d - freeR)
            dpCur[i] = ex * ex
        }
        for (j in 1 until L - 1) {
            val kx = kpts[2 * j]; val ky = kpts[2 * j + 1]
            var rmin = 1e9f
            for (i in 0 until M) {
                if (dpCur[i] < rmin) rmin = dpCur[i]
                if (rmin < 1e8f) {
                    val d = hypot(gs[2 * i] - kx, (gs[2 * i + 1] - ky) / h)
                    val ex = max(0f, d - freeR)
                    dpNext[i] = rmin + ex * ex
                } else {
                    dpNext[i] = 1e9f
                }
            }
            System.arraycopy(dpNext, 0, dpCur, 0, M)
        }
        val kxl = kpts[2 * L - 2]; val kyl = kpts[2 * L - 1]
        var best = 1e9f
        var pmin = 1e9f
        for (i in 0 until M) {
            if (dpCur[i] < pmin) pmin = dpCur[i]
            if (i >= M - 3 && pmin < 1e8f) {
                val d = hypot(gs[2 * i] - kxl, (gs[2 * i + 1] - kyl) / h)
                val ex = max(0f, d - freeR)
                val cost = pmin + ex * ex
                if (cost < best) best = cost
            }
        }
        return best
    }

    /** 弹性时间规整（Banded DTW）：消除转角弧度导致的速度相位漂移 */
    private fun bandedDtw(a: FloatArray, b: FloatArray, dpPrev: FloatArray, dpCur: FloatArray): Float {
        for (j in 0 until M) dpPrev[j] = 1e9f
        val ax0 = a[0]; val ay0 = a[1]
        for (j in 0 until min(M, DTW_BAND + 1)) {
            val d = hypot(ax0 - b[2 * j], ay0 - b[2 * j + 1])
            dpPrev[j] = if (j == 0) d else dpPrev[j - 1] + d
        }
        for (i in 1 until M) {
            for (j in 0 until M) dpCur[j] = 1e9f
            val j0 = max(0, i - DTW_BAND)
            val j1 = min(M - 1, i + DTW_BAND)
            val ax = a[2 * i]; val ay = a[2 * i + 1]
            for (j in j0..j1) {
                val cost = hypot(ax - b[2 * j], ay - b[2 * j + 1])
                var best = dpPrev[j]
                if (j > j0 && dpCur[j - 1] < best) best = dpCur[j - 1]
                if (j > 0 && dpPrev[j - 1] < best) best = dpPrev[j - 1]
                dpCur[j] = best + cost
            }
            System.arraycopy(dpCur, 0, dpPrev, 0, M)
        }
        return dpPrev[M - 1] / M
    }

    // ---------------------------------------------------------------- decoding

    private class Coarse(val id: Int, val ideal: IdealPath, val endPen: Float, val coarseScore: Float)

    fun decode(xy: FloatArray, n: Int, g: Geometry, mode: Mode, limit: Int = 5): List<Result> {
        val dict = when (mode) {
            Mode.Pinyin -> pinyin
            Mode.Flypy -> flypyDict
            Mode.English -> english
        } ?: return emptyList()
        if (n < 2) return emptyList()
        val unit = g.keyW
        if (unit <= 0f) return emptyList()
        val h = g.hRatio

        val pts = FloatArray(2 * n) { xy[it] / unit }
        val gLen = pathLength(pts, n)
        if (gLen < 0.25f) return emptyList()

        val gs = resample(pts, n)
        val gn = normalize(gs, 1.5f)
        var gx0 = Float.MAX_VALUE; var gx1 = -Float.MAX_VALUE
        var gy0 = Float.MAX_VALUE; var gy1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            val px = pts[2 * i]; val py = pts[2 * i + 1]
            if (px < gx0) gx0 = px; if (px > gx1) gx1 = px
            if (py < gy0) gy0 = py; if (py > gy1) gy1 = py
        }
        gx0 -= 1.7f; gx1 += 1.7f
        gy0 -= 1.7f * h; gy1 += 1.7f * h

        val sx0 = pts[0]; val sy0 = pts[1]
        val ex0 = pts[2 * n - 2]; val ey0 = pts[2 * n - 1]
        val starts = IntArray(26); val startD2 = FloatArray(26); var sCnt = 0
        val ends = IntArray(26); val endD2 = FloatArray(26); var eCnt = 0
        for (k in 0 until 26) {
            if (!g.present[k]) continue
            val dsx = g.normCx[k] - sx0
            val dsy = (g.normCy[k] - sy0) / h
            val d2s = dsx * dsx + dsy * dsy
            if (d2s <= NEAR * NEAR) { starts[sCnt] = k; startD2[sCnt] = d2s; sCnt++ }
            val dex = g.normCx[k] - ex0
            val dey = (g.normCy[k] - ey0) / h
            val d2e = dex * dex + dey * dey
            if (d2e <= NEAR * NEAR) { ends[eCnt] = k; endD2[eCnt] = d2e; eCnt++ }
        }

        val dpCur = FloatArray(M)
        val dpNext = FloatArray(M)
        val w = this.w
        val coarseLimit = w.coarseLimit
        val coarseHeap = PriorityQueue<Coarse>(coarseLimit + 1, compareBy { it.coarseScore })

        // Pass 1: fast bounding box + length + coarse trace coverage + monotonic alignment
        for (si in 0 until sCnt) {
            val sK = starts[si]
            val d2s = startD2[si]
            for (ei in 0 until eCnt) {
                val eK = ends[ei]
                val ids = dict.index[sK * 26 + eK] ?: continue
                val endPen = d2s + endD2[ei]
                for (id in ids) {
                    val code = dict.codes[id]
                    val ideal = g.cache[code] ?: buildIdealPath(code, g)?.also { g.cache[code] = it } ?: continue
                    val iLen = ideal.iLen
                    if (iLen > 1.90f * gLen + 1.4f || iLen < 0.42f * gLen - 1.2f) continue
                    if (ideal.minX < gx0 || ideal.maxX > gx1 || ideal.minY < gy0 || ideal.maxY > gy1) continue
                    val L = ideal.keys.size
                    val traceCoarse = traceToIdealErr(gs, ideal.kpts, L, step = 2)
                    if (traceCoarse > 1.35f) continue
                    val mono = monotonicMiss(ideal.kpts, L, gs, h, 0.30f, dpCur, dpNext)
                    val avgMono = mono / max(L - 1, 1)
                    val lenRatio = abs(iLen - gLen) / max(gLen, 1.5f)
                    val score1 = -2.2f * avgMono - 2.4f * traceCoarse * traceCoarse - 1.8f * endPen - 1.8f * lenRatio * lenRatio + w.coarseFreq * dict.freq[id]
                    if (coarseHeap.size < coarseLimit) {
                        coarseHeap.add(Coarse(id, ideal, endPen, score1))
                    } else if (score1 > coarseHeap.peek()!!.coarseScore) {
                        coarseHeap.poll()
                        coarseHeap.add(Coarse(id, ideal, endPen, score1))
                    }
                }
            }
        }

        // Pass 2: high-precision Banded DTW + Full trace coverage + Monotonic key alignment
        val fineHeap = PriorityQueue<Result>(limit + 1, compareBy { it.score })
        val freqW = when (mode) {
            Mode.Flypy -> w.freqFlypy
            Mode.Pinyin -> w.freqPinyin
            Mode.English -> w.freqEnglish
        }
        for (c in coarseHeap) {
            val ideal = c.ideal
            val L = ideal.keys.size
            val dtwLoc = bandedDtw(gs, ideal.us, dpCur, dpNext)
            val dtwShp = bandedDtw(gn, ideal.un, dpCur, dpNext)
            val traceErr = traceToIdealErr(gs, ideal.soft, L, step = 1)
            val missAvg = monotonicMiss(ideal.kpts, L, gs, h, 0.45f, dpCur, dpNext) / max(L - 1, 1)

            var chordDev = 0f
            if (L == 2 && gLen > 1.8f) {
                val ax = ideal.kpts[0]; val ay = ideal.kpts[1]
                val bx = ideal.kpts[2]; val by = ideal.kpts[3]
                var maxD2 = 0f
                var gi = 0
                while (gi < M) {
                    val d2 = pointToSegDist2(gs[2 * gi], gs[2 * gi + 1], ax, ay, bx, by)
                    if (d2 > maxD2) maxD2 = d2
                    gi += 2
                }
                val maxD = sqrt(maxD2)
                if (maxD > 0.65f) chordDev = 4.0f * (maxD - 0.65f) * (maxD - 0.65f)
            }
            val iLen = max(ideal.iLen, 0.5f)
            val lenMis = max(gLen / iLen, iLen / max(gLen, 0.5f)) - 1.0f
            val a = dtwLoc / 0.50f
            val b = dtwShp / 0.17f
            val rawCode = dict.codes[c.id]
            // 抑制“多键叠在两键上”造成的伪2键词（如 ddde 带的 / uuuh 书上 / niii 你吃 抢占 de 的 / uh 上 / ni 你）
            val repeatPen = if (mode != Mode.English) {
                val repeats = rawCode.length - L
                when {
                    L == 2 && rawCode.length >= 4 -> 4.5f
                    repeats > 0 -> 0.85f * repeats
                    else -> 0f
                }
            } else 0f
            val score = -w.loc * a * a - w.shp * b * b - w.trace * traceErr * traceErr - w.miss * missAvg -
                w.end * c.endPen - w.len * lenMis * lenMis - chordDev - repeatPen + freqW * dict.freq[c.id]

            if (fineHeap.size < limit) fineHeap.add(Result(rawCode, dict.outputs[c.id], score))
            else if (score > fineHeap.peek()!!.score) {
                fineHeap.poll(); fineHeap.add(Result(dict.codes[c.id], dict.outputs[c.id], score))
            }
        }
        return fineHeap.sortedByDescending { it.score }
    }
}
