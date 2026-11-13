#define MyAppName "Ifboard for Windows"
#define MyAppVersion "1.2.2"
#define MyAppPublisher "Ifboard"
#define MyAppURL "https://github.com/Wlaina/Ifboard"
#define MyAppExeName "IfboardWin.exe"

[Setup]
AppId={{8F64B3A2-6D14-4C93-B94D-7F35D77A120E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}（小狼毫增强公开版）
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={autopf}\Ifboard
DefaultGroupName=Ifboard
DisableProgramGroupPage=yes
AllowNoIcons=yes
LicenseFile=..\stage-public\rime-data\LICENSE
OutputDir=..\dist
OutputBaseFilename=Ifboard-Windows-Setup-{#MyAppVersion}
SetupIconFile=..\IfboardWin\Assets\ifboard.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no

[Languages]
Name: "default"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=欢迎使用 [name] 安装向导
WelcomeLabel2=本程序将为您的电脑一键安装：小狼毫输入法引擎（Weasel 0.17.4）、雾凇全拼（默认）+ 小鹤双拼、万象中文语法模型（LTS）以及 Warm Paper 暖纸液态玻璃桌面控制中心。%n%n安装包完全公开纯净，不包含任何个人词频与云端密码；安装后可直接在控制中心通过 WebDAV 导入您的全部个人词库与配置。
ButtonNext=下一步 >
ButtonBack=< 上一步
ButtonCancel=取消
ButtonInstall=立即安装
ButtonFinish=完成
FinishedHeadingLabel=[name] 安装完成
FinishedLabel=小狼毫输入法、词库与桌面伴侣已全部配置并部署完成（默认启用「Claude 陶土橙」皮肤，可在控制中心随时切换「跟随 Windows 主体色」或恢复 WebDAV 配置）。

[Tasks]
Name: "desktopicon"; Description: "在桌面创建「Ifboard 控制中心」快捷方式"; GroupDescription: "附加选项:"
Name: "autostart"; Description: "开机在后台驻留低功耗托盘，自动跟随 Windows 主体色切换皮肤"; GroupDescription: "附加选项:"
Name: "removemspin"; Description: "移除 Windows 微软拼音（仅保留小狼毫，避免 Ctrl+Space 抢占）"; GroupDescription: "附加选项:"; Flags: unchecked

[Files]
Source: "..\dist\IfboardWin-win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\scripts\post-install.ps1"; DestDir: "{app}\scripts"; Flags: ignoreversion
Source: "..\stage-public\rime-data\*"; DestDir: "{app}\defaults\rime-data"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\installer-assets\weasel-0.17.4.0-installer.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\Ifboard 控制中心"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 Ifboard"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Ifboard 控制中心"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{tmp}\weasel-0.17.4.0-installer.exe"; Parameters: "/S"; StatusMsg: "正在安装小狼毫输入法引擎 (Weasel 0.17.4)..."; Flags: waituntilterminated
Filename: "powershell.exe"; Parameters: "{code:GetPostInstallParams}"; StatusMsg: "正在部署雾凇词库、万象语法模型与暖纸皮肤..."; Flags: runhidden waituntilterminated runasoriginaluser
Filename: "{app}\{#MyAppExeName}"; Description: "立即打开 Ifboard 控制中心"; Flags: nowait postinstall skipifsilent runasoriginaluser
Filename: "{app}\{#MyAppExeName}"; Parameters: "--tray"; Tasks: autostart; Flags: nowait runasoriginaluser skipifnotsilent

[UninstallRun]
Filename: "reg.exe"; Parameters: "delete HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v IfboardDesktopTray /f"; Flags: runhidden; RunOnceId: "RemoveIfboardTrayRunKey"

[Code]
procedure KillRunningProcesses();
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/F /T /IM IfboardWin.exe /IM WeaselDeployer.exe /IM WeaselServer.exe /IM WeaselSetup.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(600);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  // 在 Inno Setup RestartManager 检测文件占用之前先结束托盘与小狼毫进程，避免静默/覆盖升级弹出占用重试框
  KillRunningProcesses();
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    KillRunningProcesses();
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    KillRunningProcesses();
  end;
end;

function GetPostInstallParams(Param: String): String;
var
  ScriptPath: String;
  Args: String;
begin
  ScriptPath := ExpandConstant('{app}\scripts\post-install.ps1');
  Args := '';
  if WizardIsTaskSelected('autostart') then
    Args := Args + ' -AutoStart';
  if WizardIsTaskSelected('removemspin') then
    Args := Args + ' -RemoveMsPinyin';
  Result := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ScriptPath + '"' + Args;
end;
