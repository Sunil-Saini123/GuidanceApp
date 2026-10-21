@echo off
setlocal enabledelayedexpansion

echo ====================================================================
echo  GuidanceApp - Auto-Grant Development Permissions
echo ====================================================================
echo.

set ADB=adb
where adb >nul 2>nul
if %ERRORLEVEL% neq 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else (
        echo [ERROR] adb.exe not found in PATH or %LOCALAPPDATA%\Android\Sdk\platform-tools\
        echo Please ensure Android SDK platform-tools is installed.
        pause
        exit /b 1
    )
)

echo [1/5] Checking connected device...
%ADB% devices
echo.

echo [2/5] Granting WRITE_SECURE_SETTINGS (Allows app to self-activate Accessibility)...
%ADB% shell pm grant com.example.floatingassistant android.permission.WRITE_SECURE_SETTINGS

echo [3/5] Granting SYSTEM_ALERT_WINDOW (Draw over other apps overlay)...
%ADB% shell appops set com.example.floatingassistant SYSTEM_ALERT_WINDOW allow

echo [4/5] Allowing Restricted Settings (Bypasses Android 13/14/15/16 OEM restrictions)...
%ADB% shell appops set com.example.floatingassistant ACCESS_RESTRICTED_SETTINGS allow

echo [5/5] Enabling Accessibility Service in Android Settings right now...
%ADB% shell settings put secure enabled_accessibility_services com.example.floatingassistant/com.example.floatingassistant.UiTreeAccessibilityService
%ADB% shell settings put secure accessibility_enabled 1

echo.
echo ====================================================================
echo  SUCCESS! All permissions granted and Accessibility Service activated.
echo  Now, whenever you update and run the app from Android Studio,
echo  it will automatically activate Accessibility without asking!
echo ====================================================================
echo.
pause
