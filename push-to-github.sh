#!/usr/bin/env bash
# RestAutoLab v2.0.0 → github 推送脚本
# 沙箱里 github.com:443 不可达，本地一行跑这个即可
# 当前：origin 已推到 codeup aliyun, 这里只补 github

set -euo pipefail

REMOTE=github
BRANCH=2022.3.x-2026.1.x
TAG=v2.0.0

echo "==> 当前 HEAD: $(git rev-parse --short HEAD) ($(git branch --show-current))"
echo "==> 当前 $TAG tag: $(git rev-parse --short $TAG)"
echo ""

echo "==> 1/4 推送 $BRANCH 到 $REMOTE (force 替换旧的 e90e29d)"
git push "$REMOTE" "$BRANCH" --force-with-lease
echo ""

echo "==> 2/4 删除 $REMOTE 上旧分支 feat/optim-round3-global-style"
git push "$REMOTE" --delete feat/optim-round3-global-style || echo "    (旧分支已不存在, 跳过)"
echo ""

echo "==> 3/4 推送 $TAG tag 到 $REMOTE (force 覆盖)"
git push "$REMOTE" "$TAG" --force
echo ""

echo "==> 4/4 验证 $REMOTE 远程状态"
git ls-remote "$REMOTE" | grep -E "$BRANCH|feat/optim|$TAG" || true
echo ""

echo "==> 全部完成 ✓"
echo "    分支: $REMOTE/$BRANCH → $(git rev-parse --short HEAD)"
echo "    tag:   $REMOTE/$TAG   → $(git rev-parse --short $TAG)"
