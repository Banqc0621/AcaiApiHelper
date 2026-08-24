#!/usr/bin/env bash
# RestAutoLab v1.0.0 安全推送脚本。
# 默认只推送发布分支，不强推、不删除远程分支、不移动标签。
# REMOTE/BRANCH 可覆盖；仅显式设置 PUSH_TAG=1 时才推送 TAG。

set -euo pipefail

REMOTE="${REMOTE:-github}"
BRANCH="${BRANCH:-2022.3.x-2026.1.x}"
TAG="${TAG:-v1.0.0}"
PUSH_TAG="${PUSH_TAG:-0}"
EXPECTED_VERSION="1.0.0"

current_branch="$(git branch --show-current)"
current_head="$(git rev-parse HEAD)"
declared_version="$(sed -n 's/^version=//p' gradle.properties)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "错误：工作树不干净。请先检查、提交并完成构建验证。" >&2
  exit 1
fi

if [[ "$current_branch" != "$BRANCH" ]]; then
  echo "错误：当前分支为 $current_branch，预期为 $BRANCH。" >&2
  exit 1
fi

if [[ "$declared_version" != "$EXPECTED_VERSION" ]]; then
  echo "错误：gradle.properties 版本为 $declared_version，预期为 $EXPECTED_VERSION。" >&2
  exit 1
fi

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "错误：远程 $REMOTE 不存在，请先配置公开仓库 remote。" >&2
  exit 1
fi

echo "==> 推送分支 $BRANCH 到 $REMOTE"
git push "$REMOTE" "$BRANCH"

if [[ "$PUSH_TAG" == "1" ]]; then
  if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "错误：本地标签 $TAG 不存在；脚本不会自动创建发布标签。" >&2
    exit 1
  fi

  tagged_head="$(git rev-list -n 1 "$TAG")"
  if [[ "$tagged_head" != "$current_head" ]]; then
    echo "错误：本地 $TAG 指向 $(git rev-parse --short "$tagged_head")，当前 HEAD 为 $(git rev-parse --short "$current_head")。" >&2
    echo "脚本不会移动或覆盖历史标签；请改用新的 TAG 名称。" >&2
    exit 1
  fi

  echo "==> 推送标签 $TAG 到 $REMOTE"
  git push "$REMOTE" "$TAG"
fi

echo "==> 验证远程分支"
git ls-remote "$REMOTE" "refs/heads/$BRANCH"

echo "发布分支已安全推送：$REMOTE/$BRANCH"
