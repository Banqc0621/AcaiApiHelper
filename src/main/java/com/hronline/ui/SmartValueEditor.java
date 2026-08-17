package com.hronline.ui;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.Map;

/**
 * v2.0.0 智能值编辑器 - 增强版：支持文件选择、多行列 JSON 编辑器、枚举智能提示。
 *
 * <ul>
 *   <li>类型为 File/MultipartFile 或位置为 FILE 时：显示文件选择按钮，点击弹出文件选择对话框，
 *       选中后回写到参数表值列、{@code attachmentPaths} 与附件标签。</li>
 *   <li>位置为 BODY 或值疑似 JSON 时：显示展开编辑按钮，弹出多行编辑器支持自动格式化。</li>
 *   <li>根据参数名 / 类型智能枚举建议（布尔、状态、排序等）。</li>
 *   <li>普通字符串值：使用等宽字体的文本框。</li>
 * </ul>
 */
class SmartValueEditor extends DefaultCellEditor {

    private final JComboBox<String> enumCombo = new JComboBox<>();
    /** 文件参数编辑面板：按钮 + 路径显示 */
    private final JPanel filePanel = new JPanel(new BorderLayout(4, 0));
    private final JButton fileChooseBtn = new JButton("选择文件", AllIcons.Actions.Upload);
    private final JLabel fileLabel = new JLabel("（未选择）");
    /** JSON/Body 多值编辑面板：文本框 + 展开按钮 */
    private final JPanel jsonPanel = new JPanel(new BorderLayout(2, 0));
    private final JTextField jsonTextField = new JTextField();
    private final JButton jsonExpandBtn = new JButton(AllIcons.Actions.ShowAsTree);

    private final Project project;
    private final JTable paramTable;
    private final Map<String, String> attachmentPaths;
    private final Map<String, JLabel> attachmentPathLabels;
    private final Gson gson;

    private String currentValue = "";
    private int currentRow = -1;
    private JTable currentTable;
    /** 记录 getTableCellEditorComponent 实际返回的组件，用于 getCellEditorValue 判断编辑模式。
     *  注意：不能用 DefaultCellEditor.getComponent()，它返回的是构造时传入的 editorComponent，
     *  而不是 getTableCellEditorComponent 返回的动态组件。 */
    private Component currentEditorComponent;

    SmartValueEditor(Project project, JTable paramTable,
                     Map<String, String> attachmentPaths,
                     Map<String, JLabel> attachmentPathLabels,
                     Gson gson) {
        super(new JTextField());
        this.project = project;
        this.paramTable = paramTable;
        this.attachmentPaths = attachmentPaths;
        this.attachmentPathLabels = attachmentPathLabels;
        this.gson = gson;

        enumCombo.setEditable(true);
        enumCombo.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));

        fileChooseBtn.setFont(fileChooseBtn.getFont().deriveFont(Font.PLAIN, 11f));
        fileChooseBtn.setMargin(new Insets(1, 6, 1, 6));
        fileChooseBtn.setIconTextGap(4);
        fileChooseBtn.addActionListener(e -> chooseFileAction());
        filePanel.setOpaque(true);
        filePanel.add(fileChooseBtn, BorderLayout.WEST);
        filePanel.add(fileLabel, BorderLayout.CENTER);

        jsonExpandBtn.setFont(jsonExpandBtn.getFont().deriveFont(Font.PLAIN, 11f));
        jsonExpandBtn.setMargin(new Insets(1, 4, 1, 4));
        jsonExpandBtn.setToolTipText("点击展开多行编辑器（支持JSON格式化）");
        jsonExpandBtn.setIconTextGap(0);
        jsonExpandBtn.addActionListener(e -> {
            String edited = showMultilineJsonEditor(jsonTextField.getText());
            if (edited != null) {
                jsonTextField.setText(edited);
                currentValue = edited;
            }
            fireEditingStopped();
        });
        jsonPanel.setOpaque(true);
        jsonPanel.add(jsonTextField, BorderLayout.CENTER);
        jsonPanel.add(jsonExpandBtn, BorderLayout.EAST);
    }

    private void chooseFileAction() {
        FileChooserDescriptor singleFile =
                new FileChooserDescriptor(true, false, false, false, false, false);
        singleFile.setTitle("选择文件");
        VirtualFile vf = FileChooser.chooseFile(singleFile, project, null);
        if (vf != null) {
            String path = vf.getPath();
            String fileName = vf.getName();
            fileLabel.setText(fileName);
            fileLabel.setIcon(AllIcons.Actions.Upload);
            fileLabel.setIconTextGap(4);
            fileLabel.setToolTipText(path);
            currentValue = path;
            if (currentTable != null && currentRow >= 0) {
                currentTable.setValueAt(path, currentRow, 3);
            }
            if (currentTable == paramTable) {
                Object paramName = currentTable.getValueAt(currentRow, 0);
                if (paramName instanceof String name) {
                    attachmentPaths.put(name, path);
                    JLabel lbl = attachmentPathLabels.get(name);
                    if (lbl != null) {
                        lbl.setText(fileName);
                        lbl.setIcon(AllIcons.Actions.Upload);
                        lbl.setIconTextGap(4);
                        lbl.setForeground(JBColor.foreground());
                        lbl.setToolTipText(path);
                    }
                }
            }
        }
        fireEditingStopped();
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        currentTable = table;
        currentRow = row;
        String paramName = table.getValueAt(row, 0) instanceof String s ? s : "";
        String paramType = table.getValueAt(row, 1) instanceof String s ? s : "";
        String paramLoc = table.getValueAt(row, 2) instanceof String s ? s : "";
        String valStr = value != null ? value.toString() : "";
        currentValue = valStr;

        // 1. 文件类型参数
        if (isFileType(paramType) || "FILE".equals(paramLoc)) {
            if (valStr != null && !valStr.isBlank()) {
                // 兼容旧数据 / 渲染器写入的「📎 文件名」前缀，统一提取纯路径再显示文件名
                String raw = valStr.startsWith("📎") ? valStr.substring(1).trim() : valStr;
                int sep = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
                String fileName = sep >= 0 ? raw.substring(sep + 1) : raw;
                fileLabel.setText(fileName);
                fileLabel.setIcon(AllIcons.Actions.Upload);
                fileLabel.setIconTextGap(4);
                fileLabel.setToolTipText(raw);
            } else {
                fileLabel.setText("（未选择）");
                fileLabel.setIcon(null);
                fileLabel.setToolTipText(null);
            }
            fileLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
            fileLabel.setForeground(JBColor.foreground());
            currentEditorComponent = filePanel;
            return filePanel;
        }

        // 2. 枚举建议
        String[] suggestions = getEnumSuggestions(paramName, paramType);
        if (suggestions.length > 0) {
            enumCombo.removeAllItems();
            for (String s : suggestions) {
                enumCombo.addItem(s);
            }
            enumCombo.setSelectedItem(valStr);
            currentEditorComponent = enumCombo;
            return enumCombo;
        }

        // 3. BODY / 疑似 JSON
        boolean isBody = "BODY".equals(paramLoc);
        boolean looksLikeJson = valStr != null && (valStr.trim().startsWith("{") || valStr.trim().startsWith("[")
                || valStr.contains("\n"));
        if (isBody || looksLikeJson) {
            jsonTextField.setText(valStr);
            jsonTextField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            jsonTextField.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            currentEditorComponent = jsonPanel;
            return jsonPanel;
        }

        // 4. 普通文本
        JTextField field = (JTextField) super.getTableCellEditorComponent(table, value, isSelected, row, column);
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        field.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        currentEditorComponent = field;
        return field;
    }

    @Override
    public Object getCellEditorValue() {
        Component comp = currentEditorComponent;
        if (comp == filePanel) {
            return currentValue;
        } else if (comp == jsonPanel) {
            return jsonTextField.getText();
        } else if (comp == enumCombo) {
            Object sel = enumCombo.getSelectedItem();
            return sel != null ? sel.toString() : "";
        }
        return super.getCellEditorValue();
    }

    private boolean isFileType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.contains("file") || t.contains("multipart");
    }

    /**
     * 弹出多行 JSON 编辑器对话框。
     */
    private String showMultilineJsonEditor(String initial) {
        JDialog dialog = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(currentTable),
                "编辑参数值（支持JSON格式化）",
                true);
        dialog.setSize(600, 450);
        dialog.setLocationRelativeTo(currentTable);
        dialog.setLayout(new BorderLayout(8, 8));

        JBTextArea area = new JBTextArea(initial, 15, 50);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setLineWrap(false);
        area.setTabSize(2);
        JScrollPane scroll = new JBScrollPane(area);
        scroll.setBorder(JBUI.Borders.empty(4));
        dialog.add(scroll, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton fmtBtn = UiStyle.button("格式化JSON", AllIcons.Actions.PrettyPrint, ev -> {
            try {
                var elem = JsonParser.parseString(area.getText().trim());
                area.setText(gson.toJson(elem));
            } catch (Exception ex) {
                Messages.showWarningDialog(dialog, "JSON格式错误: " + ex.getMessage(), "格式化失败");
            }
        });
        final String[] result = {null};
        JButton okBtn = UiStyle.primaryButton("确定", AllIcons.Actions.Commit, ev -> {
            result[0] = area.getText();
            dialog.dispose();
        });
        JButton cancelBtn = UiStyle.button("取消", null, ev -> dialog.dispose());
        btnBar.add(fmtBtn);
        btnBar.add(okBtn);
        btnBar.add(cancelBtn);
        dialog.add(btnBar, BorderLayout.SOUTH);

        if (initial != null && !initial.isBlank()) {
            try {
                var elem = JsonParser.parseString(initial.trim());
                area.setText(gson.toJson(elem));
            } catch (Exception ignored) {
                /* keep original */
            }
        }
        area.setCaretPosition(0);
        dialog.setVisible(true);
        return result[0];
    }

    /**
     * 根据参数名和类型提供枚举建议。
     */
    private String[] getEnumSuggestions(String paramName, String paramType) {
        if (paramName == null || paramType == null) return new String[0];
        String lowerName = paramName.toLowerCase();

        if (lowerName.contains("status") || lowerName.contains("state")) {
            return new String[]{"1", "0", "-1", "true", "false"};
        }
        if (lowerName.contains("gender") || lowerName.contains("sex")) {
            return new String[]{"male", "female", "other"};
        }
        if (lowerName.contains("type") || lowerName.contains("category")) {
            return new String[]{"default", "premium", "vip", "admin", "user"};
        }
        if (lowerName.contains("sort") || lowerName.contains("order")) {
            return new String[]{"asc", "desc", "1", "-1"};
        }
        if ("Boolean".equalsIgnoreCase(paramType) || lowerName.contains("enabled") || lowerName.contains("active")) {
            return new String[]{"true", "false"};
        }
        if (lowerName.contains("payment") || lowerName.contains("pay")) {
            return new String[]{"alipay", "wechat", "credit_card", "paypal"};
        }
        if (lowerName.contains("range") || lowerName.contains("period")) {
            return new String[]{"today", "week", "month", "year"};
        }

        return new String[0];
    }
}