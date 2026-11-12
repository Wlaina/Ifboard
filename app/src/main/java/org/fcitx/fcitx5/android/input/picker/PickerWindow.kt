/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class PickerWindow(
    override val key: Key,
    private val data: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    private val switchKey: KeyDef,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private lateinit var pickerLayout: PickerLayout
    private lateinit var pickerPagesAdapter: PickerPagesAdapter
    private lateinit var pickerListAdapter: PickerListAdapter

    // Gboard style: emoji and emoticons scroll vertically, symbols keep horizontal pages
    private val vertical = key != Key.Symbol

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? =
        if (key == Key.Symbol) null else super.exitAnimation(nextWindow)

    // Gboard: the emoji panel rises in; the symbol picker switches instantly like a layout
    override fun animateEnter(lastWindow: InputWindow) = key != Key.Symbol

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> {
                // Switch to NumberKeyboard before attaching KeyboardWindow
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(it.act)
                // The real switchLayout (detachCurrentLayout and attachLayout) in KeyboardWindow is postponed,
                // so we have to postpone attachWindow as well
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.FcitxKeyAction -> {
                // we want the behavior of CommitAction (commit the character as-is),
                // but don't want to include it in recently used list
                commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(it.act), source)
            }

            else -> {
                if (it is KeyAction.CommitAction) {
                    if (vertical) pickerListAdapter.insertRecent(it.text)
                    else pickerPagesAdapter.insertRecent(it.text)
                }
                commonKeyActionListener.listener.onKeyAction(it, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener {
            when (it) {
                is PopupAction.PreviewAction -> {
                    if (!popupPreview) return@PopupActionListener
                }
                is PopupAction.ShowKeyboardAction -> {
                    // prevent ViewPager from consuming swipe gesture when popup keyboard shown
                    pickerLayout.pager.isUserInputEnabled = false
                    pickerLayout.listLayoutManager.scrollEnabled = false
                }
                is PopupAction.DismissAction -> {
                    // restore ViewPager scrolling
                    pickerLayout.pager.isUserInputEnabled = true
                    pickerLayout.listLayoutManager.scrollEnabled = true
                }
                else -> {}
            }
            popup.listener.onPopupAction(it)
        }
    }

    override fun onCreateView() = PickerLayout(context, theme, switchKey, key, vertical).apply {
        pickerLayout = this
        val bordered = followKeyBorder && keyBorder
        if (vertical) {
            setupVerticalList(bordered)
            return@apply
        }
        pickerPagesAdapter = PickerPagesAdapter(
            theme, keyActionListener, popupActionListener, data,
            density, key.name, bordered, policy
        )
        tabsUi.apply {
            setTabs(pickerPagesAdapter.getCategoryList())
            setOnTabClickListener { i ->
                pager.setCurrentItem(pickerPagesAdapter.getRangeOfCategoryIndex(i).first, false)
            }
        }
        pager.apply {
            adapter = pickerPagesAdapter
            // show first symbol category by default, rather than recently used
            val range = pickerPagesAdapter.getRangeOfCategoryIndex(1)
            setCurrentItem(range.first, false)
            // update initial tab and page manually to avoid
            // "Adding or removing callbacks during dispatch to callbacks"
            tabsUi.activateTab(1)
            paginationUi.updatePageCount(range.run { last - first + 1 })
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    val range = pickerPagesAdapter.getCategoryRangeOfPage(position)
                    paginationUi.updatePageCount(range.run { last - first + 1 })
                    paginationUi.updateScrollProgress(position - range.first, positionOffset)
                }

                override fun onPageSelected(position: Int) {
                    tabsUi.activateTab(pickerPagesAdapter.getCategoryIndexOfPage(position))
                    popup.dismissAll()
                }
            })
        }
    }

    private fun PickerLayout.setupVerticalList(bordered: Boolean) {
        val rows = if (density == PickerPageUi.Density.EmoticonRow) 3.5f else 4.5f
        pickerListAdapter = PickerListAdapter(
            theme, keyActionListener, popupActionListener, data,
            density, key.name, bordered, policy, rows
        )
        tabsUi.apply {
            setTabs(pickerListAdapter.getCategoryList())
            setOnTabClickListener { i ->
                popup.dismissAll()
                listLayoutManager.scrollToPositionWithOffset(pickerListAdapter.getCategoryStart(i), 0)
                tabsUi.activateTab(i)
            }
        }
        list.adapter = pickerListAdapter
        tabsUi.activateTab(pickerListAdapter.getCategoryIndexOfRow(0))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) popup.dismissAll()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                val first = listLayoutManager.findFirstVisibleItemPosition()
                // at the very end, highlight the last category even if its header never reaches the top
                val row = if (!recyclerView.canScrollVertically(1)) pickerListAdapter.itemCount - 1 else first
                tabsUi.activateTab(pickerListAdapter.getCategoryIndexOfRow(row))
            }
        })
    }

    override fun onCreateBarExtension() = pickerLayout.tabsUi.root

    override fun onAttached() {
        pickerLayout.embeddedKeyboard.also {
            if (vertical) {
                pickerListAdapter.refresh()
                pickerLayout.listLayoutManager.scrollToPositionWithOffset(0, 0)
                pickerLayout.tabsUi.activateTab(pickerListAdapter.getCategoryIndexOfRow(0))
            } else {
                pickerPagesAdapter.refreshIfNeeded()
            }
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.keyActionListener = keyActionListener
        }
    }

    override fun onDetached() {
        popup.dismissAll()
        pickerLayout.embeddedKeyboard.keyActionListener = null
    }

    override val showTitle = false
}