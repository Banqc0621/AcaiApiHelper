package com.hronline.ui;

import com.hronline.RestAutoLabConstants;
import com.hronline.ai.AiParameterService;
import com.hronline.http.HttpExecutorService;
import com.hronline.http.PreRequestProcessor;
import com.hronline.model.*;
import com.hronline.scanner.ApiScannerService;
import com.hronline.scanner.StarredFolderService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.hronline.util.ApiDocExporter;
import com.hronline.util.CurlUtil;
import com.hronline.util.TemplateEngine;
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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.DocumentAdapter;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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
    /** 一伦优化 v11：发送/停止合并为单一按钮 —— 空闲时显示「Execute」图标，请求中显示自旋 spinner，再点一次即取消。 */
    private final JButton sendButton = new JButton(AllIcons.Actions.Execute);
    private final UiStyle.LoadingSpinnerIcon sendSpinner = new UiStyle.LoadingSpinnerIcon();
    private final JBTextField baseUrlField = new JBTextField();
    private final JBLabel activeEnvInfoLabel = new JBLabel("当前环境: -");
    private final JBTextArea preRequestScriptArea = new JBTextArea(4, 32);
    private final DefaultTableModel variableOverrideModel = new DefaultTableModel(
            new Object[]{"变量名", "覆盖值"}, 0);
    private final JBTable variableOverrideTable = new JBTable(variableOverrideModel);
    private boolean loadingPreRequestConfig = false;
    private volatile Future<?> activeRequestFuture;
    private final AtomicLong requestSequence = new AtomicLong();

    private final DefaultTableModel paramTableModel = new DefaultTableModel(
            new Object[]{"参数名", "类型", "位置", "值", "必填", "描述"}, 0);
    private final JBTable paramTable = new JBTable(paramTableModel);

    /** 附件面板：仅在当前接口含文件参数时显示，每个文件参数一行：选择按钮 + 已选路径 */
    private final JPanel attachmentPanel = new JPanel();
    private final JLabel attachmentTitle = new JLabel("文件参数（用于 multipart/form-data 上传）");
    /** key=参数名, value=当前选择的本地文件绝对路径（用户未选择时为空） */
    private final Map<String, String> attachmentPaths = new LinkedHashMap<>();
    /** key=参数名, value=对应的参数控件（用于在 updateAttachmentPanel 时清空重建） */
    private final Map<String, javax.swing.JLabel> attachmentPathLabels = new LinkedHashMap<>();

    private final DefaultTableModel headerTableModel = new DefaultTableModel(
            new Object[]{"Header名", "值"}, 0);
    private final JBTable headerTable = new JBTable(headerTableModel);
    /** 最近一次注入的环境级请求头名称，用于切换环境时精准移除旧值。 */
    private final Set<String> appliedGlobalHeaderNames = new LinkedHashSet<>();

    // ── 请求体编辑器（一伦优化 v29：移除 紧凑/标准/展开 3 态切换，固定标准行数）──
    /** 标准态行数（默认） */
    private static final int BODY_ROWS_STANDARD = 8;
    private final JBTextArea bodyEditor = new JBTextArea(BODY_ROWS_STANDARD, 60);
    /** body 编辑器的滚动容器引用 */
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
            new AiParameterService.TestScenario[]{
                    AiParameterService.TestScenario.NORMAL,
                    AiParameterService.TestScenario.BOUNDARY,
                    AiParameterService.TestScenario.ABNORMAL
            });
    private final JComboBox<String> modelCombo = new JComboBox<>(RestAutoLabConstants.AI_MODEL_OPTIONS);

    // v3 新增字段
    private JComboBox<String> bodyFormatCombo;
    private JComboBox<Environment> envCombo;
    /** 环境下拉框重建期间的抑制标志，避免 setSelectedItem 触发 ActionListener 误切换环境 */
    private boolean suppressEnvComboAction = false;
    /** 一伦优化 v23：当前打开的"环境 & 数据"弹窗（可能为 null）。用来在外部修改时通知弹窗刷新。 */
    private EnvAndDataManageDialog currentEnvAndDataDialog = null;

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

        // 一伦优化：右面板顶部 2 行布局
        //   Row 1: 环境 / 服务基础地址
        //   Row 2: 请求方法 / 路径 / 发送 / 停止
        // 前置脚本/变量覆盖/AI 配置/导出/历史 等"低频/配置类"功能统一到左侧"…"弹层。
        add(createRequestTopPanel(), BorderLayout.NORTH);

        // 一伦优化 #5：右面板拆为「请求配置层（顶部，可拖动）+ 响应展示层（底部）」双层布局。
        // 请求层使用原 TabbedPane（去掉"响应"Tab），响应层常驻底部，可拖动分割条调比例。
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        tabbedPane.addTab("参数", createParamsTab());
        tabbedPane.addTab("请求头", createHeadersTab());
        tabbedPane.addTab("请求体", createBodyTab());
        // 一伦优化 v4：从第一性原理出发，删除"断言"Tab（断言通过状态码 / 期望字段管理更轻量，
        // 单次调试对断言诉求弱；批量测试已有"允许的状态码"统一控制）。后续若要恢复，可放回"测试"位置。
        tabbedPane.addTab("历史", createHistoryTab());
        // 一伦优化 v4：合并"AI 生成"与"测试"两个 Tab 为一个 "AI 助手" Tab，
        // 场景下拉 + 助手按钮（弹出生成 / 测试当前）一站式完成。
        tabbedPane.addTab("AI 助手", createAiTab());
        JScrollPane requestScroll = new JBScrollPane(tabbedPane);
        requestScroll.setBorder(JBUI.Borders.empty());
        requestScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        requestScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel responsePanel = createResponsePanel();

        // 垂直分割：true=垂直方向（上下），0.6=请求编辑层占 60%
        JBSplitter splitter = new JBSplitter(true, 0.6f);
        // 一伦优化 v35：「发起请求」与 tabs 同一行，钉死最右端 —— 覆盖层方案，按钮不占布局宽度
        splitter.setFirstComponent(new TabStripSendButtonLayer(requestScroll, createTabStripSendButton()));
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
     * v3.0：请求体编辑器交互
     * <ul>
     *   <li>Ctrl+Z / Ctrl+Y 撤销 / 重做</li>
     *   <li>3 态显式切换：紧凑（3 行）/ 标准（8 行）/ 展开（18 行）
     *       —— 改用工具栏按钮显式切换，<b>彻底移除</b>原来的 focus 抖动（focusGained 展开 / focusLost 折叠），
     *       避免输入时窗口高度持续抖动影响排版观感</li>
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
    }

    /**
     * 一伦优化 v10：右面板顶部「请求头部」灵动化重构 —— ui-ux-pro-max-skill 审美 v4。
     * <p>关键改动：</p>
     * <ul>
     *   <li><b>环境 / 接口分两列上下</b>：上行=环境路径（环境下拉 + baseUrl），
     *       下行=接口路径（方法 chip + urlField + 发送/停止按钮），
     *       段间用 1px 浅灰水平线分隔，告别"一条长长横排"的拥挤感</li>
     *   <li><b>发送/停止按钮只保留图标</b>：去掉"发送"/"停止"文字，仅保留图标；
     *       tooltip 保留文字提示，符合 ui-ux-pro-max icon-only button 规则</li>
     *   <li><b>文本框美化</b>：baseUrl / urlField 都接入 {@link UiStyle#applyTextFieldStyle}
     *       —— 圆角描边 + focus 主色高亮 + 内边距留白</li>
     *   <li><b>环境/接口两段都自带「⛓ 环境」「接口」小标签</b>：一眼可辨，告别"两个文本框一前一后挤一起"</li>
     *   <li><b>等宽字体全程</b>：URL / baseUrl 全部 Font.MONOSPACED</li>
     *   <li><b>所有控件 baseline 28-30px</b>：触摸目标 ≥28px（满足 ui-ux-pro-max 触摸交互规则）</li>
     * </ul>
     */
    private JPanel createRequestTopPanel() {
        // ── 整块卡片（Y_AXIS 上下两段：环境行 / 接口行） ──
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(JBColor.namedColor("Panel.background", new Color(0xF7, 0xF8, 0xFA)));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(6, 8, 6, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));

        // ── 上段：环境路径（环境下拉 + baseUrl 文本框） ──
        JPanel envRow = createEnvRow();
        envRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(envRow);

        // ── 上下段之间：水平细分隔线 ──
        card.add(Box.createVerticalStrut(6));
        card.add(createHorizontalDivider());
        card.add(Box.createVerticalStrut(6));

        // ── 下段：接口路径（方法 chip + urlField + 发送/停止按钮） ──
        JPanel apiRow = createApiRow();
        apiRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(apiRow);

        return card;
    }

    /** 一伦优化 v10：构建「环境路径」行 —— ⛓ 环境 [下拉] [baseUrl]。 */
    private JPanel createEnvRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // ⛓ 链图标
//        JLabel envIcon = new JLabel(AllIcons.General.Web);
//        envIcon.setToolTipText(null);
//        envIcon.setVerticalAlignment(SwingConstants.CENTER);
//        envIcon.setAlignmentY(Component.CENTER_ALIGNMENT);
//        row.add(envIcon);
//        row.add(Box.createHorizontalStrut(6));

        // "环境"小标签
        JBLabel envLabel = new JBLabel("环境");
        envLabel.setFont(envLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_TINY));
        envLabel.setForeground(JBColor.GRAY);
        envLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(envLabel);
        row.add(Box.createHorizontalStrut(6));

        // 环境下拉
        envCombo = new JComboBox<>();
        envCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Environment) {
                    Environment env = (Environment) value;
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
        // 一伦优化 v26：envCombo 锁死 100×28，与 methodCombo 等宽等高，且 IntelliJ LAF 下不被拉长。
        // v25 单纯 setMaximumSize 在 IntelliJ JComboBox UI 下不生效——BasicComboBoxUI 内部按
        // editor+arrow 自行算 width 并忽略外部 max。这次叠加 4 重保险：
        //   1) pref==min==max 全等 100
        //   2) setPrototypeDisplayValue 锚定一个最宽文本，UI 内部按它算固定 width
        //   3) LEFT_ALIGNMENT 防 BoxLayout "中心摊"
        //   4) 在外面再套一个 Box.createHorizontalGlue(false) 前的固定槽位，靠 BoxLayout
        //      "不可压缩"特性再兜底一次
        envCombo.putClientProperty("JComboBox.isSquare", Boolean.TRUE);
        Dimension envComboSize = new Dimension(100, 28);
        envCombo.setPreferredSize(envComboSize);
        envCombo.setMinimumSize(envComboSize);
        envCombo.setMaximumSize(envComboSize);
        // 一伦优化 v26 修：envCombo 是 DefaultComboBoxModel<Environment>，setPrototypeDisplayValue
        // 收的是泛型元素 Environment。v26 错传 String 触发了"不兼容类型: String无法转换为Environment"。
        // 这里传一个 Environment 临时实例，长度按目标宽度算（"环境-生产-长文本"足够撑出 100+px 内部 width）。
        envCombo.setPrototypeDisplayValue(new Environment("环境-生产-长文本-占位", ""));
        envCombo.setFont(envCombo.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        envCombo.setToolTipText("切换环境配置");
        envCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        envCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        envCombo.addActionListener(e -> {
            if (suppressEnvComboAction) return;
            Environment selected = (Environment) envCombo.getSelectedItem();
            if (selected != null) {
                RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
                settings.setActiveEnvironment(selected.getName());
                settings.setBaseUrl(selected.getBaseUrl());
                // 一伦优化 v20：右侧 envCombo 切换环境时，同步把 env 列表的 active flag 持久化。
                // 否则左侧「环境管理」打开时只能靠运行时"双保险"回退，列表 JSON 里的 active 永远停留在旧值，
                // 跨进程恢复后会出现"勾选位置和实际激活项不一致"。
                List<Environment> envs = settings.loadEnvironments();
                for (Environment ee : envs) ee.setActive(ee.getName().equals(selected.getName()));
                settings.saveEnvironments(envs);
                applyEnvironmentToPanel(selected);
                statusLabel.setText("● 已切换到环境: " + selected.getName());
                // 一伦优化 v23：双向联动 —— 如果左侧"环境 & 数据"弹窗已开，立刻通知它重新拉数据并同步 UI
                notifyEnvDialogExternalChanged();
            }
        });
        row.add(envCombo);
        row.add(Box.createHorizontalStrut(6));

        // baseUrl 文本框（圆角描边美化 + focus 主色）
        baseUrlField.setText(RestAutoLabSettingsState.getInstance(project).getBaseUrl());
        baseUrlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_BODY));
        baseUrlField.setToolTipText("服务基础地址，如 http://localhost:8080");
        baseUrlField.setAlignmentY(Component.CENTER_ALIGNMENT);
        UiStyle.applyTextFieldStyle(baseUrlField);
        // 一伦优化 v13：baseUrl 加宽到 460，长 baseUrl 也能完整展示不被截断
        baseUrlField.setMinimumSize(new Dimension(220, 28));
        baseUrlField.setPreferredSize(new Dimension(460, 28));
        baseUrlField.setMaximumSize(new Dimension(460, 28));
        row.add(baseUrlField);

        return row;
    }

    /** 一伦优化 v10：构建「接口路径」行 —— 接口 [方法] [urlField] [发送] [停止]。 */
    private JPanel createApiRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // "接口"小标签
        JBLabel apiLabel = new JBLabel("接口");
        apiLabel.setFont(apiLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_TINY));
        apiLabel.setForeground(JBColor.GRAY);
        apiLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(apiLabel);
        row.add(Box.createHorizontalStrut(6));

        // 方法彩色 chip
        // 一伦优化 v26：methodCombo 与 envCombo 等宽 100×28，4 重保险锁死。
        Dimension methodComboSize = new Dimension(100, 28);
        methodCombo.putClientProperty("JComboBox.isSquare", Boolean.TRUE);
        methodCombo.setPreferredSize(methodComboSize);
        methodCombo.setMinimumSize(methodComboSize);
        methodCombo.setMaximumSize(methodComboSize);
        methodCombo.setPrototypeDisplayValue("DELETE");
        methodCombo.setFont(methodCombo.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        methodCombo.setToolTipText("切换 HTTP 方法");
        methodCombo.setRenderer(new HttpMethodCellRenderer());
        methodCombo.setAlignmentY(Component.CENTER_ALIGNMENT);
        methodCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
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
        row.add(methodCombo);
        row.add(Box.createHorizontalStrut(6));

        // urlField（圆角描边美化 + focus 主色 + 等宽字体）
        urlField.setEditable(false);
        urlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_BODY));
        urlField.setBackground(JBColor.namedColor("TextField.background", Color.WHITE));
        urlField.setAlignmentY(Component.CENTER_ALIGNMENT);
        UiStyle.applyTextFieldStyle(urlField);
        // 一伦优化 v13：urlField 与 baseUrl 统一 460，长 path 不被截断
        urlField.setMinimumSize(new Dimension(220, 28));
        urlField.setPreferredSize(new Dimension(460, 28));
        urlField.setMaximumSize(new Dimension(460, 28));
        row.add(urlField);
        row.add(Box.createHorizontalGlue());

        // v16 修复 1：顶部行不再放 sendButton，否则会出现两个发送按钮
        // —— 一伦优化 v34：发送入口与 tabs 同一行，靠最右边固定（TabStripSendButtonLayer + createTabStripSendButton）。
        return row;
    }

    /** 一伦优化 v7：构建一条 1×20 浅灰垂直分隔线（保留旧调用）。 */
    private JPanel createVerticalDivider() {
        JPanel div = new JPanel();
        div.setOpaque(true);
        div.setBackground(JBColor.border());
        div.setPreferredSize(new Dimension(1, 20));
        div.setMaximumSize(new Dimension(1, 20));
        div.setMinimumSize(new Dimension(1, 20));
        return div;
    }

    /** 一伦优化 v10：构建一条 280×1 浅灰水平分隔线，用于环境行 / 接口行之间分组。 */
    private JPanel createHorizontalDivider() {
        JPanel div = new JPanel();
        div.setOpaque(true);
        div.setBackground(JBColor.border());
        div.setPreferredSize(new Dimension(Integer.MAX_VALUE, 1));
        div.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        div.setMinimumSize(new Dimension(100, 1));
        div.setAlignmentX(Component.LEFT_ALIGNMENT);
        return div;
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
        activeEnvInfoLabel.setText("当前环境: " + env.getName() + "  ·  " + env.getBaseUrl());
        // 注入全局请求头：先移除旧的"全局请求头"标记区，再在表格顶部插入当前环境的全局头。
        // 这里采用简单策略：保留接口自身请求头，把全局头前置并标记，避免重复。
        Map<String, String> globalHeaders = env.getGlobalHeaders() == null
                ? Collections.emptyMap() : env.getGlobalHeaders();
        // 收集接口级请求头并剔除上一个环境注入的旧值，避免跨环境串数据。
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            Object key = headerTableModel.getValueAt(i, 0);
            if (key instanceof String name && appliedGlobalHeaderNames.contains(name)) continue;
            rows.add(new Object[]{key, headerTableModel.getValueAt(i, 1)});
        }
        headerTableModel.setRowCount(0);
        appliedGlobalHeaderNames.clear();
        appliedGlobalHeaderNames.addAll(globalHeaders.keySet());
        for (Map.Entry<String, String> e : globalHeaders.entrySet()) {
            headerTableModel.addRow(new Object[]{e.getKey(), e.getValue()});
        }
        for (Object[] row : rows) {
            Object key = row[0];
            if (key instanceof String name && globalHeaders.containsKey(name)) continue;
            headerTableModel.addRow(row);
        }
    }

    /** 上层接口级配置：明确展示生效环境，并提供安全前置脚本与变量覆盖。 */
    /**
     * 一伦优化 R4：暴露前置脚本&变量覆盖面板供 {@link EnvAndDataManageDialog} 嵌入为 Tab。
     * <p>原 {@link #createPreRequestPanel()} 保持 private，由本方法在外部调用时返回同一实例。
     * 面板内 DocumentListener 已是实时持久化，无需 onCommit 回调。</p>
     */
    public JPanel getPreRequestPanel() {
        return createPreRequestPanel();
    }

    private JPanel createPreRequestPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(UiStyle.cardBorder(4, 6));

        JPanel envRow = new JPanel(new BorderLayout());
        activeEnvInfoLabel.setFont(activeEnvInfoLabel.getFont().deriveFont(Font.BOLD, UiStyle.FONT_HINT));
        Environment active = RestAutoLabSettingsState.getInstance(project).getActiveEnvironmentObj();
        if (active != null) {
            activeEnvInfoLabel.setText("当前环境: " + active.getName() + "  ·  " + active.getBaseUrl());
        }
        envRow.add(activeEnvInfoLabel, BorderLayout.WEST);
        JBLabel scopeHint = new JBLabel("接口级前置配置（仅影响本次请求）");
        UiStyle.hint(scopeHint);
        envRow.add(scopeHint, BorderLayout.EAST);
        panel.add(envRow, BorderLayout.NORTH);

        JPanel scriptPanel = new JPanel(new BorderLayout(0, 3));
        scriptPanel.setBorder(JBUI.Borders.emptyRight(4));
        JBLabel scriptLabel = new JBLabel("前置脚本");
        scriptLabel.setToolTipText("安全 DSL：set/param/header name=value；支持 # 或 // 注释");
        scriptPanel.add(scriptLabel, BorderLayout.NORTH);
        preRequestScriptArea.getEmptyText().setText("示例：set token=abc  ·  header X-Trace={{traceId}}");
        preRequestScriptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_HINT));
        JBScrollPane scriptScroll = new JBScrollPane(preRequestScriptArea);
        scriptScroll.setPreferredSize(new Dimension(320, 78));
        scriptPanel.add(scriptScroll, BorderLayout.CENTER);

        JPanel variablesPanel = new JPanel(new BorderLayout(0, 3));
        variablesPanel.setBorder(JBUI.Borders.emptyLeft(4));
        variablesPanel.add(new JBLabel("变量覆盖"), BorderLayout.NORTH);
        UiStyle.styleTable(variableOverrideTable);
        variableOverrideTable.setRowHeight(24);
        variablesPanel.add(new JBScrollPane(variableOverrideTable), BorderLayout.CENTER);
        JPanel variableButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        variableButtons.add(UiStyle.button("添加", AllIcons.General.Add,
                e -> variableOverrideModel.addRow(new Object[]{"", ""})));
        variableButtons.add(UiStyle.button("删除", AllIcons.General.Remove, e -> {
            int row = variableOverrideTable.getSelectedRow();
            if (row >= 0) variableOverrideModel.removeRow(row);
        }));
        variablesPanel.add(variableButtons, BorderLayout.SOUTH);

        JBSplitter configSplitter = new JBSplitter(false, 0.55f);
        configSplitter.setFirstComponent(scriptPanel);
        configSplitter.setSecondComponent(variablesPanel);
        configSplitter.setHonorComponentsMinimumSize(false);
        configSplitter.setSplitterProportionKey("RestAutoLab.Debugger.PreRequestSplitter");
        panel.add(configSplitter, BorderLayout.CENTER);

        preRequestScriptArea.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                saveCurrentPreRequestConfig();
            }
        });
        variableOverrideModel.addTableModelListener(e -> saveCurrentPreRequestConfig());
        return panel;
    }

    private void saveCurrentPreRequestConfig() {
        if (loadingPreRequestConfig || currentApi == null) return;
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        settings.savePreRequestScript(currentApi.uniqueKey(), preRequestScriptArea.getText());
        settings.saveApiVariableOverrides(currentApi.uniqueKey(), collectVariableOverrides());
    }

    private void loadPreRequestConfig(ApiDefinition api) {
        loadingPreRequestConfig = true;
        try {
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            preRequestScriptArea.setText(settings.loadPreRequestScripts().getOrDefault(api.uniqueKey(), ""));
            variableOverrideModel.setRowCount(0);
            Map<String, String> values = settings.loadApiVariableOverrides().get(api.uniqueKey());
            if (values != null) {
                values.forEach((name, value) -> variableOverrideModel.addRow(new Object[]{name, value}));
            }
        } finally {
            loadingPreRequestConfig = false;
        }
    }

    private Map<String, String> collectVariableOverrides() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < variableOverrideModel.getRowCount(); i++) {
            Object name = variableOverrideModel.getValueAt(i, 0);
            Object value = variableOverrideModel.getValueAt(i, 1);
            if (name instanceof String key && !key.isBlank()) {
                values.put(key.trim(), value == null ? "" : String.valueOf(value));
            }
        }
        return values;
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

        // 一伦优化 v4：tab 顶部 [+/−] 行动行 + 附件面板（仅在有文件参数时显示）
        JPanel northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));

        // 一伦优化 v38：「清空值 / 全部」从底部状态栏上方行动行收编 —— 统一为 24×24 紧凑图标按钮，
        // 挂在 +/− 右侧，动作与表格在同一行、视线不用上下跳
        JButton clearValuesBtn = compactIconButton(AllIcons.Actions.GC, "清空所有参数的值",
                e -> clearParameterValues());
        JButton filterAllBtn = compactIconButton(AllIcons.Actions.ShowAsTree, "显示所有参数",
                e -> filterParamsByLocation(null));
        JPanel actionBar = createTabActionBar(
                "添加自定义参数",
                "删除选中的参数",
                e -> addCustomParameter(),
                e -> removeSelectedParameter(),
                clearValuesBtn, filterAllBtn);
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        northContainer.add(actionBar);

        // 附件面板（文件参数）—— 紧随行动行：仅当接口含文件参数时由 updateAttachmentPanel 填充
        attachmentPanel.setLayout(new BoxLayout(attachmentPanel, BoxLayout.Y_AXIS));
        attachmentPanel.setBorder(JBUI.Borders.empty(4, 4, 8, 4));
        attachmentPanel.setBackground(JBColor.namedColor("Panel.background", new Color(250, 250, 250)));
        attachmentPanel.setVisible(false);
        attachmentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        northContainer.add(attachmentPanel);

        panel.add(northContainer, BorderLayout.NORTH);

        // 一伦优化 v38：底部「清空值 / 全部」按钮已收编到顶部行动行，底部工具栏整体移除

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
        JLabel title = new JLabel("文件参数（" + fileParams.size() + " 个）— 必须选择本地文件，否则不会带上文件内容");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setIcon(AllIcons.Actions.Upload);
        title.setIconTextGap(6);
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
                    // v3.0：附件区使用 AllIcons.Actions.Upload 与按钮图标统一（不再用 emoji 📎）
                    pathLabel.setText(fileNameOf(path));
                    pathLabel.setIcon(AllIcons.Actions.Upload);
                    pathLabel.setIconTextGap(4);
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
                pathLabel.setIcon(null);
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
        // 一伦优化 #8：请求头区域右键菜单，含"清空 Cookie"（与原工具栏按钮行为一致）
        headerTable.setComponentPopupMenu(buildHeaderTablePopup());
        panel.add(new JBScrollPane(headerTable), BorderLayout.CENTER);

        // 一伦优化 v4：tab 顶部 [+/−/AI] 行动行
        JPanel actionBar = createTabActionBar(
                "添加请求头",
                "删除选中的请求头",
                e -> headerTableModel.addRow(new Object[]{"", ""}),
                e -> {
                    int row = headerTable.getSelectedRow();
                    if (row >= 0) headerTableModel.removeRow(row);
                });
        panel.add(actionBar, BorderLayout.NORTH);

        return panel;
    }

    /**
     * 一伦优化 #8：请求头表格右键菜单
     * <p>把"清空 Cookie"动作从主工具栏搬到请求头区域的右键菜单，
     * 顺带提供"添加行 / 删除选中行"两个常用动作，与表格内操作保持近距离。</p>
     */
    private JPopupMenu buildHeaderTablePopup() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem addItem = new JMenuItem("添加请求头", AllIcons.General.Add);
        addItem.addActionListener(e -> headerTableModel.addRow(new Object[]{"", ""}));
        menu.add(addItem);

        JMenuItem delItem = new JMenuItem("删除选中行", AllIcons.General.Remove);
        delItem.addActionListener(e -> {
            int row = headerTable.getSelectedRow();
            if (row >= 0) headerTableModel.removeRow(row);
        });
        menu.add(delItem);

        menu.addSeparator();

        // 清空 Cookie：与原工具栏"清Cookie"按钮行为完全一致
        JMenuItem clearCookieItem = new JMenuItem("清空 Cookie", AllIcons.Actions.GC);
        clearCookieItem.addActionListener(e -> {
            HttpExecutorService.getInstance(project).clearCookies();
            if (cookieStatusLabel != null) {
                cookieStatusLabel.setText("Cookie: 已清空");
            }
            statusLabel.setText("Cookie 已清空");
        });
        menu.add(clearCookieItem);

        return menu;
    }

    /**
     * 一伦优化 v6：body tab 整体灵动化。
     * <p>一伦优化 v29：按需求移除顶部行动行（+/− 与 紧凑/标准/展开 尺寸切换整行删除）——
     * 请求体是单一内容块，清空有底部「清空」按钮，默认请求体在切换接口时自动回填。</p>
     */
    private JPanel createBodyTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.empty(4));

        // ── 中部：body 格式 + cookie 状态 + 编辑器 ──
        JPanel center = new JPanel(new BorderLayout(0, 4));

        // 格式行
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JBLabel fmtLabel = new JBLabel("格式");
        fmtLabel.setFont(fmtLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        fmtLabel.setForeground(JBColor.GRAY);
        topBar.add(fmtLabel);
        // 一伦优化 v30：格式选项丰富化 —— 在 JSON / 表单 / Raw 基础上补充 XML / Text / HTML
        bodyFormatCombo = new JComboBox<>(new String[]{"JSON", "x-www-form-urlencoded", "Raw", "XML", "Text", "HTML"});
        bodyFormatCombo.setPreferredSize(new Dimension(180, 28));
        bodyFormatCombo.setFont(bodyFormatCombo.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        // 修复提单「json格式没有生效」：切换格式即刻生效——
        // JSON 时自动格式化请求体内容，同时把 Content-Type 头同步为所选格式对应的类型
        bodyFormatCombo.addActionListener(e -> applySelectedBodyFormat());
        topBar.add(bodyFormatCombo);

        topBar.add(Box.createHorizontalStrut(16));
        cookieStatusLabel = new JBLabel("Cookie: (无)");
        UiStyle.hint(cookieStatusLabel);
        topBar.add(cookieStatusLabel);

        center.add(topBar, BorderLayout.NORTH);

        // 编辑器卡片：浅底 + 圆角描边，与请求头卡片视觉一致
        bodyEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) UiStyle.FONT_MONO));
        // v2.0.0：长 JSON 不自动换行（水平滚动查看），与响应区观感一致
        bodyEditor.setLineWrap(false);
        bodyEditor.setWrapStyleWord(false);
        bodyEditor.setTabSize(2);
        bodyEditor.setRows(BODY_ROWS_STANDARD);
        // 编辑器四周 4px 留白，让视觉边界清晰
        bodyEditor.setMargin(new Insets(4, 6, 4, 6));

        bodyScrollPane = new JBScrollPane(bodyEditor);
        bodyScrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(2)));
        center.add(bodyScrollPane, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        // ── 底部：格式化 / 清空 按钮 ──
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
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
        // 一伦优化 #10：用 cardBorder(6, 8) 统一描边 + 留白（替代硬编码 compound）
        statusPanel.setBorder(UiStyle.cardBorder(6, 8));
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

        // === 底部操作按钮（v3.0：去掉 emoji 前缀、统一 iconTextGap=6、字色与字号一致）===
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        JButton viewToggleBtn = iconButton("树形视图", AllIcons.Actions.ShowAsTree, e -> toggleResponseView());
        viewToggleBtn.setToolTipText("在文本 / 树形视图间切换");

        JButton fmtBtn = iconButton("格式化", AllIcons.Actions.PrettyPrint, e -> formatResponseJson());
        fmtBtn.setToolTipText("格式化 JSON 响应（美化显示）");

        JButton copyBtn = iconButton("复制", AllIcons.Actions.Copy, e -> copyResponseToClipboard());
        copyBtn.setToolTipText("复制响应内容到剪贴板");

        JButton clearBtn = iconButton("清空", AllIcons.Actions.GC, e -> {
            responseArea.setText("");
            responsePane.setTextAndHighlight("");
            responseViewTree = false;
        });
        clearBtn.setToolTipText("清空响应内容");

        JBLabel hintLabel = new JBLabel("提示：切换『树形视图』可折叠展开 JSON 节点；状态/耗时按级别自动着色");
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_TINY));
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

    /**
     * 一伦优化 v26：AI 测试当前接口 —— AI 自动生成参数后再测试。
     * 目标源取决于左侧选中状态：
     *   - 选中单个 API → 跑这一个
     *   - 选中多个 API → 依次跑每个
     *   - 选中收藏夹 → 跑收藏夹内所有 API
     *   - 未选中 → 跑 currentApi
     * 本轮实现"currentApi + AI 生成 + 测试"单接口版本；批量多选逻辑下轮接入。
     * <p>已知问题：generateAiParameters 是异步（pooledThread 调 AI），回填 invokeLater
     * 比本方法挂的 Timer 晚完成 —— 第一版用 5s 兜底 Timer，超时未填则不阻塞测试。
     * 正确做法是把回调挂进 generateAiParameters 内部，留待下轮重构。</p>
     */
    private void runAiTestForCurrent() {
        if (currentApi == null) {
            statusLabel.setText("● 没有可测试的接口");
            return;
        }
        AiParameterService.TestScenario s =
                (AiParameterService.TestScenario) scenarioCombo.getSelectedItem();
        statusLabel.setText("● AI 生成参数中: " + currentApi.displayLabel());
        generateAiParameters(s);
        // 兜底：5s 后无论 AI 是否回填完成都发请求（参数未填时跑旧参数）
        javax.swing.Timer t = new javax.swing.Timer(5000, e -> {
            statusLabel.setText("● AI 测试: " + currentApi.displayLabel());
            sendRequest();
        });
        t.setRepeats(false);
        t.start();
    }

    private JPanel createAiTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(4));

        // ── 顶部：AI 配置 + 场景 + 操作按钮（一行紧凑） ──
        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
        topBar.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        // AI 配置状态（卡片样式，仅一行）
        // 一伦优化 v27：改回 BorderLayout —— 标题固定西侧，摘要占中部剩余宽度。
        // 面板收缩时摘要自然被裁剪（无需完整显示请求路径/模型名），不换行、不出滚动条。
        JPanel statusCard = new JPanel(new BorderLayout(6, 0));
        statusCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        statusCard.setBorder(UiStyle.cardBorder(4, 8));

        JBLabel configStatusLabel = new JBLabel("AI 配置");
        configStatusLabel.setFont(configStatusLabel.getFont().deriveFont(Font.BOLD, UiStyle.FONT_HINT));
        statusCard.add(configStatusLabel, BorderLayout.WEST);

        aiConfigInfoLabel = new JBLabel(getAiConfigSummary());
        UiStyle.hint(aiConfigInfoLabel);
        aiConfigInfoLabel.setHorizontalAlignment(SwingConstants.LEFT);
        aiConfigInfoLabel.setToolTipText(aiConfigInfoLabel.getText());
        statusCard.add(aiConfigInfoLabel, BorderLayout.CENTER);
        topBar.add(statusCard);
        topBar.add(Box.createVerticalStrut(4));

        // 操作按钮行：场景 + AI 助手(下拉) + 批量测试
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controlPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        JBLabel scenarioLabel = new JBLabel("场景");
        UiStyle.hint(scenarioLabel);
        controlPanel.add(scenarioLabel);

        scenarioCombo.setPreferredSize(new Dimension(110, 26));
        scenarioCombo.setFont(scenarioCombo.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        scenarioCombo.setToolTipText("选择本次 AI 生成场景：正常/边界/异常");
        controlPanel.add(scenarioCombo);

        // 一伦优化 v27：按需求去掉「AI 助手」下拉按钮，按钮行只保留 测试(左) + AI 测试(右)。
        // 测试：主操作高亮按钮（跟随 settings accent），使用当前参数测试当前接口。
        JButton runCurBtn = UiStyle.primaryButton("测试", AllIcons.Actions.Execute, e -> runCurrentTest(),
                UiStyle.parseAccent(RestAutoLabSettingsState.getInstance(project).getAccentColor()));
        runCurBtn.setToolTipText("使用当前参数测试当前接口");
        controlPanel.add(runCurBtn);

        // AI 测试：AI 自动生成参数并测试当前接口；用紫色调呼应 AI 语义，与「测试」形成主次区分。
        JButton aiTestBtn = UiStyle.primaryButton("AI 测试", AllIcons.Actions.Lightning, e -> runAiTestForCurrent(),
                UiStyle.AccentColor.PURPLE);
        aiTestBtn.setToolTipText("AI 自动生成参数并执行测试当前接口");
        controlPanel.add(aiTestBtn);

        // 清空结果
        JButton clearBtn = iconButton("清空", AllIcons.Actions.GC, e -> {
            testResultArea.setText("");
            statusLabel.setText("● 测试结果已清空");
        });
        clearBtn.setToolTipText("清空下方测试结果区域");
        controlPanel.add(clearBtn);

        topBar.add(controlPanel);
        panel.add(topBar, BorderLayout.NORTH);

        // ── 中部：测试结果 + 进度条（沿用原「测试」Tab 的 testResultArea / testProgressBar） ──
        JPanel center = new JPanel(new BorderLayout(0, 2));
        testProgressBar.setVisible(false);
        testProgressBar.setStringPainted(true);
        testProgressBar.setPreferredSize(new Dimension(-1, 18));
        center.add(testProgressBar, BorderLayout.NORTH);

        testResultArea.setFont(new Font("Monospaced", Font.PLAIN, (int) UiStyle.FONT_MONO));
        testResultArea.setEditable(false);
        testResultArea.setLineWrap(true);
        testResultArea.setWrapStyleWord(true);
        testResultArea.setText("点击「测试」使用当前参数执行请求，或「AI 测试」由 AI 自动生成参数并测试。\n测试结果将以 JSON 格式展示。\n\n等待操作...\n");
        center.add(new JBScrollPane(testResultArea), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        return panel;
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

    /**
     * 一伦优化 v4：tab 顶部「+/−」行动行 — 放在每个 tab 顶部 NORTH。
     * <p>目的：把"添加行 / 删除选中行"两个高频动作压到一行，
     * 不用滚到底部工具栏，也不用在每个 tab 单独维护一套按钮。</p>
     *
     * <ul>
     *   <li>「+」：调用 {@code addAction}（各 tab 自定义：参数表 addRow / 请求头 addRow / body 插入默认内容）</li>
     *   <li>「−」：调用 {@code removeAction}（各 tab 自定义：参数表 removeSelected / 请求头 removeSelected / body 清空）</li>
     * </ul>
     *
     * <p>一伦优化 v28：按需求移除行内「AI」按钮（AI 生成统一走底部「AI 测试」入口）；
     * +/− 统一为 24×24 紧凑方形图标按钮。</p>
     * <p>一伦优化 v29：+/− 右对齐并贴近表格 —— 与 IntelliJ 表格工具栏习惯一致，
     * 按钮视觉上"属于"下方表格，不再孤零零浮在左上角。</p>
     * <p>一伦优化 v38：支持追加尾部紧凑按钮（如「清空值 / 全部」），
     * 统一挂在 +/− 右侧、同一行右对齐，动作与表格视线不分离。</p>
     */
    private JPanel createTabActionBar(String addTooltip, String removeTooltip,
                                      java.awt.event.ActionListener addAction,
                                      java.awt.event.ActionListener removeAction,
                                      JButton... extraButtons) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.empty(0, 0, 2, 0));

        JButton addBtn = compactIconButton(AllIcons.General.Add, addTooltip, addAction);
        JButton delBtn = compactIconButton(AllIcons.General.Remove, removeTooltip, removeAction);

        // 一伦优化 v32：BoxLayout.X_AXIS 直排（无 insets、不折行），整组挂 EAST → 硬贴右缘固定
        JPanel btns = new JPanel();
        btns.setLayout(new BoxLayout(btns, BoxLayout.X_AXIS));
        btns.setOpaque(false);
        btns.add(addBtn);
        btns.add(Box.createHorizontalStrut(4));
        btns.add(delBtn);
        // 一伦优化 v38：尾部追加按钮（如「清空值 / 全部」），与 +/− 同一紧凑组
        for (JButton extra : extraButtons) {
            btns.add(Box.createHorizontalStrut(4));
            btns.add(extra);
        }
        bar.add(btns, BorderLayout.EAST);
        return bar;
    }

    /**
     * 一伦优化 v28：紧凑型纯图标方形按钮（24×24 固定尺寸），用于 tab 行动行的 +/− 等高频小动作。
     * 固定 preferred/minimum/maximum 三件套，任何 LaF 下都不被拉宽拉高。
     */
    private JButton compactIconButton(Icon icon, String tooltip, java.awt.event.ActionListener action) {
        JButton btn = iconButton(null, icon, e -> {
            if (action != null) action.actionPerformed(e);
        });
        btn.setToolTipText(tooltip);
        btn.setMargin(new Insets(0, 0, 0, 0));
        Dimension size = new Dimension(24, 24);
        btn.setPreferredSize(size);
        btn.setMinimumSize(size);
        btn.setMaximumSize(size);
        return btn;
    }

    private void setupActions() {
        // 一伦优化 v11：发送/停止合一 —— 根据 activeRequestFuture 是否为空判断行为。
        sendButton.addActionListener(e -> {
            if (sendSpinner.isRunning()) {
                stopRequest();
            } else {
                sendRequest();
            }
        });
        baseUrlField.addActionListener(e -> {
            // 一伦优化 v20+v22：右侧 baseUrlField 手动回车 = 改"当前激活环境"的 baseUrl。
            // 同步把修改写回 env 列表 JSON 并持久化，左侧「环境管理」再打开时看到的就是修改后的值。
            // v22 增量：active 标记以"以 env 列表里第一个 isActive=true 的项"为准，
            // 若没有 active 项则强制把 activeName 对应的 env 标激活 —— 防止 active 标记跑偏。
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            String newBaseUrl = baseUrlField.getText().trim();
            settings.setBaseUrl(newBaseUrl);
            List<Environment> envs = settings.loadEnvironments();
            String activeName = settings.getActiveEnvironment();
            Environment matched = null;
            for (Environment ee : envs) {
                if (ee.getName().equals(activeName)) {
                    ee.setBaseUrl(newBaseUrl);
                    matched = ee;
                }
            }
            // 强制以 activeName 为准重写 active 标记
            for (Environment ee : envs) ee.setActive(ee == matched);
            settings.saveEnvironments(envs);
            // 同步刷新面板上"当前环境"指示
            Environment active = settings.getActiveEnvironmentObj();
            if (active != null) {
                activeEnvInfoLabel.setText("当前环境: " + active.getName() + "  ·  " + active.getBaseUrl());
            }
            // 一伦优化 v23：双向联动 —— 通知左侧"环境 & 数据"弹窗重新拉数据并同步 UI
            notifyEnvDialogExternalChanged();
        });
    }

    /**
     * 一伦优化 v23：通知当前已打开的「环境 & 数据」弹窗外部数据变了，让它强制重新拉 + 同步 UI。
     * 弹窗没开时（currentEnvAndDataDialog == null 或 disposed）安全 no-op。
     */
    private void notifyEnvDialogExternalChanged() {
        if (currentEnvAndDataDialog == null) return;
        // 一伦优化 v23：用 Window.isShowing() 判断弹窗是否还活着（更通用，不依赖 Disposable.isDisposed）
        if (currentEnvAndDataDialog.getPeer() == null || !currentEnvAndDataDialog.getPeer().isShowing()) {
            currentEnvAndDataDialog = null;
            return;
        }
        try {
            EnvironmentManagerDialog envDialog = currentEnvAndDataDialog.getEnvDialog();
            if (envDialog != null) {
                envDialog.notifyExternalChanged();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 一伦优化 v23：把环境列表 / 当前 baseUrl 重新拉一次并刷到主面板（envCombo / baseUrlField / 指示 label）。
     * 由弹窗内 onChangeListener 触发。
     */
    private void applyExternalChangeToMainPanel() {
        if (envCombo == null) return;
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        String activeName = settings.getActiveEnvironment();
        // 重建 envCombo（保留选中项）
        refreshEnvCombo();
        // 把 envCombo 选中项对应的 baseUrl / 状态 / 全局头全量刷到主面板
        Environment active = settings.getActiveEnvironmentObj();
        if (active != null) {
            applyEnvironmentToPanel(active);
            statusLabel.setText("● 已切换到环境: " + active.getName());
        }
        // 主动强制 baseUrlField 显示最新值（即使 activeName 没变，baseUrl 可能被改过）
        baseUrlField.setText(settings.getBaseUrl());
    }

    /**
     * 一伦优化 v35/v37：「发起请求」与 tabs 同一行、钉死在整行最右端 —— 按钮是容器内覆盖层的一部分。
     * <p>点击 forward 到主 {@code sendButton}（doClick），业务逻辑只走 {@link #setupActions()} 一处；
     * 主按钮请求中会切 spinner，本按钮同步 icon，再点一次即取消，行为与顶部发送完全一致。</p>
     * <p>v36 曾只保留图标；v37 按用户要求恢复「发起请求」文字（保留 tooltip）。</p>
     * <p>布局：{@code [参数][请求头][请求体][历史][AI 助手] ...... [▶ 发起请求]} ——
     * 按钮绝对定位悬浮在 tab 条右缘（不占布局宽度、不居中），tab 条占满整行。</p>
     */
    private JButton createTabStripSendButton() {
        JButton btn = UiStyle.primaryButton("发起请求", AllIcons.Actions.Execute, e -> sendButton.doClick(),
                UiStyle.parseAccent(RestAutoLabSettingsState.getInstance(project).getAccentColor()));
        btn.setMargin(new Insets(4, 8, 4, 8));

        // 与主 sendButton 状态同步：请求中 icon 切 spinner / 请求完成恢复
        sendButton.addPropertyChangeListener("icon", evt -> btn.setIcon((Icon) evt.getNewValue()));
        sendButton.addPropertyChangeListener("enabled", evt -> btn.setEnabled((Boolean) evt.getNewValue()));
        return btn;
    }

    /**
     * 一伦优化 v35：让「发起请求」与 tabs 处于同一行、并钉死在整行最右端的容器层。
     * <p>结构：{@link JLayeredPane} —— 原 requestScroll 占满整行（DEFAULT_LAYER），
     * 按钮绝对定位悬浮在 tab 标题条右缘（PALETTE_LAYER），<b>不占布局宽度、不会被居中</b>；
     * 面板尺寸变化时自动重新定位。无论面板多宽，按钮右缘始终硬贴容器右缘。</p>
     * <p>历史包袱：v33/v34 用 BorderLayout.EAST 预留宽度放按钮，实际渲染中 EAST 区域
     * 被拉成一大块，按钮居中且 tab 条变窄 —— 覆盖层方案彻底规避布局挤占。</p>
     */
    static class TabStripSendButtonLayer extends JLayeredPane {
        private final JTabbedPane pane;
        private final JButton button;
        private final JScrollPane scroll;

        TabStripSendButtonLayer(Component center, JButton sendBtn) {
            setLayout(null);
            add(center, JLayeredPane.DEFAULT_LAYER);
            add(sendBtn, JLayeredPane.PALETTE_LAYER);

            this.button = sendBtn;
            this.scroll = center instanceof JScrollPane sp ? sp : null;
            this.pane = (scroll != null && scroll.getViewport() != null
                    && scroll.getViewport().getView() instanceof JTabbedPane tp) ? tp : null;

            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    reposition();
                }
            });
            // 一伦优化 #47：垂直滚动条出现/消失会收窄内容列可见宽度，
            // 按钮右缘必须跟随 viewport 可见右缘，才能与下方内容（AI 配置卡等）右对齐，
            // 不再硬贴容器右缘显得突出
            if (scroll != null && scroll.getViewport() != null) {
                scroll.getViewport().addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        reposition();
                    }
                });
            }
        }

        @Override
        public void doLayout() {
            reposition();
        }

        /** 尺寸语义跟随内容（scroll 容器）——覆盖层不参与布局挤占，但 splitter 初始比例仍合理。 */
        @Override
        public Dimension getPreferredSize() {
            for (Component c : getComponents()) {
                if (c != button) return c.getPreferredSize();
            }
            return super.getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return JBUI.size(1, 1);
        }

        /** 覆盖层定位：content 占满整行，按钮右缘与内容列可见右缘对齐（垂直居中对齐 tab 标题行）。 */
        private void reposition() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // tab 条在本层坐标系里的位置与高度（tabbedPane 是 requestScroll 的 view）
            int stripX = 0;
            int stripY = 0;
            int stripH = 28;
            if (pane != null) {
                Point p = SwingUtilities.convertPoint(pane, new Point(0, 0), this);
                stripX = p.x;
                stripY = p.y;
                if (pane.getTabCount() > 0) {
                    Rectangle r = pane.getBoundsAt(0);
                    if (r != null && r.height > 0) stripH = r.y + r.height;
                }
            }

            // 一伦优化 #47：右缘对齐内容列可见宽度（viewport），而不是容器硬边 ——
            // 垂直滚动条出现时内容列收窄，按钮同步内移，与下方 AI 配置卡右缘齐平；
            // 8px 内缩与内容卡右内边距（cardBorder 8）一致
            int rightEdge = w;
            if (scroll != null && scroll.getViewport() != null && scroll.getViewport().getWidth() > 0) {
                Point vp = SwingUtilities.convertPoint(scroll.getViewport(), new Point(0, 0), this);
                rightEdge = vp.x + scroll.getViewport().getWidth();
            }
            Dimension ps = button.getPreferredSize();
            int rightInset = 8;
            int x = Math.max(stripX, rightEdge - ps.width - rightInset);
            int y = stripY + Math.max(0, (stripH - ps.height) / 2);

            for (Component c : getComponents()) {
                if (c == button) {
                    c.setBounds(x, y, ps.width, ps.height);
                } else {
                    c.setBounds(0, 0, w, h);
                }
            }
            repaint();
        }
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
        stopRequestIfRunningForApiSwitch();
        currentApi = api;
        currentFolderId = folderId;
        loadPreRequestConfig(api);
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
        // 修复提单「参数解析有问题」：复杂对象（DTO）不再压成一行（值是一整坨 JSON 字符串），
        // 而是展开为点号路径行（request.appId 等），每个字段的类型/默认值/注释一目了然。
        for (ApiParameter param : api.bodyParameters()) {
            if (param.isFile()) {
                paramTableModel.addRow(new Object[]{
                        param.getName(),
                        param.getType(),
                        "FILE",
                        "请在右侧'文件参数'区选择本地文件",
                        param.isRequired() ? "是" : "否",
                        param.getDescription()
                });
            } else if (param.isComplexType()) {
                addFlattenedBodyRows(param.getName(), param, 0);
            } else {
                paramTableModel.addRow(new Object[]{
                        param.getName(),
                        param.getType(),
                        "BODY",
                        param.generateDefaultValue(),
                        param.isRequired() ? "是" : "否",
                        param.getDescription()
                });
            }
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

        rebuildHeadersForApi(api);

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

    private void rebuildHeadersForApi(ApiDefinition api) {
        headerTableModel.setRowCount(0);
        appliedGlobalHeaderNames.clear();
        Environment environment = getCurrentEnvironment();
        if (environment != null && environment.getGlobalHeaders() != null) {
            environment.getGlobalHeaders().forEach((name, value) -> {
                appliedGlobalHeaderNames.add(name);
                headerTableModel.addRow(new Object[]{name, value});
            });
        }
        addHeaderIfAbsent(RestAutoLabConstants.HEADER_CONTENT_TYPE, api.getConsumes());
        addHeaderIfAbsent(RestAutoLabConstants.HEADER_ACCEPT, api.getProduces());
        api.getHeaders().forEach(this::addHeaderIfAbsent);
    }

    private void addHeaderIfAbsent(String name, String value) {
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            if (Objects.equals(name, headerTableModel.getValueAt(i, 0))) return;
        }
        headerTableModel.addRow(new Object[]{name, value});
    }

    // ================================================================
    // 核心操作
    // ================================================================

    private void sendRequest() {
        if (currentApi == null) {
            Messages.showWarningDialog(project, "请先选择一个API接口", "提示");
            return;
        }

        Map<String, String> params = collectParameterValues();
        Map<String, String> headers = collectHeaderValues();
        String body = bodyEditor.getText();
        String requestBody = (body != null && !body.isBlank()) ? body : null;

        // v3: 获取body格式和环境
        final String finalBodyFormat = resolveSelectedBodyFormat();
        final PreRequestProcessor.Result preRequest;
        try {
            preRequest = PreRequestProcessor.apply(preRequestScriptArea.getText(), collectVariableOverrides(),
                    params, headers, getCurrentEnvironment());
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "前置脚本错误");
            statusLabel.setText("● 前置脚本校验失败");
            return;
        }
        params = preRequest.getParams();
        headers = preRequest.getHeaders();
        final Environment env = preRequest.getEnvironment();
        final ApiDefinition requestApi = currentApi;
        final String requestBaseUrl = baseUrlField.getText().trim();
        final List<ResponseAssertion> requestAssertions = new ArrayList<>(currentAssertions);
        final long requestId = requestSequence.incrementAndGet();

        // 一伦优化 v11：发送按钮切到自旋 spinner 态 —— 再点一次即取消
        // v15 修复：setIcon 之前显式重置背景色 = attachInteractionFeedback 注册时保存的 base 色。
        // 原因：若用户刚 hover 过 sendButton 还没移出，setIcon 触发的重绘可能把
        // "hover 深色"残影带进 spinner 态，看起来"点过后还发亮"。
        // setContentAreaFilled(true) 保证 setBackground 在 LaF borderless 下也生效。
        Object savedSendBase = sendButton.getClientProperty(UiStyle.INTERACTION_BASE_BG);
        Color sendBase = savedSendBase instanceof Color ? (Color) savedSendBase : sendButton.getBackground();
        sendButton.setContentAreaFilled(true);
        sendButton.setBackground(sendBase);
        sendButton.setIcon(sendSpinner);
        sendButton.setToolTipText("请求中…点击取消");
        sendSpinner.start();
        statusLabel.setText("○ 请求发送中...");

        // 详细日志：记录要发送的请求信息
        LOG.info("[执行请求] 开始 => API=" + requestApi.getHttpMethod() + " " + requestApi.getUrl()
                + ", baseUrl=" + requestBaseUrl
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

        final Map<String, String> requestParams = params;
        final Map<String, String> requestHeaders = headers;
        activeRequestFuture = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            TestResult requestResult;
            try {
                HttpExecutorService http = HttpExecutorService.getInstance(project);
                requestResult = http.executeRequest(requestApi, requestBaseUrl,
                        requestParams, requestHeaders, requestBody, finalBodyFormat, env, requestAssertions);
            } catch (Exception ex) {
                LOG.warn("[执行请求] 请求异常", ex);
                String message = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage();
                requestResult = TestResult.fromError(requestApi, message);
            }
            TestResult result = requestResult;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (requestId != requestSequence.get()) return;
                activeRequestFuture = null;
                displayResponse(result);
                // 一伦优化 v11：恢复发送按钮为 Execute 图标
                // v15 修复：setIcon 之前显式重置背景色，避免 spinner 切回 Execute
                // 时把"hover/pressed 残影"带回来，看起来"点过后还发亮"。
                resetSendButtonToIdle();
                statusLabel.setText("● " + result.summary());
            });
        });
    }

    /**
     * v15 修复：把发送按钮恢复成空闲态（Execute 图标 + 原始 tooltip + 原始背景色），
     * 集中处理 spinner → Execute 的所有视觉重置，避免每个调用点（stopRequest、
     * 请求完成回调）单独遗漏。
     * <p>调用前若 sendButton 处于 hover/pressed 态，{@code setBackground(sendBase)} 会
     * 把背景刷回 accent base，再由 {@code mouseEntered/mouseExited} 接管 hover 反馈。</p>
     * <p>注意：base 色从 {@link UiStyle#INTERACTION_BASE_BG} 取，不能用
     * {@code sendButton.getBackground()}（已经可能是 hover/pressed 残影）。</p>
     */
    private void resetSendButtonToIdle() {
        sendSpinner.stop();
        // 从 attachInteractionFeedback 注册时保存的 base 色取，避免拿到 hover/pressed 残影
        Object savedBase = sendButton.getClientProperty(UiStyle.INTERACTION_BASE_BG);
        Color base = savedBase instanceof Color ? (Color) savedBase : sendButton.getBackground();
        // 强制 setContentAreaFilled(true) 确保 setBackground 在任何 LaF 下都生效
        sendButton.setContentAreaFilled(true);
        sendButton.setBackground(base);
        sendButton.setIcon(AllIcons.Actions.Execute);
        sendButton.setToolTipText("发送请求到当前接口 (Ctrl+Enter)");
        sendButton.repaint();
    }
    private void stopRequest() {
        Future<?> task = activeRequestFuture;
        if (task == null || task.isDone()) {
            resetSendButtonToIdle();
            return;
        }
        requestSequence.incrementAndGet();
        activeRequestFuture = null;
        task.cancel(true);
        resetSendButtonToIdle();
        statusLabel.setText("● 请求已停止");
    }

    private void stopRequestIfRunningForApiSwitch() {
        Future<?> task = activeRequestFuture;
        if (task != null && !task.isDone()) stopRequest();
    }

    private void displayResponse(TestResult result) {
        int code = result.getStatusCode();
        long ms = result.getDurationMs();
        int size = result.getResponseBody() == null ? 0 : result.getResponseBody().length();

        // v3.0：状态徽章用 UiStyle 语义色 + 圆角徽章（统一设计 token，去掉 raw hex）
        JBColor sc = statusColor(code);
        String scHex = String.format("#%02X%02X%02X", sc.getRGB() & 0xFF, (sc.getRGB() >> 8) & 0xFF, (sc.getRGB() >> 16) & 0xFF);
        responseStatusLabel.setText("<html><span style='background-color:" + scHex
                + ";color:white;padding:2px 8px;border-radius:4px;font-weight:bold;'>"
                + statusGlyph(code) + " " + code + " " + httpStatusText(code)
                + "</span></html>");

        // 耗时：色码 + 数值 + 分级标签（v3.0：标签与数值用同一基色，色盲友好）
        JBColor tc = timeColor(ms);
        String tcHex = String.format("#%02X%02X%02X", tc.getRGB() & 0xFF, (tc.getRGB() >> 8) & 0xFF, (tc.getRGB() >> 16) & 0xFF);
        String speedTag = ms < 200 ? "（快）" : ms < 800 ? "（正常）" : ms < 2000 ? "（慢）" : "（极慢）";
        responseTimeLabel.setText("<html><span style='color:gray'>耗时</span> <b style='color:" + tcHex + "'>"
                + ms + " ms</b> <span style='color:" + tcHex + "'>" + speedTag + "</span></html>");

        // 大小：字段弱化 + 值（v3.0：去掉括号内冗余显示，与耗时标签对齐）
        responseSizeLabel.setText("<html><span style='color:gray'>大小</span> <b>"
                + formatBytes(size) + "</b></html>");

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

    /**
     * 把复杂 BODY 参数（DTO）递归展开为点号路径行写入参数表。
     * <p>例：request(TenantAppInitDTO) → request.appId / request.appName …
     * 嵌套对象继续下钻（最多 4 层，防循环引用爆表）。</p>
     */
    private void addFlattenedBodyRows(String prefix, ApiParameter param, int depth) {
        if (depth > 4 || param.getChildren().isEmpty()) {
            paramTableModel.addRow(new Object[]{
                    prefix, param.getType(), "BODY",
                    param.generateDefaultValue(),
                    param.isRequired() ? "是" : "否",
                    param.getDescription()
            });
            return;
        }
        // 父行：只读展示，值留空，提示为对象
        paramTableModel.addRow(new Object[]{
                prefix, param.getType(), "BODY", "",
                param.isRequired() ? "是" : "否",
                param.getDescription().isBlank() ? "对象，字段见下方 " + prefix + ".* 行"
                        : param.getDescription()
        });
        for (ApiParameter child : param.getChildren()) {
            addFlattenedBodyRows(prefix + "." + child.getName(), child, depth + 1);
        }
    }

    private String generateDefaultBody(ApiDefinition api) {
        List<ApiParameter> bodyParams = api.bodyParameters();
        if (bodyParams.isEmpty()) return "{}";
        if (bodyParams.size() == 1 && bodyParams.get(0).isComplexType()) {
            return prettyPrintDefaultBody(bodyParams.get(0).generateDefaultValue());
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (ApiParameter p : bodyParams) map.put(p.getName(), p.generateDefaultValue());
        // 一伦优化 v30：默认请求体直接输出「已格式化」JSON，用户无需再手动点格式化。
        // map 的 value 可能是嵌套 JSON 字符串（复杂类型），复用 mapToNestedJson 还原真实结构，
        // 避免 gson.toJson(map) 产生双重转义；gson 已配置 setPrettyPrinting，输出即格式化。
        return gson.toJson(mapToNestedJson(map));
    }

    /**
     * 一伦优化 v30：把默认请求体转为格式化（pretty-print）JSON。
     * <p>切换接口 / 切换方法自动回填请求体时调用，保证打开即是可读的格式化内容；
     * 若内容不是合法 JSON（如纯文本占位），原样返回，不破坏用户可见内容。</p>
     */
    private String prettyPrintDefaultBody(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return raw;
        try {
            var elem = JsonParser.parseString(trimmed);
            return gson.toJson(elem);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 修复提单「json格式没有生效」：格式下拉切换后立即生效，两步：
     * <p>1. JSON 格式时自动格式化请求体内容（内容非法 JSON 则保留原文本，仅在状态栏提示）；
     * 2. 把请求头里的 Content-Type 同步为所选格式对应的类型，保证发送时格式真正生效。</p>
     */
    private void applySelectedBodyFormat() {
        String format = resolveSelectedBodyFormat();
        // 1. JSON 时自动格式化内容
        if (HttpExecutorService.BODY_FORMAT_JSON.equals(format)) {
            String text = bodyEditor.getText();
            if (text != null && !text.isBlank()) {
                try {
                    var elem = JsonParser.parseString(text);
                    bodyEditor.setText(gson.toJson(elem));
                } catch (Exception ex) {
                    statusLabel.setText("● 内容不是合法 JSON，未格式化");
                }
            }
        }
        // 2. 同步 Content-Type 头（有则替换值，无则追加）
        String contentType = currentApi != null
                ? selectedBodyFormatContentType(currentApi)
                : RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
        setHeaderValue(RestAutoLabConstants.HEADER_CONTENT_TYPE, contentType);
        statusLabel.setText("● 请求体格式已切换: " + bodyFormatCombo.getSelectedItem());
    }

    /** 设置/覆盖指定名称的请求头值（大小写不敏感匹配已有行） */
    private void setHeaderValue(String name, String value) {
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            Object key = headerTableModel.getValueAt(i, 0);
            if (name.equalsIgnoreCase(key == null ? "" : key.toString())) {
                headerTableModel.setValueAt(value, i, 1);
                return;
            }
        }
        headerTableModel.addRow(new Object[]{name, value});
    }

    /**
     * 一伦优化 v30：把格式下拉的选中项映射为 {@link HttpExecutorService} 的 body 格式常量。
     * 供发送请求与导出 cURL 共用，避免两处分支不一致。
     */
    private String resolveSelectedBodyFormat() {
        if (bodyFormatCombo == null) return HttpExecutorService.BODY_FORMAT_JSON;
        String fmt = (String) bodyFormatCombo.getSelectedItem();
        if ("x-www-form-urlencoded".equals(fmt)) return HttpExecutorService.BODY_FORMAT_FORM;
        if ("Raw".equals(fmt)) return HttpExecutorService.BODY_FORMAT_RAW;
        if ("XML".equals(fmt)) return HttpExecutorService.BODY_FORMAT_XML;
        if ("Text".equals(fmt)) return HttpExecutorService.BODY_FORMAT_TEXT;
        if ("HTML".equals(fmt)) return HttpExecutorService.BODY_FORMAT_HTML;
        return HttpExecutorService.BODY_FORMAT_JSON;
    }

    /**
     * 一伦优化 v30：根据当前选中的 body 格式返回对应 Content-Type（导出 cURL 用）。
     */
    private String selectedBodyFormatContentType(ApiDefinition api) {
        String format = resolveSelectedBodyFormat();
        return switch (format) {
            case HttpExecutorService.BODY_FORMAT_FORM -> RestAutoLabConstants.CONTENT_TYPE_FORM_URLENCODED;
            case HttpExecutorService.BODY_FORMAT_XML -> RestAutoLabConstants.CONTENT_TYPE_XML;
            case HttpExecutorService.BODY_FORMAT_TEXT -> RestAutoLabConstants.CONTENT_TYPE_TEXT;
            case HttpExecutorService.BODY_FORMAT_HTML -> RestAutoLabConstants.CONTENT_TYPE_HTML;
            default -> api.getConsumes();
        };
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

        // 一伦优化 v30：跟随格式下拉（JSON/表单/Raw/XML/Text/HTML），与「发送」保持一致
        final String testBodyFormat = resolveSelectedBodyFormat();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService http = HttpExecutorService.getInstance(project);
            TestResult r = http.executeRequest(currentApi, baseUrlField.getText().trim(),
                    collectParameterValues(), collectHeaderValues(),
                    bodyEditor.getText(), testBodyFormat,
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
     * <p>一伦优化 R5：行为保持不变，UI 构造委托给 {@link #createAiConfigPanel(Runnable)}。
     * 保存由对话框的 OK 按钮统一触发（与"环境 & 数据"弹窗口径一致），不再有独立"保存"按钮。</p>
     */
    public void showAiConfigDialog() {
        // OK 按钮触发的保存：先 commit（内部已做校验），成功才弹提示 + dispose
        AiConfigPanel panel = createAiConfigPanel(() -> {
            // 此处不能弹"成功"提示，因为 commit 失败也会调用 onAfterSaved；
            // 成功提示由 okBtn 的 ActionListener 根据 commit() 返回值弹
        });
        JDialog dialog = new JDialog((Frame) null, "AI 配置", true);
        dialog.setResizable(true);
        dialog.setMinimumSize(UiStyle.minSize(560, 400));
        dialog.setContentPane(panel);
        // 绑定底部 OK / Cancel：JDialog 默认无按钮，需要手动加
        JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton okBtn = new JButton("确定");
        JButton cancelBtn = new JButton("取消");
        okBtn.setPreferredSize(new Dimension(80, 28));
        cancelBtn.setPreferredSize(new Dimension(80, 28));
        southPanel.add(okBtn);
        southPanel.add(cancelBtn);
        dialog.add(southPanel, BorderLayout.SOUTH);
        okBtn.addActionListener(e -> {
            if (panel.commit()) {
                Messages.showInfoMessage(project,
                        "AI配置已成功保存！\n\n服务器: " + RestAutoLabSettingsState.getInstance(project).getAiServerUrl()
                                + "\nAPI 路径: " + RestAutoLabSettingsState.getInstance(project).getAiApiPath()
                                + "\n模型: " + RestAutoLabSettingsState.getInstance(project).getAiModel(),
                        "保存成功");
                dialog.dispose();
            }
        });
        cancelBtn.addActionListener(e -> dialog.dispose());
        // Enter 触发 OK，Esc 触发取消
        dialog.getRootPane().setDefaultButton(okBtn);
        KeyStroke esc = KeyStroke.getKeyStroke("ESCAPE");
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dialog.dispose(); }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 一伦优化 R5：构造 AI 配置面板（不含外层 JDialog，无"保存"按钮）。
     * <p>供 {@link EnvAndDataManageDialog} 作为 Tab 嵌入使用，也供
     * {@link #showAiConfigDialog()} 作为独立 JDialog 内容。
     * <p><b>保存</b>：由调用方在 OK 按钮里调用返回对象的 {@link AiConfigPanel#commit()}
     * 来统一写入 settings，本面板不再有独立"保存"按钮。</p>
     *
     * @param onAfterSaved 保存成功后的回调（独立对话框用于弹"保存成功"提示并 dispose；
     *                     嵌入 Tab 可传 null）。注意：无论 commit 成功失败，onAfterSaved
     *                     都会被调用，调用方按 commit() 返回值判断是否 dispose。
     */
    public AiConfigPanel createAiConfigPanel(Runnable onAfterSaved) {
        AiConfigPanel panel = new AiConfigPanel(onAfterSaved);
        return panel;
    }

    /**
     * 一伦优化 R5：AI 配置面板 —— 自带 commit()，由 OK 按钮调用。
     * <p>独立 JDialog（{@link #showAiConfigDialog()}）和"环境&数据"合并弹窗
     *（{@link #openEnvAndDataManageDialog()}）都通过本类的 commit() 完成保存。</p>
     */
    public class AiConfigPanel extends JPanel {
        private final Runnable onAfterSaved;
        private final JBTextField urlField;
        private final JBPasswordField keyField;
        private final JBTextField apiPathField;
        private final JComboBox<String> modelField;
        private final JCheckBox localModelCheck;
        private final JBTextArea systemPromptArea;
        private final JBTextArea userPromptArea;

        public AiConfigPanel(Runnable onAfterSaved) {
            super(new BorderLayout(0, 8));
            setBorder(JBUI.Borders.empty(8));
            this.onAfterSaved = onAfterSaved;

            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            urlField = new JBTextField(settings.getAiServerUrl(), 35);
            urlField.setToolTipText("例如: https://ark.cn-beijing.volces.com/api/v3 或 http://172.29.64.24:80");
            keyField = new JBPasswordField();
            keyField.setText(settings.getAiToken());
            keyField.setToolTipText("Bearer Token 值（无需带 'Bearer ' 前缀，插件会自动加）；留空表示使用本地模型");

            JButton toggleKeyBtn = new JButton(AllIcons.Actions.Preview);
            toggleKeyBtn.setToolTipText("显示/隐藏 API Key 内容");
            toggleKeyBtn.putClientProperty("JButton.buttonType", "square");
            toggleKeyBtn.setMargin(new Insets(0, 2, 0, 2));
            toggleKeyBtn.setPreferredSize(new Dimension(28, keyField.getPreferredSize().height));
            final boolean[] keyVisible = {false};
            toggleKeyBtn.addActionListener(e -> {
                keyVisible[0] = !keyVisible[0];
                if (keyVisible[0]) {
                    keyField.setEchoChar((char) 0);
                    toggleKeyBtn.setIcon(AllIcons.Actions.Cancel);
                    toggleKeyBtn.setToolTipText("点击隐藏 API Key");
                } else {
                    keyField.setEchoChar('\u2022');
                    toggleKeyBtn.setIcon(AllIcons.Actions.Preview);
                    toggleKeyBtn.setToolTipText("点击显示 API Key");
                }
            });
            JPanel keyFieldPanel = new JPanel(new BorderLayout(2, 0));
            keyFieldPanel.add(keyField, BorderLayout.CENTER);
            keyFieldPanel.add(toggleKeyBtn, BorderLayout.EAST);

            apiPathField = new JBTextField(settings.getAiApiPath(), 20);
            apiPathField.setToolTipText("<html>OpenAI 标准用 /chat/completions；Qwen/vLLM 等私有部署可能用 /chat。<br>留空则请求直接打到服务器URL根路径。</html>");

            modelField = new JComboBox<>(RestAutoLabConstants.AI_MODEL_OPTIONS);
            modelField.setEditable(true);
            modelField.setSelectedItem(settings.getAiModel());
            modelField.setToolTipText("选择或输入模型名称，如 Qwen3.5-35B-A3B");

            localModelCheck = new JCheckBox("本地模型（API Key 自动填为 Bearer 占位）");
            localModelCheck.setSelected(RestAutoLabConstants.isLocalModelToken(settings.getAiToken()));
            localModelCheck.setToolTipText("<html>勾选后 API Key 字段自动填入字面量 <b>Bearer</b> 并禁用编辑。<br>"
                    + "调用时发送 <code>Authorization: Bearer Bearer</code>，满足 vLLM/Qwen 等网关要求。<br>"
                    + "云端模型请取消勾选，并填入真实的 API Key 值。</html>");
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

            systemPromptArea = new JBTextArea(settings.getAiSystemPrompt());
            systemPromptArea.setLineWrap(true);
            systemPromptArea.setWrapStyleWord(true);
            systemPromptArea.setFont(systemPromptArea.getFont().deriveFont(Font.PLAIN, 11f));
            JScrollPane systemPromptScroll = new JScrollPane(systemPromptArea);
            systemPromptScroll.setPreferredSize(new Dimension(460, 100));

            userPromptArea = new JBTextArea(settings.getAiUserPromptTemplate());
            userPromptArea.setLineWrap(true);
            userPromptArea.setWrapStyleWord(true);
            userPromptArea.setFont(userPromptArea.getFont().deriveFont(Font.PLAIN, 11f));
            JScrollPane userPromptScroll = new JScrollPane(userPromptArea);
            userPromptScroll.setPreferredSize(new Dimension(460, 160));

            JButton resetPromptBtn = UiStyle.button("恢复默认提示词", AllIcons.Actions.Refresh, e -> {
                systemPromptArea.setText(RestAutoLabConstants.AI_SYSTEM_PROMPT);
                userPromptArea.setText(RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
            });
            resetPromptBtn.setToolTipText("将系统/用户提示词还原为内置默认值");

            JPanel form = new ScrollableGridBagPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;

            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
            JBLabel urlLabel = new JBLabel("服务器 URL");
            urlLabel.setFont(urlLabel.getFont().deriveFont(Font.BOLD, 11f));
            form.add(urlLabel, gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add(urlField, gbc);

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
            JBLabel keyLabel = new JBLabel("API Key (Bearer)");
            keyLabel.setFont(keyLabel.getFont().deriveFont(Font.BOLD, 11f));
            form.add(keyLabel, gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add(keyFieldPanel, gbc);

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
            JBLabel apiPathLabel = new JBLabel("API 路径");
            apiPathLabel.setFont(apiPathLabel.getFont().deriveFont(Font.BOLD, 11f));
            form.add(apiPathLabel, gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add(apiPathField, gbc);

            gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
            JBLabel modelLabel = new JBLabel("模型");
            modelLabel.setFont(modelLabel.getFont().deriveFont(Font.BOLD, 11f));
            form.add(modelLabel, gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add(modelField, gbc);

            gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
            form.add(localModelCheck, gbc);

            JBLabel hintLabel = new JBLabel("<html><i>"
                    + "云端模型：取消勾选'本地模型'，API Key 填真实 token（如 4702f55c...），无需带 'Bearer ' 前缀；<br>"
                    + "本地部署（vLLM/Qwen/Ollama）：勾选'本地模型'，API Key 自动填为 <b>Bearer</b> 占位。"
                    + "</i></html>");
            hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 10f));
            hintLabel.setForeground(JBColor.GRAY);
            gbc.gridy = 5;
            form.add(hintLabel, gbc);

            gbc.gridy = 6; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
            JSeparator sep1 = new JSeparator();
            form.add(sep1, gbc);

            gbc.gridy = 7; gbc.fill = GridBagConstraints.NONE;
            JBLabel promptHeader = new JBLabel("AI 提示词自定义");
            promptHeader.setFont(promptHeader.getFont().deriveFont(Font.BOLD, 12f));
            promptHeader.setForeground(JBColor.BLUE);
            form.add(promptHeader, gbc);

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

            JPanel systemPromptContent = new JPanel(new BorderLayout());
            systemPromptContent.add(systemPromptScroll, BorderLayout.CENTER);
            systemPromptContent.setVisible(false);
            gbc.gridy = 9; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.35;
            form.add(systemPromptContent, gbc);

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

            JPanel userPromptContent = new JPanel(new BorderLayout());
            userPromptContent.add(userPromptScroll, BorderLayout.CENTER);
            userPromptContent.setVisible(false);
            gbc.gridy = 11; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.55;
            form.add(userPromptContent, gbc);

            gbc.gridy = 12; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0;
            JBLabel placeholderHint = new JBLabel("<html><font color='#888888' size='2'>占位符: ${API_URL} ${HTTP_METHOD} ${API_NAME} ${CONTROLLER_NAME} ${DESCRIPTION} ${CONTENT_TYPE} ${PARAMETERS} ${SCENARIO_NAME} ${SCENARIO_DESC} ${FULL_HINT}</font></html>");
            form.add(placeholderHint, gbc);

            JScrollPane formScroll = new JScrollPane(form);
            formScroll.setBorder(null);
            formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            // 底部行：左侧「恢复默认提示词」（仅恢复提示词，不写入 settings），右侧留空。
            // 一伦优化 R5：AI 配置面板去掉「保存」按钮，由对话框的 OK 按钮统一触发 commit()。
            JPanel btnPanel = new JPanel();
            btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
            btnPanel.setBorder(JBUI.Borders.empty(4, 4, 4, 4));
            JPanel leftBtnPanel = new JPanel();
            leftBtnPanel.setLayout(new BoxLayout(leftBtnPanel, BoxLayout.X_AXIS));
            leftBtnPanel.setOpaque(false);
            leftBtnPanel.add(resetPromptBtn);
            btnPanel.add(leftBtnPanel);
            btnPanel.add(Box.createHorizontalGlue());
            UiStyle.uniformHeight(28, resetPromptBtn);

            add(formScroll, BorderLayout.CENTER);
            add(btnPanel, BorderLayout.SOUTH);

            // 折叠/展开 互斥
            Runnable reflow = () -> {
                revalidate();
                repaint();
            };
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
                reflow.run();
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
                reflow.run();
            });
        }

        /**
         * 校验 + 写入 settings + 触发 onAfterSaved 回调。
         * <p>调用方在 OK 按钮里调用；返回 false 表示校验失败（已弹错），不 dispose；
         * 返回 true 表示保存成功，可 dispose 或继续后续动作。</p>
         */
        public boolean commit() {
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            String serverUrl = urlField.getText().trim();
            String apiKey = new String(keyField.getPassword()).trim();
            String apiPath = apiPathField.getText().trim();
            String model = String.valueOf(modelField.getSelectedItem()).trim();

            if (serverUrl.isBlank()) {
                Messages.showWarningDialog(this, "服务器URL不能为空", "配置错误");
                urlField.requestFocusInWindow();
                if (onAfterSaved != null) {
                    try { onAfterSaved.run(); } catch (Exception ignore) {}
                }
                return false;
            }
            if (!apiPath.isBlank() && !apiPath.startsWith("/")) {
                apiPath = "/" + apiPath;
            }
            settings.setAiServerUrl(serverUrl);
            settings.setAiToken(localModelCheck.isSelected()
                    ? RestAutoLabConstants.AI_LOCAL_BEARER_TOKEN : apiKey);
            settings.setAiApiPath(apiPath);
            settings.setAiModel(model);
            settings.setAiSystemPrompt(systemPromptArea.getText());
            settings.setAiUserPromptTemplate(userPromptArea.getText());

            // 刷新主面板 AI 摘要
            if (aiConfigInfoLabel != null) {
                aiConfigInfoLabel.setText(getAiConfigSummary());
                aiConfigInfoLabel.setToolTipText(aiConfigInfoLabel.getText());
            }
            if (statusLabel != null) {
                statusLabel.setText("● AI配置已更新");
            }
            if (onAfterSaved != null) {
                try { onAfterSaved.run(); } catch (Exception ignore) {}
            }
            return true;
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
     * v2.0.0 值列渲染器（v3.0：去掉 emoji 📎，统一用 SVG 图标）。
     * <ul>
     *   <li>文件类型参数（File/MultipartFile 或 FILE 位置）：左侧加 AllIcons.Actions.Upload 小图标 + 文件名，
     *       完整路径放 tooltip；未选择时灰色显示「（未选择）」。</li>
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
            setIconTextGap(4);

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
                    setText(fileNameOf(raw));
                    setIcon(AllIcons.Actions.Upload);
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

    /** JSON树节点渲染器 - 按值类型上色 + 类型徽章（v3.0：图标统一用 AllIcons.Json.*，与设计系统规范一致） */
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
                setText(text);
            } else if (text.matches("\\[\\d+\\].*")) {
                // 数组元素节点："[0]" 或 "[0]: value"
                setIcon(AllIcons.Json.Array);
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
                setText("<b style='color:#1f6feb'>" + escapeHtml(text) + "</b>");
            } else if (text.contains("(") && text.endsWith(")")) {
                // 对象/数组容器节点："key (3 items)"
                int paren = text.indexOf("(");
                String name = text.substring(0, paren).trim();
                String count = text.substring(paren);
                setIcon(text.contains("Array") || name.startsWith("[") ? AllIcons.Json.Array : AllIcons.Json.Object);
                setText("<html><b style='color:#1f6feb'>" + escapeHtml(name) + "</b>"
                        + " <span style='color:#888;font-size:10px'>" + escapeHtml(count) + "</span></html>");
            } else {
                // 普通对象 key 容器（无值的对象/数组），如 "data"、"user" 等
                setIcon(AllIcons.Json.Object);
                setText("<b style='color:#1f6feb'>" + escapeHtml(text) + "</b>");
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

        JButton aiGenBtn = iconButton("AI 生成断言", AllIcons.Actions.Lightning, e -> generateAiAssertions());

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
     * 一伦优化 v4：把"导出 cURL / 文档 / 报告"3 个按钮从右面板顶部工具栏下移到左侧 "..." 弹层。
     * 这里是给 ApiTreePanel 调用的公开入口，内部直接复用原私有方法。
     */
    public void exportCurlFromMenu() { exportCurl(); }
    public void exportApiDocFromMenu() { exportApiDoc(); }
    public void exportLastReportFromMenu() { exportLastReport(); }

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
        // 一伦优化 v30：Content-Type 按完整格式集合映射（JSON/表单/Raw/XML/Text/HTML）
        String contentType = selectedBodyFormatContentType(currentApi);
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
            preview.append("<b>").append(escapeHtml(e.getKey())).append("</b> (")
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

        // selected 在上方多次重赋值，非 effectively final，需用 final 副本供 lambda 捕获
        final List<ApiDefinition> selectedApis = selected;

        // 路径选择：统一用 FileChooser.chooseFile 弹出目录选择框（与导入同一套机制），
        // 跨平台一致。用 invokeLater + defaultModalityState 确保上方确认对话框模态状态清除后再弹。
        // 文件名与 Word 导出一致：RestAutoLab-yyyyMMddHHmmss.md
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
        String suggestName = "RestAutoLab-" + sdf.format(new java.util.Date()) + ".md";
        ApplicationManager.getApplication().invokeLater(() -> {
            String path = TestDataExporter.chooseExportPath(project, suggestName);
            if (path == null) return;
            try {
                ApiDocExporter.exportSelectedApis(selectedApis, path);
                Messages.showInfoMessage(project, "API文档已导出到:\n" + path, "导出成功");
            } catch (IOException e) {
                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.API_DOC, e);
            }
        }, ModalityState.defaultModalityState());
    }

    /**
     * 用自定义模板（.docx / .md）导出当前选中的接口文档。
     * <p>步骤：</p>
     * <ol>
     *   <li>选模板文件（FileChooser.chooseFile）</li>
     *   <li>选输出路径（按模板后缀默认 docx/md）</li>
     *   <li>调用 {@link TemplateEngine#render} 渲染</li>
     * </ol>
     */
    public void exportApiDocFromTemplate() {
        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> allApis = scanner.getCachedApis();
        if (allApis.isEmpty()) {
            Messages.showWarningDialog(project, "暂无API，请先扫描", "提示");
            return;
        }
        // 选中的接口
        List<ApiDefinition> selected = null;
        if (treePanel != null) {
            selected = treePanel.getSelectedApisForExport();
        }
        if ((selected == null || selected.isEmpty()) && currentApi != null) {
            selected = new java.util.ArrayList<>();
            selected.add(currentApi);
        }
        if (selected == null || selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n请先在左侧接口树选择 1 个或多个接口，再点击『用模板导出』",
                    "提示");
            return;
        }
        final List<ApiDefinition> selectedApis = selected;

        // 让用户选模板文件
        ApplicationManager.getApplication().invokeLater(() -> {
            String templatePath = TestDataExporter.chooseOpenFile(project,
                    "选择接口文档模板", "模板文件（需包含 ${api.xxx} 占位符）", "docx", "md", "markdown");
            if (templatePath == null) return;
            TemplateEngine.TemplateType type = TemplateEngine.detectType(templatePath);
            if (type == TemplateEngine.TemplateType.UNKNOWN) {
                Messages.showErrorDialog(project,
                        "不支持的模板类型，仅支持 .docx / .md / .markdown",
                        "模板错误");
                return;
            }
            if (type == TemplateEngine.TemplateType.PDF) {
                Messages.showErrorDialog(project,
                        "PDF 模板需要表单域或专用库支持，请改用 .docx 或 .md 模板。\n"
                                + "如需 PDF 导出，可先用 .md 模板生成后另存。",
                        "模板不支持");
                return;
            }
            // 模板必须含占位符才会有效果，否则产物与模板完全相同
            try {
                String text = TemplateEngine.extractTemplateText(templatePath);
                if (!TemplateEngine.hasPlaceholders(text)) {
                    int choice = Messages.showYesNoDialog(project,
                            "所选模板没有检测到任何占位符，导出结果会和模板一模一样（没有效果）。\n\n"
                                    + "模板里需要写占位符才会被替换，例如：\n"
                                    + "  {#each apis} … ${api.name} ${api.url} ${api.requestParams} … {/each}\n\n"
                                    + "是否帮你生成一份内置示例模板（含全部占位符），改完后再用它导出？",
                            "模板缺少占位符",
                            "生成示例模板", "仍要继续", Messages.getQuestionIcon());
                    if (choice == Messages.YES) {
                        String ext = type == TemplateEngine.TemplateType.DOCX ? ".docx" : ".md";
                        String name = "RestAutoLab-示例模板" + ext;
                        String out = TestDataExporter.chooseExportPath(project, name);
                        if (out == null) return;
                        try {
                            if (type == TemplateEngine.TemplateType.DOCX) {
                                TemplateEngine.writeSampleDocxTemplate(out);
                            } else {
                                Files.writeString(Paths.get(out),
                                        TemplateEngine.sampleMarkdownTemplate(), StandardCharsets.UTF_8);
                            }
                            Messages.showInfoMessage(project,
                                    "示例模板已生成：" + out + "\n\n"
                                            + "1) 按自己的格式修改它（保留 ${...} 占位符与 {#each apis}/{/each}）；\n"
                                            + "2) 再次右键『用模板导出』并选择这份文件即可。",
                                    "示例模板已生成");
                        } catch (IOException ex) {
                            ExportErrorReporter.reportExportFailure(project,
                                    ExportErrorReporter.Operation.API_DOC_TEMPLATE, ex);
                        }
                    } else if (choice != Messages.NO) {
                        return;
                    }
                }
            } catch (IOException ex) {
                ExportErrorReporter.reportExportFailure(project,
                        ExportErrorReporter.Operation.API_DOC_TEMPLATE, ex);
                return;
            }
            // 推荐输出路径（与内置导出同一命名规则：RestAutoLab-template-yyyyMMddHHmmss）
            String outExt = type == TemplateEngine.TemplateType.DOCX ? ".docx" : ".md";
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
            String suggestName = "RestAutoLab-template-" + sdf.format(new java.util.Date()) + outExt;
            String outputPath = TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            // 若用户没改后缀，按模板后缀修正
            String lowerOut = outputPath.toLowerCase();
            if (type == TemplateEngine.TemplateType.DOCX && !lowerOut.endsWith(".docx")) {
                outputPath = outputPath + ".docx";
            } else if (type == TemplateEngine.TemplateType.MARKDOWN
                    && !lowerOut.endsWith(".md") && !lowerOut.endsWith(".markdown")) {
                outputPath = outputPath + ".md";
            }
            try {
                TemplateEngine.render(templatePath, selectedApis, project.getName(), outputPath);
                Messages.showInfoMessage(project,
                        "模板导出成功！\n\n输出文件: " + outputPath
                                + "\n共填充 " + selectedApis.size() + " 个接口",
                        "导出成功");
            } catch (IOException ex) {
                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.API_DOC_TEMPLATE, ex);
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
            ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.TEST_REPORT, e);
        }
    }

    // ================================================================
    // 数据管理对话框：集中存放 保存/导入/导出 测试配置与全量测试数据
    // ================================================================

    /**
     * 一伦优化 R4：左侧"…"弹层触发——打开前置脚本与变量覆盖编辑弹窗。
     * <p>现统一收敛到 {@link #openEnvAndDataManageDialog()} 内作为 Tab；
     * 保留此方法仅为旧调用方兼容（已无引用）。</p>
     */
    @Deprecated
    public void openPreRequestConfigDialog() {
        openEnvAndDataManageDialog();
    }

    /**
     * 一伦优化 R4：左侧"…"弹层触发——打开 AI 配置弹窗。
     * <p>现统一收敛到 {@link #openEnvAndDataManageDialog()} 内作为 Tab；
     * 保留此方法仅为旧调用方兼容（已无引用）。</p>
     */
    @Deprecated
    public void openAiConfigDialog() {
        openEnvAndDataManageDialog();
    }

    /**
     * 一伦优化 R4：「环境 & 数据」统一入口。
     * <p>一伦优化 #3 时把"环境"和"数据"合并；R4 进一步把"前置脚本&变量覆盖"、"AI 配置"作为新 Tab 注入，
     * 让左侧"…"弹层只剩下"环境 & 数据"一个入口。Tab 顺序：环境 / 前置脚本 / AI 配置 / 数据。</p>
     * <p>关闭后通过 {@link EnvAndDataManageDialog#getPendingDataAction()} 拿到用户在数据 Tab 点选的导出/导入，
     * 用 invokeLater + defaultModalityState 执行（Windows 模态兼容）。</p>
     */
    public void openEnvAndDataManageDialog() {
        // 一伦优化 v24：包一层 try-catch，弹窗构造/初始化任何一步失败时弹真实堆栈给用户。
        // 设置按钮的 listener 也加了 catch —— 这里只捕构造期间同步异常，运行期 fireChange 链路的
        // 异常已经被各 fireChange 调用点内部 try-catch 吞掉或弹错。
        try {
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
        // 一伦优化 v23：双向联动 —— 弹窗内任一字段改动，立刻刷主面板。
        // 弹窗内：保存当前编辑 → 持久化 → fireChange → 主面板 applyExternalChangeToMainPanel
        dialog.getEnvDialog().addChangeListener(this::applyExternalChangeToMainPanel);
        // 一伦优化 v23：主面板改动通知弹窗的通道（envCombo / baseUrlField 改动时调用）
        currentEnvAndDataDialog = dialog;

        // 一伦优化 R4：注入"前置脚本&变量覆盖"和"AI 配置"两个新 Tab。
        // 顺序：环境 → 前置脚本 → AI 配置 → 数据
        // 适配弹窗：去掉 cardBorder 让 panel 平铺
        JPanel preReqPanel = getPreRequestPanel();
        preReqPanel.setBorder(JBUI.Borders.empty(8));
        dialog.addTab("前置脚本", AllIcons.General.Settings, preReqPanel,
                "接口级前置脚本（仅影响本次请求）和变量覆盖");

        // 一伦优化 R5：AI 配置面板无「保存」按钮 —— 保存由 dialog 的 OK 按钮统一触发。
        // 弹出嵌入 Tab 时先把 panel 构造出来，捕获引用，再注册 onCommit 回调：
        //   用户点 OK → dialog.doOKAction() → envDialog.applyChanges() + onCommit 列表 → aiPanel.commit()。
        // 注意：commit() 失败（字段校验不通过）会 return false，但当前 DialogWrapper 不能"拒绝关闭"，
        // 因此 AI 配置字段校验失败时仍会关闭弹窗 —— 这是与"环境&数据"合并弹窗的常见妥协；
        // 用户可在关闭前看到「服务器URL不能为空」等弹错提示。
        AiConfigPanel aiPanel = createAiConfigPanel(null);
        dialog.addTab("AI 配置", AllIcons.Actions.Lightning, aiPanel,
                "AI 服务器、API Key、模型与提示词（由 OK 按钮统一保存）");
        dialog.addOnCommit(() -> {
            // commit() 内部已做校验（失败弹错），即便失败也已执行 onAfterSaved=null 的逻辑
            aiPanel.commit();
        });

        dialog.show();
        // 一伦优化 v23：弹窗已关闭，解除引用避免后续误通知
        currentEnvAndDataDialog = null;

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
        } catch (Throwable t) {
            // 一伦优化 v24：弹窗构造/初始化失败时弹真实堆栈，并把 currentEnvAndDataDialog 引用清理
            currentEnvAndDataDialog = null;
            com.intellij.openapi.diagnostic.Logger.getInstance(ApiDebuggerPanel.class)
                    .error("[RestAutoLab] 打开「环境 & 数据」弹窗失败", t);
            com.intellij.openapi.ui.Messages.showErrorDialog(project,
                    "打开「环境 & 数据」弹窗失败：\n" + t.getClass().getName() + ": " + t.getMessage()
                            + "\n\n完整堆栈请见 IDE 日志（Help → Show Log in Finder）",
                    "错误");
        }
    }



    /** 一伦优化 #3：原 showDataManagerDialog + 内嵌 DataManagerDialog 类（~170 行）已删除。
     *  现由 {@link EnvAndDataManageDialog} 统一承担「环境 & 数据」合并弹窗：
     *  - 数据卡片列表由 {@link DataManagePanel} 静态构建
     *  - 卡片点击的 Runnable 由 {@link #openEnvAndDataManageDialog()} 注入
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
                                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.CONFIG, e));
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
            ExportErrorReporter.reportImportFailure(project, ExportErrorReporter.Operation.CONFIG, e);
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
                                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.TEST_DATA, e));
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
            ExportErrorReporter.reportImportFailure(project, ExportErrorReporter.Operation.TEST_DATA, e);
        }
    }

}