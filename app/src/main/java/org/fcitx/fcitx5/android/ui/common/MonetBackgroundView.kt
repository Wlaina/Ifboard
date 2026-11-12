/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 暖纸 · 液态玻璃：超轻量静态暖纸渐变背景（零后台持续帧率开销，离屏缓存一次绘制）
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class MonetBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cachedBg: Bitmap? = null
    private var cachedDark: Boolean? = null
    private val srcRect = Rect()
    private val dstRect = Rect()

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 兼容旧调用方的空操作接口（已彻底移除持续 Choreographer 循环） */
    @Suppress("UNUSED_PARAMETER")
    fun pauseTemporarily(resumeDelayMs: Long = 700L) = Unit

    private fun ensureBitmap(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val dark = isNightMode()
        // 1/6 采样分辨率离屏纹理（不足 120 KB），放大双线性滤波自然柔和且极低内存占用
        val bw = max(1, w / 6)
        val bh = max(1, h / 6)
        cachedBg?.let { bmp ->
            if (!bmp.isRecycled && bmp.width == bw && bmp.height == bh && cachedDark == dark) {
                return bmp
            }
            bmp.recycle()
        }
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (dark) 0xFF141413.toInt() else 0xFFF5F4ED.toInt())

        val vmax = max(bw, bh).toFloat()
        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun drawSoftGlow(color: Int, alpha: Float, cx: Float, cy: Float, radius: Float) {
            val aInt = (alpha * 255f).toInt().coerceIn(0, 255)
            val centerColor = (color and 0x00FFFFFF) or (aInt shl 24)
            val midColor = (color and 0x00FFFFFF) or ((aInt * 3 / 10) shl 24)
            orbPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(centerColor, midColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, radius, orbPaint)
        }

        if (dark) {
            // 深色模式保留极克制的暖纸暗调层次，避免发灰发浑
            drawSoftGlow(0xFFD97757.toInt(), 0.14f, bw * 0.15f, bh * 0.10f, vmax * 0.65f)
            drawSoftGlow(0xFF7D8B6A.toInt(), 0.10f, bw * 0.88f, bh * 0.16f, vmax * 0.55f)
        } else {
            // 浅色暖纸顶部柔和赤陶 + 鼠尾草微晕
            drawSoftGlow(0xFFD97757.toInt(), 0.24f, bw * 0.14f, bh * 0.10f, vmax * 0.68f)
            drawSoftGlow(0xFF7D8B6A.toInt(), 0.18f, bw * 0.88f, bh * 0.14f, vmax * 0.60f)
            drawSoftGlow(0xFFD6AA68.toInt(), 0.16f, bw * 0.22f, bh * 0.88f, vmax * 0.52f)
        }
        cachedBg = bmp
        cachedDark = dark
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val bmp = ensureBitmap(w, h) ?: return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, w, h)
        canvas.drawBitmap(bmp, srcRect, dstRect, blitPaint)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cachedBg?.recycle()
        cachedBg = null
        cachedDark = null
        super.onDetachedFromWindow()
    }
}
