# RestAutoLab v1.0.0 开源发布归档

- 归档日期：2026-08-24
- 当前分支：`2022.3.x-2026.1.x`
- 整理起点：`33d57a1`
- 插件 ID：`com.banqc.restautolab`
- 当前公开版本：`v1.0.0`

## 1. 发布定位

`v1.0.0` 是 RestAutoLab 的开源首发版本。它不是从零开始的最小版本，而是把此前内部开发阶段的功能、UI 优化、扫描修复和稳定性修复统一收敛成一个可分发版本。

仓库历史中曾使用过 `v1.0.2`、`v1.0.3`、`v1.0.4`、`v1.0.5` 和 `v2.0.0` 作为迭代号。这些编号保留在 Git 历史和下文中用于追溯，不再作为本次公开发行序列。已有 Git 标签不移动、不覆盖。

## 2. 当前发布元数据

| 项目 | v1.0.0 公开发布值 |
|---|---|
| Gradle 版本 | `gradle.properties` 中的 `version=1.0.0` |
| IntelliJ 插件版本 | `src/main/resources/META-INF/plugin.xml` 中的 `1.0.0` |
| 构建产物 | `build/distributions/RestAutoLab-1.0.0.zip` |
| Java | 17 |
| IntelliJ IDEA | 2022.3（build 223）至 2026.1.x（build 261） |
| 插件依赖 | `com.intellij.modules.java`、`Git4Idea` |

Gradle 构建与插件打包版本由 `gradle.properties` 统一驱动；仓库中的 `plugin.xml` 也已人工校验为同一版本。

## 3. 功能总览

### API 扫描

- 自动识别 Spring MVC、REST、Feign 和 JAX-RS 控制器及映射注解。
- 支持项目全量、目录范围和单个 Java 源文件范围扫描。
- 支持类级/方法级多路径注解、组合注解、路径常量和占位符解析。
- 包过滤采用段边界匹配，`com.foo` 不会误命中 `com.foobar`。
- 同一 Controller 下相同 HTTP 方法与规范化 URL 只显示一条接口。
- 右键范围无结果时保持真实空态，不回退到旧缓存或全量列表。

### 请求调试与自动化测试

- GET、POST、PUT、DELETE、PATCH 等 HTTP 方法调试。
- 环境、变量覆盖、请求头、Cookie、请求历史和测试 Profile 持久化。
- 自定义状态码、响应时间、Body、JSON 字段和 Header 断言。
- 批量测试、依赖链拓扑执行、取消/失败/跳过状态和 HTML 报告。
- Git 预提交检查，只测试受影响的 Controller 接口。

### AI 与编辑器集成

- 支持自部署模型（兼容 OpenAI 协议）进行 AI 参数与断言生成；未配置 AI 时提供本地启发式参数生成。
- Controller Gutter 导航、右键调试、收藏文件夹和最近变更分类。
- JSON 语法高亮、请求体多行编辑、文件参数选择和主题自适应。

### 数据与文档

- cURL 导入/导出、Postman Collection 导出。
- Markdown、Word 模板和 HTML 测试报告导出。
- 配置数据与接口测试数据导入/导出，并按接口粒度安全合并。
- DTO、泛型响应和嵌套对象在 Markdown/Word 导出中递归展开。

## 4. 近期修复归档（2026-08）

以下修复是本次公开版本相对早期内部构建最重要的稳定性收敛：

1. **范围扫描改为路径真相**：目录使用路径前缀，Java 文件使用精确路径，不再把范围先猜成包名。
2. **空范围语义固定**：无法解析真实目标或目标内没有源文件时清空树，不保留上次扫描结果，也不降级显示全量列表。
3. **重复接口去重**：以 Controller、HTTP 方法和规范化 URL 建立稳定键，修复同一 Controller 下 `GET /wehealth` 重复出现的问题。
4. **异步结果隔离**：扫描请求携带 generation，旧请求完成后不能覆盖用户随后选择的“全量”或另一个右键范围。
5. **IntelliJ 线程边界修复**：PSI/VFS 读取放入后台读线程，文件刷新和 IO 不在 EDT 执行，UI 更新回到 EDT 并检查项目生命周期。
6. **取消与异常收敛**：`ProcessCanceledException` 正常退出；请求异常转换为可展示结果，按钮不会永久停留在 loading/disabled 状态。

对应的设计决策和异常根因保留在：

- [设计优化知识库](../.kb/memory/corrections/design-optimizations.md)
- [异常处理知识库](../.kb/memory/corrections/exception-handling.md)
- [需求与验收](01-requirements.md)
- [设计规格](02-design-spec.md)
- [迭代记录](iterations/)

## 5. 历史版本与 Git 追溯

| 历史编号 | 日期/提交 | 主要内容 | 本次处理 |
|---|---|---|---|
| `v1.0.0` 开发节点 | `dfb0f0e`；现有同名标签指向更早提交 `fa93909` | IDEA 2022.3–2026.1.x 兼容、Markdown 返回实体类导出 | 历史标签已存在；不移动 |
| `v1.0.2` | 2026-07-06 | 状态码、环境变量、持久化、Cookie、断言、cURL、AI 能力 | 作为内部开发记录 |
| `v1.0.3` | `e80a287` | AI 配置与扫描能力增强、路径/参数/环境修复 | 作为内部开发记录 |
| `v1.0.4` | `3e142aa` | 数据管理重构和导入导出优化 | 作为内部开发记录 |
| `v1.0.5` | `77c57ee` | 收藏文件夹、批量操作和异常加固 | 作为内部开发记录 |
| `v2.0.0` | `a5cf695` | UI 体验升级、JSON 高亮、智能编辑器 | 作为内部开发记录 |
| 2026-08 稳定性收敛 | `33d57a1` | 范围扫描、去重、线程安全、空态与异步结果隔离 | 合并进公开 `v1.0.0` |

当前本地和远程均存在旧的 `v1.0.0` 标签（指向早期提交）。本次发布归档保留该历史标签，不移动、不覆盖；代码更新只正常推送当前发布分支。后续若需要创建与当前公开构建对应的新标签，应使用新的标签名并由仓库维护者显式执行。

## 6. 开源发布前检查清单

### 代码与产物

```bash
./gradlew clean test
./gradlew verifyPluginProjectConfiguration -PideaVersion=2022.3.3
./gradlew buildPlugin
git diff --check
unzip -l build/distributions/RestAutoLab-1.0.0.zip
shasum -a 256 build/distributions/RestAutoLab-1.0.0.zip
```

确认：

- zip 内的插件版本为 `1.0.0`，`since-build=223`，`until-build=261.*`。
- 不包含 `.idea`、`build`、Gradle 缓存、用户配置、API Key、Cookie 或测试环境密钥。
- `git status` 只包含预期的归档与版本变更。

### 本次验证结果

- `./gradlew test --rerun-tasks`：通过。
- `./gradlew test verifyPluginProjectConfiguration -PideaVersion=2022.3.3`：通过，59 项测试、0 failure、0 error、0 skipped；Gradle 仅提示 2022.3 已不再由新版插件维护。
- `./gradlew buildPlugin`：通过；生成 searchable options 并完成插件打包。
- 产物：`RestAutoLab-1.0.0.zip`，822395 bytes。
- SHA-256：`2ee28969eaf1dbc7a351e0662dc01fa5b062763ab57d5dfb62117ab48e68ff9d`。
- 产物内 `META-INF/plugin.xml`：`version=1.0.0`、`since-build=223`、`until-build=261.*`。

### 仓库与发布页

- 在 GitHub/GitLab 创建公开仓库并检查 remote，不要把企业内部仓库地址写入文档或脚本。
- 根据维护者选择补充开源许可证（当前仓库未替维护者擅自选择许可证）。
- 发布 Release 时上传 `RestAutoLab-1.0.0.zip` 和 SHA-256。
- 在 Release 说明中链接本文件、`CHANGELOG.md`、兼容版本和离线安装说明。
- 若发布到 JetBrains Marketplace，使用 CI Secret 提供 `ideaPublishToken`，不要把 token 写入命令历史或仓库。

## 7. 文档分工

- `README.md`：面向使用者的安装、功能和兼容性入口。
- `CHANGELOG.md`：公开 `v1.0.0` 变更摘要及内部迭代追溯。
- `BUILD_COMMANDS.md`：构建、测试、打包和发布命令。
- `OFFLINE_DEPLOYMENT.md`：无互联网环境安装/迁移。
- `docs/03-runbook.md`：验收操作手册。
- `docs/04-delivery.md`：历史节点交付证据，旧版本号仅用于证据追溯。
- `.kb/memory/corrections/`：设计决策和异常根因知识库，不删除历史上下文。

## 8. 归档结论

源代码、插件元数据、Gradle 版本和面向用户的文档已统一到公开首发 `v1.0.0`；历史修改 Markdown、设计要求、异常处理和发布证据均有明确归属。构建与最低兼容版本校验已经完成；旧 `v1.0.0` 标签按历史引用保留。正式创建 Release 前仍需由维护者选择开源许可证并上传已校验产物。
