param(
    [switch]$AutoStart,
    [switch]$RemoveMsPinyin,
    [string]$AppDir = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = "Continue"

function Test-GrammarFileValid([string]$Path, [long]$ExpectedSize) {
    if (-not (Test-Path $Path)) { return $false }
    $fi = Get-Item $Path -ErrorAction SilentlyContinue
    if (-not $fi -or $fi.Length -ne $ExpectedSize -or $fi.Length -lt 1048576) { return $false }
    try {
        $fs = [System.IO.File]::OpenRead($Path)
        try {
            $buf = New-Object byte[] 14
            $n = $fs.Read($buf, 0, 14)
            return ($n -eq 14 -and [System.Text.Encoding]::ASCII.GetString($buf) -eq "Rime::Grammar/")
        }
        finally {
            $fs.Dispose()
        }
    }
    catch {
        return $false
    }
}

$rimeUserDir = Join-Path $env:APPDATA "Rime"
$stageSrc = Join-Path $AppDir "defaults\rime-data"
$preserveIfExists = @(
    "custom_phrase.txt",
    "custom_phrase_double.txt",
    "default.custom.yaml",
    "double_pinyin_flypy.custom.yaml",
    "rime_ice.custom.yaml",
    "weasel.custom.yaml"
)

Write-Host "[Ifboard Setup] 同步公开版 Rime 词库与主题至 $rimeUserDir ..."
if (-not (Test-Path $rimeUserDir)) {
    New-Item -ItemType Directory -Path $rimeUserDir -Force | Out-Null
}

if (Test-Path $stageSrc) {
    # 停掉可能占用词库或 .gram 句柄的小狼毫进程，确保纯静默部署无冲突
    Get-Process -Name "WeaselServer", "WeaselDeployer", "WeaselSetup" -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 400

    Get-ChildItem -Path $stageSrc -Force | ForEach-Object {
        $dest = Join-Path $rimeUserDir $_.Name
        if ($_.PSIsContainer) {
            New-Item -ItemType Directory -Path $dest -Force | Out-Null
            Copy-Item -Path (Join-Path $_.FullName "*") -Destination $dest -Recurse -Force
        }
        elseif (($preserveIfExists -contains $_.Name) -and (Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)) {
            Write-Host "保留现有 $($_.Name)"
        }
        elseif ($_.Name -eq "wanxiang-lts-zh-hans.gram" -and (Test-GrammarFileValid -Path $dest -ExpectedSize $_.Length)) {
            Write-Host "语法模型已存在且校验一致，跳过重复写入 ($($_.Length) bytes)"
        }
        else {
            Copy-Item -Path $_.FullName -Destination $dest -Force
        }
    }
}

# 全新电脑首次安装时初始化友好的设备标识，避免默认随机 UUID 造成多端云盘目录难辨识
$installYaml = Join-Path $rimeUserDir "installation.yaml"
if (-not (Test-Path $installYaml)) {
    $safeMachine = ($env:COMPUTERNAME -replace '[^a-zA-Z0-9_-]', '')
    if ([string]::IsNullOrWhiteSpace($safeMachine)) { $safeMachine = "Desktop" }
    $starterYaml = @"
distribution_code_name: Weasel
distribution_name: "小狼毫"
distribution_version: 0.17.4
installation_id: "PC-$safeMachine"
"@
    [System.IO.File]::WriteAllText($installYaml, $starterYaml, [System.Text.UTF8Encoding]::new($false))
}

# 定位小狼毫安装目录并执行非交互式静默部署（必须使用 ProcessStartInfo CreateProcess，避免 ShellExecute 触发弹窗挂起）
$weaselDirs = @()
foreach ($pf in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
    if ($pf -and (Test-Path (Join-Path $pf "Rime"))) {
        $weaselDirs += Get-ChildItem -Path (Join-Path $pf "Rime") -Directory -Filter "weasel-*" | Sort-Object Name -Descending
    }
}

$deployer = $weaselDirs | ForEach-Object { Join-Path $_.FullName "WeaselDeployer.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
$server = $weaselDirs | ForEach-Object { Join-Path $_.FullName "WeaselServer.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1

if ($deployer) {
    Write-Host "[Ifboard Setup] 正在静默部署小狼毫引擎 (WeaselDeployer /deploy)..."
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $deployer
    $psi.Arguments = "/deploy"
    $psi.WorkingDirectory = (Split-Path $deployer -Parent)
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    if ($proc) {
        $completed = $proc.WaitForExit(90000)
        if (-not $completed) {
            Write-Warning "[Ifboard Setup] WeaselDeployer /deploy 超过 90 秒未退出，强制清理避免阻塞安装。"
            try { $proc.Kill() } catch {}
        }
        else {
            Write-Host "[Ifboard Setup] 小狼毫静默部署完成，ExitCode=$($proc.ExitCode)"
        }
    }
}

if ($server) {
    $srvPsi = New-Object System.Diagnostics.ProcessStartInfo
    $srvPsi.FileName = $server
    $srvPsi.WorkingDirectory = (Split-Path $server -Parent)
    $srvPsi.UseShellExecute = $true
    [void][System.Diagnostics.Process]::Start($srvPsi)
    Start-Sleep -Milliseconds 600
}

# 可选：开机自动在托盘跟随 Windows 主体色
if ($AutoStart) {
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    $exePath = Join-Path $AppDir "IfboardWin.exe"
    if (Test-Path $exePath) {
        New-Item -Path $runKey -Force | Out-Null
        Set-ItemProperty -Path $runKey -Name "IfboardDesktopTray" -Value "`"$exePath`" --tray"
    }
}

# 可选：移除系统自带微软拼音，防止 Ctrl+Space 抢占
if ($RemoveMsPinyin) {
    $msTip = "0804:{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}{FA550B04-5AD7-411F-A5AC-CA038EC515D7}"
    try {
        $langs = Get-WinUserLanguageList
        $zh = $langs | Where-Object { $_.LanguageTag -like "zh*" }
        if ($zh -and ($zh.InputMethodTips -contains $msTip)) {
            [void]$zh.InputMethodTips.Remove($msTip)
            Set-WinUserLanguageList $langs -Force
        }
    }
    catch {
        Write-Warning "移除微软拼音时出现警告（不影响安装）: $_"
    }
}
