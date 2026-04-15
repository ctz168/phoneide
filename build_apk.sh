#!/bin/bash
# =====================================================
# PhoneIDE APK Build Script
# 构建 PhoneIDE Android APK 安装包
# =====================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/android"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  PhoneIDE APK Build Script     ${NC}"
echo -e "${BLUE}================================${NC}"

# ---- Configuration ----
BUILD_TYPE="${1:-debug}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
JAVA_HOME="${JAVA_HOME:-}"

# ---- Check prerequisites ----
check_prereqs() {
    echo -e "\n${YELLOW}[1/4] Checking prerequisites...${NC}"

    # Check Java
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo -e "  ${GREEN}✓${NC} Java: $JAVA_HOME"
    elif command -v javac &> /dev/null; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
        echo -e "  ${GREEN}✓${NC} Java: $JAVA_HOME"
    else
        echo -e "  ${RED}✗${NC} Java not found. Please install JDK 17+"
        echo -e "  ${YELLOW}  Ubuntu/Debian: sudo apt install openjdk-17-jdk${NC}"
        exit 1
    fi

    # Check Android SDK
    if [ ! -d "$ANDROID_HOME/platforms" ]; then
        echo -e "  ${RED}✗${NC} Android SDK not found at $ANDROID_HOME"
        echo -e "  ${YELLOW}  Set ANDROID_HOME environment variable${NC}"
        echo -e "  ${YELLOW}  Or install Android Studio / command-line tools${NC}"
        exit 1
    fi

    # Check Android platform
    if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
        echo -e "  ${YELLOW}!${NC} android-34 platform not found, installing..."
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" 2>/dev/null || \
        "$ANDROID_HOME/tools/bin/sdkmanager" "platforms;android-34" 2>/dev/null || \
        echo -e "  ${RED}✗${NC} Failed to install android-34 platform"
    fi

    # Check build tools
    if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
        echo -e "  ${YELLOW}!${NC} build-tools 34.0.0 not found, installing..."
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "build-tools;34.0.0" 2>/dev/null || \
        "$ANDROID_HOME/tools/bin/sdkmanager" "build-tools;34.0.0" 2>/dev/null || \
        echo -e "  ${RED}✗${NC} Failed to install build-tools"
    fi

    echo -e "  ${GREEN}✓${NC} Android SDK: $ANDROID_HOME"
    echo -e "  ${GREEN}✓${NC} Build type: $BUILD_TYPE"
}

# ---- Download Gradle wrapper ----
setup_gradle() {
    echo -e "\n${YELLOW}[2/4] Setting up Gradle...${NC}"

    if [ -f "gradlew" ]; then
        echo -e "  ${GREEN}✓${NC} Gradle wrapper found"
        chmod +x gradlew
        GRADLE_CMD="./gradlew"
    else
        # Use system gradle or download
        if command -v gradle &> /dev/null; then
            echo -e "  ${GREEN}✓${NC} Using system gradle"
            GRADLE_CMD="gradle"
        else
            echo -e "  ${YELLOW}!${NC} Downloading Gradle..."
            GRADLE_VERSION="8.5"
            GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
            GRADLE_CACHE="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
            GRADLE_HOME="$GRADLE_CACHE/gradle-${GRADLE_VERSION}"

            if [ ! -f "$GRADLE_HOME/bin/gradle" ]; then
                mkdir -p "$GRADLE_CACHE"
                TMPFILE=$(mktemp)
                echo -e "  Downloading from $GRADLE_URL..."
                curl -fsSL "$GRADLE_URL" -o "$TMPFILE"
                unzip -q "$TMPFILE" -d "$GRADLE_CACHE"
                rm -f "$TMPFILE"
            fi

            GRADLE_CMD="$GRADLE_HOME/bin/gradle"
            echo -e "  ${GREEN}✓${NC} Gradle downloaded"
        fi
    fi
}

# ---- Build APK ----
build_apk() {
    echo -e "\n${YELLOW}[3/4] Building APK ($BUILD_TYPE)...${NC}"

    export ANDROID_HOME
    export JAVA_HOME

    if [ "$BUILD_TYPE" = "release" ]; then
        # Release build requires signing config
        if [ ! -f "release.keystore" ]; then
            echo -e "  ${YELLOW}!${NC} No release keystore found, creating debug keystore..."
            keytool -genkeypair \
                -v -keystore release.keystore \
                -alias phoneide \
                -keyalg RSA -keysize 2048 \
                -validity 10000 \
                -storepass phoneide123 \
                -keypass phoneide123 \
                -dname "CN=PhoneIDE, OU=Dev, O=PhoneIDE, L=Unknown, ST=Unknown, C=CN" \
                2>/dev/null || true
        fi

        $GRADLE_CMD assembleRelease --no-daemon \
            -Pandroid.injected.signing.store.file="$SCRIPT_DIR/android/release.keystore" \
            -Pandroid.injected.signing.store.password=phoneide123 \
            -Pandroid.injected.signing.key.alias=phoneide \
            -Pandroid.injected.signing.key.password=phoneide123

        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    else
        $GRADLE_CMD assembleDebug --no-daemon
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    fi

    if [ -f "$APK_PATH" ]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo -e "  ${GREEN}✓${NC} APK built: $APK_PATH ($APK_SIZE)"
    else
        echo -e "  ${RED}✗${NC} Build failed - APK not found"
        exit 1
    fi
}

# ---- Copy to output ----
copy_output() {
    echo -e "\n${YELLOW}[4/4] Copying output...${NC}"

    OUTPUT_DIR="$SCRIPT_DIR/dist"
    mkdir -p "$OUTPUT_DIR"

    VERSION=$(grep "versionName" app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    OUTPUT_NAME="PhoneIDE-v${VERSION}-${BUILD_TYPE}-${TIMESTAMP}.apk"

    cp "$APK_PATH" "$OUTPUT_DIR/$OUTPUT_NAME"

    echo -e "  ${GREEN}✓${NC} Output: $OUTPUT_DIR/$OUTPUT_NAME"

    # Also copy to latest
    cp "$APK_PATH" "$OUTPUT_DIR/PhoneIDE-latest.apk"
    echo -e "  ${GREEN}✓${NC} Latest: $OUTPUT_DIR/PhoneIDE-latest.apk"
}

# ---- Summary ----
print_summary() {
    echo -e "\n${GREEN}================================${NC}"
    echo -e "${GREEN}  Build Complete!               ${NC}"
    echo -e "${GREEN}================================${NC}"
    echo -e ""
    echo -e "  APK: $OUTPUT_DIR/$OUTPUT_NAME"
    echo -e "  Size: $APK_SIZE"
    echo -e "  Type: $BUILD_TYPE"
    echo -e ""
    echo -e "${YELLOW}Install on Android:${NC}"
    echo -e "  adb install $OUTPUT_DIR/PhoneIDE-latest.apk"
    echo -e ""
    echo -e "${YELLOW}Or copy to phone and install:${NC}"
    echo -e "  Transfer $OUTPUT_DIR/PhoneIDE-latest.apk to your phone"
    echo -e "  Enable 'Install from unknown sources' in Settings"
    echo -e "  Open the APK file to install"
    echo ""
}

# ---- Main ----
check_prereqs
setup_gradle
build_apk
copy_output
print_summary
