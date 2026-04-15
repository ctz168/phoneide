#!/bin/bash
# PhoneIDE - 安装脚本 (Termux/Ubuntu)
#
# 用法:
#   bash install.sh                    # 在已克隆的项目目录中安装
#   bash install.sh -r ctz168/phoneide  # 克隆仓库并安装（一键安装）
#   curl -fsSL https://raw.githubusercontent.com/ctz168/phoneide/main/install.sh | bash -s -- -r ctz168/phoneide

set -e

REPO=""
CLONE_DIR=""

# 解析参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        -r|--repo)
            REPO="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

echo "================================"
echo "  PhoneIDE 安装程序"
echo "  移动端 Web IDE"
echo "================================"

# 如果指定了仓库，先克隆
if [ -n "$REPO" ]; then
    CLONE_DIR="$(basename "$REPO")"
    if [ "$CLONE_DIR" = "$REPO" ] || [ -z "$CLONE_DIR" ]; then
        CLONE_DIR="phoneide"
    fi

    echo ""
    echo "[STEP 0/4] 克隆仓库 $REPO ..."

    if [ -d "$CLONE_DIR" ]; then
        echo "[INFO] 目录 $CLONE_DIR 已存在，尝试更新..."
        cd "$CLONE_DIR"
        git pull 2>/dev/null || echo "[WARN] git pull 失败，使用现有文件"
        cd ..
    else
        git clone "https://github.com/$REPO.git" "$CLONE_DIR" || {
            echo "[ERROR] 克隆仓库失败，请检查网络连接或仓库地址"
            exit 1
        }
    fi

    echo "[OK] 仓库就绪: $CLONE_DIR"
    cd "$CLONE_DIR"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 检测运行环境
if command -v pkg &> /dev/null; then
    echo "[INFO] 检测到 Termux 环境"
    PKG_MANAGER="pkg"
elif command -v apt-get &> /dev/null; then
    echo "[INFO] 检测到 Ubuntu/Debian 环境"
    PKG_MANAGER="apt-get"
elif command -v dnf &> /dev/null; then
    echo "[INFO] 检测到 Fedora 环境"
    PKG_MANAGER="dnf"
else
    echo "[WARN] 未识别的包管理器，跳过系统包安装"
    PKG_MANAGER=""
fi

# 安装系统包
install_pkg() {
    if [ -n "$PKG_MANAGER" ]; then
        echo "[INFO] 正在安装 $1..."
        if [ "$PKG_MANAGER" = "pkg" ]; then
            pkg install -y "$1" 2>/dev/null || echo "[WARN] 安装 $1 失败"
        elif [ "$PKG_MANAGER" = "apt-get" ]; then
            sudo apt-get install -y "$1" 2>/dev/null || echo "[WARN] 安装 $1 失败"
        elif [ "$PKG_MANAGER" = "dnf" ]; then
            sudo dnf install -y "$1" 2>/dev/null || echo "[WARN] 安装 $1 失败"
        fi
    fi
}

# 安装 Python 和 pip
echo ""
echo "[STEP 1/4] 安装 Python..."
install_pkg python
install_pkg python3

if command -v python3 &> /dev/null; then
    PYTHON="python3"
elif command -v python &> /dev/null; then
    PYTHON="python"
else
    echo "[ERROR] 未找到 Python！请先安装 Python 3.8+"
    exit 1
fi

PYTHON_VERSION=$($PYTHON --version 2>&1)
echo "[OK] 已找到 $PYTHON_VERSION"

# 安装 pip
echo ""
echo "[STEP 2/4] 安装 pip..."
if ! $PYTHON -m pip --version &> /dev/null; then
    echo "[INFO] 正在安装 pip..."
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

echo "[OK] pip 已就绪"

# 安装 Git
echo ""
echo "[STEP 3/4] 安装 Git..."
install_pkg git
if command -v git &> /dev/null; then
    echo "[OK] Git 已就绪"
else
    echo "[WARN] Git 不可用 - 部分 Git 功能将无法使用"
fi

# 安装 Python 依赖
echo ""
echo "[STEP 4/4] 安装 PhoneIDE Python 依赖..."
$PIP install --upgrade pip 2>/dev/null || true
$PIP install -r "$SCRIPT_DIR/requirements.txt" 2>/dev/null || {
    echo "[备选] 尝试直接安装核心依赖..."
    $PIP install flask flask-cors 2>/dev/null || echo "[WARN] Python 依赖安装失败"
}

# 创建工作空间目录
WORKSPACE="$HOME/phoneide_workspace"
mkdir -p "$WORKSPACE"
echo "[OK] 工作空间: $WORKSPACE"

# 创建配置目录
mkdir -p "$HOME/.phoneide"

echo ""
echo "================================"
echo "  安装完成!"
echo "================================"
echo ""
echo "  启动 PhoneIDE:"
echo "    cd $SCRIPT_DIR"
echo "    python3 server.py"
echo ""
echo "  或使用启动脚本:"
echo "    bash $SCRIPT_DIR/start.sh"
echo ""
echo "  在浏览器中打开:"
echo "    http://localhost:1239"
echo ""
