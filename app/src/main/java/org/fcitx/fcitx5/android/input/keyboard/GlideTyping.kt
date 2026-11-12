/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: Gboard style glide typing (shape writing).
 *
 * Decoding follows the published SHARK2 idea (Kristensson & Zhai, 2004), the same family of
 * algorithms used by FlorisBoard's StatisticalGlideTypingClassifier: the gesture and the ideal
 * key-center path of every candidate word are resampled to the same number of points, then
 * compared by absolute location and by normalized shape, combined with a word frequency prior.
 * Candidates are pruned by the keys near the start and end of the gesture.
 *
 * Word lists come from the rime-ice data already bundled in the APK:
 *  - Pinyin: cn_dicts/8105 (single characters) + cn_dicts/base (2-4 character words), as full
 *    pinyin letters (rime_ice) or with each syllable converted to its 小鹤双拼 key pair
 *    (double_pinyin_flypy)
 *  - English: en_dicts/en (~21k words), with a small built-in boost list of very common words
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.PriorityQueue
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GlideTyping {

    enum class Mode { Pinyin, Flypy, English }

    class Result(val code: String, val output: String, val score: Float)

    /** key centers in pixels (index 0..25 = a..z), plus the typical key size */
    class Geometry(val cx: FloatArray, val cy: FloatArray, val present: BooleanArray, val keyW: Float, val keyH: Float) {
        internal val cache = HashMap<String, FloatArray>()
    }

    private class Dict(
        val codes: Array<String>,
        val outputs: Array<String>,
        val freq: FloatArray,
        val index: HashMap<Int, IntArray>
    )

    private const val N = 48
    private const val S_LOC = 0.5f
    private const val S_SHP = 0.10f
    private const val ALPHA = 0.6f
    private const val NEAR = 1.1f
    private const val END_W = 2f

    @Volatile
    private var pinyin: Dict? = null

    @Volatile
    private var flypy: Dict? = null

    @Volatile
    private var english: Dict? = null
    private val loading = HashSet<Mode>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "glide-dict").apply { priority = Thread.MIN_PRIORITY } }

    fun isReady(mode: Mode) = when (mode) {
        Mode.Pinyin -> pinyin != null
        Mode.Flypy -> flypy != null
        Mode.English -> english != null
    }

    /** load the word list for [mode] in background, no-op if already loaded or loading */
    fun prepare(context: Context, mode: Mode) {
        if (isReady(mode)) return
        synchronized(loading) { if (!loading.add(mode)) return }
        val app = context.applicationContext
        executor.execute {
            val t = System.currentTimeMillis()
            runCatching {
                when (mode) {
                    Mode.Pinyin -> pinyin = loadPinyin(app, false)
                    Mode.Flypy -> flypy = loadPinyin(app, true)
                    Mode.English -> english = loadEnglish(app)
                }
            }.onFailure { Timber.w(it, "glide dictionary $mode failed to load") }
            Timber.d("glide dictionary $mode loaded in ${System.currentTimeMillis() - t}ms")
            synchronized(loading) { loading.remove(mode) }
        }
    }

    // ---------------------------------------------------------------- dictionaries

    private fun openDict(context: Context, path: String) =
        BufferedReader(InputStreamReader(context.assets.open("usr/share/rime-data/$path"), Charsets.UTF_8), 1 shl 16)

    /** iterate the body (after "...") of a rime dict.yaml */
    private inline fun forEachEntry(context: Context, path: String, block: (List<String>) -> Unit) {
        openDict(context, path).use { r ->
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

    private fun loadPinyin(context: Context, doublePinyin: Boolean): Dict {
        class W(val weight: Long, val syllables: List<String>)
        val singles = ArrayList<W>(12000)
        val words = ArrayList<W>(400000)
        forEachEntry(context, "cn_dicts/8105.dict.yaml") { p ->
            if (p.size >= 3) singles.add(W(p[2].trim().toLongOrNull() ?: 0, listOf(p[1].trim())))
        }
        forEachEntry(context, "cn_dicts/base.dict.yaml") { p ->
            if (p.size < 3) return@forEachEntry
            val sy = p[1].split(' ')
            if (sy.size !in 2..4) return@forEachEntry
            words.add(W(p[2].trim().toLongOrNull() ?: 0, sy))
        }
        singles.sortByDescending { it.weight }
        words.sortByDescending { it.weight }
        val best = HashMap<String, Long>(80000)
        fun add(list: List<W>, limit: Int) {
            var n = 0
            for (w in list) {
                if (n >= limit) break
                n++
                val code = if (doublePinyin) {
                    val sb = StringBuilder(8)
                    for (s in w.syllables) sb.append(flypy(s) ?: break.also { sb.setLength(0) })
                    if (sb.length != w.syllables.size * 2) continue
                    sb.toString()
                } else {
                    val c = w.syllables.joinToString("")
                    if (c.length !in 2..18 || c.any { it !in 'a'..'z' }) continue
                    c
                }
                if ((best[code] ?: -1) < w.weight) best[code] = w.weight
            }
        }
        add(singles, 4000)
        add(words, 60000)
        val codes = best.keys.toTypedArray()
        val freq = FloatArray(codes.size) { ln(best[codes[it]]!!.toFloat() + 1f) }
        return buildDict(codes, codes, freq)
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
            "sorry please really today tomorrow night morning going doing being got nice sure maybe").split(' ')

    private fun loadEnglish(context: Context): Dict {
        val outputs = LinkedHashMap<String, String>(24000)
        forEachEntry(context, "en_dicts/en.dict.yaml") { p ->
            val w = p[0].trim()
            val code = buildString { for (c in w.lowercase()) if (c in 'a'..'z') append(c) }
            if (code.length < 2) return@forEachEntry
            val old = outputs[code]
            if (old == null || (w == w.lowercase() && old != old.lowercase())) outputs[code] = w
        }
        val rank = HashMap<String, Int>().apply { common.forEachIndexed { i, w -> putIfAbsent(w, i) } }
        val codes = outputs.keys.toTypedArray()
        val freq = FloatArray(codes.size) {
            val r = rank[codes[it]]
            if (r != null) 10f - 3f * r / common.size else 6f - 0.15f * codes[it].length
        }
        return buildDict(codes, Array(codes.size) { outputs[codes[it]]!! }, freq)
    }

    private fun buildDict(codes: Array<String>, outputs: Array<String>, freq: FloatArray): Dict {
        val buckets = HashMap<Int, ArrayList<Int>>()
        codes.forEachIndexed { i, c ->
            buckets.getOrPut(bucket(c[0], c[c.length - 1])) { ArrayList() }.add(i)
        }
        val index = HashMap<Int, IntArray>(buckets.size)
        buckets.forEach { (k, v) -> index[k] = v.toIntArray() }
        return Dict(codes, outputs, freq, index)
    }

    private fun bucket(a: Char, b: Char) = (a - 'a') * 26 + (b - 'a')

    // ---------------------------------------------------------------- geometry helpers

    private fun pathLength(xy: FloatArray, n: Int): Float {
        var l = 0f
        for (i in 1 until n) l += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        return l
    }

    /** resample a polyline of [n] points to [N] equidistant points */
    private fun resample(xy: FloatArray, n: Int): FloatArray {
        val out = FloatArray(2 * N)
        val total = pathLength(xy, n)
        if (n < 2 || total <= 0f) {
            for (i in 0 until N) { out[2 * i] = xy[0]; out[2 * i + 1] = xy[1] }
            return out
        }
        val step = total / (N - 1)
        out[0] = xy[0]; out[1] = xy[1]
        var k = 1
        var acc = 0f
        var px = xy[0]
        var py = xy[1]
        var i = 1
        while (i < n && k < N) {
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
        while (k < N) {
            out[2 * k] = xy[2 * n - 2]; out[2 * k + 1] = xy[2 * n - 1]
            k++
        }
        return out
    }

    /** translate to centroid and scale by the larger bounding box side (at least [minSide]) */
    private fun normalize(p: FloatArray, minSide: Float): FloatArray {
        var sx = 0f
        var sy = 0f
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until N) {
            val x = p[2 * i]
            val y = p[2 * i + 1]
            sx += x; sy += y
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        val cx = sx / N
        val cy = sy / N
        val s = max(max(x1 - x0, y1 - y0), minSide)
        return FloatArray(2 * N) { if (it % 2 == 0) (p[it] - cx) / s else (p[it] - cy) / s }
    }

    private fun keysOf(code: String, g: Geometry): IntArray? {
        val keys = ArrayList<Int>(code.length)
        for (c in code) {
            val k = c - 'a'
            if (k !in 0..25 || !g.present[k]) return null
            if (keys.isEmpty() || keys.last() != k) keys.add(k)
        }
        return keys.toIntArray()
    }

    // ---------------------------------------------------------------- decoding

    /**
     * @param xy gesture points (x0, y0, x1, y1, ...) in the same coordinate space as [g]
     * @return up to [limit] best candidates, best first; empty if nothing plausible or not loaded yet
     */
    fun decode(xy: FloatArray, n: Int, g: Geometry, mode: Mode, limit: Int = 5): List<Result> {
        val dict = when (mode) {
            Mode.Pinyin -> pinyin
            Mode.Flypy -> flypy
            Mode.English -> english
        } ?: return emptyList()
        if (n < 2) return emptyList()
        val unit = g.keyW
        val h = g.keyH / g.keyW
        // work in key-width units
        val pts = FloatArray(2 * n) { xy[it] / unit }
        val gs = resample(pts, n)
        val gn = normalize(gs, 1f)
        val gLen = pathLength(pts, n)
        val cx = FloatArray(26) { g.cx[it] / unit }
        val cy = FloatArray(26) { g.cy[it] / unit }

        fun near(x: Float, y: Float): List<Int> = (0 until 26).filter {
            if (!g.present[it]) return@filter false
            val dx = cx[it] - x
            val dy = (cy[it] - y) / h
            dx * dx + dy * dy <= NEAR * NEAR
        }

        val starts = near(pts[0], pts[1])
        val ends = near(pts[2 * n - 2], pts[2 * n - 1])
        val heap = PriorityQueue<Result>(limit + 1, compareBy { it.score })
        val ideal = FloatArray(64)
        for (s in starts) for (e in ends) {
            val ids = dict.index[s * 26 + e] ?: continue
            for (id in ids) {
                val code = dict.codes[id]
                val us = g.cache[code] ?: run {
                    val keys = keysOf(code, g) ?: return@run null
                    if (keys.size < 2 || keys.size > 32) return@run null
                    for (j in keys.indices) { ideal[2 * j] = cx[keys[j]]; ideal[2 * j + 1] = cy[keys[j]] }
                    resample(ideal, keys.size)
                }?.also { g.cache[code] = it } ?: continue
                // cheap length pruning using the resampled ideal path
                val iLen = pathLength(us, N)
                if (iLen > 1.8f * gLen + 1.5f || iLen < 0.55f * gLen - 1.5f) continue
                var loc = 0f
                for (i in 0 until N) loc += hypot(gs[2 * i] - us[2 * i], gs[2 * i + 1] - us[2 * i + 1])
                loc /= N
                if (loc > 3f * S_LOC) continue
                val un = normalize(us, 1f)
                var shp = 0f
                for (i in 0 until N) shp += hypot(gn[2 * i] - un[2 * i], gn[2 * i + 1] - un[2 * i + 1])
                shp /= N
                // every key of the word should be passed reasonably closely
                var miss = 0f
                val keys = keysOf(code, g)!!
                for (j in 1 until keys.size - 1) {
                    val kx = cx[keys[j]]
                    val ky = cy[keys[j]]
                    var m = Float.MAX_VALUE
                    for (i in 0 until N) {
                        val dx = gs[2 * i] - kx
                        val dy = (gs[2 * i + 1] - ky) / h
                        m = min(m, dx * dx + dy * dy)
                    }
                    miss += max(0f, sqrt(m) - 0.7f)
                }
                // the gesture starts and ends on the word's first and last key (e.g. hello vs help)
                val k0 = keys[0]
                val k1 = keys[keys.size - 1]
                val sx = pts[0] - cx[k0]
                val sy = (pts[1] - cy[k0]) / h
                val ex = pts[2 * n - 2] - cx[k1]
                val ey = (pts[2 * n - 1] - cy[k1]) / h
                val ends = sx * sx + sy * sy + ex * ex + ey * ey
                val a = loc / S_LOC
                val b = shp / S_SHP
                val score = -a * a / 2f - b * b / 2f - 4f * miss - END_W * ends + ALPHA * dict.freq[id]
                if (heap.size < limit) heap.add(Result(code, dict.outputs[id], score))
                else if (score > heap.peek()!!.score) {
                    heap.poll(); heap.add(Result(code, dict.outputs[id], score))
                }
            }
        }
        return heap.sortedByDescending { it.score }
    }
}
