package com.ban.acai.ui;

import com.ban.acai.AcaiConstants;
import com.ban.acai.model.ApiDefinition;
import com.ban.acai.model.RequestHistory;
import com.ban.acai.scanner.ApiScannerService;
import com.ban.acai.settings.AcaiSettingsState;
import com.ban.acai.util.ApiDocExporter;
import com.ban.acai.util.PostmanCollectionExporter;
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
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * API树形面板 - 以树形结构展示项目中扫描到的所有API接口
 *
 * 功能：
 * 1. 按控制器（Controller）分组展示所有API端点
 * 2. 顶部分类标签：全量 / 自动扫描 / 手动添加 / 最新
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
 * └── 手动添加
 *     └── [POST] /api/custom - 自定义接口 ✋
 */
public class ApiTreePanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(ApiTreePanel.class);

    private final Project project;

    /** 分类标签常量 */
    private static final String FILTER_ALL = "全量";
    private static final String FILTER_AUTO = "自动";
    private static final String FILTER_MANUAL = "手动";
    private static final String FILTER_STARRED = "⭐ 收藏";
    private static final String FILTER_LATEST = "最新";

    /** 树形控件 - 展示API分组和端点 */
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("API列表"));
    private final Tree tree = new Tree(treeModel);

    /** 搜索框 - 过滤API列表 */
    private final SearchTextField searchField = new SearchTextField();

    /** 分类按钮组 */
    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton btnAll = new JToggleButton(FILTER_ALL, AllIcons.General.Filter);
    private final JToggleButton btnAuto = new JToggleButton(FILTER_AUTO, AllIcons.Vcs.Changelist);
    private final JToggleButton btnManual = new JToggleButton(FILTER_MANUAL, AllIcons.Nodes.Plugin);
    private final JToggleButton btnStarred = new JToggleButton(FILTER_STARRED, AllIcons.Nodes.Favorite);
    private final JToggleButton btnLatest = new JToggleButton(FILTER_LATEST, AllIcons.Actions.Refresh);

    /** 统计标签 */
    private final JBLabel statsLabel = new JBLabel("");

    /** API选中回调 - 通知调试面板更新 */
    private Consumer<ApiDefinition> onApiSelected = null;

    /** 全量API列表（未过滤） */
    private List<ApiDefinition> allApis = Collections.emptyList();

    /** 当前分类过滤类型 */
    private String currentFilter = FILTER_ALL;

    /** 最后一次扫描的时间戳（用于"最新"过滤） */
    private long lastScanTimestamp = 0;

    /** 空状态面板 */
    private final JPanel emptyPanel = new JPanel(new GridBagLayout());

    public ApiTreePanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
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

        // 双击事件：跳转到API源码
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSource();
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
                if (onApiSelected != null) onApiSelected.accept(api);
            }
        };
        group.add(debugAction);

        // 收藏/取消收藏
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        boolean isStarred = settings.isApiStarred(api.uniqueKey());
        AnAction starAction = new AnAction(
                isStarred ? "取消收藏" : "⭐ 收藏",
                isStarred ? "从收藏中移除此接口" : "将此接口添加到收藏",
                isStarred ? AllIcons.Nodes.Favorite : AllIcons.Nodes.Favorite) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ApiDefinition selectedApi = getSelectedApi();
                if (selectedApi == null) return;
                AcaiSettingsState s = AcaiSettingsState.getInstance(project);
                if (s.isApiStarred(selectedApi.uniqueKey())) {
                    s.unstarApi(selectedApi.uniqueKey());
                    selectedApi.setStarred(false);
                } else {
                    s.starApi(selectedApi.uniqueKey());
                    selectedApi.setStarred(true);
                }
                tree.repaint();
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
                AcaiSettingsState s = AcaiSettingsState.getInstance(project);
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

        // 导出选中接口（支持多选）
        group.addSeparator();
        AnAction exportMdAction = new AnAction("📄 导出 Markdown（多选）",
                "将选中的接口（含最近测试数据）导出为 Markdown 文档", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportSelectedApisAsMarkdown();
            }
        };
        group.add(exportMdAction);

        AnAction exportPostmanAction = new AnAction("📤 导出 Postman JSON（多选）",
                "将选中的接口导出为 Postman/Apifox 可直接导入的 JSON", AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportSelectedApisAsPostmanJson();
            }
        };
        group.add(exportPostmanAction);

        // 跳转到源码
        group.addSeparator();
        AnAction gotoSourceAction = new AnAction("跳转到源码", "在编辑器中打开此接口所在位置", AllIcons.Actions.EditSource) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                navigateToSource();
            }
        };
        group.add(gotoSourceAction);

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group);
        popup.getComponent().show(tree, e.getX(), e.getY());
    }

    /**
     * 组装面板布局
     */
    private void setupLayout() {
        JPanel topContainer = new JPanel(new BorderLayout(0, 3));
        topContainer.setBorder(JBUI.Borders.empty(4, 6));
        topContainer.add(createFilterPanel(), BorderLayout.NORTH);

        // 配置搜索框
        searchField.getTextEditor().getEmptyText().setText("搜索接口路径、名称、Controller或描述...");
        searchField.setFont(searchField.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_HINT));
        topContainer.add(searchField, BorderLayout.SOUTH);

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
     * 创建过滤器面板 - 紧凑的分段按钮组
     */
    private JPanel createFilterPanel() {
        JPanel filterPanel = new JPanel(new BorderLayout());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        buttonRow.setBorder(JBUI.Borders.emptyBottom(3));
        filterGroup.add(btnAll);
        filterGroup.add(btnAuto);
        filterGroup.add(btnManual);
        filterGroup.add(btnLatest);

        for (JToggleButton btn : new JToggleButton[]{btnAll, btnAuto, btnManual, btnStarred, btnLatest}) {
            styleFilterButton(btn);
            buttonRow.add(btn);
        }

        // 默认选中"全量"
        btnAll.setSelected(true);

        // 过滤器按钮点击事件
        btnAll.addActionListener(e -> { currentFilter = FILTER_ALL; applyFilters(); });
        btnAuto.addActionListener(e -> { currentFilter = FILTER_AUTO; applyFilters(); });
        btnManual.addActionListener(e -> { currentFilter = FILTER_MANUAL; applyFilters(); });
        btnStarred.addActionListener(e -> { currentFilter = FILTER_STARRED; applyFilters(); });
        btnLatest.addActionListener(e -> { currentFilter = FILTER_LATEST; applyFilters(); });

        filterPanel.add(buttonRow, BorderLayout.WEST);
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
            case FILTER_AUTO:
                btn.setToolTipText("仅显示自动扫描的API");
                break;
            case FILTER_MANUAL:
                btn.setToolTipText("仅显示手动添加的API");
                break;
            case FILTER_STARRED:
                btn.setToolTipText("仅显示收藏的API");
                break;
            case FILTER_LATEST:
                btn.setToolTipText("仅显示本次扫描新增的API");
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
        applyFilters();
    }

    /**
     * 应用分类过滤 + 搜索过滤，更新树显示
     */
    private void applyFilters() {
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
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        switch (currentFilter) {
            case FILTER_AUTO:
                return apis.stream()
                        .filter(ApiDefinition::isAutoDetected)
                        .collect(Collectors.toList());
            case FILTER_MANUAL:
                return apis.stream()
                        .filter(api -> !api.isAutoDetected())
                        .collect(Collectors.toList());
            case FILTER_STARRED:
                return apis.stream()
                        .filter(api -> settings.isApiStarred(api.uniqueKey()) || api.isStarred())
                        .collect(Collectors.toList());
            case FILTER_LATEST:
                if (lastScanTimestamp > 0) {
                    return apis.stream()
                            .filter(api -> api.getScanTimestamp() >= lastScanTimestamp
                                    || AcaiConstants.CHANGE_ADDED.equals(api.getChangeMarker()))
                            .collect(Collectors.toList());
                }
                return apis.stream()
                        .filter(api -> AcaiConstants.CHANGE_ADDED.equals(api.getChangeMarker()))
                        .collect(Collectors.toList());
            default:
                return new ArrayList<>(apis);
        }
    }

    /**
     * 更新统计标签
     */
    private void updateStats(List<ApiDefinition> filtered) {
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        long autoCount = allApis.stream().filter(ApiDefinition::isAutoDetected).count();
        long manualCount = allApis.size() - autoCount;
        long starredCount = allApis.stream()
                .filter(api -> settings.isApiStarred(api.uniqueKey()) || api.isStarred()).count();
        statsLabel.setText(String.format(
                "\u2022 全量 %d  \u2022 自动 %d  \u2022 手动 %d  \u2022 ⭐ %d  \u2022 显示 %d",
                allApis.size(), autoCount, manualCount, starredCount, filtered.size()));
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
     * 标记当前扫描时间戳（用于"最新"过滤）
     */
    public void markScanTimestamp() {
        this.lastScanTimestamp = System.currentTimeMillis();
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
                    "未选中任何接口。\n\n操作方式：\n• 单选 1 个接口后右键 → 导出 Markdown（多选）\n• 按住 Cmd/Ctrl 多选接口后再右键 → 导出 Markdown（多选）\n• Shift 连选接口后再右键 → 导出 Markdown（多选）",
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

        // === 文件名精确到秒：acai-api-20260708-153022.md ===
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-api-" + sdf.format(new java.util.Date()) + ".md";

        // 用 FileChooser.chooseFile 弹目录选择框（与导入同一套机制），跨平台一致。
        // 不用 FileSaverDescriptor/createSaveFileDialog：Windows 上原生保存对话框常弹不出。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = com.ban.acai.util.TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
            java.util.List<RequestHistory> history = settings.loadRequestHistory();
            try {
                ApiDocExporter.exportSelectedApisWithHistory(selected, history, project.getName(), outputPath);
                Messages.showInfoMessage(project,
                        "已导出 " + selected.size() + " 个接口到:\n" + outputPath,
                        "导出成功");
            } catch (Exception ex) {
                Messages.showErrorDialog(project, "导出失败: " + ex.getMessage(), "错误");
            }
        }, ModalityState.NON_MODAL);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 导出选中的接口（支持单选/多选）为 Postman / Apifox 可导入的 JSON Collection
     */
    private void exportSelectedApisAsPostmanJson() {
        java.util.List<ApiDefinition> selected = getSelectedApisForExport();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "未选中任何接口。\n\n操作方式：\n• 单选 1 个接口后右键 → 导出 Postman JSON（多选）\n• 按住 Cmd/Ctrl 多选接口后再右键 → 导出 Postman JSON（多选）\n• Shift 连选接口后再右键 → 导出 Postman JSON（多选）",
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
                "确认导出 - Postman JSON", new String[]{"导出", "取消"}, 0,
                AllIcons.Actions.Help);
        if (ok != 0) return;

        // 文件名精确到秒
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
        String suggestName = "acai-postman-" + sdf.format(new java.util.Date()) + ".json";

        // 用 FileChooser.chooseFile 弹目录选择框（与导入同一套机制），跨平台一致。
        // 不用 FileSaverDescriptor/createSaveFileDialog：Windows 上原生保存对话框常弹不出。
        ApplicationManager.getApplication().invokeLater(() -> {
            String outputPath = com.ban.acai.util.TestDataExporter.chooseExportPath(project, suggestName);
            if (outputPath == null) return;
            AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
            String baseUrl = settings.getBaseUrl();
            List<RequestHistory> history = settings.loadRequestHistory();
            try {
                PostmanCollectionExporter.exportToFile(selected, baseUrl, history, outputPath);
                Messages.showInfoMessage(project,
                        "已导出 " + selected.size() + " 个接口到:\n" + outputPath
                                + "\n\n导入方式：Postman/Apifox → Import → File → 选择此 JSON",
                        "导出成功");
            } catch (Exception ex) {
                Messages.showErrorDialog(project, "导出失败: " + ex.getMessage(), "错误");
            }
        }, ModalityState.NON_MODAL);
    }

    /**
     * 双击跳转到API源码位置
     */
    private void navigateToSource() {
        ApiDefinition api = getSelectedApi();
        if (api == null) return;
        if (api.getSourceFilePath().isBlank()) return;

        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(api.getSourceFilePath());
        if (virtualFile == null) return;
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, api.getSourceLineNumber() - 1, 0);
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
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
        return AcaiConstants.colorForMethod(method);
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

            if (userObj instanceof ApiDefinition) {
                renderApiNode((ApiDefinition) userObj, sel);
            } else if (userObj instanceof String) {
                renderControllerNode((String) userObj, expanded, sel);
            }

            return this;
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
                String depColor = sel ? "#FFAAAA" : toHex(AcaiConstants.COLOR_TREE_DEPRECATED);
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
                if (!sel) setForeground(AcaiConstants.COLOR_TREE_DEPRECATED);
                return;
            }

            // 手动 API：灰色文字 + 手势图标
            if (!api.isAutoDetected()) {
                String manualColor = sel ? "#CCCCCC" : toHex(AcaiConstants.COLOR_TREE_MANUAL);
                String text = "<html><span style='background-color:" + methodHex
                        + "; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>" + method
                        + "</span>&nbsp;<span style='color:" + manualColor + "; font-size:11px;'>"
                        + escapeHtml(url) + " \u270b</span>";
                if (isStarred) text += " ⭐";
                if (AcaiConstants.CHANGE_ADDED.equals(changeMarker)) text += " <span style='color:#2E7D32;'>\uD83D\uDF32</span>";
                if (description != null && !description.isBlank()) {
                    text += "&nbsp;<span style='color:" + manualColor + "; font-size:10px;'><i>" + escapeHtml(description) + "</i></span>";
                }
                setText(text + "</html>");
                setIcon(AllIcons.Nodes.Plugin);
                if (!sel) setForeground(AcaiConstants.COLOR_TREE_MANUAL);
                return;
            }

            // 普通自动 API
            String textColor = sel ? "#FFFFFF" : toHex(getForeground());
            String text = "<html><span style='background-color:" + methodHex
                    + "; color:#FFFFFF; font-weight:bold; padding:2px 6px;'>" + method
                    + "</span>&nbsp;<span style='color:" + textColor + "; font-size:11px;'>"
                    + escapeHtml(url) + "</span>";
            if (isStarred) text += " <span style='color:#FFA000;'>⭐</span>";
            if (AcaiConstants.CHANGE_ADDED.equals(changeMarker)) text += " <span style='color:#2E7D32;font-size:10px;'>● 新增</span>";
            if (api.getCallCount() > 0) {
                text += " <span style='color:#999;font-size:9px;'>" + api.getCallCount() + "次</span>";
            }
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