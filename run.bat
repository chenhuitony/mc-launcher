@echo off
REM ============================================================
REM  Minecraft Server Launcher
REM    no args    : vanilla server
REM    --paper    : Paper server (supports plugins)
REM  All Chinese output comes from Java, this file stays ASCII
REM  to avoid cmd.exe codepage desync problems.
REM ============================================================

chcp 65001 >nul 2>nul
cd /d "%~dp0"

echo =========================================
echo   Minecraft Server Launcher
echo =========================================

if not exist "src\main\java\com\mc\Launcher.java" (
    echo [ERROR] Source files not found. Use start.bat if you have mc-launcher.jar
    pause
    exit /b 1
)

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 17 or newer.
    pause
    exit /b 1
)

javac -d . -encoding UTF-8 src\main\java\com\mc\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 com.mc.Launcher %*

if errorlevel 1 (
    echo.
    echo [ERROR] Launcher exited with an error.
    pause
)
