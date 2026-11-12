# Ifboard

基于 [Fcitx5 for Android（小企鹅输入法）](https://github.com/fcitx5-android/fcitx5-android) 0.1.3 的个人定制版输入法。一个 APK 里内置了 Rime 引擎、[雾凇拼音](https://github.com/iDvel/rime-ice)（全拼和小鹤双拼）和英文键盘，外观、手感和动画逐项参照 Gboard，还支持滑行输入。

A personal build of Fcitx5 for Android: Rime with rime-ice (full pinyin / double pinyin) built in, glide typing for Chinese and English, a Gboard-like look, feel and animations, optional on-demand grammar model, inline translation / voice typing and WebDAV dictionary sync.

## 安装

1. 从 [Releases](https://github.com/Wlaina/Ifboard/releases) 下载最新的 `arm64-v8a` APK 并安装，在系统设置里启用 Ifboard。
2. 首次打开应用时，会询问是否下载**万象语法模型**（约 400 MB，用于提升整句输入的准确度）：
   - 选「下载」后在后台自动完成，不需要任何配置；
   - 模型保存在应用内，**以后升级 Ifboard 不用重新下载**；
   - 不下载也能正常打字，之后可以在「同步与更新」里随时下载、检查更新或删除。
3. **vivo / 小米**：系统默认禁止新装应用「后台弹出界面」，这会导致键盘上的设置、剪贴板编辑、语音授权等页面打不开。应用会提示你，并帮你跳到对应的权限页开启。

公开版默认使用雾凇拼音·全拼，小鹤双拼可以在方案选单里切换。

## 功能

**输入**
- **Rime 内置**：雾凇拼音的词库、Lua、OpenCC 和方案全部打包，装好即用。
- **语法模型按需下载**：万象 LTS 语法模型从[官方发布页](https://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS)下载，支持断点续传和 sha256 校验，可以一键检查更新。因为模型不打进 APK，安装包只有约 70 MB。
- **滑行输入**：全拼、小鹤双拼和英文都支持。手指划过字母即可输入，带渐隐的锥形轨迹。解码器参考 SHARK2 论文的思路自己实现，没有使用其他输入法的代码。
- **英文**：调用系统拼写检查自动纠错（可关），可选自动大写、双击空格输入句号。

**外观与手感**
- Google Sans 字体、Material Symbols 图标，按 Gboard 的尺寸和配色规则绘制，跟随系统动态取色；胶囊形回车键、首排数字角标。
- 键盘高度按 dp 固定，与 Gboard 一致（竖屏 240dp / 横屏 196dp）。
- 动画：长按弹窗弹出、焦点放大和触感反馈，面板切换时淡入并上浮，翻译面板高度平滑展开和收起。
- 液态玻璃：键盘背景半透明模糊，长按弹出栏用毛玻璃，可以在「主题」里关闭。

**工具栏与面板**
- Gboard 式工具栏：网格、翻译、撤销、剪贴板、表情、设置、麦克风。
- 两页符号键盘；按住 `?123` 滑动可以直接选符号，有跟手连线。
- 数字键盘带可以滚动的运算符列；表情面板可以上下连续滚动。
- 长按候选可以选数字、小写、大写、带声调的拼音。

**内嵌工具**
- 键盘内翻译：译文实时写进输入框。
- 键盘内语音输入：语音条背景透明，有随音量变化的光圈；系统没有可用的识别服务时会自动找替代方案，并给出超时提示。
- 剪贴板：最近的截图和复制的图片可以直接发送；分「最近 / 已固定」两区，最近区只保留一天内的内容，合计最多 20 条。

**同步与更新**
- WebDAV 同步 Rime 用户词库，目录结构和电脑端小狼毫的 `sync/<设备号>` 一致，可以多设备合并。
- 从 GitHub Release 检查 Ifboard 新版本。

**设置**：暖纸配色、圆角卡片，每个设置项都有中文说明。

## 编译

需要 JDK 17、Android SDK（platform 36）、NDK `28.0.13004108`、CMake `3.31.6`、[extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)、GNU gettext（`msgfmt`）。脚本是 bash，Windows 上请在 Git Bash 里运行。

```bash
git clone --recurse-submodules https://github.com/Wlaina/Ifboard.git
cd Ifboard
# 签名信息放在仓库之外，文件内容：SIGN_KEY_FILE / SIGN_KEY_ALIAS / SIGN_KEY_PWD
SIGN_ENV=/path/to/sign.env RIME_ICE_DIR=/path/to/rime-ice INCLUDE_USERDB=0 bash ifboard/build.sh
```

- `ifboard/build.sh` 会先编 Rime 插件，然后调用 `ifboard/stage-data.sh` 把 Rime 引擎和雾凇拼音数据放进 `app/src/main/assets`，最后打出 APK。
- `RIME_ICE_DIR` 指向一份雾凇拼音数据。仓库里自带全拼方案 `ifboard/data/rime_ice.schema.yaml`，数据目录里缺这个文件时会自动补上。
- `INCLUDE_USERDB=0` 打的是公开版：使用雾凇拼音默认设置，不带个人配置和词频。
- 语法模型不打进 APK，由应用在运行时下载（见 `app/src/main/java/org/fcitx/fcitx5/android/ifboard/GrammarModel.kt`）。
- 输出：`app/build/outputs/apk/release/io.github.wlaina.ifboard-<版本>-arm64-v8a-release.apk`

## 许可与致谢

- 源代码沿用上游的 [LGPL-2.1-or-later](LICENSE)。
- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、[fcitx5-rime](https://github.com/fcitx/fcitx5-rime)、[librime](https://github.com/rime/librime)、[librime-octagram](https://github.com/lotem/librime-octagram)
- [雾凇拼音 rime-ice](https://github.com/iDvel/rime-ice)（GPL-3.0，包括 `ifboard/data/rime_ice.schema.yaml`）
- [万象语法模型 rime-wanxiang-data](https://github.com/amzxyz/rime-wanxiang-data)（不随 APK 分发，由用户从官方发布页下载）
- [Google Sans](https://github.com/googlefonts/googlesans)（SIL OFL 1.1）、[Material Symbols](https://github.com/google/material-design-icons)（Apache-2.0）
- 本项目与 Google、Gboard 无关；"Gboard" 仅用于描述参照的外观。
