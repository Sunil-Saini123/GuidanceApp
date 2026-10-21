# GuidanceApp - Auto-Grant Development Permissions
$adbPath = "adb"
if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
    $sdkAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $adbPath = $sdkAdb
    } else {
        Write-Error "adb.exe not found in PATH or $sdkAdb"
        exit 1
    }
}

Write-Host "====================================================================" -ForegroundColor Cyan
Write-Host " GuidanceApp - Auto-Grant Development Permissions" -ForegroundColor Cyan
Write-Host "====================================================================" -ForegroundColor Cyan

Write-Host "[1/5] Checking connected device..." -ForegroundColor Yellow
& $adbPath devices

Write-Host "[2/5] Granting WRITE_SECURE_SETTINGS..." -ForegroundColor Yellow
& $adbPath shell pm grant com.example.floatingassistant android.permission.WRITE_SECURE_SETTINGS

Write-Host "[3/5] Granting SYSTEM_ALERT_WINDOW (Draw over other apps)..." -ForegroundColor Yellow
& $adbPath shell appops set com.example.floatingassistant SYSTEM_ALERT_WINDOW allow

Write-Host "[4/5] Allowing Restricted Settings..." -ForegroundColor Yellow
& $adbPath shell appops set com.example.floatingassistant ACCESS_RESTRICTED_SETTINGS allow

Write-Host "[5/5] Activating Accessibility Service in Settings..." -ForegroundColor Yellow
& $adbPath shell settings put secure enabled_accessibility_services com.example.floatingassistant/com.example.floatingassistant.UiTreeAccessibilityService
& $adbPath shell settings put secure accessibility_enabled 1

Write-Host "====================================================================" -ForegroundColor Green
Write-Host " SUCCESS! All permissions granted and Accessibility Service activated." -ForegroundColor Green
Write-Host " From now on, updates launched from Android Studio will self-activate!" -ForegroundColor Green
Write-Host "====================================================================" -ForegroundColor Green
