@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist curl2burp.jar (
    call build.bat
    if errorlevel 1 exit /b 1
)
where javaw >nul 2>nul
if errorlevel 1 (
    echo 没有找到 javaw（JRE/JDK 8 及以上一般自带），请先装好 Java 再试。
    pause
    exit /b 1
)
rem 用 javaw 起窗口版：不挂控制台，双击后这个黑窗口立刻关掉，误点也不会把工具带走
start "" javaw -jar "%~dp0curl2burp.jar"
exit
