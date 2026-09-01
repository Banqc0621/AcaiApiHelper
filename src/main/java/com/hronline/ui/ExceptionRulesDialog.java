package com.hronline.ui;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ExceptionRule;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round 7：异常自定义对话框。
 * <p>针对当前 API 编辑「响应 JSON 字段 → 期望值白名单」规则集；
 * 每条规则可单独启用/禁用。HTTP 状态码仍按 2xx 判定，本规则只补业务层。</p>
 *
 * <p>表格列：
 * <ol>
 *   <li>字段名（响应 JSON 第一级字段名）</li>
 *   <li>期望值（多个值用英文逗号分隔，留空 = 仅做存在性检查）</li>
 *   <li>启用（勾选 = 参与判定）</li>
 *   <li>删除（按钮列）</li>
 * </ol>
 * </p>
 */
public class ExceptionRulesDialog extends DialogWrapper {

    private final Project project;
    private final ApiDefinition api;
    /** 自动扫描用的"参考响应 body"，来自调用方最近一次响应；为空时「自动扫描」按钮禁用 */
    private final String referenceResponseBody;

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"字段名", "期望值（多值逗号分隔）", "启用", "操作"}, 0) {
        @Override public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 2 ? Boolean.class : Object.class;
        }
        @Override public boolean isCellEditable(int row, int column) {
            // 操作列（第 3 列）由 renderer/editor 处理，不允许直接编辑文本
            return column == 0 || column == 1 || column == 2;
        }
    };
    private final JBTable table = new JBTable(tableModel);

    public ExceptionRulesDialog(@NotNull Project project, @Nullable ApiDefinition api,
                                 @Nullable String referenceResponseBody) {
        super(project);
        this.project = project;
        this.api = api;
        this.referenceResponseBody = referenceResponseBody;
        setTitle("异常自定义 · " + (api == null ? "未选择接口" : api.displayLabel()));
        init();
        loadRules();
        configureTable();
    }

    private void configureTable() {
        table.setRowHeight(26);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        TableColumn action = table.getColumnModel().getColumn(3);
        action.setPreferredWidth(70);
        action.setCellRenderer((tbl, value, isSelected, hasFocus, row, col) -> {
            JButton btn = new JButton("删除");
            btn.setMargin(JBUI.insets(0, 6, 0, 6));
            btn.setForeground(JBColor.RED);
            btn.setEnabled(!isSelected || isSelected);
            return btn;
        });
        action.setCellEditor(new DefaultCellEditor(new JCheckBox()) {
            private final JButton btn = new JButton("删除");
            @Override public Component getTableCellEditorComponent(JTable tbl, Object value, boolean isSelected, int row, int col) {
                btn.addActionListener(e -> {
                    int modelRow = tbl.convertRowIndexToModel(row);
                    if (modelRow >= 0 && modelRow < tableModel.getRowCount()) {
                        tableModel.removeRow(modelRow);
                        fireEditingStopped();
                    }
                });
                return btn;
            }
        });
    }

    private void loadRules() {
        tableModel.setRowCount(0);
        if (api == null) return;
        Map<String, List<ExceptionRule>> all = RestAutoLabSettingsState.getInstance(project).loadExceptionRules();
        List<ExceptionRule> rules = all.get(api.uniqueKey());
        if (rules == null) return;
        for (ExceptionRule r : rules) {
            tableModel.addRow(new Object[]{
                    r.getFieldName(),
                    String.join(",", r.getExpectedValues()),
                    r.isEnabled(),
                    "删除"
            });
        }
    }

    /** 自动扫描响应 JSON 第一级字段，把每个字段作为一条新规则追加。已存在的跳过。 */
    private void autoScanFromResponse() {
        if (api == null || referenceResponseBody == null || referenceResponseBody.isBlank()) return;
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(referenceResponseBody);
        } catch (Exception ex) {
            statusLabel.setText("● 自动扫描失败：响应不是合法 JSON");
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            statusLabel.setText("● 自动扫描失败：响应不是 JSON 对象");
            return;
        }
        int added = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> e : parsed.getAsJsonObject().entrySet()) {
            String key = e.getKey();
            // 跳过嵌套对象（只扫第一级基本字段）
            if (e.getValue() == null || e.getValue().isJsonObject() || e.getValue().isJsonArray()) continue;
            // 跳过已存在
            boolean exists = false;
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (key.equals(tableModel.getValueAt(i, 0))) { exists = true; break; }
            }
            if (exists) continue;
            tableModel.addRow(new Object[]{key, "", Boolean.TRUE, "删除"});
            added++;
        }
        statusLabel.setText("● 自动扫描完成，新增 " + added + " 条规则");
    }

    private final JLabel statusLabel = new JLabel(" ");

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setPreferredSize(JBUI.size(640, 360));
        root.setBorder(JBUI.Borders.empty(8));

        JLabel header = new JLabel(api == null
                ? "<html><b>请先在左侧选择接口</b>，再打开异常自定义</html>"
                : "<html>接口：<b>" + escapeHtml(api.displayLabel()) + "</b><br>"
                + "判定流程：HTTP 状态码通过 → 跑本表规则（字段缺失或值不在白名单 = 异常）<br>"
                + "提示：响应 JSON 顶层字段名要写对（如 <code>code</code>），期望值用英文逗号分隔多值，留空 = 仅检查存在。</html>");
        header.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
        root.add(header, BorderLayout.NORTH);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton scanBtn = new JButton("自动扫描");
        scanBtn.setEnabled(api != null && referenceResponseBody != null && !referenceResponseBody.isBlank());
        scanBtn.setToolTipText(referenceResponseBody == null || referenceResponseBody.isBlank()
                ? "需要先发起一次请求以拿到响应" : "把响应 JSON 第一级字段全部加为规则候选");
        scanBtn.addActionListener(e -> autoScanFromResponse());
        JButton addBtn = new JButton("新增规则");
        addBtn.addActionListener(e -> tableModel.addRow(new Object[]{"", "", Boolean.TRUE, "删除"}));
        btnBar.add(scanBtn);
        btnBar.add(addBtn);
        root.add(btnBar, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(JBUI.size(620, 240));
        JPanel center = new JPanel(new BorderLayout());
        center.add(btnBar, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        statusLabel.setForeground(JBColor.GRAY);
        root.add(statusLabel, BorderLayout.SOUTH);
        if (api == null) {
            scanBtn.setEnabled(false);
            addBtn.setEnabled(false);
            table.setEnabled(false);
        }
        return root;
    }

    @Override
    protected void doOKAction() {
        commit();
        super.doOKAction();
    }

    /** 收集当前表格行 → List<ExceptionRule>，写回 settings。 */
    private void commit() {
        if (api == null) return;
        Map<String, List<ExceptionRule>> all = RestAutoLabSettingsState.getInstance(project).loadExceptionRules();
        List<ExceptionRule> rules = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Object f = tableModel.getValueAt(i, 0);
            Object e = tableModel.getValueAt(i, 1);
            Object en = tableModel.getValueAt(i, 2);
            String fname = f == null ? "" : f.toString().trim();
            if (fname.isEmpty()) continue; // 空字段名跳过
            List<String> values = new ArrayList<>();
            if (e != null) {
                for (String v : e.toString().split(",")) {
                    String t = v.trim();
                    if (!t.isEmpty()) values.add(t);
                }
            }
            rules.add(new ExceptionRule(fname, values, Boolean.TRUE.equals(en)));
        }
        if (rules.isEmpty()) all.remove(api.uniqueKey());
        else all.put(api.uniqueKey(), rules);
        RestAutoLabSettingsState.getInstance(project).saveExceptionRules(all);
    }

    /** 应用（写盘但不关 dialog） —— Round 7 同时提供，让用户连续编辑多接口时不用反复开关 */
    public void apply() {
        commit();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}