# 构建与运行命令汇总

> 开发者：Banqc
> 插件：RestAutoLab (`com.banqc.restautolab`) · 当前公开版本：v1.0.0

本文档汇总了 RestAutoLab 插件的所有常用构建、运行、发布命令。
项目基于 Gradle (Kotlin DSL) + IntelliJ Platform Plugin Gradle 插件构建。

## 📋 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Gradle | 使用项目自带 `./gradlew`（无需全局安装） |
| IntelliJ Platform | 2022.3 ~ 2026.1.* |
| 插件版本 | 1.0.0 |

---

## 🔨 构建相关

### 1. 编译 Java 源码

```bash
./gradlew compileJava
```

仅编译 `src/main/java`，不打包。用于快速验证语法/类型。

### 2. 打包插件（生成可分发的 zip）

```bash
./gradlew buildPlugin
```

产物位于：

```
build/distributions/RestAutoLab-1.0.0.zip
```

这个 zip 可以直接通过 IDEA 的 `Settings → Plugins → ⚙️ → Install Plugin from Disk...` 安装。

### 3. 完整 build（含校验、测试）

```bash
./gradlew build
```

会执行 `check`（含 `test`）+ `buildPlugin`，输出最终 zip。

### 4. 仅打包不跑测试

```bash
./gradlew buildPlugin -x test
```

### 5. 清理构建产物

```bash
./gradlew clean
```

清理 `build/` 目录。

---

## 🚀 运行与调试

### 1. 启动一个带插件的沙箱 IDEA 实例（开发调试用）

```bash
./gradlew runIde
```

会启动一个新的 IDEA 实例，自动加载本插件。**这是日常开发最常用的命令**。

### 2. 启动并指定 IDEA 版本（覆盖默认 2023.3.6）

```bash
./gradlew runIde -PideaVersion=2024.1
```

### 3. 启动并保留沙箱目录（保留插件配置/缓存）

```bash
./gradlew runIde --rerun-tasks=false
```

默认每次会重建沙箱；要持久化可在 `build.gradle.kts` 配置 `sandboxDir`。

### 4. 调试模式（远程调试）

在 IDEA 中直接运行 `Gradle → runIde` 任务并点击 Debug 按钮，即可断点调试插件代码。

---

## 🧪 测试

### 1. 运行单元测试

```bash
./gradlew test
```

### 1.1 验证最低兼容版本（IDEA 2022.3）

```bash
./gradlew test verifyPluginProjectConfiguration -PideaVersion=2022.3.3
```

该命令使用 Java 17 和 IDEA 2022.3.3 API 编译、测试源码，用于拦截误用高版本 API。
新版 IntelliJ Platform Gradle 插件不再支持为 2022.3 生成设置搜索索引，因此完整
`buildPlugin` 默认在仍受支持的 IDEA 2023.3.6 基线上执行。

### 2. 运行指定测试类

```bash
./gradlew test --tests "com.hronline.SomeTest"
```

### 3. 运行并输出详细日志

```bash
./gradlew test --info
```

---

## 📦 验证与发布

### 1. 验证插件兼容性

```bash
./gradlew verifyPlugin
```

### 2. 验证插件与目标 IDEA 版本兼容

```bash
./gradlew verifyPluginProjectConfiguration
```

### 3. 发布到 JetBrains Marketplace（需要 token）

```bash
./gradlew publishPlugin -PideaPublishToken=YOUR_TOKEN
# 或通过环境变量注入
IDEA_PUBLISH_TOKEN=YOUR_TOKEN ./gradlew publishPlugin
```

完整发布流程（版本号三处同步、发布前后验证、审核状态说明）见知识库文档：
[.kb/memory/marketplace-publish.md](.kb/memory/marketplace-publish.md)。

> Token 只通过命令行参数、环境变量或 CI Secret 注入，禁止写入仓库。发布前用
> `curl -s "https://plugins.jetbrains.com/plugins/list?pluginId=com.banqc.restautolab"`
> 确认插件已注册且版本号大于线上版本。

---

## 📤 推送到远程仓库（完整流程）

> 项目主分支：`2022.3.x-2026.1.x`
> 公开远程地址由发布者在本地 Git remote 配置中提供；本文不绑定内部仓库。

### 一次完整的「打包 + 提交 + 推送」流程：

```bash
# 1. 打包并验证可编译
./gradlew buildPlugin

# 2. 查看产物
ls -lah build/distributions/RestAutoLab-1.0.0.zip

# 3. 查看待提交内容
git status

# 4. 暂存所有改动
git add -A

# 5. 提交（按需修改 message）
git commit -m "fix(模块): 简要描述"

# 6. 推送当前分支
git push origin 2022.3.x-2026.1.x
```

### 仅推送代码（不打包）

```bash
git add -A
git commit -m "feat/fix(模块): 描述"
git push origin 2022.3.x-2026.1.x
```

### 拉取远程最新

```bash
git pull origin 2022.3.x-2026.1.x
```

---

## 🛠 常用辅助命令

### 1. 查看所有可执行的 Gradle 任务

```bash
./gradlew tasks --all
```

### 2. 查看依赖树

```bash
./gradlew dependencies
```

### 3. 刷新 Gradle 配置缓存

```bash
./gradlew --refresh-dependencies
```

### 4. 查看当前 git 分支与远程

```bash
git branch --show-current
git remote -v
git log -n 5 --oneline
```

---

## ⚠️ 常见问题

### Q: `buildPlugin` 报 `Java 17 required`？
A: 检查 `JAVA_HOME` 指向 JDK 17+。可用 `java -version` 验证。

### Q: `runIde` 启动报找不到 SDK？
A: 让 Gradle 自己下载 IDEA SDK：`./gradlew --refresh-dependencies runIde`。

### Q: 打包出的 zip 体积过大？
A: 默认会打包 `build/distributions/` 下的所有依赖，确认 `build.gradle.kts` 没有冗余 `implementation`。

### Q: 推送被拒（non-fast-forward）？
A: 先 `git pull --rebase origin 2022.3.x-2026.1.x` 再推送。
