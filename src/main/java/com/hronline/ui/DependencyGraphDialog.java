package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * 依赖关系配置对话框 - 展示自动检测到的依赖关系，支持用户确认/编辑/删除/添加
 *
 * <p>表格列：上游接口 | 响应字段 | 下游接口 | 目标参数</p>
 */
public class DependencyGraphDialog extends DialogWrapper {

    private final Project project;
    private final List<ApiDefinition> apis;
    private List<ApiDependency> dependencies;

    private DefaultTableModel tableModel;
    private JBTable table;

    /** key = uniqueKey, value = 短接口名称，用于表格展示 */
    private final Map<String, String> labelByKey = new LinkedHashMap<>();

    public DependencyGraphDialog(Project project, List<ApiDefinition> apis,
                                 List<ApiDependency> dependencies) {
        this(project, apis, dependencies, "API 依赖链配置");
    }

    /**
     * 构造依赖配置窗口。调用方传入的 {@code apis} 顺序会原样展示，
     * 收藏夹场景因此可以把文件夹中的接口顺序带入配置页面。
     */
    public DependencyGraphDialog(Project project, List<ApiDefinition> apis,
                                 List<ApiDependency> dependencies, String title) {
        super(project);
        this.project = project;
        this.apis = apis == null ? Collections.emptyList() : new ArrayList<>(apis);
        this.dependencies = new ArrayList<>();
        if (dependencies != null) for (ApiDependency dep : dependencies) {
            if (dep == null) continue;
            ApiDependency copy = new ApiDependency(dep.getProducerKey(), dep.getConsumerKey(), dep.getDetectionType());
            if (dep.getMappings() != null) for (ApiDependency.ValueMapping m : dep.getMappings()) {
                if (m != null) {
                    copy.getMappings().add(new ApiDependency.ValueMapping(m.getSourcePath(), m.getTargetParam()));
                }
            }
            this.dependencies.add(copy);
        }
        labelByKey.putAll(buildDisplayLabels(this.apis));
        // 兼容旧配置中暂时找不到接口定义的依赖边：仍保留关系，但只显示 key 的最后路径段，
        // 避免把完整 METHOD|URL 泄露到界面；接口重新扫描后会通过 key 正常恢复名称。
        for (ApiDependency dep : this.dependencies) {
            if (dep == null) continue;
            ensureDependencyLabel(dep.getProducerKey());
            ensureDependencyLabel(dep.getConsumerKey());
        }
        setTitle(title == null || title.isBlank() ? "API 依赖链配置" : title);
        init();
    }

    /**
     * 按收藏夹中的接口顺序创建相邻依赖边。边暂不带映射，
     * 用户可在表格中逐行补充多个“响应路径 → 目标参数”映射。
     */
    public static List<ApiDependency> createSequentialDependencies(List<ApiDefinition> orderedApis) {
        List<ApiDependency> result = new ArrayList<>();
        if (orderedApis == null || orderedApis.size() < 2) return result;
        String previousKey = null;
        Set<String> seenKeys = new HashSet<>();
        for (ApiDefinition api : orderedApis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()) continue;
            if (!seenKeys.add(api.uniqueKey())) continue;
            if (previousKey != null && !previousKey.equals(api.uniqueKey())) {
                result.add(new ApiDependency(previousKey, api.uniqueKey(), "FOLDER_ORDER"));
            }
            previousKey = api.uniqueKey();
        }
        return result;
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(820, 520));

        // 说明
        JBLabel hint = new JBLabel(
                "<html>接口顺序来自当前收藏夹；执行时按依赖边拓扑排序。<br>" +
                "可编辑路径、删除误检项或手动添加依赖；同一对接口允许配置多个字段映射。</html>");
        hint.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(hint, BorderLayout.NORTH);
        DefaultListModel<String> orderModel = new DefaultListModel<>();
        for (int i = 0; i < apis.size(); i++) {
            ApiDefinition api = apis.get(i);
            if (api != null) orderModel.addElement((i + 1) + ". " + displayLabelForApi(api));
        }
        JList<String> orderList = new JList<>(orderModel);
        orderList.setFocusable(false);
        orderList.setVisibleRowCount(Math.min(4, Math.max(1, orderModel.size())));
        orderList.setBorder(JBUI.Borders.empty(2, 6));
        JBScrollPane orderScroll = new JBScrollPane(orderList);
        orderScroll.setBorder(BorderFactory.createTitledBorder("收藏夹接口顺序"));
        orderScroll.setPreferredSize(JBUI.size(820, orderModel.isEmpty() ? 48 : 108));
        top.add(orderScroll, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        // 表格：接口列使用下拉框，字段列使用可编辑下拉框；点击“添加依赖”直接新增空白记录。
        String[] columns = {"上游接口", "响应字段", "下游接口", "目标参数"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return true;
            }
        };
        table = new JBTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int column = columnAtPoint(event.getPoint());
                if (row < 0 || column < 0) return null;
                int modelRow = convertRowIndexToModel(row);
                int modelColumn = convertColumnIndexToModel(column);
                Object value = getModel().getValueAt(modelRow, modelColumn);
                String text = value == null ? "" : String.valueOf(value).trim();
                return text.length() > 20 ? text : null;
            }

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                // 根据当前列宽计算换行后的首选高度，避免长响应字段/目标参数被固定行高截断。
                // 上限保留在 120px，超长内容仍可通过悬浮提示查看完整值。
                if (component instanceof JTextArea area) {
                    int width = Math.max(1, getColumnModel().getColumn(column).getWidth());
                    area.setSize(new Dimension(width, 1000));
                    int preferred = Math.max(42, Math.min(120, area.getPreferredSize().height + 2));
                    // 多列逐个渲染时只增不减，避免后续短列把长字段列计算出的高度覆盖掉。
                    if (preferred > getRowHeight(row)) setRowHeight(row, preferred);
                }
                return component;
            }
        };
        // 注册 ToolTipManager；getToolTipText(MouseEvent) 会按单元格返回完整长文本。
        table.setToolTipText("");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(42);
        table.setIntercellSpacing(new Dimension(JBUI.scale(8), JBUI.scale(4)));
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(190));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(310));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(190));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(280));
        for (int i = 0; i < 4; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(new WrappingCellRenderer());
        }
        table.getColumnModel().getColumn(0).setCellEditor(new ApiCellEditor());
        table.getColumnModel().getColumn(1).setCellEditor(new MappingCellEditor(true));
        table.getColumnModel().getColumn(2).setCellEditor(new ApiCellEditor());
        table.getColumnModel().getColumn(3).setCellEditor(new MappingCellEditor(false));

        fillTable();

        JBScrollPane scrollPane = new JBScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 按钮栏
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = UiStyle.primaryButton("添加依赖", AllIcons.General.Add, e -> addDependency());
        JButton deleteBtn = UiStyle.button("删除选中行", AllIcons.General.Remove, e -> deleteSelectedRow());
        buttonBar.add(addBtn);
        buttonBar.add(deleteBtn);

        panel.add(buttonBar, BorderLayout.SOUTH);

        return panel;
    }

    private void fillTable() {
        tableModel.setRowCount(0);
        for (ApiDependency dep : dependencies) {
            String producerLabel = labelByKey.getOrDefault(dep.getProducerKey(), dep.getProducerKey());
            String consumerLabel = labelByKey.getOrDefault(dep.getConsumerKey(), dep.getConsumerKey());
            if (dep.getMappings() == null || dep.getMappings().isEmpty()) {
                // 空映射边表达“顺序关系”，不能被渲染成无依赖占位行后丢失。
                tableModel.addRow(new Object[]{producerLabel, "", consumerLabel, ""});
            } else {
                for (ApiDependency.ValueMapping m : dep.getMappings()) {
                    if (m == null) continue;
                    tableModel.addRow(new Object[]{
                            producerLabel,
                            m.getSourcePath() == null ? "" : m.getSourcePath(),
                            consumerLabel,
                            m.getTargetParam() == null ? "" : m.getTargetParam()
                    });
                }
            }
        }
        if (tableModel.getRowCount() == 0) {
            tableModel.addRow(new Object[]{"(无依赖)", "", "", ""});
        }
    }

    private void addDependency() {
        if (apis.size() < 2) {
            Messages.showInfoMessage(project, "至少需要 2 个接口才能添加依赖", "提示");
            return;
        }
        stopCellEditing();
        // 新增依赖不再打断用户连续配置流程：直接创建一行空白记录，
        // 上游/下游接口通过表格下拉框选择，字段列则支持下拉选择或手动输入。
        // 这样同一对接口可以连续新增多行，分别配置多个字段映射。
        if (tableModel.getRowCount() == 1 && "(无依赖)".equals(cellText(0, 0))) {
            tableModel.removeRow(0);
        }
        tableModel.addRow(new Object[]{"", "", "", ""});
        int row = tableModel.getRowCount() - 1;
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        SwingUtilities.invokeLater(() -> {
            if (table.editCellAt(row, 0)) {
                Component editor = table.getEditorComponent();
                if (editor != null) editor.requestFocusInWindow();
            }
        });
    }

    private void deleteSelectedRow() {
        stopCellEditing();
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showWarningDialog(project, "请先选中一行", "删除");
            return;
        }

        row = table.convertRowIndexToModel(row);
        // 表格是编辑期间的单一事实来源：直接删除当前行，避免用户刚修改接口/字段后
        // 又从尚未同步的 dependencies 旧快照重绘，导致编辑内容被恢复。
        tableModel.removeRow(row);
        if (tableModel.getRowCount() == 0) {
            tableModel.addRow(new Object[]{"(无依赖)", "", "", ""});
        }
    }

    private void stopCellEditing() {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private String findKeyByLabel(String label) {
        for (Map.Entry<String, String> e : labelByKey.entrySet()) {
            if (e.getValue().equals(label)) return e.getKey();
        }
        return null;
    }

    private ApiDefinition apiByKey(String key) {
        if (key == null) return null;
        for (ApiDefinition api : apis) {
            if (api != null && key.equals(api.uniqueKey())) return api;
        }
        return null;
    }

    /**
     * 将编辑后的表格内容同步回 dependencies 列表。
     */
    private void syncFromTable() {
        // DialogWrapper 的 OK 动作可能在表格编辑器仍处于激活状态时触发；
        // 必须先把当前正在编辑的单元格值写入 model，否则 dialog dispose 后
        // tableModel 里读到的会是旧值（用户最后输入的响应字段/目标参数会丢）。
        flushActiveCellEditor();

        List<String[]> rows = new ArrayList<>(tableModel.getRowCount());
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            rows.add(new String[]{
                    cellText(r, 0), cellText(r, 1), cellText(r, 2), cellText(r, 3)
            });
        }
        this.dependencies = rebuildFromRows(this.dependencies, rows, this.labelByKey);
    }

    /**
     * 主动把当前激活的 cellEditor 值写回 model。比 {@link #stopCellEditing()}
     * 更可靠——后者依赖 AbstractCellEditor 的标准 editingStopped 回调，
     * 但 DefaultCellEditor + JComboBox 在某些焦点路径下不会触发 setValueAt。
     */
    private void flushActiveCellEditor() {
        if (table == null) return;
        if (!table.isEditing()) {
            stopCellEditing();
            return;
        }
        TableCellEditor editor = table.getCellEditor();
        if (editor == null) {
            stopCellEditing();
            return;
        }
        try {
            Object value = editor.getCellEditorValue();
            int row = table.getEditingRow();
            int column = table.getEditingColumn();
            if (row >= 0 && column >= 0 && value != null) {
                tableModel.setValueAt(value, row, column);
            }
        } catch (Exception ignored) {
            // 兜底：即使编辑器读失败，stopCellEditing 仍尝试关闭激活态
        }
        try {
            editor.stopCellEditing();
        } catch (Exception ignored) {
        }
    }

    /**
     * 把表格行重建为 ApiDependency 列表。抽出来的纯函数，单测可直接覆盖。
     * <p>输入行格式：每行 [producerLabel, sourcePath, consumerLabel, targetParam]，
     * 对应表头「上游接口 | 响应字段 | 下游接口 | 目标参数」。
     * {@code labelByKey} 是 uniqueKey → 短显示名的映射，用于反向解析。</p>
     *
     * <p>规则：
     * <ul>
     *   <li>{@code (无依赖)} 占位行跳过</li>
     *   <li>producer/consumer label 必须在 labelByKey 里反向解析，否则跳过</li>
     *   <li>producer == consumer 跳过（自环）</li>
     *   <li>两个字段都填 → 加入 mapping；同一对接口允许多个 mapping，自动去重</li>
     *   <li>两个字段都空 + 是原 dependencies 里已有 → 保留这条顺序边</li>
     *   <li>字段半填（只有一个非空）→ 跳过，视为未完成</li>
     * </ul>
     */
    static List<ApiDependency> rebuildFromRows(List<ApiDependency> original,
                                               List<String[]> rows,
                                               Map<String, String> labelByKey) {
        if (rows == null) rows = Collections.emptyList();
        Map<String, String> labelByKeySnapshot = labelByKey == null
                ? Collections.emptyMap() : labelByKey;

        Map<String, ApiDependency> byKey = new LinkedHashMap<>();
        Set<String> originalKeys = new HashSet<>();
        Map<String, String> originalDetectionTypes = new HashMap<>();
        if (original != null) {
            for (ApiDependency dep : original) {
                if (dep == null || dep.getProducerKey() == null || dep.getConsumerKey() == null) continue;
                String key = dep.getProducerKey() + "->" + dep.getConsumerKey();
                originalKeys.add(key);
                originalDetectionTypes.put(key, dep.getDetectionType());
            }
        }

        for (String[] row : rows) {
            if (row == null || row.length < 4) continue;
            String producerLabel = row[0] == null ? "" : row[0].trim();
            String sourcePath = row[1] == null ? "" : row[1].trim();
            String consumerLabel = row[2] == null ? "" : row[2].trim();
            String targetParam = row[3] == null ? "" : row[3].trim();

            if ("(无依赖)".equals(producerLabel)) continue;

            String producerKey = findKeyByLabelInMap(labelByKeySnapshot, producerLabel);
            String consumerKey = findKeyByLabelInMap(labelByKeySnapshot, consumerLabel);
            if (producerKey == null || consumerKey == null) continue;
            if (producerKey.equals(consumerKey)) continue;

            String key = producerKey + "->" + consumerKey;
            // 空映射边保留接口顺序；新建空白行在字段尚未填写完整前不落盘。
            if (sourcePath.isBlank() || targetParam.isBlank()) {
                if (sourcePath.isBlank() && targetParam.isBlank() && originalKeys.contains(key)) {
                    byKey.computeIfAbsent(key, k -> new ApiDependency(
                            producerKey, consumerKey,
                            originalDetectionTypes.getOrDefault(key, "MANUAL")));
                }
                continue;
            }
            ApiDependency dep = byKey.computeIfAbsent(key, k -> new ApiDependency(
                    producerKey, consumerKey,
                    originalDetectionTypes.getOrDefault(key, "MANUAL")));
            // 避免重复 mapping
            boolean exists = dep.getMappings().stream()
                    .anyMatch(m -> m != null
                            && Objects.equals(m.getSourcePath(), sourcePath)
                            && Objects.equals(m.getTargetParam(), targetParam));
            if (!exists) {
                dep.getMappings().add(new ApiDependency.ValueMapping(sourcePath, targetParam));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** 静态版反向解析，避免 dialog 单例在多线程测试里泄漏。 */
    private static String findKeyByLabelInMap(Map<String, String> labelByKey, String label) {
        if (label == null || label.isBlank() || labelByKey == null || labelByKey.isEmpty()) return null;
        for (Map.Entry<String, String> e : labelByKey.entrySet()) {
            if (label.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    private String cellText(int row, int column) {
        Object value = tableModel.getValueAt(row, column);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 接口列编辑器：仅显示当前窗口内的短接口名称，避免输入无法解析的未知节点。 */
    private final class ApiCellEditor extends DefaultCellEditor {
        private final JComboBox<String> combo;

        private ApiCellEditor() {
            this(new JComboBox<>());
        }

        private ApiCellEditor(JComboBox<String> combo) {
            super(combo);
            this.combo = combo;
            // 接口必须从当前收藏夹候选中选择，避免手动输入无法反向解析到 uniqueKey。
            combo.setEditable(false);
            combo.setToolTipText("请从接口列表选择上游或下游接口");
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                      int row, int column) {
            combo.removeAllItems();
            combo.addItem("");
            for (ApiDefinition api : apis) {
                String label = displayLabelForApi(api);
                if (label != null && !label.isBlank() && !containsComboItem(label)) {
                    combo.addItem(label);
                }
            }
            String current = value == null ? "" : String.valueOf(value).trim();
            if (!current.isBlank() && !containsComboItem(current)) combo.addItem(current);
            combo.setSelectedItem(current);
            return combo;
        }

        private boolean containsComboItem(String value) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (Objects.equals(combo.getItemAt(i), value)) return true;
            }
            return false;
        }

        @Override
        public Object getCellEditorValue() {
            Object item = combo.getSelectedItem();
            return item == null ? "" : String.valueOf(item).trim();
        }
    }

    /**
     * 长文本单元格渲染器。表格保留水平滚动，并通过换行和 tooltip 同时保证可读性，
     * 避免响应字段或目标参数被列宽截断后无法查看。
     */
    private static final class WrappingCellRenderer extends JTextArea implements TableCellRenderer {
        private WrappingCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setEditable(false);
            setFocusable(false);
            setBorder(JBUI.Borders.empty(4, 6));
            setMargin(JBUI.insets(2, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            String text = value == null ? "" : String.valueOf(value);
            setText(text);
            setFont(table.getFont());
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
            }
            Color focusColor = UIManager.getColor("Table.focusCellHighlightBorder");
            if (hasFocus && focusColor != null) {
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(focusColor, 2),
                        JBUI.Borders.empty(2, 4)));
            } else {
                setBorder(JBUI.Borders.empty(4, 6));
            }
            setToolTipText(text.trim().length() > 20 ? text : null);
            return this;
        }
    }

    /** 表格中仅显示接口名称；名称为空时退化为 URL 最后一级。 */
    static String shortApiLabel(ApiDefinition api) {
        if (api == null) return "";
        if (api.getName() != null && !api.getName().isBlank()) return api.getName().trim();
        String url = api.getUrl() == null ? "" : api.getUrl().trim();
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int slash = url.lastIndexOf('/');
        String last = slash >= 0 ? url.substring(slash + 1) : url;
        if (last.startsWith("{") && last.endsWith("}")) last = last.substring(1, last.length() - 1);
        return last.isBlank() ? (url.isBlank() ? "未命名接口" : url) : last;
    }

    /**
     * 为接口生成短显示名，并保证同一窗口内每个 uniqueKey 都能反向解析。
     * <p>正常情况只显示接口名称；名称冲突时先补充 HTTP 方法，若方法仍相同再追加
     * 序号。这样既不回退到完整 URL，又不会因为两个同名同方法接口而把映射写到错误节点。</p>
     */
    static Map<String, String> buildDisplayLabels(List<ApiDefinition> apis) {
        Map<String, String> result = new LinkedHashMap<>();
        if (apis == null || apis.isEmpty()) return result;

        Map<String, Integer> shortNameCounts = new HashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()) continue;
            shortNameCounts.merge(shortApiLabel(api), 1, Integer::sum);
        }

        Set<String> usedLabels = new HashSet<>();
        Map<String, Integer> nextSuffixByLabel = new HashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()) continue;
            // 同一 uniqueKey 在扫描结果中偶尔会重复；表格只能映射到一个稳定节点，保留首次出现项。
            if (result.containsKey(api.uniqueKey())) continue;

            String shortName = shortApiLabel(api);
            String label = shortName;
            if (shortNameCounts.getOrDefault(shortName, 0) > 1) {
                String method = api.getHttpMethod() == null || api.getHttpMethod().isBlank()
                        ? "API" : api.getHttpMethod().trim().toUpperCase(Locale.ROOT);
                label = "[" + method + "] " + shortName;
            }

            if (usedLabels.contains(label)) {
                int suffix = nextSuffixByLabel.getOrDefault(label, 2);
                String candidate;
                do {
                    candidate = label + " (" + suffix++ + ")";
                } while (usedLabels.contains(candidate));
                nextSuffixByLabel.put(label, suffix);
                label = candidate;
            }
            usedLabels.add(label);
            result.put(api.uniqueKey(), label);
        }
        return result;
    }

    private String displayLabelForApi(ApiDefinition api) {
        if (api == null) return "";
        return labelByKey.getOrDefault(api.uniqueKey(), shortApiLabel(api));
    }

    private void ensureDependencyLabel(String key) {
        if (key == null || key.isBlank() || labelByKey.containsKey(key)) return;
        String base = shortDependencyKeyLabel(key);
        if (base.isBlank()) base = key;
        String label = base;
        if (labelByKey.containsValue(label)) {
            String method = key.contains("|") ? key.substring(0, key.indexOf('|')).trim() : "API";
            if (method.isBlank()) method = "API";
            label = "[" + method.toUpperCase(Locale.ROOT) + "] " + base;
        }
        int suffix = 2;
        String candidate = label;
        while (labelByKey.containsValue(candidate)) candidate = label + " (" + suffix++ + ")";
        labelByKey.put(key, candidate);
    }

    /** 从 METHOD|/path/to/api 形式的旧 key 中提取最后接口名称。 */
    static String shortDependencyKeyLabel(String key) {
        if (key == null || key.isBlank()) return "";
        int separator = key.indexOf('|');
        String url = separator >= 0 ? key.substring(separator + 1).trim() : key.trim();
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int slash = url.lastIndexOf('/');
        String last = slash >= 0 ? url.substring(slash + 1) : url;
        if (last.startsWith("{") && last.endsWith("}")) last = last.substring(1, last.length() - 1);
        return last.isBlank() ? (url.isBlank() ? "未命名接口" : url) : last;
    }

    /** 检测方式内部枚举到中文显示文本的映射。 */
    static String detectionTypeDisplay(String detectionType) {
        if (detectionType == null || detectionType.isBlank()) return "手动配置";
        return switch (detectionType) {
            case "CRUD" -> "增删改查";
            case "PATH_MATCH" -> "路径参数匹配";
            case "BODY_MATCH" -> "请求体参数匹配";
            case "FOLDER_ORDER" -> "文件夹顺序";
            case "MANUAL" -> "手动配置";
            default -> "自定义";
        };
    }

    /** 中文显示文本回写为执行器使用的内部检测方式。 */
    static String detectionTypeValue(String display) {
        if (display == null || display.isBlank()) return "MANUAL";
        return switch (display) {
            case "增删改查" -> "CRUD";
            case "路径参数匹配" -> "PATH_MATCH";
            case "请求体参数匹配" -> "BODY_MATCH";
            case "文件夹顺序" -> "FOLDER_ORDER";
            case "手动配置", "自定义" -> "MANUAL";
            default -> display;
        };
    }

    private ApiDefinition apiForRow(int viewRow, int apiColumn) {
        if (viewRow < 0 || viewRow >= table.getRowCount()) return null;
        int row = table.convertRowIndexToModel(viewRow);
        String label = cellText(row, apiColumn);
        String key = findKeyByLabel(label);
        if (key == null) return null;
        for (ApiDefinition api : apis) {
            if (api != null && key.equals(api.uniqueKey())) return api;
        }
        return null;
    }

    private static void collectFieldPaths(ApiParameter parameter, String prefix, List<String> result) {
        if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) return;
        String current = prefix.isBlank() ? parameter.getName().trim()
                : prefix + "." + parameter.getName().trim();
        if (!result.contains(current)) result.add(current);
        if (parameter.getChildren() != null) {
            for (ApiParameter child : parameter.getChildren()) collectFieldPaths(child, current, result);
        }
    }

    static List<String> fieldPaths(ApiDefinition api, boolean response) {
        List<String> result = new ArrayList<>();
        if (api == null) return result;
        List<ApiParameter> roots = response ? api.getResponseSchema() : api.getParameters();
        if (roots != null) for (ApiParameter parameter : roots) collectFieldPaths(parameter, "", result);
        return result;
    }

    /** 动态候选字段编辑器：producer 的响应字段或 consumer 的请求参数。 */
    private final class MappingCellEditor extends DefaultCellEditor {
        private final boolean response;
        private final JComboBox<String> combo;

        private MappingCellEditor(boolean response) {
            this(response, new JComboBox<>());
        }

        private MappingCellEditor(boolean response, JComboBox<String> combo) {
            super(combo);
            this.response = response;
            this.combo = combo;
            combo.setEditable(true);
            combo.setToolTipText(response ? "可下拉选择响应字段，也可手动输入路径" : "可下拉选择目标参数，也可手动输入参数名");
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                      int row, int column) {
            combo.removeAllItems();
            combo.addItem("");
            ApiDefinition api = apiForRow(row, response ? 0 : 2);
            List<String> candidates = fieldPaths(api, response);
            for (String path : candidates) combo.addItem(path);
            String current = value == null ? "" : String.valueOf(value);
            if (!current.isBlank() && !candidates.contains(current)) combo.addItem(current);
            combo.setSelectedItem(current);
            return combo;
        }

        @Override
        public Object getCellEditorValue() {
            Object item = combo.getEditor().getItem();
            return item == null ? "" : String.valueOf(item).trim();
        }
    }

    /**
     * 返回编辑后的依赖列表
     */
    public List<ApiDependency> getDependencies() {
        syncFromTable();
        return dependencies;
    }

    @Override
    protected void doOKAction() {
        syncFromTable();
        super.doOKAction();
    }
}
