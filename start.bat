@echo off
REM ============================================================
REM  Minecraft Server Launcher (jar mode, no compilation needed)
REM    no args    : vanilla server
REM    --paper    : Paper server (supports plugins)
REM ============================================================

chcp 65001 >nul 2>nul
cd /d "%~dp0"

if not exist "mc-launcher.jar" (
    echo [ERROR] mc-launcher.jar not found.
    echo         Run build.bat to create it, or use run.bat for source mode.
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar mc-launcher.jar %*

if errorlevel 1 (
    echo.
    echo [ERROR] Launcher exited with an error.
    pause
)
