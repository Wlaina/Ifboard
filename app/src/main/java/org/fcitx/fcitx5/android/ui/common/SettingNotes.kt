/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 为所有设置项提供简明中文解释，贴于标题下方。
 */
package org.fcitx.fcitx5.android.ui.common

import androidx.preference.Preference

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
        "glide_typing" to "划过字母连词输入（支持全拼、小鹤双拼与英文）",
        "glide_typing_trail" to "滑行时显示渐隐轨迹线",
        "gb_auto_correct" to "仅英文：按空格时用系统拼写检查自动改正拼错的单词，紧接着按退格可以撤销",
        "gb_auto_capitalize" to "仅英文：在句首自动切换为大写",
        "gb_double_space_period" to "仅英文：连按两下空格自动输入句号和空格",
        "show_lang_switch_key" to "在空格左边显示地球键，用来切换中文 / 英文",
        "lang_switch_key_behavior" to "按地球键时的动作：在已启用的输入法之间轮换，或其他方式",
        // ---- 虚拟键盘 · 尺寸
        "keyboard_height_percent" to "竖屏键盘高度。27% 就是 Gboard 的默认高度；在默认的「固定高度」计算方式下，换任何手机都和 Gboard 一样高",
        "keyboard_height_percent_landscape" to "横屏时键盘占屏幕高度的百分比",
        "keyboard_side_padding" to "竖屏时键盘左右两侧留出的空白",
        "keyboard_side_padding_landscape" to "横屏时键盘左右两侧留出的空白",
        "keyboard_bottom_padding" to "竖屏时键盘底部与屏幕下边缘的距离",
        "keyboard_bottom_padding_landscape" to "横屏时键盘底部与屏幕下边缘的距离",
        "keyboard_height_percent_base" to "键盘高度怎么算：默认「固定高度」与 Gboard 一致，不随屏幕长短变化；也可以改回按屏幕高度的百分比计算",
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
        "clipboard_enable" to "记录你复制过的文字，并结合最近截图显示在键盘剪贴板里",
        "clipboard_limit" to "最多保留多少条。超过一天的记录会自动清理（已固定的除外）",
        "clipboard_suggestion" to "刚复制了内容时，在工具栏上显示一个可以直接粘贴的提示",
        "clipboard_item_timeout" to "上面那个粘贴提示显示多久后自动消失",
        "clipboard_return_after_paste" to "从剪贴板粘贴后自动回到键盘",
        "clipboard_mask_sensitive" to "被标记为敏感的内容（如密码、验证码）在列表里用圆点遮住",
        // ---- 表情
        "hide_unsupported_emojis" to "隐藏这台手机字体显示不出来的表情",
        "default_emoji_skin_tone" to "带肤色的表情默认使用哪种肤色",
        // ---- 高级 / 开发者
        "ignore_system_cursor" to "不读取应用报告的光标位置。只在个别应用光标错乱时打开",
        "hide_key_config" to "在设置里隐藏快捷键相关的配置项",
        "disable_animation" to "关闭键盘与设置里的动态过渡效果，最省电、最快",
        "vivo_keypress_workaround" to "部分 vivo 手机按键无反应时打开",
        "ignore_system_window_insets" to "忽略系统导航栏占用的空间。键盘位置异常时再尝试",
        "verbose_log" to "记录更详细的调试信息，排查卡顿或同步异常时再打开",
        "editor_info_inspector" to "在输入时显示当前输入框向输入法传来的类型与标志（开发排障用）",
        // ---- 主题
        "key_border" to "按键显示成独立的圆角块（Gboard 样式）。关闭后按键没有底色",
        "key_border_stroke" to "按键边缘加一圈细描边",
        "key_ripple_effect" to "按下按键时出现水波纹",
        "key_horizontal_margin" to "按键之间的左右间距（竖屏）",
        "key_horizontal_margin_landscape" to "横屏时按键之间的左右间距",
        "key_vertical_margin" to "按键之间的上下间距（竖屏）",
        "key_vertical_margin_landscape" to "横屏时按键之间的上下间距",
        "key_radius" to "按键圆角大小。默认 7dp 与宽屏 Gboard 一致（360dp 等窄屏自动收窄至 6dp）",
        "text_editing_button_radius" to "「文本编辑」面板里按钮的圆角",
        "clipboard_entry_radius" to "剪贴板条目的圆角",
        "punctuation_position" to "字母键上的小符号显示在哪里。「右上」与 Gboard 一致（只显示数字）",
        "navbar_background" to "系统导航栏区域是否跟随键盘颜色",
        "follow_system_dark_mode" to "手机切换深色 / 浅色模式时，键盘主题自动跟着换",
        "light_mode_theme" to "浅色模式下使用的键盘主题",
        "dark_mode_theme" to "深色模式下使用的键盘主题",
        // ---- Ifboard 同步与更新
        "ifb_webdav_provider" to "一键填入对应网盘的 WebDAV 服务器地址，支持 Koofr 与坚果云",
        "ifb_webdav_url" to "WebDAV 服务器地址，默认使用 Koofr（https://app.koofr.net/dav/Koofr）",
        "ifb_webdav_user" to "WebDAV 账号（Koofr / 坚果云均为登录邮箱）",
        "ifb_webdav_password" to "WebDAV 专用应用密码（在网盘安全设置里生成，不是网盘登录密码）",
        "ifb_webdav_dir" to "词库在云盘里存放的文件夹名，手机与电脑保持一致即可互通",
        "ifb_webdav_sync_now" to "把本机的个人词频上传，并合并其他设备（如电脑小狼毫）同步来的词库",
        "ifb_gram_status" to "万象语法模型文件约 400 MB，用于提升长句与双拼整句识别准确率",
        "ifb_check_update" to "到 GitHub 检查 Ifboard 是否有新版本",
        "ifb_auto_check_update" to "每次打开设置主页时自动在后台检查一次新版本",
        // ---- Fcitx 常见全局选项（若插件未自带说明则补充）
        "Hotkey" to "外接实体键盘时，用于中英文或状态切换的快捷键组合",
        "Behavior" to "候选翻页、默认输入状态、空格或回车行为等基础习惯",
        "Output" to "标点转换、数字后半角、全角半角切换等输出规则",
        "ActiveByDefault" to "切换到可输入框时默认启用中文输入状态（而不是英文直通）",
        "ResetStateWhenFocusIn" to "重新聚焦到其他输入框时如何重置中英文和临时状态",
        "ShareInputState" to "多个应用或标签页之间是否共享当前中英文状态",
        "PreeditInApplication" to "是否在输入框光标处直接嵌入显示拼音编码",
        "PreeditCursorPositionAtBeginning" to "嵌入式拼音时，把输入框光标放在拼音最前面",
        "ShowInputMethodInformation" to "切换输入法或方案时，在屏幕短暂提示当前方案名称",
        "CompactInputState" to "尽量合并并精简多输入框共用的内部输入状态",
        "DefaultPageSize" to "每一页一次展示多少个候选词（一般设为 5–7 个）",
        "ShowFirstInputMethodInHistory" to "按切换快捷键时优先回到上一次使用的输入法",
        "AllowOverridingSystemXKBSettings" to "由 Fcitx 接管外接实体键盘的键位映射规则"
    )

    val byTitle: Map<String, String> = mapOf(
        // ---- 设置首页（精简为清晰单行）
        "同步与更新" to "WebDAV 跨设备词库与万象语法模型",
        "主题" to "配色方案、圆角间距与液态玻璃效果",
        "输入法" to "中文 Rime / 小鹤双拼与英文输入管理",
        "附加组件" to "Rime 引擎、剪贴板、云拼音与标点模块",
        "全局选项" to "快捷键、每页候选数与默认输入状态",
        "虚拟键盘" to "振动音效、滑行输入、长按与高度调节",
        "候选窗口" to "候选词排列与实体键盘悬浮窗样式",
        "剪贴板" to "复制历史记录、截图并排与隐私遮挡",
        "表情和符号" to "Emoji 兼容性过滤与默认肤色",
        "高级" to "数据导入导出、关闭动画与兼容开关",
        "插件" to "已安装的扩展词库与功能插件",
        "关于" to "版本信息、隐私说明与开源许可",
        "开发者" to "实时日志、引擎重启与缓存重置",
        // ---- 高级页面
        "浏览用户数据目录" to "用系统文件管理器打开 Rime 词典与配置目录",
        "导出用户数据" to "将词库、主题和配置打包导出为压缩包",
        "导入用户数据" to "从备份压缩包恢复设置与个人词库",
        // ---- 开发者页面
        "实时日志" to "查看运行日志，排查分词、滑行与同步问题",
        "详细记录日志" to "输出调试级详细日志（排查完毕建议关闭）",
        "编辑器信息 (EditorInfo) 检查器" to "显示当前输入框传递的输入类型与标志",
        "重启输入引擎" to "重新加载底层引擎（保留个人配置与词库）",
        "删除并同步运行数据" to "重置运行缓存并从安装包同步内置词典（危险操作）",
        "清空剪贴板数据库" to "永久删除已保存的全部剪贴板记录",
        "捕获堆内存转储" to "生成进程内存快照供性能分析",
        // ---- 关于页面
        "常见问题" to "权限开启、双拼滑行与 WebDAV 同步指引",
        "隐私政策" to "离线存储机制与按需网络权限说明",
        "源代码" to "访问 github.com/Wlaina/Ifboard",
        // ---- 插件页面
        "检测到插件更改，点此重新加载" to "检测到插件变更，点此刷新输入引擎",
        // ---- Ifboard
        "GitHub 仓库" to "查看源码、更新日志与历史安装包发布",
        // ---- Fcitx 常见中文项名兜底
        "触发输入法" to "外接键盘开/关中文输入的快捷键",
        "切换激活状态与非激活状态" to "快速在中英文状态间切换的快捷键",
        "上一页" to "候选窗向前翻页快捷键",
        "下一页" to "候选窗向后翻页快捷键",
        "候选词页大小" to "每一页显示的候选词数量",
        "默认启用" to "切换到新输入框时默认启用中文状态",
        "在应用中显示预编辑" to "将正在输入的编码嵌入应用光标处显示",
        "切换输入法时显示提示" to "更换输入方案时屏幕短暂提示方案名",
        "Rime 用户数据目录" to "查看本机 Rime 配置与个人词库文件夹",
        "共享输入状态" to "跨输入框保持同一中英文状态",
        "快速短语" to "管理快捷短语缩写展开",
        "自定义短语" to "添加或修改置顶候选短语",
        "标点符号" to "自定义中文模式下全/半角标点",
        "拼音词典" to "导入扩展词库与细胞词库",
        "按住切换键的修饰键时进行轮换切换" to "按住修饰键点切换键依次轮换输入法",
        "轮换输入法时跳过第一个输入法" to "轮换切换时跳过列表首个默认输入法",
        "重新聚焦时重置状态" to "重新聚焦输入框时恢复初始输入状态",
        "在程序中显示预编辑文本" to "在输入框光标位置直接嵌入未上屏编码",
        "在焦点更改时显示输入法信息" to "切换输入框时短暂提示当前激活方案",
        "显示紧凑的输入法信息" to "仅提示简短方案名或状态缩写",
        "显示第一个输入法的信息" to "单个启用中方案也显示切换状态提示",
        "允许在密码框中使用输入法" to "在密码框保留当前输入框架（一般建议关闭）",
        "输入密码时显示预编辑文本" to "在密码框显示未上屏编码（建议关闭）"
    )

    fun lookup(p: Preference): String? {
        p.key?.let { k -> notes[k]?.let { return it } }
        val title = p.title?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byTitle[title]
    }
}
