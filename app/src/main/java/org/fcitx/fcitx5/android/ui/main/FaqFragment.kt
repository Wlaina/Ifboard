/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 内置常见问题页面（脱离上游外部网页，离线可用）
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import org.fcitx.fcitx5.android.ifboard.BackgroundStart
import org.fcitx.fcitx5.android.ifboard.IfboardActivity
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

class FaqFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("权限与系统兼容") {
                addPreference(
                    title = "为什么点键盘上的设置、语音授权或编辑剪贴板没反应？",
                    summary = "小米（澎湃 OS）与 vivo（OriginOS）默认拦截输入法从后台拉起页面。点击此处前往系统权限页开启「后台弹出界面」即可恢复正常。"
                ) {
                    BackgroundStart.openPermissionPage(requireActivity())
                }
                addPreference(
                    title = "部分手机按键响应慢或偶尔无反应怎么办？",
                    summary = "在「高级」设置中打开「解决 vivo 按键检测问题」或关闭过渡动画；部分机型建议将 Ifboard 加入系统省电白名单。"
                )
            }
            addCategory("小鹤双拼、纠错与滑行输入") {
                addPreference(
                    title = "滑行输入（Glide）怎么用更准？",
                    summary = "英文直接连续划过单词字母（连续滑词会自动补空格）；中文小鹤双拼依次划过字词对应的双拼码（如「你好」划 n→i→h→c），候选项会随轨迹实时匹配。"
                )
                addPreference(
                    title = "打字按到相邻键怎么办？",
                    summary = "小鹤双拼已内置邻键容错纠错（例如误将 guge 打成 gige，候选栏会自动补出「谷歌」等高概率词），英文模式支持空格后自动纠错。"
                )
            }
            addCategory("工具栏与快捷手势") {
                addPreference(
                    title = "工具栏还有哪些隐藏长按操作？",
                    summary = "长按「撤回」可上下切换撤回 / 重做；长按翻译栏中的「原语言 / 翻译语言」可上下滑动直接选择语种；剪贴板支持左右双栏文本与截图并排清理。"
                )
            }
            addCategory("同步与语法模型") {
                addPreference(
                    title = "如何配置 WebDAV 跨设备同步与万象语法模型？",
                    summary = "前往「同步与更新」一键选择 Koofr 或坚果云并填入应用专用密码，即可与电脑小狼毫互通个人词频；万象语法模型首次可随时在设置中下载或更新，点击此处直达。"
                ) {
                    startActivity(Intent(requireContext(), IfboardActivity::class.java))
                }
            }
        }
    }
}
