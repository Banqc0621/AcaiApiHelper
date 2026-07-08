import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// ============================================================
// 插件元信息
// ============================================================
group = "com.ban"
version = "1.0.0"

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
        intellijIdea("2025.3.5")
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
        id = "com.ban.acai"
        name = "Acai API Helper"
        version = "1.0.0"

        ideaVersion {
            sinceBuild = "223"
            untilBuild = "261.*"
        }
    }
}

// ============================================================
// 源码集配置 - Kotlin源文件保留在src/kotlin/但不参与编译
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
        // 显式指定变更日志，避免插件检测 "Version is missing: Unreleased" 报错
        // 这里留空字符串，change-notes 段由 plugin.xml 自己提供
        changeNotes = "v1.0.0 - Acai API Helper 功能完善版（兼容 IDEA 2022.3 - 2026.1.x）"
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}