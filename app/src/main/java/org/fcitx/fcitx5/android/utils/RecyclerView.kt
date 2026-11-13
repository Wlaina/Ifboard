/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import splitties.views.bottomPadding
import kotlin.math.max

fun RecyclerView.applyNavBarInsetsBottomPadding() {
    clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val mandatory = windowInsets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        bottomPadding = max(navBars.bottom, mandatory.bottom)
        windowInsets
    }
}
