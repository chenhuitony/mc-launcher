@echo off
REM ============================================================
REM  push.bat - Push to GitHub via Contents API
REM
REM  Direct github.com:443 is reset on this network and the
REM  gh-proxy.com mirror rejects writes, so this calls push.ps1
REM  which mirrors the local HEAD tree through the GitHub API.
REM
REM  Usage:
REM    push.bat                     push with default message
REM    push.bat "feat: new thing"   push with custom message
REM ============================================================

chcp 65001 >nul 2>nul
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push.ps1" -Message "%~1"

if errorlevel 1 (
    echo.
    echo [ERROR] Push failed.
    pause
    exit /b 1
)

pause
