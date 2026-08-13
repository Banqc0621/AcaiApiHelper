

package com.hronline.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 独立的 JSON 语法高亮文本面板。
 *
 * <p>v2.0 增强特性：</p>
 * <ul>
 *   <li>完整 JSON 词法高亮（键名、字符串、数字、布尔、null、标点）</li>
 *   <li>Ctrl + 鼠标滚轮 / Ctrl+± 缩放字体（10–24px）</li>
 *   <li>右键弹出菜单（复制 / 全选 / 格式化 / 字体大小）</li>
 *   <li>支持明暗主题（颜色取自 {@link UiStyle} 语义色板）</li>
 * </ul>
 */
public class JsonSyntaxPane extends JTextPane {

    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 24;
    private static final int DEFAULT_FONT_SIZE = (int) UiStyle.FONT_MONO;
    private static final float ZOOM_STEP = 1f;

    private final Style defaultStyle;
    private final Style keyStyle;
    private final Style stringStyle;
    private final Style numberStyle;
    private final Style booleanStyle;
    private final Style nullStyle;
    private final Style punctuationStyle;

    private float currentFontSize;
    private JPopupMenu contextMenu;

    public JsonSyntaxPane() {
        super();
        currentFontSize = DEFAULT_FONT_SIZE;
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) currentFontSize));
        setEditable(false);
        setBackground(JBColor.namedColor("Editor.background", new Color(0xFF, 0xFF, 0xFF)));
        setBorder(JBUI.Borders.empty(4, 6));

        defaultStyle = addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, JBColor.foreground());
        StyleConstants.setFontFamily(defaultStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(defaultStyle, (int) currentFontSize);

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

        initContextMenu();
        initZoomSupport();
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
    }

    /** 设置字体大小（限定在 MIN-MAX 范围内） */
    public void setFontSize(float size) {
        float clamped = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
        if (Math.abs(clamped - currentFontSize) < 0.1f) return;
        currentFontSize = clamped;
        Font newFont = new Font(Font.MONOSPACED, Font.PLAIN, (int) clamped);
        setFont(newFont);
        for (Style s : new Style[]{defaultStyle, keyStyle, stringStyle, numberStyle, booleanStyle, nullStyle, punctuationStyle}) {
            StyleConstants.setFontSize(s, (int) clamped);
        }
        String current = getText();
        if (current != null && !current.isEmpty()) {
            applyJsonHighlighting(current);
        }
    }

    public float getCurrentFontSize() {
        return currentFontSize;
    }

    public void zoomIn() {
        setFontSize(currentFontSize + ZOOM_STEP);
    }

    public void zoomOut() {
        setFontSize(currentFontSize - ZOOM_STEP);
    }

    public void zoomReset() {
        setFontSize(DEFAULT_FONT_SIZE);
    }

    // ── 右键菜单 ────────────────────────────────────────────

    private void initContextMenu() {
        contextMenu = new JPopupMenu();

        JMenuItem copyItem = new JMenuItem("复制", AllIcons.Actions.Copy);
        copyItem.addActionListener(e -> copySelectedText());

        JMenuItem copyAllItem = new JMenuItem("复制全部", AllIcons.Actions.Copy);
        copyAllItem.addActionListener(e -> {
            StringSelection sel = new StringSelection(getText());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });

        JMenuItem selectAllItem = new JMenuItem("全选", AllIcons.Actions.Selectall);
        selectAllItem.addActionListener(e -> selectAll());

        contextMenu.addSeparator();

        JMenuItem zoomInItem = new JMenuItem("放大字体  (+)", AllIcons.Graph.ZoomIn);
        zoomInItem.addActionListener(e -> zoomIn());

        JMenuItem zoomOutItem = new JMenuItem("缩小字体  (-)", AllIcons.Graph.ZoomOut);
        zoomOutItem.addActionListener(e -> zoomOut());

        JMenuItem zoomResetItem = new JMenuItem("重置字体", AllIcons.Actions.Refresh);
        zoomResetItem.addActionListener(e -> zoomReset());

        contextMenu.add(copyItem);
        contextMenu.add(copyAllItem);
        contextMenu.add(selectAllItem);
        contextMenu.addSeparator();
        contextMenu.add(zoomInItem);
        contextMenu.add(zoomOutItem);
        contextMenu.add(zoomResetItem);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);
            }

            private void showMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    contextMenu.show(JsonSyntaxPane.this, e.getX(), e.getY());
                }
            }
        });
    }

    private void copySelectedText() {
        String selected = getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            StringSelection sel = new StringSelection(selected);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        }
    }

    // ── Ctrl+滚轮 缩放 ──────────────────────────────────────

    private void initZoomSupport() {
        addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                if (e.getWheelRotation() < 0) {
                    zoomIn();
                } else {
                    zoomOut();
                }
                e.consume();
            }
        });

        InputMap im = getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "zoomReset");

        am.put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomIn();
            }
        });
        am.put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomOut();
            }
        });
        am.put("zoomReset", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomReset();
            }
        });
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