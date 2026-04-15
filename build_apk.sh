#!/bin/bash
# Build the PhoneIDE APK
# Strictly following stableclaw_android's build-apk.sh pattern.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
ANDROID_DIR="$PROJECT_DIR/android"

echo "=== PhoneIDE APK Build ==="
echo ""

# Step 1: Fetch proot binaries if not present
if [ ! -f "$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a/libproot.so" ]; then
    echo "[1/3] Fetching PRoot binaries..."
    bash "$SCRIPT_DIR/scripts/fetch-proot-binaries.sh"
else
    echo "[1/3] PRoot binaries already present"
fi
echo ""

# Step 2: Build with Gradle
echo "[2/3] Building APK..."
cd "$ANDROID_DIR"

if [ -f "gradlew" ]; then
    chmod +x gradlew
    GRADLE_CMD="./gradlew"
elif command -v gradle &> /dev/null; then
    GRADLE_CMD="gradle"
else
    echo "ERROR: Gradle not found. Install Gradle or add a gradlew wrapper."
    echo "  See: https://gradle.org/install/"
    exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

$GRADLE_CMD assembleRelease --no-daemon
echo ""

# Step 3: Output
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK_PATH" ]; then
    echo "=== Build Successful ==="
    echo "APK: $APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo ""
    echo "Install: adb install $APK_PATH"
    echo ""

    # Copy to dist/
    DIST_DIR="$PROJECT_DIR/dist"
    mkdir -p "$DIST_DIR"
    cp "$APK_PATH" "$DIST_DIR/PhoneIDE-latest.apk"
    echo "Copied to: $DIST_DIR/PhoneIDE-latest.apk"
else
    echo "=== Build Failed ==="
    exit 1
fi
