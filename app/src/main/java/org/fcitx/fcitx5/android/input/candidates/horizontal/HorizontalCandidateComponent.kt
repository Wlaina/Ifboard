/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.keyboard.FlypyTypoCorrector
import org.fcitx.fcitx5.android.input.keyboard.GboardSpell
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        _expandedCandidateOffset.tryEmit(childCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total == childCount)
        )
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                }
                holder.itemView.setOnClickListener {
                    val engineIndex = engineIdx.getOrElse(holder.idx) { holder.idx }
                    if (engineIndex >= 0) {
                        if (flypy) FlypyTypoCorrector.onEngineCandidateSelected(holder.candidate.text)
                        fcitx.launchOnReady { it.select(engineIndex) }
                    } else if (engineIndex == -2) {
                        // 小鹤双拼邻键容错纠错词条：直接上屏中文词（不追加空格）并重置引擎
                        val word = holder.candidate.text
                        primaryCorrectionWord = null
                        FlypyTypoCorrector.clear()
                        service.commitText(word)
                        fcitx.launchOnReady { it.reset() }
                    } else {
                        // Gboard style correction from the system spell checker
                        val word = holder.candidate.text
                        service.currentInputConnection?.commitText("$word ", 1)
                        fcitx.launchOnReady { it.reset() }
                    }
                }
                holder.itemView.setOnLongClickListener {
                    val engineIndex = engineIdx.getOrElse(holder.idx) { holder.idx }
                    if (engineIndex >= 0)
                        inputView.showCandidateActionMenu(engineIndex, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = false
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.candidates.size) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded(cnt)
            }
            // no need to override `generate{,Default}LayoutParams`, because HorizontalCandidateViewAdapter
            // guarantees ViewHolder's layoutParams to be `FlexboxLayoutManager.LayoutParams`
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
        }
    }

    private val service by manager.inputMethodService()
    private var english = false
    private var flypy = false
    private var primaryCorrectionWord: String? = null
    private val spell by lazy { GboardSpell(context) }
    // maps displayed position -> engine candidate index (-1 for injected corrections, -2 for Flypy typos)
    private var engineIdx: IntArray = IntArray(0)
    private var lastEngineData: FcitxEvent.CandidateListEvent.Data? = null

    fun commitPrimaryCorrection(): Boolean {
        val word = primaryCorrectionWord ?: return false
        primaryCorrectionWord = null
        FlypyTypoCorrector.clear()
        service.commitText(word)
        fcitx.launchOnReady { it.reset() }
        return true
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        english = ime.uniqueName.startsWith("keyboard-")
        flypy = ime.uniqueName == "rime" && ime.subMode.run {
            name.contains("小鹤") || label.contains("小鹤") ||
                name.contains("flypy", ignoreCase = true) || label.contains("flypy", ignoreCase = true)
        }
        primaryCorrectionWord = null
        if (!flypy) FlypyTypoCorrector.clear()
        if (!english) spell.close()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (flypy) {
            val clientEmpty = fcitx.runImmediately { clientPreeditCached.isEmpty() }
            if (data.preedit.isEmpty() && data.auxUp.isEmpty() && clientEmpty) {
                FlypyTypoCorrector.clear()
            }
        }
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            primaryCorrectionWord = null
            FlypyTypoCorrector.clear()
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        lastEngineData = data
        primaryCorrectionWord = null
        engineIdx = IntArray(data.candidates.size) { it }
        if (flypy) {
            val sugs = FlypyTypoCorrector.suggest(data.candidates)
            if (sugs.isNotEmpty()) {
                val sugWords = sugs.map { it.word }.toSet()
                val promote = sugs[0].promotePrimary
                val merged = ArrayList<CandidateWord>(data.candidates.size + sugs.size)
                val idx = ArrayList<Int>(data.candidates.size + sugs.size)
                if (promote) {
                    sugs.forEach { merged.add(CandidateWord("", it.word, "")); idx.add(-2) }
                    data.candidates.forEachIndexed { i, c ->
                        if (c.text !in sugWords) { merged.add(c); idx.add(i) }
                    }
                    primaryCorrectionWord = sugs[0].word
                } else {
                    if (data.candidates.isNotEmpty()) {
                        merged.add(data.candidates[0]); idx.add(0)
                    }
                    sugs.forEach { merged.add(CandidateWord("", it.word, "")); idx.add(-2) }
                    for (i in 1 until data.candidates.size) {
                        val c = data.candidates[i]
                        if (c.text !in sugWords) { merged.add(c); idx.add(i) }
                    }
                }
                engineIdx = idx.toIntArray()
                show(merged.toTypedArray(), if (data.total < 0) -1 else data.total + sugs.size)
                return
            }
        }
        show(data.candidates, data.total)
        if (!english) return
        val word = data.candidates.firstOrNull()?.text ?: return
        if (word.length < 2 || !word.all { it.isLetter() || it == '\'' }) return
        spell.query(word) { r ->
            if (lastEngineData !== data || !r.looksLikeTypo || r.suggestions.isEmpty()) return@query
            val sugs = r.suggestions.take(3)
            val merged = mutableListOf(data.candidates[0])
            val idx = mutableListOf(0)
            sugs.forEach { merged.add(CandidateWord("", it, "")); idx.add(-1) }
            data.candidates.forEachIndexed { i, c ->
                if (i > 0 && sugs.none { it.equals(c.text, ignoreCase = true) }) {
                    merged.add(c); idx.add(i)
                }
            }
            engineIdx = idx.toIntArray()
            show(merged.toTypedArray(), if (data.total < 0) -1 else data.total + sugs.size)
        }
    }

    private fun show(candidates: Array<CandidateWord>, total: Int) {
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        adapter.updateCandidates(candidates, total)
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }
}
