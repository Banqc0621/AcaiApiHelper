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
version = "3.0.0"

// ============================================================
// Java 编译配置 - Java 21 (匹配IntelliJ Platform 2025.x)
// ============================================================
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
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
        version = "3.0.0"

        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
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
        sinceBuild = "243"
        untilBuild = "253.*"
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}