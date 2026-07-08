package com.ban.acai.ui;

import com.ban.acai.AcaiConstants;
import com.ban.acai.ai.AiParameterService;
import com.ban.acai.http.HttpExecutorService;
import com.ban.acai.model.*;
import com.ban.acai.scanner.ApiScannerService;
import com.ban.acai.settings.AcaiSettingsState;
import com.ban.acai.util.ApiDocExporter;
import com.ban.acai.util.CurlUtil;
import com.ban.acai.util.ReportExporter;
import com.ban.acai.util.SimpleDiff;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * API调试面板 - 简洁大方的请求构建与测试界面
 */
public class ApiDebuggerPanel extends JPanel {

    private final Project project;
    private ApiDefinition currentApi = null;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ── UI控件 ──
    private final ComboBox<String> methodCombo = new ComboBox<>(AcaiConstants.HTTP_METHOD_NAMES);
    private final JBTextField urlField = new JBTextField();
    private final JButton sendButton = new JButton("发送", AllIcons.Actions.Execute);
    private final JBTextField baseUrlField = new JBTextField();

    private final DefaultTableModel paramTableModel = new DefaultTableModel(
            new Object[]{"参数名", "类型", "位置", "值", "必填", "描述"}, 0);
    private final JBTable paramTable = new JBTable(paramTableModel);

    private final DefaultTableModel headerTableModel = new DefaultTableModel(
            new Object[]{"Header名", "值"}, 0);
    private final JBTable headerTable = new JBTable(headerTableModel);

    private final JBTextArea bodyEditor = new JBTextArea(10, 60);
    private final JBTextArea responseArea = new JBTextArea();
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
    private final JComboBox<String> modelCombo = new JComboBox<>(AcaiConstants.AI_MODEL_OPTIONS);
    private final JBTextArea aiLogArea = new JBTextArea();

    // v3 新增字段
    private JComboBox<String> bodyFormatCombo;
    private JComboBox<Environment> envCombo;
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

    public ApiDebuggerPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        // v3: 加载历史记录
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
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
        setBorder(JBUI.Borders.empty(8));

        // 北部容器：工具栏 + 请求栏
        JPanel northPanel = new JPanel(new BorderLayout(0, 4));
        northPanel.add(createToolbar(), BorderLayout.NORTH);
        northPanel.add(createTopPanel(), BorderLayout.CENTER);
        add(northPanel, BorderLayout.NORTH);

        // Tab页（信息组织）- 类似Apipost的清晰分类
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, 12f));
        tabbedPane.addTab("参数", createParamsTab());
        tabbedPane.addTab("请求头", createHeadersTab());
        tabbedPane.addTab("请求体", createBodyTab());
        tabbedPane.addTab("响应", createResponseTab());
        tabbedPane.addTab("断言", createAssertionsTab());
        tabbedPane.addTab("历史", createHistoryTab());
        tabbedPane.addTab("测试", createTestTab());
        tabbedPane.addTab("AI生成", createAiTab());
        add(tabbedPane, BorderLayout.CENTER);

        // 底部状态栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6)));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel.setForeground(JBColor.GRAY);
        bottomPanel.add(statusLabel, BorderLayout.WEST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    /**
     * 创建顶部工具栏 - 扫描、刷新、环境切换等快捷操作
     */
    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        JButton scanBtn = new JButton("扫描API", AllIcons.Actions.Refresh);
        scanBtn.setToolTipText("重新扫描项目中的所有API接口");
        scanBtn.addActionListener(e -> {
            ApiScannerService.getInstance(project).scanProjectApisAsync();
            statusLabel.setText("● 正在扫描API...");
        });

        // v3: 环境选择下拉框
        envCombo = new JComboBox<>();
        refreshEnvCombo();
        envCombo.setPreferredSize(new Dimension(150, 28));
        envCombo.setToolTipText("切换环境配置");
        envCombo.addActionListener(e -> {
            Environment selected = (Environment) envCombo.getSelectedItem();
            if (selected != null) {
                AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
                settings.setActiveEnvironment(selected.getName());
                settings.setBaseUrl(selected.getBaseUrl());
                baseUrlField.setText(selected.getBaseUrl());
                statusLabel.setText("● 已切换到环境: " + selected.getName());
            }
        });

        JButton envBtn = new JButton("管理", AllIcons.General.Settings);
        envBtn.setToolTipText("管理环境配置");
        envBtn.addActionListener(e -> {
            EnvironmentManagerDialog dialog = new EnvironmentManagerDialog(project);
            if (dialog.showAndGet()) {
                refreshEnvCombo();
                statusLabel.setText("● 环境配置已更新");
            }
        });

        JButton saveBtn = new JButton("保存配置", AllIcons.Actions.MenuSaveall);
        saveBtn.setToolTipText("保存当前测试配置");
        saveBtn.addActionListener(e -> saveCurrentProfile());

        JButton importBtn = new JButton("导入", AllIcons.ToolbarDecorator.Import);
        importBtn.setToolTipText("导入cURL或JSON测试用例");
        importBtn.addActionListener(e -> importCurlOrJson());

        JButton exportBtn = new JButton("导出cURL", AllIcons.ToolbarDecorator.Export);
        exportBtn.setToolTipText("导出为cURL命令");
        exportBtn.addActionListener(e -> exportCurl());

        JButton exportDocBtn = new JButton("导出文档", AllIcons.Actions.Download);
        exportDocBtn.setToolTipText("导出API文档(Markdown)");
        exportDocBtn.addActionListener(e -> exportApiDoc());

        JButton exportReportBtn = new JButton("导出报告", AllIcons.Actions.Dump);
        exportReportBtn.setToolTipText("导出HTML测试报告");
        exportReportBtn.addActionListener(e -> exportLastReport());

        JButton clearCookieBtn = new JButton("清Cookie", AllIcons.Actions.GC);
        clearCookieBtn.setToolTipText("清空Cookie");
        clearCookieBtn.addActionListener(e -> {
            HttpExecutorService.getInstance(project).clearCookies();
            cookieStatusLabel.setText("Cookie: 已清空");
            statusLabel.setText("● Cookie已清空");
        });

        toolbar.add(scanBtn);
        toolbar.addSeparator(new Dimension(8, 0));
        toolbar.add(new JBLabel("环境: "));
        toolbar.add(envCombo);
        toolbar.add(envBtn);
        toolbar.addSeparator(new Dimension(8, 0));
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(saveBtn);
        toolbar.add(importBtn);
        toolbar.add(exportBtn);
        toolbar.add(exportDocBtn);
        toolbar.add(exportReportBtn);
        toolbar.addSeparator(new Dimension(4, 0));
        toolbar.add(clearCookieBtn);

        return toolbar;
    }

    /** 刷新环境下拉框 */
    private void refreshEnvCombo() {
        if (envCombo == null) return;
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        envCombo.removeAllItems();
        Environment active = settings.getActiveEnvironmentObj();
        for (Environment env : settings.loadEnvironments()) {
            envCombo.addItem(env);
            if (env.getName().equals(settings.getActiveEnvironment())) {
                envCombo.setSelectedItem(env);
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
        baseUrlField.setText(AcaiSettingsState.getInstance(project).getBaseUrl());
        baseUrlField.setFont(baseUrlField.getFont().deriveFont(Font.PLAIN, 12f));
        baseUrlField.setToolTipText("服务基础地址，如 http://localhost:8080");
        baseUrlField.setPreferredSize(new Dimension(200, 28));
        panel.add(baseUrlField, gbc);

        gbc.gridx = 1; gbc.weightx = 0.0;
        methodCombo.setPreferredSize(new Dimension(110, 28));
        methodCombo.setMinimumSize(new Dimension(110, 28));
        methodCombo.setFont(methodCombo.getFont().deriveFont(Font.BOLD, 12f));
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
        urlField.setFont(urlField.getFont().deriveFont(Font.PLAIN, 13f));
        urlField.setBackground(JBColor.namedColor("TextField.background", Color.WHITE));
        panel.add(urlField, gbc);

        gbc.gridx = 3; gbc.weightx = 0.0;
        sendButton.putClientProperty("JButton.buttonType", "default");
        sendButton.setMargin(new Insets(4, 12, 4, 12));
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, 12f));
        sendButton.setToolTipText("发送请求到当前接口");
        panel.add(sendButton, gbc);

        return panel;
    }

    private JPanel createParamsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        paramTable.setRowHeight(28);  // 增加行高，更易点击
        paramTable.setShowGrid(true);
        paramTable.setGridColor(JBColor.border());
        paramTable.setIntercellSpacing(new Dimension(1, 1));
        paramTable.getTableHeader().setReorderingAllowed(false);
        paramTable.setFont(paramTable.getFont().deriveFont(Font.PLAIN, 12f));  // 增大字体
        paramTable.getTableHeader().setFont(paramTable.getTableHeader().getFont().deriveFont(Font.BOLD, 11f));
        
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
        
        // 为值列添加智能编辑器（支持枚举提示）
        paramTable.getColumnModel().getColumn(3).setCellEditor(new SmartValueEditor());

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
        
        // 筛选按钮组
        JButton filterAllBtn = iconButton("全部", null, e -> filterParamsByLocation(null));
        filterAllBtn.setToolTipText("显示所有参数");
        filterAllBtn.putClientProperty("JButton.buttonType", "default");
        bottomBar.add(filterAllBtn);
        
        JButton filterPathBtn = iconButton("PATH", null, e -> filterParamsByLocation("PATH"));
        filterPathBtn.setToolTipText("只显示路径参数");
        filterPathBtn.setForeground(AcaiConstants.COLOR_PUT);
        bottomBar.add(filterPathBtn);
        
        JButton filterQueryBtn = iconButton("QUERY", null, e -> filterParamsByLocation("QUERY"));
        filterQueryBtn.setToolTipText("只显示查询参数");
        filterQueryBtn.setForeground(AcaiConstants.COLOR_GET);
        bottomBar.add(filterQueryBtn);
        
        JButton filterBodyBtn = iconButton("BODY", null, e -> filterParamsByLocation("BODY"));
        filterBodyBtn.setToolTipText("只显示请求体");
        filterBodyBtn.setForeground(AcaiConstants.COLOR_POST);
        bottomBar.add(filterBodyBtn);
        
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createHeadersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        headerTable.setRowHeight(26);
        headerTable.setShowGrid(true);
        headerTable.setGridColor(JBColor.border());
        headerTable.setIntercellSpacing(new Dimension(1, 1));
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
        cookieStatusLabel.setFont(cookieStatusLabel.getFont().deriveFont(Font.PLAIN, 10f));
        cookieStatusLabel.setForeground(JBColor.GRAY);
        topBar.add(Box.createHorizontalStrut(20));
        topBar.add(cookieStatusLabel);

        panel.add(topBar, BorderLayout.NORTH);

        bodyEditor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        bodyEditor.setLineWrap(true);
        bodyEditor.setWrapStyleWord(true);
        panel.add(new JBScrollPane(bodyEditor), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton fmtBtn = iconButton("格式化", AllIcons.Actions.PrettyPrint, e -> formatJson());
        JButton clrBtn = iconButton("清空", AllIcons.Actions.GC, e -> bodyEditor.setText(""));
        btnPanel.add(fmtBtn);
        btnPanel.add(clrBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createResponseTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // 状态栏
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        responseStatusLabel.setFont(responseStatusLabel.getFont().deriveFont(Font.BOLD, 12f));
        responseTimeLabel.setFont(responseTimeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        responseSizeLabel.setFont(responseSizeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        statusPanel.add(responseStatusLabel);
        statusPanel.add(createSeparator());
        statusPanel.add(responseTimeLabel);
        statusPanel.add(createSeparator());
        statusPanel.add(responseSizeLabel);
        panel.add(statusPanel, BorderLayout.NORTH);

        // === 响应内容区域（支持文本/树形切换） ===
        
        // 文本视图
        responseArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        responseArea.setEditable(false);
        responseArea.setLineWrap(true);
        responseArea.setWrapStyleWord(true);
        JScrollPane textScroll = new JBScrollPane(responseArea);
        responseContentPanel.add(textScroll, "text");
        
        // 树形视图
        responseJsonTree.setRootVisible(false);
        responseJsonTree.setShowsRootHandles(true);
        responseJsonTree.setFont(responseJsonTree.getFont().deriveFont(Font.PLAIN, 12f));
        responseJsonTree.setCellRenderer(new JsonTreeNodeRenderer());
        JScrollPane treeScroll = new JBScrollPane(responseJsonTree);
        responseContentPanel.add(treeScroll, "tree");
        
        // 默认显示文本视图
        responseCardLayout.show(responseContentPanel, "text");
        panel.add(responseContentPanel, BorderLayout.CENTER);

        // 底部操作按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        
        JButton viewToggleBtn = iconButton("🌳 树形视图", AllIcons.Actions.ShowAsTree, e -> toggleResponseView());
        viewToggleBtn.setToolTipText("🌳 在文本/树形视图间切换");
        
        JButton fmtBtn = iconButton("📋 格式化", AllIcons.Actions.PrettyPrint, e -> formatResponseJson());
        fmtBtn.setToolTipText("📋 格式化JSON响应（美化显示）");
        
        JButton copyBtn = iconButton("📄 复制", AllIcons.Actions.Copy, e -> copyResponseToClipboard());
        copyBtn.setToolTipText("📄 复制响应内容到剪贴板");
        
        JButton clearBtn = iconButton("🗑️ 清空", AllIcons.Actions.GC, e -> responseArea.setText(""));
        clearBtn.setToolTipText("🗑️ 清空响应内容");
        
        JBLabel hintLabel = new JBLabel("💡 提示: 点击'树形视图'可折叠展开JSON节点");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC, 10f));
        hintLabel.setForeground(JBColor.GRAY);
        
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
        batchTestBtn = iconButton("▶ 批量测试", AllIcons.Actions.Execute, e -> toggleBatchTest());
        batchTestBtn.setToolTipText("点击开始批量测试所有API，测试中再次点击可停止");
        batchTestBtn.putClientProperty("JButton.buttonType", "default");
        btnPanel.add(batchTestBtn);
        
        // 单接口测试按钮
        JButton runCurBtn = iconButton("▶ 测试当前", AllIcons.Actions.Execute, e -> runCurrentTest());
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
        testResultArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
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
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));

        // === 顶部：配置状态栏 ===
        JPanel statusCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusCard.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(6)));
        
        JBLabel configStatusLabel = new JBLabel("🤖 AI配置:");
        configStatusLabel.setFont(configStatusLabel.getFont().deriveFont(Font.BOLD, 11f));
        statusCard.add(configStatusLabel);
        
        JBLabel configInfo = new JBLabel(getAiConfigSummary());
        configInfo.setFont(configInfo.getFont().deriveFont(Font.PLAIN, 11f));
        configInfo.setForeground(JBColor.GRAY);
        statusCard.add(configInfo);
        
        JButton configBtn = iconButton("⚙️ 配置", AllIcons.General.Settings, e -> showAiConfigDialog());
        configBtn.setToolTipText("⚙️ 配置AI服务器、API Key和模型");
        statusCard.add(configBtn);
        
        panel.add(statusCard, BorderLayout.NORTH);

        // === 中间：生成控制区 ===
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controlPanel.setBorder(JBUI.Borders.empty(4, 0));
        
        JBLabel scenarioLabel = new JBLabel("📊 场景:");
        scenarioLabel.setFont(scenarioLabel.getFont().deriveFont(Font.PLAIN, 11f));
        controlPanel.add(scenarioLabel);
        
        scenarioCombo.setPreferredSize(new Dimension(130, 26));
        scenarioCombo.setFont(scenarioCombo.getFont().deriveFont(Font.PLAIN, 11f));
        scenarioCombo.setToolTipText("选择测试场景：正常/边界/异常/全量");
        controlPanel.add(scenarioCombo);

        JButton genBtn = iconButton("⚡ 生成参数", AllIcons.Actions.Lightning, e -> {
            AiParameterService.TestScenario s = (AiParameterService.TestScenario) scenarioCombo.getSelectedItem();
            generateAiParameters(s);
        });
        genBtn.setToolTipText("⚡ 调用AI生成真实可用的测试参数");
        genBtn.putClientProperty("JButton.buttonType", "default");
        controlPanel.add(genBtn);

        JButton genMultiBtn = iconButton("🔄 全量生成", AllIcons.Actions.RunAll, e -> {
            scenarioCombo.setSelectedItem(AiParameterService.TestScenario.FULL);
            generateAiParameters(AiParameterService.TestScenario.FULL);
        });
        genMultiBtn.setToolTipText("🔄 生成正常+边界+异常多组数据");
        controlPanel.add(genMultiBtn);

        panel.add(controlPanel, BorderLayout.CENTER);

        // === 底部：AI交互日志 ===
        JPanel logPanel = new JPanel(new BorderLayout(0, 4));
        
        JBLabel logTitle = new JBLabel("📝 AI交互日志");
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD, 11f));
        logTitle.setBorder(JBUI.Borders.emptyBottom(2));
        logPanel.add(logTitle, BorderLayout.NORTH);
        
        aiLogArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        aiLogArea.setEditable(false);
        aiLogArea.setLineWrap(true);
        aiLogArea.setText("💡 使用流程：\n1. 点击『配置』设置AI服务器URL和API Key\n2. 选择测试场景（正常值/边界值/异常值/全量）\n3. 点击『生成参数』\n4. 下方日志区会显示：\n   - 📤 发送请求：模型、场景、参数列表\n   - 📥 模型原始返回：AI模型的JSON响应\n   - ✅ 解析后参数：自动填充到参数表格\n\n等待操作...\n");
        aiLogArea.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(4)));
        JScrollPane logScroll = new JBScrollPane(aiLogArea);
        logScroll.setPreferredSize(new Dimension(-1, 300));
        logPanel.add(logScroll, BorderLayout.CENTER);
        
        panel.add(logPanel, BorderLayout.SOUTH);

        return panel;
    }
    
    /**
     * 获取AI配置摘要显示
     */
    private String getAiConfigSummary() {
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
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
        JButton btn = new JButton(text, icon);
        btn.putClientProperty("JButton.buttonType", "square");
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setFocusPainted(false);
        btn.addActionListener(listener);
        return btn;
    }

    private void setupActions() {
        sendButton.addActionListener(e -> sendRequest());
        baseUrlField.addActionListener(e ->
                AcaiSettingsState.getInstance(project).setBaseUrl(baseUrlField.getText().trim()));
    }

    // ================================================================
    // 公共方法
    // ================================================================

    public void loadApi(ApiDefinition api) {
        currentApi = api;
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
        
        // 添加请求体参数
        for (ApiParameter param : api.bodyParameters()) {
            paramTableModel.addRow(new Object[]{
                    param.getName(),
                    param.getType(),
                    "BODY",
                    param.generateDefaultValue(),
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
        }
        
        // 如果没有显式参数，显示默认请求头
        if (paramTableModel.getRowCount() == 0) {
            headerTableModel.setRowCount(0);
            headerTableModel.addRow(new Object[]{AcaiConstants.HEADER_CONTENT_TYPE, api.getConsumes()});
            headerTableModel.addRow(new Object[]{AcaiConstants.HEADER_ACCEPT, api.getProduces()});
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
        Color color;
        if (result.getStatusCode() >= 200 && result.getStatusCode() <= 299) {
            color = AcaiConstants.COLOR_GET;
        } else if (result.getStatusCode() >= 400 && result.getStatusCode() <= 499) {
            color = new JBColor(new Color(232, 168, 32), new Color(255, 183, 77));
        } else {
            color = AcaiConstants.COLOR_DELETE;
        }
        responseStatusLabel.setForeground(color);
        responseStatusLabel.setText("状态: " + result.getStatusCode());
        responseTimeLabel.setText("耗时: " + result.getDurationMs() + " ms");
        responseSizeLabel.setText("大小: " + formatBytes(result.getResponseBody().length()));
        responseArea.setText(result.getResponseBody());
        responseArea.setCaretPosition(0);
        responseCardLayout.show(responseContentPanel, "text");
        buildResponseJsonTree(result.getResponseBody());
        tabbedPane.setSelectedIndex(3); // 响应tab
    }

    private String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
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
            
        appendAiLog("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        appendAiLog("▶ 开始生成 - 场景: " + scenario.getDisplayName() + "\n");
        appendAiLog("▶ API: " + currentApi.getHttpMethod() + " " + currentApi.getUrl() + "\n");
        appendAiLog("▶ 参数数量: " + currentApi.getParameters().size() + " 个\n");
        statusLabel.setText("○ AI生成中...");
    
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AiParameterService ai = AiParameterService.getInstance(project);
                
            // 显示Prompt预览
            appendAiLog("\n📤 发送请求:\n");
            appendAiLog("  模型: " + AcaiSettingsState.getInstance(project).getAiModel() + "\n");
            appendAiLog("  场景: " + scenario.getDescription() + "\n");
            appendAiLog("  参数列表:\n");
            for (ApiParameter param : currentApi.getParameters()) {
                appendAiLog("    - " + param.getName() + " (" + param.getType() + ", " + param.getLocation() + ")\n");
            }
                
            // 调用AI并获取原始响应
            AiParameterService.GenerateResult result = ai.generateParametersWithRaw(currentApi, scenario);
    
            ApplicationManager.getApplication().invokeLater(() -> {
                List<Map<String, String>> sets = result.getParameters();
                    
                // 显示模型原始返回内容
                appendAiLog("\n📥 模型原始返回:\n");
                appendAiLog("─────────────────────────\n");
                String rawResponse = result.getRawResponse();
                if (rawResponse != null && !rawResponse.isBlank()) {
                    appendAiLog(rawResponse + "\n");
                } else {
                    appendAiLog("(无响应内容)\n");
                }
                appendAiLog("─────────────────────────\n");
                    
                // 显示是否使用了AI
                if (!result.isUsedAi()) {
                    appendAiLog("\n⚠ 注意: 本次使用的是本地默认值生成，而非AI生成\n");
                    appendAiLog("  原因: " + rawResponse + "\n");
                    appendAiLog("  解决: 请点击『配置』按钮设置AI服务器和API Key\n\n");
                }
                    
                if (!sets.isEmpty()) {
                    Map<String, String> first = sets.get(0);
                        
                    // 显示解析后的参数JSON
                    appendAiLog("\n✅ 解析后的参数 (" + sets.size() + " 组):\n");
                    try {
                        String json = gson.toJson(first);
                        appendAiLog(json.replace("\n", "\n  ") + "\n\n");
                    } catch (Exception e) {
                        appendAiLog("JSON格式化失败: " + e.getMessage() + "\n\n");
                    }
                        
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
                        
                    appendAiLog("✓ 已填充 " + filledCount + " 个参数值到表格\n");
                    appendAiLog("💡 切换到『参数』Tab查看和编辑\n");
                    statusLabel.setText("● 生成完成 (" + (result.isUsedAi() ? "AI" : "默认") + ", " + filledCount + " 个参数)");
                        
                    tabbedPane.setSelectedIndex(0);
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Thread.sleep(200);
                            highlightModifiedRows(modifiedRows);
                        } catch (InterruptedException e) { /* ignore */ }
                    });
                } else {
                    appendAiLog("\n✗ 未返回任何结果\n");
                    statusLabel.setText("○ 生成失败");
                }
            });
        });
    }

    private void appendAiLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            aiLogArea.append(msg);
            aiLogArea.setCaretPosition(aiLogArea.getDocument().getLength());
        });
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
                        batchTestBtn.setText("▶ 批量测试");
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
    }
    
    /**
     * 按位置筛选参数
     */
    private void filterParamsByLocation(String location) {
        if (location == null) {
            // 显示所有参数，重新加载当前API
            if (currentApi != null) {
                loadApi(currentApi);
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
                statusLabel.setText("● 已切换到树形视图");
            } catch (Exception e) {
                Messages.showWarningDialog(project, "无法解析为JSON: " + e.getMessage(), "非JSON响应");
            }
        } else {
            responseCardLayout.show(responseContentPanel, "text");
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
     * 显示AI配置对话框
     */
    private void showAiConfigDialog() {
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        
        JBTextField urlField = new JBTextField(settings.getAiServerUrl(), 35);
        urlField.setToolTipText("例如: https://ark.cn-beijing.volces.com/api/v3");
        
        JBPasswordField keyField = new JBPasswordField();
        keyField.setText(settings.getAiToken());
        keyField.setToolTipText("留空表示使用本地模型（如Ollama）");
        
        JComboBox<String> modelField = new JComboBox<>(AcaiConstants.AI_MODEL_OPTIONS);
        modelField.setEditable(true);
        modelField.setSelectedItem(settings.getAiModel());
        modelField.setToolTipText("选择或输入模型名称");
        
        JCheckBox localModelCheck = new JCheckBox("本地模型（无需API Key）");
        localModelCheck.setSelected(settings.getAiToken().isBlank());
        localModelCheck.setToolTipText("勾选后将跳过API Key验证");
        localModelCheck.addActionListener(e -> keyField.setEnabled(!localModelCheck.isSelected()));
        keyField.setEnabled(!localModelCheck.isSelected());

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
            systemPromptArea.setText(AcaiConstants.AI_SYSTEM_PROMPT);
            userPromptArea.setText(AcaiConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        });

        JPanel form = new JPanel(new GridBagLayout());
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
        JBLabel keyLabel = new JBLabel("🔑 API Key:");
        keyLabel.setFont(keyLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(keyLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(keyField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        JBLabel modelLabel = new JBLabel("🤖 模型:");
        modelLabel.setFont(modelLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(modelLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(modelField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        form.add(localModelCheck, gbc);

        // 提示文字
        JBLabel hintLabel = new JBLabel("<html><i>提示: 本地模型只需填URL和模型名，云端模型需要API Key</i></html>");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 10f));
        hintLabel.setForeground(JBColor.GRAY);
        gbc.gridy = 4;
        form.add(hintLabel, gbc);

        // ── AI 提示词自定义分区 ──
        gbc.gridy = 5; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        JSeparator sep1 = new JSeparator();
        form.add(sep1, gbc);

        gbc.gridy = 6; gbc.fill = GridBagConstraints.NONE;
        JBLabel promptHeader = new JBLabel("📝 AI 提示词自定义");
        promptHeader.setFont(promptHeader.getFont().deriveFont(Font.BOLD, 12f));
        promptHeader.setForeground(JBColor.BLUE);
        form.add(promptHeader, gbc);

        gbc.gridy = 7; gbc.fill = GridBagConstraints.HORIZONTAL;
        JBLabel systemPromptLabel = new JBLabel("系统提示词 (System Prompt):");
        systemPromptLabel.setFont(systemPromptLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(systemPromptLabel, gbc);

        gbc.gridy = 8; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.3;
        form.add(systemPromptScroll, gbc);

        gbc.gridy = 9; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        JBLabel userPromptLabel = new JBLabel("用户提示词模板 (User Prompt):");
        userPromptLabel.setFont(userPromptLabel.getFont().deriveFont(Font.BOLD, 11f));
        form.add(userPromptLabel, gbc);

        gbc.gridy = 10; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.5;
        form.add(userPromptScroll, gbc);

        gbc.gridy = 11; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
        JBLabel placeholderHint = new JBLabel("<html><font color='#888888' size='2'>占位符: ${API_URL} ${HTTP_METHOD} ${API_NAME} ${CONTROLLER_NAME} ${DESCRIPTION} ${CONTENT_TYPE} ${PARAMETERS} ${SCENARIO_NAME} ${SCENARIO_DESC} ${FULL_HINT}</font></html>");
        form.add(placeholderHint, gbc);

        gbc.gridy = 12; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.EAST;
        form.add(resetPromptBtn, gbc);

        // 内容较多，套滚动面板防止超出屏幕高度
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setPreferredSize(new Dimension(560, 560));
        formScroll.setBorder(null);
        int result = JOptionPane.showConfirmDialog(this, formScroll, "AI配置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        
        if (result == JOptionPane.OK_OPTION) {
            String serverUrl = urlField.getText().trim();
            String apiKey = new String(keyField.getPassword()).trim();
            String model = String.valueOf(modelField.getSelectedItem()).trim();
            
            if (serverUrl.isBlank()) {
                Messages.showWarningDialog(project, "服务器URL不能为空", "配置错误");
                return;
            }
            
            settings.setAiServerUrl(serverUrl);
            settings.setAiToken(localModelCheck.isSelected() ? "" : apiKey);
            settings.setAiModel(model);
            settings.setAiSystemPrompt(systemPromptArea.getText());
            settings.setAiUserPromptTemplate(userPromptArea.getText());

            appendAiLog("✅ AI配置已保存\n");
            appendAiLog("  服务器: " + serverUrl + "\n");
            appendAiLog("  模型: " + model + "\n");
            appendAiLog("  系统提示词: " + (systemPromptArea.getText().isBlank() ? "(默认)" : "已自定义") + "\n");
            appendAiLog("  用户提示词: " + (userPromptArea.getText().isBlank() ? "(默认)" : "已自定义") + "\n\n");
            statusLabel.setText("● AI配置已更新");
            
            Messages.showInfoMessage(project, "AI配置已成功保存！\n\n服务器: " + serverUrl + "\n模型: " + model, "保存成功");
        }
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
                    case "PATH": setForeground(AcaiConstants.COLOR_PUT); break;
                    case "QUERY": setForeground(AcaiConstants.COLOR_GET); break;
                    case "BODY": setForeground(AcaiConstants.COLOR_POST); break;
                    case "HEADER": setForeground(AcaiConstants.COLOR_PATCH); break;
                    default: setForeground(table.getForeground());
                }
            }
            return this;
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
                setForeground(AcaiConstants.COLOR_GET);
            }
            return this;
        }
    }
    
    /** 智能值编辑器 - 根据参数名提供枚举提示 */
    private static class SmartValueEditor extends DefaultCellEditor {
        private final JComboBox<String> enumCombo = new JComboBox<>();
        
        public SmartValueEditor() {
            super(new JTextField());
            enumCombo.setEditable(true);
            enumCombo.setFont(new Font("Dialog", Font.PLAIN, 12));
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            // 获取参数名（第0列）
            String paramName = (String) table.getValueAt(row, 0);
            String paramType = (String) table.getValueAt(row, 1);
            
            // 根据参数名提供枚举建议
            String[] suggestions = getEnumSuggestions(paramName, paramType);
            
            if (suggestions.length > 0) {
                // 有枚举建议，使用下拉框
                enumCombo.removeAllItems();
                for (String s : suggestions) {
                    enumCombo.addItem(s);
                }
                if (value != null) {
                    enumCombo.setSelectedItem(value);
                }
                return enumCombo;
            } else {
                // 无枚举建议，使用普通文本框
                JTextField field = (JTextField) super.getTableCellEditorComponent(table, value, isSelected, row, column);
                field.setFont(new Font("Monospaced", Font.PLAIN, 12));
                return field;
            }
        }
        
        /**
         * 根据参数名和类型提供枚举建议
         */
        private String[] getEnumSuggestions(String paramName, String paramType) {
            if (paramName == null || paramType == null) return new String[0];
            
            String lowerName = paramName.toLowerCase();
            
            // 状态字段
            if (lowerName.contains("status") || lowerName.contains("state")) {
                return new String[]{"1", "0", "-1", "true", "false"};
            }
            // 性别字段
            if (lowerName.contains("gender") || lowerName.contains("sex")) {
                return new String[]{"male", "female", "other"};
            }
            // 类型字段
            if (lowerName.contains("type") || lowerName.contains("category")) {
                return new String[]{"default", "premium", "vip", "admin", "user"};
            }
            // 排序字段
            if (lowerName.contains("sort") || lowerName.contains("order")) {
                return new String[]{"asc", "desc", "1", "-1"};
            }
            // 布尔字段
            if ("Boolean".equalsIgnoreCase(paramType) || lowerName.contains("enabled") || lowerName.contains("active")) {
                return new String[]{"true", "false"};
            }
            // 支付方式
            if (lowerName.contains("payment") || lowerName.contains("pay")) {
                return new String[]{"alipay", "wechat", "credit_card", "paypal"};
            }
            // 时间范围
            if (lowerName.contains("range") || lowerName.contains("period")) {
                return new String[]{"today", "week", "month", "year"};
            }
            
            return new String[0];
        }
    }
    
    /** JSON树节点渲染器 */
    private static class JsonTreeNodeRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            
            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObj = node.getUserObject();
                
                if (userObj instanceof String) {
                    String text = (String) userObj;
                    
                    // 根据内容设置图标和颜色
                    if (text.contains(":")) {
                        // 键值对
                        String key = text.substring(0, text.indexOf(":"));
                        if (!key.startsWith("[") && !key.equals("root")) {
                            setIcon(AllIcons.Nodes.Property);
                            setForeground(AcaiConstants.COLOR_GET);  // 蓝色
                        } else if (key.startsWith("[")) {
                            setIcon(AllIcons.Nodes.Folder);  // 数组元素
                            setForeground(JBColor.foreground());
                        }
                    } else if (text.equals("root")) {
                        setIcon(AllIcons.Json.Object);
                    }
                }
            }
            
            return this;
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
        assertionTable.setRowHeight(24);
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
        historyList.setFont(new Font("Monospaced", Font.PLAIN, 11));

        // 加载历史
        for (RequestHistory h : requestHistory) {
            historyListModel.addElement(h);
        }

        panel.add(new JBLabel("请求历史 (最近" + AcaiConstants.MAX_HISTORY_SIZE + "条)，双击重新发送"), BorderLayout.NORTH);
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
                result.getResponseHeaders(),
                result.getRequestBody(),
                result.getStatusCode(),
                result.getResponseBody(),
                result.getDurationMs(),
                result.getApiDefinition().getName()
        );
        requestHistory.add(0, h);
        // 限制历史记录数量
        while (requestHistory.size() > AcaiConstants.MAX_HISTORY_SIZE) {
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
            AcaiSettingsState.getInstance(project).recordApiCall(currentApi.uniqueKey());
        }

        lastResult = result;

        // 更新断言结果
        updateAssertionResults(result);
    }

    private void persistHistory() {
        AcaiSettingsState.getInstance(project).saveRequestHistory(requestHistory);
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

    private void saveCurrentProfile() {
        String name = Messages.showInputDialog(project, "请输入配置名称:", "保存测试配置", Messages.getQuestionIcon());
        if (name == null || name.isBlank()) return;
        TestProfile profile = new TestProfile();
        profile.setName(name);
        profile.setBaseUrl(baseUrlField.getText().trim());
        profile.setGlobalHeaders(collectHeaderValues());
        AcaiSettingsState.getInstance(project).saveTestProfile(name, profile);
        Messages.showInfoMessage(project, "配置 '" + name + "' 已保存", "保存成功");
    }

    private void importCurlOrJson() {
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
                AcaiConstants.CONTENT_TYPE_FORM_URLENCODED : currentApi.getConsumes();
        headers.put("Content-Type", contentType);

        String curl = CurlUtil.generateCurl((String) methodCombo.getSelectedItem(),
                urlBuilder.toString(), headers, body, contentType);

        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(curl);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        Messages.showInfoMessage(project, "cURL命令已复制到剪贴板！\n\n" + curl.substring(0, Math.min(curl.length(), 200)) + (curl.length() > 200 ? "..." : ""), "导出成功");
    }

    private void exportApiDoc() {
        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> apis = scanner.getCachedApis();
        if (apis.isEmpty()) {
            Messages.showWarningDialog(project, "暂无API，请先扫描", "提示");
            return;
        }

        String path = project.getBasePath() + "/.acai/api-doc.md";
        try {
            ApiDocExporter.exportAllDoc(apis, path);
            Messages.showInfoMessage(project, "API文档已导出到:\n" + path, "导出成功");
        } catch (IOException e) {
            Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误");
        }
    }

    private void exportLastReport() {
        if (lastResult == null) {
            Messages.showWarningDialog(project, "尚无测试结果，请先执行测试", "提示");
            return;
        }
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
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

}