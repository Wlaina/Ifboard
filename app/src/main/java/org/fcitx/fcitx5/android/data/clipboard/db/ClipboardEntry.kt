/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.utils.timestamp

@Entity(tableName = ClipboardEntry.TABLE_NAME)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = ClipDescription.MIMETYPE_TEXT_PLAIN)
    val type: String = ClipDescription.MIMETYPE_TEXT_PLAIN,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false
) {
    companion object {
        const val BULLET = "•"

        const val TABLE_NAME = "clipboard"

        private val IS_SENSITIVE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }

        fun fromClipData(
            clipData: ClipData,
            transformer: ((String) -> String)? = null,
            context: Context? = null
        ): ClipboardEntry? {
            if (clipData.itemCount <= 0) return null
            val desc = clipData.description
            val hasImage = (0 until desc.mimeTypeCount).any {
                desc.getMimeType(it).startsWith("image/")
            }
            // TODO: handle multiple items (when does this happen?)
            val item = clipData.getItemAt(0) ?: return null
            val str = item.text?.toString()
                ?: (if (!hasImage && context != null) item.coerceToText(context)?.toString() else null)
                ?: return null
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                desc.extras?.getBoolean(IS_SENSITIVE) ?: false
            } else {
                false
            }
            return ClipboardEntry(
                text = if (transformer != null) transformer(str) else str,
                timestamp = clipData.timestamp(),
                type = if (desc.mimeTypeCount > 0) desc.getMimeType(0) else ClipDescription.MIMETYPE_TEXT_PLAIN,
                sensitive = sensitive
            )
        }

        /** 根据时间戳（及同时间戳下的 id）筛选超出上限的过期未置顶条目 id。 */
        internal fun selectOutdatedIds(unpinned: List<ClipboardEntry>, limit: Int): IntArray {
            if (unpinned.size <= limit) return intArrayOf()
            if (limit <= 0) {
                return unpinned.map { it.id }.toIntArray()
            }
            return unpinned
                .sortedWith(compareByDescending<ClipboardEntry> { it.timestamp }.thenByDescending { it.id })
                .drop(limit)
                .map { it.id }
                .toIntArray()
        }
    }
}