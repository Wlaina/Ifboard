/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.util.Random
import kotlin.math.hypot
import kotlin.math.max

/** standard phone QWERTY geometry plus synthetic noisy gestures / taps for offline benchmarks */
object KeyboardGeometryForTest {

    const val KEY_W = 120f
    const val KEY_H = 165f

    private val rows = arrayOf(
        Triple("qwertyuiop", 0.5f, 0),
        Triple("asdfghjkl", 1.0f, 1),
        Triple("zxcvbnm", 2.0f, 2)
    )

    fun qwerty(): GlideTyping.Geometry {
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        val present = BooleanArray(26)
        for ((letters, startX, row) in rows) {
            letters.forEachIndexed { i, ch ->
                val k = ch - 'a'
                cx[k] = (startX + i) * KEY_W
                cy[k] = (row + 0.5f) * KEY_H
                present[k] = true
            }
        }
        return GlideTyping.Geometry(cx, cy, present, KEY_W, KEY_H)
    }

    /** a tap aimed at [ch], with gaussian scatter of [noise] key widths / heights */
    fun synthTap(ch: Char, g: GlideTyping.Geometry, rnd: Random, noise: Float): Pair<Float, Float> {
        val k = ch - 'a'
        return (g.cx[k] + rnd.nextGaussian().toFloat() * noise * g.keyW) to
            (g.cy[k] + rnd.nextGaussian().toFloat() * noise * 0.85f * g.keyH)
    }

    /**
     * A gesture for [code]: aim points scattered around the key centers by [noise] key widths, corners
     * partly cut (people do not reach every key center), joined with a Catmull-Rom spline sampled every
     * ~9 px plus a little touch jitter.
     */
    fun synthGlide(code: String, g: GlideTyping.Geometry, rnd: Random, noise: Float): FloatArray? {
        val keys = ArrayList<Int>()
        for (c in code) {
            val k = c - 'a'
            if (k !in 0..25) return null
            if (keys.isEmpty() || keys.last() != k) keys.add(k)
        }
        if (keys.size < 2) return null
        val L = keys.size
        val px = FloatArray(L)
        val py = FloatArray(L)
        for (j in 0 until L) {
            px[j] = g.cx[keys[j]] + rnd.nextGaussian().toFloat() * noise * g.keyW
            py[j] = g.cy[keys[j]] + rnd.nextGaussian().toFloat() * noise * 0.8f * g.keyH
        }
        // corner cutting: interior points move towards the middle of their neighbours
        for (j in 1 until L - 1) {
            val u = rnd.nextFloat() * 0.30f
            px[j] += u * ((px[j - 1] + px[j + 1]) / 2 - px[j])
            py[j] += u * ((py[j - 1] + py[j + 1]) / 2 - py[j])
        }
        val out = ArrayList<Float>(256)
        fun at(i: Int, arr: FloatArray) = arr[i.coerceIn(0, L - 1)]
        for (s in 0 until L - 1) {
            val segLen = hypot(px[s + 1] - px[s], py[s + 1] - py[s])
            val steps = max(3, (segLen / 9f).toInt())
            for (t in 0 until steps) {
                val u = t.toFloat() / steps
                val u2 = u * u
                val u3 = u2 * u
                fun cr(arr: FloatArray): Float {
                    val p0 = at(s - 1, arr); val p1 = at(s, arr); val p2 = at(s + 1, arr); val p3 = at(s + 2, arr)
                    return 0.5f * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 +
                        (-p0 + 3 * p1 - 3 * p2 + p3) * u3)
                }
                out.add(cr(px) + rnd.nextGaussian().toFloat() * 1.5f)
                out.add(cr(py) + rnd.nextGaussian().toFloat() * 1.5f)
            }
        }
        out.add(px[L - 1]); out.add(py[L - 1])
        return out.toFloatArray()
    }
}
