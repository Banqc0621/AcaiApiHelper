package com.ban.acai.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * 全局 UI 样式工具 —— 统一字体字号、按钮风格、表格观感与边框间距。
 *
 * <p>设计理念：简洁大方、一目了然。</p>
 * <ul>
 *   <li>字号略小但仍易读（基准 12 → 11，次要 11 → 10）</li>
 *   <li>按钮统一用 roundRect（圆角描边），告别「方块中间有字」的生硬感</li>
 *   <li>表格去除粗网格，仅保留行高与悬停/选中的柔和反馈，视觉更轻盈</li>
 *   <li>边框以留白为主，分隔用极细线条</li>
 * </ul>
 */
public final class UiStyle {

    private UiStyle() {}

    // ── 字号（float，供 deriveFont 使用）──
    /** 基准正文字号，略小于默认以提升信息密度 */
    public static final float FONT_BODY = 12f;
    /** 表格/列表等密集内容字号 */
    public static final float FONT_TABLE = 12f;
    /** 次要/说明文字字号 */
    public static final float FONT_HINT = 11f;
    /** 状态栏/统计等辅助信息字号 */
    public static final float FONT_TINY = 10f;
    /** 章节标题字号 */
    public static final float FONT_SECTION = 13f;
    /** 等宽代码字号 */
    public static final float FONT_MONO = 12f;

    // ── 语义色板（v2.0.0：明暗主题双值）──
    /** 主色（链接 / 键名 / 主操作） */
    public static final JBColor COLOR_PRIMARY = new JBColor(
            new Color(0x15, 0x65, 0xC0),
            new Color(0x42, 0xA5, 0xF5)
    );
    /** JSON 键名色 */
    public static final JBColor JSON_KEY = new JBColor(
            new Color(0x15, 0x65, 0xC0),
            new Color(0x7E, 0xB8, 0xFF)
    );
    /** JSON 字符串值色 */
    public static final JBColor JSON_STRING = new JBColor(
            new Color(0x2E, 0x7D, 0x32),
            new Color(0xA5, 0xD6, 0xA7)
    );
    /** JSON 数字色 */
    public static final JBColor JSON_NUMBER = new JBColor(
            new Color(0xED, 0x6C, 0x02),
            new Color(0xFF, 0xCC, 0x80)
    );
    /** JSON 布尔色 */
    public static final JBColor JSON_BOOLEAN = new JBColor(
            new Color(0x6B, 0x21, 0xA8),
            new Color(0xCE, 0x93, 0xD8)
    );
    /** JSON null 色彩 */
    public static final JBColor JSON_NULL = new JBColor(
            new Color(0x75, 0x75, 0x75),
            new Color(0xBD, 0xBD, 0xBD)
    );
    /** JSON 标点（括号 / 冒号 / 逗号）色 */
    public static final JBColor JSON_PUNCTUATION = new JBColor(
            new Color(0x55, 0x55, 0x55),
            new Color(0xB0, 0xBE, 0xC5)
    );

    // ── 按钮 ──

    /**
     * 创建统一风格的小型操作按钮（圆角描边 + 图标 + 文字）。
     * 替代旧版 iconButton 的 square 风格，整体更优雅。
     */
    public static JButton button(String text, Icon icon, ActionListener listener) {
        JButton btn = new JButton(text, icon);
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.putClientProperty("JButton.minimumWidth", 0);
        btn.setMargin(new Insets(2, 8, 2, 8));
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, FONT_HINT));
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setIconTextGap(4);
        if (listener != null) btn.addActionListener(listener);
        return btn;
    }

    /**
     * 主操作按钮（高亮填充），用于「发送」「批量测试」等关键动作。
     */
    public static JButton primaryButton(String text, Icon icon, ActionListener listener) {
        JButton btn = button(text, icon, listener);
        btn.putClientProperty("JButton.buttonType", "default");
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, FONT_HINT));
        return btn;
    }

    /**
     * 幽灵按钮（无边框无填充），用于工具栏次级操作，悬停时仅靠底色反馈。
     */
    public static JButton ghostButton(String text, Icon icon, ActionListener listener) {
        JButton btn = new JButton(text, icon);
        btn.putClientProperty("JButton.buttonType", "borderless");
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, FONT_HINT));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setIconTextGap(4);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (listener != null) btn.addActionListener(listener);
        return btn;
    }

    // ── 表格 ──

    /**
     * 应用统一的表格风格：隐藏粗网格、增大行高、表头加粗、柔和选中色。
     */
    public static void styleTable(JTable table) {
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(table.getFont().deriveFont(Font.PLAIN, FONT_TABLE));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, FONT_HINT));
        // 不显示网格线，仅靠行高与选中色区分行，视觉更轻盈
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setSelectionBackground(JBColor.namedColor("Table.stripeColor", new Color(245, 246, 247)));
    }

    // ── 边框 / 留白 ──

    /** 标准内容区内边距 */
    public static javax.swing.border.Border contentBorder() {
        return JBUI.Borders.empty(8);
    }

    /** 卡片/区块的优雅边框：极细描边 + 适度留白 */
    public static javax.swing.border.Border cardBorder() {
        return JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8));
    }

    /** 顶部细分隔线 */
    public static javax.swing.border.Border topDivider() {
        return JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6));
    }

    /** 给 JLabel 设置次要文字风格（灰色小字） */
    public static void hint(JLabel label) {
        label.setFont(label.getFont().deriveFont(Font.PLAIN, FONT_HINT));
        label.setForeground(JBColor.GRAY);
    }
}