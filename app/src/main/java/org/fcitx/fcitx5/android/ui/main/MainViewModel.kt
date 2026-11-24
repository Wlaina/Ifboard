/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.appContext

class MainViewModel : ViewModel() {

    enum class ButtonMode { NONE, EDIT, DELETE }

    val toolbarTitle = MutableLiveData(appContext.getString(R.string.app_name))

    val toolbarShadow = MutableLiveData(true)

    val toolbarButton = MutableLiveData(ButtonMode.NONE)

    /**
     * Ifboard：每个实例用自己的连接名。原先都用类名，同时开着两个设置页时（键盘菜单进一个、桌面图标又进一个），
     * 先关掉的那个会把共用的连接断开，另一个在 onStop 保存配置时抛 "is disconnected" 崩溃。
     */
    private val connectionName = "${javaClass.name}#${System.identityHashCode(this)}"

    val fcitx: FcitxConnection = FcitxDaemon.connect(connectionName)

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    fun enableToolbarShadow() {
        toolbarShadow.value = true
    }

    fun disableToolbarShadow() {
        toolbarShadow.value = false
    }

    override fun onCleared() {
        FcitxDaemon.disconnect(connectionName)
    }
}