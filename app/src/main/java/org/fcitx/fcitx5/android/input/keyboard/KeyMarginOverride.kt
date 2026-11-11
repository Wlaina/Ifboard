/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Temporarily overrides key margins while a keyboard builds its key views (e.g. tighter numpad like Gboard) */
object KeyMarginOverride {
    var dp: Float? = null
}
