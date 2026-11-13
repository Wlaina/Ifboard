param(
    [string]$RimeIceSource = "D:\OneDrive\文档\Ifboard_project\rime-ice-data",
    [string]$RimeIceSchemaSource = "",
    [string]$RepoRoot = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RimeIceSchemaSource)) {
    $parentSchema = Join-Path (Split-Path $RepoRoot -Parent) "ifboard\data\rime_ice.schema.yaml"
    $RimeIceSchemaSource = if (Test-Path $parentSchema) {
        $parentSchema
    } else {
        "D:\Android\src\fcitx5-android\ifboard\data\rime_ice.schema.yaml"
    }
}

$stageDir = Join-Path $RepoRoot "stage-public\rime-data"
$defaultsDir = Join-Path $RepoRoot "scripts\public-defaults"

Write-Host "[Ifboard Stage] 正在清理并构建公开版纯净词库与方案目录..." -ForegroundColor Cyan
if (Test-Path $stageDir) {
    Remove-Item -Path $stageDir -Recurse -Force
}
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

# 1. 复制纯净上游运行时目录
$dirsToCopy = @("cn_dicts", "en_dicts", "lua", "opencc")
foreach ($d in $dirsToCopy) {
    $src = Join-Path $RimeIceSource $d
    if (-not (Test-Path $src)) { throw "缺少上游目录: $src" }
    Copy-Item -Path $src -Destination (Join-Path $stageDir $d) -Recurse -Force
}

# 2. 复制纯净上游单文件
$filesToCopy = @(
    "default.yaml",
    "double_pinyin_flypy.schema.yaml",
    "melt_eng.dict.yaml",
    "melt_eng.schema.yaml",
    "radical_pinyin.dict.yaml",
    "radical_pinyin.schema.yaml",
    "rime_ice.dict.yaml",
    "symbols_v.yaml",
    "symbols_caps_v.yaml",
    "LICENSE"
)
foreach ($f in $filesToCopy) {
    $src = Join-Path $RimeIceSource $f
    if (-not (Test-Path $src)) { throw "缺少上游文件: $src" }
    Copy-Item -Path $src -Destination (Join-Path $stageDir $f) -Force
}

# 3. 补齐雾凇全拼主方案 rime_ice.schema.yaml
if (-not (Test-Path $RimeIceSchemaSource)) { throw "缺少 rime_ice.schema.yaml: $RimeIceSchemaSource" }
Copy-Item -Path $RimeIceSchemaSource -Destination (Join-Path $stageDir "rime_ice.schema.yaml") -Force

# 4. 复制并校验万象语法模型 LTS（确保文件头为 Rime::Grammar/）
$gramSrc = Join-Path $RimeIceSource "wanxiang-lts-zh-hans.gram"
if (-not (Test-Path $gramSrc)) { throw "缺少 wanxiang-lts-zh-hans.gram: $gramSrc" }
$fs = [System.IO.File]::OpenRead($gramSrc)
$hdrBytes = New-Object byte[] 14
[void]$fs.Read($hdrBytes, 0, 14)
$fs.Dispose()
$hdrText = [System.Text.Encoding]::ASCII.GetString($hdrBytes)
if ($hdrText -ne "Rime::Grammar/") {
    throw "语法模型头校验失败（期望 Rime::Grammar/，实际: $hdrText）"
}
Copy-Item -Path $gramSrc -Destination (Join-Path $stageDir "wanxiang-lts-zh-hans.gram") -Force

# 5. 修复 default.yaml 中默认 Tab 规则，使 Tab/Shift+Tab 翻页与手机/电脑一致
$defaultYamlPath = Join-Path $stageDir "default.yaml"
$defaultText = [System.IO.File]::ReadAllText($defaultYamlPath, [System.Text.UTF8Encoding]::new($false))
$tabRule = "- { when: composing, accept: Tab, send: Shift+Right }"
$tabCommented = "# - { when: composing, accept: Tab, send: Shift+Right }  # disabled by Ifboard Tab paging"
if ($defaultText.Contains($tabRule) -and -not $defaultText.Contains($tabCommented)) {
    $defaultText = $defaultText.Replace($tabRule, $tabCommented)
    [System.IO.File]::WriteAllText($defaultYamlPath, $defaultText, [System.Text.UTF8Encoding]::new($false))
}

# 6. 写入公开版纯净默认 custom / 空短语模板与托盘图标
Get-ChildItem $defaultsDir -Force | ForEach-Object {
    if ($_.PSIsContainer) {
        $subDest = Join-Path $stageDir $_.Name
        New-Item -ItemType Directory -Path $subDest -Force | Out-Null
        Copy-Item -Path (Join-Path $_.FullName "*") -Destination $subDest -Recurse -Force
    }
    else {
        Copy-Item -Path $_.FullName -Destination (Join-Path $stageDir $_.Name) -Force
    }
}

# 7. 严格的个人隐私泄露审计
Write-Host "[Ifboard Audit] 正在扫描公开版打包目录隐私项..." -ForegroundColor Cyan
$forbiddenNames = @("*.userdb*", "installation.yaml", "user.yaml", "webdav*")
foreach ($pat in $forbiddenNames) {
    $hits = Get-ChildItem -Path $stageDir -Recurse -Filter $pat
    if ($hits) {
        throw "隐私审计失败：公开目录包含禁止文件 $($hits[0].FullName)"
    }
}

$forbiddenRegex = "app\.koofr\.net|dav\.jianguoyun\.com|C:\\\\Users\\\\|(?:password|webdav_pass)\s*[:=]\s*\S+"
$textFiles = Get-ChildItem -Path $stageDir -Recurse -File | Where-Object { $_.Extension -in @(".yaml", ".yml", ".txt", ".json", ".lua") }
$leakMatches = $textFiles | Select-String -Pattern $forbiddenRegex -CaseSensitive:$false
if ($leakMatches) {
    $first = $leakMatches[0]
    throw "隐私审计失败：发现敏感字符 $($first.Path):$($first.LineNumber) -> $($first.Line)"
}
# 额外检查是否存在非上游作者注释行中的个人邮箱
$emailHits = $textFiles | Select-String -Pattern "[a-zA-Z0-9._%+-]+@(gmail|qq|163|126|outlook|hotmail)\.com" -CaseSensitive:$false |
    Where-Object { $_.Line -notmatch "<[^>]+@[^>]+>" -and $_.Line -notmatch "Copyright" }
if ($emailHits) {
    $firstE = $emailHits[0]
    throw "隐私审计失败：发现未知个人邮箱 $($firstE.Path):$($firstE.LineNumber) -> $($firstE.Line)"
}

$totalSizeMb = ((Get-ChildItem $stageDir -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB
$fileCount = (Get-ChildItem $stageDir -Recurse -File).Count
Write-Host ("[Ifboard Stage] 完成！共打包 {0} 个纯净文件，总大小 {1:F1} MB，隐私审计 0 泄露。" -f $fileCount, $totalSizeMb) -ForegroundColor Green
