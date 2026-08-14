package com.hronline.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;

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

    private static final String INTERACTION_FEEDBACK_ATTACHED = "__interaction_feedback_attached";
    private static final String LOADING_TEXT = "__loading_text";
    private static final String LOADING_CURSOR = "__loading_cursor";

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

    // ── 可选 accent 主题（一伦优化 #9：跟随 IDE 主题 + 2 套可选 accent）──
    /**
     * accent 主题枚举：提供 2 套可选 accent 色（默认蓝 / 翠绿），
     * 配合 {@link com.intellij.ui.JBColor} 在明暗主题下自动切换。
     * <p>用于主操作按钮（"发送" / "AI 助手"）的背景色、关键 tab 边框等强语义高亮。</p>
     */
    public enum AccentColor {
        BLUE  ("默认蓝", 0x15, 0x65, 0xC0, 0x42, 0xA5, 0xF5),
        GREEN ("翠绿",   0x2E, 0x7D, 0x32, 0x66, 0xBB, 0x6A);

        public final String displayName;
        private final int lightR, lightG, lightB;
        private final int darkR,  darkG,  darkB;

        AccentColor(String displayName,
                    int lr, int lg, int lb, int dr, int dg, int db) {
            this.displayName = displayName;
            this.lightR = lr; this.lightG = lg; this.lightB = lb;
            this.darkR  = dr; this.darkG  = dg; this.darkB  = db;
        }

        /** 返回跟随 IDE 主题的 JBColor */
        public JBColor color() {
            return new JBColor(
                    new Color(lightR, lightG, lightB),
                    new Color(darkR,  darkG,  darkB));
        }

        /** 在明暗主题下都偏白，作为主按钮文字色 */
        public JBColor onAccent() {
            return new JBColor(Color.WHITE, new Color(0xF5, 0xF5, 0xF5));
        }
    }

    /**
     * 根据名称解析 accent 主题（来自 settings.accentColor）。
     * 名称不匹配时回退到 BLUE，避免 UI 闪退。
     */
    public static AccentColor parseAccent(String name) {
        if (name == null) return AccentColor.BLUE;
        try { return AccentColor.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return AccentColor.BLUE; }
    }
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
     * <p>使用默认 BLUE accent。</p>
     */
    public static JButton primaryButton(String text, Icon icon, ActionListener listener) {
        return primaryButton(text, icon, listener, AccentColor.BLUE);
    }

    /**
     * 主操作按钮（高亮填充），accent 可由 settings.accentColor 控制。
     * <p>一轮优化 #9：跟随 settings 选择 BLUE/GREEN，明暗主题下自动切换色值。</p>
     */
    public static JButton primaryButton(String text, Icon icon, ActionListener listener, AccentColor accent) {
        JButton btn = button(text, icon, listener);
        btn.putClientProperty("JButton.buttonType", "default");
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, FONT_HINT));
        applyAccent(btn, accent);
        // 一轮优化 #12：主操作按钮自动挂上悬停/按下/禁用三态反馈
        attachInteractionFeedback(btn);
        return btn;
    }

    /**
     * 一轮优化 #9：把 accent 主题色应用到一个按钮上。
     * <p>使用 {@code setBackground}/{@code setForeground} 直接着色，
     * 跳过 LaF 渲染差异，保证明暗主题下都看得见。</p>
     */
    public static void applyAccent(JButton btn, AccentColor accent) {
        if (btn == null || accent == null) return;
        btn.setBackground(accent.color());
        btn.setForeground(accent.onAccent());
        btn.setOpaque(true);
        btn.setBorderPainted(false);
    }

    // ── 按钮交互反馈（一轮优化 #12：统一悬停/按下/加载态）──

    /**
     * 给按钮加三态视觉反馈：悬停加深底色，按下再深一档，禁用置灰。
     * <p>只对主操作按钮（已 setOpaque(true)）有效；幽灵按钮 LaF 自带反馈，不必调。</p>
     */
    public static void attachInteractionFeedback(JButton btn) {
        if (btn == null) return;
        if (Boolean.TRUE.equals(btn.getClientProperty(INTERACTION_FEEDBACK_ATTACHED))) return;
        btn.putClientProperty(INTERACTION_FEEDBACK_ATTACHED, Boolean.TRUE);

        Color base = btn.getBackground();
        Color baseForeground = btn.getForeground();
        Color hover = shift(base, 0.92f);
        Color pressed = shift(base, 0.82f);
        Color disabled = JBColor.namedColor("Button.disabledText", new Color(0x9E, 0x9E, 0x9E));

        btn.addMouseListener(new MouseInputAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (!btn.isEnabled() || !btn.isVisible()) return;
                btn.setBackground(hover);
            }
            @Override public void mouseExited(MouseEvent e) {
                if (!btn.isEnabled() || !btn.isVisible()) return;
                btn.setBackground(base);
            }
            @Override public void mousePressed(MouseEvent e) {
                if (!btn.isEnabled() || !btn.isVisible()) return;
                if (SwingUtilities.isLeftMouseButton(e)) btn.setBackground(pressed);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!btn.isEnabled() || !btn.isVisible()) return;
                btn.setBackground(btn.getMousePosition() != null ? hover : base);
            }
        });
        // 禁用态：底色变灰，文字保留可读性
        btn.addPropertyChangeListener("enabled", evt -> {
            boolean en = (boolean) evt.getNewValue();
            if (en) {
                btn.setBackground(base);
                btn.setForeground(baseForeground);
            } else {
                btn.setBackground(JBColor.namedColor("Button.background", new Color(0xEE, 0xEE, 0xEE)).darker());
                btn.setForeground(disabled);
            }
        });
    }

    /**
     * 切换按钮的"加载中"态：禁用按钮、替换文案为 prefix + 旋转指示。
     * <p>配套 {@link #endLoading(JButton, String)} 恢复。</p>
     */
    public static void startLoading(JButton btn, String prefix) {
        if (btn == null) return;
        if (btn.getClientProperty(LOADING_TEXT) == null) {
            btn.putClientProperty(LOADING_TEXT, btn.getText());
            btn.putClientProperty(LOADING_CURSOR, btn.getCursor());
        }
        btn.setText((prefix == null ? "" : prefix) + "  ⟳ …");
        btn.setEnabled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    /** 结束 loading：恢复原文本 + 启用按钮。 */
    public static void endLoading(JButton btn, String fallbackText) {
        if (btn == null) return;
        Object prev = btn.getClientProperty(LOADING_TEXT);
        Object previousCursor = btn.getClientProperty(LOADING_CURSOR);
        btn.setText(prev instanceof String ? (String) prev : (fallbackText == null ? "" : fallbackText));
        btn.setEnabled(true);
        btn.setCursor(previousCursor instanceof Cursor
                ? (Cursor) previousCursor
                : Cursor.getDefaultCursor());
        btn.putClientProperty(LOADING_TEXT, null);
        btn.putClientProperty(LOADING_CURSOR, null);
    }

    /** 把 RGB 各通道按 ratio 缩放，<1 变深，>1 变浅；clamp 到 0-255 */
    private static Color shift(Color c, float ratio) {
        if (c == null) return null;
        int r = clamp((int) (c.getRed()   * ratio));
        int g = clamp((int) (c.getGreen() * ratio));
        int b = clamp((int) (c.getBlue()  * ratio));
        return new Color(r, g, b, c.getAlpha());
    }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

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
        return cardBorder(8);
    }

    /**
     * 一伦优化 #10：参数化卡片边框，padding 灵活可调。
     * 极细描边由 LaF 决定（Darcula 自带阴影，Light 用 JBColor.border() 描边）。
     */
    public static javax.swing.border.Border cardBorder(int padding) {
        return JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(padding));
    }

    /**
     * 一伦优化 #10：参数化卡片边框（垂直/水平分开），适配"窄高/宽矮"等不同区块。
     */
    public static javax.swing.border.Border cardBorder(int vertical, int horizontal) {
        return JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(vertical, horizontal));
    }

    /**
     * 一伦优化 #10：四向 padding 边框（无描边），替代硬编码 {@code new Insets(...)}。
     */
    public static javax.swing.border.Border paddedBorder(int top, int left, int bottom, int right) {
        return JBUI.Borders.empty(top, left, bottom, right);
    }

    /**
     * 一伦优化 #10：常用内边距面板（FlowLayout），替代到处 new JPanel(new FlowLayout(...)) + setBorder。
     */
    public static JPanel paddedPanel(int top, int left, int bottom, int right) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setBorder(paddedBorder(top, left, bottom, right));
        return p;
    }

    /**
     * 一伦优化 #10：卡片面板（带描边 + 圆角渲染交由 LaF），用于 AI 状态卡、响应状态卡等区块。
     */
    public static JPanel cardPanel() {
        JPanel p = new JPanel();
        p.setBorder(cardBorder());
        return p;
    }

    /**
     * 一伦优化 #11：常用的"最小尺寸"工厂方法，避免被 splitter 压扁。
     */
    public static java.awt.Dimension minSize(int w, int h) {
        return new java.awt.Dimension(w, h);
    }

    /**
     * 一伦优化 #11：可换行的次要提示 JLabel（HTML 模式），用于长文案自适应。
     */
    public static JBLabel wrappedHint(String text) {
        // HTML 模式 + JLabel 自带换行；空 padding 由父容器控制
        JBLabel l = new JBLabel("<html><div style='width:100%;'>" + escapeHtml(text) + "</div></html>");
        UiStyle.hint(l);
        return l;
    }

    /** 极简 HTML 转义，避免提示文案里的特殊字符破坏渲染 */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
