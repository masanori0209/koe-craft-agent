#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build/mic-bridge"
APP_NAME="KoeCraft Mic Bridge.app"
APP_BUNDLE="$ROOT_DIR/build/$APP_NAME"
APP_CONTENTS="$APP_BUNDLE/Contents"
APP_MACOS="$APP_CONTENTS/MacOS"
APP_RESOURCES="$APP_CONTENTS/Resources"
MAIN_CLASS="dev.koecraft.micbridge.KoeCraftMicBridge"
JAR_NAME="koecraft-mic-bridge.jar"
ONNXRUNTIME_VERSION="1.26.0"
ONNXRUNTIME_SHA256="cf5a48c6f5d07b15f10634b80433ddce8f5892662b1a122bbbc0907f4f442c60"
DEPS_DIR="$BUILD_DIR/deps"
ONNXRUNTIME_JAR="$DEPS_DIR/onnxruntime-$ONNXRUNTIME_VERSION.jar"

if ! command -v javac >/dev/null 2>&1; then
  echo "[mic-bridge] javac is required. Install JDK 21 or set JAVA_HOME." >&2
  exit 1
fi

rm -rf "$BUILD_DIR" "$APP_BUNDLE"
mkdir -p "$BUILD_DIR/classes" "$DEPS_DIR" "$APP_MACOS" "$APP_RESOURCES/lib"

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
jar --create --file "$BUILD_DIR/$JAR_NAME" -C "$BUILD_DIR/classes" .
cp "$BUILD_DIR/$JAR_NAME" "$APP_RESOURCES/$JAR_NAME"
cp "$ONNXRUNTIME_JAR" "$APP_RESOURCES/lib/onnxruntime-$ONNXRUNTIME_VERSION.jar"
cp "$ROOT_DIR/tools/mic-bridge/koecraft-mic-bridge-icon.svg" "$APP_RESOURCES/koecraft-mic-bridge-icon.svg"

java -cp "$BUILD_DIR/classes" dev.koecraft.micbridge.KoeCraftMicBridgeIconGenerator "$BUILD_DIR/koecraft-mic-bridge.iconset"
if command -v iconutil >/dev/null 2>&1; then
  iconutil -c icns "$BUILD_DIR/koecraft-mic-bridge.iconset" -o "$APP_RESOURCES/koecraft-mic-bridge.icns"
fi

cat > "$APP_CONTENTS/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>ja</string>
  <key>CFBundleExecutable</key>
  <string>KoeCraftMicBridge</string>
  <key>CFBundleIdentifier</key>
  <string>dev.koecraft.micbridge</string>
  <key>CFBundleIconFile</key>
  <string>koecraft-mic-bridge</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>KoeCraft Mic Bridge</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSMinimumSystemVersion</key>
  <string>12.0</string>
  <key>NSHighResolutionCapable</key>
  <true/>
  <key>NSMicrophoneUsageDescription</key>
  <string>KoeCraft uses the microphone to recognize Minecraft voice commands.</string>
</dict>
</plist>
PLIST

cat > "$APP_MACOS/KoeCraftMicBridge" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail

CONTENTS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_PATH="$CONTENTS_DIR/Resources/koecraft-mic-bridge.jar"

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "/opt/homebrew/opt/openjdk@21/bin/java" ]; then
  JAVA_BIN="/opt/homebrew/opt/openjdk@21/bin/java"
elif [ -x "/usr/local/opt/openjdk@21/bin/java" ]; then
  JAVA_BIN="/usr/local/opt/openjdk@21/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi

if [ -z "$JAVA_BIN" ]; then
  /usr/bin/osascript -e 'display dialog "KoeCraft Mic Bridge needs Java 21. Install a JDK or set JAVA_HOME." buttons {"OK"} default button "OK" with icon caution'
  exit 1
fi

exec "$JAVA_BIN" -cp "$JAR_PATH:$CONTENTS_DIR/Resources/lib/*" dev.koecraft.micbridge.KoeCraftMicBridge
LAUNCHER
chmod +x "$APP_MACOS/KoeCraftMicBridge"

if command -v codesign >/dev/null 2>&1; then
  codesign --force --deep --sign - "$APP_BUNDLE" >/dev/null
fi

if command -v xattr >/dev/null 2>&1; then
  xattr -dr com.apple.quarantine "$APP_BUNDLE" 2>/dev/null || true
fi

mkdir -p "$HOME/Applications"
rm -rf "$HOME/Applications/$APP_NAME"
cp -R "$APP_BUNDLE" "$HOME/Applications/$APP_NAME"

echo "[mic-bridge] built: $APP_BUNDLE"
echo "[mic-bridge] installed: $HOME/Applications/$APP_NAME"
