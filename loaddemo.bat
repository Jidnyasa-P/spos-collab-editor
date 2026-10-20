@echo off
REM Guided, slow demonstration of race conditions and how the lock prevents them.
REM   loaddemo.bat               waits for Enter between scenes (best for presenting)
REM   loaddemo.bat --auto        runs without waiting
REM Start run.bat first if you want scene 4 (the same rule over HTTP).
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b 1
echo.
java -cp out com.spos.editor.LoadDemo %*
