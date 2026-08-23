package com.hronline.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「仅显示此包接口」包名推导纯逻辑单测。
 *
 * <p>一伦反馈 #56 后，actionPerformed 内的 4-7 级宽松匹配兜底（磁盘遍历 / 向上寻包 /
 * 模块描述符 / 目录名猜包）已全部删除，保留的解析路径只剩 3 种严格解析：
 * PSI 反查 / 路径标记截取 / 已扫描接口目录归属 / 单文件 package 声明读取。
 * 这些 helper 的纯字符串 / 纯逻辑部分继续覆盖，确保边界行为稳定。</p>
 */
class AddToScanPackageFallbackTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDirRoot;

    // ================================================================
    // longestCommonPackagePrefix：聚合包集合取最长公共前缀（按段边界）。
    //   resolvePackagesFromCachedApis / aggregateToCommonPrefix / files 路径都依赖此函数。
    // ================================================================

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
        // 完全不相干的两个包 → 无公共前缀，调用方按空结果处理（直接给空反馈，不降级）
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
    // 单文件右键包名推导 resolvePackagesFromPathStrings
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
}
