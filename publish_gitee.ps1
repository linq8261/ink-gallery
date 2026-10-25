# ============================================================
# 本地打包 + Gitee Release 发布（PowerShell 7）
# ============================================================
# 用法：
#   .\publish_gitee.ps1                    # 用 HEAD 上的 tag，打包并发布
#   .\publish_gitee.ps1 -Tag 20261023-1.0.1-2
#   .\publish_gitee.ps1 -SkipBuild         # 跳过编译，用已有 APK 重新发布
#   .\publish_gitee.ps1 -Prerelease        # 标记为预览版
#   .\publish_gitee.ps1 -SkipServerRegister # 只发 Gitee，不登记 my-server
#
# 令牌来源（优先级从高到低）：
#   1. -Token 参数
#   2. 环境变量 GITEE_TOKEN
#   3. 令牌文件 -TokenFile（默认 D:\sync\jks\gitee私人令牌.txt）
#      文件内容为 DPAPI(CurrentUser) 加密后的 Base64，格式：
#      GITEE_TOKEN_DPAPI_V1:<base64>
#
# my-server 凭据来源（第 8 步登记版本用，优先级从高到低）：
#   1. -ServerToken 参数 / 环境变量 MY_SERVER_TOKEN（access token，24h 有效）
#   2. 账号文件 -ServerCredFile（默认 D:\sync\jks\my-server账号.txt）
#      内容为 phone|password，或 DPAPI 加密后的 MY_SERVER_LOGIN_DPAPI_V1:<base64>
#   缺失时只警告并跳过登记，不影响已经成功的 Gitee 发布。
#
# 前置条件（脚本只检查，不会替你创建）：
#   1. tag 已手动创建，本地存在
#   2. tag 已推送到 gitee 远程
#   3. my-server 账号需为 admin 角色，否则登记接口返回 403
# ============================================================
param(
    [string]$Tag = '',
    [switch]$SkipBuild,
    [switch]$Prerelease,
    [string]$Token = $env:GITEE_TOKEN,
    [string]$TokenFile = 'D:\sync\jks\gitee私人令牌.txt',
    [string]$Owner = 'qiantu',
    [string]$Repo = 'ink-gallery',
    [string]$Remote = 'gitee',
    # 发布完成后顺带在 my-server 登记版本（App 内「检查更新」读的就是这条记录）
    [switch]$SkipServerRegister,
    [string]$ServerToken = $env:MY_SERVER_TOKEN,
    [string]$ServerCredFile = 'D:\sync\jks\my-server账号.txt',
    [string]$ServerBaseUrl = 'https://203.195.213.233',
    [string]$PackageName = 'com.ink.gallery'
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Host "[失败] 需要 PowerShell 7（Invoke-RestMethod -Form 上传附件），当前版本 $($PSVersionTable.PSVersion)" -ForegroundColor Red
    exit 1
}

$PROJECT_DIR = $PSScriptRoot
Set-Location $PROJECT_DIR

$apiBase = "https://gitee.com/api/v5/repos/$Owner/$Repo"

function Write-Step([string]$index, [string]$text) {
    Write-Host "[$index] $text" -ForegroundColor Yellow
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host "[失败] $message" -ForegroundColor Red
    exit 1
}

function Get-HttpStatus($errorRecord) {
    if ($errorRecord.Exception.Response) {
        return [int]$errorRecord.Exception.Response.StatusCode
    }
    return 0
}

function Get-HttpBody($errorRecord) {
    if ($errorRecord.ErrorDetails -and $errorRecord.ErrorDetails.Message) {
        return $errorRecord.ErrorDetails.Message
    }
    return $errorRecord.Exception.Message
}

# 读取 local.properties 中的单个键（处理 Properties 的 \: 与 \\ 转义）
function Get-LocalProperty([string]$name) {
    $propsPath = Join-Path $PROJECT_DIR 'local.properties'
    if (-not (Test-Path -LiteralPath $propsPath)) { return '' }
    $escaped = [regex]::Escape($name)
    $line = Select-String -LiteralPath $propsPath -Pattern "^\s*$escaped\s*[=:]" | Select-Object -First 1
    if (-not $line) { return '' }
    $raw = $line.Line -replace "^\s*$escaped\s*[=:]\s*", ''
    return ((($raw -replace '\\:', ':') -replace '\\\\', '\').Trim())
}

# 构建前强制校验签名配置：缺配置直接终止，不产出未签名 release 包
function Assert-SigningConfigured {
    $keys = @('signing.storeFile', 'signing.storePassword', 'signing.keyAlias', 'signing.keyPassword')
    $missing = @()
    foreach ($k in $keys) {
        if (-not (Get-LocalProperty $k)) { $missing += $k }
    }
    if ($missing.Count -gt 0) {
        Fail "local.properties 缺少签名配置：$($missing -join ', ')。release 包必须签名，已终止（不会构建未签名产物）。"
    }

    $storeRaw = Get-LocalProperty 'signing.storeFile'
    $storePath = if ([System.IO.Path]::IsPathRooted($storeRaw)) { $storeRaw } else { Join-Path (Join-Path $PROJECT_DIR 'app') $storeRaw }
    $storePath = [System.IO.Path]::GetFullPath($storePath)
    if (-not (Test-Path -LiteralPath $storePath)) {
        Fail "签名文件不存在：$storePath（local.properties 的 signing.storeFile）"
    }
    Write-Host "  签名文件: $storePath" -ForegroundColor Green
}

# 构建前强制校验 Bugly AppID：缺失时 release 包不会上报崩溃（静默失效），直接终止。
# 读取顺序与 app/build.gradle.kts 一致：local.properties → 环境变量
function Assert-BuglyConfigured {
    $appId = Get-LocalProperty 'bugly.appId'
    if (-not $appId) { $appId = $env:BUGLY_APP_ID }
    if (-not $appId) {
        Fail "缺少 Bugly AppID：local.properties 的 bugly.appId（或环境变量 BUGLY_APP_ID）。缺失时 release 包不会上报崩溃，已终止。请在 local.properties 增加一行：bugly.appId=<Bugly 后台的 AppID>"
    }
    Write-Host "  Bugly AppID: $appId" -ForegroundColor Green
}

# 读取 my-server 的 access token。
# 优先级：-ServerToken / 环境变量 MY_SERVER_TOKEN > 账号文件（phone|password，支持 DPAPI 加密）。
# 任何一步失败都只返回 $null 由调用方警告跳过，不影响已经成功的 Gitee 发布。
function Get-ServerToken {
    if ($ServerToken) { return $ServerToken }

    if (-not (Test-Path -LiteralPath $ServerCredFile)) { return $null }

    $raw = (Get-Content -Raw -LiteralPath $ServerCredFile).Trim()
    if (-not $raw) { return $null }

    if ($raw -match '^MY_SERVER_LOGIN_DPAPI_V1:(.+)$') {
        $b64 = $matches[1].Trim()
        try {
            Add-Type -AssemblyName System.Security.Cryptography.ProtectedData -ErrorAction Stop
            $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
                [Convert]::FromBase64String($b64), $null, 'CurrentUser')
            $raw = [System.Text.Encoding]::UTF8.GetString($plain).Trim()
        } catch {
            Write-Host "  [警告] 服务端账号解密失败：$($_.Exception.Message)" -ForegroundColor DarkYellow
            return $null
        }
    } else {
        Write-Host '  [警告] 服务端账号文件是明文，建议改为 DPAPI 加密存储' -ForegroundColor DarkYellow
    }

    $sep = $raw.IndexOf('|')
    if ($sep -lt 1) {
        Write-Host "  [警告] 服务端账号文件格式应为 phone|password：$ServerCredFile" -ForegroundColor DarkYellow
        return $null
    }
    $phone = $raw.Substring(0, $sep).Trim()
    $password = $raw.Substring($sep + 1).Trim()

    try {
        $loginBody = @{ phone = $phone; password = $password } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Uri "$ServerBaseUrl/api/v1/auth/login" -Method Post `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) `
            -ContentType 'application/json' -SkipCertificateCheck
    } catch {
        Write-Host "  [警告] 服务端登录失败：$(Get-HttpBody $_)" -ForegroundColor DarkYellow
        return $null
    }

    if (-not $resp.data.accessToken) {
        Write-Host "  [警告] 服务端登录未返回 accessToken：$($resp.message)" -ForegroundColor DarkYellow
        return $null
    }
    return $resp.data.accessToken
}

# 在 my-server 登记本次版本：App 内「检查更新」读的就是这条记录，
# 不登记的话线上用户永远看到「已是最新版本」。
function Register-ServerVersion([string]$updateContent) {
    if ($SkipServerRegister) {
        Write-Host '  跳过服务端登记（-SkipServerRegister）' -ForegroundColor DarkGray
        return
    }

    $releasePageUrl = "https://gitee.com/$Owner/$Repo/releases/tag/$Tag"

    # 已登记过（或服务端版本更高）就不重复写。404 表示还没有任何记录，继续往下走。
    try {
        $latest = Invoke-RestMethod -Uri "$ServerBaseUrl/api/v1/apps/$PackageName/versions/latest" -SkipCertificateCheck
        $latestCode = 0
        if ($null -ne $latest.data.versionCode) { $latestCode = [int]$latest.data.versionCode }
        if ($latestCode -ge [int]$VERSION_CODE) {
            Write-Host "  服务端已有 $($latest.data.versionName) ($latestCode)，无需重复登记" -ForegroundColor Green
            return
        }
    } catch {
        # 没有历史记录，属于首次登记
    }

    $token = Get-ServerToken
    if (-not $token) {
        Write-Host '  [警告] 未配置服务端凭据，本次跳过版本登记' -ForegroundColor DarkYellow
        Write-Host "  [警告] 修复：在 $ServerCredFile 写入 phone|password，或用 -ServerToken / 环境变量 MY_SERVER_TOKEN" -ForegroundColor DarkGray
        return
    }

    $payload = @{
        packageName   = $PackageName
        versionCode   = [int]$VERSION_CODE
        versionName   = $VERSION_NAME
        updateTitle   = '版本更新'
        updateContent = $updateContent
        url           = $releasePageUrl
        fileSize      = "$apkSizeMB MB"
        isForceUpdate = $false
    } | ConvertTo-Json

    try {
        Invoke-RestMethod -Uri "$ServerBaseUrl/api/v1/apps/$PackageName/versions" -Method Post `
            -Headers @{ Authorization = "Bearer $token" } `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) `
            -ContentType 'application/json' -SkipCertificateCheck | Out-Null
    } catch {
        $status = Get-HttpStatus $_
        if ($status -eq 403) {
            Write-Host '  [警告] 服务端登记失败：当前账号不是管理员（HTTP 403）' -ForegroundColor DarkYellow
        } else {
            Write-Host "  [警告] 服务端登记失败（HTTP $status）：$(Get-HttpBody $_)" -ForegroundColor DarkYellow
        }
        return
    }

    Write-Host "  已登记：$PackageName $VERSION_NAME ($VERSION_CODE)" -ForegroundColor Green
    Write-Host "  服务端下载页: $releasePageUrl" -ForegroundColor Green
}

Write-Host '========================================' -ForegroundColor Cyan
Write-Host '   打包并发布到 Gitee Release' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan

# ---------- 1. 确认 tag ----------
Write-Step '1/8' '检查 tag...'

if (-not $Tag) {
    $headTag = & git describe --tags --exact-match 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $headTag) {
        Fail '当前 HEAD 上没有 tag。请先手动执行 git tag <tag名> 并推送后再运行本脚本。'
    }
    $Tag = $headTag.Trim()
}

$localTag = & git rev-parse -q --verify "refs/tags/$Tag" 2>$null
if (-not $localTag) {
    Fail "本地不存在 tag：$Tag。请先手动创建：git tag $Tag"
}

$remoteTag = & git ls-remote --tags $Remote "refs/tags/$Tag" 2>$null
if (-not $remoteTag) {
    Fail "远程 $Remote 上不存在 tag：$Tag。请先手动推送：git push $Remote $Tag"
}

Write-Host "  tag: $Tag" -ForegroundColor Green
Write-Host "  tag 提交: $($localTag.Trim())" -ForegroundColor DarkGray

# ---------- 2. 版本号与一致性 ----------
Write-Step '2/8' '读取版本号并校验 tag...'

$buildGradle = Get-Content (Join-Path $PROJECT_DIR 'app\build.gradle.kts') -Raw
if ($buildGradle -notmatch 'versionCode\s*=\s*(\d+)') { Fail 'app/build.gradle.kts 里找不到 versionCode' }
$VERSION_CODE = $matches[1]
if ($buildGradle -notmatch 'versionName\s*=\s*"([^"]+)"') { Fail 'app/build.gradle.kts 里找不到 versionName' }
$VERSION_NAME = $matches[1]

Write-Host "  versionName: $VERSION_NAME" -ForegroundColor Green
Write-Host "  versionCode: $VERSION_CODE" -ForegroundColor Green

# 支持两种 tag 命名：
#   20261023-1.0.1-2  → 同时校验 versionName 与 versionCode
#   v1.0.1 / 1.0.1    → 只校验 versionName
if ($Tag -match '^\d{8}-(\d+\.\d+\.\d+)-(\d+)$') {
    $tagVer = $matches[1]; $tagCode = $matches[2]
    if ($tagVer -ne $VERSION_NAME -or $tagCode -ne $VERSION_CODE) {
        Fail "tag 版本($tagVer/$tagCode) 与 build.gradle.kts($VERSION_NAME/$VERSION_CODE) 不一致，请先改好版本号并重新打 tag。"
    }
} elseif ($Tag -match '^v?(\d+\.\d+\.\d+)$') {
    if ($matches[1] -ne $VERSION_NAME) {
        Fail "tag 版本($($matches[1])) 与 versionName($VERSION_NAME) 不一致，请先改好版本号并重新打 tag。"
    }
} else {
    Write-Host '  [警告] tag 命名无法解析版本号，跳过版本一致性校验' -ForegroundColor DarkYellow
}

$headSha = (& git rev-parse HEAD).Trim()
if ($headSha -ne $localTag.Trim()) {
    Write-Host "  [警告] tag 指向 $($localTag.Trim())，当前 HEAD 是 $headSha，发布的是工作区代码" -ForegroundColor DarkYellow
}

# ---------- 3. 令牌 ----------
Write-Step '3/8' '读取 Gitee 令牌...'

function Get-TokenFromFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        Fail "找不到令牌文件：$path。可用 -TokenFile 指定路径，或改用 -Token / 环境变量 GITEE_TOKEN。"
    }
    $raw = (Get-Content -Raw -LiteralPath $path).Trim()
    if (-not $raw) { Fail "令牌文件为空：$path" }

    if ($raw -match '^GITEE_TOKEN_DPAPI_V1:(.+)$') {
        $b64Text = $matches[1].Trim()
        try {
            Add-Type -AssemblyName System.Security.Cryptography.ProtectedData -ErrorAction Stop
            $blob = [Convert]::FromBase64String($b64Text)
            $plain = [System.Security.Cryptography.ProtectedData]::Unprotect($blob, $null, 'CurrentUser')
            Write-Host '  令牌已解密（DPAPI / CurrentUser）' -ForegroundColor Green
            return [System.Text.Encoding]::UTF8.GetString($plain).Trim()
        } catch {
            Fail "令牌解密失败：$($_.Exception.Message)。令牌只能由加密时的同一 Windows 用户在同一台机器上解密，换了机器或用户请重新加密令牌文件。"
        }
    }

    Write-Host '  [警告] 令牌文件是明文，建议改为 DPAPI 加密存储' -ForegroundColor DarkYellow
    return $raw
}

if (-not $Token) {
    $Token = Get-TokenFromFile $TokenFile
}

try {
    $user = Invoke-RestMethod -Uri "https://gitee.com/api/v5/user?access_token=$([uri]::EscapeDataString($Token))" -Method Get
    Write-Host "  令牌有效，账号: $($user.login)" -ForegroundColor Green
} catch {
    Fail "令牌校验失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
}

# ---------- 4. 打包 ----------
$apkName = "ink-gallery-$VERSION_NAME-$VERSION_CODE.apk"
$distDir = Join-Path $PROJECT_DIR 'build\dist'
$apkPath = Join-Path $distDir $apkName

if ($SkipBuild) {
    Write-Step '4/8' '跳过打包（-SkipBuild）'
    if (-not (Test-Path $apkPath)) {
        Fail "找不到已有 APK：$apkPath，去掉 -SkipBuild 重新打包。"
    }
} else {
    Write-Step '4/8' '校验签名与 Bugly 配置并打包 release APK...'
    Assert-SigningConfigured
    Assert-BuglyConfigured

    & '.\gradlew.bat' assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle 打包失败（exit=$LASTEXITCODE）"
    }

    $releaseDir = Join-Path $PROJECT_DIR 'app\build\outputs\apk\release'
    $builtApk = Join-Path $releaseDir 'app-release.apk'
    if (-not (Test-Path $builtApk)) {
        $unsignedApk = Join-Path $releaseDir 'app-release-unsigned.apk'
        if (Test-Path $unsignedApk) {
            Fail "只产出了未签名包：$unsignedApk，签名配置未生效，已终止发布。"
        }
        Fail "未找到构建产物：$releaseDir"
    }

    New-Item -ItemType Directory -Path $distDir -Force | Out-Null
    Copy-Item -LiteralPath $builtApk -Destination $apkPath -Force
}

$apkSizeMB = [math]::Round((Get-Item -LiteralPath $apkPath).Length / 1MB, 2)
Write-Host "  APK: $apkPath ($apkSizeMB MB)" -ForegroundColor Green

# ---------- 5. 创建或复用 Release ----------
Write-Step '5/8' '创建/复用 Gitee Release...'

$releaseBody = @()
$releaseBody += '## 版本信息'
$releaseBody += ''
$releaseBody += "- versionName: ``$VERSION_NAME``"
$releaseBody += "- versionCode: ``$VERSION_CODE``"
$releaseBody += "- 构建时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$releaseBody += ''
$releaseBody += '## 更新内容'
$releaseBody += ''

$prevTag = (& git describe --tags --abbrev=0 "$Tag^" 2>$null)
if ($LASTEXITCODE -eq 0 -and $prevTag) {
    $commits = & git log --oneline --no-merges "$($prevTag.Trim())..$Tag" 2>$null
} else {
    $commits = & git log --oneline --no-merges -n 20 $Tag 2>$null
}
if ($commits) {
    foreach ($line in $commits) { $releaseBody += "- $line" }
} else {
    $releaseBody += '- 无提交记录'
}
$releaseBodyText = ($releaseBody -join "`n")
$releaseName = "v$VERSION_NAME ($VERSION_CODE)"
$prereleaseFlag = if ($Prerelease.IsPresent) { 'true' } else { 'false' }

$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "$apiBase/releases/tags/$([uri]::EscapeDataString($Tag))?access_token=$([uri]::EscapeDataString($Token))" -Method Get
} catch {
    $status = Get-HttpStatus $_
    if ($status -ne 404) {
        Fail "查询 Release 失败（HTTP $status）：$(Get-HttpBody $_)"
    }
}

if ($existing) {
    $releaseId = $existing.id
    Write-Host "  Release 已存在（id=$releaseId），更新说明" -ForegroundColor Green
    $pairs = @(
        "access_token=$([uri]::EscapeDataString($Token))"
        "tag_name=$([uri]::EscapeDataString($Tag))"
        "name=$([uri]::EscapeDataString($releaseName))"
        "body=$([uri]::EscapeDataString($releaseBodyText))"
        "prerelease=$prereleaseFlag"
    )
    try {
        Invoke-RestMethod -Uri "$apiBase/releases/$releaseId" -Method Patch `
            -Body ($pairs -join '&') -ContentType 'application/x-www-form-urlencoded' | Out-Null
    } catch {
        Fail "更新 Release 失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
    }
} else {
    $pairs = @(
        "access_token=$([uri]::EscapeDataString($Token))"
        "tag_name=$([uri]::EscapeDataString($Tag))"
        "name=$([uri]::EscapeDataString($releaseName))"
        "body=$([uri]::EscapeDataString($releaseBodyText))"
        "target_commitish=$([uri]::EscapeDataString($localTag.Trim()))"
        "prerelease=$prereleaseFlag"
    )
    try {
        $created = Invoke-RestMethod -Uri "$apiBase/releases" -Method Post `
            -Body ($pairs -join '&') -ContentType 'application/x-www-form-urlencoded'
        $releaseId = $created.id
        Write-Host "  已创建 Release（id=$releaseId）" -ForegroundColor Green
    } catch {
        Fail "创建 Release 失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
    }
}

if (-not $releaseId) {
    Fail '未能获得 Release id'
}

# ---------- 6. 清理同名附件 ----------
Write-Step '6/8' '清理同名旧附件...'

try {
    $attachments = Invoke-RestMethod -Uri "$apiBase/releases/$releaseId/attach_files?access_token=$([uri]::EscapeDataString($Token))&per_page=100&page=1" -Method Get
} catch {
    Fail "读取附件列表失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
}
if ($null -eq $attachments) { $attachments = @() }
elseif ($attachments -isnot [array]) { $attachments = @($attachments) }

$removed = 0
foreach ($att in $attachments) {
    if ($att.name -eq $apkName) {
        try {
            Invoke-RestMethod -Uri "$apiBase/releases/$releaseId/attach_files/$($att.id)?access_token=$([uri]::EscapeDataString($Token))" -Method Delete | Out-Null
            $removed++
        } catch {
            Fail "删除旧附件失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
        }
    }
}
Write-Host "  删除同名附件: $removed 个" -ForegroundColor Green

# ---------- 7. 上传 APK ----------
Write-Step '7/8' '上传 APK...'

try {
    $uploaded = Invoke-RestMethod -Uri "$apiBase/releases/$releaseId/attach_files?access_token=$([uri]::EscapeDataString($Token))" -Method Post -Form @{
        file = Get-Item -LiteralPath $apkPath
    }
} catch {
    Fail "上传附件失败（HTTP $(Get-HttpStatus $_)）：$(Get-HttpBody $_)"
}

$downloadUrl = if ($uploaded.browser_download_url) { $uploaded.browser_download_url } else { '(未返回下载地址)' }

# ---------- 复制一份到桌面 ----------
$desktopApkPath = $null
try {
    $desktopDir = [Environment]::GetFolderPath('Desktop')
    if (-not $desktopDir) { throw '无法获取桌面路径' }
    $desktopApkPath = Join-Path $desktopDir $apkName
    Copy-Item -LiteralPath $apkPath -Destination $desktopApkPath -Force
    Write-Host "  已复制到桌面: $desktopApkPath" -ForegroundColor Green
} catch {
    $desktopApkPath = $null
    Write-Host "  [警告] 复制到桌面失败：$($_.Exception.Message)" -ForegroundColor DarkYellow
}

# ---------- 8. 登记服务端版本 ----------
Write-Step '8/8' '在 my-server 登记版本...'

# 用本次 tag 区间的提交生成更新说明，跳过「发版：」这类纯版本号提交
$serverUpdateLines = @()
foreach ($line in $commits) {
    $text = ($line -replace '^[0-9a-fA-F]{7,}\s+', '').Trim()
    if (-not $text) { continue }
    if ($text -match '^发版[：:]') { continue }
    $serverUpdateLines += "- $text"
}
$serverUpdateContent = if ($serverUpdateLines.Count -gt 0) { $serverUpdateLines -join "`n" } else { '版本更新' }

Register-ServerVersion $serverUpdateContent

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '   发布完成' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host "  Release : https://gitee.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Green
Write-Host "  下载地址: $downloadUrl" -ForegroundColor Green
Write-Host "  APK     : $apkName ($apkSizeMB MB)" -ForegroundColor Green
if ($desktopApkPath) {
    Write-Host "  桌面副本: $desktopApkPath" -ForegroundColor Green
}
Write-Host ''
