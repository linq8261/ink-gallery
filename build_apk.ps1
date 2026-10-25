# ============================================================
# APK Build Script (PowerShell 7)
# ============================================================
# Please modify the APK base name below (without extension and version)
# ============================================================
$APK_BASE_NAME = "ink-gallery"
# ============================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Android APK Build Tool" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Get project root directory
$PROJECT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $PROJECT_DIR

# ============================================================
# 签名配置校验：local.properties 缺少 signing.* 或 jks 不存在时直接终止，
# 避免构建出未签名的 release 包
# ============================================================
function Get-LocalProperty([string]$name) {
    $propsPath = Join-Path $PROJECT_DIR 'local.properties'
    if (-not (Test-Path -LiteralPath $propsPath)) { return '' }
    $escaped = [regex]::Escape($name)
    $line = Select-String -LiteralPath $propsPath -Pattern "^\s*$escaped\s*[=:]" | Select-Object -First 1
    if (-not $line) { return '' }
    $raw = $line.Line -replace "^\s*$escaped\s*[=:]\s*", ''
    return ((($raw -replace '\\:', ':') -replace '\\\\', '\').Trim())
}

function Assert-SigningConfigured {
    $keys = @('signing.storeFile', 'signing.storePassword', 'signing.keyAlias', 'signing.keyPassword')
    $missing = @()
    foreach ($k in $keys) {
        if (-not (Get-LocalProperty $k)) { $missing += $k }
    }
    if ($missing.Count -gt 0) {
        Write-Host "[ERROR] local.properties 缺少签名配置: $($missing -join ', ')" -ForegroundColor Red
        Write-Host "[ERROR] release 包必须签名，已终止（不会构建未签名产物）" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }

    $storeRaw = Get-LocalProperty 'signing.storeFile'
    $storePath = if ([System.IO.Path]::IsPathRooted($storeRaw)) { $storeRaw } else { Join-Path (Join-Path $PROJECT_DIR 'app') $storeRaw }
    $storePath = [System.IO.Path]::GetFullPath($storePath)
    if (-not (Test-Path -LiteralPath $storePath)) {
        Write-Host "[ERROR] 签名文件不存在: $storePath (signing.storeFile)" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
    Write-Host "  Keystore: $storePath" -ForegroundColor Green
}

# ============================================================
# Bugly AppID 校验：缺失时 release 包不会初始化 Bugly、崩溃静默不上报，
# 这种失效在本地看不出来，所以宁可构建前直接终止。
# 读取顺序与 app/build.gradle.kts 保持一致：local.properties → 环境变量
# ============================================================
function Assert-BuglyConfigured {
    $appId = Get-LocalProperty 'bugly.appId'
    if (-not $appId) { $appId = $env:BUGLY_APP_ID }
    if (-not $appId) {
        Write-Host "[ERROR] 缺少 Bugly AppID：local.properties 的 bugly.appId（或环境变量 BUGLY_APP_ID）" -ForegroundColor Red
        Write-Host "[ERROR] 缺失时 release 包不会上报崩溃，已终止" -ForegroundColor Red
        Write-Host "[ERROR] 在 local.properties 增加一行：bugly.appId=<Bugly 后台的 AppID>" -ForegroundColor DarkGray
        Read-Host "Press Enter to exit"
        exit 1
    }
    Write-Host "  Bugly AppID: $appId" -ForegroundColor Green
}

# Extract version info from build.gradle.kts
Write-Host "[1/4] Reading version info..." -ForegroundColor Yellow

try {
    $buildGradleContent = Get-Content "app\build.gradle.kts" -Raw

    if ($buildGradleContent -match 'versionCode\s*=\s*(\d+)') {
        $VERSION_CODE = $matches[1]
    } else {
        throw "Could not find versionCode in build.gradle.kts"
    }

    if ($buildGradleContent -match 'versionName\s*=\s*"([^"]+)"') {
        $VERSION_NAME = $matches[1]
    } else {
        throw "Could not find versionName in build.gradle.kts"
    }

    Write-Host "  Version Name: $VERSION_NAME" -ForegroundColor Green
    Write-Host "  Version Code: $VERSION_CODE" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Failed to read version info: $_" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Execute Gradle build command
Assert-SigningConfigured
Assert-BuglyConfigured
Write-Host "[2/4] Building APK with Gradle..." -ForegroundColor Yellow
try {
    & ".\gradlew.bat" "assembleRelease"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
    Write-Host "  [OK] Build successful" -ForegroundColor Green
} catch {
    Write-Host ""
    Write-Host "[ERROR] Build failed! Please check error messages above." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Find generated APK file
Write-Host "[3/4] Locating generated APK file..." -ForegroundColor Yellow
$RELEASE_DIR = Join-Path $PROJECT_DIR "app\build\outputs\apk\release"
$APK_SOURCE = Join-Path $RELEASE_DIR "app-release.apk"

if (-not (Test-Path $APK_SOURCE)) {
    $UNSIGNED_APK = Join-Path $RELEASE_DIR "app-release-unsigned.apk"
    if (Test-Path $UNSIGNED_APK) {
        Write-Host "[ERROR] 只产出了未签名包: $UNSIGNED_APK（签名配置未生效），已终止" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
    Write-Host "[ERROR] APK file not found in: $RELEASE_DIR" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host "  [OK] APK file found" -ForegroundColor Green

Write-Host ""

# Get desktop path
Write-Host "[4/4] Copying and renaming APK to Desktop..." -ForegroundColor Yellow
try {
    $DESKTOP_PATH = [Environment]::GetFolderPath("Desktop")
} catch {
    Write-Host "[ERROR] Could not get Desktop path: $_" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Build new filename: BaseName-VersionName-VersionCode.apk
$NEW_APK_NAME = "$APK_BASE_NAME-$VERSION_NAME-$VERSION_CODE.apk"
$DEST_PATH = Join-Path $DESKTOP_PATH $NEW_APK_NAME

try {
    Copy-Item -Path $APK_SOURCE -Destination $DEST_PATH -Force
} catch {
    Write-Host "[ERROR] Failed to copy file: $_" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Build Completed Successfully!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  File Name: $NEW_APK_NAME" -ForegroundColor Green
Write-Host "  Location: $DEST_PATH" -ForegroundColor Green
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan

Read-Host "Press Enter to exit"
