@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b 1
if "%PORT%"=="" set PORT=8080
echo.
java -cp out com.spos.editor.Server
