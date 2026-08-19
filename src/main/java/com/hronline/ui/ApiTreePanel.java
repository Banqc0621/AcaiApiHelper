package com.hronline.ui;

import com.hronline.RestAutoLabConstants;
import com.hronline.ai.AiParameterService;
import com.hronline.git.ApiChangeDetector;
import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.hronline.model.RequestHistory;
import com.hronline.chain.ApiDependency;
import com.hronline.chain.ChainTestExecutor;
import com.hronline.chain.DependencyDetector;
import com.hronline.scanner.ApiScannerService;
import com.hronline.scanner.StarredFolderService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.hronline.util.ApiDocExporter;
import com.hronline.util.ApiDocWordExporter;
import com.hronline.util.PostmanCollectionExporter;
import com.hronline.util.TestDataExporter;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.Transferable;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * API树形面板 - 以树形结构展示项目中扫描到的所有API接口
 *
 * 功能：
 * 1. 按控制器（Controller）分组展示所有API端点
 * 2. 顶部分类标签：全量 / 收藏 / 最新（最近3天 Git 变更的接口）
 * 3. 搜索框支持URL/名称/控制器名的模糊搜索
 * 4. 双击API节点跳转到对应的源码位置
 * 5. 单选API节点触发调试面板更新
 * 6. 右键菜单支持调试/复制URL等操作
 *
 * 树节点层级结构:
 * Root (隐藏)
 * ├── ControllerA (分组节点，带蓝色图标)
 * │   ├── [GET] /api/users - 获取用户列表 (API节点，方法彩色徽章)
 * │   └── [POST] /api/users - 创建用户
 * └── ControllerB
 *     └── [POST] /api/custom - 自定义接口
 */
public class ApiTreePanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(ApiTreePanel.class);

    private final Project project;

    /** 分类标签常量 */
    private static final String FILTER_ALL = "全量";
    private static final String FILTER_STARRED = "收藏";
    private static final String FILTER_LATEST = "最新";

    /** 「最新」过滤的天数窗口：仅显示最近 N 天 Git 变更涉及的接口（1 个月 ≈ 30 天） */
    private static final int LATEST_CHANGE_DAYS = 30;

    /** 树形控件 - 展示API分组和端点 */
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("API列表"));
    private final Tree tree = new Tree(treeModel);

    /** 搜索框 - 过滤API列表 */
    private final SearchTextField searchField = new SearchTextField();

    /** 分类按钮组 */
    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton btnAll = new JToggleButton(FILTER_ALL, AllIcons.General.Filter);
    private final JToggleButton btnStarred = new JToggleButton(FILTER_STARRED, AllIcons.Nodes.Favorite);
    private final JToggleButton btnLatest = new JToggleButton(FILTER_LATEST, AllIcons.Actions.Refresh);

    /** 统计标签 */
    private final JBLabel statsLabel = new JBLabel("");

    /** API选中回调 - 通知调试面板更新 */
    private Consumer<ApiDefinition> onApiSelected = null;

    /** 调试面板引用（注入，用于跨面板操作，如 cURL 导入和环境/数据管理） */
    private ApiDebuggerPanel debuggerPanel;

    /** 收藏模式下：apiKey -> ApiDefinition 解析表 */
    private final Map<String, ApiDefinition> starredApiByKey = new LinkedHashMap<>();

    /** 收藏文件夹服务（构造器内初始化，依赖 project 字段赋值） */
    private StarredFolderService folderService;
    /** AI 参数服务（收藏模式批量生成参数） */
    private AiParameterService aiService;
    /** HTTP 服务（收藏模式批量测试） */
    private HttpExecutorService httpService;

    /** 全量API列表（未过滤） */
    private List<ApiDefinition> allApis = Collections.emptyList();

    /** 当前分类过滤类型 */
    private String currentFilter = FILTER_ALL;

    /** 「最新」过滤的预计算结果：最近 {@link #LATEST_CHANGE_DAYS} 天有 Git 变更的接口列表。
     *  <p>由 {@link ApiChangeDetector} 在后台线程计算，null 表示尚未计算（首次点击「最新」时触发）。</p> */
    private volatile List<ApiDefinition> latestChangedApis = null;

    /** 「最新」是否正在后台计算中（避免重复触发） */
    private volatile boolean latestComputing = false;

    /** 空状态面板 */
    private final JPanel emptyPanel = new JPanel(new GridBagLayout());

    public ApiTreePanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.folderService = StarredFolderService.getInstance(project);
        this.aiService = AiParameterService.getInstance(project);
        this.httpService = HttpExecutorService.getInstance(project);
        setupTree();
        setupLayout();
    }

    /**
     * 配置树形控件
     */
    private void setupTree() {
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);  // 可变行高，适配HTML渲染
        tree.setBorder(JBUI.Borders.emptyLeft(6));
        tree.setFont(tree.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));
        tree.setBackground(JBColor.namedColor("Tree.background", Color.WHITE));

        // 拖拽支持：收藏模式下拖动接口节点到目标文件夹节点即移动
        tree.setDragEnabled(true);
        tree.setTransferHandler(new StarredDragTransferHandler());

        // 双击事件：跳转到API源码（收藏模式下改为调试此接口）
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    if (FILTER_STARRED.equals(currentFilter)) {
                        starredDebugApi();
                    } else {
                        navigateToSource();
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }
        });

        // 选择事件：通知调试面板
        tree.addTreeSelectionListener(e -> {
            if (FILTER_STARRED.equals(currentFilter)) {
                StarredApiNode n = getSelectedStarredApiNode();
                if (n != null && onApiSelected != null) onApiSelected.accept(n.api);
                return;
            }
            ApiDefinition selectedApi = getSelectedApi();
            if (selectedApi != null && onApiSelected != null) {
                onApiSelected.accept(selectedApi);
            }
        });

        // 自定义渲染：区分自动/手动API，方法彩色徽章
        tree.setCellRenderer(new ApiTreeCellRenderer());
    }

    /**
     * 右键弹出菜单。
     * <p>关键：保留多选。
     * <ul>
     *   <li>右键点中的节点已在已选集中 → 保留多选（让用户能右键多选后导出）</li>
     *   <li>右键点中的节点未在已选集中 → <b>加入</b>已选集（不是覆盖）</li>
     *   <li>右键点在空白处 → 保留原选择（不再覆盖，避免误清空多选）</li>
     * </ul>
     * 这样保证：用户先 Cmd+点 5 个接口，再右键其中任意一个，右键菜单触发时
     * <code>tree.getSelectionPaths()</code> 仍是这 5 个路径。
     */
    private void handlePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        // 收藏模式：走收藏专属右键菜单（文件夹/接口操作）
        if (FILTER_STARRED.equals(currentFilter)) {
            showStarredPopup(e);
            return;
        }
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());

        if (path != null) {
            TreePath[] cur = tree.getSelectionPaths();
            java.util.Set<TreePath> selSet = cur == null
                    ? new java.util.HashSet<>() : new java.util.HashSet<>(java.util.Arrays.asList(cur));
            if (!selSet.contains(path)) {
                // 把当前右键命中的路径加入选择（不清空其它已选项）
                selSet.add(path);
                tree.setSelectionPaths(selSet.toArray(new TreePath[0]));
            }
            // 若已在已选集中，啥都不做（保留多选）
        }
        // 空白处右键：保留原选择，不再覆盖

        Object node = path == null ? null : path.getLastPathComponent();
        if (node == null) return;
        if (!(node instanceof DefaultMutableTreeNode)) return;
        Object userObj = ((DefaultMutableTreeNode) node).getUserObject();

        // 右键命中的节点若不是 API（Controller 或非 API 节点），
        // 仍然允许弹菜单 —— 多选场景下用户可能右键空白处或 Controller 父节点。
        // 但 api 变量必须有一个"上下文接口"以兼容菜单中的旧动作（调试/收藏/复制URL等）。
        final ApiDefinition api;
        if (userObj instanceof ApiDefinition) {
            api = (ApiDefinition) userObj;
        } else {
            // 找已选中的第一个 API 作为上下文
            TreePath[] cur2 = tree.getSelectionPaths();
            ApiDefinition found = null;
            if (cur2 != null) {
                for (TreePath tp : cur2) {
                    Object u = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
                    if (u instanceof ApiDefinition) { found = (ApiDefinition) u; break; }
                }
            }
            if (found == null) return; // 既不是 API 节点又没已选 API → 不弹菜单
            api = found;
        }

        DefaultActionGroup group = new DefaultActionGroup();

        // 调试动作
        AnAction debugAction = new AnAction("调试此接口", "在调试面板打开此接口", AllIcons.Actions.Execute) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                try {
                    if (onApiSelected != null) onApiSelected.accept(api);
                } catch (Exception ex) {
                    LOG.warn("调试接口回调失败: " + api.getUrl(), ex);
                    Messages.showErrorDialog(project, "打开调试面板失败：" + ex.getMessage(), "调试失败");
                }
            }
        };
        group.add(debugAction);

        // 收藏：已收藏则提供「取消收藏」；未收藏则「收藏」（单选/多选均可，统一走批量逻辑）
        StarredFolderService folderSvc =
                StarredFolderService.getInstance(project);
        boolean isStarred = folderSvc.isStarred(api.uniqueKey());
        if (isStarred) {
            AnAction unstarAction = new AnAction("取消收藏", "从所有收藏文件夹中移除", AllIcons.Nodes.Favorite) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    java.util.List<ApiDefinition> selected = getSelectedApis();
                    if (selected.isEmpty()) {
                        ApiDefinition single = getSelectedApi();
                        if (single != null) selected = java.util.Collections.singletonList(single);
                    }
                    if (selected.isEmpty()) return;
                    StarredFolderService svc =
                            StarredFolderService.getInstance(project);
                    for (ApiDefinition a : selected) {
                        svc.unstarApi(a.uniqueKey());
                        a.setStarred(false);
                    }
                    tree.repaint();
                }
            };
            group.add(unstarAction);
        }

        // 「收藏」按钮：单选/多选统一走批量收藏对话框
        AnAction starAction = new AnAction("收藏", "加入收藏文件夹", AllIcons.Nodes.Favorite) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                java.util.List<ApiDefinition> selected = getSelectedApis();
                if (selected.isEmpty()) {
                    ApiDefinition single = getSelectedApi();
                    if (single != null) selected = java.util.Collections.singletonList(single);
                }
                if (selected.isEmpty()) return;
                addApisToFolderDialog(selected);
            }
        };
        group.add(starAction);

        // 复制URL
        group.addSeparator();
        AnAction copyUrlAction = new AnAction("复制URL", "复制接口路径到剪贴板", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ApiDefinition selectedApi = getSelectedApi();
                if (selectedApi != null) {
                    java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(selectedApi.getUrl());
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                }
            }
        };
        group.add(copyUrlAction);

        // 复制cURL
        AnAction copyCurlAction = new AnAction("复制为cURL", "复制为cURL命令", AllIcons.Debugger.Console) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ApiDefinition selectedApi = getSelectedApi();
                if (selectedApi == null) return;
                RestAutoLabSettingsState s = RestAutoLabSettingsState.getInstance(project);
                String url = s.getBaseUrl() + selectedApi.getUrl();
                StringBuilder curl = new StringBuilder("curl -X ").append(selectedApi.getHttpMethod())
                        .append(" '").append(url).append("'");
                if (selectedApi.getConsumes() != null) {
                    curl.append(" -H 'Content-Type: ").append(selectedApi.getConsumes()).append("'");
                }
                java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(curl.toString());
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                Messages.showInfoMessage(project, "cURL命令已复制到剪贴板", "复制成功");
            }
        };
        group.add(copyCurlAction);

        // 导出选中接口（均支持多选）
        group.addSeparator();
        AnAction exportMdAction = new AnAction("导出 Markdown",
                "将选中的接口（含最近测试数据）导出为 Markdown 文档", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportSelectedApisAsMarkdown();
            }
        };
        group.add(exportMdAction);

        AnAction exportWordAction = new AnAction("导出 Word",
                "将选中的接口按内置设计开发接口模版导出为 Word 文档", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportSelectedApisAsWord();
            }
        };
        group.add(exportWordAction);

        AnAction exportPostmanAction = new AnAction("导出 Postman JSON",
                "将选中的接口导出为 Postman/Apifox 可直接导入的 JSON", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportSelectedApisAsPostmanJson();
            }
        };
        group.add(exportPostmanAction);

        // 自定义模板导出（.docx / .md 模板 + 占位符）
        AnAction exportTemplateAction = new AnAction("用模板导出",
                "用自定义 Word/Markdown 模板导出选中的接口文档", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (debuggerPanel == null) {
                    Messages.showWarningDialog(project, "调试面板尚未初始化，请打开工具窗口后重试", "用模板导出");
                    return;
                }
                debuggerPanel.exportApiDocFromTemplate();
            }
        };
        group.add(exportTemplateAction);

        // 依赖链操作
        group.addSeparator();
        AnAction chainTestAction = new AnAction("依赖链测试",
                "自动检测接口依赖，按依赖顺序批量测试并传递响应值", AllIcons.Actions.Execute) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                runChainBatchTest();
            }
        };
        group.add(chainTestAction);

        AnAction chainAiGenAction = new AnAction("AI 生成参数",
                "为选中接口生成参数并标注依赖自动填充项", AllIcons.Actions.Lightning) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                runChainAiGen();
            }
        };
        group.add(chainAiGenAction);

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group);
        popup.getComponent().show(tree, e.getX(), e.getY());
    }

    /**
     * 组装面板布局
     */
    private void setupLayout() {
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setBorder(JBUI.Borders.empty(6, 6, 4, 6));
        topContainer.setOpaque(false);

        // 顶部：分类 + 设置 单行（createTopToolbar 已含全部按钮）
        JPanel topRow = createTopToolbar();
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        topContainer.add(topRow);

        // 搜索框：保持存在（原"右侧列表中的文本框"用户希望保留并美化）
        searchField.getTextEditor().getEmptyText().setText("搜索接口路径、名称、Controller或描述...");
        searchField.setFont(searchField.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        // 顶部留 6px 呼吸
        topContainer.add(Box.createVerticalStrut(6));
        topContainer.add(searchField);

        // 搜索框实时过滤
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                applyFilters();
            }
        });

        add(topContainer, BorderLayout.NORTH);

        // 构建树面板（用 CardLayout 切换 树/空状态）
        JPanel centerPanel = new JPanel(new CardLayout());
        JBScrollPane scrollPane = new JBScrollPane(tree);
        scrollPane.setBorder(null);
        centerPanel.add(scrollPane, "tree");
        centerPanel.add(createEmptyStatePanel(), "empty");
        add(centerPanel, BorderLayout.CENTER);

        // 底部统计栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(JBUI.Borders.empty(2, 6));
        statsLabel.setFont(statsLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_TINY));
        statsLabel.setForeground(JBColor.GRAY);
        bottomPanel.add(statsLabel, BorderLayout.WEST);
        add(bottomPanel, BorderLayout.SOUTH);

        // 保存 centerPanel 引用以便切换
        centerPanel.putClientProperty("cardLayout", centerPanel.getLayout());
    }

    /**
     * 创建精美的空状态面板
     */
    private JPanel createEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(4, 0, 4, 0);

        JBLabel iconLabel = new JBLabel(AllIcons.Actions.Find);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridy = 0;
        panel.add(iconLabel, gbc);

        JBLabel titleLabel = new JBLabel("<html><b>暂无 API 数据</b></html>");
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setForeground(JBColor.GRAY);
        gbc.gridy = 1;
        panel.add(titleLabel, gbc);

        JBLabel hintLabel = new JBLabel("<html><center>点击工具栏 \"扫描API\" 按钮<br/>自动检测项目中的所有接口及参数</center></html>");
        hintLabel.setHorizontalAlignment(SwingConstants.CENTER);
        hintLabel.setForeground(JBColor.GRAY);
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        gbc.gridy = 2;
        panel.add(hintLabel, gbc);

        return panel;
    }

    /**
     * 创建顶部工具栏。
     * <p>一伦优化 v9：四按钮 <b>固定靠左</b>，右侧剩余空间由弹性空白吸收——
     * 拖动左侧分割条变宽时，按钮位置不变，避免被集体向右推。</p>
     */
    private JPanel createTopToolbar() {
        // 单行容器：四按钮固定靠左，右侧自动撑满
        // [全量] [收藏] [最新] [⚙] ................
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(JBUI.Borders.empty(0, 0, 4, 0));
        row.setOpaque(false);
        // 关键：让 row 自身宽度与父容器一致，按钮保持 preferred 宽度，不会被 BoxLayout 拉伸
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 分类按钮（全量 / 收藏 / 最新）— 固定靠左
        filterGroup.add(btnAll);
        filterGroup.add(btnStarred);
        filterGroup.add(btnLatest);
        for (JToggleButton btn : new JToggleButton[]{btnAll, btnStarred, btnLatest}) {
            styleFilterButton(btn);
            row.add(btn);
            // 按钮之间留 4px 呼吸
            row.add(Box.createHorizontalStrut(4));
        }
        btnAll.setSelected(true);
        btnAll.addActionListener(e -> {
            currentFilter = FILTER_ALL;
            // 「全量」点击时若缓存为空，主动触发一次扫描
            triggerScanIfNeeded("全量");
            applyFilters();
        });
        btnStarred.addActionListener(e -> { currentFilter = FILTER_STARRED; applyFilters(); });
        btnLatest.addActionListener(e -> {
            currentFilter = FILTER_LATEST;
            // 「最新」点击时若缓存为空，主动触发扫描（triggerLatestFilter 内部会异步重算）
            triggerScanIfNeeded("最新");
            triggerLatestFilter();
        });

        // 设置齿轮：紧贴「最新」右侧（与分类按钮同基线 26-28px）
        JButton settingsBtn = new JButton(AllIcons.General.Settings);
        settingsBtn.setToolTipText(null);
        settingsBtn.putClientProperty("JButton.buttonType", "borderless");
        settingsBtn.setFocusPainted(false);
        // 与分类按钮等高（26px），让整行 baseline 一致；用 roundRect 占位以防 hover 时 outline 错位
        settingsBtn.putClientProperty("JButton.buttonType", "roundRect");
        settingsBtn.setPreferredSize(new Dimension(30, 26));
        settingsBtn.setMinimumSize(new Dimension(30, 26));
        settingsBtn.setMaximumSize(new Dimension(30, 26));
        settingsBtn.setMargin(new Insets(2, 4, 2, 4));
        settingsBtn.setIconTextGap(0);
        settingsBtn.setHorizontalTextPosition(SwingConstants.CENTER);
        settingsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsBtn.addActionListener(e -> {
            if (debuggerPanel == null) {
                Messages.showErrorDialog(project, "调试面板尚未初始化，请重新打开 RestAutoLab 工具窗口。", "无法打开管理面板");
                return;
            }
            try {
                debuggerPanel.openEnvAndDataManageDialog();
            } catch (Throwable t) {
                // 一伦优化 v24：捕获设置按钮点击异常，弹真实堆栈给用户便于诊断
                com.intellij.openapi.diagnostic.Logger.getInstance(ApiTreePanel.class)
                        .error("[RestAutoLab] 打开「环境 & 数据」弹窗失败", t);
                Messages.showErrorDialog(project,
                        "打开「环境 & 数据」弹窗失败：\n" + t.getClass().getName() + ": " + t.getMessage()
                                + "\n\n完整堆栈请见 IDE 日志（Help → Show Log in Finder）",
                        "错误");
            }
        });
        row.add(settingsBtn);

        // 弹性空白放在最后 —— 吸收右侧剩余空间，按钮组始终固定靠左
        row.add(Box.createHorizontalGlue());

        return row;
    }

    /**
     * 一伦优化 v7：「全量」「最新」切换时若接口列表为空或缓存已失效，主动触发一次扫描，
     * 替代被移除的独立「扫描API」按钮。这样分类按钮本身就承担了扫描入口职责。
     */
    private void triggerScanIfNeeded(String reason) {
        if (allApis.isEmpty()) {
            ApiScannerService.getInstance(project).scanProjectApisAsync();
            statsLabel.setText("● 正在扫描API（" + reason + "）...");
        }
    }

    /**
     * 一伦优化 R4：左侧统一的更多操作入口只剩「环境 & 数据」一个菜单项。
     * <p>原"前置脚本&变量覆盖"、"AI 配置"、"导出"子菜单（R3 引入）已统一合并到
     * {@link EnvAndDataManageDialog} 内作为 Tab 呈现：
     * <ul>
     *   <li>前置脚本 → 弹窗的"前置脚本" Tab</li>
     *   <li>AI 配置 → 弹窗的"AI 配置" Tab</li>
     *   <li>导出（cURL / Markdown / HTML 报告）→ 这些是低频且结果型动作，
     *       已迁到右侧调试面板顶部的「导出」按钮下（避免和左侧"…"弹层互相干扰）</li>
     * </ul>
     * </p>
     */
    private void showMoreMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem envData = new JMenuItem("环境 & 数据", AllIcons.General.Settings);
        envData.setToolTipText("管理环境、变量、全局请求头、AI 配置、前置脚本与测试数据");
        envData.addActionListener(e -> {
            if (debuggerPanel == null) {
                Messages.showErrorDialog(project, "调试面板尚未初始化，请重新打开 RestAutoLab 工具窗口。", "无法打开管理面板");
                return;
            }
            debuggerPanel.openEnvAndDataManageDialog();
        });
        menu.add(envData);

        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * 创建过滤器面板 —— 一伦优化 v7：分类按钮已合并到 {@link #createTopToolbar()}，
     * 保留此方法仅为兼容历史调用方，不再构建独立 UI。
     */
    private JPanel createFilterPanel() {
        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setOpaque(false);
        return filterPanel;
    }

    /**
     * 设置筛选按钮的紧凑样式
     */
    private void styleFilterButton(JToggleButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        btn.setMargin(new Insets(2, 8, 2, 8));
        btn.setFocusPainted(false);
        // roundRect 圆角描边，与工具栏按钮风格统一，告别方块感
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.setHorizontalTextPosition(SwingConstants.RIGHT);
        btn.setIconTextGap(4);
        
        // 添加悬停提示
        switch (btn.getText()) {
            case FILTER_ALL:
                btn.setToolTipText("显示所有API接口");
                break;
            case FILTER_STARRED:
                btn.setToolTipText("打开收藏文件夹管理（文件夹分组 / 拖拽 / 批量AI参数 / 批量测试）");
                break;
            case FILTER_LATEST:
                btn.setToolTipText("仅显示最近1个月（" + LATEST_CHANGE_DAYS + "天）Git 变更涉及的接口（含 Controller/Service/实体类等全栈逻辑改动）");
                break;
        }
    }

    // ================================================================
    // Getter/Setter for callback
    // ================================================================

    public Consumer<ApiDefinition> getOnApiSelected() {
        return onApiSelected;
    }

    public void setOnApiSelected(Consumer<ApiDefinition> onApiSelected) {
        this.onApiSelected = onApiSelected;
    }

    /**
     * 注入调试面板引用，供面板间协作（如顶部"扫描/导入"按钮、cURL 导入跳转等）。
     * 由 {@link RestAutoLabToolWindowFactory} 在创建面板时调用。
     */
    public void setDebuggerPanel(ApiDebuggerPanel debuggerPanel) {
        this.debuggerPanel = debuggerPanel;
    }

    /** 获取注入的调试面板（可能为 null，外层在 ToolWindowFactory 中已确保非空） */
    public ApiDebuggerPanel getDebuggerPanel() {
        return debuggerPanel;
    }

    /** 当前选中接口所属的收藏文件夹ID（仅收藏视图有效；全量视图返回null）。
     *  供外层在加载接口时传入，以实现同一接口在不同文件夹中参数各自独立归档。 */
    public String getSelectedFolderId() {
        if (FILTER_STARRED.equals(currentFilter)) {
            StarredApiNode n = getSelectedStarredApiNode();
            return n != null ? n.folderId : null;
        }
        return null;
    }

    /** 设置「收藏」按钮回调：点击后由外层切换到收藏 Tab */
    public void setOnShowStarred(Runnable onShowStarred) {
        // 保留空方法以兼容潜在的外部调用，但收藏已改为内嵌视图切换，不再使用此回调
    }

    // ================================================================
    // 公共方法
    // ================================================================

    /**
     * 更新API树形列表
     * 从扫描服务获取API数据，按控制器分组构建树节点
     *
     * @param apis 要展示的API列表
     */
    public void updateTree(List<ApiDefinition> apis) {
        allApis = apis;
        LOG.warn("[ApiTree] updateTree 接收接口数=" + (apis == null ? 0 : apis.size())
                + ", 当前过滤=" + currentFilter);
        // 扫描产生新数据时清空搜索框，避免旧搜索词把新数据过滤掉（"显示不全"的诱因之一）
        if (!searchField.getText().trim().isEmpty()) {
            searchField.setText("");
        }
        // 扫描产生新数据：失效「最新」缓存，并清空 latestChangedApis 以便用新数据重算。
        // applyCategoryFilter 在 latestChangedApis==null 时回退全量显示，
        // 故此处先渲染全量，再后台重算最新子集。
        latestChangedApis = null;
        try {
            ApiChangeDetector.getInstance(project).onScanComplete();
        } catch (Exception e) {
            LOG.warn("失效最新过滤缓存失败: " + e.getMessage());
        }
        applyFilters();
        // 若用户正在看「最新」，后台重算（算完会自动刷新树为最新子集）
        if (FILTER_LATEST.equals(currentFilter)) {
            triggerLatestFilter();
        }
    }

    /**
     * 应用分类过滤 + 搜索过滤，更新树显示
     */
    private void applyFilters() {
        // 收藏模式：直接构建文件夹视图（不走普通 API 过滤管线）
        if (FILTER_STARRED.equals(currentFilter)) {
            refreshStarredApiIndex();
            buildStarredTree();
            updateStats(Collections.emptyList());
            return;
        }

        List<ApiDefinition> filtered = applyCategoryFilter(allApis);

        // 再应用搜索过滤
        String keyword = searchField.getText().trim();
        if (!keyword.isBlank()) {
            String lowerKeyword = keyword.toLowerCase();
            filtered = filtered.stream()
                    .filter(api -> api.getUrl().toLowerCase().contains(lowerKeyword)
                            || api.getName().toLowerCase().contains(lowerKeyword)
                            || api.getControllerName().toLowerCase().contains(lowerKeyword)
                            || api.getHttpMethod().toLowerCase().contains(lowerKeyword)
                            || (api.getDescription() != null && api.getDescription().toLowerCase().contains(lowerKeyword)))
                    .collect(Collectors.toList());
        }

        updateStats(filtered);
        buildTree(filtered);
    }

    /**
     * 根据当前选中的分类标签过滤API列表
     */
    private List<ApiDefinition> applyCategoryFilter(List<ApiDefinition> apis) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        switch (currentFilter) {
            case FILTER_LATEST:
                // 「最新」使用后台预计算的结果（最近 N 天 Git 变更涉及的接口）。
                // 尚未计算时（latestChangedApis == null）回退到全量显示，
                // 避免扫描后/重算期间用户看到空白列表（"接口显示不全"的根因之一）。
                // triggerLatestFilter 会在后台计算完成后刷新为最新变更子集。
                return latestChangedApis != null ? new ArrayList<>(latestChangedApis) : new ArrayList<>(apis);
            default:
                // 全量 / 收藏（收藏按钮已改为打开管理器，不再切换过滤器，故同全量）
                return new ArrayList<>(apis);
        }
    }

    /**
     * 更新统计标签
     */
    private void updateStats(List<ApiDefinition> filtered) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        long starredCount = allApis.stream()
                .filter(api -> settings.isApiStarred(api.uniqueKey()) || api.isStarred()).count();
        long latestCount = latestChangedApis != null ? latestChangedApis.size() : 0;
        statsLabel.setText(String.format(
                "\u2022 全量 %d  \u2022 ⭐ %d  \u2022 最新 %d  \u2022 显示 %d",
                allApis.size(), starredCount, latestCount, filtered.size()));
    }

    /**
     * 构建树节点
     */
    private void buildTree(List<ApiDefinition> apis) {
        ApplicationManager.getApplication().invokeLater(() -> {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("API列表");

            // 切换树/空状态面板
            toggleEmptyState(apis.isEmpty() && allApis.isEmpty());

            if (apis.isEmpty()) {
                treeModel.setRoot(root);
                treeModel.reload();
                return;
            }

            LOG.info("Building tree with " + apis.size() + " APIs");

            // 按控制器名称分组，保持插入顺序
            Map<String, List<ApiDefinition>> grouped = new LinkedHashMap<>();
            for (ApiDefinition api : apis) {
                String controllerName = api.getControllerName();
                if (controllerName == null || controllerName.isBlank()) {
                    controllerName = "未分类";
                }
                grouped.computeIfAbsent(controllerName, k -> new ArrayList<>()).add(api);
            }

            int totalApiCount = apis.size();
            int controllerCount = 0;
            for (Map.Entry<String, List<ApiDefinition>> entry : grouped.entrySet()) {
                String controllerName = entry.getKey();
                List<ApiDefinition> controllerApis = entry.getValue();

                // v3: 按最近调用时间排序（最近调用的排前面）
                controllerApis.sort((a, b) -> {
                    long timeA = Math.max(a.getLastCalledAt(), (long) a.getCallCount() * 1000);
                    long timeB = Math.max(b.getLastCalledAt(), (long) b.getCallCount() * 1000);
                    return Long.compare(timeB, timeA);
                });
                
                DefaultMutableTreeNode controllerNode = new DefaultMutableTreeNode(
                        controllerName + " (" + controllerApis.size() + ")");
                controllerCount++;

                for (ApiDefinition api : controllerApis) {
                    DefaultMutableTreeNode apiNode = new DefaultMutableTreeNode(api);
                    controllerNode.add(apiNode);
                }

                root.add(controllerNode);
            }

            treeModel.setRoot(root);
            treeModel.reload();

            // 默认展开所有一级节点（Controller节点），API子节点由用户手动展开
            int controllerNodeCount = root.getChildCount();
            for (int i = 0; i < controllerNodeCount; i++) {
                TreePath path = new TreePath(((DefaultMutableTreeNode) root.getChildAt(i)).getPath());
                tree.expandPath(path);
            }

            // 诊断：确认实际建树节点数与传入数一致（排查"显示不全"）
            int builtApiNodes = 0;
            for (int i = 0; i < controllerNodeCount; i++) {
                builtApiNodes += root.getChildAt(i).getChildCount();
            }
            LOG.warn("[ApiTree] buildTree 传入=" + apis.size()
                    + ", Controller节点=" + controllerNodeCount
                    + ", 实际API叶子节点=" + builtApiNodes);
        });
    }

    // ================================================================
    // 收藏文件夹视图（「收藏」按钮切换到此模式）
    // ================================================================

    /** 文件夹节点包装 */
    private static final class FolderNode {
        final StarredFolder folder;
        FolderNode(StarredFolder f) { this.folder = f; }
        public String toString() { return folder.getName() + " (" + folder.getApiKeys().size() + ")"; }
    }

    /** 收藏接口节点包装（带所属文件夹 id，用于移动/移除/参数编辑） */
    private static final class StarredApiNode {
        final ApiDefinition api;
        final String folderId;
        /** 渲染时使用的测试状态快照（buildStarredTree 时填充，渲染器无 project 故用字段传递） */
        FolderApiStatus status;
        /** 是否已配置测试参数（buildStarredTree 时填充，渲染器据此显示参数标记） */
        boolean hasParams;
        StarredApiNode(ApiDefinition api, String folderId) { this.api = api; this.folderId = folderId; }
        public String toString() { return api.getHttpMethod() + " " + api.getUrl(); }
    }

    /** 刷新收藏模式的接口索引（从扫描缓存解析 uniqueKey -> ApiDefinition） */
    private void refreshStarredApiIndex() {
        starredApiByKey.clear();
        for (ApiDefinition api : allApis) {
            starredApiByKey.put(api.uniqueKey(), api);
        }
    }

    /** 构建收藏文件夹视图树 */
    private void buildStarredTree() {
        // 调用方可能在后台线程（如扫描完成回调 onScanComplete），需切到 EDT 操作树；
        // 但展开逻辑必须与 setRoot 同步执行，避免 invokeLater 嵌套导致 setRoot 与 expandPath 时序错位
        // （时序错位会表现为文件夹折叠后打不开、初始未展开等交互问题）
        Runnable build = () -> {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
            String keyword = searchField.getText().trim().toLowerCase();

            List<StarredFolder> folders = folderService.loadFolders();
            int folderCount = 0, apiCount = 0, failedCount = 0;
            for (StarredFolder folder : folders) {
                DefaultMutableTreeNode folderNode = new DefaultMutableTreeNode(new FolderNode(folder));
                for (String apiKey : folder.getApiKeys()) {
                    ApiDefinition api = starredApiByKey.get(apiKey);
                    if (api == null) continue;
                    // 搜索过滤
                    if (!keyword.isBlank()) {
                        String key = (api.getHttpMethod() + " " + api.getUrl() + " " + api.getName()).toLowerCase();
                        if (!key.contains(keyword)) continue;
                    }
                    FolderApiStatus st = folderService.getStatus(folder.getId(), apiKey);
                    StarredApiNode sNode = new StarredApiNode(api, folder.getId());
                    sNode.status = st;
                    Map<String, String> savedParams = folderService.getParams(folder.getId(), apiKey);
                    sNode.hasParams = savedParams != null && !savedParams.isEmpty();
                    folderNode.add(new DefaultMutableTreeNode(sNode));
                    apiCount++;
                    if (st.shouldHighlightRed()) failedCount++;
                }
                root.add(folderNode);
                folderCount++;
            }
            treeModel.setRoot(root);
            // setRoot 已触发结构重载，无需再 reload()（reload 会再次清空 expandedState，让紧随的 expandPath 失效）
            // 同步展开所有文件夹节点：紧随 setRoot 之后，路径基于新 root 构造，有效
            for (int i = 0; i < root.getChildCount(); i++) {
                TreeNode n = root.getChildAt(i);
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) n).getPath()));
            }
            statsLabel.setText(String.format("● 文件夹 %d · 接口 %d · 失败标红 %d",
                    folderCount, apiCount, failedCount));
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            build.run();
        } else {
            ApplicationManager.getApplication().invokeLater(build);
        }
    }

    /** 获取收藏模式下选中的文件夹节点 */
    private StarredFolder getSelectedStarredFolder() {
        Object node = tree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) return null;
        Object uo = ((DefaultMutableTreeNode) node).getUserObject();
        if (uo instanceof FolderNode) return ((FolderNode) uo).folder;
        if (uo instanceof StarredApiNode) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) ((DefaultMutableTreeNode) node).getParent();
            if (parent != null && parent.getUserObject() instanceof FolderNode) {
                return ((FolderNode) parent.getUserObject()).folder;
            }
        }
        return null;
    }

    /** 获取收藏模式下选中的接口节点 */
    private StarredApiNode getSelectedStarredApiNode() {
        Object node = tree.getLastSelectedPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) return null;
        Object uo = ((DefaultMutableTreeNode) node).getUserObject();
        return uo instanceof StarredApiNode ? (StarredApiNode) uo : null;
    }

    // ── 收藏模式右键菜单 ──

    /** 拖拽 TransferHandler：收藏模式下拖接口到文件夹即移动；非收藏模式不干预 */
    private final class StarredDragTransferHandler extends TransferHandler {
        private final java.awt.datatransfer.DataFlavor flavor =
                new java.awt.datatransfer.DataFlavor(StarredApiNode.class, "StarredApiNode");

        @Override public int getSourceActions(JComponent c) {
            return FILTER_STARRED.equals(currentFilter) ? MOVE : NONE;
        }

        @Override protected Transferable createTransferable(JComponent c) {
            if (!FILTER_STARRED.equals(currentFilter)) return null;
            StarredApiNode n = getSelectedStarredApiNode();
            if (n == null) return null;
            final StarredApiNode data = n;
            return new Transferable() {
                @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{flavor}; }
                @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return flavor.equals(f); }
                @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f)
                        throws java.awt.datatransfer.UnsupportedFlavorException {
                    if (!flavor.equals(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
                    return data;
                }
            };
        }

        @Override public boolean canImport(TransferHandler.TransferSupport support) {
            if (!FILTER_STARRED.equals(currentFilter)) return false;
            if (!support.isDataFlavorSupported(flavor)) return false;
            if (support.getDropLocation() == null) return false;
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            TreePath path = dl.getPath();
            if (path == null) return false;
            Object node = path.getLastPathComponent();
            if (!(node instanceof DefaultMutableTreeNode)) return false;
            return ((DefaultMutableTreeNode) node).getUserObject() instanceof FolderNode;
        }

        @Override public boolean importData(TransferHandler.TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                StarredApiNode n = (StarredApiNode) support.getTransferable().getTransferData(flavor);
                JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
                DefaultMutableTreeNode targetNode = (DefaultMutableTreeNode) dl.getPath().getLastPathComponent();
                FolderNode fn = (FolderNode) targetNode.getUserObject();
                if (n.folderId.equals(fn.folder.getId())) return false; // 同文件夹不处理
                boolean ok = folderService.moveApi(n.api.uniqueKey(), n.folderId, fn.folder.getId());
                if (ok) SwingUtilities.invokeLater(ApiTreePanel.this::buildStarredTree);
                return ok;
            } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException ex) {
                return false;
            }
        }
    }

    private void showStarredPopup(MouseEvent e) {
        int row = tree.getRowForLocation(e.getX(), e.getY());
        DefaultActionGroup group = new DefaultActionGroup();

        if (row < 0) {
            // 空白处：仅「新建文件夹」
            group.add(starredAction("新建文件夹", AllIcons.Actions.NewFolder, this::starredNewFolder));
        } else {
            // 一伦优化 #4：右键命中节点时保留多选，而不是替换为单选。
            // 这与普通 handlePopup 行为一致，让"先 Cmd 多选 N 个接口再右键其中一个"的体验可工作。
            TreePath path = tree.getPathForRow(row);
            TreePath[] cur = tree.getSelectionPaths();
            java.util.Set<TreePath> selSet = cur == null
                    ? new java.util.HashSet<>() : new java.util.HashSet<>(java.util.Arrays.asList(cur));
            if (!selSet.contains(path)) {
                selSet.add(path);
                tree.setSelectionPaths(selSet.toArray(new TreePath[0]));
            }

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object uo = node.getUserObject();

            if (uo instanceof FolderNode) {
                StarredFolder f = ((FolderNode) uo).folder;
                group.add(starredAction("重命名", AllIcons.Actions.Edit, this::starredRenameFolder));
                group.addSeparator();
                group.add(starredAction("AI 生成参数", AllIcons.Actions.Lightning, this::starredBatchAiGen));
                group.add(starredAction("批量测试", AllIcons.Actions.Execute, this::starredBatchTest));
                group.add(starredAction("依赖链批量测试", AllIcons.Actions.Execute, this::starredChainBatchTest));
                group.addSeparator();
                // 一伦优化 v37：破坏性操作固定放菜单最底部
                group.add(starredAction("删除文件夹", AllIcons.Actions.Cancel, this::starredDeleteFolder));
            } else if (uo instanceof StarredApiNode) {
                // 一伦优化 v37：菜单按选中数量自适应——多选只留批量操作，单选保留完整操作，
                // 「移动到…」「移除」在两种模式下复用同一入口（内部自动分流单/批量）。
                List<StarredApiNode> selectedApis = getSelectedStarredApiNodes();
                boolean multi = selectedApis.size() > 1;
                if (multi) {
                    group.add(starredAction("批量测试", AllIcons.Actions.Execute, this::starredBatchTestSelected));
                } else {
                    group.add(starredAction("调试此接口", AllIcons.Actions.Execute, this::starredDebugApi));
                    group.add(starredAction("编辑参数", AllIcons.Actions.EditSource, this::starredEditParams));
                }
                group.addSeparator();
                group.add(starredAction("移动到…", AllIcons.Actions.MoveTo2, this::starredMoveToUnified));
                if (!multi) {
                    group.add(starredAction("复制到…", AllIcons.Actions.Copy, this::starredCopyTo));
                    group.add(starredAction("复制URL", AllIcons.Actions.Copy, this::starredCopyUrl));
                }
                group.addSeparator();
                if (!multi) {
                    group.add(starredAction("取消警示", AllIcons.Actions.QuickfixBulb, this::starredClearWarning));
                }
                group.add(starredAction("移除", AllIcons.Actions.GC, this::starredRemoveUnified));
            }
        }

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group);
        popup.getComponent().show(tree, e.getX(), e.getY());
    }

    /**
     * 一伦优化 #4：收集收藏视图下用户多选的 StarredApiNode（不展开 FolderNode）。
     * 与普通视图 getSelectedApis() 对齐语义。
     */
    private List<StarredApiNode> getSelectedStarredApiNodes() {
        List<StarredApiNode> result = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) return result;
        for (TreePath tp : paths) {
            Object node = tp.getLastPathComponent();
            if (!(node instanceof DefaultMutableTreeNode)) continue;
            Object userObj = ((DefaultMutableTreeNode) node).getUserObject();
            if (userObj instanceof StarredApiNode) {
                result.add((StarredApiNode) userObj);
            }
        }
        return result;
    }

    private static AnAction starredAction(String text, Icon icon, Runnable run) {
        return new AnAction(text, text, icon) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { run.run(); }
        };
    }

    // ── 收藏模式操作实现 ──

    private void starredNewFolder() {
        String name = Messages.showInputDialog(project, "文件夹名称：", "新建文件夹",
                Messages.getQuestionIcon(), "新文件夹", null);
        if (name == null || name.isBlank()) return;
        folderService.createFolder(name.trim());
        buildStarredTree();
    }

    private void starredRenameFolder() {
        StarredFolder f = getSelectedStarredFolder();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "重命名"); return; }
        String name = Messages.showInputDialog(project, "新名称：", "重命名文件夹",
                Messages.getQuestionIcon(), f.getName(), null);
        if (name == null || name.isBlank()) return;
        folderService.renameFolder(f.getId(), name.trim());
        buildStarredTree();
    }

    private void starredDeleteFolder() {
        StarredFolder f = getSelectedStarredFolder();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "删除文件夹"); return; }
        int ret = Messages.showYesNoDialog(project, "删除「" + f.getName() + "」？",
                "删除文件夹", Messages.getQuestionIcon());
        if (ret != Messages.YES) return;
        folderService.deleteFolder(f.getId());
        buildStarredTree();
    }

    /**
     * 批量收藏：把多个选中接口一次性加入目标文件夹。
     * 对话框显示每个文件夹的已含数量（如 2/5），全部已加入时标 ✓；同文件夹内自动去重。
     */
    private void addApisToFolderDialog(java.util.List<ApiDefinition> apis) {
        if (apis == null || apis.isEmpty()) return;
        List<StarredFolder> folders = folderService.loadFolders();
        if (folders.isEmpty()) {
            Messages.showInfoMessage(project, "暂无收藏文件夹", "收藏");
            return;
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (ApiDefinition a : apis) keys.add(a.uniqueKey());
        FolderPicker picker = new FolderPicker(project, folders, keys.iterator().next(), keys);
        if (!picker.showAndGet()) return;
        List<StarredFolder> picked = picker.getSelected();
        if (picked.isEmpty()) return;
        for (StarredFolder f : picked) {
            for (String key : keys) folderService.addApiToFolder(f.getId(), key);
        }
        for (ApiDefinition a : apis) a.setStarred(true);
        tree.repaint();
    }

    /** 添加到收藏文件夹的多选对话框：带搜索、已加入标记，可一次加入多个文件夹。 */
    private static final class FolderPicker extends com.intellij.openapi.ui.DialogWrapper {
        private final java.util.List<StarredFolder> folders;
        private final String apiKey;
        /** v2.0.0 批量收藏：多接口场景下的 key 集合（单接口时为 null，走 apiKey 分支） */
        private final java.util.Set<String> apiKeySet;
        private final DefaultListModel<StarredFolder> model = new DefaultListModel<>();
        private final JBList<StarredFolder> list = new JBList<>(model);
        private final JTextField searchField = new JTextField();

        /** 单接口构造（向后兼容） */
        FolderPicker(Project project, java.util.List<StarredFolder> folders, String apiKey) {
            this(project, folders, apiKey, null);
        }

        /**
         * v2.0.0 批量收藏构造。
         * @param apiKeySet 多接口的 uniqueKey 集合；非 null 时按"全部已加入"判断 ✓ 标记
         */
        FolderPicker(Project project, java.util.List<StarredFolder> folders,
                     String apiKey, java.util.Set<String> apiKeySet) {
            super(project);
            this.folders = folders;
            this.apiKey = apiKey;
            this.apiKeySet = apiKeySet;
            setTitle("添加到收藏文件夹");
            setOKButtonText("添加到所选文件夹");
            init();
            list.setCellRenderer((l, f, idx, sel, focus) -> {
                boolean in = isAllInFolder(f);
                String prefix = in ? "✓ " : "";
                int total = f.getApiKeys() == null ? 0 : f.getApiKeys().size();
                String suffix = "  (" + total + " 个)";
                JBLabel label = new JBLabel(prefix + f.getName() + suffix);
                if (in) {
                    label.setIcon(AllIcons.Actions.Checked);
                } else {
                    label.setIcon(AllIcons.Nodes.Folder);
                }
                label.setIconTextGap(6);
                label.setOpaque(true);
                if (sel) {
                    label.setBackground(UIManager.getColor("Tree.selectionBackground"));
                    label.setForeground(UIManager.getColor("Tree.selectionForeground"));
                } else if (in) {
                    label.setForeground(JBColor.GRAY);
                }
                return label;
            });
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            applyFilter("");
            searchField.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override protected void textChanged(@NotNull DocumentEvent e) {
                    applyFilter(searchField.getText());
                }
            });
        }

        /** 单接口：apiKey 在文件夹内；多接口：所有 apiKey 都在文件夹内 */
        private boolean isAllInFolder(StarredFolder f) {
            if (apiKeySet != null) return f.getApiKeys().containsAll(apiKeySet);
            return f.getApiKeys().contains(apiKey);
        }

        /** 多接口场景：该文件夹已包含多少个待加接口 */
        private int countInFolder(StarredFolder f) {
            if (apiKeySet == null) return f.getApiKeys().contains(apiKey) ? 1 : 0;
            int c = 0;
            for (String k : apiKeySet) if (f.getApiKeys().contains(k)) c++;
            return c;
        }

        private void applyFilter(String text) {
            model.clear();
            String t = text == null ? "" : text.trim().toLowerCase();
            for (StarredFolder f : folders) {
                if (t.isEmpty() || f.getName().toLowerCase().contains(t)) model.addElement(f);
            }
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(JBUI.size(480, 360));
            JPanel top = new JPanel(new BorderLayout(4, 4));
            top.setBorder(JBUI.Borders.empty(4));
            top.add(new JBLabel("搜索文件夹："), BorderLayout.WEST);
            top.add(searchField, BorderLayout.CENTER);
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JBScrollPane(list), BorderLayout.CENTER);
            JBLabel hint = new JBLabel("可按住 Cmd/Ctrl 多选；标记 ✓ 表示该接口已在此文件夹中（将被跳过）");
            hint.setForeground(JBColor.GRAY);
            hint.setBorder(JBUI.Borders.empty(4, 4, 4, 4));
            panel.add(hint, BorderLayout.SOUTH);
            return panel;
        }

        java.util.List<StarredFolder> getSelected() {
            return list.getSelectedValuesList();
        }
    }

    private void starredRemoveApi() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        folderService.removeApiFromFolder(n.folderId, n.api.uniqueKey());
        buildStarredTree();
    }

    private void starredCopyTo() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        List<StarredFolder> folders = folderService.loadFolders().stream()
                .filter(f -> !f.getId().equals(n.folderId))
                .collect(Collectors.toList());
        if (folders.isEmpty()) return;
        String[] names = folders.stream().map(StarredFolder::getName).toArray(String[]::new);
        Object choice = JOptionPane.showInputDialog(tree, "复制到哪个文件夹？", "复制接口",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (choice == null) return;
        int ret = Arrays.asList(names).indexOf(choice);
        if (ret < 0 || ret >= folders.size()) return;
        folderService.addApiToFolder(folders.get(ret).getId(), n.api.uniqueKey());
        buildStarredTree();
    }

    private void starredMoveTo() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        List<StarredFolder> folders = folderService.loadFolders().stream()
                .filter(f -> !f.getId().equals(n.folderId))
                .collect(Collectors.toList());
        if (folders.isEmpty()) return;
        String[] names = folders.stream().map(StarredFolder::getName).toArray(String[]::new);
        Object choice = JOptionPane.showInputDialog(tree, "移动到哪个文件夹？", "移动接口",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (choice == null) return;
        int ret = Arrays.asList(names).indexOf(choice);
        if (ret < 0 || ret >= folders.size()) return;
        folderService.moveApi(n.api.uniqueKey(), n.folderId, folders.get(ret).getId());
        buildStarredTree();
    }

    /** 一伦优化 v37：「移动到…」统一入口——按选中数量自动分流单选/批量逻辑。 */
    private void starredMoveToUnified() {
        if (getSelectedStarredApiNodes().size() > 1) starredBatchMoveTo();
        else starredMoveTo();
    }

    /** 一伦优化 v37：「移除」统一入口——按选中数量自动分流单选/批量逻辑。 */
    private void starredRemoveUnified() {
        if (getSelectedStarredApiNodes().size() > 1) starredBatchRemove();
        else starredRemoveApi();
    }

    private void starredEditParams() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        Map<String, String> existing = folderService.getParams(n.folderId, n.api.uniqueKey());
        Map<String, String> editable = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
        if (editable.isEmpty()) {
            for (ApiParameter p : n.api.getParameters()) editable.put(p.getName(), "");
        }
        // 简单的多行文本编辑：每行 key=value
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : editable.entrySet()) {
            sb.append(en.getKey()).append('=').append(en.getValue() == null ? "" : en.getValue()).append('\n');
        }
        // area 提到外部以便取值（匿名 DialogWrapper 内部方法无法从外部直接调用）
        final javax.swing.JTextArea area = new javax.swing.JTextArea(sb.toString());
        com.intellij.openapi.ui.DialogWrapper dlg = new com.intellij.openapi.ui.DialogWrapper(project) {
            {
                setTitle("编辑测试参数（每行 key=value）");
                init();
            }
            @Override protected JComponent createCenterPanel() {
                javax.swing.JPanel p = new javax.swing.JPanel(new BorderLayout());
                p.setPreferredSize(JBUI.size(460, 360));
                p.add(new javax.swing.JLabel("每行一个参数，格式 key=value："), BorderLayout.NORTH);
                p.add(new javax.swing.JScrollPane(area), BorderLayout.CENTER);
                return p;
            }
        };
        if (dlg.showAndGet()) {
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : area.getText().split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1);
                if (!k.isEmpty()) result.put(k, v);
            }
            folderService.setParams(n.folderId, n.api.uniqueKey(), result);
            statsLabel.setText("已保存参数：" + n.api.getUrl());
        }
    }

    private void starredClearWarning() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        folderService.clearWarning(n.folderId, n.api.uniqueKey());
        buildStarredTree();
    }

    private void starredCopyUrl() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n == null) return;
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(n.api.getUrl());
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
    }

    private void starredDebugApi() {
        StarredApiNode n = getSelectedStarredApiNode();
        if (n != null && onApiSelected != null) onApiSelected.accept(n.api);
    }

    private void starredBatchAiGen() {
        refreshStarredApiIndex();
        StarredFolder f = getSelectedStarredFolder();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "AI生成参数"); return; }
        List<ApiDefinition> targets = new ArrayList<>();
        for (String key : f.getApiKeys()) {
            ApiDefinition api = starredApiByKey.get(key);
            if (api != null) targets.add(api);
        }
        if (targets.isEmpty()) { Messages.showInfoMessage(project, "该文件夹无接口", "AI生成参数"); return; }
        int ret = Messages.showYesNoDialog(project,
                "将对「" + f.getName() + "」内 " + targets.size() + " 个接口调用 AI 生成参数，是否继续？",
                "AI生成参数", Messages.getQuestionIcon());
        if (ret != Messages.YES) return;

        final String folderId = f.getId();
        statsLabel.setText("AI 生成参数中（0/" + targets.size() + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < targets.size(); i++) {
                final ApiDefinition api = targets.get(i);
                final int idx = i + 1;
                try {
                    AiParameterService.GenerateResult gr = aiService.generateParametersWithRaw(
                            api, AiParameterService.TestScenario.NORMAL);
                    Map<String, String> params = null;
                    if (gr != null && gr.getParameters() != null && !gr.getParameters().isEmpty()) {
                        params = gr.getParameters().get(0);
                    } else {
                        params = aiService.generateDefaultParameters(api);
                    }
                    folderService.setParams(folderId, api.uniqueKey(), params);
                    ok++;
                    final int okNow = ok;
                    SwingUtilities.invokeLater(() ->
                            statsLabel.setText("AI 生成参数中（" + idx + "/" + targets.size() + "）… 已成功 " + okNow));
                } catch (Exception ex) {
                    fail++;
                }
            }
            final int okF = ok, failF = fail;
            SwingUtilities.invokeLater(() -> {
                buildStarredTree();
                statsLabel.setText("AI 生成完成：成功 " + okF + " · 失败 " + failF);
            });
        });
    }

    private void starredBatchTest() {
        refreshStarredApiIndex();
        StarredFolder f = getSelectedStarredFolder();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "批量测试"); return; }
        List<ApiDefinition> targets = new ArrayList<>();
        for (String key : f.getApiKeys()) {
            ApiDefinition api = starredApiByKey.get(key);
            if (api != null) targets.add(api);
        }
        if (targets.isEmpty()) { Messages.showInfoMessage(project, "该文件夹无接口", "批量测试"); return; }

        final String folderId = f.getId();
        final String baseUrl = RestAutoLabSettingsState.getInstance(project).getBaseUrl();
        statsLabel.setText("批量测试中（0/" + targets.size() + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int passed = 0, failed = 0;
            for (int i = 0; i < targets.size(); i++) {
                final ApiDefinition api = targets.get(i);
                Map<String, String> params = folderService.getParams(folderId, api.uniqueKey());
                if (params == null) params = aiService.generateDefaultParameters(api);
                final int idx = i + 1;
                FolderApiStatus status = new FolderApiStatus();
                try {
                    TestResult tr = httpService.executeRequest(api, baseUrl, params);
                    status.setPassed(tr.getStatus() == TestStatus.PASSED);
                    status.setStatusCode(tr.getStatusCode());
                    status.setMessage(status.isPassed() ? "通过" : ("未通过 HTTP " + tr.getStatusCode()));
                } catch (Exception ex) {
                    status.setPassed(false);
                    status.setStatusCode(-1);
                    status.setMessage("请求异常：" + ex.getMessage());
                }
                status.setManuallyCleared(false);
                status.setTestedAt(System.currentTimeMillis());
                folderService.setStatus(folderId, api.uniqueKey(), status);
                if (status.isPassed()) passed++; else failed++;
                final int pNow = passed, fNow = failed;
                SwingUtilities.invokeLater(() ->
                        statsLabel.setText("批量测试中（" + idx + "/" + targets.size() + "）… 通过 " + pNow + " · 失败 " + fNow));
            }
            final int passedF = passed, failedF = failed;
            SwingUtilities.invokeLater(() -> {
                buildStarredTree();
                statsLabel.setText("批量测试完成：通过 " + passedF + " · 失败 " + failedF);
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 一伦优化 #4：收藏视图 多选 StarredApiNode 批量操作
    //  - 批量测试：跨文件夹执行（每个 API 用自己文件夹的 params）
    //  - 批量移动到：把所有选中的 API 一次性移到目标文件夹（同文件夹内自动跳过）
    //  - 批量删除：从各自所属文件夹中移除选中 API
    // ═══════════════════════════════════════════════════════════════

    /** 批量测试：跨文件夹对多选 StarredApiNode 顺序执行。 */
    private void starredBatchTestSelected() {
        refreshStarredApiIndex();
        List<StarredApiNode> selected = getSelectedStarredApiNodes();
        if (selected.size() < 2) {
            Messages.showInfoMessage(project, "批量测试需要至少 2 个接口", "提示");
            return;
        }
        final List<StarredApiNode> targets = new ArrayList<>(selected);
        final String baseUrl = RestAutoLabSettingsState.getInstance(project).getBaseUrl();
        statsLabel.setText("批量测试中（0/" + targets.size() + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int passed = 0, failed = 0;
            for (int i = 0; i < targets.size(); i++) {
                final StarredApiNode n = targets.get(i);
                ApiDefinition api = starredApiByKey.get(n.api.uniqueKey());
                if (api == null) continue;
                Map<String, String> params = folderService.getParams(n.folderId, api.uniqueKey());
                if (params == null) params = aiService.generateDefaultParameters(api);
                final int idx = i + 1;
                FolderApiStatus status = new FolderApiStatus();
                try {
                    TestResult tr = httpService.executeRequest(api, baseUrl, params);
                    status.setPassed(tr.getStatus() == TestStatus.PASSED);
                    status.setStatusCode(tr.getStatusCode());
                    status.setMessage(status.isPassed() ? "通过" : ("未通过 HTTP " + tr.getStatusCode()));
                } catch (Exception ex) {
                    status.setPassed(false);
                    status.setStatusCode(-1);
                    status.setMessage("请求异常：" + ex.getMessage());
                }
                status.setManuallyCleared(false);
                status.setTestedAt(System.currentTimeMillis());
                folderService.setStatus(n.folderId, api.uniqueKey(), status);
                if (status.isPassed()) passed++; else failed++;
                final int pNow = passed, fNow = failed;
                SwingUtilities.invokeLater(() ->
                        statsLabel.setText("批量测试中（" + idx + "/" + targets.size() + "）… 通过 " + pNow + " · 失败 " + fNow));
            }
            final int passedF = passed, failedF = failed;
            SwingUtilities.invokeLater(() -> {
                buildStarredTree();
                statsLabel.setText("批量测试完成：通过 " + passedF + " · 失败 " + failedF);
            });
        });
    }

    /** 批量移动：把多选 API 一次性移到目标文件夹。 */
    private void starredBatchMoveTo() {
        refreshStarredApiIndex();
        List<StarredApiNode> selected = getSelectedStarredApiNodes();
        if (selected.size() < 2) {
            Messages.showInfoMessage(project, "批量移动需要至少 2 个接口", "提示");
            return;
        }
        List<StarredFolder> folders = folderService.loadFolders();
        if (folders.isEmpty()) {
            Messages.showInfoMessage(project, "暂无收藏文件夹", "批量移动");
            return;
        }
        // 排除所有选中 API 所属的文件夹（无意义），保留可作为目标的文件夹
        java.util.Set<String> sourceIds = new java.util.HashSet<>();
        for (StarredApiNode n : selected) sourceIds.add(n.folderId);
        List<StarredFolder> candidates = folders.stream()
                .filter(f -> !sourceIds.contains(f.getId()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "选中的接口已覆盖全部文件夹，无可移入目标", "批量移动");
            return;
        }
        // 弹一个简单选择对话框（不重复造轮子，用 JOptionPane）
        String[] names = candidates.stream().map(StarredFolder::getName).toArray(String[]::new);
        Object choice = JOptionPane.showInputDialog(tree,
                "将 " + selected.size() + " 个接口移动到哪个文件夹？",
                "批量移动",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (choice == null) return;
        int idx = java.util.Arrays.asList(names).indexOf(choice);
        if (idx < 0 || idx >= candidates.size()) return;
        StarredFolder target = candidates.get(idx);
        int ok = 0, skip = 0;
        for (StarredApiNode n : selected) {
            if (n.folderId.equals(target.getId())) { skip++; continue; }
            // 目标里已有同 API：跳过（移动语义），保持与单接口 moveTo 一致
            if (target.getApiKeys().contains(n.api.uniqueKey())) { skip++; continue; }
            boolean moved = folderService.moveApi(n.api.uniqueKey(), n.folderId, target.getId());
            if (moved) ok++;
        }
        buildStarredTree();
        Messages.showInfoMessage(project,
                "已移动 " + ok + " 个到「" + target.getName() + "」" +
                        (skip > 0 ? "（" + skip + " 个已存在或同文件夹，已跳过）" : ""),
                "批量移动完成");
    }

    /** 批量删除：从各自所属文件夹中移除多选 API（v37 起也作为单选「移除」的实际执行体）。 */
    private void starredBatchRemove() {
        List<StarredApiNode> selected = getSelectedStarredApiNodes();
        if (selected.isEmpty()) return;
        String title = selected.size() > 1 ? "批量删除" : "移除收藏";
        int ret = Messages.showYesNoDialog(project,
                "将从各自所属文件夹移除 " + selected.size() + " 个接口，是否继续？\n" +
                        "（仅从收藏移除，不会删除源码中的接口）",
                title, Messages.getQuestionIcon());
        if (ret != Messages.YES) return;
        int ok = 0;
        for (StarredApiNode n : selected) {
            try {
                folderService.removeApiFromFolder(n.folderId, n.api.uniqueKey());
                ok++;
            } catch (Exception ex) {
                LOG.warn("批量删除失败: " + n.api.getUrl(), ex);
            }
        }
        buildStarredTree();
        Messages.showInfoMessage(project, "已从收藏移除 " + ok + " 个接口", title + "完成");
    }

    // ═══════════════════════════════════════════════════════════
    // 依赖链批量测试
    // ═══════════════════════════════════════════════════════════

    /**
     * 依赖链批量测试 - 普通模式多选入口
     * 1. 获取选中的 API 列表
     * 2. 自动检测依赖关系
     * 3. 弹对话框让用户确认/编辑
     * 4. 生成默认参数
     * 5. 按依赖链执行测试
     */
    private void runChainBatchTest() {
        java.util.List<ApiDefinition> selected = getSelectedApis();
        if (selected.isEmpty()) {
            ApiDefinition single = getSelectedApi();
            if (single != null) selected = java.util.Collections.singletonList(single);
        }
        if (selected.size() < 2) {
            Messages.showInfoMessage(project, "依赖链测试需要至少选择 2 个接口", "提示");
            return;
        }

        // 检测依赖
        java.util.List<ApiDependency> deps =
                DependencyDetector.detect(selected);

        // 弹对话框确认
        DependencyGraphDialog dialog = new DependencyGraphDialog(project, selected, deps);
        if (!dialog.showAndGet()) return;
        deps = dialog.getDependencies();

        // 构建测试配置
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        final String baseUrl = settings.getBaseUrl();
        final Environment env = settings.getActiveEnvironmentObj();
        final TestProfile profile = new TestProfile("依赖链测试", baseUrl);

        // 生成默认参数
        for (ApiDefinition api : selected) {
            Map<String, String> params = aiService.generateDefaultParameters(api);
            profile.setParams(api.uniqueKey(), params);
        }

        final java.util.List<ApiDefinition> apis = selected;
        final java.util.List<ApiDependency> finalDeps = deps;
        final int total = apis.size();

        statsLabel.setText("依赖链测试中（0/" + total + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ChainTestExecutor chain =
                    ChainTestExecutor.getInstance(project);
            TestReport report = chain.execute(apis, finalDeps, profile, env,
                    (result, cur, t) -> {
                        String icon = result.getStatus() == TestStatus.PASSED ? "✅"
                                : result.getStatus() == TestStatus.SKIPPED ? "⊘"
                                : result.getStatus() == TestStatus.ERROR ? "⚠" : "❌";
                        SwingUtilities.invokeLater(() ->
                                statsLabel.setText("依赖链测试中（" + cur + "/" + t + "）… " + icon + " " +
                                        result.getApiDefinition().displayLabel()));
                    });

            final int passed = report.getPassedCount();
            final int failed = report.getFailedCount();
            final int skipped = report.getSkippedCount();
            SwingUtilities.invokeLater(() -> {
                statsLabel.setText("依赖链测试完成: 通过 " + passed + " · 失败 " + failed + " · 跳过 " + skipped);
                String summary = report.generateSummary();
                Messages.showInfoMessage(project, summary, "依赖链测试报告");
            });
        });
    }

    /**
     * 依赖链AI生成参数 - 为选中接口生成参数并显示依赖映射
     */
    private void runChainAiGen() {
        java.util.List<ApiDefinition> selected = getSelectedApis();
        if (selected.isEmpty()) {
            ApiDefinition single = getSelectedApi();
            if (single != null) selected = java.util.Collections.singletonList(single);
        }
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "请先选择接口", "提示");
            return;
        }

        // 检测依赖
        java.util.List<ApiDependency> deps =
                DependencyDetector.detect(selected);

        // 弹对话框确认
        DependencyGraphDialog dialog = new DependencyGraphDialog(project, selected, deps);
        if (!dialog.showAndGet()) return;
        deps = dialog.getDependencies();

        // 统计自动填充参数数
        int autoFilledCount = 0;
        for (ApiDependency dep : deps) {
            autoFilledCount += dep.getMappings().size();
        }

        final java.util.List<ApiDefinition> apis = selected;
        final int depCount = deps.size();
        final int filledCount = autoFilledCount;
        final java.util.List<ApiDependency> finalDeps = deps;

        statsLabel.setText("AI 生成参数中（0/" + apis.size() + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < apis.size(); i++) {
                final ApiDefinition api = apis.get(i);
                final int idx = i + 1;
                try {
                    AiParameterService.GenerateResult gr = aiService.generateParametersWithRaw(
                            api, AiParameterService.TestScenario.NORMAL);
                    if (gr != null && gr.getParameters() != null && !gr.getParameters().isEmpty()) {
                        ok++;
                    } else {
                        aiService.generateDefaultParameters(api);
                        ok++;
                    }
                } catch (Exception ex) {
                    fail++;
                }
                final int okNow = ok;
                SwingUtilities.invokeLater(() ->
                        statsLabel.setText("AI 生成参数中（" + idx + "/" + apis.size() + "）… 已成功 " + okNow));
            }

            final int okF = ok, failF = fail;
            SwingUtilities.invokeLater(() -> {
                statsLabel.setText("AI 生成完成: 成功 " + okF + " · 失败 " + failF);
                String msg = "AI 生成参数完成: 成功 " + okF + " · 失败 " + failF
                        + "\n检测到 " + depCount + " 条依赖关系"
                        + "\n" + filledCount + " 个参数将在依赖链测试时自动从上游填充";
                Messages.showInfoMessage(project, msg, "依赖链AI生成参数");
            });
        });
    }

    /**
     * 依赖链批量测试 - 收藏文件夹入口
     * 从收藏文件夹加载 API 和已保存的参数，检测依赖后执行
     */
    private void starredChainBatchTest() {
        refreshStarredApiIndex();
        StarredFolder f = getSelectedStarredFolder();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "依赖链批量测试"); return; }
        java.util.List<ApiDefinition> targets = new ArrayList<>();
        for (String key : f.getApiKeys()) {
            ApiDefinition api = starredApiByKey.get(key);
            if (api != null) targets.add(api);
        }
        if (targets.size() < 2) {
            Messages.showInfoMessage(project, "依赖链测试需要至少 2 个接口", "提示");
            return;
        }

        // 检测依赖
        java.util.List<ApiDependency> deps =
                DependencyDetector.detect(targets);

        // 弹对话框确认
        DependencyGraphDialog dialog = new DependencyGraphDialog(project, targets, deps);
        if (!dialog.showAndGet()) return;
        deps = dialog.getDependencies();

        final String folderId = f.getId();
        final String baseUrl = RestAutoLabSettingsState.getInstance(project).getBaseUrl();
        final Environment env =
                RestAutoLabSettingsState.getInstance(project).getActiveEnvironmentObj();
        final TestProfile profile = new TestProfile("依赖链测试", baseUrl);

        // 从文件夹加载已保存的参数，没有则生成默认值
        for (ApiDefinition api : targets) {
            Map<String, String> params = folderService.getParams(folderId, api.uniqueKey());
            if (params == null || params.isEmpty()) {
                params = aiService.generateDefaultParameters(api);
            }
            profile.setParams(api.uniqueKey(), params);
        }

        final java.util.List<ApiDefinition> apis = targets;
        final java.util.List<ApiDependency> finalDeps = deps;
        final int total = apis.size();

        statsLabel.setText("依赖链测试中（0/" + total + "）…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ChainTestExecutor chain =
                    ChainTestExecutor.getInstance(project);
            TestReport report = chain.execute(apis, finalDeps, profile, env,
                    (result, cur, t) -> {
                        String icon = result.getStatus() == TestStatus.PASSED ? "✅"
                                : result.getStatus() == TestStatus.SKIPPED ? "⊘"
                                : result.getStatus() == TestStatus.ERROR ? "⚠" : "❌";
                        SwingUtilities.invokeLater(() ->
                                statsLabel.setText("依赖链测试中（" + cur + "/" + t + "）… " + icon + " " +
                                        result.getApiDefinition().displayLabel()));
                    });

            final int passed = report.getPassedCount();
            final int failed = report.getFailedCount();
            final int skipped = report.getSkippedCount();
            SwingUtilities.invokeLater(() -> {
                buildStarredTree();
                statsLabel.setText("依赖链测试完成: 通过 " + passed + " · 失败 " + failed + " · 跳过 " + skipped);
            });
        });
    }

    /**
     * 切换空状态面板的显示
     */
    private void toggleEmptyState(boolean showEmpty) {
        Container parent = tree.getParent();
        if (parent instanceof JBScrollPane) {
            Container cardPanel = parent.getParent();
            if (cardPanel != null && cardPanel.getLayout() instanceof CardLayout) {
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                cl.show(cardPanel, showEmpty ? "empty" : "tree");
            }
        }
    }

    /**
     * 扫描完成通知：失效「最新」过滤的缓存。
     * <p>扫描后接口方法体可能变化（调用链、引用类改变），需重新计算关联文件；
     * 同时 Git 变更文件也可能有新提交，一并失效变更缓存。</p>
     */
    public void markScanTimestamp() {
        latestChangedApis = null;
        try {
            ApiChangeDetector.getInstance(project).onScanComplete();
            ApiChangeDetector.getInstance(project).invalidateChangedFilesCache();
        } catch (Exception e) {
            LOG.warn("失效最新过滤缓存失败: " + e.getMessage());
        }
        // 若当前正在看「最新」，重新触发计算
        if (FILTER_LATEST.equals(currentFilter)) {
            triggerLatestFilter();
        }
    }

    /**
     * 触发「最新」过滤：在后台线程用 {@link ApiChangeDetector} 计算最近 {@link #LATEST_CHANGE_DAYS}
     * 天有 Git 变更的接口，计算完成后回 EDT 刷新树。
     * <p>git log 与 PSI 读取耗时，必须放后台线程；用 {@link #latestComputing} 标志避免重复触发。</p>
     */
    private void triggerLatestFilter() {
        // 已有缓存直接用
        if (latestChangedApis != null) {
            applyFilters();
            return;
        }
        if (latestComputing) {
            return; // 正在计算，避免重复触发
        }
        latestComputing = true;
        statsLabel.setText("○ 检测 Git 变更中...");
        final List<ApiDefinition> snapshot = new ArrayList<>(allApis);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<ApiDefinition> changed;
            try {
                changed = ApiChangeDetector.getInstance(project)
                        .filterChangedApis(snapshot, LATEST_CHANGE_DAYS);
            } catch (Exception e) {
                LOG.warn("最新过滤计算失败: " + e.getMessage());
                changed = Collections.emptyList();
            }
            final List<ApiDefinition> result = changed;
            ApplicationManager.getApplication().invokeLater(() -> {
                latestChangedApis = result;
                latestComputing = false;
                // 仅在用户仍停留在「最新」时刷新，避免切到其他分类后又被覆盖
                if (FILTER_LATEST.equals(currentFilter)) {
                    applyFilters();
                    if (result.isEmpty()) {
                        statsLabel.setText("○ 最近1个月无 Git 变更接口");
                    } else {
                        statsLabel.setText("● 共 " + result.size() + " 个接口近1个月有变更");
                    }
                }
            }, ModalityState.defaultModalityState());
        });
    }

    /**
     * 获取当前选中的API定义
     * @return 选中的ApiDefinition，未选中或非API节点时返回null
     */
    public ApiDefinition getSelectedApi() {
        Object selectedNode = tree.getLastSelectedPathComponent();
        if (!(selectedNode instanceof DefaultMutableTreeNode)) return null;
        Object userObject = ((DefaultMutableTreeNode) selectedNode).getUserObject();
        if (userObject instanceof ApiDefinition) {
            return (ApiDefinition) userObject;
        }
        return null;
    }

    /**
     * 获取用户在接口树中**严格选中**的接口列表（多选语义）。
     * <ul>
     *   <li>仅收集用户真正点击选中的 API 节点；不展开 Controller 节点</li>
     *   <li>不静默回退到 "最后选中的" 单选接口 —— 没多选就返回空列表</li>
     *   <li>按树路径顺序排序（按 Controller 分组、按 url 排序），保持导出稳定</li>
     * </ul>
     * 如果希望单选也能导出，请用 {@link #getSelectedApisForExport()}。
     */
    public java.util.List<ApiDefinition> getSelectedApis() {
        java.util.List<ApiDefinition> result = new java.util.ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) {
            return result;
        }
        for (TreePath tp : paths) {
            Object node = tp.getLastPathComponent();
            if (!(node instanceof DefaultMutableTreeNode)) continue;
            Object userObj = ((DefaultMutableTreeNode) node).getUserObject();
            if (userObj instanceof ApiDefinition) {
                result.add((ApiDefinition) userObj);
            }
            // Controller 节点不展开 — 严格只导出用户明确点击的接口
        }
        // 去重（按 ApiDefinition 自身 hashCode/equals）—— 用户不可能多选同节点但防御下
        java.util.LinkedHashSet<ApiDefinition> uniq = new java.util.LinkedHashSet<>(result);
        return new java.util.ArrayList<>(uniq);
    }

    /**
     * 导出场景下使用的"选中接口"获取：
     * <ul>
     *   <li>若用户在树中选了 1+ 个节点（多选）→ 返回严格多选的接口列表（不含 Controller 展开）</li>
     *   <li>若用户只单选了一个接口 → 也返回该接口（单选导出）</li>
     *   <li>若用户没选任何节点 → 弹提示框说明，回退到当前聚焦节点；再不行返回空</li>
     * </ul>
     */
    public java.util.List<ApiDefinition> getSelectedApisForExport() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null && paths.length > 1) {
            // 多选：严格返回多选接口
            return getSelectedApis();
        }
        // 单选/未选：尝试用当前最后选中的节点
        ApiDefinition single = getSelectedApi();
        if (single != null) {
            java.util.List<ApiDefinition> one = new java.util.ArrayList<>();
            one.add(single);
            return one;
        }
        return new java.util.ArrayList<>();
    }

    /**
     * 导出选中的接口（支持单选/多选）为 Markdown 文档（含最近测试数据）。
     * <ul>
     *   <li>文件命名精确到秒：<code>acai-api-yyyyMMdd-HHmmss.md</code></li>
     *   <li>严格按用户在接口树中**明确选中**的节点生成；不展开 Controller 节点</li>
     *   <li>导出前弹出确认框，列出要导出的接口（按 Controller 分组），用户可取消</li>
     * </ul>
     */
    private void exportSelectedApisAsMarkdown() {
        java.util.List<ApiDefinition> selected = getSelectedApisForExport();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n\n操作方式：\n• 单选 1 个接口后右键 → 导出 Markdown\n• 按住 Cmd/Ctrl 多选接口后再右键 → 导出 Markdown\n• Shift 连选接口后再右键 → 导出 Markdown",
                    "提示");
            return;
        }

        // === 二次确认：按 Controller 分组列出即将导出的接口 ===
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

        // === 文件名精确到秒：acai-api-20260708-153022.md ===
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-api-" + sdf.format(new java.util.Date()) + ".md";

        // 用 FileChooser.chooseFile 弹目录选择框（与导入同一套机制），跨平台一致。
        // 不用 FileSaverDescriptor/createSaveFileDialog：Windows 上原生保存对话框常弹不出。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            java.util.List<RequestHistory> history = settings.loadRequestHistory();
            try {
                ApiDocExporter.exportSelectedApisWithHistory(selected, history, project.getName(), outputPath);
                Messages.showInfoMessage(project,
                        "已导出 " + selected.size() + " 个接口到:\n" + outputPath,
                        "导出成功");
            } catch (Exception ex) {
                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.API_DOC, ex);
            }
        }, ModalityState.defaultModalityState());
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 导出选中的接口（支持单选/多选）为 Word 文档，使用内置「设计开发接口模版」：
     * 接口设计标题 + 接口名称/地址 + 接口入参/出参三列表格（字段名/类型/注释），
     * DTO 等嵌套对象的全部字段以点号路径展开。
     */
    private void exportSelectedApisAsWord() {
        java.util.List<ApiDefinition> selected = getSelectedApisForExport();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n\n操作方式：\n• 单选 1 个接口后右键 → 导出 Word\n• 按住 Cmd/Ctrl 多选接口后再右键 → 导出 Word\n• Shift 连选接口后再右键 → 导出 Word",
                    "提示");
            return;
        }

        // 二次确认：按 Controller 分组列出即将导出的接口
        StringBuilder preview = new StringBuilder();
        preview.append("<html><body style='width:480px;font-family:Menlo,Monaco,monospace;font-size:11px;'>")
                .append("即将导出 <b>").append(selected.size())
                .append("</b> 个接口到 Word 文档（内置设计开发接口模版）：<br/><br/>");
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
                "确认导出 - Word", new String[]{"导出", "取消"}, 0,
                AllIcons.Actions.Help);
        if (ok != 0) return;

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-api-" + sdf.format(new java.util.Date()) + ".docx";

        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            String out = outputPath.toLowerCase().endsWith(".docx") ? outputPath : outputPath + ".docx";
            try {
                ApiDocWordExporter.exportWord(selected, project.getName(), out);
                Messages.showInfoMessage(project,
                        "已导出 " + selected.size() + " 个接口到:\n" + out,
                        "导出成功");
            } catch (Exception ex) {
                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.API_DOC, ex);
            }
        }, ModalityState.defaultModalityState());
    }

    /**
     * 导出选中的接口（支持单选/多选）为 Postman / Apifox 可导入的 JSON Collection
     */
    private void exportSelectedApisAsPostmanJson() {
        java.util.List<ApiDefinition> selected = getSelectedApisForExport();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n\n操作方式：\n• 单选 1 个接口后右键 → 导出 Postman JSON\n• 按住 Cmd/Ctrl 多选接口后再右键 → 导出 Postman JSON\n• Shift 连选接口后再右键 → 导出 Postman JSON",
                    "提示");
            return;
        }

        // 二次确认
        StringBuilder preview = new StringBuilder();
        preview.append("<html><body style='width:480px;font-family:Menlo,Monaco,monospace;font-size:11px;'>")
                .append("即将导出 <b>").append(selected.size())
                .append("</b> 个接口到 Postman JSON：<br/><br/>");
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
                "确认导出 - Postman JSON", new String[]{"导出", "取消"}, 0,
                AllIcons.Actions.Help);
        if (ok != 0) return;

        // 文件名精确到秒
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-postman-" + sdf.format(new java.util.Date()) + ".json";

        // 用 FileChooser.chooseFile 弹目录选择框（与导入同一套机制），跨平台一致。
        // 不用 FileSaverDescriptor/createSaveFileDialog：Windows 上原生保存对话框常弹不出。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
            String baseUrl = settings.getBaseUrl();
            List<RequestHistory> history = settings.loadRequestHistory();
            try {
                PostmanCollectionExporter.exportToFile(selected, baseUrl, history, outputPath);
                Messages.showInfoMessage(project,
                        "已导出 " + selected.size() + " 个接口到:\n" + outputPath
                                + "\n\n导入方式：Postman/Apifox → Import → File → 选择此 JSON",
                        "导出成功");
            } catch (Exception ex) {
                ExportErrorReporter.reportExportFailure(project, ExportErrorReporter.Operation.POSTMAN_COLLECTION, ex);
            }
        }, ModalityState.defaultModalityState());
    }

    /**
     * 双击跳转到API源码位置
     */
    private void navigateToSource() {
        ApiDefinition api = getSelectedApi();
        if (api == null) return;
        if (api.getSourceFilePath().isBlank()) return;

        try {
            VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(api.getSourceFilePath());
            if (virtualFile == null) {
                Messages.showWarningDialog(project, "找不到源文件：\n" + api.getSourceFilePath(), "跳转失败");
                return;
            }
            // 行号从 1 开始；非法行号（<=0）退化为不指定行，避免 OpenFileDescriptor 抛 IllegalArgumentException
            int line = api.getSourceLineNumber();
            int offsetLine = line > 0 ? line - 1 : -1;
            OpenFileDescriptor descriptor = offsetLine >= 0
                    ? new OpenFileDescriptor(project, virtualFile, offsetLine, 0)
                    : new OpenFileDescriptor(project, virtualFile);
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        } catch (Exception ex) {
            LOG.warn("跳转到源码失败: " + api.getSourceFilePath(), ex);
            Messages.showErrorDialog(project, "跳转到源码失败：" + ex.getMessage(), "跳转失败");
        }
    }

    /**
     * 根据API定义选中树中对应的节点
     * 用于从Gutter图标或右键菜单定位到API
     */
    public void selectApi(ApiDefinition api) {
        Object rootObj = treeModel.getRoot();
        if (!(rootObj instanceof DefaultMutableTreeNode)) return;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) rootObj;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode controllerNode = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < controllerNode.getChildCount(); j++) {
                DefaultMutableTreeNode apiNode = (DefaultMutableTreeNode) controllerNode.getChildAt(j);
                Object nodeApi = apiNode.getUserObject();
                if (nodeApi instanceof ApiDefinition && ((ApiDefinition) nodeApi).uniqueKey().equals(api.uniqueKey())) {
                    TreePath path = new TreePath(new Object[]{root, controllerNode, apiNode});
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    return;
                }
            }
        }
    }

    // ================================================================
    // 辅助方法：HTTP方法颜色映射
    // ================================================================

    /**
     * 根据HTTP方法名返回对应的主题感知颜色
     * 支持7种HTTP方法: GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS
     */
    static Color getMethodColor(String method) {
        return RestAutoLabConstants.colorForMethod(method);
    }

    /**
     * 将Color转为CSS可用的hex字符串，自动解析JBColor的当前主题颜色
     */
    static String toHex(Color color) {
        // JBColor的getRed/getGreen/getBlue会自动返回当前主题的颜色值
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    // ================================================================
    // 自定义树节点渲染器
    // ================================================================

    /**
     * API树节点渲染器 - 使用HTML渲染彩色方法徽章和差异化节点样式
     *
     * API节点: [METHOD_BADGE] /url/path - 接口名称
     * 控制器节点: 📦 ControllerName (N)
     * 手动API: 灰色文字 + ✋标记
     * 废弃API: 红色文字 + strikethrough
     */
    private static class ApiTreeCellRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                       boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setBorder(null);

            if (!(value instanceof DefaultMutableTreeNode)) return this;
            Object userObj = ((DefaultMutableTreeNode) value).getUserObject();

            // ── 收藏文件夹视图节点 ──
            if (userObj instanceof FolderNode) {
                StarredFolder f = ((FolderNode) userObj).folder;
                setIcon(AllIcons.Nodes.Folder);
                setText("<html><b>" + escapeHtml(f.getName()) + "</b> <span style='color:#888;font-size:10px;'>("
                        + f.getApiKeys().size() + ")</span></html>");
                if (!sel) setForeground(JBColor.foreground());
                return this;
            }
            if (userObj instanceof StarredApiNode) {
                renderStarredApiNode((StarredApiNode) userObj, sel);
                return this;
            }

            if (userObj instanceof ApiDefinition) {
                renderApiNode((ApiDefinition) userObj, sel);
            } else if (userObj instanceof String) {
                renderControllerNode((String) userObj, expanded, sel);
            }

            return this;
        }

        /** 渲染收藏模式下的接口节点：方法徽章 + URL + 测试状态（失败标红/通过标绿） */
        private void renderStarredApiNode(StarredApiNode node, boolean sel) {
            ApiDefinition api = node.api;
            String method = api.getHttpMethod();
            String url = api.getUrl();
            Color methodColor = getMethodColor(method);
            String methodHex = toHex(methodColor);

            FolderApiStatus st = node.status;
            boolean red = st != null && st.shouldHighlightRed();
            boolean green = st != null && st.isPassed() && st.getTestedAt() > 0 && !red;

            String textColor;
            if (red) textColor = sel ? "#FFCCCC" : "#CC0000";
            else if (green) textColor = sel ? "#BBF0BB" : "#2E7D32";
            else textColor = sel ? "#FFFFFF" : toHex(getForeground());

            StringBuilder text = new StringBuilder("<html><span style='background-color:").append(methodHex)
                    .append("; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>").append(method)
                    .append("</span>&nbsp;<span style='color:").append(textColor)
                    .append("; font-size:11px;'>").append(escapeHtml(url)).append("</span>");
            if (red) {
                text.append(" <span style='color:#CC0000;font-size:10px;'>✗ ")
                        .append(escapeHtml(st.getMessage() == null ? "失败" : st.getMessage())).append("</span>");
            } else if (green) {
                text.append(" <span style='color:#2E7D32;font-size:10px;'>✓</span>");
            }
            // 已配置参数标记（蓝色小标签），让用户直观看到参数已持久化
            if (node.hasParams) {
                text.append(" <span style='color:#1565C0;font-size:9px;'>[参数]</span>");
            }
            setText(text.append("</html>").toString());
            setIcon(AllIcons.Nodes.Plugin);
            if (!sel) {
                if (red) setForeground(new JBColor(new Color(0xCC, 0x00, 0x00), new Color(0xFF, 0x88, 0x88)));
                else if (green) setForeground(new JBColor(new Color(0x2E, 0x7D, 0x32), new Color(0x62, 0xBE, 0x62)));
                else setForeground(JBColor.foreground());
            }
        }

        /**
         * 渲染API节点：彩色方法徽章 + URL + 接口说明 + 收藏/变更标记
         */
        private void renderApiNode(ApiDefinition api, boolean sel) {
            String method = api.getHttpMethod();
            String url = api.getUrl();
            String description = api.getDescription();
            Color methodColor = getMethodColor(method);
            String methodHex = toHex(methodColor);
            // Check starred status (restored from settings during scan)
            boolean isStarred = api.isStarred();
            String changeMarker = api.getChangeMarker();

            // 废弃 API：strikethrough + 红色
            if (api.isDeprecated()) {
                String depColor = sel ? "#FFAAAA" : toHex(RestAutoLabConstants.COLOR_TREE_DEPRECATED);
                String text = "<html><span style='background-color:" + methodHex
                        + "; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>" + method
                        + "</span>&nbsp;<span style='color:" + depColor
                        + "; text-decoration:line-through; font-size:11px;'>" + escapeHtml(url) + "</span>";
                if (isStarred) text += " ⭐";
                if (description != null && !description.isBlank()) {
                    text += "&nbsp;<span style='color:" + depColor + "; font-size:10px;'><i>" + escapeHtml(description) + "</i></span>";
                }
                setText(text + "</html>");
                setIcon(AllIcons.General.Warning);
                if (!sel) setForeground(RestAutoLabConstants.COLOR_TREE_DEPRECATED);
                return;
            }

            // 手动 API：灰色文字 + 手势图标
            if (!api.isAutoDetected()) {
                String manualColor = sel ? "#CCCCCC" : toHex(RestAutoLabConstants.COLOR_TREE_MANUAL);
                String text = "<html><span style='background-color:" + methodHex
                        + "; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>" + method
                        + "</span>&nbsp;<span style='color:" + manualColor + "; font-size:11px;'>"
                        + escapeHtml(url) + " \u270b</span>";
                if (isStarred) text += " ⭐";
                if (RestAutoLabConstants.CHANGE_ADDED.equals(changeMarker)) text += " <span style='color:#2E7D32;'>\uD83D\uDF32</span>";
                if (description != null && !description.isBlank()) {
                    text += "&nbsp;<span style='color:" + manualColor + "; font-size:10px;'><i>" + escapeHtml(description) + "</i></span>";
                }
                setText(text + "</html>");
                setIcon(AllIcons.Nodes.Plugin);
                if (!sel) setForeground(RestAutoLabConstants.COLOR_TREE_MANUAL);
                return;
            }

            // 普通自动 API
            String textColor = sel ? "#FFFFFF" : toHex(getForeground());
            String text = "<html><span style='background-color:" + methodHex
                    + "; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>" + method
                    + "</span>&nbsp;<span style='color:" + textColor + "; font-size:11px;'>"
                    + escapeHtml(url) + "</span>";
            if (isStarred) text += " <span style='color:#FFA000;'>⭐</span>";
            if (RestAutoLabConstants.CHANGE_ADDED.equals(changeMarker)) text += " <span style='color:#2E7D32;font-size:10px;'>● 新增</span>";
            if (description != null && !description.isBlank()) {
                text += "&nbsp;<span style='color:" + textColor + "; font-size:10px;'><i>" + escapeHtml(description) + "</i></span>";
            }
            setText(text + "</html>");
            setIcon(null);
        }

        /**
         * 渲染控制器分组节点：图标 + 加粗名称
         */
        private void renderControllerNode(String label, boolean expanded, boolean sel) {
            String textColor = sel ? "#FFFFFF" : toHex(getForeground());
            setText("<html><b style='color:" + textColor + ";'>" + escapeHtml(label) + "</b></html>");
            setIcon(expanded ? AllIcons.Nodes.Module : AllIcons.Nodes.Folder);
        }

        /**
         * HTML实体转义
         */
        private String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
