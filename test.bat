@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist curl2burp.jar (
    call build.bat
)
java -cp curl2burp.jar curl2burp.Tests
pause
