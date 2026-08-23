package com.hronline.actions;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「仅显示此包接口」磁盘遍历兜底（右键模块根且缓存无该模块接口）的纯逻辑单测。
 * <p>覆盖 {@code longestCommonPackagePrefix}：多包名集合取最长公共包前缀，
 * 保证「右键模块根 → 推导模块根包 → 覆盖 controller 等全部子包」的语义。</p>
 */
class AddToScanPackageFallbackTest {

    @Test
    void commonPrefix_parentAndChildPackage_returnsParent() {
        // 模块根下典型结构：根包 + controller 子包 → 公共前缀为根包
        assertEquals("cn.hollis.nft.turbo.auth",
                RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of(
                        "cn.hollis.nft.turbo.auth",
                        "cn.hollis.nft.turbo.auth.controller")));
    }

    @Test
    void commonPrefix_multipleSiblingPackages_returnsModuleRootPackage() {
        assertEquals("cn.hollis.nft.turbo.auth",
                RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of(
                        "cn.hollis.nft.turbo.auth.controller",
                        "cn.hollis.nft.turbo.auth.service",
                        "cn.hollis.nft.turbo.auth.vo")));
    }

    @Test
    void commonPrefix_singlePackage_returnsItself() {
        assertEquals("cn.hollis.nft.turbo.auth.controller",
                RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of(
                        "cn.hollis.nft.turbo.auth.controller")));
    }

    @Test
    void commonPrefix_disjointPackages_returnsEmpty() {
        // 完全不相干的两个包 → 无公共前缀，调用方按空结果处理（继续兜底/提示）
        assertEquals("",
                RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of(
                        "com.foo.controller",
                        "org.bar.controller")));
    }

    @Test
    void commonPrefix_emptySet_returnsEmpty() {
        assertEquals("", RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of()));
    }

    @Test
    void commonPrefix_segmentBoundary_notCharPrefix() {
        // 段边界校验：com.foobar 与 com.foo 的公共前缀只能是 "com"，不能误合成 "com.foo"
        assertEquals("com",
                RestAutoLabActions.AddToScanPackageAction.longestCommonPackagePrefix(Set.of(
                        "com.foo.a",
                        "com.foobar.b")));
    }

    // ================================================================
    // 6 级兜底：extractPackageFromModuleText
    // ================================================================

    @Test
    void moduleText_pomXml_groupId() {
        String pom = "<project>\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>cn.hollis.nft.turbo</groupId>\n" +
                "    <artifactId>nft-turbo-auth</artifactId>\n" +
                "</project>";
        assertEquals("cn.hollis.nft.turbo",
                RestAutoLabActions.AddToScanPackageAction.extractPackageFromModuleText("pom.xml", pom));
    }

    @Test
    void moduleText_pomXml_artifactIdFallback() {
        // 没 groupId 时退化用 artifactId（适用 parent pom / 子模块 pom 缺 groupId 的场景）
        String pom = "<project>\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <artifactId>nft-turbo-auth</artifactId>\n" +
                "</project>";
        assertEquals("nft-turbo-auth",
                RestAutoLabActions.AddToScanPackageAction.extractPackageFromModuleText("pom.xml", pom));
    }

    @Test
    void moduleText_buildGradle_groupAssignment() {
        String gradle = "plugins { id 'java' }\n" +
                "group = 'cn.hollis.nft.turbo.auth'\n" +
                "version = '1.0.0'\n";
        assertEquals("cn.hollis.nft.turbo.auth",
                RestAutoLabActions.AddToScanPackageAction.extractPackageFromModuleText("build.gradle", gradle));
    }

    @Test
    void moduleText_buildGradleKts_rootProjectName() {
        String kts = "rootProject.name = \"nft-turbo-auth\"\n";
        assertEquals("nft-turbo-auth",
                RestAutoLabActions.AddToScanPackageAction.extractPackageFromModuleText("build.gradle.kts", kts));
    }

    @Test
    void moduleText_noMatch_returnsNull() {
        // gradle 文件里既没有 group 也没有 rootProject.name
        String gradle = "plugins { id 'java' }\nversion = '1.0.0'\n";
        assertNull(RestAutoLabActions.AddToScanPackageAction.extractPackageFromModuleText("build.gradle", gradle));
    }

    // ================================================================
    // 7 级兜底：pickDirNameHint
    // ================================================================

    @Test
    void dirNameHint_skipsSrcMainJavaNoise() {
        // 路径段从深到浅：myfeature 是第一个有意义的，跳过 src/main/java
        List<String> chain = Arrays.asList("myfeature", "java", "main", "src", "nft-turbo-auth", "modules");
        assertEquals("myfeature",
                RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(chain));
    }

    @Test
    void dirNameHint_skipsBuildTargetGit() {
        // 没有更深的有意义段，回退到模块根目录名
        // 连字符会被归一化为合法 Java 包段（nft-turbo-gateway → nftturbogateway）
        List<String> chain = Arrays.asList("build", "target", ".git", "nft-turbo-gateway");
        assertEquals("nftturbogateway",
                RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(chain));
    }

    @Test
    void dirNameHint_allNoise_returnsNull() {
        // 全是噪音目录（src/main/java/build/.gradle）→ 无可挑
        List<String> chain = Arrays.asList("java", "main", "src", "build", ".gradle");
        assertNull(RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(chain));
    }

    @Test
    void dirNameHint_normalizesSpecialChars() {
        // 连字符归一化为合法 Java 包段；下划线按 Java 标识符规则保留
        List<String> chain = Arrays.asList("my-feature_v2", "src", "main");
        assertEquals("myfeature_v2",
                RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(chain));
    }

    @Test
    void dirNameHint_emptyChain_returnsNull() {
        assertNull(RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(List.of()));
    }

    @Test
    void dirNameHint_emptyChain_handlesNullSegment() {
        // null 段不能 NPE（防御性）
        assertNotNull(RestAutoLabActions.AddToScanPackageAction.pickDirNameHint(Arrays.asList(null, "src", "myfeature")));
    }
}
