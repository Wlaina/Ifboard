/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme

abstract class StatusAreaAdapter : RecyclerView.Adapter<StatusAreaAdapter.Holder>() {
    inner class Holder(val ui: StatusAreaEntryUi) : RecyclerView.ViewHolder(ui.root)

    var entries: Array<StatusAreaEntry> = arrayOf()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            if (!suppressNotify) notifyDataSetChanged()
        }

    private fun silentEntries(value: Array<StatusAreaEntry>) {
        suppressNotify = true
        entries = value
        suppressNotify = false
    }

    private var suppressNotify = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(StatusAreaEntryUi(parent.context, theme))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.ui.setEntry(entry)
        holder.ui.root.setOnClickListener {
            // 拖动换位后 position 会变，按当前位置取条目
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(it, entries[pos])
        }
    }

    abstract val theme: Theme

    /** 拖动排序时交换位置，不整表刷新，以免打断拖动 */
    fun move(from: Int, to: Int) {
        if (from == to || from !in entries.indices || to !in entries.indices) return
        val list = entries.toMutableList()
        list.add(to, list.removeAt(from))
        silentEntries(list.toTypedArray())
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = entries.size

    abstract fun onItemClick(view: View, entry: StatusAreaEntry)
}