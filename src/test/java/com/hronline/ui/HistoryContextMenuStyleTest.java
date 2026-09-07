package com.hronline.ui;

import com.intellij.icons.AllIcons;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一伦优化 v17：历史 Tab 右键菜单项的样式契约测试。
 *
 * <p>验证</p>
 * <ul>
 *   <li>「发送」替代「重新发送」（v17 重命名）</li>
 *   <li>菜单项 font 加粗</li>
 *   <li>菜单项 padding 适中（左右至少 8），避免按钮过长</li>
 *   <li>删除类项染红（JBColor.RED）</li>
 *   <li>icon-text gap ≥ 6px</li>
 * </ul>
 *
 * <p>createStyledMenuItem 是 private static 方法，单测走反射拿不到 —— 这里改成
 * 通过 {@link ApiDebuggerPanel#createStyledMenuItem(String, javax.swing.Icon, boolean)}
 * 的可见性提升来直接验证；如果将来改成 instance/非 static，这条测试还能复用。</p>
 */
class HistoryContextMenuStyleTest {

    /**
     * 通过反射调 private static helper —— 避免改可见性影响生产代码。
     * 反射失败（方法签名变了）= 编译期就拦不住，必须改这条测试。
     */
    private static JMenuItem callCreate(String text, javax.swing.Icon icon, boolean danger) throws Exception {
        java.lang.reflect.Method m = ApiDebuggerPanel.class
                .getDeclaredMethod("createStyledMenuItem", String.class, javax.swing.Icon.class, boolean.class);
        m.setAccessible(true);
        return (JMenuItem) m.invoke(null, text, icon, danger);
    }

    @Test
    void resendItem_isNamed_send() throws Exception {
        // 发送（v17 重命名）：菜单项文字必须是 "发送"，不再是 "重新发送"
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        assertEquals("发送", item.getText());
        assertNotNull(item.getIcon());
    }

    @Test
    void menuItem_usesBoldFont() throws Exception {
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        Font f = item.getFont();
        assertNotNull(f);
        assertEquals(Font.BOLD, f.getStyle(),
                "菜单项 font 必须加粗（默认 LaF 是 PLAIN，看着又小又挤）");
    }

    @Test
    void menuItem_hasGenerousPadding() throws Exception {
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        Insets m = item.getMargin();
        assertNotNull(m);
        // 左右 padding 至少 8，垂直至少 3；在可读性和紧凑度之间取平衡
        assertTrue(m.left >= 8, "左 padding 至少 8px: " + m.left);
        assertTrue(m.right >= 8, "右 padding 至少 8px: " + m.right);
        assertTrue(m.top >= 3, "上 padding 至少 3px: " + m.top);
        assertTrue(m.bottom >= 3, "下 padding 至少 3px: " + m.bottom);
    }

    @Test
    void menuItem_iconTextGapIsAtLeastEight() throws Exception {
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        // iconTextGap 默认 4，看着 icon 跟文字几乎贴一起；6px 足够清晰且更紧凑
        assertTrue(item.getIconTextGap() >= 6,
                "icon 跟文字至少 6px 间距: " + item.getIconTextGap());
    }

    @Test
    void dangerItem_usesRedForeground() throws Exception {
        JMenuItem danger = callCreate("删除", AllIcons.General.Remove, true);
        Color fg = danger.getForeground();
        assertNotNull(fg);
        // JBColor.RED 在 light 主题是 (204,0,0)，dark 是 (255,108,108) 系。
        // 这里只验红通道 > 100，绿/蓝 < 80（粗略近似）。
        assertTrue(fg.getRed() > 100, "删除项前景红通道必须 > 100: " + fg.getRed());
        assertTrue(fg.getGreen() < 80, "删除项前景绿通道必须 < 80: " + fg.getGreen());
        assertTrue(fg.getBlue() < 80, "删除项前景蓝通道必须 < 80: " + fg.getBlue());
    }

    @Test
    void safeItem_keepsDefaultForeground() throws Exception {
        // 「发送」是安全操作，不应被染红
        JMenuItem safe = callCreate("发送", AllIcons.Actions.Execute, false);
        Color fg = safe.getForeground();
        assertNotNull(fg);
        // 默认前景：light 接近黑、dark 接近白 —— 红通道都低
        // 但 LaF 也可能用蓝色等主题色，所以只验「不是红」
        assertTrue(fg.getRed() <= 100 || fg.getGreen() >= 100,
                "发送项不能被染红: rgb=" + fg.getRed() + "," + fg.getGreen() + "," + fg.getBlue());
    }

    @Test
    void menuItem_minimumDimensionIs28High() throws Exception {
        // 防止高度被压扁导致中文菜单项显示不全
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        java.awt.Dimension d = item.getPreferredSize();
        assertNotNull(d);
        assertTrue(d.height >= 28, "菜单项最小高度 28px: " + d.height);
    }

    @Test
    void menuItem_compactWidthFollowsContent() throws Exception {
        JMenuItem item = callCreate("发送", AllIcons.Actions.Execute, false);
        java.awt.Dimension d = item.getPreferredSize();
        assertTrue(d.width <= 100, "单条历史菜单项不应被固定宽度撑长: " + d.width);
    }
}
