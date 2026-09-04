package com.hronline.ui;

import com.hronline.model.ApiDefinition;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #80：依赖设置弹框里接口行渲染的契约测试。
 *
 * <p>渲染器返回 HTML 文本，徽章用主题色块 + HTTP method，URL 走对应文字色；
 * 这里只验 HTML 字符串契约（保证样式 token 不漏），不验像素颜色。</p>
 */
class DependencyGraphDialogRendererTest {

    private static ApiDefinition api(String method, String path) {
        ApiDefinition a = new ApiDefinition();
        a.setHttpMethod(method);
        a.setUrl(path);
        a.setName("");
        return a;
    }

    /**
     * OrderListCellRenderer 必须把 method 渲染为带颜色背景的徽章 + URL。
     * <p>GET 用 GET 主题色 (#2E7D32 light / 等价 dark)，POST 是 #1565C0 系蓝。</p>
     */
    @Test
    void orderListCellRenderer_emitsMethodBadgeAndUrl() {
        JList<ApiDefinition> list = new JList<>();
        list.setModel(new DefaultListModel<>());
        DependencyGraphDialog.OrderListCellRenderer renderer =
                new DependencyGraphDialog.OrderListCellRenderer();
        Component c = renderer.getListCellRendererComponent(list,
                api("GET", "/admin/box/list"), 0, false, false);
        assertNotNull(c);
        String html = ((javax.swing.JLabel) c).getText();
        assertTrue(html.contains("GET"),
                "OrderListCellRenderer 文本必须包含方法名");
        assertTrue(html.contains("/admin/box/list"),
                "OrderListCellRenderer 文本必须包含 URL");
        assertTrue(html.contains("background-color"),
                "OrderListCellRenderer 必须给方法徽章配背景色（不要纯文字）");
        // 第 0 行必须显示序号 "1."
        assertTrue(html.contains("1."));
    }

    @Test
    void orderListCellRenderer_handlesNullAndEmptyMethod() {
        JList<ApiDefinition> list = new JList<>();
        DependencyGraphDialog.OrderListCellRenderer renderer =
                new DependencyGraphDialog.OrderListCellRenderer();
        // null api → 不抛异常
        Component c = renderer.getListCellRendererComponent(list, null, 0, false, false);
        assertNotNull(c);
        // method 为空字符串时徽章用 "API" 兜底（不显示空白徽章）
        ApiDefinition noMethod = new ApiDefinition();
        noMethod.setHttpMethod("");
        noMethod.setUrl("/x");
        Component c2 = renderer.getListCellRendererComponent(list, noMethod, 1, false, false);
        String html2 = ((javax.swing.JLabel) c2).getText();
        assertTrue(html2.contains("API"),
                "method 为空时徽章兜底显示 API，而不是空白");
        assertTrue(html2.contains("/x"));
    }

    /**
     * ApiColumnRenderer：依赖表格里上游/下游接口列渲染器。
     * 表格 cellRenderer 的 value 通常是 "METHOD URL" 字符串，
     * 这里走空字符串 fallback（占位/未知接口），不强行套徽章。
     */
    @Test
    void apiColumnRenderer_emptyValueUsesDashFallback() {
        JTable table = new JTable(new DefaultTableModel(
                new Object[]{"col"}, 0));
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        Component c = renderer.getTableCellRendererComponent(table, "", false, false, 0, 0);
        String html = ((javax.swing.JTextArea) c).getText();
        assertTrue(html.contains("—"),
                "空 value 必须用 em-dash 占位，不能空白");
    }

    @Test
    void apiColumnRenderer_methodUrlLabelRendersBadge() {
        JTable table = new JTable(new DefaultTableModel(
                new Object[]{"col"}, 0));
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        Component c = renderer.getTableCellRendererComponent(table,
                "GET /admin/box/list", false, false, 0, 0);
        String html = ((javax.swing.JTextArea) c).getText();
        assertTrue(html.contains("GET"));
        assertTrue(html.contains("/admin/box/list"));
        assertTrue(html.contains("background-color"),
                "ApiColumnRenderer 必须给方法徽章配背景色");
    }

    @Test
    void apiColumnRenderer_bracketedShortLabelParses() {
        // 撞名短标签走 [GET] list 形式
        JTable table = new JTable();
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        Component c = renderer.getTableCellRendererComponent(table,
                "[POST] orders", false, false, 0, 0);
        String html = ((javax.swing.JTextArea) c).getText();
        assertTrue(html.contains("POST"));
        assertTrue(html.contains("orders"));
    }

    @Test
    void apiColumnRenderer_unrecognizedHeadTreatedAsPlainText() {
        // value 是 "未知接口" 之类不会撞 method 名 → 不强行套徽章
        JTable table = new JTable();
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        Component c = renderer.getTableCellRendererComponent(table,
                "(无依赖)", false, false, 0, 0);
        String html = ((javax.swing.JTextArea) c).getText();
        assertTrue(html.contains("(无依赖)"),
                "非 method 头的 value 直接原文显示，不强行加徽章");
        assertEquals(false, html.contains("background-color"),
                "无 method 的占位不应该出现徽章背景色块");
    }

    /**
     * #82：dark theme 下文字看不清的根因是 HTML 里硬编码了 #222222。
     * 这个测试保证渲染器不再写死黑字 —— body 文字色必须从 table 的主题前景里动态取。
     */
    @Test
    void apiColumnRenderer_doesNotBakeDarkOnlyTextColor() {
        JTable table = new JTable(new DefaultTableModel(new Object[]{"col"}, 0));
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        String html = ((javax.swing.JTextArea) renderer.getTableCellRendererComponent(
                table, "GET /admin/foo", false, false, 0, 0)).getText();
        assertFalse(html.contains("#222222"),
                "ApiColumnRenderer body 文字色不能用硬编码 #222222（dark theme 黑底黑字）");
    }

    /**
     * #82：占位行（空 value）必须是 italic 灰字，让用户一眼看出"这不是真接口"。
     */
    @Test
    void apiColumnRenderer_emptyValueIsItalicPlaceholder() {
        JTable table = new JTable(new DefaultTableModel(new Object[]{"col"}, 0));
        DependencyGraphDialog.ApiColumnRenderer renderer =
                new DependencyGraphDialog.ApiColumnRenderer();
        String html = ((javax.swing.JTextArea) renderer.getTableCellRendererComponent(
                table, "", false, false, 0, 0)).getText();
        assertTrue(html.contains("—"), "空 value 必须用 em-dash 占位");
        assertTrue(html.contains("italic"),
                "空 value 必须走 italic 样式，让用户区分于真实接口");
    }
}