#!/usr/bin/env bash
# fetch-proot-binaries.sh
# Downloads proot binaries from Termux APT repository and places them in jniLibs
# Run this before building the APK
#
# Usage: bash scripts/fetch-proot-binaries.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JNI_DIR="$PROJECT_DIR/android/app/src/main/jniLibs"

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Architecture mapping: Android ABI -> Termux arch -> deb arch
ARCHS=(
    "arm64-v8a:aarch64"
    "armeabi-v7a:arm"
    "x86_64:x86_64"
)

# Packages to fetch from Termux
PACKAGES=("proot" "libtalloc")

# Create temp directory
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

log_info "Downloading proot binaries from Termux APT repository..."
log_info "Temporary directory: $TMPDIR"

for entry in "${ARCHS[@]}"; do
    IFS=':' read -r abi deb_arch <<< "$entry"
    abi_dir="$JNI_DIR/$abi"
    mkdir -p "$abi_dir"
    
    log_info "Processing $abi ($deb_arch)..."
    
    for pkg in "${PACKAGES[@]}"; do
        log_info "  Fetching $pkg..."
        
        # Get package info from Packages file
        PACKAGES_FILE="$TMPDIR/Packages_${abi}_${pkg}"
        
        # Try to get the Packages index
        if [ ! -f "$PACKAGES_FILE" ]; then
            log_info "  Downloading package index for $abi..."
            curl -fsSL "${TERMUX_REPO}/dists/stable/main/binary-${deb_arch}/Packages" \
                -o "$PACKAGES_FILE" 2>/dev/null || {
                log_warn "  Failed to download package index for $abi, trying..."
                curl -fsSL "${TERMUX_REPO}/dists/stable/main/binary-${deb_arch}/Packages.gz" \
                    -o "${PACKAGES_FILE}.gz" 2>/dev/null && \
                    gunzip -f "${PACKAGES_FILE}.gz" || \
                    { log_error "  Could not download package index for $abi"; continue; }
            }
        fi
        
        # Extract .deb URL
        DEB_URL=$(grep -A 20 "^Package: ${pkg}$" "$PACKAGES_FILE" 2>/dev/null | \
                  grep "^Filename:" | head -1 | awk '{print $2}')
        
        if [ -z "$DEB_URL" ]; then
            log_warn "  Package $pkg not found for $abi"
            continue
        fi
        
        FULL_URL="${TERMUX_REPO}/${DEB_URL}"
        DEB_FILE="$TMPDIR/${pkg}_${abi}.deb"
        
        log_info "  Downloading: $pkg from $FULL_URL"
        curl -fsSL "$FULL_URL" -o "$DEB_FILE" || {
            log_error "  Failed to download $pkg for $abi"
            continue
        }
        
        # Extract .deb (it's an ar archive)
        DEB_EXTRACT="$TMPDIR/extract_${abi}_${pkg}"
        mkdir -p "$DEB_EXTRACT"
        
        # Extract data.tar.xz from .deb
        ar x "$DEB_FILE" --output="$DEB_EXTRACT" 2>/dev/null || {
            # Fallback: use tar to extract
            cd "$DEB_EXTRACT"
            ar x "$DEB_FILE" 2>/dev/null
        }
        
        # Find and extract the data archive
        DATA_TAR=$(ls "$DEB_EXTRACT"/data.tar.* 2>/dev/null | head -1)
        if [ -z "$DATA_TAR" ]; then
            log_warn "  No data archive found in $pkg for $abi"
            continue
        fi
        
        DATA_EXTRACT="$TMPDIR/data_${abi}_${pkg}"
        mkdir -p "$DATA_EXTRACT"
        
        case "$DATA_TAR" in
            *.xz) tar -xJf "$DATA_TAR" -C "$DATA_EXTRACT" 2>/dev/null || \
                   xz -dc "$DATA_TAR" | tar -xf - -C "$DATA_EXTRACT" ;;
            *.gz) tar -xzf "$DATA_TAR" -C "$DATA_EXTRACT" ;;
            *.bz2) tar -xjf "$DATA_TAR" -C "$DATA_EXTRACT" ;;
            *) tar -xf "$DATA_TAR" -C "$DATA_EXTRACT" ;;
        esac
        
        # Map files to jniLibs names
        case "$pkg" in
            proot)
                # Find proot binary and loader
                PROOT_BIN=$(find "$DATA_EXTRACT" -name "proot" -type f 2>/dev/null | head -1)
                LOADER=$(find "$DATA_EXTRACT" -name "loader" -type f 2>/dev/null | head -1)
                LOADER32=$(find "$DATA_EXTRACT" -name "loader32" -type f 2>/dev/null | head -1)
                
                [ -n "$PROOT_BIN" ] && cp "$PROOT_BIN" "$abi_dir/libproot.so" && \
                    log_info "  Copied proot -> $abi_dir/libproot.so"
                [ -n "$LOADER" ] && cp "$LOADER" "$abi_dir/libprootloader.so" && \
                    log_info "  Copied loader -> $abi_dir/libprootloader.so"
                [ -n "$LOADER32" ] && cp "$LOADER32" "$abi_dir/libprootloader32.so" && \
                    log_info "  Copied loader32 -> $abi_dir/libprootloader32.so"
                ;;
            libtalloc)
                # Find libtalloc.so.2
                TALLOC=$(find "$DATA_EXTRACT" -name "libtalloc.so*" -type f 2>/dev/null | head -1)
                [ -n "$TALLOC" ] && cp "$TALLOC" "$abi_dir/libtalloc.so" && \
                    log_info "  Copied libtalloc -> $abi_dir/libtalloc.so"
                ;;
        esac
    done
    
    # Set execute permissions
    chmod +x "$abi_dir"/*.so 2>/dev/null || true
    
    log_info "  $abi: $(ls -la "$abi_dir"/*.so 2>/dev/null | wc -l) libraries"
done

log_info ""
log_info "Done! Proot binaries are ready in $JNI_DIR"
log_info "You can now build the APK with ./build_apk.sh"
