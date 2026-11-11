@echo off
REM ============================================================
REM  Build mc-launcher.jar
REM  Requires JDK 17+. ASCII only, see run.bat header note.
REM ============================================================

chcp 65001 >nul 2>nul
cd /d "%~dp0"

echo =========================================
echo   Building mc-launcher.jar
echo =========================================

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 17 or newer.
    pause
    exit /b 1
)

if exist build rmdir /s /q build
mkdir build

echo [INFO] Compiling...
javac -d build -encoding UTF-8 src\main\java\com\mc\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo [INFO] Packaging...
(
    echo Manifest-Version: 1.0
    echo Main-Class: com.mc.Launcher
    echo Implementation-Title: mc-launcher
    echo Implementation-Version: 1.1
    echo Implementation-Vendor: chenhuitony
    echo Author: chenhuitony
    echo Created-By: chenhuitony
) > build\MANIFEST.MF

jar --create --file mc-launcher.jar --manifest build\MANIFEST.MF -C build com
if errorlevel 1 (
    echo [ERROR] Packaging failed.
    pause
    exit /b 1
)

rmdir /s /q build

echo.
echo [OK] Built: mc-launcher.jar
echo      Run with: start.bat   or   java -jar mc-launcher.jar
pause
