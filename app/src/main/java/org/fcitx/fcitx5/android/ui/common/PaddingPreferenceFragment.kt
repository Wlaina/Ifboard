/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 * Ifboard: warm paper cards + a plain-language note under every setting.
 */
package org.fcitx.fcitx5.android.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.ui.main.modified.MyPreferenceFragment
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.styledColorCompat

abstract class PaddingPreferenceFragment : MyPreferenceFragment() {

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.applyNavBarInsetsBottomPadding()
        listView.addItemDecoration(WarmCardDecoration(listView))
    }

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
        preferenceScreen?.let { applyNotes(it) }
    }

    override fun setPreferenceScreen(preferenceScreen: androidx.preference.PreferenceScreen?) {
        super.setPreferenceScreen(preferenceScreen)
        preferenceScreen?.let { applyNotes(it) }
    }

    private fun applyNotes(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val p = group.getPreference(i)
            if (p is PreferenceGroup) applyNotes(p)
            val note = SettingNotes.notes[p.key] ?: continue
            if (p.extras.getBoolean("ifb_noted")) continue
            p.extras.putBoolean("ifb_noted", true)
            val old = p.summaryProvider
            if (old != null) {
                @Suppress("UNCHECKED_CAST")
                val prov = old as Preference.SummaryProvider<Preference>
                p.summaryProvider = Preference.SummaryProvider<Preference> { pref ->
                    val v = prov.provideSummary(pref)
                    if (v.isNullOrEmpty()) note else "$v\n$note"
                }
            } else {
                val s = p.summary
                p.summary = if (s.isNullOrEmpty()) note else "$s\n$note"
            }
        }
    }

    /** Groups consecutive settings into rounded "warm paper" cards (radius 20dp) */
    private class WarmCardDecoration(list: RecyclerView) : RecyclerView.ItemDecoration() {
        private val density = list.resources.displayMetrics.density
        private val side = (14 * density).toInt()
        private val radius = 20 * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = list.context.styledColorCompat(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        }
        private val rect = RectF()

        private fun isCategory(parent: RecyclerView, pos: Int): Boolean {
            val adapter = parent.adapter as? PreferenceGroupAdapter ?: return false
            if (pos < 0 || pos >= adapter.itemCount) return true
            return adapter.getItem(pos) is PreferenceCategory
        }

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            outRect.left = side; outRect.right = side
            if (!isCategory(parent, pos) && isCategory(parent, pos + 1)) outRect.bottom = (8 * density).toInt()
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            var top = Float.NaN
            var bottom = 0f
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                if (isCategory(parent, pos)) {
                    if (!top.isNaN()) draw(c, parent, top, bottom)
                    top = Float.NaN
                    continue
                }
                val t = child.top + child.translationY
                val b = child.bottom + child.translationY
                if (top.isNaN()) top = t
                bottom = b
                if (isCategory(parent, pos + 1)) {
                    draw(c, parent, top, bottom)
                    top = Float.NaN
                }
            }
            if (!top.isNaN()) draw(c, parent, top, bottom)
        }

        private fun draw(c: Canvas, parent: RecyclerView, top: Float, bottom: Float) {
            rect.set(side.toFloat(), top, (parent.width - side).toFloat(), bottom)
            c.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
