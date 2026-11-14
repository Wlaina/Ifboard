# Ifboard

基于 [Fcitx5 for Android（小企鹅输入法）](https://github.com/fcitx5-android/fcitx5-android) 0.1.3 的定制输入法生态，提供 **Android 手机端** 与 **[Windows 小狼毫桌面端](windows/README.md)**：一个安装包即可启用 Rime 引擎 + [雾凇拼音](https://github.com/iDvel/rime-ice)（全拼 / 小鹤双拼）+ 万象中文语法模型，支持 Gboard 风格外观/滑行输入、Warm Paper 暖纸液态玻璃界面及跨端 WebDAV 词库双向同步。

## 安装

- **Android**：从 [Releases](https://github.com/Wlaina/Ifboard/releases) 下载最新的 `Ifboard-<版本>-arm64-v8a.apk` 并安装，在系统设置里启用 Ifboard。
  1. 首次打开应用时，会询问是否下载**万象语法模型**（约 400 MB，用于提升整句输入的准确度）：
     - 选「下载」后在后台自动完成，不需要任何配置；
     - 模型保存在应用内，**以后升级 Ifboard 不用重新下载**；
     - 不下载也能正常打字，之后可以在「同步与更新」里随时下载、检查更新或删除。
  2. **vivo / 小米**：系统默认禁止新装应用「后台弹出界面」，这会导致键盘上的设置、剪贴板编辑、语音授权等页面打不开。应用会提示你，并帮你跳到对应的权限页开启。
- **Windows**：从 [Releases](https://github.com/Wlaina/Ifboard/releases) 下载 `Ifboard-Windows-Setup-<版本>.exe` 一键安装。内置小狼毫（Weasel `0.17.4`）、雾凇全拼/小鹤双拼、`wanxiang-lts-zh-hans.gram` 语法模型与 Warm Paper 桌面控制中心（详细说明见 [windows/README.md](windows/README.md)）。

公开版双端均默认使用雾凇拼音·全拼，小鹤双拼、模糊拼音及个人词频可通过控制中心或 WebDAV 同步自由切换与迁移。

## 功能

**输入**
- **Rime 内置**：雾凇拼音的词库、Lua、OpenCC 和方案全部打包，装好即用。
- **语法模型按需下载**：万象 LTS 语法模型从[官方发布页](https://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS)下载，GitHub 连不上时自动改用作者在 [CNB](https://cnb.cool/amzxyz/rime-wanxiang) 上的国内镜像（与万象官方更新工具相同），支持断点续传和 sha256 校验，可以一键检查更新。因为模型不打进 APK，安装包只有约 68 MB。
- **滑行输入**：全拼、小鹤双拼和英文都支持。手指划过字母即可输入，带渐隐的锥形轨迹，滑行期间工具栏实时显示拼音。上屏后其他可能的词留在候选栏，点一下即可改选（中文插在 Rime 首选之后，英文在工具栏直接替换刚滑出的词）。英文按真实词频排序。解码器参考 SHARK2 论文的思路自己实现，没有使用其他输入法的代码。
- **邻键纠错**：
  - 小鹤双拼与全拼都按「触点概率空间模型 + 词频」做贝叶斯解码（思路参考 Goodman 2002、Weir et al. CHI 2014、VelociTap CHI 2015），一个词里多处误触也能一起纠正（如小鹤 `gihe`、全拼 `zhonfgui` → 谷歌 / 中国）；
  - 原串不合法时纠错直接置顶，原串合法时只在触点落在键边缘且纠错词明显更可能时才插到第 2 位；
  - 会在本机学习你在每个键上的习惯落点偏移，越用越准（不上传、不同步），可在「虚拟键盘 → 触摸习惯」查看或重置；
  - 同时开启 librime 自带的整句邻键纠错（`translator/enable_correction`），覆盖长句输入。
- **英文**：调用系统拼写检查自动纠错（可关），可选自动大写、双击空格输入句号。

**外观与手感**
- Google Sans 字体、Material Symbols 图标，按 Gboard 的尺寸和配色规则绘制，跟随系统动态取色；胶囊形回车键、首排数字角标（大写锁定下与角标错开不重叠）。
- 键盘高度按 dp 固定，与 Gboard 一致（竖屏 240dp / 横屏 196dp）。
- 悬浮键盘：工具栏网格面板及「虚拟键盘」设置支持一键切换为可自由拖动的圆角悬浮窗口（横竖屏分别记忆位置），点击右下角「贴回底部」即可恢复停靠。
- 动画：长按弹窗弹出、焦点放大和触感反馈，面板切换时淡入并上浮，翻译面板高度平滑展开和收起。
- 液态玻璃：**仅在系统底层支持跨窗口实时模糊（Android 12+ 且 ROM 开启 Cross-Window Blur，如小米 HyperOS）的机型上，停靠与悬浮键盘背景才会呈现半透明高斯模糊液态玻璃效果**；在未开放跨窗口模糊的系统上（如部分 OriginOS 机型），键盘背景会自动回退为纯净不透明底板（避免底层锐利文字透出影响辨识度），长按弹出栏仍保留磨砂效果。可在「主题」设置中关闭。

**工具栏与面板**
- Gboard 式工具栏：网格、翻译、撤销（长按向右滑选「重做」）、剪贴板、表情、设置、麦克风。
- 两页符号键盘；按住 `?123` 滑动可以直接选符号，有跟手连线。
- 数字键盘带可以滚动的运算符列；表情面板采用 8 列大尺寸网格、带左上角退出键并支持上下连续滚动。
- 长按候选可以选数字、小写、大写、带声调的拼音。

**内嵌工具**
- 键盘内翻译：译文实时写进输入框，长按「原语言」或「翻译语言」支持上下滑动快速切换语言。
- 键盘内语音输入：语音条背景透明，有随音量变化的光圈；系统没有可用的识别服务时会自动找替代方案，并给出超时提示。
- 剪贴板：文本与截图双列排布，最近的截图和复制的图片可以直接发送；分「最近 / 已固定」两区，最近区只保留一天内的内容，合计最多 20 条，一键清空未置顶条目时同步清理剪贴板截图（不删系统相册）。

**同步与更新**
- WebDAV 同步 Rime 用户词库，默认预置 **Koofr** 并支持 **Koofr / 坚果云** 一键切换，目录结构和电脑端小狼毫（`Ifboard for Windows`）的 `sync/<设备号>` 一致，可以手机/电脑全端自动合并（并自动跳过 `_pc_profile` 等辅助目录）。
- 从 GitHub Release 检查 Ifboard Android / Windows 新版本。

**设置**：「暖纸 · 液态玻璃」风格圆角卡片界面，支持浅色/深色主题自适应，每个设置项均有中文说明。

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
- 英文词频 `ifboard/data/ifb_en_freq.tsv` 取自 [AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/) 的 `en_US_wordlist`（Apache-2.0，已去除冒犯性词条，见 `ifboard/data/NOTICE.LatinIME`）
- [Google Sans](https://github.com/googlefonts/googlesans)（SIL OFL 1.1）、[Material Symbols](https://github.com/google/material-design-icons)（Apache-2.0）
- 本项目与 Google、Gboard 无关；"Gboard" 仅用于描述参照的外观。
