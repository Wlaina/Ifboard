/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.WarmPaperUi
import org.fcitx.fcitx5.android.ui.common.showWarm

class MySwitchPreference(context: Context) : SwitchPreference(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        WarmPaperUi.styleSwitches(holder.findViewById(android.R.id.widget_frame))
        holder.itemView.setOnLongClickListener {
            AlertDialog.Builder(context)
                .setTitle(title ?: "Preference")
                .setMessage(R.string.whether_reset_switch_preference)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.reset) { _, _ -> restore() }
                .create()
                .showWarm()
            true
        }
    }
}