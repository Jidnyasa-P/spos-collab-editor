@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b 1
echo.
echo ===== Self tests =====
java -cp out com.spos.editor.SelfTests
if errorlevel 1 exit /b 1
echo.
echo ===== Stress test =====
java -cp out com.spos.editor.StressTest
