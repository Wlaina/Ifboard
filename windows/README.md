# Ifboard for Windows（小狼毫增强公开版）

Warm Paper · Liquid Glass 风格的 Windows 小狼毫（Weasel `0.17.4`）一键安装与桌面伴侣控制中心，体验与 [Ifboard 手机端](https://github.com/Wlaina/Ifboard) 对齐。

## 核心特性

- **开箱即用一体化安装包**：单文件 `.exe` 内置小狼毫 `0.17.4`、雾凇全拼（`rime_ice` 默认）+ 小鹤双拼（`double_pinyin_flypy` 随切）、`wanxiang-lts-zh-hans.gram` 万象中文语法模型及全部标准词库。
- **零隐私泄露公开架构**：安装包完全剥离任何个人词频、自定义短语与云端账号；个人词库与偏好均通过 WebDAV 运行时加密导入或双向同步。
- **双端一致的桌面外观与热换肤**：
  - 内置 **Claude 陶土橙（默认推荐）**、**跟随 Windows 系统主体色**、**暖纸琥珀** 三套浅色/深色双模小狼毫候选窗皮肤。
  - 支持后台超低功耗托盘模式（`--tray`），监听 Windows 11 系统取色与浅/深色切换并自动更新小狼毫皮肤。
- **WebDAV 云端同步与每日自动同步**：
  - 兼容手机端 Ifboard `/Ifboard/<DeviceId>/*.userdb.txt` 结构，自动跳过 `_pc_profile` / `_touch_model` 等辅助目录，手机/电脑全端词频打通合并，并支持默认开启的每 24 小时后台自动同步与云端触摸习惯统计展示。
  - 使用 Windows DPAPI 硬件级加密保存云盘密码；支持一键备份与还原电脑整套方案、模糊音、皮肤与短语。
- **万象语法模型与控制中心对齐**：
  - 对接 `amzxyz/rime-wanxiang-data` 官方 `LTS` Release，支持无限超时保护、后台托盘提醒与自动断点续传（GitHub 连不上时自动回退 CNB 国内镜像），并通过 `Rime::Grammar/` 头与 SHA-256 校验后热替换部署。
  - 控制中心与 Android 端能力对齐，支持 `librime` 整句纠错开关（`translator/enable_correction`）与仅匹配 Windows `.exe` 安装包的 Release 检查更新。
  - 新增**任务栏右下角「中 / 英」状态图标**（`HKCU\Software\Microsoft\CTF\LangBar` + COM `ITfLangBarMgr` + `style/display_tray_icon`）与**切换中英文浮动「中 / 英」提示图标**（`show_notifications`）双开关，支持在「输入与方案」、「外观与皮肤」及系统托盘菜单随时切换，并在启动时自动守护状态以抵御 Windows 系统更新重置。

## 目录结构

```text
Ifboard-Win/
├─ IfboardWin/                 # .NET 8 WPF 暖纸液态玻璃控制中心源码
├─ scripts/
│  ├─ public-defaults/         # 公开版纯净默认 custom.yaml 与空短语模板
│  ├─ stage-public-data.ps1    # 公开版词库整理与隐私泄露审计脚本
│  └─ post-install.ps1         # 安装后用户目录展开与首次自动部署脚本
├─ installer/
│  └─ IfboardSetup.iss         # Inno Setup 6 一体化安装打包脚本
└─ installer-assets/           # 打包所需外部二进制（gitignored）
```

## 本地构建安装包

1. 安装 `.NET 8 SDK` 与 `Inno Setup 6`。
2. 确保 `installer-assets/weasel-0.17.4.0-installer.exe` 就位，并运行公开版词库整理与隐私审计：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stage-public-data.ps1
```

3. 发布自包含控制中心：

```powershell
dotnet publish .\IfboardWin\IfboardWin.csproj -c Release -r win-x64 --self-contained true -p:DebugType=none -p:DebugSymbols=false -o .\dist\IfboardWin-win-x64
```

4. 编译一体化安装包：

```powershell
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" .\installer\IfboardSetup.iss
```

输出产物位于 `dist\Ifboard-Windows-Setup-1.3.1.exe`。
