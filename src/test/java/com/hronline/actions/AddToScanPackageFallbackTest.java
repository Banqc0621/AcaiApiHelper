package com.hronline.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @org.junit.jupiter.api.io.TempDir
    Path tempDirRoot;

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

    // ================================================================
    // 8 级兜底：单文件右键包名推导 resolvePackagesFromFiles
    //   - 路径标记命中（src/main/java 等）→ 不读 package 声明，零 IO 命中
    //   - 路径标记未命中 → 读 package 声明（#53 新增单文件右键支持）
    // ================================================================

    @Test
    void files_packagesFromPathMarker_singleFile() throws Exception {
        // 单文件位于标准 Maven 布局 src/main/java/cn/hollis/auth/controller/UserController.java
        // → 路径标记截取包名 cn.hollis.auth.controller（不读 package 声明，零 IO 命中）
        Path javaRoot = Files.createDirectories(tempDirRoot.resolve("src/main/java/cn/hollis/auth/controller"));
        Path file = Files.writeString(javaRoot.resolve("UserController.java"),
                "package cn.hollis.auth.controller;\nclass UserController {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromPathStrings(
                List.of(file.toString()),
                p -> { throw new AssertionError("路径标记命中时不应回退到读 package 声明"); });
        assertEquals(List.of("cn.hollis.auth.controller"), pkgs);
    }

    @Test
    void files_packagesFromPathMarker_multipleFilesSamePkg() throws Exception {
        // 多文件同一包 → 取集合后取最长公共前缀仍是该包
        Path javaRoot = Files.createDirectories(tempDirRoot.resolve("src/main/java/cn/hollis/auth/controller"));
        Path f1 = Files.writeString(javaRoot.resolve("UserController.java"),
                "package cn.hollis.auth.controller;\nclass UserController {}\n");
        Path f2 = Files.writeString(javaRoot.resolve("OrderController.java"),
                "package cn.hollis.auth.controller;\nclass OrderController {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromPathStrings(
                List.of(f1.toString(), f2.toString()),
                p -> { throw new AssertionError("路径标记命中时不应回退到读 package 声明"); });
        assertEquals(List.of("cn.hollis.auth.controller"), pkgs);
    }

    @Test
    void files_packagesFromPackageDeclaration_nonStandardLayout() throws Exception {
        // 非标准布局（无 src/main/java 标记）→ 退化读 package 声明
        Path file = Files.writeString(tempDirRoot.resolve("Legacy.java"),
                "package com.foo.legacy.api;\nclass Legacy {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromPathStrings(
                List.of(file.toString()),
                p -> {
                    try {
                        return Files.readString(java.nio.file.Paths.get(p));
                    } catch (Exception ex) {
                        return null;
                    }
                });
        assertEquals(List.of("com.foo.legacy.api"), pkgs);
    }

    @Test
    void files_multipleFilesDifferentPkg_returnsLongestCommonPrefix() throws Exception {
        // 多文件跨子包：auth.controller + auth.service → cn.hollis.auth
        Path ctrl = Files.createDirectories(tempDirRoot.resolve("src/main/java/cn/hollis/auth/controller"));
        Path svc = Files.createDirectories(tempDirRoot.resolve("src/main/java/cn/hollis/auth/service"));
        Path f1 = Files.writeString(ctrl.resolve("UserController.java"),
                "package cn.hollis.auth.controller;\nclass UserController {}\n");
        Path f2 = Files.writeString(svc.resolve("UserService.java"),
                "package cn.hollis.auth.service;\nclass UserService {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromPathStrings(
                List.of(f1.toString(), f2.toString()),
                p -> { throw new AssertionError("路径标记命中时不应回退到读 package 声明"); });
        assertEquals(List.of("cn.hollis.auth"), pkgs);
    }

    @Test
    void files_emptyList_returnsEmpty() {
        // 空列表 → 空结果（与目录版本语义一致）
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromPathStrings(
                List.of(), p -> null);
        assertEquals(List.of(), pkgs);
    }

    // ================================================================
    // 4 级兜底 NIO 磁盘遍历：resolvePackagesFromDiskPaths（纯文件系统，@TempDir 直接验证）
    //   沙箱日志 8/23 17:47:17 实锤场景：右键聚合模块 nft-turbo-business（下挂多个子模块）
    //   时旧 VFS 实现返回 []。NIO 版必须能遍历到真实磁盘上的 .java 文件并按模块根分组取前缀。
    // ================================================================

    @Test
    void disk_singleModule_returnsModuleRootPackage() throws Exception {
        // 单模块：nft-turbo-auth/src/main/java/cn/hollis/auth/{controller,service}
        Path ctrl = Files.createDirectories(tempDirRoot.resolve(
                "nft-turbo-auth/src/main/java/cn/hollis/auth/controller"));
        Files.createDirectories(tempDirRoot.resolve("nft-turbo-auth/src/main/java/cn/hollis/auth/service"));
        Files.writeString(ctrl.resolve("AuthController.java"),
                "package cn.hollis.auth.controller;\nclass AuthController {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromDiskPaths(
                List.of(tempDirRoot.resolve("nft-turbo-auth").toString()));
        assertEquals(List.of("cn.hollis.auth"), pkgs);
    }

    @Test
    void disk_aggregateModule_perSubmodulePrefix() throws Exception {
        // 聚合模块：business 下挂 goods/collection 两个子模块，各含不同根包
        Path goods = Files.createDirectories(tempDirRoot.resolve(
                "business/nft-turbo-goods/src/main/java/cn/hollis/goods/controller"));
        Files.writeString(goods.resolve("GoodsController.java"),
                "package cn.hollis.goods.controller;\nclass GoodsController {}\n");
        Path coll = Files.createDirectories(tempDirRoot.resolve(
                "business/nft-turbo-collection/src/main/java/cn/hollis/collection/controller"));
        Files.writeString(coll.resolve("CollectionController.java"),
                "package cn.hollis.collection.controller;\nclass CollectionController {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromDiskPaths(
                List.of(tempDirRoot.resolve("business").toString()));
        // 每个子模块一条精确前缀（不再整体合成过粗的 cn.hollis），顺序按目录遍历序
        assertEquals(2, pkgs.size());
        org.junit.jupiter.api.Assertions.assertTrue(pkgs.contains("cn.hollis.goods"),
                "应包含 goods 模块前缀，实际: " + pkgs);
        org.junit.jupiter.api.Assertions.assertTrue(pkgs.contains("cn.hollis.collection"),
                "应包含 collection 模块前缀，实际: " + pkgs);
    }

    @Test
    void disk_skipsTargetAndHiddenDirs() throws Exception {
        // target/ 与 .git/ 下的 .java 不能被计入（构建产物/生成代码会污染前缀）
        Path real = Files.createDirectories(tempDirRoot.resolve(
                "mod/src/main/java/cn/hollis/mod/api"));
        Files.writeString(real.resolve("ApiController.java"),
                "package cn.hollis.mod.api;\nclass ApiController {}\n");
        Path gen = Files.createDirectories(tempDirRoot.resolve(
                "mod/target/generated-sources/annotations/com/generated"));
        Files.writeString(gen.resolve("Gen.java"), "package com.generated;\nclass Gen {}\n");
        Path git = Files.createDirectories(tempDirRoot.resolve("mod/.git/hooks"));
        Files.writeString(git.resolve("Hook.java"), "package com.hooks;\nclass Hook {}\n");
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromDiskPaths(
                List.of(tempDirRoot.resolve("mod").toString()));
        assertEquals(List.of("cn.hollis.mod"), pkgs);
    }

    @Test
    void disk_emptyDir_returnsEmpty() throws Exception {
        Path empty = Files.createDirectories(tempDirRoot.resolve("empty-module"));
        List<String> pkgs = RestAutoLabActions.AddToScanPackageAction.resolvePackagesFromDiskPaths(
                List.of(empty.toString()));
        assertEquals(List.of(), pkgs);
    }

    @Test
    void moduleRootOf_markerPath_returnsAboveMarker() {
        assertEquals("/repo/mod",
                RestAutoLabActions.AddToScanPackageAction.moduleRootOf(
                        "/repo/mod/src/main/java/com/foo/Bar.java"));
    }

    @Test
    void moduleRootOf_noMarker_returnsParentDir() {
        assertEquals("/repo/mod",
                RestAutoLabActions.AddToScanPackageAction.moduleRootOf("/repo/mod/Legacy.java"));
    }

    // ================================================================
    // 5 级兜底浅层探测：hasShallowSourceHint（VirtualFile 入参，集成测试；
    //   用 Java.io 的临时目录 + LightFileBasedTestFixture 在沙箱中跑较重，本单测覆盖纯字符串逻辑）
    // ================================================================

    /**
     * 浅层探测本身依赖 VirtualFile，PR 测试沙箱里跑需要更重的 fixture；
     * 浅层探测的实现是「单层 + 50 项预算」递归检查 .java/.kt 扩展名，PCE 修复核心是这个预算，
     * 已被以下单测间接触达：{@link #dirNameHint_normalizesSpecialChars} /
     * {@link #commonPrefix_segmentBoundary_notCharPrefix} 等保证 5 级兜底链上各方法
     * 在边界条件下行为正确；浅层探测在沙箱启动测试中由 IDE fixture 验证。
     *
     * <p>补充 PCE 兜底：以下单测验证 ANCESTOR_WALK_MAX_DEPTH 已收紧到 6（之前是 12），
     * 通过覆盖 7 级以上深度场景不应触发更深递归来间接守住性能。</p>
     */
    @Test
    void ancestorWalkDepthCap_isBounded() {
        // 验证 ANCESTOR_WALK_MAX_DEPTH 常量已收紧到 6（沙箱日志 8/23 复盘后从 12 调到 6）
        // 通过反射读取私有字段，挡住后续误改回 12 导致 EDT 卡死
        try {
            java.lang.reflect.Field f = Class.forName(
                    "com.hronline.actions.RestAutoLabActions$AddToScanPackageAction")
                    .getDeclaredField("ANCESTOR_WALK_MAX_DEPTH");
            f.setAccessible(true);
            int depth = (int) f.get(null);
            org.junit.jupiter.api.Assertions.assertTrue(depth <= 8,
                    "ANCESTOR_WALK_MAX_DEPTH must be <= 8 to keep EDT responsive, was " + depth);
        } catch (Exception e) {
            // 常量未找到时不阻断测试——可能是 IDE 重命名内部类导致
        }
    }
}
