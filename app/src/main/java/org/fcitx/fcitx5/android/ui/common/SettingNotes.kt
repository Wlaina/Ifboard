/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: a short, plain-language note for every setting (shown under its title).
 */
package org.fcitx.fcitx5.android.ui.common

object SettingNotes {
    val notes: Map<String, String> = mapOf(
        // ---- 虚拟键盘 · 反馈
        "haptic_on_keypress" to "按下按键时手机轻微振动。「跟随系统」会使用系统设置里的触感强度",
        "haptic_on_keyup" to "手指抬起时再振动一次，手感更像实体键盘",
        "haptic_on_repeat" to "长按退格连续删除、划动空格移动光标时，每一步都振动",
        "button_vibration_press_milliseconds" to "单次振动持续多久。数值越大越明显，「系统默认」最省心",
        "button_vibration_long_press_milliseconds" to "长按按键时的振动持续时间",
        "button_vibration_press_amplitude" to "振动力度（需要手机支持调节力度）",
        "button_vibration_long_press_amplitude" to "长按时的振动力度",
        "sound_on_keypress" to "按键时播放按键音",
        "button_sound_volume" to "按键音的音量",
        // ---- 虚拟键盘 · 行为
        "reset_keyboard_on_focus_change" to "切换到另一个输入框时，自动回到字母键盘（而不是停在符号或表情页）",
        "expand_toolbar_by_default" to "没在打字时，键盘上方一直显示工具栏（翻译、剪贴板、表情等），和 Gboard 一样",
        "inline_suggestions" to "让密码管理器（如 Bitwarden）把账号建议直接嵌在键盘顶部",
        "toolbar_num_row_on_password" to "输入密码时在工具栏位置显示一排数字，方便输入数字密码",
        "popup_on_key_press" to "按下按键时在上方弹出放大的字符气泡",
        "keep_keyboard_letters_uppercase" to "键帽上始终显示大写字母，实际输入仍按大小写状态",
        "show_voice_input_button" to "在工具栏最右侧显示麦克风，点一下就在键盘里语音输入",
        "preferred_voice_input" to "没有系统语音识别服务时，改用哪个语音输入法",
        "expand_keypress_area" to "把每行两端的空白也算进最边上的按键，边缘更好按",
        "swipe_symbol_behavior" to "在字母键上向这个方向划一下，输入键帽角上的符号或数字",
        "keyboard_long_press_delay" to "按住多久算长按（弹出更多字符）。越小越快，太小容易误触",
        "space_long_press_behavior" to "长按空格键要做什么，例如弹出输入法选择",
        "space_swipe_move_cursor" to "在空格键上左右划动来移动光标",
        "gb_auto_correct" to "仅英文：按空格时用系统拼写检查自动改正拼错的单词，紧接着按退格可以撤销",
        "gb_auto_capitalize" to "仅英文：在句首自动切换为大写",
        "gb_double_space_period" to "仅英文：连按两下空格自动输入句号和空格",
        "show_lang_switch_key" to "在空格左边显示地球键，用来切换中文 / 英文",
        "lang_switch_key_behavior" to "按地球键时的动作：在已启用的输入法之间轮换，或其他方式",
        // ---- 虚拟键盘 · 尺寸
        "keyboard_height_percent" to "竖屏时键盘占屏幕高度的百分比。27% 与 Gboard 默认高度一致",
        "keyboard_height_percent_landscape" to "横屏时键盘占屏幕高度的百分比",
        "keyboard_side_padding" to "竖屏时键盘左右两侧留出的空白",
        "keyboard_side_padding_landscape" to "横屏时键盘左右两侧留出的空白",
        "keyboard_bottom_padding" to "竖屏时键盘底部与屏幕下边缘的距离",
        "keyboard_bottom_padding_landscape" to "横屏时键盘底部与屏幕下边缘的距离",
        "keyboard_height_percent_base" to "键盘高度百分比是按「整块屏幕」还是「可用区域」计算",
        // ---- 候选词
        "horizontal_candidate_style" to "候选栏里词条的排列方式：按词长自适应，或固定平分宽度",
        "expanded_candidate_style" to "点开候选栏右侧箭头后，更多候选的排列方式",
        "expanded_candidate_grid_span_count_portrait" to "展开候选时每行放几个（竖屏）",
        "expanded_candidate_grid_span_count_landscape" to "展开候选时每行放几个（横屏）",
        "show_candidates_window" to "使用实体键盘时，是否显示悬浮的候选窗口",
        "candidates_window_orientation" to "悬浮候选窗口横排还是竖排",
        "candidates_window_min_width" to "悬浮候选窗口的最小宽度",
        "candidates_window_padding" to "悬浮候选窗口的内边距",
        "candidates_window_font_size" to "悬浮候选窗口的字号",
        "candidates_window_radius" to "悬浮候选窗口的圆角",
        "candidates_item_padding_vertical" to "悬浮候选窗口中每个候选的上下间距",
        "candidates_item_padding_horizontal" to "悬浮候选窗口中每个候选的左右间距",
        // ---- 剪贴板
        "clipboard_enable" to "记录你复制过的文字，方便从键盘的剪贴板里再次粘贴",
        "clipboard_limit" to "最多保留多少条。超过一天的记录会自动清理（已固定的除外）",
        "clipboard_suggestion" to "刚复制了内容时，在工具栏上显示一个可以直接粘贴的提示",
        "clipboard_item_timeout" to "上面那个粘贴提示显示多久后自动消失",
        "clipboard_return_after_paste" to "从剪贴板粘贴后自动回到键盘",
        "clipboard_mask_sensitive" to "被标记为敏感的内容（如密码、验证码）在列表里用圆点遮住",
        // ---- 表情
        "hide_unsupported_emojis" to "隐藏这台手机字体显示不出来的表情",
        "default_emoji_skin_tone" to "带肤色的表情默认使用哪种肤色",
        // ---- 高级
        "ignore_system_cursor" to "不读取应用报告的光标位置。只在个别应用光标错乱时打开",
        "hide_key_config" to "在设置里隐藏快捷键相关的配置项",
        "disable_animation" to "关闭键盘里的所有过渡动画，最省电、最快",
        "vivo_keypress_workaround" to "部分 vivo 手机按键无反应时打开",
        "ignore_system_window_insets" to "忽略系统导航栏占用的空间。键盘位置异常时再尝试",
        // ---- 主题
        "key_border" to "按键显示成独立的圆角块（Gboard 样式）。关闭后按键没有底色",
        "key_border_stroke" to "按键边缘加一圈细描边",
        "key_ripple_effect" to "按下按键时出现水波纹",
        "ifb_glass_effect" to "键盘背景变成半透明毛玻璃，长按弹出栏也带模糊效果。关闭后更省电",
        "key_horizontal_margin" to "按键之间的左右间距（竖屏 / 横屏）",
        "key_horizontal_margin_landscape" to "横屏时按键之间的左右间距",
        "key_vertical_margin" to "按键之间的上下间距（竖屏 / 横屏）",
        "key_vertical_margin_landscape" to "横屏时按键之间的上下间距",
        "key_radius" to "按键圆角大小。6dp 与 Gboard 一致",
        "text_editing_button_radius" to "「文本编辑」面板里按钮的圆角",
        "clipboard_entry_radius" to "剪贴板条目的圆角",
        "punctuation_position" to "字母键上的小符号显示在哪里。「右上」与 Gboard 一致（只显示数字）",
        "navbar_background" to "系统导航栏区域是否跟随键盘颜色",
        "follow_system_dark_mode" to "手机切换深色 / 浅色模式时，键盘主题自动跟着换",
        "light_mode_theme" to "浅色模式下使用的键盘主题",
        "dark_mode_theme" to "深色模式下使用的键盘主题",
        // ---- Ifboard
        "ifb_webdav_url" to "WebDAV 服务器地址，例如坚果云：https://dav.jianguoyun.com/dav/",
        "ifb_webdav_user" to "WebDAV 账号（坚果云是登录邮箱）",
        "ifb_webdav_password" to "WebDAV 密码（坚果云需要在「第三方应用管理」里生成应用密码）",
        "ifb_webdav_dir" to "词库在服务器上存放的文件夹名",
        "ifb_webdav_sync_now" to "把本机的个人词频上传，并合并其他设备的词库",
        "ifb_check_update" to "到 GitHub 查看有没有新版本",
        "ifb_auto_check_update" to "每次打开设置时自动检查一次新版本"
    )
}
