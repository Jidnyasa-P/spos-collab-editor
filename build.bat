@echo off
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javac was not found. Install JDK 17 or newer, then open a NEW terminal.
  echo         Quick install:  winget install EclipseAdoptium.Temurin.17.JDK
  exit /b 1
)

if exist out rmdir /s /q out
mkdir out
javac --release 17 -encoding UTF-8 -d out src\com\spos\editor\*.java
if errorlevel 1 (
  echo [ERROR] Build failed. Read the messages above.
  exit /b 1
)
echo Build OK. Classes are in the "out" folder.
