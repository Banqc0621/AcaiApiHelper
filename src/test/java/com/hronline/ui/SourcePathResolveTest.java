package com.hronline.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 跳转源路径解析回归测试：
 * <p>修复提单「找不到源文件：…/.jar!/…/TemplateController.class」。
 * 扫描范围 allScope 会把依赖 jar 里带 @RestController 的类也收录，
 * 记录路径形如 {@code xxx.jar!/com/…/Xxx.class}，双击跳转需拆分该路径而非交给 LocalFileSystem。</p>
 * <ul>
 *   <li>{@link ApiTreePanel#splitJarEntryPath}：jar!/ 路径拆分为 [jar 本地路径, 条目路径]；普通路径返回 null</li>
 *   <li>{@link ApiTreePanel#classEntryToJavaPath}：.class 记录路径 → 对应 .java 路径（优先跳源码）</li>
 * </ul>
 */
class SourcePathResolveTest {

    // ── splitJarEntryPath ──

    @Test
    void split_jarEntryPath_returnsJarAndEntry() {
        String path = "/Users/x/dev/maven_rep/com/github/davidfantasy/mybatis-plus-generator-ui/2.0.5/"
                + "mybatis-plus-generator-ui-2.0.5.jar!/com/github/davidfantasy/mybatisplus/generatorui/"
                + "controller/TemplateController.class";
        String[] parts = ApiTreePanel.splitJarEntryPath(path);
        assertArrayEquals(new String[]{
                        path.substring(0, path.indexOf("!/")),
                        "com/github/davidfantasy/mybatisplus/generatorui/controller/TemplateController.class"},
                parts);
    }

    @Test
    void split_plainPath_returnsNull() {
        assertNull(ApiTreePanel.splitJarEntryPath("/Users/x/project/src/main/java/com/foo/UserController.java"));
    }

    @Test
    void split_nullOrInvalid_returnsNull() {
        assertNull(ApiTreePanel.splitJarEntryPath(null));
        assertNull(ApiTreePanel.splitJarEntryPath(""));
        // 条目为空
        assertNull(ApiTreePanel.splitJarEntryPath("/repo/foo.jar!/"));
        // !/ 出现在开头（无 jar 路径）
        assertNull(ApiTreePanel.splitJarEntryPath("!/com/foo/Bar.class"));
    }

    @Test
    void split_windowsStyleJarPath() {
        String path = "D:/repo/com/foo/bar-1.0.jar!/com/foo/Bar.class";
        String[] parts = ApiTreePanel.splitJarEntryPath(path);
        assertArrayEquals(new String[]{"D:/repo/com/foo/bar-1.0.jar", "com/foo/Bar.class"}, parts);
    }

    // ── classEntryToJavaPath ──

    @Test
    void classPath_mapsToJavaPath() {
        assertEquals("/repo/foo.jar!/com/foo/Bar.java",
                ApiTreePanel.classEntryToJavaPath("/repo/foo.jar!/com/foo/Bar.class"));
    }

    @Test
    void nonClassPath_returnsNull() {
        assertNull(ApiTreePanel.classEntryToJavaPath("/repo/foo.jar!/META-INF/MANIFEST.MF"));
        assertNull(ApiTreePanel.classEntryToJavaPath(null));
    }
}
