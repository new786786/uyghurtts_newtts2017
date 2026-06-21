@echo off
chcp 65001 >nul
echo ======================================
echo   Uyghur TTS Web Server
echo   http://localhost:8080
echo ======================================
echo.
php -S localhost:8080 -t "%~dp0"
pause
