# JetBrains Marketplace 发布流程归档

- 归档时间：2026-08-27
- 插件：RestAutoLab（`com.banqc.restautolab`）
- 首次发布记录：v1.0.0（2026-08-25 上线）→ v1.0.1（2026-08-27）
- 商城地址：`https://plugins.jetbrains.com/plugin/<numericId>-restautolab`
- 配套文档：`BUILD_COMMANDS.md`（构建命令）、`CHANGELOG.md`（版本记录）

## 一、发布前提

1. **插件已在 Marketplace 注册**：同一 `plugin id`（`com.banqc.restautolab`）只需创建一次。验证是否已收录：

   ```bash
   curl -s "https://plugins.jetbrains.com/plugins/list?pluginId=com.banqc.restautolab" | head -c 500
   ```

   返回包含 `<name>RestAutoLab</name>` 即已注册。
2. **发布 Token（Permanent Token）**：JetBrains 账户 → Account Settings → Marketplace → API Tokens 生成。Token 只允许三种注入方式，**严禁写入仓库任何文件**：
   - 命令行参数：`-PideaPublishToken=<token>`
   - 环境变量：`IDEA_PUBLISH_TOKEN=<token>`
   - CI Secret
3. **版本号必须大于线上版本**：Marketplace 拒绝重复版本。查询线上当前版本：

   ```bash
   curl -s "https://plugins.jetbrains.com/plugins/list?pluginId=com.banqc.restautolab" \
     | grep -o '<version>[^<]*</version>'
   ```

## 二、版本号三处同步（缺一不可）

| 位置 | 文件 | 说明 |
|---|---|---|
| Gradle 版本 | `gradle.properties` | `version=x.y.z`，驱动 `project.version` |
| 插件清单 | `src/main/resources/META-INF/plugin.xml` | `<version>` 与 gradle 保持一致 |
| 变更日志 | `CHANGELOG.md` + plugin.xml `<change-notes>` | 追加新版本段落；change-notes 新段落放最上面（用户在插件管理器优先看到最新版） |

`build.gradle.kts` 中 `pluginConfiguration.version` 已从 `project.version` 自动取值，无需手改。

## 三、发布步骤（完整流程）

```bash
# 1. 跑测试与项目配置校验
./gradlew test verifyPluginProjectConfiguration

# 2. 打包（产物 build/distributions/RestAutoLab-<version>.zip）
./gradlew buildPlugin

# 3. 抽查产物内 plugin.xml 版本与兼容区间
unzip -p build/distributions/RestAutoLab-*.zip restautolab/lib/*.jar > /tmp/x.jar 2>/dev/null || true
unzip -l build/distributions/RestAutoLab-*.zip

# 4. 发布到 Marketplace（token 通过命令行参数注入，不进仓库、不进 shell 历史可临时注入）
./gradlew publishPlugin -PideaPublishToken=<token>
# 或
IDEA_PUBLISH_TOKEN=<token> ./gradlew publishPlugin

# 5. 发布后验证（uploads 接口查询已上传的版本）
curl -s -H "Authorization: Bearer <token>" \
  "https://plugins.jetbrains.com/api/plugins/com.banqc.restautolab/updates?size=3"
```

注意事项：

- **不要执行 `verifyPlugin`**：它会解析并下载 11 个 IDE 分发包（2022.3–2026.1 全区间），耗时极长且占磁盘；日常用 `verifyPluginProjectConfiguration` 代替。
- `publishPlugin` 成功后新版本进入 **Awaiting review**（审核）状态，通常 1–2 个工作日；审核通过前线上列表仍显示旧版本，这是正常现象，不是发布失败。
- 审核被拒时 Marketplace 会发审核意见邮件，按意见修改后**同版本号可重新上传**（审核未通过版本不占用版本号）。

## 四、发布后收尾

1. `git add -A && git commit -m "release: vX.Y.Z ..."`，推送 `2022.3.x-2026.1.x` 分支。
2. 打版本标签：`git tag vX.Y.Z && git push origin vX.Y.Z`。
3. 更新 `CHANGELOG.md` 顶部「当前公开版本」标注。
4. `build/` 目录已在 `.gitignore` 中，zip 产物不入库。

## 五、v1.0.1 发布记录（2026-08-27）

- 线上基线：v1.0.0；本次发布 v1.0.1。
- 版本改动：收藏双击刷新/定位保留收藏视图、右键菜单更名排序、环境设置「应用」按钮与保存回显修复、收藏 key 重映射与失效清理。
- 构建配置新增：`build.gradle.kts` 增加 `intellijPlatform.publishing.token`（读取 `-PideaPublishToken` 或 `IDEA_PUBLISH_TOKEN`）。
- 发布命令：`./gradlew publishPlugin -PideaPublishToken=***`（token 未入库）。
