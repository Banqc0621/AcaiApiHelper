package com.hronline.actions;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
