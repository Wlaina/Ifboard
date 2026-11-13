/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 暖纸 · 液态玻璃：统一色板、字体（衬线标题 500）、胶囊按钮与 24dp 玻璃弹窗样式
 */
package org.fcitx.fcitx5.android.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

object WarmPaperUi {

    data class Palette(
        val canvas: Int,
        val surface: Int,
        val cardBg: Int,
        val glassBg: Int,
        val glassBorder: Int,
        val dialogBg: Int,
        val text: Int,
        val textSecondary: Int,
        val muted: Int,
        val divider: Int,
        val borderStrong: Int,
        val brand: Int,
        val brandInk: Int,
        val brandSoft: Int,
        val error: Int,
        val sage: Int,
        val amber: Int
    )

    val LIGHT = Palette(
        canvas = 0xFFF5F4ED.toInt(),
        surface = 0xFFFAF9F5.toInt(),
        cardBg = 0xFAFFFFFF.toInt(), // 干净暖白纸卡片，保证文字清晰对比度
        glassBg = 0xF0FAF9F5.toInt(),
        glassBorder = 0x99FFFFFF.toInt(),
        dialogBg = 0xF5FAF9F5.toInt(),
        text = 0xFF141413.toInt(),
        textSecondary = 0xFF5E5D59.toInt(),
        muted = 0xFF87867F.toInt(),
        divider = 0xFFF0EEE6.toInt(),
        borderStrong = 0xFFE6DFD2.toInt(),
        brand = 0xFFD97757.toInt(),
        brandInk = 0xFFC6613F.toInt(),
        brandSoft = 0x21D97757.toInt(),
        error = 0xFFB53333.toInt(),
        sage = 0xFF7D8B6A.toInt(),
        amber = 0xFFD97706.toInt()
    )

    val DARK = Palette(
        canvas = 0xFF141413.toInt(),
        surface = 0xFF232321.toInt(),
        cardBg = 0xF2232321.toInt(), // 深炭暖纸卡片，与 #141413 画布拉开清晰层次
        glassBg = 0xF01B1B19.toInt(),
        glassBorder = 0x1AFFFFFF.toInt(),
        dialogBg = 0xF5252523.toInt(),
        text = 0xFFFAF9F5.toInt(),
        textSecondary = 0xFFC2C0B6.toInt(),
        muted = 0xFF9C9A92.toInt(),
        divider = 0xFF30302E.toInt(),
        borderStrong = 0xFF343431.toInt(),
        brand = 0xFFD97757.toInt(),
        brandInk = 0xFFD97757.toInt(), // 深色下统一用 --brand 保证对比度
        brandSoft = 0x33D97757.toInt(),
        error = 0xFFB53333.toInt(),
        sage = 0xFF7D8B6A.toInt(),
        amber = 0xFFD97706.toInt()
    )

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun palette(context: Context): Palette = if (isDark(context)) DARK else LIGHT

    private var cachedSerif500: Typeface? = null

    fun serifWeight500(): Typeface {
        cachedSerif500?.let { return it }
        val tf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SERIF, 500, false)
        } else {
            Typeface.SERIF
        }
        cachedSerif500 = tf
        return tf
    }

    fun applySerifTitle(tv: TextView, sizeSp: Float? = null, useBrandInk: Boolean = false) {
        val p = palette(tv.context)
        tv.typeface = serifWeight500()
        tv.letterSpacing = 0.01f
        if (sizeSp != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setTextColor(if (useBrandInk) p.brandInk else p.text)
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** 配置边缘到边缘状态栏与导航栏对比度 */
    fun applySystemBars(activity: Activity) {
        val dark = isDark(activity)
        val window: Window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /** 顶栏采用液态玻璃半透明填充 + 底部细高光边框 + 衬线标题 */
    fun styleGlassToolbar(toolbar: Toolbar) {
        val ctx = toolbar.context
        val p = palette(ctx)
        val bg = GradientDrawable().apply { setColor(p.glassBg) }
        val divider = GradientDrawable().apply { setColor(p.borderStrong) }
        val layer = LayerDrawable(arrayOf(bg, divider)).apply {
            setLayerInset(1, 0, 0, 0, 0)
            // 仅保留底部 1dp 细描边
            setLayerGravity(1, android.view.Gravity.BOTTOM or android.view.Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, maxOf(1, dp(ctx, 0.8f)))
        }
        toolbar.background = layer
        toolbar.elevation = 0f
        toolbar.setTitleTextColor(p.text)
        styleToolbarChildren(toolbar, p)
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            styleToolbarChildren(toolbar, p)
        }
    }

    private fun styleToolbarChildren(toolbar: Toolbar, p: Palette) {
        toolbar.children.forEach { child ->
            when (child) {
                is TextView -> {
                    child.typeface = serifWeight500()
                    child.letterSpacing = 0.01f
                    child.setTextColor(p.text)
                }
                is ImageButton -> child.imageTintList = ColorStateList.valueOf(p.text)
            }
        }
        toolbar.navigationIcon?.setTint(p.text)
        toolbar.overflowIcon?.setTint(p.text)
    }

    /** 记录顶栏占用的顶部安全高度，供滚动列表设置为 paddingTop（clipToPadding = false 即可滑入玻璃顶栏下方） */
    fun attachToolbarTopInset(toolbar: Toolbar, root: View, onTopOffsetChanged: (Int) -> Unit) {
        val baseHeight = dp(toolbar.context, 56f)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = bars.top)
            toolbar.updateLayoutParams<ViewGroup.LayoutParams> {
                height = baseHeight + bars.top
            }
            onTopOffsetChanged(baseHeight + bars.top)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun pillDrawable(
        context: Context,
        fillColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(context, 999f)
        setColor(fillColor)
        if (strokeDp > 0f && strokeColor != Color.TRANSPARENT) {
            setStroke(maxOf(1, dp(context, strokeDp)), strokeColor)
        }
    }

    fun pillRipple(
        context: Context,
        fillColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f
    ): RippleDrawable {
        val p = palette(context)
        val content = pillDrawable(context, fillColor, strokeColor, strokeDp)
        val mask = pillDrawable(context, Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(p.brandSoft), content, mask)
    }

    /** 按规范着色系统 SwitchCompat（轨道关闭 --border-strong / 打开 --brand，圆钮白色） */
    fun applyPillSwitchDrawables(
        sw: CompoundButton,
        activeTrackColor: Int,
        inactiveTrackColor: Int,
        thumbColor: Int
    ) {
        val ctx = sw.context
        val radius = dpf(ctx, 13f)
        val trackW = dp(ctx, 48f)
        val trackH = dp(ctx, 26f)
        val checkedTrack = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(activeTrackColor)
            setSize(trackW, trackH)
        }
        val uncheckedTrack = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(inactiveTrackColor)
            setSize(trackW, trackH)
        }
        val track = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), checkedTrack)
            addState(intArrayOf(), uncheckedTrack)
        }
        val thumbSize = dp(ctx, 18f)
        val padH = dp(ctx, 3f)
        val padV = dp(ctx, 4f)
        val thumbStroke = if (androidx.core.graphics.ColorUtils.calculateLuminance(thumbColor) > 0.7) {
            0x26000000
        } else {
            0x33FFFFFF
        }
        val thumbCircle = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(thumbColor)
            setStroke(maxOf(1, dp(ctx, 1f)), thumbStroke)
            setSize(thumbSize, thumbSize)
        }
        // Zero optical padding so SwitchCompat/Switch keep the track inset around the thumb
        val thumb = object : android.graphics.drawable.DrawableWrapper(
            android.graphics.drawable.InsetDrawable(thumbCircle, padH, padV, padH, padV)
        ) {
            override fun getPadding(padding: android.graphics.Rect): Boolean {
                padding.set(0, 0, 0, 0)
                return false
            }
        }
        val minW = dp(ctx, 48f)
        when (sw) {
            is androidx.appcompat.widget.SwitchCompat -> {
                sw.trackDrawable = track
                sw.thumbDrawable = thumb
                sw.trackTintList = null
                sw.thumbTintList = null
                sw.splitTrack = false
                sw.thumbTextPadding = 0
                sw.switchMinWidth = minW
                sw.showText = false
            }
            is android.widget.Switch -> {
                sw.trackDrawable = track
                sw.thumbDrawable = thumb
                sw.trackTintList = null
                sw.thumbTintList = null
                sw.splitTrack = false
                sw.thumbTextPadding = 0
                sw.switchMinWidth = minW
                sw.showText = false
            }
        }
    }

    fun styleSwitches(root: android.view.View?) {
        when (root) {
            is CompoundButton -> styleSwitch(root)
            is android.view.ViewGroup -> {
                for (i in 0 until root.childCount) {
                    styleSwitches(root.getChildAt(i))
                }
            }
        }
    }

    fun styleSwitch(sw: CompoundButton) {
        val p = palette(sw.context)
        val dark = isDark(sw.context)
        val uncheckedTrack = if (dark) 0xFF54534D.toInt() else 0xFFD4CBC0.toInt()
        val thumbColor = if (dark) p.text else Color.WHITE
        applyPillSwitchDrawables(sw, p.brand, uncheckedTrack, thumbColor)
    }

    /** 24dp 暖纸弹窗：衬线标题 + 胶囊主次按钮（danger 用于高风险确认） */
    private fun applyDialogTheme(
        context: Context,
        window: Window?,
        findView: (Int) -> View?,
        getButton: (Int) -> android.widget.Button?,
        destructive: Boolean
    ) {
        val p = palette(context)
        window?.apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(context, 24f)
                setColor(p.dialogBg)
                setStroke(maxOf(1, dp(context, 1f)), p.borderStrong)
            }
            setBackgroundDrawable(bg)
        }
        val titleIds = listOfNotNull(
            context.resources.getIdentifier("alertTitle", "id", context.packageName).takeIf { it != 0 },
            context.resources.getIdentifier("alertTitle", "id", "android").takeIf { it != 0 }
        )
        titleIds.firstNotNullOfOrNull { findView(it) as? TextView }?.let { tv ->
            applySerifTitle(tv, sizeSp = 19f, useBrandInk = false)
        }
        (findView(android.R.id.message) as? TextView)?.apply {
            setTextColor(p.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setLineSpacing(dpf(context, 2f), 1.05f)
        }
        getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            val fill = if (destructive) p.error else p.brand
            androidx.core.view.ViewCompat.setBackgroundTintList(this, null)
            backgroundTintList = null
            background = pillRipple(context, fill)
            setTextColor(Color.WHITE)
            isAllCaps = false
            typeface = Typeface.DEFAULT
            setPadding(dp(context, 18f), dp(context, 8f), dp(context, 18f), dp(context, 8f))
            minHeight = dp(context, 38f)
        }
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            getButton(which)?.apply {
                androidx.core.view.ViewCompat.setBackgroundTintList(this, null)
                backgroundTintList = null
                background = pillRipple(context, p.surface, p.borderStrong, 1f)
                setTextColor(p.textSecondary)
                isAllCaps = false
                setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
                minHeight = dp(context, 38f)
            }
        }
    }

    fun styleAlertDialog(dialog: AlertDialog, destructive: Boolean = false): AlertDialog {
        applyDialogTheme(
            dialog.context,
            dialog.window,
            { id -> dialog.findViewById(id) },
            { which -> dialog.getButton(which) },
            destructive
        )
        return dialog
    }

    fun styleAlertDialog(
        dialog: androidx.appcompat.app.AlertDialog,
        destructive: Boolean = false
    ): androidx.appcompat.app.AlertDialog {
        applyDialogTheme(
            dialog.context,
            dialog.window,
            { id -> dialog.findViewById(id) },
            { which -> dialog.getButton(which) },
            destructive
        )
        return dialog
    }
}

fun AlertDialog.showWarm(destructive: Boolean = false): AlertDialog {
    show()
    return WarmPaperUi.styleAlertDialog(this, destructive)
}

fun androidx.appcompat.app.AlertDialog.showWarm(
    destructive: Boolean = false
): androidx.appcompat.app.AlertDialog {
    show()
    return WarmPaperUi.styleAlertDialog(this, destructive)
}
