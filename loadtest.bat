@echo off
REM Usage: loadtest.bat [baseUrl] [readers] [writers] [seconds]
REM Example: loadtest.bat http://localhost:8080 30 6 8
setlocal
cd /d "%~dp0"
if not exist out call build.bat
java -cp out com.spos.editor.HttpLoadTest %*
