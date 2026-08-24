package com.hronline.scanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包扫描过滤（设置「扫描包过滤」→ 左侧全量接口只显示指定包下的接口）单元测试。
 * <p>覆盖两个核心逻辑：</p>
 * <ul>
 *   <li>{@code parsePackageFilter}：配置串拆分（逗号/分号/空白分隔、去尾部点、空配置=不过滤）</li>
 *   <li>{@code matchesPackagePrefix}：包段边界匹配（com.foo 不得误命中 com.foobar.*）</li>
 * </ul>
 */
class ApiScannerPackageFilterTest {

    // ── parsePackageFilter ──

    @Test
    void parse_nullOrBlank_returnsEmpty() {
        assertTrue(ApiScannerService.parsePackageFilter(null).isEmpty());
        assertTrue(ApiScannerService.parsePackageFilter("").isEmpty());
        assertTrue(ApiScannerService.parsePackageFilter("   ").isEmpty());
    }

    @Test
    void parse_singlePrefix() {
        assertEquals(List.of("com.xxx.sys"), ApiScannerService.parsePackageFilter("com.xxx.sys"));
    }

    @Test
    void parse_multiple_commaSemicolonSpaceSeparated() {
        assertEquals(List.of("com.a", "com.b", "com.c"),
                ApiScannerService.parsePackageFilter("com.a, com.b; com.c"));
        assertEquals(List.of("com.a", "com.b"),
                ApiScannerService.parsePackageFilter("com.a,com.b"));
    }

    @Test
    void parse_trailingDotsStripped() {
        assertEquals(List.of("com.foo"), ApiScannerService.parsePackageFilter("com.foo."));
        assertEquals(List.of("com.foo"), ApiScannerService.parsePackageFilter("com.foo.."));
    }

    @Test
    void parse_blankSegmentsSkipped() {
        assertEquals(List.of("com.a"), ApiScannerService.parsePackageFilter(", com.a,; ,"));
    }

    // ── matchesPackagePrefix：边界匹配语义 ──

    @Test
    void match_samePackageClass() {
        assertTrue(ApiScannerService.matchesPackagePrefix(
                "com.xxx.sys.UserController", List.of("com.xxx.sys")));
    }

    @Test
    void match_subPackageClass() {
        // 指定包前缀天然覆盖所有子包
        assertTrue(ApiScannerService.matchesPackagePrefix(
                "com.xxx.sys.admin.RoleController", List.of("com.xxx.sys")));
    }

    @Test
    void match_parentPackage_coversControllerSubPackage() {
        // 一伦优化 #51：右键「任意层级包」即可，不必精确到 controller 层。
        // 右键 cn.hollis.nft.turbo.auth（含 controller/vo/param 等子包）
        // 应覆盖其 controller 子包下的接口。
        assertTrue(ApiScannerService.matchesPackagePrefix(
                "cn.hollis.nft.turbo.auth.controller.AuthController",
                List.of("cn.hollis.nft.turbo.auth")));
        assertTrue(ApiScannerService.matchesPackagePrefix(
                "cn.hollis.nft.turbo.admin.controller.UserAdminController",
                List.of("cn.hollis.nft.turbo")));
        // 兄弟模块不受影响（段边界校验）
        assertFalse(ApiScannerService.matchesPackagePrefix(
                "cn.hollis.nft.turbo2.controller.X", List.of("cn.hollis.nft.turbo")));
    }

    @Test
    void match_siblingPackageWithSameTextPrefix_notMatched() {
        // 边界校验核心用例：com.foo 绝不能命中 com.foobar.*
        assertFalse(ApiScannerService.matchesPackagePrefix(
                "com.foobar.UserController", List.of("com.foo")));
        assertFalse(ApiScannerService.matchesPackagePrefix(
                "com.xxx.system.UserController", List.of("com.xxx.sys")));
    }

    @Test
    void match_exactClassName() {
        // 配置精确到类名（少见场景）也应命中
        assertTrue(ApiScannerService.matchesPackagePrefix(
                "com.xxx.sys.UserController", List.of("com.xxx.sys.UserController")));
    }

    @Test
    void match_multiplePrefixes_orSemantics() {
        List<String> prefixes = List.of("com.xxx.sys", "com.xxx.admin");
        assertTrue(ApiScannerService.matchesPackagePrefix("com.xxx.sys.AController", prefixes));
        assertTrue(ApiScannerService.matchesPackagePrefix("com.xxx.admin.BController", prefixes));
        assertFalse(ApiScannerService.matchesPackagePrefix("com.xxx.other.CController", prefixes));
    }

    @Test
    void match_unrelatedPackage_notMatched() {
        assertFalse(ApiScannerService.matchesPackagePrefix(
                "org.springframework.web.ErrorController", List.of("com.xxx")));
    }

    @Test
    void match_nullQfn_kept() {
        // qfn 为 null（部分 Kotlin 类/局部类）不过滤，保留
        assertTrue(ApiScannerService.matchesPackagePrefix(null, List.of("com.xxx")));
    }

    @Test
    void match_trailingDotConfig_stillMatches() {
        // "com.foo." 归一化为 "com.foo" 后照常匹配
        List<String> prefixes = ApiScannerService.parsePackageFilter("com.foo.");
        assertTrue(ApiScannerService.matchesPackagePrefix("com.foo.BarController", prefixes));
        assertFalse(ApiScannerService.matchesPackagePrefix("com.foobar.BarController", prefixes));
    }

    @Test
    void sourcePath_scopeUsesDirectoryBoundary() {
        assertTrue(ApiScannerService.matchesSourcePath(
                "/project/nft-turbo-auth/src/main/java/com/acme/AuthController.java",
                Set.of("/project/nft-turbo-auth")));
        assertFalse(ApiScannerService.matchesSourcePath(
                "/project/nft-turbo-authentication/src/main/java/com/acme/AuthController.java",
                Set.of("/project/nft-turbo-auth")));
    }

    @Test
    void sourcePath_fileSelectionIsExact() {
        assertTrue(ApiScannerService.matchesSourcePath(
                "/project/src/main/java/com/acme/AuthController.java",
                Set.of("/project/src/main/java/com/acme/AuthController.java")));
        assertFalse(ApiScannerService.matchesSourcePath(
                "/project/src/main/java/com/acme/OrderController.java",
                Set.of("/project/src/main/java/com/acme/AuthController.java")));
    }
}
