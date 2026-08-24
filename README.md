# RestAutoLab — IntelliJ IDEA API 调试与测试插件

> 插件 ID：`com.banqc.restautolab` · 当前公开版本：`v1.0.0`

RestAutoLab 面向 Java 服务开发与测试场景，在 IntelliJ IDEA 内完成 API 扫描、请求调试、自动化测试、AI 参数生成和文档导出。

这是项目的开源首发版本。仓库历史中曾使用 `v1.0.2`–`v2.0.0` 作为内部开发迭代号；这些能力已整合进本版，历史修改、设计决策和验收证据统一归档在 [v1.0.0 发布归档](docs/RELEASE_ARCHIVE_v1.0.0.md)。

文档入口：[更新日志](CHANGELOG.md) · [发布归档](docs/RELEASE_ARCHIVE_v1.0.0.md) · [构建命令](BUILD_COMMANDS.md) · [离线部署](OFFLINE_DEPLOYMENT.md)

## 功能概览

- **API 扫描**：识别 Spring MVC、REST、Feign、JAX-RS 控制器；支持项目、目录和单个 Java 文件范围扫描、包过滤及稳定去重。
- **接口调试**：支持 GET/POST/PUT/DELETE/PATCH，请求参数、请求头、请求体、环境变量、Cookie 和请求历史均可管理。
- **自动化测试**：自定义状态码/响应时间/Body/JSON/Header 断言，批量测试、依赖链执行、取消与 HTML 报告。
- **AI 助手**：支持自部署模型（兼容 OpenAI 协议）生成参数和断言；未配置模型时提供本地启发式生成。
- **编辑器集成**：Controller Gutter 导航、右键调试、收藏文件夹、最近变更分类和 JSON 语法高亮。
- **导出与迁移**：支持 cURL、Postman、Markdown、Word 模板、配置数据和接口测试数据导入导出。

## 安装

1. 从 Release 下载 `RestAutoLab-1.0.0.zip`。
2. 在 IDEA 打开 `Settings → Plugins → ⚙️ → Install Plugin from Disk...`。
3. 选择 zip 文件并重启 IDEA。
4. 打开右侧 `RestAutoLab` Tool Window，点击「扫描 API」。

插件只针对 Java 项目，依赖 IntelliJ IDEA 的 Java 插件和 Git4Idea。

## 兼容版本

| 项目 | 支持范围 |
|---|---|
| IntelliJ IDEA | 2022.3（build 223）至 2026.1.x（build 261） |
| Java | 17 或更高版本 |
| 项目语言 | Java（Spring MVC/REST/Feign/JAX-RS 等） |

2022.2 及更早版本、2026.2 及更高版本不在本版声明范围内。兼容约束由 `plugin.xml` 的 `since-build="223"` / `until-build="261.*"` 表达。

## 快速开始

1. 打开 Java 项目并启动 `RestAutoLab` Tool Window。
2. 点击「扫描 API」生成接口树；也可以在项目视图中右键目录或 Java 文件，选择只扫描该范围。
3. 选择接口，在右侧填写环境、参数、请求头和请求体。
4. 点击「发起请求」查看响应，或在「断言」Tab 添加自动化校验。
5. 使用「环境 & 数据」管理环境、测试 Profile、收藏和导入导出数据。

### 环境变量示例

在环境中定义 `token` 后，可在 URL、Header 或请求体中使用：

```text
Authorization: Bearer {{token}}
```

### AI 配置

在 `Settings → Tools → RestAutoLab` 配置自部署模型网关地址、模型、API 路径和 Token。网关兼容 OpenAI Chat Completions 协议即可；Token 只保存在本地 IDE 配置中，不要提交到仓库。

## 构建与验证

```bash
./gradlew clean test
./gradlew verifyPluginProjectConfiguration -PideaVersion=2022.3.3
./gradlew buildPlugin
```

产物位于 `build/distributions/RestAutoLab-1.0.0.zip`。更多命令见 [BUILD_COMMANDS.md](BUILD_COMMANDS.md)，无互联网环境见 [OFFLINE_DEPLOYMENT.md](OFFLINE_DEPLOYMENT.md)。

## 数据位置

- 项目配置：`.idea/restautolab.xml`
- 测试报告：`.restautolab/reports/`
- 导出文档：`.restautolab/api-doc.md`

## 开源发布说明

- 本仓库当前未替维护者选择许可证，请在公开发布前补充合适的 `LICENSE`。
- 不要提交 API Key、Cookie、环境密钥、IDE 配置或构建缓存。
- 现有 Git 历史标签不自动改写；`v1.0.0` 标签冲突及公开 Release 标签策略见 [发布归档](docs/RELEASE_ARCHIVE_v1.0.0.md)。

## 许可证

待维护者确认后补充。当前仓库代码不应被默认视为已授予任意开源许可证。
