#!/bin/bash
# Build the PhoneIDE APK
# Clones ctz168/ide and bundles IDE code into APK assets.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
ANDROID_DIR="$PROJECT_DIR/android"
IDE_REPO="${IDE_REPO:-ctz168/ide}"
IDE_BRANCH="${IDE_BRANCH:-main}"

echo "=== PhoneIDE APK Build ==="
echo ""

# Step 1: Fetch proot binaries if not present
if [ ! -f "$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a/libproot.so" ]; then
    echo "[1/4] Fetching PRoot binaries..."
    bash "$SCRIPT_DIR/scripts/fetch-proot-binaries.sh"
else
    echo "[1/4] PRoot binaries already present"
fi
echo ""

# Step 2: Clone IDE from ctz168/ide
echo "[2/4] Cloning IDE from https://github.com/${IDE_REPO}.git (branch: ${IDE_BRANCH})..."
IDE_TMP_DIR=$(mktemp -d)
trap "rm -rf $IDE_TMP_DIR" EXIT
git clone --depth 1 --branch "$IDE_BRANCH" "https://github.com/${IDE_REPO}.git" "$IDE_TMP_DIR"
echo "Cloned IDE commit: $(cd "$IDE_TMP_DIR" && git rev-parse HEAD)"
echo ""

# Step 3: Copy IDE files to Android assets
echo "[3/4] Copying IDE files to assets..."
mkdir -p "$ANDROID_DIR/app/src/main/assets/ide"
cp "$IDE_TMP_DIR/phoneide_server.py" "$ANDROID_DIR/app/src/main/assets/ide/"
cp "$IDE_TMP_DIR/utils.py" "$ANDROID_DIR/app/src/main/assets/ide/"
cp "$IDE_TMP_DIR/requirements.txt" "$ANDROID_DIR/app/src/main/assets/ide/"
cp -r "$IDE_TMP_DIR/routes" "$ANDROID_DIR/app/src/main/assets/ide/"
cp -r "$IDE_TMP_DIR/static" "$ANDROID_DIR/app/src/main/assets/ide/"

# Write version.txt and commit.txt
VERSION=$(grep 'versionName' "$ANDROID_DIR/app/build.gradle" | head -1 | sed 's/.*"\([^"]*\)".*/\1/')
IDE_COMMIT=$(cd "$IDE_TMP_DIR" && git rev-parse HEAD)
echo "v${VERSION}" > "$ANDROID_DIR/app/src/main/assets/ide/version.txt"
echo "$IDE_COMMIT" > "$ANDROID_DIR/app/src/main/assets/ide/commit.txt"
echo "Packaged IDE: v${VERSION} (commit: ${IDE_COMMIT:0:8})"
echo ""

# Clean up temp dir early
rm -rf "$IDE_TMP_DIR"
trap - EXIT

# Step 4: Build with Gradle
echo "[4/4] Building APK..."
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

# Output
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK_PATH" ]; then
    echo "=== Build Successful ==="
    echo "APK: $APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo "IDE:  ctz168/ide @ ${IDE_COMMIT:0:8}"
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
