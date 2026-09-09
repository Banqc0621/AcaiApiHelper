package com.hronline.ui;

import com.hronline.model.ExceptionRule;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
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
 * Round 7（重构）+ 一伦优化 #67：异常自定义面板。
 * <p>同时支持：
 * <ul>
 *   <li>作为「环境 & 数据」弹窗里的 Tab 嵌入（只渲染表格 + 工具栏；保存由 dialog 的 OK 按钮统一触发，
 *       通过 {@link #commit()} 写盘）</li>
 *   <li>作为独立 JDialog 弹出（保留 OK / Cancel 按钮）</li>
 * </ul>
 *
 * <p><b>全局规则</b>：本面板配置的是项目级异常判定规则，对项目内所有接口生效。规则分为两类（语义相反）：
 * <ul>
 *   <li>HTTP_VALUE（白名单）：字段名留空时校验 HTTP 状态码，填写字段名时校验响应 JSON 字段；
 *       不在白名单 = 异常</li>
 *   <li>FIELD_VALUE（告警值）：响应 JSON 字段值命中即异常（未命中 = 正常）</li>
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
        if (panel.commitRules()) super.doOKAction();
    }

    /** 给 {@link EnvAndDataManageDialog} 注册 Tab 时调，弹窗 OK 按钮统一触发 commit。 */
    public void commit() {
        panel.commit();
    }

    /**
     * Round 7（重构）：异常自定义面板组件 —— 可独立嵌入到任意 Dialog / JTabbedPane。
     * 无顶部按钮栏：规则表格的「操作」列内置「新增 / 删除」按钮；
     * 保存统一由外层弹窗的「应用 / 确定」触发 {@link #commitRules()}。
     * 规则对项目内所有接口生效，不挂具体接口。
     */
    public static final class ExceptionRulesPanel extends JPanel {

        private final Project project;

        private static final String[] HEADERS = {
            "类型", "字段名（HTTP_VALUE 可填；留空=HTTP状态码）",
            "取值（逗号分隔：HTTP_VALUE=白名单 / FIELD_VALUE=告警值）", "启用", "操作"};
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
                    + "<b>HTTP_VALUE</b>：字段名留空时校验 HTTP 状态码白名单；填写字段名时校验响应字段白名单。<br>"
                    + "<b>FIELD_VALUE</b>：响应字段命中告警值 = 失败。示例：FIELD_VALUE code=500/501 告警；HTTP_VALUE code=301 且响应 code=301 通过。"
                    + "</html>");
            header.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
            return header;
        }

        private JComponent buildCenter() {
            JPanel center = new JPanel(new BorderLayout(0, 4));
            // 无顶部按钮栏：新增/删除都在表格「操作」列内；保存走弹窗「应用」。
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(JBUI.size(680, 260));
            center.add(scroll, BorderLayout.CENTER);
            return center;
        }

        private JComponent buildStatusBar() {
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));
            statusLabel.setForeground(JBColor.BLUE);

            // 底部常驻「+ 新增规则」入口：表格里一条记录都没有时操作列没有可点的
            // 按钮行，靠它新增第一条；新增后仍统一点「应用」保存。
            JLabel addLink = new JLabel("＋ 新增规则");
            addLink.setForeground(JBColor.BLUE);
            addLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addLink.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (table.isEnabled()) addRuleRow(tableModel.getRowCount());
                }
            });

            JPanel south = new JPanel(new BorderLayout(8, 0));
            south.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                    JBUI.Borders.empty(4, 8)));
            south.setOpaque(true);
            south.setBackground(JBColor.PanelBackground);
            south.add(addLink, BorderLayout.WEST);
            south.add(statusLabel, BorderLayout.CENTER);
            return south;
        }

        private void applyEnabledState() {
            table.setEnabled(true);
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● 当前共 " + tableModel.getRowCount() + " 条全局规则；新增/编辑后点「应用」保存");
        }

        private void configureTable() {
            table.setRowHeight(28);
            Color gridColor = JBColor.namedColor("Table.gridColor",
                    new JBColor(new Color(0xB8, 0xBE, 0xC6), new Color(0x60, 0x66, 0x6E)));
            table.setShowGrid(true);
            table.setGridColor(gridColor);
            table.setIntercellSpacing(JBUI.size(1, 1));
            table.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(170);
            table.getColumnModel().getColumn(COL_FIELD).setPreferredWidth(230);
            table.getColumnModel().getColumn(COL_EXPECTED).setPreferredWidth(280);
            table.getColumnModel().getColumn(COL_ENABLED).setPreferredWidth(60);

            // 类型列：JComboBox 直接编辑
            JComboBox<ExceptionRule.RuleType> typeCombo = new JComboBox<>(ExceptionRule.RuleType.values());
            typeCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value == ExceptionRule.RuleType.HTTP_VALUE) {
                        setText("HTTP_VALUE（白名单）");
                    } else if (value == ExceptionRule.RuleType.FIELD_VALUE) {
                        setText("FIELD_VALUE（告警值）");
                    }
                    return this;
                }
            });
            table.getColumnModel().getColumn(COL_TYPE).setCellEditor(new DefaultCellEditor(typeCombo));
            table.getColumnModel().getColumn(COL_TYPE).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object value) {
                    if (value == ExceptionRule.RuleType.HTTP_VALUE) {
                        setText("HTTP_VALUE（白名单）");
                    } else if (value == ExceptionRule.RuleType.FIELD_VALUE) {
                        setText("FIELD_VALUE（告警值）");
                    } else {
                        super.setValue(value);
                    }
                }
            });

            // 操作列：渲染「新增 | 删除」文本按钮。
            // 旧实现用共享原型 JButton + FlowLayout 做命中，单元格放不下两个按钮时
            // bounds 会漂出单元格，点击无响应（删除失效）；现直接按单元格左右半区判定。
            TableColumn action = table.getColumnModel().getColumn(COL_ACTION);
            action.setPreferredWidth(110);
            action.setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                    setHorizontalAlignment(SwingConstants.CENTER);
                    setText(isSelected ? "新增 | 删除"
                            : "<html><span style='color:#1565C0'>新增</span>"
                            + " <span style='color:gray'>|</span> "
                            + "<span style='color:#C62828'>删除</span></html>");
                    return this;
                }
            });
            action.setCellEditor(null);

            // 鼠标点击操作列：左半区=新增（当前行下方插入），右半区=删除（移除当前行）
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!table.isEnabled()) return;
                    Point p = e.getPoint();
                    int row = table.rowAtPoint(p);
                    int col = table.columnAtPoint(p);
                    if (row < 0 || col != COL_ACTION) return;
                    int modelRow = table.convertRowIndexToModel(row);
                    if (modelRow < 0 || modelRow >= tableModel.getRowCount()) return;

                    Rectangle cellRect = table.getCellRect(row, col, false);
                    if (p.x < cellRect.x + cellRect.width / 2) {
                        addRuleRow(modelRow + 1);
                    } else {
                        tableModel.removeRow(modelRow);
                        statusLabel.setForeground(JBColor.BLUE);
                        statusLabel.setText("● 已删除第 " + (modelRow + 1) + " 行，点「应用」保存");
                    }
                }
            });
        }

        /** 在指定位置插入一条默认规则行（新增入口，位于表格操作列内）。 */
        private void addRuleRow(int insertAt) {
            if (!validateRules()) return;
            int row = Math.max(0, Math.min(insertAt, tableModel.getRowCount()));
            tableModel.insertRow(row, new Object[]{
                    ExceptionRule.RuleType.HTTP_VALUE, "", "200", Boolean.TRUE, ""});
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● 已新增规则，HTTP_VALUE 默认白名单 [200]，请按需修改后点「应用」保存");
        }

        private void loadRules() {
            tableModel.setRowCount(0);
            List<ExceptionRule> rules = RestAutoLabSettingsState.getInstance(project).loadExceptionRules();
            // 一伦优化 #89：首次进入且已存规则为空 → 自动注入通用默认规则并落盘，
            // 用户可立即看到合理的告警行为，也可编辑 / 删除 / 禁用。
            if (rules == null || rules.isEmpty()) {
                rules = ExceptionRule.defaultRules();
                RestAutoLabSettingsState.getInstance(project).saveExceptionRules(rules);
            }
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

        /**
         * 校验当前规则。新增和保存都会走同一套校验，避免空白字段、非法状态码
         * 或 FIELD_VALUE 缺少字段名的配置写入项目设置。
         */
        private boolean validateRules() {
            if (table.isEditing() && table.getCellEditor() != null
                    && !table.getCellEditor().stopCellEditing()) {
                return false;
            }
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                Object typeObj = tableModel.getValueAt(i, COL_TYPE);
                ExceptionRule.RuleType type = typeObj instanceof ExceptionRule.RuleType
                        ? (ExceptionRule.RuleType) typeObj : ExceptionRule.RuleType.HTTP_VALUE;
                String field = String.valueOf(tableModel.getValueAt(i, COL_FIELD) == null
                        ? "" : tableModel.getValueAt(i, COL_FIELD)).trim();
                String expected = String.valueOf(tableModel.getValueAt(i, COL_EXPECTED) == null
                        ? "" : tableModel.getValueAt(i, COL_EXPECTED)).trim();
                if (expected.isBlank()) {
                    showValidationError(i, "请填写取值（多个值用逗号分隔）");
                    return false;
                }
                List<String> values = splitExpectedValues(expected);
                if (values.isEmpty()) {
                    showValidationError(i, "取值列表不能只包含逗号或空格");
                    return false;
                }
                if (type == ExceptionRule.RuleType.FIELD_VALUE && field.isBlank()) {
                    showValidationError(i, "FIELD_VALUE 规则必须填写字段名，例如 code");
                    return false;
                }
                if (type == ExceptionRule.RuleType.HTTP_VALUE && field.isBlank()) {
                    // 字段名留空时才是 HTTP 状态码白名单，必须是 100-599 整数。
                    for (String value : values) {
                        try {
                            int code = Integer.parseInt(value);
                            if (code < 100 || code > 599) {
                                showValidationError(i, "HTTP 状态码数值必须是 100-599：" + value);
                                return false;
                            }
                        } catch (NumberFormatException ignored) {
                            showValidationError(i, "HTTP 状态码必须是整数：" + value
                                    + "（如果是 code 等响应字段，请填写字段名后再保存）");
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private List<String> splitExpectedValues(String raw) {
            List<String> values = new ArrayList<>();
            if (raw == null) return values;
            for (String value : raw.split(",")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
            return values;
        }

        private void showValidationError(int row, String message) {
            if (row >= 0 && row < tableModel.getRowCount()) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, COL_EXPECTED, true));
            }
            statusLabel.setForeground(JBColor.RED);
            statusLabel.setText("● 规则未保存：" + message);
            Messages.showErrorDialog(project,
                    "第 " + (row + 1) + " 条规则：\n" + message,
                    "异常规则校验失败");
        }

        /** 收集当前表格行 → List<ExceptionRule>，写回 settings。 */
        public boolean commitRules() {
            if (!validateRules()) return false;
            List<ExceptionRule> rules = new ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                Object typeObj = tableModel.getValueAt(i, COL_TYPE);
                Object fObj = tableModel.getValueAt(i, COL_FIELD);
                Object eObj = tableModel.getValueAt(i, COL_EXPECTED);
                Object enObj = tableModel.getValueAt(i, COL_ENABLED);

                ExceptionRule.RuleType type = typeObj instanceof ExceptionRule.RuleType
                        ? (ExceptionRule.RuleType) typeObj : ExceptionRule.RuleType.HTTP_VALUE;
                String fname = fObj == null ? "" : fObj.toString().trim();
                List<String> values = splitExpectedValues(eObj == null ? "" : eObj.toString());
                rules.add(new ExceptionRule(type, fname, values, Boolean.TRUE.equals(enObj)));
            }
            RestAutoLabSettingsState.getInstance(project).saveExceptionRules(rules);
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● ✓ 已保存 " + rules.size() + " 条全局规则到项目设置（" + java.time.LocalTime.now().withNano(0) + "）");
            return true;
        }

        /** 给 DialogWrapper / EnvAndDataManageDialog 的兼容入口。 */
        public void commit() { commitRules(); }

        /** 给 ApiDebuggerPanel 在打开 dialog 前拿到状态文本用。 */
        public String getCurrentStatus() { return statusLabel.getText(); }
    }
}