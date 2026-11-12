/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 内置隐私政策与数据说明页面
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

class PrivacyFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("本地与离线优先") {
                addPreference(
                    title = "日常输入与词库完全离线运行",
                    summary = "Ifboard 的 Rime 拼音引擎、小鹤双拼、万象语法模型、滑行输入解码与个人用户词库全部保存在本机应用私有目录，不会向任何第三方服务器上传您的按键内容或打字记录。"
                )
                addPreference(
                    title = "剪贴板与密码框保护",
                    summary = "剪贴板记录仅存放在本地数据库；系统密码输入框（Password / OTP 等）中会依据配置自动隐藏敏感内容且不记录普通输入上下文。"
                )
            }
            addCategory("仅按需发起的网络请求") {
                addPreference(
                    title = "WebDAV 词库同步",
                    summary = "仅在您主动配置 Koofr / 坚果云或其它 WebDAV 账号并点击同步时，加密（HTTPS）连接您自己指定的网盘同步个人 Rime 词频与配置。"
                )
                addPreference(
                    title = "键盘内置翻译与语音输入",
                    summary = "打开翻译栏输入需翻译文本时才会把待译短句发送给翻译接口；语音输入直接调用系统已授权的语音识别服务（Android SpeechRecognizer），本应用不额外留存录音。"
                )
                addPreference(
                    title = "版本检查与语法模型下载",
                    summary = "仅用于向 GitHub 检查 Ifboard 新版本以及下载 / 校验开源的万象语法模型（SHA-256 校验），不包含任何设备跟踪标识符。"
                )
            }
            addCategory("开源透明与许可") {
                addPreference(
                    title = "公开源代码可审计",
                    summary = "Ifboard 基于 GNU LGPL-2.1 开源的 fcitx5-android 与 GPL 雾凇拼音词库构建，全部定制源码公开托管于 GitHub（Wlaina/Ifboard）。"
                )
            }
        }
    }
}
