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
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Round 7（重做）：异常自定义面板。
 * <p>同时支持：
 * <ul>
 *   <li>作为「环境 & 数据」弹窗里的 Tab 嵌入（只渲染表格 + 工具栏；保存由 dialog 的 OK 按钮统一触发，
 *       通过 {@link #commit()} 写盘）</li>
 *   <li>作为独立 JDialog 弹出（保留 OK / Cancel 按钮）</li>
 * </ul>
 *
 * <p>针对当前 API 编辑「响应 JSON 字段 → 期望值白名单」规则集；HTTP 状态码仍按 2xx 判定，
 * 本规则只补业务层。判定细节见 {@link com.hronline.service.ExceptionRuleEvaluator}。</p>
 */
public class ExceptionRulesDialog extends DialogWrapper {

    private final ExceptionRulesPanel panel;

    public ExceptionRulesDialog(@NotNull Project project, @Nullable ApiDefinition api,
                                 @Nullable String referenceResponseBody) {
        super(project);
        this.panel = new ExceptionRulesPanel(project, api, referenceResponseBody);
        setTitle("异常自定义 · " + (api == null ? "未选择接口" : api.displayLabel()));
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
     * Round 7：异常自定义面板组件 —— 可独立嵌入到任意 Dialog / JTabbedPane。
     * 顶部「自动扫描」按钮（依赖最近一次响应）+ 中部规则表格 + 底部状态提示。
     */
    public static final class ExceptionRulesPanel extends JPanel {

        private final Project project;
        private final ApiDefinition api;
        private final String referenceResponseBody;

        private final DefaultTableModel tableModel = new DefaultTableModel(
                new Object[]{"字段名", "期望值（多值逗号分隔）", "启用", "操作"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? Boolean.class : Object.class;
            }
            @Override public boolean isCellEditable(int row, int column) {
                // 操作列（第 3 列）由 renderer/mouseListener 处理，不允许直接编辑文本
                return column == 0 || column == 1 || column == 2;
            }
        };
        private final JBTable table = new JBTable(tableModel);
        private final JLabel statusLabel = new JLabel(" ");
        private JButton scanBtn;
        private JButton addBtn;
        private JButton saveBtn;

        public ExceptionRulesPanel(@NotNull Project project, @Nullable ApiDefinition api,
                                    @Nullable String referenceResponseBody) {
            super(new BorderLayout(0, 6));
            this.project = project;
            this.api = api;
            this.referenceResponseBody = referenceResponseBody;
            setBorder(JBUI.Borders.empty(8));
            add(buildHeader(), BorderLayout.NORTH);
            add(buildCenter(), BorderLayout.CENTER);
            add(buildStatusBar(), BorderLayout.SOUTH);
            configureTable();
            loadRules();
            applyEnabledState();
        }

        private JComponent buildHeader() {
            JLabel header = new JLabel(api == null
                    ? "<html><b>请先在左侧选择接口</b>，再编辑异常规则</html>"
                    : "<html>接口：<b>" + escapeHtml(api.displayLabel()) + "</b><br>"
                    + "判定流程：HTTP 状态码通过 → 跑本表规则（字段缺失或值不在白名单 = 异常）<br>"
                    + "提示：响应 JSON 顶层字段名要写对（如 <code>code</code>），期望值用英文逗号分隔多值，留空 = 仅检查存在。</html>");
            header.setBorder(JBUI.Borders.empty(0, 0, 6, 0));
            return header;
        }

        private JComponent buildCenter() {
            JPanel center = new JPanel(new BorderLayout(0, 4));
            JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            scanBtn = new JButton("自动扫描");
            scanBtn.setToolTipText(referenceResponseBody == null || referenceResponseBody.isBlank()
                    ? "需要先发起一次请求以拿到响应" : "把响应 JSON 第一级字段全部加为规则候选");
            scanBtn.addActionListener(e -> autoScanFromResponse());
            addBtn = new JButton("新增规则");
            addBtn.addActionListener(e -> tableModel.addRow(new Object[]{"", "", Boolean.TRUE, "删除"}));
            saveBtn = new JButton("保存规则");
            saveBtn.setToolTipText("立即把当前表格写回项目设置（不依赖弹窗 OK 按钮）");
            saveBtn.addActionListener(e -> commit());
            btnBar.add(scanBtn);
            btnBar.add(addBtn);
            btnBar.add(saveBtn);
            center.add(btnBar, BorderLayout.NORTH);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(JBUI.size(620, 240));
            center.add(scroll, BorderLayout.CENTER);
            return center;
        }

        private JComponent buildStatusBar() {
            // 醒目的状态条：粗体 + 内边距 + 背景色 + 加边框，作为视觉锚点
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
            if (api == null) {
                // 全禁用 + 醒目引导，避免用户在没选 API 的状态下乱点
                table.setEnabled(false);
                table.setBackground(JBColor.PanelBackground);
                if (scanBtn != null) scanBtn.setEnabled(false);
                if (addBtn != null) addBtn.setEnabled(false);
                if (saveBtn != null) saveBtn.setEnabled(false);
                statusLabel.setText("● 未选择接口：所有编辑操作已禁用，请先在左侧接口列表中选中一个 API。");
                statusLabel.setForeground(JBColor.RED);
            } else {
                table.setEnabled(true);
                if (scanBtn != null) {
                    scanBtn.setEnabled(referenceResponseBody != null && !referenceResponseBody.isBlank());
                }
                if (addBtn != null) addBtn.setEnabled(true);
                if (saveBtn != null) saveBtn.setEnabled(true);
                statusLabel.setForeground(JBColor.BLUE);
                if (statusLabel.getText() == null || statusLabel.getText().isBlank()
                        || statusLabel.getText().startsWith("● 未选择接口")) {
                    statusLabel.setText("● 当前共 " + tableModel.getRowCount() + " 条规则，编辑后请点「保存规则」");
                }
            }
        }

        private void configureTable() {
            table.setRowHeight(28);
            table.getColumnModel().getColumn(0).setPreferredWidth(140);
            table.getColumnModel().getColumn(1).setPreferredWidth(260);
            table.getColumnModel().getColumn(2).setPreferredWidth(60);
            TableColumn action = table.getColumnModel().getColumn(3);
            action.setPreferredWidth(80);

            // 删除列：renderer 渲染成按钮样式；点击由 mouseListener 直接响应，不走编辑器
            // 避免旧实现"必须先进入编辑模式再点一次才生效"的卡顿问题
            JButton deleteProto = new JButton();
            deleteProto.setMargin(JBUI.insets(0, 6, 0, 6));
            deleteProto.setOpaque(true);
            deleteProto.setBorder(UIManager.getBorder("Button.border"));
            action.setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                    deleteProto.setText("删除");
                    deleteProto.setBackground(isSelected ? tbl.getSelectionBackground() : tbl.getBackground());
                    deleteProto.setForeground(isSelected ? tbl.getSelectionForeground() : JBColor.RED);
                    return deleteProto;
                }
            });
            // 禁用单元格编辑（点击直接由 mouseListener 接管）
            action.setCellEditor(null);

            // 鼠标点击删除列直接删行（不依赖编辑模式）
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (!table.isEnabled()) return;
                    Point p = e.getPoint();
                    int row = table.rowAtPoint(p);
                    int col = table.columnAtPoint(p);
                    if (row < 0 || col != 3) return;
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
                if (e.getValue() == null || e.getValue().isJsonObject() || e.getValue().isJsonArray()) continue;
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

        /** 收集当前表格行 → List<ExceptionRule>，写回 settings。 */
        public void commit() {
            if (api == null) return;
            Map<String, List<ExceptionRule>> all = RestAutoLabSettingsState.getInstance(project).loadExceptionRules();
            List<ExceptionRule> rules = new ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                Object f = tableModel.getValueAt(i, 0);
                Object e = tableModel.getValueAt(i, 1);
                Object en = tableModel.getValueAt(i, 2);
                String fname = f == null ? "" : f.toString().trim();
                if (fname.isEmpty()) continue;
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
            statusLabel.setForeground(JBColor.BLUE);
            statusLabel.setText("● ✓ 已保存 " + rules.size() + " 条规则到项目设置（" + java.time.LocalTime.now().withNano(0) + "）");
        }

        /** 给 ApiDebuggerPanel 在打开 dialog 前拿到响应 body 用。 */
        public String getCurrentStatus() { return statusLabel.getText(); }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}