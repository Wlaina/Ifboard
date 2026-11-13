/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 * Ifboard: 暖纸 · 液态玻璃 —— 半透明圆角卡片、暖棕轻阴影、衬线分类标题与设置注释
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.modified.MyPreferenceFragment
import splitties.views.bottomPadding
import splitties.views.topPadding
import kotlin.math.max

abstract class PaddingPreferenceFragment : MyPreferenceFragment() {

    private var toolbarLayoutListener: View.OnLayoutChangeListener? = null
    private var attachedToolbar: View? = null

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        val rv = listView
        rv.setBackgroundColor(Color.TRANSPARENT)
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_ALWAYS
        rv.addItemDecoration(WarmCardDecoration(rv))

        val baseBottom = WarmPaperUi.dp(rv.context, 20f)
        ViewCompat.setOnApplyWindowInsetsListener(rv) { _, windowInsets ->
            val nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val mandatory = windowInsets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val wantBottom = max(nav.bottom, mandatory.bottom) + baseBottom
            if (rv.paddingBottom != wantBottom) rv.bottomPadding = wantBottom
            windowInsets
        }

        // 在顶层设置页（非 ViewPager 子页）给列表预留液态玻璃顶栏高度，同时让内容滑进顶栏下方
        val nestedInTab = parentFragment != null && parentFragment !is NavHostFragment
        if (!nestedInTab) {
            val tb = activity?.findViewById<View>(R.id.toolbar)
            if (tb != null) {
                val extra = WarmPaperUi.dp(rv.context, 8f)
                rv.topPadding = WarmPaperUi.dp(rv.context, 92f)
                var userScrolled = false
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState != RecyclerView.SCROLL_STATE_IDLE) userScrolled = true
                    }
                })
                val syncTop = {
                    val tbH = maxOf(tb.bottom, tb.height)
                    val targetTop = tbH + extra
                    if (tbH > 0 && rv.paddingTop != targetTop) {
                        rv.topPadding = targetTop
                        if (!userScrolled) {
                            (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                                ?.scrollToPositionWithOffset(0, 0)
                        }
                    }
                }
                if (tb.height > 0 || tb.bottom > 0) syncTop() else tb.doOnLayout { syncTop() }
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncTop() }
                tb.addOnLayoutChangeListener(listener)
                attachedToolbar = tb
                toolbarLayoutListener = listener
            }
        } else {
            rv.topPadding = WarmPaperUi.dp(rv.context, 8f)
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    activity?.findViewById<MonetBackgroundView>(R.id.monet_bg)?.pauseTemporarily()
                }
            }
        })

        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleAttachedRow(rv, view)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        preferenceScreen?.let { applyNotes(it) }
        listView?.post { preferenceScreen?.let { applyNotes(it) } }
    }

    override fun onDestroyView() {
        toolbarLayoutListener?.let { attachedToolbar?.removeOnLayoutChangeListener(it) }
        toolbarLayoutListener = null
        attachedToolbar = null
        super.onDestroyView()
    }

    override fun setPreferenceScreen(preferenceScreen: androidx.preference.PreferenceScreen?) {
        super.setPreferenceScreen(preferenceScreen)
        preferenceScreen?.let { applyNotes(it) }
    }

    private fun styleAttachedRow(rv: RecyclerView, view: View) {
        val ctx = view.context
        val p = WarmPaperUi.palette(ctx)
        val adapter = rv.adapter as? PreferenceGroupAdapter
        val pos = rv.getChildAdapterPosition(view)
        val item = if (adapter != null && pos in 0 until adapter.itemCount) adapter.getItem(pos) else null

        if (item is PreferenceCategory) {
            view.background = null
            view.minimumHeight = WarmPaperUi.dp(ctx, 28f)
            view.setPaddingRelative(
                WarmPaperUi.dp(ctx, 18f),
                WarmPaperUi.dp(ctx, 10f),
                WarmPaperUi.dp(ctx, 18f),
                WarmPaperUi.dp(ctx, 4f)
            )
            view.findViewById<TextView>(android.R.id.title)?.apply {
                WarmPaperUi.applySerifTitle(this, sizeSp = 13.5f, useBrandInk = true)
                isSingleLine = false
            }
            return
        }

        // 自定义视图类偏好项（如 WebDAV 服务商分段胶囊）保留其自身布局内边距，只设透明背景
        val titleView = view.findViewById<TextView>(android.R.id.title)
        if (titleView == null) {
            view.setBackgroundColor(Color.TRANSPARENT)
            return
        }

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = WarmPaperUi.dpf(ctx, 16f)
            setColor(Color.WHITE)
        }
        view.background = RippleDrawable(ColorStateList.valueOf(p.brandSoft), null, mask)
        view.minimumHeight = WarmPaperUi.dp(ctx, 46f)
        view.setPaddingRelative(
            WarmPaperUi.dp(ctx, 16f),
            WarmPaperUi.dp(ctx, 10f),
            WarmPaperUi.dp(ctx, 16f),
            WarmPaperUi.dp(ctx, 10f)
        )

        titleView.apply {
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT
            isSingleLine = false
            maxLines = 3
        }
        view.findViewById<android.widget.ImageView>(android.R.id.icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(p.textSecondary)
        view.findViewById<TextView>(android.R.id.summary)?.apply {
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(WarmPaperUi.dpf(ctx, 1.5f), 1.06f)
            maxLines = 6
        }
        view.findViewById<View>(android.R.id.widget_frame)?.let { styleSwitchesRecursively(it) }
    }

    private fun styleSwitchesRecursively(v: View) {
        if (v is CompoundButton) {
            WarmPaperUi.styleSwitch(v)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) styleSwitchesRecursively(v.getChildAt(i))
        }
    }

    private fun applyNotes(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val p = group.getPreference(i)
            if (p is PreferenceGroup) {
                applyNotes(p)
                continue
            }
            val note = SettingNotes.lookup(p) ?: continue
            if (p.extras.getBoolean("ifb_noted")) continue
            p.extras.putBoolean("ifb_noted", true)
            val old = p.summaryProvider
            if (old != null) {
                @Suppress("UNCHECKED_CAST")
                val prov = old as Preference.SummaryProvider<Preference>
                p.summaryProvider = Preference.SummaryProvider<Preference> { pref ->
                    val v = prov.provideSummary(pref)?.toString()?.trim()
                    when {
                        v.isNullOrEmpty() -> note
                        v.contains(note) -> v
                        v.length <= 16 -> "$v · $note"
                        else -> "$v\n$note"
                    }
                }
            } else {
                val s = p.summary?.toString()?.trim()
                // 已有自定义摘要时不再叠加大段重复解释，保持界面清爽
                if (s.isNullOrEmpty()) p.summary = note
            }
        }
    }

    /** 将相邻设置项合并成 18dp 圆角暖纸卡片，单层轻描边 + 极简阴影减少重绘开销 */
    private class WarmCardDecoration(list: RecyclerView) : RecyclerView.ItemDecoration() {
        private val ctx = list.context
        private val p = WarmPaperUi.palette(ctx)
        private val dark = WarmPaperUi.isDark(ctx)
        private val density = list.resources.displayMetrics.density
        private val side = (14f * density).toInt()
        private val radius = 18f * density
        private val dividerInset = 16f * density

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.cardBg
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, 0.85f * density)
            color = p.borderStrong
        }
        private val shadowLight = if (dark) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            color = 0x0C3C2814
        }
        private val dividerPaint = Paint().apply {
            strokeWidth = maxOf(1f, 0.75f * density)
            color = p.divider
        }
        private val rect = RectF()
        private val shadowRect = RectF()

        private fun isCategory(parent: RecyclerView, pos: Int): Boolean {
            val adapter = parent.adapter as? PreferenceGroupAdapter ?: return false
            if (pos < 0 || pos >= adapter.itemCount) return true
            return adapter.getItem(pos) is PreferenceCategory
        }

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            outRect.left = side
            outRect.right = side
            if (isCategory(parent, pos)) {
                outRect.top = (6 * density).toInt()
                outRect.bottom = (2 * density).toInt()
            } else {
                if (pos == 0) outRect.top = (4 * density).toInt()
                if (isCategory(parent, pos + 1)) outRect.bottom = (8 * density).toInt()
            }
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            var top = Float.NaN
            var bottom = 0f
            val dividers = mutableListOf<Float>()
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                if (isCategory(parent, pos)) {
                    if (!top.isNaN()) {
                        drawCard(c, parent, top, bottom, dividers)
                        dividers.clear()
                    }
                    top = Float.NaN
                    continue
                }
                val t = child.top + child.translationY
                val b = child.bottom + child.translationY
                if (top.isNaN()) top = t
                bottom = b
                if (!isCategory(parent, pos + 1)) {
                    dividers += b
                } else {
                    drawCard(c, parent, top, bottom, dividers)
                    dividers.clear()
                    top = Float.NaN
                }
            }
            if (!top.isNaN()) drawCard(c, parent, top, bottom, dividers)
        }

        private fun drawCard(
            c: Canvas,
            parent: RecyclerView,
            top: Float,
            bottom: Float,
            dividers: List<Float>
        ) {
            val left = side.toFloat()
            val right = (parent.width - side).toFloat()
            if (shadowLight != null) {
                shadowRect.set(left, top + 1.2f * density, right, bottom + 1.2f * density)
                c.drawRoundRect(shadowRect, radius, radius, shadowLight)
            }
            rect.set(left, top, right, bottom)
            c.drawRoundRect(rect, radius, radius, fillPaint)
            c.drawRoundRect(rect, radius, radius, strokePaint)

            val x1 = left + dividerInset
            val x2 = right - dividerInset
            for (y in dividers) {
                c.drawLine(x1, y, x2, y, dividerPaint)
            }
        }
    }
}
