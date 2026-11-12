/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: Gboard style vertically scrolling emoji / emoticon list.
 * Every item is one row of the grid; recently used rows come first, then each category.
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

class PickerListAdapter(
    val theme: Theme,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener,
    private val rawData: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    recentlyUsedFileName: String,
    private val bordered: Boolean,
    private val policy: PickerPolicy,
    /** how many rows fit in the visible area */
    private val visibleRows: Float
) : RecyclerView.Adapter<PickerListAdapter.ViewHolder>() {

    class ViewHolder(val ui: PickerPageUi) : RecyclerView.ViewHolder(ui.root) {
        var recent = false
    }

    private class Row(val items: List<String>, val recent: Boolean)

    private val columns = density.columnCount

    private val recentlyUsed = RecentlyUsed(recentlyUsedFileName, columns * RecentRows)

    private val rows = mutableListOf<Row>()

    /** category list, index 0 is "recently used", with the first row of every category */
    private val categories = mutableListOf<Pair<PickerData.Category, Int>>()

    private var lastInvalidateKey = policy.invalidateKey()

    init {
        rebuild()
    }

    private fun rebuild() {
        rows.clear()
        categories.clear()
        categories.add(PickerData.RecentlyUsedCategory to 0)
        recentlyUsed.items.chunked(columns).forEach { rows.add(Row(it, true)) }
        rawData.forEach { (cat, arr) ->
            categories.add(cat to rows.size)
            arr.filter(policy::filter).chunked(columns).forEach { rows.add(Row(it, false)) }
        }
    }

    /** rebuild rows (recently used may have changed) */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        lastInvalidateKey = policy.invalidateKey()
        rebuild()
        notifyDataSetChanged()
    }

    fun insertRecent(text: String) = recentlyUsed.insert(text)

    fun getCategoryList(): List<PickerData.Category> = categories.map { it.first }

    fun getCategoryStart(cat: Int): Int = categories[cat].second

    fun getCategoryIndexOfRow(row: Int): Int {
        if (row < 0) return 0
        var idx = 0
        categories.forEachIndexed { i, (_, start) -> if (row >= start && start < rows.size) idx = i }
        // "recently used" is empty: highlight the first real category
        return if (idx == 0 && rows.firstOrNull()?.recent != true) 1.coerceAtMost(categories.size - 1) else idx
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ui = PickerPageUi(parent.context, theme, density, bordered)
        val h = ((parent.height - parent.paddingTop - parent.paddingBottom) / visibleRows).toInt()
        ui.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h.coerceAtLeast(1))
        return ViewHolder(ui)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.recent = row.recent
        if (row.recent) holder.ui.setItems(row.items) else holder.ui.setItems(row.items, policy)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = keyActionListener
        // recently used items are displayed as-is, without popups
        holder.ui.popupActionListener = if (holder.recent) null else popupActionListener
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.ui.keyActionListener = null
        holder.ui.popupActionListener = null
    }

    companion object {
        const val RecentRows = 2
    }
}
