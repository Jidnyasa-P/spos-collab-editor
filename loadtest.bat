@echo off
REM FAST benchmark against a running server (it is supposed to be fast: it is a load test).
REM For the slow, narrated version use loaddemo.bat.
REM Usage: loadtest.bat [baseUrl] [readers] [writers] [seconds]
REM Example: loadtest.bat http://localhost:8080 30 6 8
setlocal
cd /d "%~dp0"
if not exist out call build.bat
java -cp out com.spos.editor.HttpLoadTest %*
