import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val ideaVersion = providers.gradleProperty("ideaVersion").orElse("2026.1").get()

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

// ============================================================
// 插件元信息
// ============================================================
group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

// ============================================================
// Java 编译配置 - Java 17 (匹配 IntelliJ Platform 2022.3+ 运行时)
// ============================================================
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
    // 显式指定 release 17，生成 Java 17 字节码（兼容 IDEA 2022.3+ 运行时）
    options.release = 17
}

// ============================================================
// 仓库配置
// ============================================================
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ============================================================
// 依赖配置
// ============================================================
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Gson: JSON序列化/反序列化
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        // 默认使用 2026.1 平台；可用 -PideaVersion=2022.3.3
        // 单独执行最低兼容版本的编译和测试。
        intellijIdea(ideaVersion)
        testFramework(TestFrameworkType.Platform)

        // Java PSI support
        bundledPlugin("com.intellij.java")
        // Git/VCS integration for pre-commit hooks
        bundledPlugin("Git4Idea")
    }
}

// ============================================================
// IntelliJ Platform 配置
// ============================================================
intellijPlatform {
    pluginConfiguration {
        id = "com.banqc.restautolab"
        name = "RestAutoLab"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "223"
            untilBuild = "261.*"
        }
    }

    // 发布到 JetBrains Marketplace：token 仅从命令行参数 -PideaPublishToken=xxx
    // 或环境变量 IDEA_PUBLISH_TOKEN 注入，禁止写入仓库。
    publishing {
        token = providers.gradleProperty("ideaPublishToken")
            .orElse(providers.environmentVariable("IDEA_PUBLISH_TOKEN"))
    }
}

// ============================================================
// 源码集配置 - 本插件只编译 Java 源码
// ============================================================
sourceSets {
    main {
        java {
            setSrcDirs(listOf("src/main/java"))
        }
        resources {
            setSrcDirs(listOf("src/main/resources"))
        }
    }
}

// ============================================================
// 构建任务配置
// ============================================================
tasks {
    test {
        useJUnitPlatform()
    }

    patchPluginXml {
        sinceBuild = "223"
        untilBuild = "261.*"
        // 不在此处设置 changeNotes，<change-notes> 段由 plugin.xml 自己提供
        // （若赋值会覆盖 plugin.xml 中完整的多版本变更日志，用户在插件管理器只能看到单行）
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    buildPlugin {
        // 产物名与插件展示名保持一致：RestAutoLab-1.0.0.zip（默认取 rootProject.name 会是小写 restautolab）
        archiveBaseName.set("RestAutoLab")
    }

    /**
     * 打包后自动上传压缩包到飞书 base 表格（优化提单）。
     * <p>脚本位于 {@code scripts/upload_plugin_to_feishu_base.py}，依赖 lark-cli 已登录并具备：
     * {@code base:record:read / base:record:write / base:field:write / base:file:write / drive:file:upload}。
     * 首次跑前请运行 {@code lark-cli auth login --scope "..."} 完成授权；缺 scope 时脚本会 fail-fast
     * 提示去授权，不影响 buildPlugin 本身的产物。</p>
     * <p>关闭自动上传：传 {@code -PfeishuUploadPlugin=false} 或环境变量
     * {@code FEISHU_UPLOAD_PLUGIN=false}（脚本内部检查）。</p>
     */
    val uploadToFeishuBase by registering(Exec::class) {
        group = "publishing"
        description = "把 build/distributions/*.zip 上传到飞书 base 表格「优化提单」的最新版本包行"

        // 极简配置：所有 enable 检查 / zip 存在检查都下放到 Python 脚本里，
        // 避免闭包引用被 Configuration Cache 拒绝（"cannot serialize Gradle script object references"）。
        commandLine = listOf("python3", "scripts/upload_plugin_to_feishu_base.py")
        // 失败不抛异常，避免阻塞打包；脚本自身按 exit code 区分跳过/失败
        isIgnoreExitValue = true
    }

    // 打包成功后自动跑上传（fail-fast 不阻塞）
    buildPlugin {
        finalizedBy(uploadToFeishuBase)
    }
}
