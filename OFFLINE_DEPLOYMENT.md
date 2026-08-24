# RestAutoLab 离线环境部署操作说明

> 开发者：Banqc
> 插件：RestAutoLab (`com.banqc.restautolab`) · 当前公开版本：v1.0.0

本文档指导将 RestAutoLab（`com.banqc.restautolab`，IntelliJ IDEA 插件）迁移到**无互联网**的离线环境正常运行。
适用对象：构建/运维人员。
项目构建方式：Gradle 9.5.0 Wrapper + IntelliJ Platform Gradle Plugin 2.17.0，默认构建基线为 IntelliJ IDEA 2023.3.6；最低兼容检查使用 2022.3.3。

---

## 〇、术语与物料清单

| 物料 | 说明 | 来源 |
|------|------|------|
| 插件 zip | `build/distributions/*.zip`，最终安装到 IDE 的产物 | 在线机构建产出 |
| IntelliJ IDEA 安装包 | 离线机的运行载体，版本须在 **2022.3 ~ 2026.1.x** | JetBrains 官网离线安装包 |
| JDK 17+ | IDE 与 Gradle 运行时（推荐 JDK 17 或 21） | Oracle / Adoptium |
| Gradle 离线缓存（可选） | `~/.gradle/caches`，仅方案B需要 | 在线机拷贝 |
| Gradle 发行包（可选） | `gradle-9.5.0-bin.zip`，仅方案B需要 | services.gradle.org |

---

## 一、方案选择

| 方案 | 适用场景 | 物料体积 | 复杂度 |
|------|----------|----------|--------|
| **方案A（推荐）**：在线预构建 + 离线安装运行 | 仅需在离线机**使用**插件 | 约 1～2 GB（IDE 安装包 + 插件 zip） | 低 |
| **方案B**：完整离线构建环境 | 需在离线机**重新构建/二次开发** | 约 7～8 GB（含 Gradle 缓存 6G + JDK + 源码） | 中 |

> 绝大多数「迁移到离线环境运行」需求用 **方案A** 即可。下方先讲方案A，再附方案B。

---

## 二、方案A：在线预构建 + 离线安装运行（推荐）

### A.1 在线机：构建插件 zip

在能联网的开发机上，于项目根目录执行：

```bash
cd /path/to/RestAutoLab

# 清理并构建（会自动下载 IntelliJ Platform SDK 2023.3.6 与全部依赖）
./gradlew clean buildPlugin

# 跳过测试快速构建（如急需）
./gradlew clean buildPlugin -x test
```

构建成功后，产物位于：

```
build/distributions/RestAutoLab-<版本号>.zip      # 例如 RestAutoLab-1.0.0.zip
```

> 校验：zip 内应包含 `lib/*.jar` 与 `META-INF/plugin.xml`，`plugin.xml` 中 `idea-version since-build="223" until-build="261.*"`。

### A.2 在线机：下载 IDE 离线安装包

插件声明兼容 **IDEA 2022.3 ~ 2026.1.x**。推荐下载与默认构建目标一致的 **IntelliJ IDEA 2023.3.6**；最低版本另用 `-PideaVersion=2022.3.3` 编译测试。插件依赖 `com.intellij.java` 与 `Git4Idea` 两个 bundled 插件。

- 官网离线安装包下载页：https://www.jetbrains.com/idea/download/other.html
- 选择 `2023.3.6` 对应平台的 `.dmg`（macOS）/ `.exe` 或 `.zip`（Windows）/ `.tar.gz`（Linux）

> 若离线机已有符合版本范围的 IDEA，可跳过本步。

### A.3 在线机：下载 JDK（若离线机未安装）

IDEA 2022.3+ 需要 JDK 17+ 运行时。下载离线安装包：

- Adoptium Temurin 17：https://adoptium.net/temurin/releases/?version=17
- 选择对应平台的 `.pkg` / `.msi` / `.tar.gz`

### A.4 传输到离线机

将以下文件拷贝到离线机（U盘 / 内网传输）：

```
物料清单：
├── RestAutoLab-<版本号>.zip              # A.1 产出的插件包
├── ideaIU-2023.3.6.dmg           # A.2 IDE 安装包（按平台替换后缀）
└── Temurin17.jdk.pkg             # A.3 JDK 安装包（离线机已装有则省略）
```

### A.5 离线机：安装 JDK 与 IDE

1. **安装 JDK 17+**：双击安装包，确认 `java -version` 可用。
2. **安装 IntelliJ IDEA**：双击安装包完成安装。
   - macOS：拖入 Applications
   - Windows：按向导安装
   - Linux：解压到 `/opt/idea`
3. 首次启动 IDEA，**不**勾选任何「下载/更新」联网选项，跳过插件市场。

### A.6 离线机：安装插件

1. 启动 IDEA → 菜单 `File → Settings`（macOS 为 `IntelliJ IDEA → Settings`）。
2. 左侧选 `Plugins`。
3. 点击右侧齿轮图标 ⚙️ → `Install Plugin from Disk...`。
4. 选择传输进来的 `RestAutoLab-<版本号>.zip`。
5. 点击 OK → `Restart IDE` 重启。

### A.7 离线机：验证运行

重启后，验证以下功能点均正常（这些功能完全本地化，无需联网）：

| 验证项 | 操作 | 预期 |
|--------|------|------|
| 工具窗 | 右侧边栏出现 `RestAutoLab` 工具窗 | 可见且可点击 |
| 扫描 API | 在 Spring MVC 项目中右键 → `RestAutoLab → 扫描项目API` | 树形展示接口 |
| Gutter 图标 | Controller 方法行号旁出现 API 图标 | 可点击调试 |
| HTTP 调试 | 选中接口点「发送」 | 正常发出请求并返回（**目标服务器需在内网可达**） |
| Git 预提交 | 提交代码触发检查 | 正常拦截/放行 |
| 测试用例 | 编辑/保存/批量执行 | 正常持久化到 `restautolab.xml` |

> **通过即视为离线运行正常。**

---

## 三、方案B：完整离线构建环境（需在离线机重新构建）

适用于离线机需要自行修改源码、重新打包的场景。

> ✅ **本机已按下方步骤打包完成**，物料位于 `~/offline-bundle/`：
>
> | 文件 | 大小 | 说明 |
> |------|------|------|
> | `gradle-caches-offline.zip` | 3.6G | Gradle 缓存（caches/9.5.0 + modules-2 + wrapper/dists，已排除 `*.lock`） |
> | `gradle-caches-offline.zip.part-aa~ae` | 732M×5 | 上者的 5 等分分片（针对 FAT32 U 盘） |
> | `RestAutoLab-src.zip` | 2.2M | 项目源码（已排除 build/.gradle/.intellijPlatform/.idea/.DS_Store） |
> | `RestAutoLab-1.0.0.zip` | 717K | 顺带产出的插件包（可直接用于方案A安装） |
>
> 已用 `./gradlew clean buildPlugin --offline` 验证缓存完整可离线构建。
> **离线机为 Windows**，所有命令已按 Windows 视角给出。
> 下方步骤供需要在其他在线机重新打包时参考。

### B.1 在线机：准备离线物料

在能联网的开发机上执行以下打包：

```bash
# 1. 先正常构建一次，确保缓存完整
cd /path/to/RestAutoLab
./gradlew clean buildPlugin

# 2. 打包 Gradle 依赖缓存（含 IntelliJ Platform SDK 2023.3.6、Gson、JUnit 等）
#    排除 *.lock 避免离线机解压后锁文件残留；wrapper/dists 已含 gradle-9.5.0 发行包
#    用 zip 格式，Windows 自带支持；-rq 静默递归
cd ~
mkdir -p ~/offline-bundle
zip -rq ~/offline-bundle/gradle-caches-offline.zip .gradle/caches .gradle/wrapper -x '*.lock'

# 3. 打包项目源码（排除构建产物与 IDE 本地缓存）
cd /path/to
zip -rq ~/offline-bundle/RestAutoLab-src.zip RestAutoLab \
    -x 'RestAutoLab/build/*' 'RestAutoLab/.gradle/*' \
    'RestAutoLab/.intellijPlatform/*' 'RestAutoLab/.idea/*' '*/.DS_Store'

# 4. 顺带把已构建好的插件 zip 也放进去（离线机若不想重新构建可直接用方案A安装）
cp /path/to/RestAutoLab/build/distributions/*.zip ~/offline-bundle/
```

物料清单（本机实测体积）：

```
~/offline-bundle/
├── gradle-caches-offline.zip   # 3.6G（3837914036 字节）
├── RestAutoLab-src.zip        # 2.2M
└── RestAutoLab-1.0.0.zip    # 717K（插件产物，文件名来自 build.gradle.kts 的 archiveBaseName.set("RestAutoLab")）
```

> 另需单独下载 JDK 17 离线安装包（如 `Temurin17.msi`）一并传输。
> 总体积约 3.6G，建议用移动硬盘或内网传输。

#### 🔀 分片传输（针对 FAT32 U 盘 4G 单文件限制）

若传输介质有单文件大小限制（如 FAT32/exFAT 单文件 4G），可将 `gradle-caches-offline.zip` 分成 5 片传输，离线机合并后再解压。

在线机分片（已执行，产物在 `~/offline-bundle/`）：

```bash
cd ~/offline-bundle
# split -n 5 平均分 5 份；每份约 732M
split -n 5 gradle-caches-offline.zip gradle-caches-offline.zip.part-
ls -lh gradle-caches-offline.zip.part-*
# gradle-caches-offline.zip.part-aa  732M
# gradle-caches-offline.zip.part-ab  732M
# gradle-caches-offline.zip.part-ac  732M
# gradle-caches-offline.zip.part-ad  732M
# gradle-caches-offline.zip.part-ae  732M
# 原始 gradle-caches-offline.zip 可保留或删除（保留便于校验）
```

分片后物料清单：

```
~/offline-bundle/
├── gradle-caches-offline.zip.part-aa   # 732M
├── gradle-caches-offline.zip.part-ab   # 732M
├── gradle-caches-offline.zip.part-ac   # 732M
├── gradle-caches-offline.zip.part-ad   # 732M
├── gradle-caches-offline.zip.part-ae   # 732M
├── RestAutoLab-src.zip                # 2.2M
└── RestAutoLab-1.0.0.zip            # 717K
```

离线机（Windows）合并 —— 使用 `copy /b` 二进制拼接（**顺序必须一致**：aa → ab → ac → ad → ae）：

```cmd
cd D:\offline-bundle
copy /b gradle-caches-offline.zip.part-aa + gradle-caches-offline.zip.part-ab + gradle-caches-offline.zip.part-ac + gradle-caches-offline.zip.part-ad + gradle-caches-offline.zip.part-ae gradle-caches-offline.zip

REM 校验合并后大小（应为 3837914036 字节，约 3.6G）
dir gradle-caches-offline.zip
```

或 PowerShell 校验字节数：

```powershell
(Get-Item .\gradle-caches-offline.zip).Length
# 应输出 3837914036
```

### B.2 离线机（Windows）：部署环境

> 关键：Gradle 缓存必须解压到 `%USERPROFILE%\.gradle`（即 `C:\Users\<你的用户名>\.gradle`），Gradle 才会识别。

```powershell
# 1. 安装 JDK 17+（双击 Temurin17.msi），确认：
java -version    # 应输出 17 或 21

# 2. 合并分片得到 gradle-caches-offline.zip（若未分片则跳过，直接用整包）
cd D:\offline-bundle
copy /b gradle-caches-offline.zip.part-aa + gradle-caches-offline.zip.part-ab + gradle-caches-offline.zip.part-ac + gradle-caches-offline.zip.part-ad + gradle-caches-offline.zip.part-ae gradle-caches-offline.zip

# 3. 解压 Gradle 缓存到用户目录（关键路径：%USERPROFILE%\.gradle）
#    PowerShell 的 Expand-Archive 解压 zip
cd $env:USERPROFILE
Expand-Archive -Path D:\offline-bundle\gradle-caches-offline.zip -DestinationPath $env:USERPROFILE -Force

# 确认如下目录存在：
ls $env:USERPROFILE\.gradle\caches\9.5.0          # Gradle 自身 + IntelliJ Platform SDK 变换产物
ls $env:USERPROFILE\.gradle\caches\modules-2      # 第三方依赖 jar（gson/junit/intellij platform 插件等）
ls $env:USERPROFILE\.gradle\wrapper\dists\gradle-9.5.0-bin  # Gradle 发行包解压目录

# 4. 解压项目源码到工作目录
mkdir D:\workspace -Force
Expand-Archive -Path D:\offline-bundle\RestAutoLab-src.zip -DestinationPath D:\workspace -Force
cd D:\workspace\RestAutoLab
```

### B.3 离线机（Windows）：执行构建（关键加 `--offline`）

```cmd
cd D:\workspace\RestAutoLab

REM --offline 强制 Gradle 只使用本地缓存，绝不访问网络
REM Windows 用 gradlew.bat（不要用 ./gradlew）
gradlew.bat clean buildPlugin --offline
```

> 若出现 `No cached version of ... available for offline mode`，说明该依赖未在在线机缓存过。回到在线机执行一次 `./gradlew dependencies` 或 `./gradlew buildPlugin` 补齐缓存后重新打包传输。

### B.4 离线机：安装与验证

产物同样在 `build\distributions\RestAutoLab-1.0.0.zip`，按 **A.6 / A.7** 安装验证即可。

---

## 四、离线环境下的功能可用性说明

插件部分功能依赖外部服务，离线环境下行为如下：

| 功能 | 离线表现 | 说明 |
|------|----------|------|
| API 扫描 / 树形管理 / Gutter 导航 | ✅ 完全可用 | 纯本地 PSI 解析 |
| 测试用例管理 / 持久化 | ✅ 完全可用 | 存于项目级 `restautolab.xml` |
| Git 预提交检查 | ✅ 完全可用 | 本地 Git |
| Markdown / Postman / HTML 报告导出 | ✅ 完全可用 | 本地生成 |
| cURL 导入导出 / 历史记录 / Diff 对比 | ✅ 完全可用 | 本地处理 |
| 收藏文件夹 / 变更检测 | ✅ 完全可用 | 本地存储 |
| **HTTP 接口调试** | ⚠️ 依赖目标服务器可达 | 离线机需能访问被测接口所在内网服务器 |
| **AI 参数生成 / AI 断言生成** | ⚠️ 依赖自部署模型网关可达 | 需在 `Settings → Tools → RestAutoLab` 配置**自部署模型**地址；若不可达，自动降级为本地启发式生成（phone/email/idcard/name 等字段仍可识别） |
| 插件市场更新检查 | ❌ 不可用 | 离线环境预期行为，不影响使用 |

> **结论**：除「联网更新」外，所有核心功能在离线环境均可正常运行；自部署模型与 HTTP 调试只要对应服务可达即不受影响。

---

## 五、常见问题

### Q1：安装插件时提示「插件不兼容当前 IDE 版本」
检查 IDE 版本是否在 **2022.3 ~ 2026.1.x** 范围内（`Help → About` 查看 build 号是否在 223 ~ 261 之间）。版本不符需更换 IDE 版本或重新调整 `plugin.xml` 与 `build.gradle.kts` 的 `sinceBuild/untilBuild`。

### Q2：方案B构建报 `Could not resolve com.jetbrains.intellij.idea:ideaIU:2023.3.6`
说明 Gradle 缓存未完整拷贝。回到在线机，删除 `~/.gradle/caches` 后重新执行 `./gradlew buildPlugin` 触发完整下载，再重新打包。注意 `caches\modules-2` 与 `caches\9.5.0` **两个目录都要拷**（即 zip 内 `.gradle\caches\` 下都要有）。

### Q3：方案B构建卡在下载 Gradle 发行包
确认 `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.5.0-bin\<hash>\gradle-9.5.0` 目录存在且完整。若缺失，将在线机的该目录整体拷过来覆盖；或修改 `gradle\wrapper\gradle-wrapper.properties` 的 `distributionUrl` 指向内网镜像。

### Q4：`--offline` 模式报 foojay-resolver 错误
`settings.gradle.kts` 中的 `foojay-resolver-convention` 插件用于自动下载 JDK 工具链。离线机已手动安装 JDK 的情况下，可临时注释该插件行：

```kotlin
// settings.gradle.kts 中注释掉：
// id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
```
并在 `gradle.properties` 中显式指定 JDK 路径（Windows 用正斜杠或双反斜杠）：

```properties
org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-17.x.x-hotspot
```

### Q5：Windows SmartScreen 阻止 JDK/IDEA 安装
点击「更多信息」→「仍要运行」即可。若安装后 IDEA 无法启动，检查 `JAVA_HOME` 环境变量是否指向 JDK 17/21。

### Q6：macOS 提示「无法验证开发者」/「已损坏」（仅方案A在 macOS 离线机时适用）
IDEA 与 JDK 安装包执行：
```bash
sudo xattr -dr com.apple.quarantine /Applications/IntelliJ\ IDEA.app
```

### Q7：插件安装后工具窗不显示
确认 IDE 已启用 bundled 插件 `Java` 与 `Git4Idea`（`Settings → Plugins → Installed` 中检查二者未被禁用）。本插件 `<depends>` 这两个插件，被禁用时本插件不会加载。

---

## 六、快速检查清单（离线部署完成后逐项确认）

- [ ] 离线机 `java -version` 输出 17 或 21
- [ ] IDEA 版本在 2022.3 ~ 2026.1.x
- [ ] 插件 zip 已通过 `Install Plugin from Disk` 安装并重启
- [ ] 右侧 `RestAutoLab` 工具窗可见
- [ ] 右键菜单出现 `RestAutoLab` 分组
- [ ] 在 Spring 项目中可扫描出接口
- [ ] Controller 方法行号旁有 API gutter 图标
- [ ] （如需 AI）Settings 中已配置自部署模型网关地址
- [ ] （如需 HTTP 调试）被测接口服务器从离线机可达

全部勾选即代表离线环境运行正常。
