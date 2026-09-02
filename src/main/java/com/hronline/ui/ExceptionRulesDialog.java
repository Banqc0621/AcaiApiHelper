package com.hronline.ui;

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
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Round 7（重构）：异常自定义面板。
 * <p>同时支持：
 * <ul>
 *   <li>作为「环境 & 数据」弹窗里的 Tab 嵌入（只渲染表格 + 工具栏；保存由 dialog 的 OK 按钮统一触发，
 *       通过 {@link #commit()} 写盘）</li>
 *   <li>作为独立 JDialog 弹出（保留 OK / Cancel 按钮）</li>
 * </ul>
 *
 * <p><b>全局规则</b>：本面板配置的是项目级异常判定规则，对项目内所有接口生效。规则分为两类：
 * <ul>
 *   <li>HTTP 状态码白名单：自定义"哪些 HTTP 状态码算正常"（叠加在接口默认 2xx 之上）</li>
 *   <li>JSON 字段值白名单：响应 JSON 第一级字段值在白名单 = 正常（如字段 {@code code} 白名单 {@code 0,200}）</li>
 * </ul>
 * 判定细节见 {@link com.hronline.service.ExceptionRuleEvaluator}。</p>
 */
public class ExceptionRulesDialog extends DialogWrapper {

    private final ExceptionRulesPanel panel;

    public ExceptionRulesDialog(@NotNull Project project) {
        super(project);
        this.panel = new ExceptionRulesPanel(project);
        setTitle("异常自定义 · 全局规则");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return panel;
    }

    @Override
    protected void doOKAction() {
        panel.commit();
        super.doOKAction();
    }

    /** 给 {@link EnvAndDataManageDialog} 注册 Tab 时调，弹窗 OK 按钮统一触发 commit。 */
    public void commit() {
        panel.commit();
    }

    /**
     * Round 7（重构）：异常自定义面板组件 —— 可独立嵌入到任意 Dialog / JTabbedPane。
     * 顶部「新增规则 / 保存规则」按钮 + 中部规则表格 + 底部状态提示。
     * 规则对项目内所有接口生效，不挂具体接口。
     */
    public static final class ExceptionRulesPanel extends JPanel {

        private final Project project;

        private static final String[] HEADERS = {"类型", "字段名（仅 FIELD_VALUE）", "期望值（逗号分隔 = 白名单）", "启用", "操作"};
        private static final int COL_TYPE = 0;
        private static final int COL_FIELD = 1;
        private static final int COL_EXPECTED = 2;
        private static final int COL_ENABLED = 3;
        private static final int COL_ACTION = 4;

        private final DefaultTableModel tableModel = new DefaultTableModel(HEADERS, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == COL_ENABLED) return Boolean.class;
                if (columnIndex == COL_TYPE) return ExceptionRule.RuleType.class;
                return Object.class;
            }
            @Override public boolean isCellEditable(int row, int column) {
                // 操作列由 mouseListener 处理；其余可编辑
                return column != COL_ACTION;
            }
        };
        private final JBTable table = new JBTable(tableModel);
        private final JLabel statusLabel = new JLabel(" ");
        private JButton addBtn;
        private JButton saveBtn;

        public ExceptionRulesPanel(@NotNull Project project) {
            super(new BorderLayout(0, 6));
            this.project = project;
            setBorder(JBUI.Borders.empty(8));
            add(buildHeader(), BorderLayout.NORTH);
            add(buildCenter(), BorderLayout.CENTER);
            add(buildStatusBar(), BorderLayout.SOUTH);
            configureTable();
            loadRules();
            applyEnabledState();
        }

        private JComponent buildHeader() {
            JLabel header = new JLabel("<html>"
                    + "<b>异常自定义规则（全局，对所有接口生效）</b><br>"
                    + "判定流程：HTTP 通过 → 跑本表规则；任一不通过 = 异常<br>"
                    + "<b>HTTP 状态码</b>：在白名单里 = 正常；<b>字段值</b>：响应 JSON 顶层字段值在白名单 = 正常（留空 = 不限）。"
                    + "</html>");
            header.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
            return header;
        }

        private JComponent buildCenter() {
            JPanel center = new JPanel(new BorderLayout(0, 4));
            JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            addBtn = new JButton("新增规则");
            addBtn.addActionListener(e -> tableModel.addRow(new Object[]{
                    ExceptionRule.RuleType.HTTP_STATUS, "", "", Boolean.TRUE, "删除"}));
            saveBtn = new JButton("保存规则");
            saveBtn.setToolTipText("立即把当前表格写回项目设置（不依赖弹窗 OK 按钮）");
            saveBtn.addActionListener(e -> commit());
            btnBar.add(addBtn);
            btnBar.add(saveBtn);
            center.add(btnBar, BorderLayout.NORTH);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(JBUI.size(680, 260));
            center.add(scroll, BorderLayout.CENTER);
            return center;
        }

        private JComponent buildStatusBar() {
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));
            statusLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                    JBUI.Borders.empty(4, 8)));
            statusLabel.setOpaque(true);
            statusLabel.setBackground(JBColor.PanelBackground);
            statusLabel.setForeground(JBColor.BLUE);
            return statusLabel;
        }

        private void applyEnabledState() {
            table.setEnabled(true);
            if (addBtn != null) addBtn.setEnabled(true);
            if (saveBtn != null) saveBtn.setEnabled(true);
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● 当前共 " + tableModel.getRowCount() + " 条全局规则，编辑后请点「保存规则」");
        }

        private void configureTable() {
            table.setRowHeight(28);
            table.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(110);
            table.getColumnModel().getColumn(COL_FIELD).setPreferredWidth(140);
            table.getColumnModel().getColumn(COL_EXPECTED).setPreferredWidth(280);
            table.getColumnModel().getColumn(COL_ENABLED).setPreferredWidth(60);

            // 类型列：JComboBox 直接编辑
            JComboBox<ExceptionRule.RuleType> typeCombo = new JComboBox<>(ExceptionRule.RuleType.values());
            table.getColumnModel().getColumn(COL_TYPE).setCellEditor(new DefaultCellEditor(typeCombo));

            // 操作列：renderer 渲染为按钮样式；点击由 mouseListener 直接响应
            TableColumn action = table.getColumnModel().getColumn(COL_ACTION);
            action.setPreferredWidth(80);
            JButton deleteProto = new JButton();
            deleteProto.setMargin(JBUI.insets(0, 6, 0, 6));
            deleteProto.setOpaque(true);
            deleteProto.setBorder(UIManager.getBorder("Button.border"));
            action.setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    deleteProto.setText("删除");
                    deleteProto.setBackground(isSelected ? tbl.getSelectionBackground() : tbl.getBackground());
                    deleteProto.setForeground(isSelected ? tbl.getSelectionForeground() : JBColor.RED);
                    return deleteProto;
                }
            });
            action.setCellEditor(null);

            // 鼠标点击删除列直接删行（不依赖编辑模式）
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!table.isEnabled()) return;
                    Point p = e.getPoint();
                    int row = table.rowAtPoint(p);
                    int col = table.columnAtPoint(p);
                    if (row < 0 || col != COL_ACTION) return;
                    int modelRow = table.convertRowIndexToModel(row);
                    if (modelRow >= 0 && modelRow < tableModel.getRowCount()) {
                        tableModel.removeRow(modelRow);
                        statusLabel.setText("● 已删除第 " + (modelRow + 1) + " 行，记得点保存");
                    }
                }
            });
        }

        private void loadRules() {
            tableModel.setRowCount(0);
            List<ExceptionRule> rules = RestAutoLabSettingsState.getInstance(project).loadExceptionRules();
            if (rules == null) return;
            for (ExceptionRule r : rules) {
                tableModel.addRow(new Object[]{
                        r.getType(),
                        r.getFieldName(),
                        String.join(",", r.getExpectedValues()),
                        r.isEnabled(),
                        "删除"
                });
            }
        }

        /** 收集当前表格行 → List<ExceptionRule>，写回 settings。 */
        public void commit() {
            List<ExceptionRule> rules = new ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                Object typeObj = tableModel.getValueAt(i, COL_TYPE);
                Object fObj = tableModel.getValueAt(i, COL_FIELD);
                Object eObj = tableModel.getValueAt(i, COL_EXPECTED);
                Object enObj = tableModel.getValueAt(i, COL_ENABLED);

                ExceptionRule.RuleType type = typeObj instanceof ExceptionRule.RuleType
                        ? (ExceptionRule.RuleType) typeObj : ExceptionRule.RuleType.HTTP_STATUS;
                String fname = fObj == null ? "" : fObj.toString().trim();
                List<String> values = new ArrayList<>();
                if (eObj != null) {
                    for (String v : eObj.toString().split(",")) {
                        String t = v.trim();
                        if (!t.isEmpty()) values.add(t);
                    }
                }
                // HTTP_STATUS 不需要字段名；FIELD_VALUE 必须有字段名，否则跳过
                if (type == ExceptionRule.RuleType.FIELD_VALUE && fname.isEmpty()) continue;
                rules.add(new ExceptionRule(type, fname, values, Boolean.TRUE.equals(enObj)));
            }
            RestAutoLabSettingsState.getInstance(project).saveExceptionRules(rules);
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● ✓ 已保存 " + rules.size() + " 条全局规则到项目设置（" + java.time.LocalTime.now().withNano(0) + "）");
        }

        /** 给 ApiDebuggerPanel 在打开 dialog 前拿到状态文本用。 */
        public String getCurrentStatus() { return statusLabel.getText(); }
    }
}