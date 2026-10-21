@echo off
title GuidanceApp Live Dashboard
cd /d "%~dp0dashboard"

echo ====================================================================
echo  Starting GuidanceApp Telemetry & Visualization Dashboard...
echo ====================================================================
echo.

npm run dev
pause
