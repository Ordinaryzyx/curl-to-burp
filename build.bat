@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -source 1.8 -target 1.8 -d out src\curl2burp\*.java
if errorlevel 1 (
    echo.
    echo 编译失败，请检查上面的 javac 报错。
    pause
    exit /b 1
)
jar cfe curl2burp.jar curl2burp.Main -C out .
if errorlevel 1 (
    echo.
    echo 打包 jar 失败。
    pause
    exit /b 1
)
echo 编译打包完成：curl2burp.jar
endlocal
