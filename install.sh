#!/bin/bash
# PhoneIDE - Installation Script for Termux/Ubuntu
# Usage: bash install.sh

set -e

echo "================================"
echo "  PhoneIDE Installer"
echo "  Mobile Web IDE for Termux"
echo "================================"

# Detect environment
if command -v pkg &> /dev/null; then
    echo "[INFO] Detected Termux environment"
    PKG_MANAGER="pkg"
elif command -v apt-get &> /dev/null; then
    echo "[INFO] Detected Ubuntu/Debian environment"
    PKG_MANAGER="apt-get"
elif command -v dnf &> /dev/null; then
    echo "[INFO] Detected Fedora environment"
    PKG_MANAGER="dnf"
else
    echo "[WARN] Unknown package manager, skipping system packages"
    PKG_MANAGER=""
fi

# Install system packages
install_pkg() {
    if [ -n "$PKG_MANAGER" ]; then
        echo "[INFO] Installing $1..."
        if [ "$PKG_MANAGER" = "pkg" ]; then
            pkg install -y "$1" 2>/dev/null || echo "[WARN] Failed to install $1"
        elif [ "$PKG_MANAGER" = "apt-get" ]; then
            sudo apt-get install -y "$1" 2>/dev/null || echo "[WARN] Failed to install $1"
        elif [ "$PKG_MANAGER" = "dnf" ]; then
            sudo dnf install -y "$1" 2>/dev/null || echo "[WARN] Failed to install $1"
        fi
    fi
}

# Install Python and pip
echo ""
echo "[STEP 1/4] Installing Python..."
install_pkg python
install_pkg python3

# Check Python version
if command -v python3 &> /dev/null; then
    PYTHON="python3"
elif command -v python &> /dev/null; then
    PYTHON="python"
else
    echo "[ERROR] Python not found! Please install Python first."
    exit 1
fi

PYTHON_VERSION=$($PYTHON --version 2>&1)
echo "[OK] Found $PYTHON_VERSION"

# Install pip if needed
echo ""
echo "[STEP 2/4] Installing pip..."
if ! $PYTHON -m pip --version &> /dev/null; then
    echo "[INFO] Installing pip..."
    if [ "$PKG_MANAGER" = "pkg" ]; then
        pkg install -y python-pip 2>/dev/null || true
    else
        curl -sS https://bootstrap.pypa.io/get-pip.py | $PYTHON
    fi
fi

if $PYTHON -m pip --version &> /dev/null; then
    PIP="$PYTHON -m pip"
else
    PIP="pip"
fi

echo "[OK] pip installed"

# Install Git
echo ""
echo "[STEP 3/4] Installing Git..."
install_pkg git
if command -v git &> /dev/null; then
    echo "[OK] Git installed"
else
    echo "[WARN] Git not available - some features will not work"
fi

# Install Python dependencies
echo ""
echo "[STEP 4/4] Installing PhoneIDE dependencies..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
$PIP install --upgrade pip 2>/dev/null || true
$PIP install -r "$SCRIPT_DIR/requirements.txt" 2>/dev/null || {
    echo "[FALLBACK] Trying direct install..."
    $PIP install flask flask-cors 2>/dev/null || echo "[WARN] Failed to install pip packages"
}

# Create workspace directory
WORKSPACE="$HOME/phoneide_workspace"
mkdir -p "$WORKSPACE"
echo "[OK] Workspace: $WORKSPACE"

# Create config directory
mkdir -p "$HOME/.phoneide"

echo ""
echo "================================"
echo "  Installation Complete!"
echo "================================"
echo ""
echo "  Start PhoneIDE:"
echo "    cd $SCRIPT_DIR"
echo "    python3 server.py"
echo ""
echo "  Or use the start script:"
echo "    bash $SCRIPT_DIR/start.sh"
echo ""
echo "  Open in browser:"
echo "    http://localhost:1239"
echo ""
