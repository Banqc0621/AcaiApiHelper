# RestAutoLab 全量界面与交互优化 — Runbook

- Task ID: `RAL-UI-OPT-20260814`
- 工作目录: 仓库根目录
- 唯一分支: `2022.3.x-2026.1.x`
- 开发者：Banqc

## 状态检查

```bash
git status --short --branch
git log -8 --oneline --decorate
git diff --check
```

## 自动验收

```bash
# 默认 Java 17 / IDEA 2023.3.6 基线
./gradlew test --rerun-tasks

# 最低兼容基线
./gradlew test verifyPluginProjectConfiguration -PideaVersion=2022.3.3

# 完整插件打包（含真实加载并生成设置搜索索引）
./gradlew buildPlugin

# 产物验签
shasum -a 256 build/distributions/RestAutoLab-1.0.0.zip
unzip -l build/distributions/RestAutoLab-1.0.0.zip
```

## 人工验收清单

1. 启动 `./gradlew runIde`。
2. 打开 RestAutoLab Tool Window，检查窄屏/宽屏、亮色/暗色/高对比度。
3. 左侧扫描；接口右键导入 cURL；“…”打开环境与数据。
4. 批量收藏并导出，再清空测试数据后导入恢复。
5. 配置环境、前置脚本和变量覆盖；发送请求并在运行中停止。
   - 前置脚本仅接受 `set/param/header name=value`；输入未知命令时应在发送前报错。
   - 切换接口或点击停止后，迟到响应不得覆盖当前界面；切换环境后旧全局请求头不得残留。
6. 使用统一 AI/测试入口；检查成功、失败、取消、加载和禁用恢复。
7. 检查请求头右键清除 Cookie。
8. 截取左侧、右侧、设置页和异常/停止状态作为视觉证据。

## 故障判定

- `compileJava` 通过但 `buildPlugin` 失败：不得报完成。
- `ClassNotFoundException` / `IllegalAccessException`：检查 `plugin.xml` 注册类路径及反射可见性。
- 2022.3 注解位置编译失败：避免仅在新版 annotations 支持的 TYPE_USE 写法。
- 并行执行覆盖工作树：停止扩大改动，先固化各自文件并在唯一分支提交，再继续下一节点。
