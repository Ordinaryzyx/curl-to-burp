@echo off
cd /d "%~dp0"
if not exist curl2burp.jar (
    call build.bat
    if errorlevel 1 exit /b 1
)
java -jar curl2burp.jar
