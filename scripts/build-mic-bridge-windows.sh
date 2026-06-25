#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build/mic-bridge-windows-build"
DIST_DIR="$ROOT_DIR/build/windows/KoeCraft Mic Bridge"
MAIN_CLASS="dev.koecraft.micbridge.KoeCraftMicBridge"
JAR_NAME="koecraft-mic-bridge.jar"
ONNXRUNTIME_VERSION="1.26.0"
ONNXRUNTIME_SHA256="cf5a48c6f5d07b15f10634b80433ddce8f5892662b1a122bbbc0907f4f442c60"
DEPS_DIR="$BUILD_DIR/deps"
ONNXRUNTIME_JAR="$DEPS_DIR/onnxruntime-$ONNXRUNTIME_VERSION.jar"

if ! command -v javac >/dev/null 2>&1; then
  echo "[mic-bridge-windows] javac is required. Install JDK 21 or set JAVA_HOME." >&2
  exit 1
fi

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR/classes" "$DEPS_DIR" "$DIST_DIR/lib"

if [ ! -f "$ONNXRUNTIME_JAR" ]; then
  curl -L --fail --silent --show-error \
    -o "$ONNXRUNTIME_JAR" \
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$ONNXRUNTIME_VERSION/onnxruntime-$ONNXRUNTIME_VERSION.jar"
fi
printf '%s  %s\n' "$ONNXRUNTIME_SHA256" "$ONNXRUNTIME_JAR" | shasum -a 256 -c -

javac -encoding UTF-8 -cp "$ONNXRUNTIME_JAR" -d "$BUILD_DIR/classes" \
  "$ROOT_DIR/tools/mic-bridge/KoeCraftMicBridge.java" \
  "$ROOT_DIR/tools/mic-bridge/KoeCraftMicBridgeIconGenerator.java"
mkdir -p "$BUILD_DIR/classes/assets/koecraft-agent/models"
cp "$ROOT_DIR/mod/src/main/resources/assets/koecraft-agent/models/silero_vad_op18_ifless.onnx" \
  "$BUILD_DIR/classes/assets/koecraft-agent/models/silero_vad_op18_ifless.onnx"
jar --create --file "$DIST_DIR/$JAR_NAME" -C "$BUILD_DIR/classes" .
cp "$ONNXRUNTIME_JAR" "$DIST_DIR/lib/onnxruntime-$ONNXRUNTIME_VERSION.jar"
cp "$ROOT_DIR/tools/mic-bridge/koecraft-mic-bridge-icon.svg" "$DIST_DIR/koecraft-mic-bridge-icon.svg"

cat > "$DIST_DIR/KoeCraft Mic Bridge.bat" <<'BAT'
@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%koecraft-mic-bridge.jar"

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_EXE if exist "%ProgramFiles%\Eclipse Adoptium\jdk-21*\bin\javaw.exe" for /f "delims=" %%I in ('dir /b /s "%ProgramFiles%\Eclipse Adoptium\jdk-21*\bin\javaw.exe" 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%I"
if not defined JAVA_EXE if exist "%ProgramFiles%\Java\jdk-21*\bin\javaw.exe" for /f "delims=" %%I in ('dir /b /s "%ProgramFiles%\Java\jdk-21*\bin\javaw.exe" 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%I"
if not defined JAVA_EXE for /f "delims=" %%I in ('where javaw.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%I"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%I"

if not defined JAVA_EXE (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('KoeCraft Mic Bridge needs Java 21. Install Temurin JDK 21 or set JAVA_HOME.','KoeCraft Mic Bridge')"
  exit /b 1
)

start "" "%JAVA_EXE%" -cp "%JAR_PATH%;%SCRIPT_DIR%lib\*" dev.koecraft.micbridge.KoeCraftMicBridge
endlocal
BAT

cat > "$DIST_DIR/KoeCraft Mic Bridge.ps1" <<'PS1'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "koecraft-mic-bridge.jar"
$libPath = Join-Path $scriptDir "lib\*"

$javaCandidates = @()
if ($env:JAVA_HOME) {
  $javaCandidates += (Join-Path $env:JAVA_HOME "bin\javaw.exe")
  $javaCandidates += (Join-Path $env:JAVA_HOME "bin\java.exe")
}
$javaCandidates += "javaw.exe"
$javaCandidates += "java.exe"

$java = $null
foreach ($candidate in $javaCandidates) {
  try {
    $command = Get-Command $candidate -ErrorAction Stop
    $java = $command.Source
    break
  } catch {
  }
}

if (-not $java) {
  Add-Type -AssemblyName PresentationFramework
  [System.Windows.MessageBox]::Show("KoeCraft Mic Bridge needs Java 21. Install Temurin JDK 21 or set JAVA_HOME.", "KoeCraft Mic Bridge") | Out-Null
  exit 1
}

Start-Process -FilePath $java -ArgumentList @("-cp", "$jarPath;$libPath", "dev.koecraft.micbridge.KoeCraftMicBridge") -WorkingDirectory $scriptDir
PS1

cat > "$DIST_DIR/README-windows.txt" <<'README'
KoeCraft Mic Bridge for Windows
===============================

Run:
  1. Install Java 21 if needed.
     Recommended: Eclipse Temurin JDK 21.
  2. Double-click "KoeCraft Mic Bridge.bat".

What it does:
  - Opens the same KoeCraft Mic Bridge UI as macOS.
  - Records microphone audio while Mic is ON.
  - Sends audio to AmiVoice.
  - Sends recognized text to the Fabric MOD at http://127.0.0.1:8791/api/utterance.
  - Uses Windows System.Speech for TTS read-aloud.

Windows notes:
  - Allow microphone access in Windows Settings > Privacy & security > Microphone.
  - If Windows SmartScreen warns about the batch file, this is an unsigned local developer bundle.
  - API keys are read from the local Minecraft config, not from this folder.
README

if command -v zip >/dev/null 2>&1; then
  (cd "$ROOT_DIR/build/windows" && zip -qr "../KoeCraft-Mic-Bridge-Windows.zip" "KoeCraft Mic Bridge")
  echo "[mic-bridge-windows] zip: $ROOT_DIR/build/KoeCraft-Mic-Bridge-Windows.zip"
fi

echo "[mic-bridge-windows] built: $DIST_DIR"
