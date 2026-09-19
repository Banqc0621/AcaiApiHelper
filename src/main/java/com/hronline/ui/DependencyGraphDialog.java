package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.chain.LastResponseCache;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
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
import java.util.function.Consumer;

/**
 * 依赖关系配置对话框 - 展示自动检测到的依赖关系，支持用户确认/编辑/删除/添加
 *
 * <p>表格列：上游接口 | 响应字段 | 下游接口 | 目标字段</p>
 */
public class DependencyGraphDialog extends DialogWrapper {

    private final Project project;
    private final List<ApiDefinition> apis;
    private List<ApiDependency> dependencies;
    /**
     * 可选的草稿保存回调。收藏夹依赖设置传入此回调后，用户编辑表格即会同步到设置，
     * 即使通过窗口右上角关闭也不会丢失；普通的依赖链测试对话框不传回调，仍保持
     * “确认后才应用”的一次性编辑语义。
     */
    private final Consumer<List<ApiDependency>> autoSaveListener;
    private boolean autoSaveCompleted;
    private boolean autoSaveScheduled;
    private boolean syncingTable;

    private DefaultTableModel tableModel;
    private JBTable table;

    /** key = uniqueKey, value = METHOD + URL 全路径，用于依赖表格和上下游下拉框展示。 */
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
        this(project, apis, dependencies, title, null);
    }

    /**
     * 构造依赖配置窗口，并可选开启草稿自动保存。
     *
     * @param autoSaveListener 每次窗口关闭前接收当前表格的深拷贝；传 {@code null}
     *                         表示仅由调用方在 OK 后读取 {@link #getDependencies()}
     */
    public DependencyGraphDialog(Project project, List<ApiDefinition> apis,
                                 List<ApiDependency> dependencies, String title,
                                 Consumer<List<ApiDependency>> autoSaveListener) {
        super(project);
        this.project = project;
        this.autoSaveListener = autoSaveListener;
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
        // 收藏夹接口顺序、依赖表格以及上下游下拉框统一展示「HTTP 方法 + URL 全路径」，
        // 便于区分同名接口；长路径通过单元格换行和悬浮提示完整呈现。
        labelByKey.putAll(buildDisplayLabels(this.apis));
        // 兼容旧配置中暂时找不到接口定义的依赖边：仍保留关系，并从 METHOD|URL key
        // 生成完整方法 + URL 标签；接口重新扫描后会通过 key 正常恢复名称。
        for (ApiDependency dep : this.dependencies) {
            if (dep == null) continue;
            ensureDependencyLabel(dep.getProducerKey());
            ensureDependencyLabel(dep.getConsumerKey());
        }
        setTitle(title == null || title.isBlank() ? "API 依赖链配置" : title);
        // DialogWrapper 默认按钮文案跟随英文 IDE 环境可能显示为 Cancel；依赖设置页面统一中文。
        setCancelButtonText("取消");
        // 明确告诉用户主按钮会把当前表格内容落盘，降低“点关闭后为什么没保存”的歧义。
        setOKButtonText("保存");
        init();
    }

    /**
     * 按收藏夹中的接口顺序创建相邻依赖边。边暂不带映射，
     * 用户可在表格中逐行补充多个"响应路径 → 目标字段"映射。
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
                "可编辑路径、删除误检项或手动添加依赖；同一对接口允许配置多个字段映射。<br>" +
                "修改会自动保存到当前收藏夹，点击右上角关闭后下次打开仍会恢复。</html>");
        hint.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(hint, BorderLayout.NORTH);
        // #80：把「1. GET /admin/foo」这种纯文本 JList 换成自定义渲染 —— 每行带序号 + 方法徽章
        // + URL，徽章用主题色（GET 绿/POST 蓝/PUT 橙/DELETE 红），交替行背景，hover 高亮。
        // 原来是 JList<String> 默认渲染，单看一坨「METHOD URL」文字，确实不够直观。
        DefaultListModel<ApiDefinition> orderModel = new DefaultListModel<>();
        for (ApiDefinition api : apis) {
            if (api != null) orderModel.addElement(api);
        }
        JList<ApiDefinition> orderList = new JList<>(orderModel) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                // 长 URL 不在单元格边界处悄悄截断；允许滚动容器按内容宽度提供水平滚动条。
                return false;
            }

            @Override
            public String getToolTipText(MouseEvent event) {
                int index = locationToIndex(event.getPoint());
                if (index < 0 || index >= getModel().getSize()) return null;
                Rectangle bounds = getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) return null;
                ApiDefinition api = getModel().getElementAt(index);
                return api == null ? null : fullApiLabel(api);
            }
        };
        orderList.setCellRenderer(new OrderListCellRenderer());
        orderList.setFocusable(false);
        orderList.setVisibleRowCount(Math.min(4, Math.max(1, orderModel.size())));
        orderList.setFixedCellHeight(30);
        orderList.setBorder(JBUI.Borders.empty(2, 4));
        // 主题感知底色：light = Panel.background / dark = 自动切换
        orderList.setBackground(JBColor.namedColor("Panel.background", new Color(0xFA, 0xFB, 0xFC)));
        JBScrollPane orderScroll = new JBScrollPane(orderList);
        orderList.setToolTipText("");
        orderScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        orderScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "收藏夹接口顺序"));
        orderScroll.setPreferredSize(JBUI.size(820, orderModel.isEmpty() ? 56 : 118));
        UiStyle.fixWindowsScrolling(orderScroll);
        top.add(orderScroll, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        // 表格：接口列使用下拉框，字段列使用可编辑下拉框；点击“添加依赖”直接新增空白记录。
        String[] columns = {"上游接口", "响应字段", "下游接口", "目标字段"};
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
                // 上游/下游单元格直接显示完整方法 + URL；悬浮提示再次提供完整内容，
                // 便于窄列或超长路径场景核对。
                if ((modelColumn == 0 || modelColumn == 2) && !text.isBlank()) {
                    String key = findKeyByLabel(text);
                    ApiDefinition api = apiByKey(key);
                    if (api != null) return fullApiLabel(api);
                }
                return text.length() > 20 ? text : null;
            }

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                return component;
            }
        };
        // 注册 ToolTipManager；getToolTipText(MouseEvent) 会按单元格返回完整长文本。
        table.setToolTipText("");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        // 行高在 26px 基础上再降到 0.8 倍，约 21px
        table.setRowHeight(21);
        table.setIntercellSpacing(new Dimension(JBUI.scale(4), JBUI.scale(1)));
        // 列宽比例：上游接口 : 上游字段 : 下游接口 : 目标字段 = 2 : 1 : 2 : 1
        // 接口列占更宽空间展示完整URL，字段列窄一些，表格支持横向滚动查看长内容
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(260));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(130));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(260));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(130));
        // #80：上游/下游接口列走自定义 ApiColumnRenderer（完整方法 + URL + 交替行底），
        // 响应字段/目标字段列使用单行渲染器（不换行），长内容靠横向滚动查看。
        table.getColumnModel().getColumn(0).setCellRenderer(new ApiColumnRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new WrappingCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ApiColumnRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new WrappingCellRenderer());
        table.getColumnModel().getColumn(0).setCellEditor(new ApiCellEditor());
        table.getColumnModel().getColumn(1).setCellEditor(new MappingCellEditor(true));
        table.getColumnModel().getColumn(2).setCellEditor(new ApiCellEditor());
        table.getColumnModel().getColumn(3).setCellEditor(new MappingCellEditor(false));

        fillTable();
        if (autoSaveListener != null) {
            // 编辑器提交值后 DefaultTableModel 会发出事件；合并同一 EDT 回合内的多列
            // 更新，避免每次键入都重复序列化设置，同时确保关闭前最后一次事件已落盘。
            tableModel.addTableModelListener(e -> scheduleAutoSave());
        }
        // 单元格编辑结束后按最新内容重新计算列宽（打字过程中不调整，避免列宽频繁跳动）
        table.addPropertyChangeListener("tableCellEditor", evt -> {
            if (!table.isEditing()) {
                autoFitColumnWidths();
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(table);
        UiStyle.fixWindowsScrolling(scrollPane);
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
        // 根据内容自动调整列宽：内容超长时扩展列宽，用户通过横向滚动条查看完整内容
        autoFitColumnWidths();
    }

    /**
     * 根据表格单元格内容自动调整列宽。
     * 保持最小宽度：接口列 260px，字段列 130px；内容更长时按文本宽度扩展，无上限。
     */
    private void autoFitColumnWidths() {
        int[] minWidths = {JBUI.scale(260), JBUI.scale(130), JBUI.scale(260), JBUI.scale(130)};
        java.awt.FontMetrics headerMetrics = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
        java.awt.FontMetrics cellMetrics = table.getFontMetrics(table.getFont());
        for (int col = 0; col < table.getColumnCount(); col++) {
            int width = minWidths[col];
            // 表头宽度
            Object headerValue = table.getColumnModel().getColumn(col).getHeaderValue();
            if (headerValue != null) {
                width = Math.max(width, headerMetrics.stringWidth(String.valueOf(headerValue)) + JBUI.scale(20));
            }
            // 所有单元格内容宽度
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                Object value = tableModel.getValueAt(row, col);
                if (value != null) {
                    String text = String.valueOf(value);
                    // 接口列显示会加上 [METHOD] 前缀，预留约 60px 宽度
                    int extra = (col == 0 || col == 2) ? JBUI.scale(70) : JBUI.scale(20);
                    width = Math.max(width, cellMetrics.stringWidth(text) + extra);
                }
            }
            table.getColumnModel().getColumn(col).setPreferredWidth(width);
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
        // tableModel 里读到的会是旧值（用户最后输入的响应字段/目标字段会丢）。
        if (syncingTable) return;
        syncingTable = true;
        try {
            flushActiveCellEditor();

            List<String[]> rows = new ArrayList<>(tableModel.getRowCount());
            for (int r = 0; r < tableModel.getRowCount(); r++) {
                rows.add(new String[]{
                        cellText(r, 0), cellText(r, 1), cellText(r, 2), cellText(r, 3)
                });
            }
            this.dependencies = rebuildFromRows(this.dependencies, rows, this.labelByKey);
        } finally {
            syncingTable = false;
        }
    }

    private void scheduleAutoSave() {
        if (autoSaveListener == null || autoSaveCompleted || autoSaveScheduled) return;
        autoSaveScheduled = true;
        Runnable save = () -> {
            autoSaveScheduled = false;
            if (autoSaveCompleted) return;
            try {
                syncFromTable();
                autoSaveListener.accept(copyDependencies(dependencies));
            } catch (RuntimeException ignored) {
                // 保存失败不打断用户继续编辑；关闭时会再尝试一次最终保存。
            }
        };
        if (SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeLater(save);
        else SwingUtilities.invokeLater(save);
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
     * 对应表头「上游接口 | 响应字段 | 下游接口 | 目标字段」。
     * {@code labelByKey} 是 uniqueKey → METHOD + URL 全路径标签的映射，用于反向解析。</p>
     *
     * <p>规则：
     * <ul>
     *   <li>{@code (无依赖)} 占位行跳过</li>
     *   <li>producer/consumer label 必须在 labelByKey 里反向解析，否则跳过</li>
     *   <li>producer == consumer 跳过（自环）</li>
     *   <li>两个字段都填 → 加入 mapping；同一对接口允许多个 mapping，自动去重</li>
     *   <li>两个字段都空 → 保留顺序边；新建 producer→consumer 也允许（type 默认 MANUAL），
     *       不再要求「必须是原 dependencies 里已有」——#  #77 修：原逻辑会让用户手动加的新行
     *       被静默丢弃，看上去像「依赖设置保存不了」</li>
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
            // 半填（只有 sourcePath 或只有 targetParam）→ 跳过，视为未完成。
            // 空映射（两个都空）+ 满映射（两个都填）→ 都允许落盘：
            //   空映射 → 仅保留 producer→consumer 顺序边（新建也允许，type 默认 MANUAL）。
            //   满映射 → 在对应边上追加一条 mapping。
            if (sourcePath.isBlank() != targetParam.isBlank()) {
                continue;
            }
            if (sourcePath.isBlank() && targetParam.isBlank()) {
                byKey.computeIfAbsent(key, k -> new ApiDependency(
                        producerKey, consumerKey,
                        originalDetectionTypes.getOrDefault(key, "MANUAL")));
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

    /** 接口列编辑器：仅显示当前窗口内可解析的 METHOD + URL 全路径，避免输入未知节点。 */
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
     * 长文本单元格渲染器。不换行，单行显示，超长内容通过表格横向滚动条 + tooltip 查看完整值。
     */
    private static final class WrappingCellRenderer extends JTextArea implements TableCellRenderer {
        private WrappingCellRenderer() {
            setLineWrap(false);
            setWrapStyleWord(false);
            setOpaque(true);
            setEditable(false);
            setFocusable(false);
            setBorder(JBUI.Borders.empty(2, 4));
            setMargin(JBUI.insets(1, 2));
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
                        BorderFactory.createLineBorder(focusColor, 1),
                        JBUI.Borders.empty(1, 3)));
            } else {
                setBorder(JBUI.Borders.empty(2, 4));
            }
            setToolTipText(text.trim().length() > 20 ? text : null);
            return this;
        }
    }

    /**
     * #82：「收藏夹接口顺序」JList 的行渲染器 —— 序号 + HTTP 方法 + 完整 URL。
     * 使用纯文本而不是 Swing HTML/CSS：IntelliJ 不同主题下 HTML 的 inline span
     * 可能只计算出序号 span 的宽度，导致方法和 URL 在界面中消失。纯文本可稳定计算
     * 首选宽度，长 URL 由外层滚动条承载，悬浮提示仍提供完整标签。
     */
    static final class OrderListCellRenderer extends JBLabel implements ListCellRenderer<ApiDefinition> {
        // 主题感知行底色：light = 极淡蓝白 / dark = 比 panel 深一档的灰
        private static final JBColor EVEN_BG = new JBColor(new Color(0xFA, 0xFB, 0xFC), new Color(0x2B, 0x2D, 0x30));
        private static final JBColor ODD_BG = new JBColor(new Color(0xF2, 0xF4, 0xF7), new Color(0x31, 0x33, 0x36));
        OrderListCellRenderer() {
            setOpaque(true);
            setBorder(JBUI.Borders.empty(5, 10));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ApiDefinition> list, ApiDefinition api,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            if (api == null) {
                setText("");
                setBackground(isSelected ? list.getSelectionBackground() : EVEN_BG);
                return this;
            }
            String method = api.getHttpMethod() == null ? "" : api.getHttpMethod().toUpperCase();
            String url = api.getUrl() == null ? "" : api.getUrl().trim();
            // 去掉 query 和尾部斜杠，跟 fullApiLabel 保持一致
            int query = url.indexOf('?');
            if (query >= 0) url = url.substring(0, query);
            while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);

            // 纯文本渲染，确保方法和 URL 在所有 IntelliJ LaF 下都可见、可复制。
            setText((index + 1) + ". " + (method.isBlank() ? "API" : method)
                    + (url.isBlank() ? " (未命名)" : " " + url));
            setToolTipText(method + " " + url);
            // 选中态用 LaF 主题色，偶数行浅灰，奇数行更浅
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(index % 2 == 0 ? EVEN_BG : ODD_BG);
                setForeground(list.getForeground());
            }
            setFont(list.getFont());
            return this;
        }
    }

    /**
     * #82：依赖表格里上游/下游接口列的自定义渲染器。沿用 JTextArea 换行，
     * 使用完整方法 + URL 纯文本，颜色跟表格主题保持一致 —— 全部走主题感知，
     * 选中态用 selectionForeground，未选中用 table.foreground，dark theme 下不再黑字黑底。
     * <p>value 是 {@code labelByKey.get(key)}（如 "GET /admin/foo"）。兼容旧配置传入的
     * "[GET] foo" 格式，仍会先切出 method 渲染；
     * 空串 / "(无依赖)" 占位走 fallback 分支，不强行套徽章。</p>
     */
    static final class ApiColumnRenderer extends JTextArea implements TableCellRenderer {
        // 主题感知行底色：light 极淡蓝白 / dark 比 table 默认底色深一档，区分交替行
        private static final JBColor EVEN_BG = new JBColor(new Color(0xFA, 0xFB, 0xFC), new Color(0x2B, 0x2D, 0x30));
        private static final JBColor ODD_BG = new JBColor(new Color(0xF2, 0xF4, 0xF7), new Color(0x31, 0x33, 0x36));
        // 占位灰（"无依赖"/"—"/unknown）：light 中等灰 / dark 中等亮度的灰
        private static final JBColor PLACEHOLDER_FG = new JBColor(new Color(0x66, 0x66, 0x66), new Color(0x99, 0x99, 0x99));

        ApiColumnRenderer() {
            setLineWrap(false);
            setWrapStyleWord(false);
            setOpaque(true);
            setEditable(false);
            setFocusable(false);
            setBorder(JBUI.Borders.empty(2, 6));
            setMargin(JBUI.insets(1, 2));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            String raw = value == null ? "" : String.valueOf(value).trim();
            String method = "";
            String body = raw;
            // 从 "GET /admin/foo" / "[GET] foo" / "(无依赖)" 里切 method
            int firstSpace = raw.indexOf(' ');
            int firstBracket = raw.indexOf(']');
            if (raw.startsWith("[") && firstBracket > 0 && firstBracket + 1 < raw.length()
                    && raw.charAt(firstBracket + 1) == ' ') {
                method = raw.substring(1, firstBracket);
                body = raw.substring(firstBracket + 2).trim();
            } else if (firstSpace > 0) {
                String head = raw.substring(0, firstSpace);
                if (isHttpMethod(head)) {
                    method = head.toUpperCase();
                    body = raw.substring(firstSpace + 1).trim();
                }
            }

            // JTextArea 不解析 HTML，直接写入 <span style=...> 会把样式代码泄露到界面。
            // 采用紧凑且可复制的纯文本格式；行背景与焦点边框仍提供清晰层级。
            String display = method.isBlank()
                    ? (raw.isBlank() ? "—" : raw)
                    : "[" + method + "] " + body;
            setText(display);
            setFont(table.getFont());
            // 选中态用 LaF 主题色，奇偶行交替灰底
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(row % 2 == 0 ? EVEN_BG : ODD_BG);
                setForeground(table.getForeground());
            }
            setToolTipText(raw.length() > 20 ? raw : null);
            return this;
        }

    }

    private static boolean isHttpMethod(String s) {
        if (s == null) return false;
        switch (s.toUpperCase()) {
            case "GET": case "POST": case "PUT": case "DELETE":
            case "PATCH": case "HEAD": case "OPTIONS":
                return true;
            default:
                return false;
        }
    }

    /**
     * 表格和上下游下拉框使用的短标签。优先使用接口名称，但要先清洗历史/导入数据
     * 中可能已经拼好的完整展示串，例如「[GET] /admin/box/blindBoxList - blindBoxList」；
     * 这种值不能原样显示，否则用户看到的仍是完整路径。没有可用名称时退化为 URL 最后一级。
     */
    static String shortApiLabel(ApiDefinition api) {
        if (api == null) return "";
        String name = api.getName() == null ? "" : api.getName().trim();
        String cleanedName = extractEndpointName(name);
        if (!cleanedName.isBlank()) return cleanedName;
        return lastPathSegment(api.getUrl());
    }

    /** 清洗接口名称中的方法、完整 URL 及展示分隔符，只保留最终接口名称。 */
    private static String extractEndpointName(String rawName) {
        if (rawName == null || rawName.isBlank()) return "";
        String value = rawName.trim();

        // 兼容 ApiDefinition.displayLabel() / 旧版本收藏数据的「... - name」格式。
        int separator = value.indexOf(" - ");
        if (separator >= 0 && separator + 3 < value.length()
                && looksLikeEndpointDisplayPrefix(value.substring(0, separator))) {
            String suffix = value.substring(separator + 3).trim();
            if (!suffix.isBlank()) value = suffix;
        } else {
            // 也兼容导出数据使用的长破折号分隔格式。
            for (String delimiter : new String[]{" — ", " – "}) {
                int pos = value.indexOf(delimiter);
                if (pos >= 0 && pos + delimiter.length() < value.length()
                        && looksLikeEndpointDisplayPrefix(value.substring(0, pos))) {
                    String suffix = value.substring(pos + delimiter.length()).trim();
                    if (!suffix.isBlank()) value = suffix;
                    break;
                }
            }
        }

        // 去掉展示串前缀 [GET] / GET，再判断剩余内容是否为路径。
        if (value.matches("^\\[[A-Za-z]+\\]\\s+.*")) {
            value = value.substring(value.indexOf(']') + 1).trim();
        } else {
            int space = value.indexOf(' ');
            if (space > 0 && isHttpMethod(value.substring(0, space))) {
                value = value.substring(space + 1).trim();
            }
        }

        // 只要剩余值是路径/URL，就取最后一级；普通中文 summary（无斜杠）原样保留。
        if (value.contains("/")) return lastPathSegment(value);
        return value;
    }

    private static boolean looksLikeEndpointDisplayPrefix(String prefix) {
        if (prefix == null) return false;
        String value = prefix.trim();
        return value.contains("/")
                || value.matches("^\\[[A-Za-z]+\\]\\s+.*")
                || (value.indexOf(' ') > 0 && isHttpMethod(value.substring(0, value.indexOf(' '))));
    }

    /** 从 URL 或旧 uniqueKey 中提取最后一级路径名称。 */
    private static String lastPathSegment(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        int separator = url.indexOf('|');
        if (separator >= 0) url = url.substring(separator + 1).trim();
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int slash = url.lastIndexOf('/');
        String last = slash >= 0 ? url.substring(slash + 1) : url;
        if (last.startsWith("{") && last.endsWith("}")) last = last.substring(1, last.length() - 1);
        return last.isBlank() ? (url.isBlank() ? "未命名接口" : url) : last;
    }

    /**
     * 接口全路径显示标签：{@code METHOD /path/{id}}。
     * <p>收藏夹接口顺序、依赖表格和上下游下拉框统一使用该完整标签，悬浮提示提供
     * 同一完整内容。
     * method 缺失时退化为 {@code API}，URL 缺失时退化为 {@code METHOD (未命名)}。</p>
     */
    static String fullApiLabel(ApiDefinition api) {
        if (api == null) return "";
        String method = api.getHttpMethod() == null || api.getHttpMethod().isBlank()
                ? "API" : api.getHttpMethod().trim().toUpperCase(Locale.ROOT);
        String url = api.getUrl() == null ? "" : api.getUrl().trim();
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isBlank()) return method + " (未命名)";
        return method + " " + url;
    }

    /**
     * 为接口生成全路径显示名，并保证同一窗口内每个 uniqueKey 都能反向解析。
     * <p>默认显示 {@code METHOD /path/{id}}；全路径撞名（同 method 同 url 但 uniqueKey 不同 —
     * 例如多文件扫描到的同名接口）时追加 {@code (#2) / (#3)} 序号，仍然唯一可反查。</p>
     */
    static Map<String, String> buildDisplayLabels(List<ApiDefinition> apis) {
        Map<String, String> result = new LinkedHashMap<>();
        if (apis == null || apis.isEmpty()) return result;

        Set<String> usedLabels = new HashSet<>();
        Map<String, Integer> nextSuffixByLabel = new HashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()) continue;
            // 同一 uniqueKey 在扫描结果中偶尔会重复；表格只能映射到一个稳定节点，保留首次出现项。
            if (result.containsKey(api.uniqueKey())) continue;

            String label = fullApiLabel(api);
            if (usedLabels.contains(label)) {
                int suffix = nextSuffixByLabel.getOrDefault(label, 2);
                String candidate;
                do {
                    candidate = label + " (#" + suffix++ + ")";
                } while (usedLabels.contains(candidate));
                nextSuffixByLabel.put(label, suffix);
                label = candidate;
            }
            usedLabels.add(label);
            result.put(api.uniqueKey(), label);
        }
        return result;
    }

    /**
     * 旧版本短名称标签生成器，仅保留给历史数据兼容测试；当前依赖设置 UI 不再调用，
     * 表格、顺序列表和上下游下拉框统一使用 {@link #buildDisplayLabels(List)} 的完整路径。
     */
    static Map<String, String> buildShortDisplayLabels(List<ApiDefinition> apis) {
        Map<String, String> result = new LinkedHashMap<>();
        if (apis == null || apis.isEmpty()) return result;
        Map<String, Integer> counts = new HashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()) continue;
            counts.merge(shortApiLabel(api), 1, Integer::sum);
        }
        Set<String> used = new HashSet<>();
        Map<String, Integer> next = new HashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null || api.uniqueKey() == null || api.uniqueKey().isBlank()
                    || result.containsKey(api.uniqueKey())) continue;
            String shortName = shortApiLabel(api);
            String label = shortName;
            // 同名接口也只展示最后名称，使用轻量序号消歧，避免 [GET] 或 URL 再次泄露到下拉框。
            if (counts.getOrDefault(shortName, 0) > 1) {
                int occurrence = 1;
                for (ApiDefinition previous : apis) {
                    if (previous == api) break;
                    if (previous != null && shortName.equals(shortApiLabel(previous))) occurrence++;
                }
                if (occurrence > 1) label = shortName + " (" + occurrence + ")";
            }
            if (used.contains(label)) {
                int suffix = next.getOrDefault(label, 2);
                String candidate;
                do { candidate = label + " (" + suffix++ + ")"; }
                while (used.contains(candidate));
                next.put(label, suffix);
                label = candidate;
            }
            used.add(label);
            result.put(api.uniqueKey(), label);
        }
        return result;
    }

    private String displayLabelForApi(ApiDefinition api) {
        if (api == null) return "";
        return labelByKey.getOrDefault(api.uniqueKey(), fullApiLabel(api));
    }

    private void ensureDependencyLabel(String key) {
        if (key == null || key.isBlank() || labelByKey.containsKey(key)) return;
        String base = fullDependencyKeyLabel(key);
        if (base.isBlank()) base = key;
        String label = base;
        if (labelByKey.containsValue(label)) label = base + " (2)";
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

    /** 从 METHOD|/path/to/api 形式的旧 key 中生成完整方法 + URL 标签。 */
    static String fullDependencyKeyLabel(String key) {
        if (key == null || key.isBlank()) return "";
        int separator = key.indexOf('|');
        if (separator < 0) return key.trim();
        String method = key.substring(0, separator).trim();
        String url = key.substring(separator + 1).trim();
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (method.isBlank()) method = "API";
        return url.isBlank() ? method.toUpperCase(Locale.ROOT) + " (未命名)"
                : method.toUpperCase(Locale.ROOT) + " " + url;
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

    /**
     * 收集候选字段路径。
     * <ul>
     *   <li>非响应字段（入参）：直接走手工维护的 {@code ApiDefinition.getParameters()}。</li>
     *   <li>响应字段：优先用手工维护的 {@code responseSchema}；若为空则回退到 {@link LastResponseCache}
     *       里该接口最近一次响应 body，递归遍历所有字段生成点号路径（如
     *       {@code data}、{@code data.id}、{@code data.user.name}）。</li>
     * </ul>
     */
    static List<String> fieldPaths(ApiDefinition api, boolean response) {
        List<String> result = new ArrayList<>();
        if (api == null) return result;
        List<ApiParameter> roots = response ? api.getResponseSchema() : api.getParameters();
        if (roots != null) {
            for (ApiParameter parameter : roots) collectFieldPaths(parameter, "", result);
        }
        if (response && result.isEmpty()) {
            // 一伦优化 #94：responseSchema 通常不维护嵌套对象，从最近响应 body 递归出全部候选
            String body = LastResponseCache.get(api.uniqueKey());
            if (body != null && !body.isBlank()) {
                com.google.gson.JsonElement parsed = null;
                try {
                    parsed = com.google.gson.JsonParser.parseString(body);
                } catch (Exception ignored) {
                    parsed = null;
                }
                if (parsed != null) collectBodyFieldPaths(parsed, "", result);
            }
        }
        return result;
    }

    /**
     * 递归遍历 JSON body，生成所有点号路径。
     * 数组按首元素展开（{@code data.list[0].name}），跳过 null 元素以避免误导。
     */
    private static void collectBodyFieldPaths(com.google.gson.JsonElement element,
                                             String prefix, List<String> result) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : element.getAsJsonObject().entrySet()) {
                String name = e.getKey();
                if (name == null || name.isBlank()) continue;
                String current = prefix.isEmpty() ? name : prefix + "." + name;
                if (!result.contains(current)) result.add(current);
                collectBodyFieldPaths(e.getValue(), current, result);
            }
            return;
        }
        if (element.isJsonArray()) {
            com.google.gson.JsonArray arr = element.getAsJsonArray();
            if (arr.size() == 0) return;
            // 数组本身可作为整体路径（复杂对象用其 toString），同时给出首元素的递归路径
            com.google.gson.JsonElement first = null;
            for (com.google.gson.JsonElement v : arr) {
                if (v != null && !v.isJsonNull()) { first = v; break; }
            }
            if (first != null) {
                collectBodyFieldPaths(first, prefix + "[0]", result);
            }
        }
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
            combo.setToolTipText(response ? "可下拉选择响应字段，也可手动输入路径" : "可下拉选择目标字段，也可手动输入字段名");
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
            return readComboValue(combo);
        }
    }

    /**
     * 读取可编辑 JComboBox 的最终文本。用户直接键入时，selectedItem 仍可能是上一次
     * 选中的候选值，只有 editor item 才是最新文本；选择候选时 editor item 也会同步，
     * 因而统一优先 editor，再以 selectedItem 兜底。
     */
    static String readComboValue(JComboBox<?> combo) {
        if (combo == null) return "";
        Object item = null;
        if (combo.isEditable() && combo.getEditor() != null) {
            item = combo.getEditor().getItem();
        }
        if (item == null) item = combo.getSelectedItem();
        return item == null ? "" : String.valueOf(item).trim();
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

    /**
     * DialogWrapper 的右上角关闭、取消和 ESC 最终都会走 dispose；在这里做最后一次
     * flush，覆盖“用户正在编辑字段时直接点 X”的场景。回调接收深拷贝，避免窗口销毁
     * 后外部仍持有可变表格数据。
     */
    @Override
    protected void dispose() {
        if (autoSaveListener != null && !autoSaveCompleted) {
            try {
                syncFromTable();
                autoSaveListener.accept(copyDependencies(dependencies));
                autoSaveCompleted = true;
            } catch (RuntimeException ignored) {
                // 关闭流程不能因持久化回调异常被阻塞；保留未完成标记，若框架再次
                // 触发 dispose 或调用方在 OK 后显式保存，仍有机会完成持久化。
            }
        }
        super.dispose();
    }

    private static List<ApiDependency> copyDependencies(List<ApiDependency> source) {
        List<ApiDependency> result = new ArrayList<>();
        if (source == null) return result;
        for (ApiDependency dep : source) {
            if (dep == null) continue;
            ApiDependency copy = new ApiDependency(dep.getProducerKey(), dep.getConsumerKey(),
                    dep.getDetectionType());
            if (dep.getMappings() != null) {
                for (ApiDependency.ValueMapping mapping : dep.getMappings()) {
                    if (mapping != null) {
                        copy.getMappings().add(new ApiDependency.ValueMapping(
                                mapping.getSourcePath(), mapping.getTargetParam()));
                    }
                }
            }
            result.add(copy);
        }
        return result;
    }
}