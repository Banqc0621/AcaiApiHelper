package com.ban.acai.ui;

import com.ban.acai.AcaiConstants;
import com.ban.acai.model.ApiDefinition;
import com.ban.acai.scanner.ApiScannerService;
import com.ban.acai.settings.AcaiSettingsState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
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
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);  // 可变行高，适配HTML渲染
        tree.setBorder(JBUI.Borders.emptyLeft(6));
        tree.setFont(tree.getFont().deriveFont(Font.PLAIN, 12f));
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
     * 右键弹出菜单
     */
    private void handlePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        tree.setSelectionPath(path);

        Object node = path.getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode)) return;
        Object userObj = ((DefaultMutableTreeNode) node).getUserObject();
        if (!(userObj instanceof ApiDefinition api)) return;

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
        searchField.getTextEditor().getEmptyText().setText("🔍 搜索接口路径、名称、Controller或描述...");
        searchField.setFont(searchField.getFont().deriveFont(Font.PLAIN, 11f));
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
        statsLabel.setFont(statsLabel.getFont().deriveFont(Font.PLAIN, 10f));
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
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11f));
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
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFocusPainted(false);
        btn.putClientProperty("JButton.buttonType", "square");
        btn.setHorizontalTextPosition(SwingConstants.RIGHT);
        btn.setIconTextGap(3);
        
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