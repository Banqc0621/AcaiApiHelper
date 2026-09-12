package com.hronline.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 独立的 JSON 语法高亮文本面板。
 *
 * <ul>
 *   <li>完整 JSON 词法高亮（键名、字符串、数字、布尔、null、标点）</li>
 *   <li>滚动：Shift+滚轮水平滚动，普通滚轮垂直滚动（无字体缩放）</li>
 *   <li>右键菜单：复制 / 全选</li>
 *   <li>支持明暗主题（颜色取自 {@link UiStyle} 语义色板）</li>
 * </ul>
 */
public class JsonSyntaxPane extends JTextPane {

    private static final int DEFAULT_FONT_SIZE = (int) UiStyle.FONT_MONO;

    private final Style defaultStyle;
    private final Style keyStyle;
    private final Style stringStyle;
    private final Style numberStyle;
    private final Style booleanStyle;
    private final Style nullStyle;
    private final Style punctuationStyle;

    public JsonSyntaxPane() {
        super();
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, DEFAULT_FONT_SIZE));
        setEditable(false);
        setBackground(JBColor.namedColor("Editor.background", new Color(0xFF, 0xFF, 0xFF)));
        setBorder(JBUI.Borders.empty(4, 6));

        defaultStyle = addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, JBColor.foreground());
        StyleConstants.setFontFamily(defaultStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(defaultStyle, DEFAULT_FONT_SIZE);

        keyStyle = addStyle("key", defaultStyle);
        StyleConstants.setForeground(keyStyle, UiStyle.JSON_KEY);
        StyleConstants.setBold(keyStyle, true);

        stringStyle = addStyle("string", defaultStyle);
        StyleConstants.setForeground(stringStyle, UiStyle.JSON_STRING);

        numberStyle = addStyle("number", defaultStyle);
        StyleConstants.setForeground(numberStyle, UiStyle.JSON_NUMBER);

        booleanStyle = addStyle("boolean", defaultStyle);
        StyleConstants.setForeground(booleanStyle, UiStyle.JSON_BOOLEAN);
        StyleConstants.setBold(booleanStyle, true);

        nullStyle = addStyle("null", defaultStyle);
        StyleConstants.setForeground(nullStyle, UiStyle.JSON_NULL);
        StyleConstants.setItalic(nullStyle, true);

        punctuationStyle = addStyle("punctuation", defaultStyle);
        StyleConstants.setForeground(punctuationStyle, UiStyle.JSON_PUNCTUATION);

        initScrollSupport();
        initContextMenu();
    }

    /**
     * 关键修复：JTextPane 默认「跟随视口宽度换行」，收到长响应后内容宽度被压缩、
     * 水平滚动条永远不出现，垂直滚动条的长度计算也随之异常（上下滑动框错乱）。
     * 返回 false 让内容按自身首选尺寸布局：超长行交给水平滚动条，垂直方向才按需出现。
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        Container parent = getParent();
        if (parent instanceof JViewport) {
            return getUI().getPreferredSize(this).width <= parent.getWidth();
        }
        return super.getScrollableTracksViewportWidth();
    }

    /** 高度不跟随视口收缩：内容比视口高时保持完整高度，垂直滚动条才能正确反映全文长度。 */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** 右键菜单：复制 / 全选（无缩放、无提示项）。 */
    private void initContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("复制");
        copyItem.addActionListener(e -> copy());
        copyItem.setEnabled(false);
        addCaretListener(e -> copyItem.setEnabled(getSelectionStart() != getSelectionEnd()));
        menu.add(copyItem);

        JMenuItem selectAllItem = new JMenuItem("全选");
        selectAllItem.addActionListener(e -> selectAll());
        menu.add(selectAllItem);

        setComponentPopupMenu(menu);
    }

    /**
     * 设置文本并自动应用 JSON 语法高亮。非 JSON 内容会原样显示（不报错，不高亮）。
     */
    public void setTextAndHighlight(String text) {
        setText("");
        if (text == null || text.isEmpty()) return;

        StyledDocument doc = getStyledDocument();
        try {
            doc.insertString(0, text, defaultStyle);
        } catch (BadLocationException e) {
            return;
        }

        applyJsonHighlighting(text);
        setCaretPosition(0);
        // 强制重新布局，确保外层滚动容器拿到正确的首选尺寸（滚动条范围正常）
        revalidate();
        repaint();
    }

    // ── 滚动支持：Shift+滚轮水平滚动，普通滚轮垂直滚动（均显式处理，不依赖事件冒泡）──

    private void initScrollSupport() {
        addMouseWheelListener(e -> {
            JScrollPane scrollPane = findEnclosingScrollPane();
            if (scrollPane == null) return;
            if ((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                scrollHorizontal(scrollPane, e);
            } else {
                scrollVertical(scrollPane, e);
            }
            // 显式消费：注册了本监听器后，事件不会再可靠地冒泡给外层滚动面板，
            // 因此两个方向都手动驱动滚动条并消费事件，避免「滚轮无效」或「滚动两次」。
            e.consume();
        });
    }

    private void scrollVertical(JScrollPane scrollPane, MouseWheelEvent e) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) return;
        int direction = e.getWheelRotation(); // 负 = 向上，正 = 向下
        if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            int units = Math.max(1, e.getScrollAmount());
            bar.setValue(bar.getValue() + direction * units * bar.getUnitIncrement());
        } else {
            bar.setValue(bar.getValue() + direction * bar.getBlockIncrement());
        }
    }

    private void scrollHorizontal(JScrollPane scrollPane, MouseWheelEvent e) {
        JScrollBar bar = scrollPane.getHorizontalScrollBar();
        if (bar == null || !bar.isVisible()) return;
        int direction = e.getWheelRotation() < 0 ? -1 : 1;
        bar.setValue(bar.getValue() + direction * bar.getBlockIncrement());
    }

    private JScrollPane findEnclosingScrollPane() {
        Container parent = getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane) {
                return (JScrollPane) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    // ── JSON 语法高亮核心 ───────────────────────────────────

    /**
     * 手写词法分析器。
     * <p>处理：字符串（含转义）、数字（含负数、浮点、科学计数）、
     * true/false/null、括号与标点。键名（后跟冒号的字符串）单独着色。</p>
     */
    private void applyJsonHighlighting(String text) {
        StyledDocument doc = getStyledDocument();
        doc.setCharacterAttributes(0, text.length(), defaultStyle, true);

        int i = 0;
        int len = text.length();
        boolean inString = false;
        boolean stringIsKey = false;
        int stringStart = -1;
        char stringQuote = '"';
        boolean escape = false;

        Deque<Character> bracketStack = new ArrayDeque<>();
        boolean expectKey = true;

        while (i < len) {
            char c = text.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    i++;
                    continue;
                }
                if (c == stringQuote) {
                    if (stringIsKey) {
                        doc.setCharacterAttributes(stringStart, i - stringStart + 1, keyStyle, true);
                    } else {
                        doc.setCharacterAttributes(stringStart, i - stringStart + 1, stringStyle, true);
                    }
                    inString = false;
                    stringIsKey = false;
                    int j = i + 1;
                    while (j < len && Character.isWhitespace(text.charAt(j))) j++;
                    expectKey = !(j < len && text.charAt(j) == ':');
                    i++;
                    continue;
                }
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                stringStart = i;
                stringIsKey = expectKey;
                i++;
                continue;
            }

            if (c == '{' || c == '[') {
                bracketStack.push(c);
                doc.setCharacterAttributes(i, 1, punctuationStyle, true);
                expectKey = (c == '{');
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                if (!bracketStack.isEmpty()) bracketStack.pop();
                doc.setCharacterAttributes(i, 1, punctuationStyle, true);
                i++;
                continue;
            }
            if (c == ':') {
                doc.setCharacterAttributes(i, 1, punctuationStyle, true);
                expectKey = false;
                i++;
                continue;
            }
            if (c == ',') {
                doc.setCharacterAttributes(i, 1, punctuationStyle, true);
                if (!bracketStack.isEmpty() && bracketStack.peek() == '{') {
                    expectKey = true;
                }
                i++;
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && i + 1 < len && Character.isDigit(text.charAt(i + 1)))) {
                int numStart = i;
                if (c == '-') i++;
                while (i < len && (Character.isDigit(text.charAt(i))
                        || text.charAt(i) == '.'
                        || text.charAt(i) == 'e' || text.charAt(i) == 'E'
                        || text.charAt(i) == '+' || text.charAt(i) == '-')) {
                    i++;
                }
                doc.setCharacterAttributes(numStart, i - numStart, numberStyle, true);
                continue;
            }

            if (c == 't' && matchWord(text, i, "true")) {
                doc.setCharacterAttributes(i, 4, booleanStyle, true);
                i += 4;
                continue;
            }
            if (c == 'f' && matchWord(text, i, "false")) {
                doc.setCharacterAttributes(i, 5, booleanStyle, true);
                i += 5;
                continue;
            }
            if (c == 'n' && matchWord(text, i, "null")) {
                doc.setCharacterAttributes(i, 4, nullStyle, true);
                i += 4;
                continue;
            }

            i++;
        }
    }

    private boolean matchWord(String text, int pos, String word) {
        if (pos + word.length() > text.length()) return false;
        for (int k = 0; k < word.length(); k++) {
            if (text.charAt(pos + k) != word.charAt(k)) return false;
        }
        if (pos + word.length() < text.length()) {
            char next = text.charAt(pos + word.length());
            return !Character.isLetterOrDigit(next);
        }
        return true;
    }
}