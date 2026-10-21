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
    $APK_SOURCE = Join-Path $RELEASE_DIR "app-release-unsigned.apk"
}

if (-not (Test-Path $APK_SOURCE)) {
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
