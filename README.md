# Ifboard

基于 [Fcitx5 for Android（小企鹅输入法）](https://github.com/fcitx5-android/fcitx5-android) 0.1.3 的个人定制版输入法：雾凇拼音（小鹤双拼）+ 万象语法模型，外观和手感参照 Gboard，设置界面使用「暖纸 · 液态玻璃」风格。

A personal build of Fcitx5 for Android with Rime (rime-ice, double pinyin) built in, a Gboard-like look and feel, liquid glass effects, inline translation / voice typing and WebDAV dictionary sync.

## 主要改动

- **单个 APK**：Rime 引擎（原插件）内置，雾凇拼音词库、lua、opencc、万象 LTS 语法模型全部打包，首次启动自动导入配置，装好即用。
- **外观**：Google Sans 字体、Material Symbols 图标、Gboard 尺寸与配色规则（跟随系统动态取色）、胶囊形回车键、首排数字角标。
- **交互**：Gboard 式工具栏（网格 / 翻译 / 撤销 / 剪贴板 / 表情 / 设置 / 麦克风）、长按候选（数字·小写·大写·拼音声调）、键盘变暗与毛玻璃、两页符号键盘与 `?123` 滑动输入、带可滚动运算符列的数字键盘、网格功能面板。
- **内嵌工具**：键盘内翻译栏（实时把译文写进输入框）、键盘内语音输入（工具栏变成「正在听取…」，按键照常可用）。
- **剪贴板**：最近截图与复制的图片可直接发送，「最近 / 已固定」分区，只保留一天内合计 20 条。
- **英文**：调用系统拼写检查自动纠错（可关），可选自动大写、双击空格出句号。
- **液态玻璃**：键盘背景半透明模糊（跨窗口模糊），长按弹出栏毛玻璃，可在「主题」里关闭。
- **设置**：暖纸配色、圆角卡片，每一项都有中文说明。
- **同步与更新**：WebDAV 同步 Rime 用户词库（与电脑端小狼毫的 `sync/<设备号>` 结构一致），GitHub Release 检查更新。

## 编译

需要 Android SDK、NDK `28.0.13004108`、CMake `3.31.6`、[extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)、GNU gettext（`msgfmt`）。

```bash
git clone --recurse-submodules https://github.com/Wlaina/Ifboard.git
cd Ifboard
# 签名信息放在仓库之外：SIGN_KEY_FILE / SIGN_KEY_ALIAS / SIGN_KEY_PWD
SIGN_ENV=/path/to/sign.env RIME_ICE_DIR=/path/to/rime-ice INCLUDE_USERDB=0 bash ifboard/build.sh
```

`ifboard/stage-data.sh` 会把 Rime 引擎、雾凇拼音数据和个人配置放进 APK；`INCLUDE_USERDB=0` 表示不打包个人词频（公开发布用）。

## 许可与致谢

- 源代码沿用上游的 [LGPL-2.1-or-later](LICENSE)。
- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、[fcitx5-rime](https://github.com/fcitx/fcitx5-rime)、[librime](https://github.com/rime/librime)
- [雾凇拼音 rime-ice](https://github.com/iDvel/rime-ice)（GPL-3.0）、[万象语法模型 RIME-LMDG](https://github.com/amzxyz/RIME-LMDG)
- [Google Sans](https://github.com/googlefonts/googlesans)（SIL OFL 1.1）、[Material Symbols](https://github.com/google/material-design-icons)（Apache-2.0）
- 本项目与 Google、Gboard 无关；"Gboard" 仅用于描述参照的外观。
