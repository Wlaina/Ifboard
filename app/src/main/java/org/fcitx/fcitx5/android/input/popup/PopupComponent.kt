/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.Glass
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

/** Gboard pinyin long-press extras (first one sits on the bottom row next to the letter) */
val GboardTones = mapOf(
    "a" to listOf("ā", "á", "ǎ", "à"),
    "e" to listOf("ê", "é", "ě", "ē", "è"),
    "i" to listOf("ī", "í", "ǐ", "ì"),
    "o" to listOf("ō", "ó", "ǒ", "ò"),
    "u" to listOf("ü", "ú", "ǔ", "ū", "ù"),
    "v" to listOf("ü", "ǘ", "ǚ", "ǖ", "ǜ")
)

/** Gboard style symbol panels: fixed grid, row by row from top */
data class GboardGrid(val columns: Int, val focus: Int, val items: Array<String>)

val GboardSymbolGrid = mapOf(
    "." to GboardGrid(
        8, 14,
        arrayOf(
            "^_^", ":)", ":(", ":D", ":P", "&", "$", "^",
            "%", "_", ";", ":", "、", "!", "……", "?"
        )
    )
)

class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val keyBottomMargin by lazy {
        context.dp(ThemeManager.prefs.keyVerticalMargin.getValue())
    }
    private val popupWidth by lazy {
        context.dp(38)
    }
    private val popupHeight by lazy {
        context.dp(116)
    }
    private val popupKeyHeight by lazy {
        context.dp(48)
    }
    private val popupRadius by lazy {
        context.dp(ThemeManager.prefs.keyRadius.getValue()).toFloat()
    }
    private val hideThreshold = 100L

    private val keySideMargin by lazy {
        context.dp(ThemeManager.prefs.keyHorizontalMargin.getValue())
    }
    private val bubbleGap by lazy { context.dp(4) }

    // Gboard style: dim the keyboard while a long-press popup is shown
    private val scrim by lazy {
        View(context).apply {
            setBackgroundColor(0x33000000)
            isClickable = false
            isFocusable = false
        }
    }

    private val inputView by manager.inputView()
    private val scrimLoc = IntArray(2)

    private fun showScrim() {
        Glass.frost(listOf(inputView.keyboardView), true)
        // only dim the keyboard area (toolbar + keys), not the app behind it
        val kv = inputView.keyboardView
        kv.getLocationInWindow(scrimLoc)
        val lp = FrameLayout.LayoutParams(-1, kv.height).apply {
            topMargin = scrimLoc[1] - rootBounds.top
        }
        if (scrim.parent == null) root.addView(scrim, 0, lp) else scrim.layoutParams = lp
    }

    private fun hideScrimIfIdle() {
        if (showingContainerUi.isEmpty()) {
            root.removeView(scrim)
            Glass.frost(listOf(inputView.keyboardView), false)
        }
    }

    /** Gboard order: [digit, lower, upper, accents...] for the number row, otherwise [lower, upper, accents...] */
    private fun gboardOrder(label: String, preset: Array<String>): Array<String> {
        if (label.length != 1 || !label[0].isLetter() || preset.isEmpty()) return preset
        val lower = label.lowercase()
        val rest = preset.filter { !(it.length == 1 && !it[0].isLetterOrDigit()) && it != lower }
        val digit = rest.firstOrNull { it.length == 1 && it[0].isDigit() }
        // Gboard pinyin layout: upper case + pinyin tone letters for vowels
        val tones = GboardTones[lower] ?: emptyList()
        val others = listOf(label.uppercase()) + tones
        return (listOfNotNull(digit) + lower + others).toTypedArray()
    }

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        showingEntryUi[viewId]?.apply {
            dismissJobs[viewId]?.also {
                dismissJobs.remove(viewId)?.cancel()
            }
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        // Gboard style: a rounded bubble floating right above the key, as wide as the key
        val bubbleWidth = maxOf(bounds.width() - 2 * keySideMargin, popupWidth)
        popup.root.layoutParams = FrameLayout.LayoutParams(bubbleWidth, popupKeyHeight).apply {
            topMargin = bounds.top + keyBottomMargin - bubbleGap - popupKeyHeight - rootBounds.top
            leftMargin = (bounds.left + bounds.right - bubbleWidth) / 2 - rootBounds.left
        }
        // make sure that popup.root does not have parent view before adding it under root container
        // it's wired that on some devices it would have a parent view despite it was newly created
        // or just polled from freeEntryUi
        if (popup.root.parent == null) {
            root.addView(popup.root)
        } else if (popup.root.parent !== root) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            root.addView(popup.root)
        }
        showingEntryUi[viewId] = popup
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        var keys: Array<String>
        var labels: Array<String>
        var grid = 0
        var initialFocus = 0
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val gb = GboardSymbolGrid[keyboard.label]
                val preset = gb?.items ?: gboardOrder(keyboard.label, PopupPreset[keyboard.label] ?: return)
                if (gb != null) { grid = gb.columns; initialFocus = gb.focus }
                keys = preset
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    Array(keys.size) { punctuation.transform(keys[it]) }
                } else keys
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                keys = keyboard.items
                labels = keyboard.items
            }
        }
        // Gboard style: the popup keyboard replaces the preview bubble
        showingEntryUi[viewId]?.let { dismissPopupEntry(viewId, it) }
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // bottom row sits right above the key, like the preview bubble
            bounds.height() - keyBottomMargin + bubbleGap + popupKeyHeight,
            keys,
            labels,
            grid,
            initialFocus
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items,
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        showScrim()
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                dismissJobs[viewId] = service.lifecycleScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
        hideScrimIfIdle()
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        showingEntryUi.remove(viewId)
        root.removeView(popup.root)
        freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        root.removeView(scrim)
        Glass.frost(listOf(inputView.keyboardView), false)
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
