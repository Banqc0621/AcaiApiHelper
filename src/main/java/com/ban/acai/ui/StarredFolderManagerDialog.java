package com.ban.acai.ui;

import com.ban.acai.ai.AiParameterService;
import com.ban.acai.http.HttpExecutorService;
import com.ban.acai.model.ApiDefinition;
import com.ban.acai.model.FolderApiStatus;
import com.ban.acai.model.StarredFolder;
import com.ban.acai.model.TestResult;
import com.ban.acai.scanner.ApiScannerService;
import com.ban.acai.scanner.StarredFolderService;
import com.ban.acai.settings.AcaiSettingsState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 收藏管理对话框 —— 文件夹化的收藏 GUI。
 *
 * <p>以两级树呈现：根（隐藏）→ 文件夹 → 接口。提供：</p>
 * <ul>
 *   <li>文件夹新建/删除/更名（v2.0.0 起「未分类」与其他文件夹功能无差别）</li>
 *   <li>接口拖拽到文件夹、文件夹间移动（拖拽=移动）；「复制到」实现同接口多文件夹</li>
 *   <li>同文件夹内接口唯一，不同文件夹可重复</li>
 *   <li>文件夹内一键批量 AI 生成测试参数（持久化、可手动编辑）</li>
 *   <li>文件夹内一键批量测试；失败接口标红，可手动取消警示</li>
 * </ul>
 *
 * <p>AI 调用与 HTTP 测试均在后台线程执行，完成后回 EDT 刷新树。</p>
 */
public class StarredFolderManagerDialog extends DialogWrapper {

    private final Project project;
    private final StarredFolderService folderService;
    private final AiParameterService aiService;
    private final HttpExecutorService httpService;
    private final AcaiSettingsState settings;

    private Tree tree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode root;
    private final JLabel statusLabel = new JBLabel("就绪");

    /** apiKey(uniqueKey) -> ApiDefinition 解析表，用于把存储的 key 还原成接口对象 */
    private final Map<String, ApiDefinition> apiByKey = new LinkedHashMap<>();

    public StarredFolderManagerDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        this.folderService = StarredFolderService.getInstance(project);
        this.aiService = AiParameterService.getInstance(project);
        this.httpService = HttpExecutorService.getInstance(project);
        this.settings = AcaiSettingsState.getInstance(project);
        setTitle("收藏管理");
        setModal(false);
        init();
        refreshApiIndex();
        rebuildTree();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(JBUI.size(620, 520));

        // 顶部工具栏
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(toolButton("新建文件夹", AllIcons.Actions.NewFolder, this::doNewFolder));
        toolBar.add(toolButton("删除文件夹", AllIcons.Actions.Cancel, this::doDeleteFolder));
        toolBar.add(toolButton("重命名", AllIcons.Actions.Edit, this::doRenameFolder));
        toolBar.addSeparator();
        toolBar.add(toolButton("添加接口", AllIcons.General.Add, this::doAddApi));
        toolBar.add(toolButton("移除", AllIcons.Actions.GC, this::doRemoveApi));
        toolBar.addSeparator();
        toolBar.add(toolButton("AI生成参数", AllIcons.Actions.Lightning, this::doBatchAiGen));
        toolBar.add(toolButton("批量测试", AllIcons.Actions.Execute, this::doBatchTest));
        toolBar.add(toolButton("编辑参数", AllIcons.Actions.EditSource, this::doEditParams));
        toolBar.add(toolButton("取消警示", AllIcons.Actions.QuickfixBulb, this::doClearWarning));
        toolBar.add(toolButton("刷新", AllIcons.Actions.Refresh, () -> { refreshApiIndex(); rebuildTree(); }));
        panel.add(toolBar, BorderLayout.NORTH);

        // 树
        root = new DefaultMutableTreeNode("root");
        treeModel = new DefaultTreeModel(root);
        tree = new Tree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new StarredTreeCellRenderer());
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new ApiDragTransferHandler());
        tree.addMouseListener(new PopupAdapter());

        // 双击编辑参数
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ApiUserObject auo = getSelectedApi();
                    if (auo != null) doEditParams();
                }
            }
        });

        JBScrollPane scroll = new JBScrollPane(tree);
        panel.add(scroll, BorderLayout.CENTER);

        statusLabel.setBorder(new EmptyBorder(4, 6, 4, 6));
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JButton toolButton(String text, Icon icon, Runnable action) {
        JButton b = new JButton(text, icon);
        b.setToolTipText(text);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ==================== 树构建 ====================

    private void refreshApiIndex() {
        apiByKey.clear();
        for (ApiDefinition api : ApiScannerService.getInstance(project).getCachedApis()) {
            apiByKey.put(api.uniqueKey(), api);
        }
    }

    private void rebuildTree() {
        root.removeAllChildren();
        List<StarredFolder> folders = folderService.loadFolders();
        for (StarredFolder folder : folders) {
            DefaultMutableTreeNode folderNode = new DefaultMutableTreeNode(new FolderUserObject(folder));
            for (String apiKey : folder.getApiKeys()) {
                ApiDefinition api = apiByKey.get(apiKey);
                if (api != null) {
                    folderNode.add(new DefaultMutableTreeNode(
                            new ApiUserObject(api, folder.getId())));
                }
            }
            root.add(folderNode);
        }
        treeModel.reload();
        // 展开所有文件夹
        for (int i = 0; i < root.getChildCount(); i++) {
            TreeNode n = root.getChildAt(i);
            tree.expandPath(new TreePath(((DefaultMutableTreeNode) n).getPath()));
        }
        updateStatusCount();
    }

    private void updateStatusCount() {
        int folders = root.getChildCount();
        int apis = 0;
        int failed = 0;
        for (int i = 0; i < folders; i++) {
            DefaultMutableTreeNode fn = (DefaultMutableTreeNode) root.getChildAt(i);
            apis += fn.getChildCount();
            for (int j = 0; j < fn.getChildCount(); j++) {
                DefaultMutableTreeNode an = (DefaultMutableTreeNode) fn.getChildAt(j);
                ApiUserObject auo = (ApiUserObject) an.getUserObject();
                if (folderService.getStatus(auo.folderId, auo.api.uniqueKey()).shouldHighlightRed()) failed++;
            }
        }
        statusLabel.setText("共 " + folders + " 个文件夹 · " + apis + " 个接口 · " + failed + " 个失败标红");
    }

    // ==================== 节点包装 ====================

    private static final class FolderUserObject {
        final StarredFolder folder;
        FolderUserObject(StarredFolder folder) { this.folder = folder; }
        public String toString() { return folder.getName() + " (" + folder.getApiKeys().size() + ")"; }
    }

    private static final class ApiUserObject {
        final ApiDefinition api;
        final String folderId;
        ApiUserObject(ApiDefinition api, String folderId) { this.api = api; this.folderId = folderId; }
        public String toString() { return api.getHttpMethod() + " " + api.getUrl(); }
    }

    private @Nullable FolderUserObject getSelectedFolderUserObject() {
        DefaultMutableTreeNode node = getSelectedNode();
        if (node == null || !(node.getUserObject() instanceof FolderUserObject)) return null;
        return (FolderUserObject) node.getUserObject();
    }

    private @Nullable ApiUserObject getSelectedApi() {
        DefaultMutableTreeNode node = getSelectedNode();
        if (node == null || !(node.getUserObject() instanceof ApiUserObject)) return null;
        return (ApiUserObject) node.getUserObject();
    }

    private @Nullable DefaultMutableTreeNode getSelectedNode() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : (DefaultMutableTreeNode) path.getLastPathComponent();
    }

    /** 选中节点的所属文件夹（若选中的是接口，返回其所在文件夹；若是文件夹本身，返回它） */
    private @Nullable StarredFolder selectedFolderContext() {
        DefaultMutableTreeNode node = getSelectedNode();
        if (node == null) return null;
        Object uo = node.getUserObject();
        if (uo instanceof FolderUserObject) return ((FolderUserObject) uo).folder;
        if (uo instanceof ApiUserObject) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
            if (parent != null && parent.getUserObject() instanceof FolderUserObject) {
                return ((FolderUserObject) parent.getUserObject()).folder;
            }
        }
        return null;
    }

    // ==================== 文件夹操作 ====================

    private void doNewFolder() {
        String name = Messages.showInputDialog(project, "文件夹名称：", "新建文件夹",
                Messages.getQuestionIcon(), "新文件夹", null);
        if (name == null || name.isBlank()) return;
        folderService.createFolder(name.trim());
        rebuildTree();
    }

    private void doDeleteFolder() {
        StarredFolder f = selectedFolderContext();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "删除文件夹"); return; }
        int ret = Messages.showYesNoDialog(project, "删除「" + f.getName() + "」？",
                "删除文件夹", Messages.getQuestionIcon());
        if (ret != Messages.YES) return;
        folderService.deleteFolder(f.getId());
        rebuildTree();
    }

    private void doRenameFolder() {
        StarredFolder f = selectedFolderContext();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "重命名"); return; }
        String name = Messages.showInputDialog(project, "新名称：", "重命名文件夹",
                Messages.getQuestionIcon(), f.getName(), null);
        if (name == null || name.isBlank()) return;
        folderService.renameFolder(f.getId(), name.trim());
        rebuildTree();
    }

    // ==================== 接口成员操作 ====================

    private void doAddApi() {
        refreshApiIndex();
        StarredFolder f = selectedFolderContext();
        if (f == null) { Messages.showWarningDialog(project, "请先选中目标文件夹", "添加接口"); return; }
        List<ApiDefinition> candidates = new ArrayList<>();
        for (ApiDefinition api : apiByKey.values()) {
            if (!f.getApiKeys().contains(api.uniqueKey())) candidates.add(api);
        }
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "没有可添加的接口（可能全部已在文件夹内，或项目尚未扫描接口）", "添加接口");
            return;
        }
        AddApiPicker picker = new AddApiPicker(project, candidates);
        if (picker.showAndGet()) {
            for (ApiDefinition api : picker.getSelected()) {
                folderService.addApiToFolder(f.getId(), api.uniqueKey());
            }
            rebuildTree();
        }
    }

    private void doRemoveApi() {
        ApiUserObject auo = getSelectedApi();
        if (auo == null) { Messages.showWarningDialog(project, "请先选中要移除的接口", "移除接口"); return; }
        folderService.removeApiFromFolder(auo.folderId, auo.api.uniqueKey());
        rebuildTree();
    }

    /** 复制选中接口到另一文件夹（实现「不同文件夹可出现相同接口」） */
    private void doCopyTo() {
        ApiUserObject auo = getSelectedApi();
        if (auo == null) return;
        List<StarredFolder> folders = folderService.loadFolders().stream()
                .filter(f -> !f.getId().equals(auo.folderId))
                .collect(Collectors.toList());
        if (folders.isEmpty()) return;
        String[] names = folders.stream().map(StarredFolder::getName).toArray(String[]::new);
        Object choice = JOptionPane.showInputDialog(tree, "复制到哪个文件夹？", "复制接口",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (choice == null) return;
        int ret = Arrays.asList(names).indexOf(choice);
        if (ret < 0 || ret >= folders.size()) return;
        folderService.addApiToFolder(folders.get(ret).getId(), auo.api.uniqueKey());
        rebuildTree();
    }

    /** 移动选中接口到另一文件夹 */
    private void doMoveTo() {
        ApiUserObject auo = getSelectedApi();
        if (auo == null) return;
        List<StarredFolder> folders = folderService.loadFolders().stream()
                .filter(f -> !f.getId().equals(auo.folderId))
                .collect(Collectors.toList());
        if (folders.isEmpty()) return;
        String[] names = folders.stream().map(StarredFolder::getName).toArray(String[]::new);
        Object choice = JOptionPane.showInputDialog(tree, "移动到哪个文件夹？", "移动接口",
                JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
        if (choice == null) return;
        int ret = Arrays.asList(names).indexOf(choice);
        if (ret < 0 || ret >= folders.size()) return;
        folderService.moveApi(auo.api.uniqueKey(), auo.folderId, folders.get(ret).getId());
        rebuildTree();
    }

    // ==================== 测试参数编辑 ====================

    private void doEditParams() {
        ApiUserObject auo = getSelectedApi();
        if (auo == null) { Messages.showWarningDialog(project, "请先选中一个接口", "编辑参数"); return; }
        ApiDefinition api = auo.api;
        Map<String, String> existing = folderService.getParams(auo.folderId, api.uniqueKey());
        Map<String, String> editable = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
        // 若无参数，按接口参数初始化空行
        if (editable.isEmpty()) {
            for (com.ban.acai.model.ApiParameter p : api.getParameters()) {
                editable.put(p.getName(), "");
            }
        }
        ParamsEditor editor = new ParamsEditor(project, editable);
        if (editor.showAndGet()) {
            folderService.setParams(auo.folderId, api.uniqueKey(), editor.getResult());
            statusLabel.setText("已保存参数：" + api.getUrl());
        }
    }

    // ==================== 批量 AI 生成参数 ====================

    private void doBatchAiGen() {
        refreshApiIndex();
        StarredFolder f = selectedFolderContext();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "AI生成参数"); return; }
        List<ApiDefinition> targets = new ArrayList<>();
        for (String key : f.getApiKeys()) {
            ApiDefinition api = apiByKey.get(key);
            if (api != null) targets.add(api);
        }
        if (targets.isEmpty()) { Messages.showInfoMessage(project, "该文件夹无接口", "AI生成参数"); return; }
        int ret = Messages.showYesNoDialog(project,
                "将对「" + f.getName() + "」内 " + targets.size() + " 个接口调用 AI 生成参数，是否继续？",
                "AI生成参数", Messages.getQuestionIcon());
        if (ret != Messages.YES) return;

        final String folderId = f.getId();
        statusLabel.setText("AI 生成参数中（0/" + targets.size() + "）…");
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
                    final Map<String, String> finalParams = params;
                    folderService.setParams(folderId, api.uniqueKey(), finalParams);
                    ok++;
                    final int okNow = ok;
                    SwingUtilities.invokeLater(() ->
                            statusLabel.setText("AI 生成参数中（" + idx + "/" + targets.size() + "）… 已成功 " + okNow));
                } catch (Exception ex) {
                    fail++;
                }
            }
            final int okF = ok, failF = fail;
            SwingUtilities.invokeLater(() -> {
                rebuildTree();
                statusLabel.setText("AI 生成完成：成功 " + okF + " · 失败 " + failF);
            });
        });
    }

    // ==================== 批量测试 ====================

    private void doBatchTest() {
        refreshApiIndex();
        StarredFolder f = selectedFolderContext();
        if (f == null) { Messages.showWarningDialog(project, "请先选中一个文件夹", "批量测试"); return; }
        List<ApiDefinition> targets = new ArrayList<>();
        for (String key : f.getApiKeys()) {
            ApiDefinition api = apiByKey.get(key);
            if (api != null) targets.add(api);
        }
        if (targets.isEmpty()) { Messages.showInfoMessage(project, "该文件夹无接口", "批量测试"); return; }

        final String folderId = f.getId();
        final String baseUrl = settings.getBaseUrl();
        statusLabel.setText("批量测试中（0/" + targets.size() + "）…");
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
                    status.setPassed(tr.getStatus() == com.ban.acai.model.TestStatus.PASSED);
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
                        statusLabel.setText("批量测试中（" + idx + "/" + targets.size() + "）… 通过 " + pNow + " · 失败 " + fNow));
            }
            final int passedF = passed, failedF = failed;
            SwingUtilities.invokeLater(() -> {
                rebuildTree();
                statusLabel.setText("批量测试完成：通过 " + passedF + " · 失败 " + failedF);
            });
        });
    }

    // ==================== 取消警示 ====================

    private void doClearWarning() {
        ApiUserObject auo = getSelectedApi();
        if (auo == null) { Messages.showWarningDialog(project, "请先选中一个标红接口", "取消警示"); return; }
        folderService.clearWarning(auo.folderId, auo.api.uniqueKey());
        rebuildTree();
    }

    // ==================== 右键菜单 ====================

    private final class PopupAdapter extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
        @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

        private void maybeShow(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int row = tree.getRowForLocation(e.getX(), e.getY());
            if (row < 0) return;
            tree.setSelectionRow(row);
            TreePath path = tree.getPathForRow(row);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object uo = node.getUserObject();
            JPopupMenu menu = new JPopupMenu();
            if (uo instanceof FolderUserObject) {
                FolderUserObject fuo = (FolderUserObject) uo;
                menu.add("新建文件夹").addActionListener(a -> doNewFolder());
                menu.add("重命名").addActionListener(a -> doRenameFolder());
                // v2.0.0：未分类也支持删除（与其他文件夹功能无差别）
                menu.add("删除文件夹").addActionListener(a -> doDeleteFolder());
                menu.addSeparator();
                menu.add("添加接口").addActionListener(a -> doAddApi());
                menu.addSeparator();
                menu.add("AI生成参数").addActionListener(a -> doBatchAiGen());
                menu.add("批量测试").addActionListener(a -> doBatchTest());
            } else if (uo instanceof ApiUserObject) {
                menu.add("编辑参数").addActionListener(a -> doEditParams());
                menu.add("移动到…").addActionListener(a -> doMoveTo());
                menu.add("复制到…").addActionListener(a -> doCopyTo());
                menu.add("移除").addActionListener(a -> doRemoveApi());
                menu.addSeparator();
                menu.add("取消警示").addActionListener(a -> doClearWarning());
            }
            menu.show(tree, e.getX(), e.getY());
        }
    }

    // ==================== 拖拽 ====================

    private final class ApiDragTransferHandler extends TransferHandler {
        private final DataFlavor flavor = new DataFlavor(ApiUserObject.class, "ApiNode");

        @Override public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override protected Transferable createTransferable(JComponent c) {
            ApiUserObject auo = getSelectedApi();
            if (auo == null) return null;
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{flavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return flavor.equals(f); }
                @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!flavor.equals(f)) throw new UnsupportedFlavorException(f);
                    return auo;
                }
            };
        }

        @Override public boolean canImport(TransferHandler.TransferSupport support) {
            if (!support.isDataFlavorSupported(flavor)) return false;
            TreePath path = support.getDropLocation() == null ? null
                    : ((JTree.DropLocation) support.getDropLocation()).getPath();
            if (path == null) return false;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            return node.getUserObject() instanceof FolderUserObject;
        }

        @Override public boolean importData(TransferHandler.TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                ApiUserObject auo = (ApiUserObject) support.getTransferable().getTransferData(flavor);
                JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) dl.getPath().getLastPathComponent();
                FolderUserObject fuo = (FolderUserObject) node.getUserObject();
                if (auo.folderId.equals(fuo.folder.getId())) return false;
                folderService.moveApi(auo.api.uniqueKey(), auo.folderId, fuo.folder.getId());
                SwingUtilities.invokeLater(StarredFolderManagerDialog.this::rebuildTree);
                return true;
            } catch (UnsupportedFlavorException | java.io.IOException ex) {
                return false;
            }
        }
    }

    // ==================== 渲染器 ====================

    private final class StarredTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object uo = node.getUserObject();
            if (uo instanceof FolderUserObject) {
                FolderUserObject fuo = (FolderUserObject) uo;
                setIcon(AllIcons.Nodes.Folder);
                setText(fuo.folder.getName() + " (" + fuo.folder.getApiKeys().size() + ")");
                setForeground(JBColor.foreground());
            } else if (uo instanceof ApiUserObject) {
                ApiUserObject auo = (ApiUserObject) uo;
                setIcon(AllIcons.Nodes.Plugin);
                setText(auo.api.getHttpMethod() + " " + auo.api.getUrl());
                FolderApiStatus st = folderService.getStatus(auo.folderId, auo.api.uniqueKey());
                if (st.shouldHighlightRed()) {
                    setForeground(JBColor.RED);
                    setText(auo.api.getHttpMethod() + " " + auo.api.getUrl() + "  ✗ " + st.getMessage());
                } else if (st.isPassed() && st.getTestedAt() > 0) {
                    setForeground(new JBColor(new Color(0, 128, 0), new Color(98, 190, 98)));
                    setText(auo.api.getHttpMethod() + " " + auo.api.getUrl() + "  ✓");
                }
            }
            return this;
        }
    }

    // ==================== 参数编辑器（内嵌对话框） ====================

    private static final class ParamsEditor extends DialogWrapper {
        private final Map<String, String> data;
        private final DefaultListModel<String> keyModel = new DefaultListModel<>();
        private final JBList<String> keyList = new JBList<>(keyModel);
        private final JTextField valueField = new JTextField();

        ParamsEditor(Project project, Map<String, String> data) {
            super(project);
            this.data = data;
            setTitle("编辑测试参数");
            init();
            for (String k : data.keySet()) keyModel.addElement(k);
            if (!keyModel.isEmpty()) keyList.setSelectedIndex(0);
            keyList.addListSelectionListener(e -> {
                String k = keyList.getSelectedValue();
                if (k != null) valueField.setText(data.getOrDefault(k, ""));
            });
            valueField.addActionListener(e -> commitCurrent());
        }

        private void commitCurrent() {
            String k = keyList.getSelectedValue();
            if (k != null) data.put(k, valueField.getText());
        }

        @Override protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(JBUI.size(460, 360));
            JBList<String> list = keyList;
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            panel.add(new JBScrollPane(list), BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout(4, 4));
            south.setBorder(JBUI.Borders.empty(4));
            south.add(new JBLabel("参数值："), BorderLayout.WEST);
            south.add(valueField, BorderLayout.CENTER);
            panel.add(south, BorderLayout.SOUTH);
            return panel;
        }

        Map<String, String> getResult() {
            commitCurrent();
            return data;
        }
    }

    // ==================== 添加接口选择器（内嵌对话框） ====================

    private static final class AddApiPicker extends DialogWrapper {
        private final List<ApiDefinition> candidates;
        private final DefaultListModel<ApiDefinition> model = new DefaultListModel<>();
        private final JBList<ApiDefinition> list = new JBList<>(model);
        private final JTextField searchField = new JTextField();

        AddApiPicker(Project project, List<ApiDefinition> candidates) {
            super(project);
            this.candidates = candidates;
            setTitle("选择要添加的接口");
            setOKButtonText("添加");
            init();
            list.setCellRenderer((l, api, idx, sel, focus) -> {
                JBLabel label = new JBLabel(api.getHttpMethod() + "  " + api.getUrl() + "  —  " + api.getName());
                label.setOpaque(true);
                if (sel) label.setBackground(UIManager.getColor("Tree.selectionBackground"));
                return label;
            });
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            applyFilter("");
            searchField.addActionListener(e -> applyFilter(searchField.getText()));
        }

        private void applyFilter(String text) {
            model.clear();
            String t = text == null ? "" : text.trim().toLowerCase();
            for (ApiDefinition api : candidates) {
                String key = (api.getHttpMethod() + " " + api.getUrl() + " " + api.getName()).toLowerCase();
                if (t.isEmpty() || key.contains(t)) model.addElement(api);
            }
        }

        @Override protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(JBUI.size(560, 420));
            JPanel top = new JPanel(new BorderLayout(4, 4));
            top.setBorder(JBUI.Borders.empty(4));
            top.add(new JBLabel("搜索："), BorderLayout.WEST);
            top.add(searchField, BorderLayout.CENTER);
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JBScrollPane(list), BorderLayout.CENTER);
            return panel;
        }

        List<ApiDefinition> getSelected() {
            return list.getSelectedValuesList();
        }
    }
}