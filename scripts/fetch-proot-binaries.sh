#!/bin/bash
# Fetch pre-compiled PRoot binaries from Termux packages for Android.
# Extracts proot, libtalloc, and loader from Termux .deb packages.
# Places them in jniLibs/<abi>/lib*.so so Android auto-extracts
# them to nativeLibraryDir with execute permission.
# Based on stableclaw_android's fetch-proot-binaries.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNILIBS_DIR="$SCRIPT_DIR/../android/app/src/main/jniLibs"
TMP_DIR=$(mktemp -d)

trap 'rm -rf "$TMP_DIR"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# Fetch a Termux package and extract binaries
fetch_termux_pkg() {
    local pkg_name="$1"
    local deb_arch="$2"
    local extract_dir="$3"

    echo "    Fetching $pkg_name for $deb_arch..."

    local pkg_url
    pkg_url=$(curl -fsSL "${TERMUX_REPO}/dists/stable/main/binary-${deb_arch}/Packages" \
        | grep -A 20 "^Package: ${pkg_name}$" \
        | grep "^Filename:" \
        | head -1 \
        | awk '{print $2}')

    if [ -z "$pkg_url" ]; then
        echo "    WARN: $pkg_name not found in Termux repo for $deb_arch"
        return 1
    fi

    local deb_file="$TMP_DIR/${pkg_name}-${deb_arch}.deb"
    curl -fsSL "${TERMUX_REPO}/${pkg_url}" -o "$deb_file"

    mkdir -p "$extract_dir"
    cd "$extract_dir"
    ar x "$deb_file"
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz
    elif [ -f data.tar.gz ]; then
        tar xf data.tar.gz
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst -o data.tar && tar xf data.tar
    else
        tar xf data.tar.* 2>/dev/null
    fi
    cd "$SCRIPT_DIR"
}

fetch_for_abi() {
    local jni_abi="$1"
    local deb_arch="$2"
    local out_dir="$JNILIBS_DIR/$jni_abi"
    local extract_base="$TMP_DIR/extract-$jni_abi"

    mkdir -p "$out_dir"
    echo "  [$jni_abi]"

    local proot_dir="$extract_base/proot"
    if ! fetch_termux_pkg "proot" "$deb_arch" "$proot_dir"; then
        return 1
    fi

    local talloc_dir="$extract_base/talloc"
    if ! fetch_termux_pkg "libtalloc" "$deb_arch" "$talloc_dir"; then
        return 1
    fi

    # Copy proot binary
    local proot_bin
    proot_bin=$(find "$proot_dir" -name "proot" -path "*/bin/*" -type f | head -1)
    if [ -z "$proot_bin" ]; then
        echo "  [$jni_abi] ERROR: proot binary not found"
        return 1
    fi
    cp "$proot_bin" "$out_dir/libproot.so"
    chmod 755 "$out_dir/libproot.so"

    # Copy loader
    local loader
    loader=$(find "$proot_dir" -name "loader" -not -name "loader32" -path "*/proot/*" -type f | head -1)
    if [ -n "$loader" ]; then
        cp "$loader" "$out_dir/libprootloader.so"
        chmod 755 "$out_dir/libprootloader.so"
    fi

    # Copy loader32
    local loader32
    loader32=$(find "$proot_dir" -name "loader32" -path "*/proot/*" -type f | head -1)
    if [ -n "$loader32" ]; then
        cp "$loader32" "$out_dir/libprootloader32.so"
        chmod 755 "$out_dir/libprootloader32.so"
    fi

    # Copy libtalloc
    local talloc_lib
    talloc_lib=$(find "$talloc_dir" -name "libtalloc.so.*" -not -name "*.py" -type f | head -1)
    if [ -z "$talloc_lib" ]; then
        talloc_lib=$(find "$talloc_dir" -name "libtalloc.so" -type f -o -name "libtalloc.so" -type l | head -1)
    fi
    if [ -n "$talloc_lib" ]; then
        cp -L "$talloc_lib" "$out_dir/libtalloc.so"
        chmod 755 "$out_dir/libtalloc.so"
    else
        echo "  [$jni_abi] WARN: libtalloc not found"
    fi

    echo "  [$jni_abi] OK - $(ls "$out_dir"/ | tr '\n' ' ')"
}

echo "=== Fetching PRoot + libtalloc from Termux packages ==="
echo ""

SUCCESS=0
FAILED=0

for entry in "arm64-v8a:aarch64" "armeabi-v7a:arm"; do
    IFS=':' read -r abi deb_arch <<< "$entry"

    if fetch_for_abi "$abi" "$deb_arch"; then
        SUCCESS=$((SUCCESS + 1))
    else
        echo "  [$abi] FAILED"
        FAILED=$((FAILED + 1))
    fi
    echo ""
done

echo "=== Summary ==="
echo "Success: $SUCCESS / 2"
if [ "$FAILED" -gt 0 ]; then
    echo "Failed: $FAILED"
fi

echo ""
echo "Files:"
ls -la "$JNILIBS_DIR"/*/lib*.so 2>/dev/null || echo "  (none)"
