/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard "warm paper · liquid glass": blurred translucent keyboard background (cross-window blur)
 * and frosted long-press popups (the keyboard behind them is blurred with RenderEffect).
 */
package org.fcitx.fcitx5.android.input

import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

object Glass {

    val enabled: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ThemeManager.prefs.glassEffect.getValue()

    /** translucent version of a surface color (template "standard" glass: ~70% opacity) */
    @ColorInt
    fun tint(@ColorInt color: Int, alpha: Float = 0.70f): Int =
        ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())

    private var exempted = false

    /**
     * Put a BackgroundBlurDrawable (blurs other windows behind exactly this view) on [view].
     * @return false when cross-window blur is unavailable; caller keeps the opaque background
     */
    fun applyBackgroundBlur(
        view: View,
        @ColorInt color: Int,
        radiusDp: Int = 28,
        cornerRadiusDp: Float = 0f,
        host: View = view
    ): Boolean {
        if (!enabled) return false
        val wm = view.context.getSystemService(WindowManager::class.java) ?: return false
        if (!wm.isCrossWindowBlurEnabled) return false
        return runCatching {
            if (!exempted) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/view/", "Lcom/android/internal/graphics/")
                exempted = true
            }
            val density = view.resources.displayMetrics.density
            val existing = view.background
            val d = if (existing != null && existing.javaClass.name.endsWith("BackgroundBlurDrawable")) {
                existing
            } else {
                val vri = View::class.java.getMethod("getViewRootImpl").invoke(host)
                    ?: return false
                vri.javaClass.getMethod("createBackgroundBlurDrawable").invoke(vri) as Drawable
            }
            val px = (radiusDp * density).toInt()
            d.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(d, px)
            d.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(d, tint(color))
            d.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(d, cornerRadiusDp * density)
            if (view.background !== d) {
                view.background = d
            }
            invalidateBlur(view)
            true
        }.onFailure { Timber.w(it, "background blur unavailable") }.getOrDefault(false)
    }

    /** Update blur corner radius in place when switching between docked and floating mode */
    fun updateCornerRadius(view: View, cornerRadiusDp: Float) {
        val d = view.background ?: return
        if (!d.javaClass.name.endsWith("BackgroundBlurDrawable")) return
        runCatching {
            val px = cornerRadiusDp * view.resources.displayMetrics.density
            d.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType).invoke(d, px)
            d.javaClass.getMethod("invalidateBlurRegion").invoke(d)
            view.invalidate()
        }
    }

    /** Force SurfaceFlinger blur region to resync immediately (used while dragging floating keyboard) */
    fun invalidateBlur(view: View) {
        val d = view.background ?: return
        if (!d.javaClass.name.endsWith("BackgroundBlurDrawable")) return
        runCatching {
            d.javaClass.getMethod("invalidateBlurRegion").invoke(d)
        }
        view.invalidate()
    }

    /** Frost the given views (used behind long-press popups) */
    fun frost(views: List<View>, on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = if (on && enabled) RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP) else null
        views.forEach { it.setRenderEffect(effect) }
    }
}
