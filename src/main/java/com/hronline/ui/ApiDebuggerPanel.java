package com.hronline.ui;

import com.hronline.RestAutoLabConstants;
import com.hronline.ai.AiParameterService;
import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.hronline.scanner.ApiScannerService;
import com.hronline.scanner.StarredFolderService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.hronline.util.ApiDocExporter;
import com.hronline.util.CurlUtil;
import com.hronline.util.ReportExporter;
import com.hronline.util.SimpleDiff;
import com.hronline.util.TestDataExporter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * API调试面板 - 简洁大方的请求构建与测试界面
 */
public class ApiDebuggerPanel extends JPanel {

    private final Project project;
    private ApiDefinition currentApi = null;
    /** 收藏模式下当前接口所属文件夹 ID；为 null 表示处于全量视图（不按文件夹归档实时参数） */
    private String currentFolderId = null;
    private ApiTreePanel treePanel; // 注入：在 Markdown 导出时获取用户在树中的多选
    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ApiDebuggerPanel.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ── UI控件 ──
    private final ComboBox<String> methodCombo = new ComboBox<>(RestAutoLabConstants.HTTP_METHOD_NAMES);
    private final JBTextField urlField = new JBTextField();
    private final JButton sendButton = new JButton("发送", AllIcons.Actions.Execute);
    private final JBTextField baseUrlField = new JBTextField();

    private final DefaultTableModel paramTableModel = new DefaultTableModel(
            new Object[]{"参数名", "类型", "位置", "值", "必填", "描述"}, 0);
    private final JBTable paramTable = new JBTable(paramTableModel);

    /** 附件面板：仅在当前接口含文件参数时显示，每个文件参数一行：选择按钮 + 已选路径 */
    private final JPanel attachmentPanel = new JPanel();
    private final JLabel attachmentTitle = new JLabel("📎 文件参数（用于 multipart/form-data 上传）");
    /** key=参数名, value=当前选择的本地文件绝对路径（用户未选择时为空） */
    private final Map<String, String> attachmentPaths = new LinkedHashMap<>();
    /** key=参数名, value=对应的参数控件（用于在 updateAttachmentPanel 时清空重建） */
    private final Map<String, javax.swing.JLabel> attachmentPathLabels = new LinkedHashMap<>();

    private final DefaultTableModel headerTableModel = new DefaultTableModel(
            new Object[]{"Header名", "值"}, 0);
    private final JBTable headerTable = new JBTable(headerTableModel);

    // ── 请求体编辑器（v2.0.0：失焦折叠 / 获焦展开 + Ctrl+Z/Y 撤销重做）──
    /** body 编辑器折叠时的行数（默认紧凑，给响应区让出垂直空间） */
    private static final int BODY_ROWS_COLLAPSED = 6;
    /** body 编辑器获焦展开后的行数 */
    private static final int BODY_ROWS_EXPANDED = 18;
    private final JBTextArea bodyEditor = new JBTextArea(BODY_ROWS_COLLAPSED, 60);
    /** body 编辑器的滚动容器引用：展开 / 折叠时 revalidate + repaint */
    private JBScrollPane bodyScrollPane;
    /** body 编辑器撤销管理器（支持 Ctrl+Z / Ctrl+Y） */
    private final javax.swing.undo.UndoManager bodyUndoManager = new javax.swing.undo.UndoManager();

    // ── 响应区（v2.0.0：JsonSyntaxPane 提供语法高亮 + Ctrl+滚轮缩放 + 右键菜单）──
    private final JBTextArea responseArea = new JBTextArea();
    /** 响应文本视图：带 JSON 语法高亮 / 缩放 / 右键菜单的高亮面板（responseArea 仅作后台数据兼容） */
    private final JsonSyntaxPane responsePane = new JsonSyntaxPane();
    /** 当前响应视图是否为树形（true=树形，false=文本） */
    private boolean responseViewTree = false;
    private final JTree responseJsonTree = new JTree();
    private final CardLayout responseCardLayout = new CardLayout();
    private final JPanel responseContentPanel = new JPanel(responseCardLayout);
    private final JBLabel responseStatusLabel = new JBLabel("状态: -");
    private final JBLabel responseTimeLabel = new JBLabel("耗时: -");
    private final JBLabel responseSizeLabel = new JBLabel("大小: -");

    private final JBTextArea testResultArea = new JBTextArea();
    private final JProgressBar testProgressBar = new JProgressBar();
    
    // 批量测试状态控制
    private volatile boolean batchTestRunning = false;
    private volatile boolean batchTestCancelled = false;
    private JButton batchTestBtn;  // 批量测试按钮引用

    private final ComboBox<AiParameterService.TestScenario> scenarioCombo = new ComboBox<>(
            AiParameterService.TestScenario.values());
    private final JComboBox<String> modelCombo = new JComboBox<>(RestAutoLabConstants.AI_MODEL_OPTIONS);

    // v3 新增字段
    private JComboBox<String> bodyFormatCombo;
    private JComboBox<Environment> envCombo;
    /** 环境下拉框重建期间的抑制标志，避免 setSelectedItem 触发 ActionListener 误切换环境 */
    private boolean suppressEnvComboAction = false;
    private DefaultListModel<RequestHistory> historyListModel;
    private JList<RequestHistory> historyList;
    private DefaultTableModel assertionTableModel;
    private JBTable assertionTable;
    private JComboBox<String> expectedStatusCombo;
    private JBLabel cookieStatusLabel;
    private List<RequestHistory> requestHistory = new ArrayList<>();
    private TestResult lastResult = null;
    private final List<ResponseAssertion> currentAssertions = new ArrayList<>();

    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final JBLabel statusLabel = new JBLabel("就绪");
    /** AI 配置摘要标签，保存配置后需刷新此标签文本 */
    private JBLabel aiConfigInfoLabel;

    /**
     * 注入左侧接口树。Markdown 导出按钮会从这里取用户在树中的多选。
     */
    public void setTreePanel(ApiTreePanel treePanel) {
        this.treePanel = treePanel;
    }

    public ApiDebuggerPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        // v3: 加载历史记录
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        requestHistory = settings.loadRequestHistory();

        setupUI();
        setupActions();

        // v3: 注册HTTP历史监听
        HttpExecutorService http = HttpExecutorService.getInstance(project);
        http.setHistoryListener(result -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                addToHistory(result);
            });
        });
    }

    private void setupUI() {
        setBorder(UiStyle.contentBorder());

        // 北部容器：工具栏 + 请求栏
        JPanel northPanel = new JPanel(new BorderLayout(0, 4));
        northPanel.add(createToolbar(), BorderLayout.NORTH);
        northPanel.add(createTopPanel(), BorderLayout.CENTER);
        add(northPanel, BorderLayout.NORTH);

        // 一伦优化 #5：右面板拆为「请求配置层（顶部，可拖动）+ 响应展示层（底部）」双层布局。
        // 请求层使用原 TabbedPane（去掉"响应"Tab），响应层常驻底部，可拖动分割条调比例。
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        tabbedPane.addTab("参数", createParamsTab());
        tabbedPane.addTab("请求头", createHeadersTab());
        tabbedPane.addTab("请求体", createBodyTab());
        tabbedPane.addTab("断言", createAssertionsTab());
        tabbedPane.addTab("历史", createHistoryTab());
        tabbedPane.addTab("测试", createTestTab());
        tabbedPane.addTab("AI生成", createAiTab());
        JScrollPane requestScroll = new JBScrollPane(tabbedPane);
        requestScroll.setBorder(JBUI.Borders.empty());
        requestScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        requestScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel responsePanel = createResponsePanel();

        // 垂直分割：true=垂直方向（上下），0.6=顶部请求层占 60%
        JBSplitter splitter = new JBSplitter(true, 0.6f);
        splitter.setFirstComponent(requestScroll);
        splitter.setSecondComponent(responsePanel);
        // 解除子组件最小尺寸限制，使分割条可自由上下拖动
        splitter.setHonorComponentsMinimumSize(false);
        // 持久化拖动比例，下次打开工具窗口自动恢复
        splitter.setSplitterProportionKey("RestAutoLab.Debugger.VerticalSplitter");
        add(splitter, BorderLayout.CENTER);

        // 底部状态栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(UiStyle.topDivider());
        UiStyle.hint(statusLabel);
        statusLabel.setText("就绪");
        bottomPanel.add(statusLabel, BorderLayout.WEST);
        add(bottomPanel, BorderLayout.SOUTH);

        // ── v2.0.0 交互增强：body 编辑器撤销/折叠 ──
        // 响应层常驻显示，"切到响应Tab重置视图"逻辑已不再适用，移除 initResponseTabListener
        initBodyEditorInteractions();
    }

    /**
     * v2.0.0：请求体编辑器交互增强
     * <ul>
     *   <li>Ctrl+Z / Ctrl+Y 撤销 / 重做</li>
     *   <li>获焦自动展开（BODY_ROWS_EXPANDED），失焦自动折叠（BODY_ROWS_COLLAPSED）</li>
     * </ul>
     */
    private void initBodyEditorInteractions() {
        // 撤销监听
        bodyEditor.getDocument().addUndoableEditListener(e -> {
            if (e.getEdit().isSignificant()) {
                bodyUndoManager.addEdit(e.getEdit());
            }
        });
        // Ctrl+Z / Ctrl+Y
        javax.swing.KeyStroke undoKs = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        javax.swing.KeyStroke redoKs = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        String undoId = "body-undo";
        String redoId = "body-redo";
        javax.swing.InputMap im = bodyEditor.getInputMap();
        javax.swing.ActionMap am = bodyEditor.getActionMap();
        im.put(undoKs, undoId);
        im.put(redoKs, redoId);
        am.put(undoId, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (bodyUndoManager.canUndo()) bodyUndoManager.undo();
            }
        });
        am.put(redoId, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (bodyUndoManager.canRedo()) bodyUndoManager.redo();
            }
        });

        // 获焦展开 / 失焦折叠（避免抢用户正在编辑的内容，仅在行数不同时调整）
        bodyEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                if (bodyEditor.getRows() != BODY_ROWS_EXPANDED) {
                    bodyEditor.setRows(BODY_ROWS_EXPANDED);
                    if (bodyScrollPane != null) {
                        bodyScrollPane.revalidate();
                        bodyScrollPane.repaint();
                    }
                }
            }
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (bodyEditor.getRows() != BODY_ROWS_COLLAPSED) {
                    bodyEditor.setRows(BODY_ROWS_COLLAPSED);
                    if (bodyScrollPane != null) {
                        bodyScrollPane.revalidate();
                        bodyScrollPane.repaint();
                    }
                }
            }
        });
    }

    /**
     * 创建顶部工具栏 - 扫描、刷新、环境切换等快捷操作
     * <p>拆为上下两排，避免单行过长：</p>
     * <ul>
     *   <li>第 1 行：扫描API + 环境（下拉+管理）+ 数据管理</li>
     *   <li>第 2 行：导入 / 导出cURL / 导出文档 / 导出报告 / 清Cookie</li>
     * </ul>
     */
    private JPanel createToolbar() {
        // ============== 第 1 行：扫描API + 环境 + 环境管理 + 数据管理 ==============
        JToolBar toolbar1 = new JToolBar();
        toolbar1.setFloatable(false);
        toolbar1.setBorder(JBUI.Borders.empty(0, 0, 2, 0));

        JButton scanBtn = new JButton("扫描API", AllIcons.Actions.Refresh);
        scanBtn.setToolTipText("重新扫描项目中的所有API接口");
        scanBtn.putClientProperty("JButton.buttonType", "roundRect");
        scanBtn.setFont(scanBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        scanBtn.setFocusPainted(false);
        scanBtn.addActionListener(e -> {
            ApiScannerService.getInstance(project).scanProjectApisAsync();
            statusLabel.setText("● 正在扫描API...");
        });
        toolbar1.add(scanBtn);

        toolbar1.addSeparator(new Dimension(12, 0));
        JBLabel envLabel = new JBLabel("环境: ");
        UiStyle.hint(envLabel);
        toolbar1.add(envLabel);

        // 环境选择下拉框
        envCombo = new JComboBox<>();
        // 自定义渲染器：当前选中项（即激活环境）前显示 ✓，其余不显示。
        // 不再依赖 Environment.active 字段（该字段为冗余状态，切换时未同步刷新会导致 ✓ 停留错误）。
        envCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Environment) {
                    Environment env = (Environment) value;
                    // index == -1 表示当前下拉框选中项（编辑框显示）；列表项则仅高亮选中行
                    boolean isComboSelection = (index == -1);
                    boolean isListSelected = isSelected && index >= 0;
                    if (isComboSelection || isListSelected) {
                        setText("✓ " + env.getName());
                    } else {
                        setText(env.getName());
                    }
                }
                return this;
            }
        });
        refreshEnvCombo();
        envCombo.setPreferredSize(new Dimension(140, 26));
        envCombo.setToolTipText("切换环境配置");
        envCombo.addActionListener(e -> {
            // 重建下拉框（refreshEnvCombo）期间触发的选择事件忽略，避免误切环境
            if (suppressEnvComboAction) return;
            Environment selected = (Environment) envCombo.getSelectedItem();
            if (selected != null) {
                RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
                settings.setActiveEnvironment(selected.getName());
                settings.setBaseUrl(selected.getBaseUrl());
                applyEnvironmentToPanel(selected);
                statusLabel.setText("● 已切换到环境: " + selected.getName());
            }
        });
        toolbar1.add(envCombo);

        // 一伦优化 #3：原"环境管理"+"数据管理"两个按钮合并为单个入口"环境 & 数据"，
        // 弹统一的合并对话框（EnvAndDataManageDialog），不再在工具栏占两个槽位。
        JButton envDataBtn = new JButton("环境 & 数据", AllIcons.General.Settings);
        envDataBtn.setToolTipText("管理环境 / 变量 / 全局请求头；导入导出测试配置与接口数据");
        envDataBtn.putClientProperty("JButton.buttonType", "roundRect");
        envDataBtn.setFont(envDataBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        envDataBtn.setFocusPainted(false);
        envDataBtn.addActionListener(e -> showEnvAndDataManageDialog());
        toolbar1.add(envDataBtn);

        // ============== 第 2 行：导出cURL / 导出文档 / 导出报告 / 清Cookie ==============
        // 一伦优化 #2：原"导入"按钮已挪到左侧接口树右键菜单（ApiTreePanel → 导入cURL），
        // 不再在本工具栏占位，腾出空间给更常用的导出与清Cookie。
        JToolBar toolbar2 = new JToolBar();
        toolbar2.setFloatable(false);
        toolbar2.setBorder(JBUI.Borders.empty(2, 0, 0, 0));

        JButton exportBtn = new JButton("导出cURL", AllIcons.ToolbarDecorator.Export);
        exportBtn.setToolTipText("导出为cURL命令");
        exportBtn.putClientProperty("JButton.buttonType", "roundRect");
        exportBtn.setFont(exportBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        exportBtn.setFocusPainted(false);
        exportBtn.addActionListener(e -> exportCurl());
        toolbar2.add(exportBtn);

        JButton exportDocBtn = new JButton("导出文档", AllIcons.Actions.Download);
        exportDocBtn.setToolTipText("导出API文档(Markdown)");
        exportDocBtn.putClientProperty("JButton.buttonType", "roundRect");
        exportDocBtn.setFont(exportDocBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        exportDocBtn.setFocusPainted(false);
        exportDocBtn.addActionListener(e -> exportApiDoc());
        toolbar2.add(exportDocBtn);

        JButton exportReportBtn = new JButton("导出报告", AllIcons.Actions.Dump);
        exportReportBtn.setToolTipText("导出HTML测试报告");
        exportReportBtn.putClientProperty("JButton.buttonType", "roundRect");
        exportReportBtn.setFont(exportReportBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        exportReportBtn.setFocusPainted(false);
        exportReportBtn.addActionListener(e -> exportLastReport());
        toolbar2.add(exportReportBtn);

        toolbar2.addSeparator(new Dimension(4, 0));

        JButton clearCookieBtn = new JButton("清Cookie", AllIcons.Actions.GC);
        clearCookieBtn.setToolTipText("清空Cookie");
        clearCookieBtn.putClientProperty("JButton.buttonType", "roundRect");
        clearCookieBtn.setFont(clearCookieBtn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        clearCookieBtn.setFocusPainted(false);
        clearCookieBtn.addActionListener(e -> {
            HttpExecutorService.getInstance(project).clearCookies();
            cookieStatusLabel.setText("Cookie: 已清空");
            statusLabel.setText("● Cookie已清空");
        });
        toolbar2.add(clearCookieBtn);

        // ============== 外层：垂直 BoxLayout 包裹两行 ==============
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        toolbar1.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbar2.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(toolbar1);
        wrapper.add(toolbar2);

        return wrapper;
    }

    /** 刷新环境下拉框 */
    private void refreshEnvCombo() {
        if (envCombo == null) return;
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        // 重建期间抑制 ActionListener，避免 removeAllItems/setSelectedItem 触发误切换
        suppressEnvComboAction = true;
        try {
            envCombo.removeAllItems();
            for (Environment env : settings.loadEnvironments()) {
                envCombo.addItem(env);
                if (env.getName().equals(settings.getActiveEnvironment())) {
                    envCombo.setSelectedItem(env);
                }
            }
        } finally {
            suppressEnvComboAction = false;
        }
    }

    /**
     * 将指定环境的配置回显到主调试面板：更新 Base URL 输入框，
     * 并在请求头表格顶部注入该环境的全局请求头（保留已有的接口级请求头）。
     */
    private void applyEnvironmentToPanel(Environment env) {
        if (env == null) return;
        baseUrlField.setText(env.getBaseUrl());
        // 注入全局请求头：先移除旧的"全局请求头"标记区，再在表格顶部插入当前环境的全局头。
        // 这里采用简单策略：保留接口自身请求头，把全局头前置并标记，避免重复。
        Map<String, String> globalHeaders = env.getGlobalHeaders();
        if (globalHeaders != null && !globalHeaders.isEmpty()) {
            // 收集当前表格中非全局头的请求头（即接口自身/Content-Type 等）
            java.util.List<Object[]> rows = new java.util.ArrayList<>();
            for (int i = 0; i < headerTableModel.getRowCount(); i++) {
                rows.add(new Object[]{
                        headerTableModel.getValueAt(i, 0),
                        headerTableModel.getValueAt(i, 1)
                });
            }
            headerTableModel.setRowCount(0);
            // 先放全局头
            for (Map.Entry<String, String> e : globalHeaders.entrySet()) {
                headerTableModel.addRow(new Object[]{e.getKey(), e.getValue()});
            }
            // 再放原有请求头（跳过与全局头同名的，避免重复）
            java.util.Set<String> globalKeys = new java.util.HashSet<>(globalHeaders.keySet());
            for (Object[] row : rows) {
                Object key = row[0];
                if (key instanceof String k && globalKeys.contains(k)) continue;
                headerTableModel.addRow(row);
            }
        }
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.emptyBottom(8),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        // 单行布局: Base URL + 方法 + URL + 发送
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        baseUrlField.setText(RestAutoLabSettingsState.getInstance(project).getBaseUrl());
        baseUrlField.setFont(baseUrlField.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        baseUrlField.setToolTipText("服务基础地址，如 http://localhost:8080");
        baseUrlField.setPreferredSize(new Dimension(200, 28));
        panel.add(baseUrlField, gbc);

        gbc.gridx = 1; gbc.weightx = 0.0;
        methodCombo.setPreferredSize(new Dimension(110, 28));
        methodCombo.setMinimumSize(new Dimension(110, 28));
        methodCombo.setFont(methodCombo.getFont().deriveFont(Font.BOLD, UiStyle.FONT_BODY));
        methodCombo.setRenderer(new HttpMethodCellRenderer());
        // 选中方法后联动更新 currentApi 与请求体（POST/PUT/PATCH 生成默认 body，其余清空）
        methodCombo.addItemListener(e -> {
            if (e.getStateChange() != java.awt.event.ItemEvent.SELECTED) return;
            if (currentApi == null) return;
            Object item = e.getItem();
            if (!(item instanceof String)) return;
            String newMethod = (String) item;
            if (newMethod.equals(currentApi.getHttpMethod())) return;

            currentApi.setHttpMethod(newMethod);

            if ("POST".equals(newMethod) || "PUT".equals(newMethod) || "PATCH".equals(newMethod)) {
                bodyEditor.setText(generateDefaultBody(currentApi));
            } else {
                bodyEditor.setText("");
            }

            statusLabel.setText("● 已切换方法: " + newMethod + " - " + currentApi.displayLabel());
        });
        panel.add(methodCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 1.0;
        urlField.setEditable(false);
        urlField.setFont(urlField.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        urlField.setBackground(JBColor.namedColor("TextField.background", Color.WHITE));
        panel.add(urlField, gbc);

        gbc.gridx = 3; gbc.weightx = 0.0;
        sendButton.putClientProperty("JButton.buttonType", "default");
        sendButton.setMargin(new Insets(4, 14, 4, 14));
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, UiStyle.FONT_BODY));
        sendButton.setFocusPainted(false);
        sendButton.setToolTipText("发送请求到当前接口");
        panel.add(sendButton, gbc);

        return panel;
    }

    private JPanel createParamsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        UiStyle.styleTable(paramTable);
        paramTable.setRowHeight(28);  // 参数表行高略大，更易点击编辑
        // 启用自动调整列宽（根据内容）
        paramTable.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);  // 手动控制列宽

        // 列宽设置 - 优化后的宽度
        paramTable.getColumnModel().getColumn(0).setPreferredWidth(130);  // 参数名
        paramTable.getColumnModel().getColumn(1).setPreferredWidth(90);   // 类型
        paramTable.getColumnModel().getColumn(2).setPreferredWidth(75);   // 位置
        paramTable.getColumnModel().getColumn(3).setPreferredWidth(200);  // 值
        paramTable.getColumnModel().getColumn(4).setPreferredWidth(65);   // 必填
        paramTable.getColumnModel().getColumn(5).setPreferredWidth(220);  // 描述

        // 类型列和必填列使用下拉框编辑器
        paramTable.getColumnModel().getColumn(1).setCellEditor(new TypeComboBoxEditor());
        paramTable.getColumnModel().getColumn(1).setCellRenderer(new TypeComboBoxRenderer());
        paramTable.getColumnModel().getColumn(4).setCellEditor(new RequiredComboBoxEditor());
        paramTable.getColumnModel().getColumn(4).setCellRenderer(new RequiredComboBoxRenderer());
        paramTable.getColumnModel().getColumn(2).setCellRenderer(new LocationCellRenderer());

        // v2.0.0：值列渲染器 - 文件类型参数显示「📎 文件名」（完整路径放 tooltip），长值也用 tooltip 辅助查看
        paramTable.getColumnModel().getColumn(3).setCellRenderer(new ValueCellRenderer());

        // 为值列添加智能编辑器（v2.0.0：文件选择 + 多行JSON编辑器 + 枚举提示）
        paramTable.getColumnModel().getColumn(3).setCellEditor(
                new SmartValueEditor(project, paramTable, attachmentPaths, attachmentPathLabels, gson));

        // 整行选择，更易读
        paramTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        paramTable.setRowSelectionAllowed(true);
        
        // 双击编辑时自动全选
        paramTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = paramTable.rowAtPoint(e.getPoint());
                    int col = paramTable.columnAtPoint(e.getPoint());
                    if (row >= 0 && col >= 0) {
                        paramTable.editCellAt(row, col);
                        java.awt.Component editor = paramTable.getEditorComponent();
                        if (editor instanceof javax.swing.JTextField) {
                            ((javax.swing.JTextField) editor).selectAll();
                        }
                    }
                }
            }
        });

        panel.add(new JBScrollPane(paramTable), BorderLayout.CENTER);

        // 底部工具栏 - 单行紧凑布局
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        
        JButton addBtn = iconButton("添加", AllIcons.General.Add, e -> addCustomParameter());
        addBtn.setToolTipText("添加自定义参数");
        JButton delBtn = iconButton("删除", AllIcons.General.Remove, e -> removeSelectedParameter());
        delBtn.setToolTipText("删除选中的参数");
        JButton clearBtn = iconButton("清空值", AllIcons.Actions.GC, e -> clearParameterValues());
        clearBtn.setToolTipText("清空所有参数的值");
        
        bottomBar.add(addBtn);
        bottomBar.add(delBtn);
        bottomBar.add(clearBtn);
        bottomBar.add(Box.createHorizontalStrut(12));
        
        // 筛选按钮
        JButton filterAllBtn = iconButton("全部", null, e -> filterParamsByLocation(null));
        filterAllBtn.setToolTipText("显示所有参数");
        filterAllBtn.putClientProperty("JButton.buttonType", "default");
        bottomBar.add(filterAllBtn);

        panel.add(bottomBar, BorderLayout.SOUTH);

        // 附件面板（文件参数）—— 挂在 NORTH：仅当接口含文件参数时由 updateAttachmentPanel 填充
        attachmentPanel.setLayout(new BoxLayout(attachmentPanel, BoxLayout.Y_AXIS));
        attachmentPanel.setBorder(JBUI.Borders.empty(4, 4, 8, 4));
        attachmentPanel.setBackground(JBColor.namedColor("Panel.background", new Color(250, 250, 250)));
        attachmentPanel.setVisible(false);
        panel.add(attachmentPanel, BorderLayout.NORTH);

        return panel;
    }

    /**
     * 重建附件面板：根据当前接口的"文件类型"参数（{@link ApiParameter#isFile()} == true）
     * 生成一行行"📎 参数名: [选择文件]  当前已选: ..."。
     *
     * 调用时机：
     * - 切换到新接口时（loadApiParameters 末尾）
     * - 用户在参数表里手动添加/删除/修改参数类型为文件参数时（暂只支持 load 时重建）
     */
    private void updateAttachmentPanel(ApiDefinition api) {
        attachmentPanel.removeAll();
        attachmentPaths.clear();
        attachmentPathLabels.clear();

        if (api == null) {
            attachmentPanel.setVisible(false);
            return;
        }

        List<ApiParameter> fileParams = new ArrayList<>();
        for (ApiParameter p : api.getParameters()) {
            if (p.isFile()) fileParams.add(p);
        }
        if (fileParams.isEmpty()) {
            attachmentPanel.setVisible(false);
            return;
        }

        // 标题
        JLabel title = new JLabel("📎 文件参数（" + fileParams.size() + " 个）— 必须选择本地文件，否则不会带上文件内容");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setBorder(JBUI.Borders.empty(2, 2, 6, 2));
        attachmentPanel.add(title);

        for (ApiParameter p : fileParams) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(JBUI.Borders.empty(2));

            // 左：参数名 + 类型
            JLabel nameLabel = new JLabel("<html><b>" + escapeHtml(p.getName()) + "</b>  <span color='gray'>("
                    + escapeHtml(p.getType()) + (p.isRequired() ? ", 必填" : "") + ")</span></html>");
            nameLabel.setPreferredSize(new Dimension(180, 24));
            row.add(nameLabel, BorderLayout.WEST);

            // 中：已选路径
            javax.swing.JLabel pathLabel = new javax.swing.JLabel("（未选择）");
            pathLabel.setForeground(JBColor.gray);
            pathLabel.setToolTipText("选择本地文件后显示文件名（悬浮查看完整路径）");
            row.add(pathLabel, BorderLayout.CENTER);

            // 右：选择按钮 + 清除按钮
            JPanel btnBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            btnBox.setOpaque(false);
            JButton pickBtn = new JButton("选择文件...", AllIcons.Actions.Upload);
            pickBtn.addActionListener(e -> {
                FileChooserDescriptor singleFile =
                        new FileChooserDescriptor(true, false, false, false, false, false);
                singleFile.setTitle("选择文件");
                VirtualFile vf = FileChooser.chooseFile(singleFile, project, null);
                if (vf != null) {
                    String path = vf.getPath();
                    attachmentPaths.put(p.getName(), path);
                    // v2.0.0：附件区也显示「📎 文件名」，与参数表值列渲染器保持一致
                    pathLabel.setText("📎 " + fileNameOf(path));
                    pathLabel.setForeground(UiStyle.JSON_KEY);
                    pathLabel.setToolTipText(path);
                    LOG.info("[附件] 选择文件成功 => 参数=" + p.getName() + ", 路径=" + path);
                    // 同步回参数表里对应行的"值"列，让 collectParameterValues 也能拿到（冗余但兼容）
                    syncFilePathToParamTable(p.getName(), path);
                }
            });
            JButton clearBtn = new JButton("清除");
            clearBtn.addActionListener(e -> {
                attachmentPaths.put(p.getName(), "");
                pathLabel.setText("（未选择）");
                pathLabel.setForeground(JBColor.gray);
                pathLabel.setToolTipText("选择本地文件后显示文件名（悬浮查看完整路径）");
                syncFilePathToParamTable(p.getName(), "");
            });
            btnBox.add(pickBtn);
            btnBox.add(clearBtn);
            row.add(btnBox, BorderLayout.EAST);

            attachmentPaths.put(p.getName(), "");
            attachmentPathLabels.put(p.getName(), pathLabel);
            attachmentPanel.add(row);
        }

        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    /** 同步文件参数值到 paramTable 中对应行（让 collectParameterValues 也能拿到，作为冗余兜底） */
    private void syncFilePathToParamTable(String paramName, String path) {
        for (int i = 0; i < paramTableModel.getRowCount(); i++) {
            Object name = paramTableModel.getValueAt(i, 0);
            if (paramName.equals(name)) {
                paramTableModel.setValueAt(path, i, 3);
                return;
            }
        }
    }

    /** 从完整路径提取文件名（兼容 / 与 \），用于文件参数的友好展示 */
    private static String fileNameOf(String path) {
        if (path == null || path.isEmpty()) return "";
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    /** 简单 HTML 转义，避免参数名/类型里的特殊字符破坏 JLabel 渲染 */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel createHeadersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        UiStyle.styleTable(headerTable);
        panel.add(new JBScrollPane(headerTable), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = iconButton("添加", AllIcons.General.Add, e ->
                headerTableModel.addRow(new Object[]{"", ""}));
        JButton delBtn = iconButton("删除", AllIcons.General.Remove, e -> {
            int row = headerTable.getSelectedRow();
            if (row >= 0) headerTableModel.removeRow(row);
        });
        btnPanel.add(addBtn);
        btnPanel.add(delBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createBodyTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // v3: Body格式选择器
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topBar.add(new JBLabel("格式:"));
        bodyFormatCombo = new JComboBox<>(new String[]{"JSON", "x-www-form-urlencoded", "Raw"});
        bodyFormatCombo.setPreferredSize(new Dimension(160, 26));
        topBar.add(bodyFormatCombo);

        cookieStatusLabel = new JBLabel("Cookie: (无)");
        UiStyle.hint(cookieStatusLabel);
        topBar.add(Box.createHorizontalStrut(20));
        topBar.add(cookieStatusLabel);

        panel.add(topBar, BorderLayout.NORTH);

        bodyEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_MONO));
        // v2.0.0：长 JSON 不自动换行（水平滚动查看），与响应区观感一致
        bodyEditor.setLineWrap(false);
        bodyEditor.setWrapStyleWord(false);
        bodyEditor.setTabSize(2);
        bodyEditor.setRows(BODY_ROWS_COLLAPSED);
        bodyScrollPane = new JBScrollPane(bodyEditor);
        panel.add(bodyScrollPane, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton fmtBtn = iconButton("格式化", AllIcons.Actions.PrettyPrint, e -> formatJson());
        JButton clrBtn = iconButton("清空", AllIcons.Actions.GC, e -> bodyEditor.setText(""));
        btnPanel.add(fmtBtn);
        btnPanel.add(clrBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createResponsePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // === 顶部状态栏（带色码徽章） ===
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        statusPanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(6, 8)));
        statusPanel.setBackground(JBColor.namedColor("Panel.background", new Color(248, 249, 250)));
        responseStatusLabel.setFont(responseStatusLabel.getFont().deriveFont(Font.BOLD, UiStyle.FONT_BODY));
        responseTimeLabel.setFont(responseTimeLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        responseSizeLabel.setFont(responseSizeLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        statusPanel.add(responseStatusLabel);
        statusPanel.add(createSeparator());
        statusPanel.add(responseTimeLabel);
        statusPanel.add(createSeparator());
        statusPanel.add(responseSizeLabel);
        panel.add(statusPanel, BorderLayout.NORTH);

        // === 响应 body 容器（卡片式：带色码边线） ===
        responseContentPanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2)));
        responseContentPanel.setBackground(JBColor.namedColor("Editor.background", new Color(250, 250, 250)));
        panel.add(responseContentPanel, BorderLayout.CENTER);

        // === 响应内容区域（支持文本/树形切换） ===
        // v2.0.0：文本视图使用 JsonSyntaxPane（JSON 语法高亮 + Ctrl+滚轮缩放 + 右键菜单）
        responsePane.setEditable(false);
        responsePane.setToolTipText("提示：Ctrl+滚轮 或 Ctrl++/- 可缩放字体，右键提供复制/全选/缩放");
        JScrollPane textScroll = new JBScrollPane(responsePane);
        textScroll.setBorder(JBUI.Borders.empty());
        textScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        textScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        responseContentPanel.add(textScroll, "text");

        // 保持 responseArea 同步（用于后台数据兼容，不直接显示）
        responseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_MONO));
        responseArea.setEditable(false);
        responseArea.setLineWrap(false);

        // 树形视图
        responseJsonTree.setRootVisible(false);
        responseJsonTree.setShowsRootHandles(true);
        responseJsonTree.setFont(responseJsonTree.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        responseJsonTree.setCellRenderer(new JsonTreeNodeRenderer());
        JScrollPane treeScroll = new JBScrollPane(responseJsonTree);
        treeScroll.setBorder(JBUI.Borders.empty());
        responseContentPanel.add(treeScroll, "tree");

        // 默认显示文本视图
        responseCardLayout.show(responseContentPanel, "text");

        // === 底部操作按钮 ===
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        JButton viewToggleBtn = iconButton("🌳 树形视图", AllIcons.Actions.ShowAsTree, e -> toggleResponseView());
        viewToggleBtn.setToolTipText("🌳 在文本/树形视图间切换");

        JButton fmtBtn = iconButton("📋 格式化", AllIcons.Actions.PrettyPrint, e -> formatResponseJson());
        fmtBtn.setToolTipText("📋 格式化JSON响应（美化显示）");

        JButton copyBtn = iconButton("📄 复制", AllIcons.Actions.Copy, e -> copyResponseToClipboard());
        copyBtn.setToolTipText("📄 复制响应内容到剪贴板");

        JButton clearBtn = iconButton("🗑️ 清空", AllIcons.Actions.GC, e -> {
            responseArea.setText("");
            responsePane.setTextAndHighlight("");
            responseViewTree = false;
        });
        clearBtn.setToolTipText("🗑️ 清空响应内容");

        JBLabel hintLabel = new JBLabel("💡 提示: 切换『树形视图』可折叠展开JSON节点；状态/耗时按级别自动着色");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC, UiStyle.FONT_TINY));
        hintLabel.setForeground(JBColor.GRAY);

        btnPanel.add(viewToggleBtn);
        btnPanel.add(fmtBtn);
        btnPanel.add(copyBtn);
        btnPanel.add(clearBtn);

        JPanel southPanel = new JPanel(new BorderLayout(4, 0));
        southPanel.add(btnPanel, BorderLayout.WEST);
        southPanel.add(hintLabel, BorderLayout.CENTER);
        panel.add(southPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JSeparator createSeparator() {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 16));
        sep.setForeground(JBColor.border());
        return sep;
    }

    private JPanel createTestTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // 顶部按钮栏 - 精简设计，只保留核心操作
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        
        // 批量测试按钮（可切换开始/停止）
        batchTestBtn = iconButton(" 批量测试", AllIcons.Actions.Execute, e -> toggleBatchTest());
        batchTestBtn.setToolTipText("点击开始批量测试所有API，测试中再次点击可停止");
        batchTestBtn.putClientProperty("JButton.buttonType", "default");
        btnPanel.add(batchTestBtn);
        
        // 单接口测试按钮
        JButton runCurBtn = iconButton("测试当前", AllIcons.Actions.Execute, e -> runCurrentTest());
        runCurBtn.setToolTipText("测试当前选中的API接口，结果以JSON格式展示");
        btnPanel.add(runCurBtn);
        
        // 清空结果按钮
        JButton clearBtn = iconButton("清空", AllIcons.Actions.GC, e -> {
            testResultArea.setText("");
            statusLabel.setText("● 测试结果已清空");
        });
        clearBtn.setToolTipText("清空测试结果区域");
        btnPanel.add(clearBtn);
        
        panel.add(btnPanel, BorderLayout.NORTH);

        // 进度条
        testProgressBar.setVisible(false);
        testProgressBar.setStringPainted(true);
        testProgressBar.setPreferredSize(new Dimension(-1, 20));
        panel.add(testProgressBar, BorderLayout.SOUTH);

        // 测试结果区域
        testResultArea.setFont(new Font("Monospaced", Font.PLAIN, (int) UiStyle.FONT_MONO));
        testResultArea.setEditable(false);
        testResultArea.setLineWrap(true);
        testResultArea.setWrapStyleWord(true);
        testResultArea.setText("点击「批量测试」测试所有API，或「测试当前」测试单个接口\n测试结果将以JSON格式展示\n\n等待操作...\n");
        panel.add(new JBScrollPane(testResultArea), BorderLayout.CENTER);

        return panel;
    }

    /**
     * 切换批量测试状态（开始/停止）
     */
    private void toggleBatchTest() {
        if (batchTestRunning) {
            // 停止测试
            batchTestCancelled = true;
            batchTestBtn.setText("▶ 批量测试");
            batchTestBtn.setToolTipText("点击开始批量测试所有API，测试中再次点击可停止");
            statusLabel.setText("● 正在停止...");
        } else {
            // 开始测试
            runAllTests();
        }
    }

    private JPanel createAiTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8));

        // === 配置状态栏（卡片样式） ===
        JPanel statusCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        statusCard.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(6, 10)));

        JBLabel configStatusLabel = new JBLabel("🤖 AI配置:");
        configStatusLabel.setFont(configStatusLabel.getFont().deriveFont(Font.BOLD, UiStyle.FONT_HINT));
        statusCard.add(configStatusLabel);

        aiConfigInfoLabel = new JBLabel(getAiConfigSummary());
        UiStyle.hint(aiConfigInfoLabel);
        statusCard.add(aiConfigInfoLabel);

        JButton configBtn = iconButton("配置", AllIcons.General.Settings, e -> showAiConfigDialog());
        configBtn.setToolTipText("⚙️ 配置AI服务器、API Key和模型");
        statusCard.add(configBtn);

        panel.add(statusCard);
        panel.add(Box.createVerticalStrut(8));

        // === 生成控制区（单行紧凑） ===
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controlPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        JBLabel scenarioLabel = new JBLabel("📊 场景:");
        UiStyle.hint(scenarioLabel);
        controlPanel.add(scenarioLabel);

        scenarioCombo.setPreferredSize(new Dimension(130, 26));
        scenarioCombo.setFont(scenarioCombo.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        scenarioCombo.setToolTipText("选择测试场景：正常/边界/异常/全量");
        controlPanel.add(scenarioCombo);

        // 一伦优化 #6：原"生成参数" + "全量生成"两个独立按钮合并为单个"🤖 AI 助手"下拉。
        // 弹层含两个子动作：按当前场景生成 / 全量生成（正常+边界+异常）。
        JButton aiAssistantBtn = iconButton("🤖 AI 助手", AllIcons.Actions.Lightning, null);
        aiAssistantBtn.setToolTipText("AI 参数生成助手（含按当前场景生成 / 全量生成两个子动作）");
        aiAssistantBtn.putClientProperty("JButton.buttonType", "default");
        aiAssistantBtn.addActionListener(e -> showAiAssistantMenu(aiAssistantBtn));
        controlPanel.add(aiAssistantBtn);

        panel.add(controlPanel);
        panel.add(Box.createVerticalStrut(8));

        // === 使用提示（替代此前的 AI 日志面板） ===
        JBLabel hint = new JBLabel("<html><div style='color:gray;font-size:11px;'>"
                + "💡 生成完成后参数会自动填入『参数』Tab，状态栏会显示『生成完成 (AI, N 个参数)』。"
                + "</div></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        hint.setBorder(JBUI.Borders.empty(8, 12, 4, 4));
        panel.add(hint);
        panel.add(Box.createVerticalGlue());

        return panel;
    }
    
    /**
     * 一伦优化 #6：AI 助手下拉菜单
     * <p>把"按当前场景生成参数"和"全量生成（正常+边界+异常）"两个动作打包到单个 JPopupMenu。</p>
     */
    private void showAiAssistantMenu(JButton invoker) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem genItem = new JMenuItem("⚡ AI 生成参数（按当前场景）", AllIcons.Actions.Lightning);
        genItem.addActionListener(ev -> {
            AiParameterService.TestScenario s = (AiParameterService.TestScenario) scenarioCombo.getSelectedItem();
            generateAiParameters(s);
        });
        menu.add(genItem);

        menu.addSeparator();

        JMenuItem genAllItem = new JMenuItem("🧪 AI 全量生成（正常+边界+异常）", AllIcons.Actions.RunAll);
        genAllItem.addActionListener(ev -> {
            scenarioCombo.setSelectedItem(AiParameterService.TestScenario.FULL);
            generateAiParameters(AiParameterService.TestScenario.FULL);
        });
        menu.add(genAllItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * 获取AI配置摘要显示
     */
    private String getAiConfigSummary() {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        String url = settings.getAiServerUrl();
        String model = settings.getAiModel();
        
        if (url.isBlank()) {
            return "未配置";
        }
        
        // 截断过长的URL
        String shortUrl = url.length() > 40 ? url.substring(0, 37) + "..." : url;
        return shortUrl + " | " + model;
    }

    /**
     * 创建带图标的按钮
     */
    private JButton iconButton(String text, Icon icon, java.awt.event.ActionListener listener) {
        // 统一用 roundRect 圆角描边风格，告别「方块中间有字」的生硬观感
        return UiStyle.button(text, icon, listener);
    }

    private void setupActions() {
        sendButton.addActionListener(e -> sendRequest());
        baseUrlField.addActionListener(e ->
                RestAutoLabSettingsState.getInstance(project).setBaseUrl(baseUrlField.getText().trim()));
    }

    // ================================================================
    // 公共方法
    // ================================================================

    public void loadApi(ApiDefinition api) {
        loadApi(api, null);
    }

    /**
     * 加载接口到调试面板。
     *
     * <p>{@code folderId} 标识当前接口在收藏模式下所属的文件夹：
     * <ul>
     *   <li>非空：从 {@link StarredFolderService} 读取该文件夹下此接口
     *       的实时参数并覆盖到参数表，并在发送请求时回写——保证同一接口在不同文件夹中
     *       各自归档、互不干扰。</li>
     *   <li>null：全量视图，仅使用接口默认参数，不回写。</li>
     * </ul></p>
     */
    public void loadApi(ApiDefinition api, String folderId) {
        currentApi = api;
        currentFolderId = folderId;
        methodCombo.setSelectedItem(api.getHttpMethod());
        urlField.setText(api.getUrl());

        // 合并所有参数到一个表格（通过位置列区分）
        paramTableModel.setRowCount(0);

        // 添加路径参数
        for (ApiParameter param : api.pathParameters()) {
            paramTableModel.addRow(new Object[]{
                    param.getName(),
                    param.getType(),
                    "PATH",
                    param.generateDefaultValue(),
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
        }

        // 添加查询参数
        for (ApiParameter param : api.queryParameters()) {
            paramTableModel.addRow(new Object[]{
                    param.getName(),
                    param.getType(),
                    "QUERY",
                    param.generateDefaultValue(),
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
        }

        // 添加请求头参数
        for (ApiParameter param : api.headerParameters()) {
            paramTableModel.addRow(new Object[]{
                    param.getName(),
                    param.getType(),
                    "HEADER",
                    param.generateDefaultValue(),
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
        }

        // 添加请求体参数（文件参数占位，等用户在附件面板选择）
        for (ApiParameter param : api.bodyParameters()) {
            String defaultVal = param.isFile()
                    ? "📎 请在右侧'文件参数'区选择本地文件"
                    : param.generateDefaultValue();
            paramTableModel.addRow(new Object[]{
                    param.getName(),
                    param.getType(),
                    param.isFile() ? "FILE" : "BODY",
                    defaultVal,
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
        }

        // 收藏模式下：覆盖该文件夹下此接口的实时参数（与其它文件夹互不干扰）
        if (folderId != null) {
            try {
                Map<String, String> saved = StarredFolderService.getInstance(project)
                        .getParams(folderId, api.uniqueKey());
                if (saved != null && !saved.isEmpty()) {
                    for (int i = 0; i < paramTableModel.getRowCount(); i++) {
                        Object name = paramTableModel.getValueAt(i, 0);
                        if (name instanceof String && saved.containsKey(name)) {
                            paramTableModel.setValueAt(saved.get(name), i, 3);
                        }
                    }
                }
            } catch (Exception ignore) {
                // 读取实时参数失败时退化为默认参数，不阻断加载
            }
        }

        // 同步附件面板
        updateAttachmentPanel(api);

        // 如果没有显式参数，显示默认请求头
        if (paramTableModel.getRowCount() == 0) {
            headerTableModel.setRowCount(0);
            headerTableModel.addRow(new Object[]{RestAutoLabConstants.HEADER_CONTENT_TYPE, api.getConsumes()});
            headerTableModel.addRow(new Object[]{RestAutoLabConstants.HEADER_ACCEPT, api.getProduces()});
            api.getHeaders().forEach((k, v) -> headerTableModel.addRow(new Object[]{k, v}));
        }

        String method = api.getHttpMethod();
        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            bodyEditor.setText(generateDefaultBody(api));
        } else {
            bodyEditor.setText("");
        }

        responseArea.setText("");
        responseStatusLabel.setText("状态: -");
        responseStatusLabel.setForeground(JBColor.foreground());
        responseTimeLabel.setText("耗时: -");

        tabbedPane.setSelectedIndex(0);
        statusLabel.setText("● 已加载: " + api.displayLabel());
    }

    // ================================================================
    // 核心操作
    // ================================================================

    private void sendRequest() {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }
        sendButton.setEnabled(false);
        statusLabel.setText("○ 请求发送中...");

        Map<String, String> params = collectParameterValues();
        Map<String, String> headers = collectHeaderValues();
        String body = bodyEditor.getText();
        String requestBody = (body != null && !body.isBlank()) ? body : null;

        // v3: 获取body格式和环境
        final String finalBodyFormat;
        if (bodyFormatCombo != null) {
            String fmt = (String) bodyFormatCombo.getSelectedItem();
            if ("x-www-form-urlencoded".equals(fmt)) {
                finalBodyFormat = HttpExecutorService.BODY_FORMAT_FORM;
            } else if ("Raw".equals(fmt)) {
                finalBodyFormat = HttpExecutorService.BODY_FORMAT_RAW;
            } else {
                finalBodyFormat = HttpExecutorService.BODY_FORMAT_JSON;
            }
        } else {
            finalBodyFormat = HttpExecutorService.BODY_FORMAT_JSON;
        }
        final Environment env = getCurrentEnvironment();

        // 详细日志：记录要发送的请求信息
        LOG.info("[执行请求] 开始 => API=" + currentApi.getHttpMethod() + " " + currentApi.getUrl()
                + ", baseUrl=" + baseUrlField.getText().trim()
                + ", bodyFormat=" + finalBodyFormat
                + ", 参数个数=" + params.size() + ", 请求头个数=" + headers.size());
        if (!attachmentPaths.isEmpty()) {
            LOG.info("[执行请求] 文件参数附件 => " + attachmentPaths);
        }
        if (!params.isEmpty()) {
            LOG.info("[执行请求] 参数值 => " + params);
        }
        if (requestBody != null && !requestBody.isBlank()) {
            LOG.info("[执行请求] 自定义请求体 => 长度=" + requestBody.length()
                    + ", 预览=" + (requestBody.length() > 500 ? requestBody.substring(0, 500) + "..." : requestBody));
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService http = HttpExecutorService.getInstance(project);
            TestResult result = http.executeRequest(currentApi, baseUrlField.getText().trim(),
                    params, headers, requestBody, finalBodyFormat, env, new ArrayList<>(currentAssertions));

            ApplicationManager.getApplication().invokeLater(() -> {
                displayResponse(result);
                sendButton.setEnabled(true);
                statusLabel.setText("● " + result.summary());
            });
        });
    }

    private void displayResponse(TestResult result) {
        int code = result.getStatusCode();
        long ms = result.getDurationMs();
        int size = result.getResponseBody() == null ? 0 : result.getResponseBody().length();

        // 状态：HTML 徽章，色码背景 + 状态码大字 + 状态文本
        JBColor sc = statusColor(code);
        String scHex = String.format("#%02X%02X%02X", sc.getRGB() & 0xFF, (sc.getRGB() >> 8) & 0xFF, (sc.getRGB() >> 16) & 0xFF);
        responseStatusLabel.setText("<html><span style='background-color:" + scHex
                + ";color:white;padding:2px 8px;border-radius:4px;font-weight:bold;'>"
                + statusGlyph(code) + " " + code + " " + httpStatusText(code)
                + "</span></html>");

        // 耗时：色码 + 数值 + 分级标签
        JBColor tc = timeColor(ms);
        String tcHex = String.format("#%02X%02X%02X", tc.getRGB() & 0xFF, (tc.getRGB() >> 8) & 0xFF, (tc.getRGB() >> 16) & 0xFF);
        String speedTag = ms < 200 ? "(快)" : ms < 800 ? "(正常)" : ms < 2000 ? "(慢)" : "(极慢)";
        responseTimeLabel.setText("<html><span style='color:gray'>耗时</span> <b style='color:" + tcHex + "'>"
                + ms + " ms</b> <span style='color:gray'>" + speedTag + "</span></html>");

        // 大小：字段弱化 + 值
        responseSizeLabel.setText("<html><span style='color:gray'>大小</span> <b>"
                + formatBytes(size) + "</b> <span style='color:gray'>(" + size + " chars)</span></html>");

        String body = result.getResponseBody() == null ? "" : result.getResponseBody();
        responseArea.setText(body);
        responseArea.setCaretPosition(0);
        // v2.0.0：响应文本视图走 JsonSyntaxPane（语法高亮 + 缩放）
        responsePane.setTextAndHighlight(body);
        responsePane.setCaretPosition(0);
        responseCardLayout.show(responseContentPanel, "text");
        responseViewTree = false;
        buildResponseJsonTree(result.getResponseBody());

        // 一伦优化 #5：响应已独立为底部常驻层，无需切 Tab
    }

    private String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** 根据状态码返回可读描述（如 200 -> "OK", 404 -> "Not Found"） */
    private String httpStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> code >= 200 && code < 300 ? "Success" : code >= 400 && code < 500 ? "Client Error" : code >= 500 ? "Server Error" : "Unknown";
        };
    }

    /** 按状态码分级返回主题色（light + dark 两套） */
    private JBColor statusColor(int code) {
        if (code >= 200 && code < 300) {
            return new JBColor(new Color(40, 167, 69), new Color(72, 199, 116));
        } else if (code >= 300 && code < 400) {
            return new JBColor(new Color(252, 175, 23), new Color(255, 199, 95));
        } else if (code >= 400 && code < 500) {
            return new JBColor(new Color(232, 138, 32), new Color(255, 175, 90));
        } else if (code >= 500) {
            return new JBColor(new Color(220, 53, 69), new Color(255, 99, 114));
        }
        return new JBColor(new Color(108, 117, 125), new Color(173, 181, 189));
    }

    /** 按耗时给出颜色（< 200ms 绿，< 800ms 黄，< 2000ms 橙，>= 2000ms 红） */
    private JBColor timeColor(long ms) {
        if (ms < 200) return new JBColor(new Color(40, 167, 69), new Color(72, 199, 116));
        if (ms < 800) return new JBColor(new Color(23, 162, 184), new Color(72, 199, 200));
        if (ms < 2000) return new JBColor(new Color(252, 175, 23), new Color(255, 199, 95));
        return new JBColor(new Color(220, 53, 69), new Color(255, 99, 114));
    }

    /** 返回值类型颜色（用于 JSON 树形渲染器） */
    private JBColor jsonTypeColor(String value) {
        if (value == null) return new JBColor(new Color(108, 117, 125), new Color(173, 181, 189));
        if (value.equals("null")) return new JBColor(new Color(108, 117, 125), new Color(173, 181, 189));
        if (value.equals("true") || value.equals("false")) return new JBColor(new Color(255, 140, 0), new Color(255, 175, 64));
        try { Double.parseDouble(value); return new JBColor(new Color(23, 162, 184), new Color(72, 199, 200)); }
        catch (NumberFormatException ignored) { /* not a number */ }
        if (value.startsWith("\"") && value.endsWith("\"")) return new JBColor(new Color(40, 167, 69), new Color(72, 199, 116));
        return new JBColor(JBColor.foreground(), JBColor.foreground());
    }

    /** 用 Unicode 字符美化状态指示点 */
    private String statusGlyph(int code) {
        return code >= 200 && code < 300 ? "✓" : code >= 400 ? "✗" : "●";
    }

    private Map<String, String> collectParameterValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < paramTableModel.getRowCount(); i++) {
            Object name = paramTableModel.getValueAt(i, 0);
            Object value = paramTableModel.getValueAt(i, 3);
            if (name instanceof String && value instanceof String) {
                String n = (String) name;
                String v = (String) value;
                if (!v.isBlank()) values.put(n, v);
            }
        }
        return values;
    }

    /** 收集参数表全部行（含空值），用于收藏模式下回写各文件夹的实时参数快照。 */
    private Map<String, String> collectAllParameterPairs() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < paramTableModel.getRowCount(); i++) {
            Object name = paramTableModel.getValueAt(i, 0);
            Object value = paramTableModel.getValueAt(i, 3);
            if (name instanceof String) {
                String n = (String) name;
                if (!n.isBlank()) values.put(n, value instanceof String ? (String) value : "");
            }
        }
        return values;
    }

    private Map<String, String> collectHeaderValues() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            Object name = headerTableModel.getValueAt(i, 0);
            Object value = headerTableModel.getValueAt(i, 1);
            if (name instanceof String && value instanceof String) {
                String n = (String) name;
                String v = (String) value;
                if (!n.isBlank()) headers.put(n, v);
            }
        }
        return headers;
    }

    private String generateDefaultBody(ApiDefinition api) {
        List<ApiParameter> bodyParams = api.bodyParameters();
        if (bodyParams.isEmpty()) return "{}";
        if (bodyParams.size() == 1 && bodyParams.get(0).isComplexType()) {
            return bodyParams.get(0).generateDefaultValue();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (ApiParameter p : bodyParams) map.put(p.getName(), p.generateDefaultValue());
        return gson.toJson(map);
    }

    private void generateAiParameters(AiParameterService.TestScenario scenario) {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }
            
        statusLabel.setText("○ AI生成中...");
    
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AiParameterService ai = AiParameterService.getInstance(project);
                
            // 显示Prompt预览
            for (ApiParameter param : currentApi.getParameters()) {
            }
                
            // 调用AI并获取原始响应
            AiParameterService.GenerateResult result = ai.generateParametersWithRaw(currentApi, scenario);
    
            ApplicationManager.getApplication().invokeLater(() -> {
                List<Map<String, String>> sets = result.getParameters();
                String errMsg = result.getErrorMessage();

                // ===== 失败情况：显示失败原因 =====
                if (errMsg != null) {
                    String rawResponse = result.getRawResponse();
                    StringBuilder failInfo = new StringBuilder();
                    failInfo.append("╔══════════════════════════════════════════╗\n");
                    failInfo.append("║          ⚠ AI 生成失败                   ║\n");
                    failInfo.append("╚══════════════════════════════════════════╝\n\n");
                    failInfo.append("【失败原因】\n").append(errMsg).append("\n\n");
                    if (rawResponse != null && !rawResponse.isBlank()) {
                        failInfo.append("【AI原始响应】\n").append(rawResponse);
                    }
                    testResultArea.setText(failInfo.toString());
                    testResultArea.setCaretPosition(0);
                    // 一伦优化 #5：响应已独立为底部常驻层，无需切 Tab
                    statusLabel.setText("❌ AI生成失败: " + errMsg.split("\n")[0]);
                    return;
                }

                // ===== 成功情况：填充参数表格 + 跳转请求体显示格式化JSON =====
                if (!sets.isEmpty()) {
                    Map<String, String> first = sets.get(0);

                    // 自动填充到参数表格
                    int filledCount = 0;
                    List<Integer> modifiedRows = new ArrayList<>();

                    for (int i = 0; i < paramTableModel.getRowCount(); i++) {
                        Object name = paramTableModel.getValueAt(i, 0);
                        if (name instanceof String && first.containsKey(name)) {
                            String value = first.get(name);
                            paramTableModel.setValueAt(value, i, 3);
                            modifiedRows.add(i);
                            filledCount++;
                        }
                    }

                    // 将生成的参数转为格式化JSON填入请求体编辑器
                    // 关键：Map 的值是 String，其中嵌套对象/数组已被 jsonObjectToMap 序列化为
                    // JSON 字符串。若直接 gson.toJson(map) 会把这些字符串再转义一次，
                    // 导致请求体里出现 \"、\\n 等双重转义、无法正常阅读。
                    // 这里把每个值尝试解析回 JsonElement，重建为真正的嵌套结构再美化输出。
                    try {
                        com.google.gson.JsonObject nested = mapToNestedJson(first);
                        bodyEditor.setText(gson.toJson(nested));
                        bodyEditor.setCaretPosition(0);
                    } catch (Exception ex) {
                        bodyEditor.setText(gson.toJson(first));
                    }

                    // 跳转到请求体Tab页（index=2），显示格式化JSON内容
                    tabbedPane.setSelectedIndex(2);

                    // 多组数据（边界/异常/全量场景）：第一组已填入参数表与请求体，
                    // 其余组写入响应Tab供用户查看与挑选，避免多组结果被静默丢弃。
                    if (sets.size() > 1) {
                        com.google.gson.JsonArray allGroups = new com.google.gson.JsonArray();
                        for (int gi = 0; gi < sets.size(); gi++) {
                            com.google.gson.JsonObject groupObj = mapToNestedJson(sets.get(gi));
                            // 标注组序号，便于用户识别
                            com.google.gson.JsonObject labeled = new com.google.gson.JsonObject();
                            labeled.addProperty("_组序号", "第" + (gi + 1) + "组/共" + sets.size() + "组");
                            for (String key : groupObj.keySet()) {
                                labeled.add(key, groupObj.get(key));
                            }
                            allGroups.add(labeled);
                        }
                        com.google.gson.Gson pretty = gson.newBuilder().setPrettyPrinting().create();
                        StringBuilder multiText = new StringBuilder();
                        multiText.append("╔══════════════════════════════════════════╗\n");
                        multiText.append("║  共生成 ").append(sets.size()).append(" 组测试数据（已填入第1组到参数表与请求体）║\n");
                        multiText.append("╚══════════════════════════════════════════╝\n\n");
                        multiText.append(pretty.toJson(allGroups));
                        testResultArea.setText(multiText.toString());
                        testResultArea.setCaretPosition(0);

                        statusLabel.setText("● 生成完成 (" + (result.isUsedAi() ? "AI" : "默认")
                                + ", 共" + sets.size() + "组/" + filledCount + "个参数, 第1组已填入请求体，全部见响应Tab)");
                    } else {
                        statusLabel.setText("● 生成完成 (" + (result.isUsedAi() ? "AI" : "默认")
                                + ", " + filledCount + " 个参数, 已填入请求体)");
                    }

                    SwingUtilities.invokeLater(() -> {
                        try {
                            Thread.sleep(200);
                            highlightModifiedRows(modifiedRows);
                        } catch (InterruptedException e) { /* ignore */ }
                    });
                } else {
                    statusLabel.setText("○ 生成失败：未解析出参数");
                    testResultArea.setText("AI返回内容为空，未解析出有效参数。\n\n原始响应:\n" + result.getRawResponse());
                    // 一伦优化 #5：响应已独立为底部常驻层，无需切 Tab
                }
            });
        });
    }

    /**
     * 将 AI 生成的参数 Map 转为嵌套的 JsonObject。
     * <p>AI 响应解析时（{@code jsonObjectToMap}）会把嵌套对象/数组序列化为 JSON 字符串存进 Map。
     * 直接 {@code gson.toJson(map)} 会把这些字符串再转义一次，导致请求体里出现
     * {@code \"}、{@code \\n} 等双重转义，无法阅读。</p>
     * <p>本方法逐个值尝试解析回 JsonElement：能解析为 JSON 的还原为真实结构（对象/数组），
     * 解析失败的保留为原始字符串，从而得到规整的、可直接阅读的格式化 JSON。</p>
     */
    private com.google.gson.JsonObject mapToNestedJson(Map<String, String> map) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (val == null) {
                obj.add(key, com.google.gson.JsonNull.INSTANCE);
                continue;
            }
            String trimmed = val.trim();
            // 仅当看起来像 JSON 结构时才尝试解析，避免把普通字符串误判
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    com.google.gson.JsonElement parsed = JsonParser.parseString(trimmed);
                    obj.add(key, parsed);
                    continue;
                } catch (Exception ignore) {
                    // 解析失败则当作普通字符串
                }
            }
            obj.addProperty(key, val);
        }
        return obj;
    }

    private void runCurrentTest() {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }
        
        testProgressBar.setVisible(true);
        testProgressBar.setIndeterminate(true);
        testResultArea.setText("");
        statusLabel.setText("● 正在测试: " + currentApi.getName());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService http = HttpExecutorService.getInstance(project);
            TestResult r = http.executeRequest(currentApi, baseUrlField.getText().trim(),
                    collectParameterValues(), collectHeaderValues(),
                    bodyEditor.getText(), HttpExecutorService.BODY_FORMAT_JSON,
                    getCurrentEnvironment(), new ArrayList<>(currentAssertions));
            ApplicationManager.getApplication().invokeLater(() -> {
                testProgressBar.setVisible(false);
                
                if (r == null) {
                    testResultArea.setText("{\n  \"error\": \"请求失败: 无响应\"\n}");
                    statusLabel.setText("❌ 测试失败");
                    return;
                }
                
                // 构建结构化JSON测试结果
                Map<String, Object> resultJson = new LinkedHashMap<>();
                resultJson.put("api", currentApi.getName());
                resultJson.put("method", currentApi.getHttpMethod());
                resultJson.put("url", r.getRequestUrl());
                resultJson.put("statusCode", r.getStatusCode());
                resultJson.put("status", r.isPassed() ? "PASSED" : (r.getStatus() == TestStatus.ERROR ? "ERROR" : "FAILED"));
                resultJson.put("durationMs", r.getDurationMs());
                
                // 响应体：尝试解析为JSON对象
                String responseBody = r.getResponseBody();
                if (responseBody != null && !responseBody.isBlank()) {
                    try {
                        resultJson.put("response", JsonParser.parseString(responseBody));
                    } catch (Exception e) {
                        resultJson.put("response", responseBody);
                    }
                } else {
                    resultJson.put("response", null);
                }
                
                if (r.getStatus() == TestStatus.ERROR && !r.getErrorMessage().isEmpty()) {
                    resultJson.put("error", r.getErrorMessage());
                }
                
                String formatted = gson.toJson(resultJson);
                testResultArea.setText(formatted);
                testResultArea.setCaretPosition(0);
                
                String statusIcon = r.isPassed() ? "✅" : "❌";
                statusLabel.setText(statusIcon + " 测试完成: " + currentApi.getName() + " (" + r.getStatusCode() + ", " + r.getDurationMs() + "ms)");
            });
        });
    }

    private void runAllTests() {
        List<ApiDefinition> apis = ApiScannerService.getInstance(project).getCachedApis();
        if (apis.isEmpty()) {
            Messages.showWarningDialog(project, "暂无API，请先扫描", "提示");
            return;
        }
        
        // 设置状态为运行中
        batchTestRunning = true;
        batchTestCancelled = false;
        
        // 更新按钮状态为"停止"
        batchTestBtn.setText("⏹ 停止测试");
        batchTestBtn.setToolTipText("点击停止正在进行的批量测试");
        
        testProgressBar.setVisible(true);
        testProgressBar.setMaximum(apis.size());
        testProgressBar.setValue(0);
        testProgressBar.setString("0/" + apis.size());
        testResultArea.setText("");
        statusLabel.setText("● 批量测试开始 (" + apis.size() + " 个API)");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService http = HttpExecutorService.getInstance(project);
            TestProfile profile = new TestProfile();
            profile.setName("批量测试");
            profile.setBaseUrl(baseUrlField.getText().trim());
            profile.setGlobalHeaders(new LinkedHashMap<>(collectHeaderValues()));

            int passed = 0, failed = 0, error = 0;
            for (int i = 0; i < apis.size(); i++) {
                // 检查是否取消
                if (batchTestCancelled) {
                    int completed = i;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        testProgressBar.setVisible(false);
                        batchTestBtn.setText("批量测试");
                        batchTestBtn.setToolTipText("点击开始批量测试所有API，测试中再次点击可停止");
                        testResultArea.append("\n⏹ 测试已停止 (完成 " + completed + "/" + apis.size() + ")\n");
                        statusLabel.setText("● 批量测试已停止 (" + completed + "/" + apis.size() + ")");
                    });
                    batchTestRunning = false;
                    return;
                }
                
                ApiDefinition api = apis.get(i);
                Map<String, String> params = profile.getParams(api.uniqueKey());
                TestResult result = http.executeRequest(api, profile.getBaseUrl(), params,
                        profile.getGlobalHeaders(), null, HttpExecutorService.BODY_FORMAT_JSON,
                        getCurrentEnvironment(), null);
                
                if (result.isPassed()) passed++;
                else if (result.getStatus() == TestStatus.ERROR) error++;
                else failed++;
                
                final int cur = i + 1;
                final TestResult r = result;
                final int p = passed, f = failed, er = error;
                ApplicationManager.getApplication().invokeLater(() -> {
                    testProgressBar.setValue(cur);
                    testProgressBar.setString(cur + "/" + apis.size());
                    String icon = r.isPassed() ? "✅" : (r.getStatus() == TestStatus.ERROR ? "⚠" : "❌");
                    testResultArea.append("[" + cur + "/" + apis.size() + "] " + icon + " " 
                            + r.getStatusCode() + " " + r.getDurationMs() + "ms  " 
                            + r.getApiDefinition().displayLabel() + "\n");
                    statusLabel.setText("● 测试中: " + cur + "/" + apis.size() + " (通过:" + p + " 失败:" + f + ")");
                });
            }
            
            final int total = apis.size();
            final int fp = passed, ff = failed, fe = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                testProgressBar.setVisible(false);
                batchTestBtn.setText("▶ 批量测试");
                batchTestBtn.setToolTipText("点击开始批量测试所有API，测试中再次点击可停止");
                batchTestRunning = false;
                
                // 追加汇总报告
                testResultArea.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                testResultArea.append("📊 测试报告\n");
                testResultArea.append("  总计: " + total + " 个接口\n");
                testResultArea.append("  ✅ 通过: " + fp + " | ❌ 失败: " + ff + " | ⚠ 异常: " + fe + "\n");
                testResultArea.append("  通过率: " + String.format("%.1f", (fp * 100.0 / total)) + "%\n");
                testResultArea.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                statusLabel.setText("● 批量测试完成: " + fp + "/" + total + " 通过");
            });
        });
    }

    private void addCustomParameter() {
        paramTableModel.addRow(new Object[]{"新参数", "String", "QUERY", "", "否", ""});
    }

    private void removeSelectedParameter() {
        int row = paramTable.getSelectedRow();
        if (row >= 0) paramTableModel.removeRow(row);
    }

    private void clearParameterValues() {
        for (int i = 0; i < paramTableModel.getRowCount(); i++) {
            paramTableModel.setValueAt("", i, 3);
        }
        // 清空附件面板里已选的文件路径
        for (Map.Entry<String, String> e : new HashMap<>(attachmentPaths).entrySet()) {
            attachmentPaths.put(e.getKey(), "");
            javax.swing.JLabel lbl = attachmentPathLabels.get(e.getKey());
            if (lbl != null) {
                lbl.setText("（未选择）");
                lbl.setForeground(JBColor.gray);
                lbl.setToolTipText("选择本地文件后显示绝对路径");
            }
        }
    }
    
    /**
     * 按位置筛选参数
     */
    private void filterParamsByLocation(String location) {
        if (location == null) {
            // 显示所有参数，重新加载当前API（保留收藏文件夹上下文，避免丢失实时参数归档）
            if (currentApi != null) {
                loadApi(currentApi, currentFolderId);
            }
            statusLabel.setText("● 显示所有参数");
            return;
        }
        
        // 使用 TableRowSorter 过滤行
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(paramTableModel);
        paramTable.setRowSorter(sorter);
        
        // 设置过滤器：只显示指定位置的参数
        sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                Object locValue = entry.getValue(2);  // 位置列
                if (locValue instanceof String) {
                    return location.equals(locValue);
                }
                return false;
            }
        });
        
        statusLabel.setText("● 只显示 " + location + " 参数");
    }
    
    /**
     * 高亮显示被修改的行（闪烁效果）
     */
    private void highlightModifiedRows(List<Integer> rows) {
        if (rows.isEmpty()) return;
        
        // 设置背景色为黄色
        Color highlightColor = new Color(255, 255, 200);  // 淡黄色
        
        for (int row : rows) {
            paramTable.setRowHeight(row, 32);  // 临时增加行高
        }
        
        // 滚动到第一个修改的行
        if (!rows.isEmpty()) {
            paramTable.scrollRectToVisible(paramTable.getCellRect(rows.get(0), 0, true));
            paramTable.setRowSelectionInterval(rows.get(0), rows.get(rows.size() - 1));
        }
        
        // 2秒后恢复原状
        javax.swing.Timer timer = new javax.swing.Timer(2000, e -> {
            for (int row : rows) {
                paramTable.setRowHeight(row, 28);  // 恢复原高度
            }
            paramTable.clearSelection();
        });
        timer.setRepeats(false);
        timer.start();
        
        statusLabel.setText("● AI生成的参数已高亮显示（2秒后自动取消）");
    }

    private void formatJson() {
        try {
            var elem = JsonParser.parseString(bodyEditor.getText());
            bodyEditor.setText(gson.toJson(elem));
        } catch (Exception e) {
            Messages.showWarningDialog(project, "JSON错误: " + e.getMessage(), "格式化失败");
        }
    }
    
    /**
     * 切换响应视图（文本/树形）
     */
    private void toggleResponseView() {
        String currentView = responseArea.getText().isBlank() ? "text" : 
            (responseContentPanel.getComponent(0).isVisible() ? "text" : "tree");
        
        if ("text".equals(currentView)) {
            // 尝试切换到树形视图
            try {
                String jsonText = responseArea.getText();
                if (jsonText.isBlank()) {
                    Messages.showWarningDialog(project, "响应内容为空", "提示");
                    return;
                }
                var jsonObj = com.google.gson.JsonParser.parseString(jsonText);
                DefaultMutableTreeNode root = buildJsonTree(jsonObj, "root");
                responseJsonTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
                
                // 展开前两层节点
                for (int i = 0; i < responseJsonTree.getRowCount(); i++) {
                    if (i < 3) {
                        responseJsonTree.expandRow(i);
                    }
                }
                
                responseCardLayout.show(responseContentPanel, "tree");
                responseViewTree = true;
                statusLabel.setText("● 已切换到树形视图");
            } catch (Exception e) {
                Messages.showWarningDialog(project, "无法解析为JSON: " + e.getMessage(), "非JSON响应");
            }
        } else {
            responseCardLayout.show(responseContentPanel, "text");
            responseViewTree = false;
            statusLabel.setText("● 已切换到文本视图");
        }
    }
    
    /**
     * 构建响应JSON树（自动解析响应体文本）
     */
    private void buildResponseJsonTree(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            responseJsonTree.setModel(new javax.swing.tree.DefaultTreeModel(
                    new DefaultMutableTreeNode("(无响应)")));
            return;
        }
        try {
            var jsonObj = JsonParser.parseString(responseBody);
            DefaultMutableTreeNode root = buildJsonTree(jsonObj, "Response");
            responseJsonTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
            for (int i = 0; i < Math.min(responseJsonTree.getRowCount(), 5); i++) {
                responseJsonTree.expandRow(i);
            }
        } catch (Exception e) {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("(非JSON响应)");
            root.add(new DefaultMutableTreeNode(responseBody.length() > 200
                    ? responseBody.substring(0, 200) + "..."
                    : responseBody));
            responseJsonTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        }
    }

    /**
     * 构建JSON树
     */
    private DefaultMutableTreeNode buildJsonTree(com.google.gson.JsonElement element, String nodeName) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(nodeName);
        
        if (element.isJsonObject()) {
            com.google.gson.JsonObject obj = element.getAsJsonObject();
            for (var entry : obj.entrySet()) {
                node.add(buildJsonTree(entry.getValue(), entry.getKey()));
            }
        } else if (element.isJsonArray()) {
            com.google.gson.JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                node.add(buildJsonTree(arr.get(i), "[" + i + "]"));
            }
        } else if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            node = new DefaultMutableTreeNode(nodeName + ": " + value);
        } else if (element.isJsonNull()) {
            node = new DefaultMutableTreeNode(nodeName + ": null");
        }
        
        return node;
    }
    private void formatResponseJson() {
        String response = responseArea.getText();
        if (response.isBlank()) {
            Messages.showWarningDialog(project, "响应内容为空", "提示");
            return;
        }
        
        try {
            // 尝试解析JSON
            var elem = JsonParser.parseString(response);
            String formatted = gson.toJson(elem);
            responseArea.setText(formatted);
            // v2.0.0：同步刷新高亮视图
            responsePane.setTextAndHighlight(formatted);
            responsePane.setCaretPosition(0);
            statusLabel.setText("● JSON已格式化");
        } catch (Exception e) {
            // 如果不是JSON，显示原始内容
            Messages.showInfoMessage(project, "响应内容不是有效的JSON格式\n\n错误: " + e.getMessage(), "非JSON响应");
        }
    }
    
    /**
     * 复制响应到剪贴板
     */
    private void copyResponseToClipboard() {
        String response = responseArea.getText();
        if (response.isBlank()) {
            Messages.showWarningDialog(project, "响应内容为空", "提示");
            return;
        }
        
        java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(response);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        statusLabel.setText("● 已复制到剪贴板");
        Messages.showInfoMessage(project, "响应内容已复制到剪贴板！", "复制成功");
    }

    // ================================================================
    // AI配置对话框
    // ================================================================

    /**
     * 可滚动的 GridBagLayout 面板。
     * <p>实现 {@link javax.swing.Scrollable}，令 {@link javax.swing.JScrollPane}
     * 把视口宽度强制设为本面板宽度（{@link #getScrollableTracksViewportWidth()} 返回 true），
     * 这样 GridBagLayout 的 weightx=1 / fill=HORIZONTAL 才能随容器宽度真正拉伸/收缩，
     * 而不是固定在首选宽度、出现水平滚动条或右侧留白。</p>
     * <p>高度方向不跟踪视口（返回 false），内容超出时仍可垂直滚动。</p>
     */
    private static class ScrollableGridBagPanel extends JPanel implements javax.swing.Scrollable {
        ScrollableGridBagPanel() {
            super(new GridBagLayout());
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }
    }

    /**
     * 显示AI配置对话框
     */
    private void showAiConfigDialog() {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        
        JBTextField urlField = new JBTextField(settings.getAiServerUrl(), 35);
        urlField.setToolTipText("例如: https://ark.cn-beijing.volces.com/api/v3 或 http://172.29.64.24:80");

        // API Key 输入框：默认密文显示，支持小眼睛切换明文
        JBPasswordField keyField = new JBPasswordField();
        keyField.setText(settings.getAiToken());
        keyField.setToolTipText("Bearer Token 值（无需带 'Bearer ' 前缀，插件会自动加）；留空表示使用本地模型");
        // 小眼睛切换按钮：明文/密文
        JButton toggleKeyBtn = new JButton(AllIcons.Actions.Preview);
        toggleKeyBtn.setToolTipText("显示/隐藏 API Key 内容");
        toggleKeyBtn.putClientProperty("JButton.buttonType", "square");
        toggleKeyBtn.setMargin(new Insets(0, 2, 0, 2));
        toggleKeyBtn.setPreferredSize(new Dimension(28, keyField.getPreferredSize().height));
        final boolean[] keyVisible = {false};
        toggleKeyBtn.addActionListener(e -> {
            keyVisible[0] = !keyVisible[0];
            if (keyVisible[0]) {
                keyField.setEchoChar((char) 0);  // 明文显示
                toggleKeyBtn.setIcon(AllIcons.Actions.Cancel);
                toggleKeyBtn.setToolTipText("点击隐藏 API Key");
            } else {
                keyField.setEchoChar('\u2022');  // 圆点密文
                toggleKeyBtn.setIcon(AllIcons.Actions.Preview);
                toggleKeyBtn.setToolTipText("点击显示 API Key");
            }
        });
        JPanel keyFieldPanel = new JPanel(new BorderLayout(2, 0));
        keyFieldPanel.add(keyField, BorderLayout.CENTER);
        keyFieldPanel.add(toggleKeyBtn, BorderLayout.EAST);

        JBTextField apiPathField = new JBTextField(settings.getAiApiPath(), 20);
        apiPathField.setToolTipText("<html>OpenAI 标准用 /chat/completions；Qwen/vLLM 等私有部署可能用 /chat。<br>留空则请求直接打到服务器URL根路径。</html>");

        JComboBox<String> modelField = new JComboBox<>(RestAutoLabConstants.AI_MODEL_OPTIONS);
        modelField.setEditable(true);
        modelField.setSelectedItem(settings.getAiModel());
        modelField.setToolTipText("选择或输入模型名称，如 Qwen3.5-35B-A3B");
        
        JCheckBox localModelCheck = new JCheckBox("本地模型（API Key 自动填为 Bearer 占位）");
        // 判定本地模型：token 为空，或 token 为字面量 "Bearer"
        localModelCheck.setSelected(RestAutoLabConstants.isLocalModelToken(settings.getAiToken()));
        localModelCheck.setToolTipText("<html>勾选后 API Key 字段自动填入字面量 <b>Bearer</b> 并禁用编辑。<br>"
                + "调用时发送 <code>Authorization: Bearer Bearer</code>，满足 vLLM/Qwen 等网关要求。<br>"
                + "云端模型请取消勾选，并填入真实的 API Key 值。</html>");
        // 勾选时自动填 "Bearer" 并禁用；取消勾选时清空让用户填真实 token
        if (localModelCheck.isSelected()) {
            keyField.setText(RestAutoLabConstants.AI_LOCAL_BEARER_TOKEN);
        }
        localModelCheck.addActionListener(e -> {
            if (localModelCheck.isSelected()) {
                keyField.setText(RestAutoLabConstants.AI_LOCAL_BEARER_TOKEN);
                keyField.setEnabled(false);
                toggleKeyBtn.setEnabled(false);
            } else {
                keyField.setText("");
                keyField.setEnabled(true);
                toggleKeyBtn.setEnabled(true);
                keyField.requestFocusInWindow();
            }
        });
        keyField.setEnabled(!localModelCheck.isSelected());
        toggleKeyBtn.setEnabled(!localModelCheck.isSelected());

        // 自定义系统提示词
        JBTextArea systemPromptArea = new JBTextArea(settings.getAiSystemPrompt());
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setFont(systemPromptArea.getFont().deriveFont(Font.PLAIN, 11f));
        JScrollPane systemPromptScroll = new JScrollPane(systemPromptArea);
        systemPromptScroll.setPreferredSize(new Dimension(460, 100));

        // 自定义用户提示词模板（支持占位符）
        JBTextArea userPromptArea = new JBTextArea(settings.getAiUserPromptTemplate());
        userPromptArea.setLineWrap(true);
        userPromptArea.setWrapStyleWord(true);
        userPromptArea.setFont(userPromptArea.getFont().deriveFont(Font.PLAIN, 11f));
        JScrollPane userPromptScroll = new JScrollPane(userPromptArea);
        userPromptScroll.setPreferredSize(new Dimension(460, 160));

        JButton resetPromptBtn = new JButton("恢复默认提示词");
        resetPromptBtn.setToolTipText("将系统/用户提示词还原为内置默认值");
        resetPromptBtn.addActionListener(e -> {
            systemPromptArea.setText(RestAutoLabConstants.AI_SYSTEM_PROMPT);
            userPromptArea.setText(RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        });

        // 使用自定义 ScrollableGridBagPanel：实现 Scrollable 并令
        // getScrollableTracksViewportWidth()=true，JScrollPane 据此把视口宽度
        // 强制设为 form 宽度，GridBagLayout 的 weightx=1 / fill=HORIZONTAL
        // 才能真正随对话框右边缘拉伸，输入框和小眼睛(BorderLayout.EAST)才会跟随右移。
        JPanel form = new ScrollableGridBagPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JBLabel urlLabel = new JBLabel("🌐 服务器URL:");
        urlLabel.setFont(urlLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(urlLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(urlField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JBLabel keyLabel = new JBLabel("🔑 API Key(Bearer):");
        keyLabel.setFont(keyLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(keyLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(keyFieldPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JBLabel apiPathLabel = new JBLabel("🛤️ API 路径:");
        apiPathLabel.setFont(apiPathLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(apiPathLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(apiPathField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        JBLabel modelLabel = new JBLabel("🤖 模型:");
        modelLabel.setFont(modelLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(modelLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(modelField, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        form.add(localModelCheck, gbc);

        // 提示文字
        JBLabel hintLabel = new JBLabel("<html><i>"
                + "云端模型：取消勾选'本地模型'，API Key 填真实 token（如 4702f55c...），无需带 'Bearer ' 前缀；<br>"
                + "本地部署（vLLM/Qwen/Ollama）：勾选'本地模型'，API Key 自动填为 <b>Bearer</b> 占位。"
                + "</i></html>");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 10f));
        hintLabel.setForeground(JBColor.GRAY);
        gbc.gridy = 5;
        form.add(hintLabel, gbc);

        // ── AI 提示词自定义分区 ──
        gbc.gridy = 6; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator sep1 = new JSeparator();
        form.add(sep1, gbc);

        gbc.gridy = 7; gbc.fill = GridBagConstraints.NONE;
        JBLabel promptHeader = new JBLabel("📝 AI 提示词自定义");
        promptHeader.setFont(promptHeader.getFont().deriveFont(Font.BOLD, 12f));
        promptHeader.setForeground(JBColor.BLUE);
        form.add(promptHeader, gbc);

        // 系统提示词 - 可折叠标题（默认折叠）
        final boolean[] systemPromptExpanded = {false};
        JButton systemPromptToggle = new JButton("▸ 系统提示词 (System Prompt)");
        systemPromptToggle.setContentAreaFilled(false);
        systemPromptToggle.setBorderPainted(false);
        systemPromptToggle.setFocusPainted(false);
        systemPromptToggle.setHorizontalAlignment(SwingConstants.LEFT);
        systemPromptToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        systemPromptToggle.setFont(systemPromptToggle.getFont().deriveFont(Font.BOLD, 11f));
        gbc.gridy = 8; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1; gbc.weighty = 0;
        form.add(systemPromptToggle, gbc);

        // 系统提示词内容（默认折叠隐藏）
        JPanel systemPromptContent = new JPanel(new BorderLayout());
        systemPromptContent.add(systemPromptScroll, BorderLayout.CENTER);
        systemPromptContent.setVisible(false);
        gbc.gridy = 9; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.35;
        form.add(systemPromptContent, gbc);

        // 用户提示词 - 可折叠标题（默认折叠）
        final boolean[] userPromptExpanded = {false};
        JButton userPromptToggle = new JButton("▸ 用户提示词模板 (User Prompt)");
        userPromptToggle.setContentAreaFilled(false);
        userPromptToggle.setBorderPainted(false);
        userPromptToggle.setFocusPainted(false);
        userPromptToggle.setHorizontalAlignment(SwingConstants.LEFT);
        userPromptToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        userPromptToggle.setFont(userPromptToggle.getFont().deriveFont(Font.BOLD, 11f));
        gbc.gridy = 10; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        form.add(userPromptToggle, gbc);

        // 用户提示词内容（默认折叠隐藏）
        JPanel userPromptContent = new JPanel(new BorderLayout());
        userPromptContent.add(userPromptScroll, BorderLayout.CENTER);
        userPromptContent.setVisible(false);
        gbc.gridy = 11; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.55;
        form.add(userPromptContent, gbc);

        gbc.gridy = 12; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        JBLabel placeholderHint = new JBLabel("<html><font color='#888888' size='2'>占位符: ${API_URL} ${HTTP_METHOD} ${API_NAME} ${CONTROLLER_NAME} ${DESCRIPTION} ${CONTENT_TYPE} ${PARAMETERS} ${SCENARIO_NAME} ${SCENARIO_DESC} ${FULL_HINT}</font></html>");
        form.add(placeholderHint, gbc);

        // 内容较多，套滚动面板防止超出屏幕高度。
        // 因 form 实现了 Scrollable.getScrollableTracksViewportWidth()=true，
        // 视口宽度会强制传给 form，水平方向无需滚动条，输入框随对话框宽度伸缩。
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // 使用 JDialog 替代 JOptionPane，支持自由拉伸收缩（上下左右都可调）
        JDialog dialog = new JDialog((Frame) null, "AI 配置", true);
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(560, 400));  // 最小尺寸保底

        // 按钮面板：恢复默认提示词(左) + 确定/取消(右) 同一排，减小上下高度
        JPanel btnPanel = new JPanel(new BorderLayout());
        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        leftBtnPanel.add(resetPromptBtn);
        JPanel rightBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton okBtn = new JButton("确定");
        JButton cancelBtn = new JButton("取消");
        rightBtnPanel.add(okBtn);
        rightBtnPanel.add(cancelBtn);
        btnPanel.add(leftBtnPanel, BorderLayout.WEST);
        btnPanel.add(rightBtnPanel, BorderLayout.EAST);

        // 主布局：滚动面板在中间，按钮栏在底部
        JPanel contentPane = new JPanel(new BorderLayout(0, 8));
        contentPane.setBorder(JBUI.Borders.empty(8));
        contentPane.add(formScroll, BorderLayout.CENTER);
        contentPane.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(contentPane);
        dialog.pack();
        dialog.setLocationRelativeTo(this);  // 居中显示

        // 折叠/展开 互斥逻辑：系统提示词与用户提示词同一时间只能展开一个
        systemPromptToggle.addActionListener(e -> {
            boolean willExpand = !systemPromptExpanded[0];
            systemPromptExpanded[0] = willExpand;
            systemPromptToggle.setText((willExpand ? "▾ " : "▸ ") + "系统提示词 (System Prompt)");
            systemPromptContent.setVisible(willExpand);
            if (willExpand && userPromptExpanded[0]) {
                userPromptExpanded[0] = false;
                userPromptToggle.setText("▸ 用户提示词模板 (User Prompt)");
                userPromptContent.setVisible(false);
            }
            form.revalidate();
            form.repaint();
            dialog.pack();
        });
        userPromptToggle.addActionListener(e -> {
            boolean willExpand = !userPromptExpanded[0];
            userPromptExpanded[0] = willExpand;
            userPromptToggle.setText((willExpand ? "▾ " : "▸ ") + "用户提示词模板 (User Prompt)");
            userPromptContent.setVisible(willExpand);
            if (willExpand && systemPromptExpanded[0]) {
                systemPromptExpanded[0] = false;
                systemPromptToggle.setText("▸ 系统提示词 (System Prompt)");
                systemPromptContent.setVisible(false);
            }
            form.revalidate();
            form.repaint();
            dialog.pack();
        });

        // 结果标记
        final boolean[] okPressed = {false};

        // 按钮事件：确定时先校验，校验通过才关闭对话框并保存
        okBtn.addActionListener(e -> {
            String serverUrl = urlField.getText().trim();
            String apiKey = new String(keyField.getPassword()).trim();
            String apiPath = apiPathField.getText().trim();
            String model = String.valueOf(modelField.getSelectedItem()).trim();

            if (serverUrl.isBlank()) {
                Messages.showWarningDialog(dialog, "服务器URL不能为空", "配置错误");
                urlField.requestFocusInWindow();
                return;
            }
            // API 路径允许为空保存（留空时请求直接打到服务器URL根路径）；
            // 非空时仅补前导斜杠，保证路径格式规范
            if (!apiPath.isBlank() && !apiPath.startsWith("/")) {
                apiPath = "/" + apiPath;
            }

            // 校验通过，保存配置
            settings.setAiServerUrl(serverUrl);
            settings.setAiToken(localModelCheck.isSelected()
                    ? RestAutoLabConstants.AI_LOCAL_BEARER_TOKEN : apiKey);
            settings.setAiApiPath(apiPath);
            settings.setAiModel(model);
            settings.setAiSystemPrompt(systemPromptArea.getText());
            settings.setAiUserPromptTemplate(userPromptArea.getText());

            okPressed[0] = true;
            dialog.dispose();

            // 刷新 AI 配置摘要标签，使面板上显示的 URL/模型 与刚保存的配置一致
            aiConfigInfoLabel.setText(getAiConfigSummary());
            statusLabel.setText("● AI配置已更新");

            Messages.showInfoMessage(project,
                    "AI配置已成功保存！\n\n服务器: " + serverUrl + "\nAPI 路径: " + apiPath
                            + "\n模型: " + model, "保存成功");
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // ESC 键关闭对话框
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(okBtn);

        dialog.setVisible(true);
        // 保存逻辑已在 okBtn 事件中完成，此处无需重复处理
    }

    // ================================================================
    // 自定义渲染器和编辑器
    // ================================================================

    /** HTTP方法彩色渲染器 - 使用彩色方块图标+纯文本，兼容组合框显示区(index=-1)的渲染 */
    private static class HttpMethodCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof String) {
                String m = (String) value;
                Color c = ApiTreePanel.getMethodColor(m);
                setText(m);
                setIcon(new ColoredSquareIcon(c, 10, 12));
                setHorizontalTextPosition(SwingConstants.RIGHT);
                setIconTextGap(6);
            } else {
                setIcon(null);
            }
            return this;
        }
    }

    /** 彩色方块图标 - 用于HTTP方法徽章 */
    private static class ColoredSquareIcon implements Icon {
        private final Color color;
        private final int width;
        private final int height;

        ColoredSquareIcon(Color color, int width, int height) {
            this.color = color;
            this.width = width;
            this.height = height;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color saved = g.getColor();
            g.setColor(color);
            g.fillRect(x, y, width, height);
            g.setColor(saved);
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }

    /** 位置列彩色渲染器 */
    private static class LocationCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(CENTER);
            if (!isSelected && value instanceof String) {
                switch ((String) value) {
                    case "PATH": setForeground(RestAutoLabConstants.COLOR_PUT); break;
                    case "QUERY": setForeground(RestAutoLabConstants.COLOR_GET); break;
                    case "BODY": setForeground(RestAutoLabConstants.COLOR_POST); break;
                    case "HEADER": setForeground(RestAutoLabConstants.COLOR_PATCH); break;
                    default: setForeground(table.getForeground());
                }
            }
            return this;
        }
    }

    /**
     * v2.0.0 值列渲染器。
     * <ul>
     *   <li>文件类型参数（File/MultipartFile 或 FILE 位置）：显示「📎 文件名」，完整路径放 tooltip；
     *       未选择时灰色显示「（未选择）」。</li>
     *   <li>普通值：原样显示，长值用 tooltip 辅助查看。</li>
     * </ul>
     * <p>数据层仍保留完整路径（HttpExecutorService 依赖路径构建 multipart），此处仅做展示层美化。</p>
     */
    private static class ValueCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            setIcon(null);

            String paramType = table.getValueAt(row, 1) instanceof String t ? t : "";
            String paramLoc = table.getValueAt(row, 2) instanceof String l ? l : "";
            String valStr = value instanceof String s ? s : "";

            if (isFileType(paramType) || "FILE".equals(paramLoc)) {
                if (valStr.isBlank()) {
                    setText("（未选择）");
                    setToolTipText("双击选择文件");
                    if (!isSelected) setForeground(JBColor.GRAY);
                } else {
                    String raw = valStr.startsWith("📎") ? valStr.substring(1).trim() : valStr;
                    setText("📎 " + fileNameOf(raw));
                    setToolTipText(raw);
                    if (!isSelected) setForeground(UiStyle.JSON_KEY);
                }
                return this;
            }

            // 普通值：长值用 tooltip 辅助查看
            setText(valStr);
            setToolTipText(valStr.length() > 60 ? valStr : null);
            if (!isSelected) setForeground(table.getForeground());
            return this;
        }

        private static boolean isFileType(String type) {
            if (type == null) return false;
            String t = type.toLowerCase();
            return t.contains("file") || t.contains("multipart");
        }
    }

    /** 类型下拉框编辑器 */
    private static class TypeComboBoxEditor extends DefaultCellEditor {
        public TypeComboBoxEditor() {
            super(new JComboBox<>(new String[]{"String", "Integer", "Long", "Double", "Boolean", "Object", "Array"}));
        }
    }

    /** 类型下拉框渲染器 */
    private static class TypeComboBoxRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(CENTER);
            return this;
        }
    }

    /** 必填下拉框编辑器 */
    private static class RequiredComboBoxEditor extends DefaultCellEditor {
        public RequiredComboBoxEditor() {
            super(new JComboBox<>(new String[]{"是", "否"}));
        }
    }

    /** 必填下拉框渲染器 */
    private static class RequiredComboBoxRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(CENTER);
            if (!isSelected && "是".equals(String.valueOf(value))) {
                setForeground(RestAutoLabConstants.COLOR_GET);
            }
            return this;
        }
    }
    
    // SmartValueEditor 已抽取为独立文件 SmartValueEditor.java（v2.0.0：文件选择 + 多行JSON编辑器 + 枚举提示）

    /** JSON树节点渲染器 - 按值类型上色 + 类型徽章 */
    private class JsonTreeNodeRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (!(value instanceof DefaultMutableTreeNode)) return this;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObj = node.getUserObject();
            if (!(userObj instanceof String)) return this;
            String text = (String) userObj;

            // 节点格式约定：
            //   数组元素: "[0] (3 items)"
            //   对象/数组键: "keyName (2 items)" 或 "keyName"
            //   叶子键值对: "keyName: value"
            //   root: "root"
            if (text.equals("root") || text.equals("Response") || text.equals("(无响应)")) {
                setIcon(AllIcons.Json.Object);
                setForeground(JBColor.foreground());
                setText("📦 " + escapeHtml(text));
            } else if (text.matches("\\[\\d+\\].*")) {
                // 数组元素节点："[0]" 或 "[0]: value"
                setIcon(AllIcons.Nodes.Folder);
                int colon = text.indexOf(":");
                if (colon > 0) {
                    String key = text.substring(0, colon);
                    String val = text.substring(colon + 1).trim();
                    setText("<html>"
                            + "<span style='color:#888'>" + escapeHtml(key) + "</span>: "
                            + "<b style='color:" + hexOf(jsonTypeColor(val)) + "'>" + escapeHtml(val) + "</b>"
                            + "</html>");
                } else {
                    setText("<html><b style='color:#1f6feb'>" + escapeHtml(text) + "</b></html>");
                }
            } else if (text.contains(":")) {
                // 叶子键值对 "key: value"
                String key = text.substring(0, text.indexOf(":"));
                String val = text.substring(text.indexOf(":") + 1).trim();
                JBColor typeColor = jsonTypeColor(val);
                String typeBadge = detectTypeBadge(val);
                setIcon(AllIcons.Nodes.Property);
                setText("<html>"
                        + "<b style='color:#1f6feb'>" + escapeHtml(key) + "</b>"
                        + "<span style='color:#888'>: </span>"
                        + "<span style='color:" + hexOf(typeColor) + ";font-weight:" + (val.startsWith("\"") ? "normal" : "bold") + "'>"
                        + escapeHtml(val) + "</span>"
                        + (typeBadge.isEmpty() ? "" : " <span style='background-color:" + hexOf(typeColor)
                                + ";color:white;font-size:9px;padding:1px 4px;border-radius:3px;'>" + typeBadge + "</span>")
                        + "</html>");
            } else if (text.startsWith("[") && text.endsWith("]")) {
                // 数组容器节点（buildJsonTree 会用 "[]" 标数组，理论不会出现，但兼容下）
                setIcon(AllIcons.Json.Array);
                setText("📚 <b style='color:#1f6feb'>" + escapeHtml(text) + "</b>");
            } else if (text.contains("(") && text.endsWith(")")) {
                // 对象/数组容器节点："key (3 items)"
                int paren = text.indexOf("(");
                String name = text.substring(0, paren).trim();
                String count = text.substring(paren);
                setIcon(text.contains("Array") || name.startsWith("[") ? AllIcons.Json.Array : AllIcons.Json.Object);
                setText("<html>📂 <b style='color:#1f6feb'>" + escapeHtml(name) + "</b>"
                        + " <span style='color:#888;font-size:10px'>" + escapeHtml(count) + "</span></html>");
            } else {
                // 普通对象 key 容器（无值的对象/数组），如 "data"、"user" 等
                setIcon(AllIcons.Json.Object);
                setText("📂 <b style='color:#1f6feb'>" + escapeHtml(text) + "</b>");
            }

            return this;
        }

        private String detectTypeBadge(String val) {
            if (val == null) return "";
            if (val.equals("null")) return "null";
            if (val.equals("true") || val.equals("false")) return "bool";
            try { Double.parseDouble(val); return "num"; } catch (NumberFormatException ignored) { }
            if (val.startsWith("\"") && val.endsWith("\"")) return "str";
            return "";
        }

        private String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private String hexOf(JBColor c) {
            return String.format("#%02X%02X%02X", c.getRGB() & 0xFF, (c.getRGB() >> 8) & 0xFF, (c.getRGB() >> 16) & 0xFF);
        }
    }

    // ================================================================
    // v3 新增：断言Tab
    // ================================================================

    private JPanel createAssertionsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // 顶部工具栏
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        String[] assertTypes = {"状态码等于", "响应时间小于(ms)", "响应体包含", "响应体不包含",
                "JSON字段存在", "JSON字段等于", "响应头存在"};
        JComboBox<String> assertTypeCombo = new JComboBox<>(assertTypes);
        JBTextField targetField = new JBTextField(20);
        JBTextField expectedField = new JBTextField(20);
        expectedField.setText("200");
        JButton addBtn = iconButton("添加断言", AllIcons.General.Add, e -> {
            String type = (String) assertTypeCombo.getSelectedItem();
            String target = targetField.getText().trim();
            String expected = expectedField.getText().trim();
            if (type == null) return;
            ResponseAssertion a = new ResponseAssertion();
            a.setType(type);
            a.setTarget(target);
            a.setExpected(expected);
            currentAssertions.add(a);
            assertionTableModel.addRow(new Object[]{type, target, expected, "-"});
            targetField.setText("");
            expectedField.setText("");
        });

        JButton aiGenBtn = iconButton("🤖 AI生成断言", AllIcons.Actions.Lightning, e -> generateAiAssertions());

        topBar.add(new JBLabel("类型:"));
        topBar.add(assertTypeCombo);
        topBar.add(new JBLabel("目标/路径:"));
        topBar.add(targetField);
        topBar.add(new JBLabel("期望值:"));
        topBar.add(expectedField);
        topBar.add(addBtn);
        topBar.add(Box.createHorizontalStrut(8));
        topBar.add(aiGenBtn);

        panel.add(topBar, BorderLayout.NORTH);

        // 断言表格
        assertionTableModel = new DefaultTableModel(
                new Object[]{"断言类型", "目标", "期望值", "实际结果"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        assertionTable = new JBTable(assertionTableModel);
        UiStyle.styleTable(assertionTable);
        panel.add(new JBScrollPane(assertionTable), BorderLayout.CENTER);

        // 底部按钮
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton delBtn = iconButton("删除选中", AllIcons.General.Remove, e -> {
            int row = assertionTable.getSelectedRow();
            if (row >= 0) {
                assertionTableModel.removeRow(row);
                currentAssertions.remove(row);
            }
        });
        JButton clearBtn = iconButton("清空断言", AllIcons.Actions.GC, e -> {
            assertionTableModel.setRowCount(0);
            currentAssertions.clear();
        });
        bottomBar.add(delBtn);
        bottomBar.add(clearBtn);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void generateAiAssertions() {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }
        statusLabel.setText("○ AI生成断言中...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AiParameterService ai = AiParameterService.getInstance(project);
            List<ResponseAssertion> asserts = ai.generateAssertions(currentApi);
            ApplicationManager.getApplication().invokeLater(() -> {
                currentAssertions.clear();
                assertionTableModel.setRowCount(0);
                for (ResponseAssertion a : asserts) {
                    currentAssertions.add(a);
                    assertionTableModel.addRow(new Object[]{
                            a.getType().getDisplayName(), a.getTarget(), a.getExpected(), "待执行"
                    });
                }
                statusLabel.setText("● 已生成 " + asserts.size() + " 条断言");
            });
        });
    }

    // ================================================================
    // v3 新增：历史Tab
    // ================================================================

    private JPanel createHistoryTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        historyListModel = new DefaultListModel<>();
        historyList = new JList<>(historyListModel);
        historyList.setCellRenderer(new HistoryCellRenderer());
        historyList.setFont(new Font("Monospaced", Font.PLAIN, (int) UiStyle.FONT_HINT));
        historyList.setFixedCellHeight(26);

        // 加载历史
        for (RequestHistory h : requestHistory) {
            historyListModel.addElement(h);
        }

        JBLabel historyTitle = new JBLabel("请求历史 (最近" + RestAutoLabConstants.MAX_HISTORY_SIZE + "条)，双击重新发送");
        UiStyle.hint(historyTitle);
        panel.add(historyTitle, BorderLayout.NORTH);
        panel.add(new JBScrollPane(historyList), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton resendBtn = iconButton("重新发送", AllIcons.Actions.Execute, e -> resendHistory());
        JButton diffBtn = iconButton("Diff对比", AllIcons.Actions.Diff, e -> diffSelectedHistory());
        JButton delBtn = iconButton("删除", AllIcons.General.Remove, e -> {
            int idx = historyList.getSelectedIndex();
            if (idx >= 0) {
                historyListModel.remove(idx);
                requestHistory.remove(idx);
                persistHistory();
            }
        });
        JButton clearBtn = iconButton("清空历史", AllIcons.Actions.GC, e -> {
            historyListModel.clear();
            requestHistory.clear();
            persistHistory();
        });
        btnPanel.add(resendBtn);
        btnPanel.add(diffBtn);
        btnPanel.add(delBtn);
        btnPanel.add(clearBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        // 双击重发
        historyList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) resendHistory();
            }
        });

        return panel;
    }

    private static class HistoryCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof RequestHistory h) {
                String color = h.getStatusCode() >= 200 && h.getStatusCode() < 300 ? "#2E7D32" : "#C62828";
                setText(String.format("<html><span style='color:%s;font-weight:bold'>[%s]</span> %d <span style='color:#666'>(%dms)</span> %s - %s</html>",
                        color, h.getMethod(), h.getStatusCode(), h.getDurationMs(),
                        h.timeDisplay(),
                        h.getUrl().length() > 60 ? h.getUrl().substring(h.getUrl().length() - 60) : h.getUrl()));
            }
            return this;
        }
    }

    private void addToHistory(TestResult result) {
        RequestHistory h = new RequestHistory(
                result.getApiDefinition().getHttpMethod(),
                result.getRequestUrl(),
                result.getApiDefinition().uniqueKey(),
                result.getResponseHeaders(),
                result.getRequestBody(),
                result.getStatusCode(),
                result.getResponseBody(),
                result.getDurationMs(),
                result.getApiDefinition().getName()
        );
        requestHistory.add(0, h);
        // 限制历史记录数量
        while (requestHistory.size() > RestAutoLabConstants.MAX_HISTORY_SIZE) {
            requestHistory.remove(requestHistory.size() - 1);
        }
        // 更新列表
        if (historyListModel != null) {
            historyListModel.clear();
            for (RequestHistory rh : requestHistory) {
                historyListModel.addElement(rh);
            }
        }
        persistHistory();

        // 更新Cookie状态
        if (cookieStatusLabel != null) {
            String cookieStr = HttpExecutorService.getInstance(project).getCookieDebugString();
            cookieStatusLabel.setText("Cookie: " + (cookieStr.length() > 60 ? cookieStr.substring(0, 60) + "..." : cookieStr));
        }

        // 更新API调用统计
        if (currentApi != null && result.getApiDefinition().uniqueKey().equals(currentApi.uniqueKey())) {
            currentApi.incrementCallCount();
            RestAutoLabSettingsState.getInstance(project).recordApiCall(currentApi.uniqueKey());
        }

        lastResult = result;

        // 更新断言结果
        updateAssertionResults(result);
    }

    private void persistHistory() {
        RestAutoLabSettingsState.getInstance(project).saveRequestHistory(requestHistory);
    }

    private void resendHistory() {
        RequestHistory h = historyList.getSelectedValue();
        if (h == null) return;

        // 发送请求
        statusLabel.setText("○ 重发历史请求...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService http = HttpExecutorService.getInstance(project);
            ApiDefinition api = currentApi != null ? currentApi : new ApiDefinition();
            api.setHttpMethod(h.getMethod());
            api.setUrl(h.getUrl());
            String baseUrl = baseUrlField.getText().trim();
            String fullUrl = h.getUrl();
            String urlPath = fullUrl;
            if (fullUrl.startsWith(baseUrl)) {
                urlPath = fullUrl.substring(baseUrl.length());
            }
            api.setUrl(urlPath);

            TestResult result = http.executeRequest(api, baseUrl, Collections.emptyMap(),
                    h.getHeaders() != null ? h.getHeaders() : Collections.emptyMap(),
                    h.getRequestBody(), HttpExecutorService.BODY_FORMAT_JSON, getCurrentEnvironment(), currentAssertions);
            ApplicationManager.getApplication().invokeLater(() -> displayResponse(result));
        });
    }

    private void diffSelectedHistory() {
        int[] indices = historyList.getSelectedIndices();
        if (indices.length != 2) {
            Messages.showInfoMessage(project, "请选择2条历史记录进行对比（按住Ctrl多选）", "提示");
            return;
        }
        RequestHistory h1 = historyListModel.get(indices[0]);
        RequestHistory h2 = historyListModel.get(indices[1]);

        List<SimpleDiff.DiffLine> diffs = SimpleDiff.diff(h1.getResponseBody(), h2.getResponseBody());
        String html = SimpleDiff.toHtml(diffs);

        // Show diff in a simple dialog with JEditorPane
        JEditorPane editorPane = new JEditorPane("text/html",
                "<h3>响应对比</h3>"
                        + "<p><b>请求1:</b> [" + h1.getMethod() + "] " + h1.getStatusCode() + " " + h1.getUrl() + "</p>"
                        + "<p><b>请求2:</b> [" + h2.getMethod() + "] " + h2.getStatusCode() + " " + h2.getUrl() + "</p>"
                        + "<hr>" + html);
        editorPane.setEditable(false);
        editorPane.setCaretPosition(0);
        JScrollPane scroll = new JBScrollPane(editorPane);
        scroll.setPreferredSize(new Dimension(800, 500));

        JDialog dialog = new JDialog((Frame) null, "Diff对比", true);
        dialog.setContentPane(scroll);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void updateAssertionResults(TestResult result) {
        if (assertionTableModel == null) return;
        for (int i = 0; i < Math.min(currentAssertions.size(), assertionTableModel.getRowCount()); i++) {
            ResponseAssertion a = currentAssertions.get(i);
            assertionTableModel.setValueAt(a.isPassed() ? "✓ 通过" : "✗ 失败" + (a.getMessage().isEmpty() ? "" : ": " + a.getMessage()), i, 3);
        }
        // Also update result's assertions
        result.setAssertions(new ArrayList<>(currentAssertions));
    }

    // ================================================================
    // v3 新增：辅助功能方法
    // ================================================================

    private Environment getCurrentEnvironment() {
        if (envCombo == null) return null;
        return (Environment) envCombo.getSelectedItem();
    }

    /**
     * 一伦优化 #2：从 ApiTreePanel 右键菜单触发 cURL 导入（移出本面板工具栏后，
     * 这里只从入口端调用，但保持包内可见以减少外部依赖）。
     */
    public void importCurlOrJson() {
        String input = Messages.showInputDialog(project,
                "粘贴cURL命令或JSON:", "导入", Messages.getQuestionIcon());
        if (input == null || input.isBlank()) return;

        if (input.trim().startsWith("curl")) {
            // 解析cURL
            Map<String, Object> parsed = CurlUtil.parseCurl(input);
            String method = (String) parsed.get("method");
            String url = (String) parsed.get("url");
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) parsed.get("headers");
            String body = (String) parsed.get("body");

            // 分离baseUrl和path
            String baseUrl = baseUrlField.getText().trim();
            String path = url;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                int pathStart = url.indexOf("/", 8);
                if (pathStart > 0) {
                    int queryStart = url.indexOf("?", pathStart);
                    int pathEnd = queryStart > 0 ? queryStart : url.length();
                    baseUrl = url.substring(0, pathStart);
                    path = url.substring(pathStart, pathEnd);
                }
            }

            methodCombo.setSelectedItem(method);
            urlField.setText(path);
            baseUrlField.setText(baseUrl);

            headerTableModel.setRowCount(0);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    headerTableModel.addRow(new Object[]{e.getKey(), e.getValue()});
                }
            }
            if (body != null) {
                bodyEditor.setText(body);
            }
            statusLabel.setText("● 已从cURL导入请求");
        } else {
            // JSON导入（使用AI service）
            AiParameterService ai = AiParameterService.getInstance(project);
            List<Map<String, String>> params = ai.importFromJson(input);
            if (!params.isEmpty()) {
                Map<String, String> first = params.get(0);
                for (int i = 0; i < paramTableModel.getRowCount(); i++) {
                    Object name = paramTableModel.getValueAt(i, 0);
                    if (name instanceof String && first.containsKey(name)) {
                        paramTableModel.setValueAt(first.get(name), i, 3);
                    }
                }
                statusLabel.setText("● 已从JSON导入参数");
            }
        }
    }

    private void exportCurl() {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }
        String baseUrl = baseUrlField.getText().trim();
        String url = baseUrl + urlField.getText();
        Map<String, String> params = collectParameterValues();
        // Append query params
        boolean hasQuery = url.contains("?");
        StringBuilder urlBuilder = new StringBuilder(url);
        Environment env = getCurrentEnvironment();
        for (ApiParameter p : currentApi.queryParameters()) {
            String val = params.get(p.getName());
            if (val != null) {
                if (env != null) val = env.resolveVariables(val);
                urlBuilder.append(hasQuery ? "&" : "?")
                        .append(p.getName()).append("=").append(val);
                hasQuery = true;
            }
        }

        Map<String, String> headers = collectHeaderValues();
        String body = bodyEditor.getText();
        String format = bodyFormatCombo != null ? (String) bodyFormatCombo.getSelectedItem() : "JSON";
        String contentType = "x-www-form-urlencoded".equals(format) ?
                RestAutoLabConstants.CONTENT_TYPE_FORM_URLENCODED : currentApi.getConsumes();
        headers.put("Content-Type", contentType);

        String curl = CurlUtil.generateCurl((String) methodCombo.getSelectedItem(),
                urlBuilder.toString(), headers, body, contentType);

        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(curl);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        Messages.showInfoMessage(project, "cURL命令已复制到剪贴板！\n\n" + curl.substring(0, Math.min(curl.length(), 200)) + (curl.length() > 200 ? "..." : ""), "导出成功");
    }

    private void exportApiDoc() {
        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> allApis = scanner.getCachedApis();
        if (allApis.isEmpty()) {
            Messages.showWarningDialog(project, "暂无API，请先扫描", "提示");
            return;
        }

        // === 严格按用户在接口树中选中的接口生成（支持单选/多选） ===
        // 1) 优先使用接口树的多选
        List<ApiDefinition> selected = null;
        if (treePanel != null) {
            selected = treePanel.getSelectedApisForExport();
        }
        // 2) 若树里没选，回退到"当前正在调试的接口"
        if ((selected == null || selected.isEmpty()) && currentApi != null) {
            selected = new java.util.ArrayList<>();
            selected.add(currentApi);
        }
        // 3) 再不行才是真的没选
        if (selected == null || selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n\n操作方式：\n• 在左侧接口树里点 1 个或多个接口（按住 Cmd/Ctrl 多选）\n• 然后再点击『导出 API 文档(Markdown)』",
                    "提示");
            return;
        }

        // 二次确认：按 Controller 分组列出
        StringBuilder preview = new StringBuilder();
        preview.append("<html><body style='width:480px;font-family:Menlo,Monaco,monospace;font-size:11px;'>")
                .append("即将导出 <b>").append(selected.size())
                .append("</b> 个接口到 Markdown 文档：<br/><br/>");
        java.util.Map<String, java.util.List<ApiDefinition>> grouped = new java.util.LinkedHashMap<>();
        for (ApiDefinition api : selected) {
            grouped.computeIfAbsent(api.getControllerName(), k -> new java.util.ArrayList<>()).add(api);
        }
        for (java.util.Map.Entry<String, java.util.List<ApiDefinition>> e : grouped.entrySet()) {
            preview.append("📁 <b>").append(escapeHtml(e.getKey())).append("</b> (")
                    .append(e.getValue().size()).append(")<br/>");
            for (ApiDefinition api : e.getValue()) {
                String method = api.getHttpMethod() == null ? "" : api.getHttpMethod();
                String url = api.getUrl() == null ? "" : api.getUrl();
                preview.append("&nbsp;&nbsp;• <span style='color:#1f6feb;font-weight:bold;'>")
                        .append(escapeHtml(method)).append("</span> ")
                        .append(escapeHtml(url)).append("<br/>");
            }
        }
        preview.append("</body></html>");
        int ok = Messages.showDialog(project, preview.toString(),
                "确认导出 - Markdown", new String[]{"导出", "取消"}, 0,
                AllIcons.Actions.Help);
        if (ok != 0) return;

        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        List<RequestHistory> history = settings.loadRequestHistory();
        // selected 在上方多次重赋值，非 effectively final，需用 final 副本供 lambda 捕获
        final List<ApiDefinition> selectedApis = selected;

        // 路径选择：统一用 FileChooser.chooseFile 弹出目录选择框（与导入同一套机制），
        // 跨平台一致。用 invokeLater + defaultModalityState 确保上方确认对话框模态状态清除后再弹。
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-api-" + sdf.format(new java.util.Date()) + ".md";
        ApplicationManager.getApplication().invokeLater(() -> {
            String path = TestDataExporter.chooseExportPath(project, suggestName);
            if (path == null) return;
            try {
                ApiDocExporter.exportSelectedApisWithHistory(selectedApis, history, project.getName(), path);
                Messages.showInfoMessage(project, "API文档已导出到:\n" + path, "导出成功");
            } catch (IOException e) {
                Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误");
            }
        }, ModalityState.defaultModalityState());
    }

    private void exportLastReport() {
        if (lastResult == null) {
            Messages.showWarningDialog(project, "尚无测试结果，请先执行测试", "提示");
            return;
        }
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        String dir = project.getBasePath() + "/" + settings.getTestReportDir();

        // Build a quick report from last batch or current result
        TestReport report = new TestReport();
        report.setTestName("单接口测试报告");
        report.setStartTime(lastResult.getTimestamp());
        report.setEndTime(lastResult.getTimestamp() + lastResult.getDurationMs());
        report.getResults().add(lastResult);

        try {
            String path = ReportExporter.exportHtmlReport(report, dir);
            Messages.showInfoMessage(project, "测试报告已导出到:\n" + path, "导出成功");
            // 尝试打开浏览器
            if (Desktop.isDesktopSupported()) {
                try { Desktop.getDesktop().browse(java.nio.file.Paths.get(path).toUri()); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误");
        }
    }

    // ================================================================
    // 数据管理对话框：集中存放 保存/导入/导出 测试配置与全量测试数据
    // ================================================================

    /**
     * 一伦优化 #3：合并后的「环境 & 数据」入口。
     * 用一个 JTabbedPane 同时承载环境管理（{@link EnvironmentManagerDialog#createCenterPanel()}）和
     * 数据管理（{@link DataManagePanel} 卡片列表），关闭后通过 {@link EnvAndDataManageDialog#getPendingDataAction()}
     * 获取用户点选的导出/导入操作并 invokeLater 执行（Windows 模态兼容）。
     */
    private void showEnvAndDataManageDialog() {
        // 配置类操作（AI 设置 · 环境配置 · 测试配置）
        java.util.List<DataManagePanel.Action> configActions = new java.util.ArrayList<>();
        configActions.add(new DataManagePanel.Action(
                AllIcons.ToolbarDecorator.Export, "导出配置",
                "将当前 AI 设置、环境配置、测试配置导出为 JSON",
                this::exportTestConfigAction));
        configActions.add(new DataManagePanel.Action(
                AllIcons.ToolbarDecorator.Import, "导入配置",
                "导入他人配置；AI 设置覆盖当前，环境/同名配置保留本地",
                this::importTestConfigAction));

        // 接口数据类操作（全量接口定义 · 已测接口测试数据）
        java.util.List<DataManagePanel.Action> apiDataActions = new java.util.ArrayList<>();
        apiDataActions.add(new DataManagePanel.Action(
                AllIcons.Actions.Download, "导出接口数据",
                "导出全量接口定义与已测接口的测试数据",
                this::exportTestDataAction));
        apiDataActions.add(new DataManagePanel.Action(
                AllIcons.ToolbarDecorator.Import, "导入接口数据",
                "按接口粒度合并；本地已测接口保留不覆盖，未测接口补入",
                this::importTestDataAction));

        EnvAndDataManageDialog dialog = new EnvAndDataManageDialog(project, configActions, apiDataActions);
        dialog.show();

        // 环境 Tab 的 applyChanges 已在 dialog.doOKAction 内统一执行，这里刷新回主面板
        refreshEnvCombo();
        Environment active = RestAutoLabSettingsState.getInstance(project).getActiveEnvironmentObj();
        if (active != null) {
            applyEnvironmentToPanel(active);
            statusLabel.setText("● 已切换到环境: " + active.getName());
        } else {
            statusLabel.setText("● 环境配置已更新");
        }

        // 数据 Tab：执行用户点选的待办操作（导出/导入），用 invokeLater + defaultModalityState
        // 避免 Windows 上模态状态残留导致原生文件对话框无法弹出
        Runnable pending = dialog.getPendingDataAction();
        if (pending != null) {
            ApplicationManager.getApplication().invokeLater(pending, ModalityState.defaultModalityState());
        }
    }



    /** 一伦优化 #3：原 showDataManagerDialog + 内嵌 DataManagerDialog 类（~170 行）已删除。
     *  现由 {@link EnvAndDataManageDialog} 统一承担「环境 & 数据」合并弹窗：
     *  - 数据卡片列表由 {@link DataManagePanel} 静态构建
     *  - 卡片点击的 Runnable 由 {@link #showEnvAndDataManageDialog()} 注入
     *  - 原 exportTestConfigAction / importTestConfigAction / exportTestDataAction /
     *    importTestDataAction 保留在下方（3297+），仅调用方变化。 */

    // ================================================================
    // 测试配置 / 测试数据 导入导出
    // ================================================================

    /** 构建 AI 配置实时摘要文本，用于导出前核对当前生效配置（与 AI 配置对话框显示口径一致）。
     *  列出全部 AI 字段，便于导出前一眼发现“不是实时配置”的问题。 */
    private static String buildAiConfigSummary(RestAutoLabSettingsState settings) {
        String url = nullToEmpty(settings.getAiServerUrl());
        String path = settings.getAiApiPath();
        String model = nullToEmpty(settings.getAiModel());
        boolean enabled = settings.isAiEnabled();
        String key = settings.getAiToken();
        String keyMasked = (key == null || key.isBlank())
                ? "(空)" : ("****" + key.substring(Math.max(0, key.length() - 4)));
        return "  服务器: " + url + "\n"
                + "  API 路径: " + (path == null || path.isBlank() ? "(空)" : path) + "\n"
                + "  主模型: " + model + "\n"
                + "  API Key: " + keyMasked + "\n"
                + "  启用AI: " + (enabled ? "是" : "否") + "\n"
                + "  系统提示词: " + previewText(settings.getAiSystemPrompt(), 40) + "\n"
                + "  用户提示词: " + previewText(settings.getAiUserPromptTemplate(), 40);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** 截取文本前 maxLen 字符（空白折叠为单空格），超出加省略号；空文本返回 (空) */
    private static String previewText(String s, int maxLen) {
        if (s == null) return "(空)";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) return "(空)";
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen) + "...";
    }

    /** 导出配置信息（AI 设置 + 环境配置 + 测试 Profile）为 JSON 文件。
     *  <p>数据收集与文件写入在后台线程执行，避免接口/历史数据量大时阻塞 EDT
     *  导致文件保存对话框无法弹出（Windows 大数据量场景下的卡顿问题）。</p> */
    private void exportTestConfigAction() {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        int profileCount = settings.getSavedProfileNames().size();
        int envCount = settings.loadEnvironments().size();
        int ok = Messages.showDialog(project,
                "即将导出【当前实时】配置信息：\n\n"
                        + "—— 当前 AI 配置（导出前请核对）——\n"
                        + buildAiConfigSummary(settings)
                        + "\n—— 环境配置 × " + envCount + " 个 ——\n"
                        + "—— 已保存测试配置 × " + profileCount + " 个 ——\n\n"
                        + "若上述配置非最新，请先在「AI 配置」对话框中点确定保存后再导出。",
                "导出配置", new String[]{"确定", "取消"}, 0, AllIcons.ToolbarDecorator.Export);
        if (ok != Messages.OK) return;

        // 确认对话框（Messages.showDialog）关闭后，Windows 上其模态状态可能尚未完全清除。
        // 用 invokeLater + defaultModalityState 确保在完全无模态状态时才弹目录选择框。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = TestDataExporter.chooseExportPath(
                    project, TestDataExporter.suggestFileName("acai-config", "json"));
            if (outputPath == null) return;
            statusLabel.setText("● 正在导出配置...");

            // 后台任务执行序列化与写盘，带进度提示，避免大数据量阻塞 EDT
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "正在导出配置", false) {
                @Override
                public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("正在序列化并写入配置文件...");
                    try {
                        TestDataExporter.exportTestConfig(settings, project.getName(), outputPath);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showInfoMessage(project,
                                    "配置信息已导出到:\n" + outputPath, "导出成功");
                            statusLabel.setText("● 配置已导出: " + outputPath);
                        });
                    } catch (IOException e) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误"));
                    }
                }
            });
        }, ModalityState.defaultModalityState());
    }

    /** 导入他人导出的配置信息 JSON（AI 设置覆盖；环境/Profile 合并） */
    private void importTestConfigAction() {
        FileChooserDescriptor fd = new FileChooserDescriptor(true, false, false, false, false, false);
        fd.setTitle("选择配置信息文件");
        fd.setDescription("选择他人导出的 acai-config JSON 文件");
        fd.withFileFilter(virtualFile -> virtualFile.getName().toLowerCase().endsWith(".json"));
        VirtualFile selected = FileChooser.chooseFile(fd, project, null);
        if (selected == null) return;
        String inputPath = selected.getPath();

        int ok = Messages.showDialog(project,
                "将导入配置信息：\n" + inputPath + "\n\n"
                        + "合并规则：\n"
                        + "• AI 设置：覆盖当前设置\n"
                        + "• 环境配置：本地已存在同名的保留本地，否则新增\n"
                        + "• 测试配置：本地已存在同名的保留本地，否则新增",
                "导入配置", new String[]{"确定", "取消"}, 0, AllIcons.ToolbarDecorator.Import);
        if (ok != Messages.OK) return;

        try {
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            String result = TestDataExporter.importTestConfig(settings, inputPath);
            Messages.showInfoMessage(project, result, "导入成功");
            statusLabel.setText("● 配置已导入");
        } catch (IOException e) {
            Messages.showErrorDialog(project, "导入失败: " + e.getMessage(), "错误");
        }
    }

    /** 导出接口数据（全量接口定义 + 已测接口的测试数据）为 JSON 文件。
     *  <p>序列化与写盘在后台线程执行，避免接口数据量大时阻塞 EDT
     *  导致文件保存对话框无法弹出（Windows 大数据量场景下的卡顿问题）。</p> */
    private void exportTestDataAction() {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> allApis = scanner.getCachedApis();
        int historyCount = settings.loadRequestHistory().size();
        int profileCount = settings.getSavedProfileNames().size();
        int ok = Messages.showDialog(project,
                "即将导出接口数据：\n"
                        + "  • 全量接口定义 × " + allApis.size() + " 个\n"
                        + "  • 已测接口测试数据 × " + historyCount + " 条\n"
                        + "  • 测试配置 × " + profileCount + " 个\n"
                        + "  • 调用统计 / 收藏文件夹及其各自实时参数\n\n"
                        + "导出后，使用同插件用户，可通过「导入接口数据」复用你的接口与测试记录。",
                "导出接口数据", new String[]{"确定", "取消"}, 0, AllIcons.Actions.Download);
        if (ok != Messages.OK) return;

        // 确认对话框关闭后，Windows 上模态状态可能尚未完全清除。
        // 用 invokeLater + defaultModalityState 确保在无模态上下文时才弹目录选择框。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = TestDataExporter.chooseExportPath(
                    project, TestDataExporter.suggestFileName("acai-api-data", "json"));
            if (outputPath == null) return;
            statusLabel.setText("● 正在导出接口数据（" + allApis.size() + " 个接口）...");

            // 后台任务执行序列化与写盘，带进度提示，避免大数据量阻塞 EDT
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "正在导出接口数据", false) {
                @Override
                public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("正在序列化 " + allApis.size() + " 个接口与测试数据...");
                    try {
                        TestDataExporter.exportTestData(settings, allApis, project.getName(), outputPath);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showInfoMessage(project,
                                    "接口数据已导出到:\n" + outputPath, "导出成功");
                            statusLabel.setText("● 接口数据已导出: " + outputPath);
                        });
                    } catch (IOException e) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误"));
                    }
                }
            });
        }, ModalityState.defaultModalityState());
    }

    /** 导入他人导出的接口数据，按接口粒度合并（已有测试数据的接口不覆盖） */
    private void importTestDataAction() {
        FileChooserDescriptor fd = new FileChooserDescriptor(true, false, false, false, false, false);
        fd.setTitle("选择接口数据文件");
        fd.setDescription("选择他人导出的 acai-api-data JSON 文件");
        fd.withFileFilter(virtualFile -> virtualFile.getName().toLowerCase().endsWith(".json"));
        VirtualFile selected = FileChooser.chooseFile(fd, project, null);
        if (selected == null) return;
        String inputPath = selected.getPath();

        int ok = Messages.showDialog(project,
                "将导入接口数据：\n" + inputPath + "\n\n"
                        + "合并规则：\n"
                        + "• 接口定义：本地已存在的接口保留，没有的新增\n"
                        + "• 测试数据：已测过的接口保留本地不覆盖，未测过的接口导入其测试记录\n"
                        + "• 测试配置：本地已存在同名的保留本地，否则新增\n"
                        + "• 调用统计 / 收藏：仅补入本地没有的部分",
                "导入接口数据", new String[]{"确定", "取消"}, 0, AllIcons.ToolbarDecorator.Import);
        if (ok != Messages.OK) return;

        try {
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            ApiScannerService scanner = ApiScannerService.getInstance(project);
            String result = TestDataExporter.importTestData(settings, scanner, inputPath);
            // 刷新历史列表 UI
            requestHistory = settings.loadRequestHistory();
            if (historyListModel != null) {
                historyListModel.clear();
                for (RequestHistory rh : requestHistory) {
                    historyListModel.addElement(rh);
                }
            }
            Messages.showInfoMessage(project, result, "导入成功");
            statusLabel.setText("● 接口数据已导入并合并");
        } catch (IOException e) {
            Messages.showErrorDialog(project, "导入失败: " + e.getMessage(), "错误");
        }
    }

}