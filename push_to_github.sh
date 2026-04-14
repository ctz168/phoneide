#!/bin/bash
# 推送 phoneide 到 GitHub 并触发 APK 自动构建
# 用法: ./push_to_github.sh <GITHUB_TOKEN>

set -e

TOKEN="$1"
if [ -z "$TOKEN" ]; then
    echo "用法: $0 <GITHUB_TOKEN>"
    echo "  GITHUB_TOKEN 格式: ghp_xxxx 或 github_pat_xxxx"
    echo "  获取方式: https://github.com/settings/tokens"
    exit 1
fi

REPO="ctz168/phoneide"
REMOTE_URL="https://${TOKEN}@github.com/${REPO}.git"

cd "$(dirname "$0")"

# 设置 remote
if git remote get-url origin &>/dev/null; then
    git remote set-url origin "$REMOTE_URL"
else
    git remote add origin "$REMOTE_URL"
fi

# 推送
git push origin main --force

echo ""
echo "✅ 推送成功！"
echo ""
echo "👉 查看构建状态: https://github.com/${REPO}/actions"
echo ""
echo "💡 手动触发构建:"
echo "   1. 打开 https://github.com/${REPO}/actions/workflows/build-apk.yml"
echo "   2. 点击 'Run workflow'"
echo "   3. 选择 build_type: debug 或 release"
echo "   4. 点击 'Run workflow'"
echo ""
echo "📦 构建完成后，在 Actions 页面下载 APK"
echo ""
echo "🏷️  打 tag 自动发布 Release:"
echo "   git tag v2.0.0"
echo "   git push origin v2.0.0"
