package com.hronline.ui;

import com.hronline.model.Environment;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 环境管理对话框 - 创建/编辑/删除/切换环境
 */
public class EnvironmentManagerDialog extends DialogWrapper {

    private final Project project;
    private final RestAutoLabSettingsState settings;
    private List<Environment> environments;
    private Environment selectedEnvironment;

    private JList<Environment> envList;
    private DefaultListModel<Environment> listModel;
    private JBTextField nameField;
    private JBTextField baseUrlField;
    private JBTextArea descField;
    private DefaultTableModel varTableModel;
    private DefaultTableModel headerTableModel;
    private boolean dirty = false;
    private boolean initializing = true;
    /** 刷新列表并恢复选择时，禁止 ListSelectionListener 重新进入自身。 */
    private boolean suppressEnvListSelectionAction = false;
    /** 一伦优化 v24：refreshEnvList() 内部重建 listModel 时锁住 listener，避免 clear() 触发 selection 事件造成死循环（StackOverflow）。 */
    private boolean refreshingEnvList = false;
    /** 一伦优化 v23：监听器装填（loadEnvironment / 初始化）期间为 true，避免回写风暴 */
    private boolean loadingFields = false;

    /** 一伦优化 v23：双向联动通道 —— 任一字段被改、envList 选中项变化、激活项变化时触发。
     *  主面板安装本回调即可实时刷新右侧 envCombo / baseUrlField。 */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /** 主面板注入：右侧 envCombo / baseUrlField 发生改动时调用，强制本对话框重新 load 列表。 */
    private Runnable externalRefreshHook = null;

    /** 一伦优化 v23：注册联动回调。主面板装上后，左侧任意字段改动会立刻刷右侧。 */
    public void addChangeListener(Runnable r) {
        if (r != null) changeListeners.add(r);
    }

    /** 一伦优化 v23：主面板通知本对话框「外部已修改」，强制重拉数据 + 重画列表 + 同步右侧选中项。 */
    public void setExternalRefreshHook(Runnable r) {
        this.externalRefreshHook = r;
    }

    /** 一伦优化 v23：主动触发一次刷新（外部调用）。主面板在 baseUrl 回车 / envCombo 切换后调本方法。 */
    public void notifyExternalChanged() {
        String selectedName = envList != null && envList.getSelectedValue() != null
                ? envList.getSelectedValue().getName() : null;
        // 强制重拉最新 env 列表
        this.environments = settings.loadEnvironments();
        suppressEnvListSelectionAction = true;
        try {
            refreshEnvList();
        } finally {
            suppressEnvListSelectionAction = false;
        }
        if (externalRefreshHook != null) {
            try { externalRefreshHook.run(); } catch (Exception ignored) {}
        }
        // 优先恢复原选中环境；若它已不存在则回退到当前激活环境。
        Environment sel = null;
        String activeName = settings.getActiveEnvironment();
        for (Environment e : this.environments) {
            if (e.getName().equals(selectedName)) {
                sel = e;
                break;
            }
        }
        if (sel == null) {
            for (Environment e : this.environments) {
                if (e.getName().equals(activeName)) {
                    sel = e;
                    break;
                }
            }
        }
        if (sel != null) {
            suppressEnvListSelectionAction = true;
            try {
                envList.setSelectedValue(sel, true);
            } finally {
                suppressEnvListSelectionAction = false;
            }
            selectedEnvironment = sel;
            loadEnvironment(sel);
        }
        fireChange();
    }

    private void fireChange() {
        for (Runnable r : changeListeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    public EnvironmentManagerDialog(Project project) {
        super(project);
        this.project = project;
        this.settings = RestAutoLabSettingsState.getInstance(project);
        // 一伦优化 R5：先从 settings 拉一次最新数据，确保「环境列表」与「外面主面板的下拉框、baseUrlField」始终同步。
        // 兜底逻辑（缺哪个补哪个 / 强制只保留 dev/test/prod）已在 loadEnvironments 内完成。
        this.environments = settings.loadEnvironments();
        this.selectedEnvironment = null;
        String activeName = settings.getActiveEnvironment();
        for (Environment e : this.environments) {
            if (e.getName().equals(activeName)) {
                this.selectedEnvironment = e;
                break;
            }
        }
        if (this.selectedEnvironment == null && !this.environments.isEmpty()) {
            this.selectedEnvironment = this.environments.get(0);
        }
        setTitle("环境管理");
        setSize(800, 470);
        init();
    }

    @Override
    public @Nullable JComponent createCenterPanel() {
        // 一伦优化 #64：createCenterPanel 会被调用两次（本对话框 init() 一次，
        // EnvAndDataManageDialog 嵌入时再一次）。initializing 在第一次结束时已置 false，
        // 若不重置，第二次调用时下方「初始定位选中项」的 envList.setSelectedValue() 会
        // 触发 ListSelectionListener → saveCurrentEdits() 用【刚创建的空字段】回写当前选中
        // （即第一个/激活的）环境并 settings.saveEnvironments() 持久化，导致第一条记录
        // 的 name/baseUrl/desc/变量被清空（"只有第一条记录数据异常、不同步、应用不生效"的根因）。
        // 每次构建面板都视为初始化阶段，先重置守卫，结尾统一放开。
        initializing = true;
        // 一伦优化 R5+：createCenterPanel 每次被调用（即每次 init/init 重新触发）都从 settings
        // 拉取最新环境数据并重新定位 selectedEnvironment，
        // 保证左侧「环境列表」始终与右侧主面板 envCombo 的 activeEnvironment 一致。
        // 关键点：右侧 envCombo 切换时调用的是 settings.setActiveEnvironment(name)，
        // 这里必须重新 loadEnvironments() 拉一次，否则 active 勾选会一直停留在旧值。
        this.environments = settings.loadEnvironments();
        String activeName = settings.getActiveEnvironment();
        this.selectedEnvironment = null;
        for (Environment e : this.environments) {
            // 用 active flag + name 双保险定位当前激活项
            if (e.isActive() || e.getName().equals(activeName)) {
                this.selectedEnvironment = e;
                // 同步 active flag（防止持久化里 active 标记丢失）
                for (Environment ee : this.environments) ee.setActive(ee == e);
                break;
            }
        }
        if (this.selectedEnvironment == null && !this.environments.isEmpty()) {
            this.selectedEnvironment = this.environments.get(0);
        }

        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setPreferredSize(new Dimension(780, 420));

        // === Left: environment list ===
        JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
        leftPanel.setBorder(JBUI.Borders.empty(4));
        leftPanel.setPreferredSize(new Dimension(200, -1));

        listModel = new DefaultListModel<>();
        envList = new JList<>(listModel);
        envList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Environment env) {
                    setText(env.isActive() ? "✓ " + env.getName() : env.getName());
                    setIcon(AllIcons.Nodes.Plugin);
                }
                return this;
            }
        });
        envList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || initializing || suppressEnvListSelectionAction || refreshingEnvList) return;
            saveCurrentEdits();
            Environment selected = envList.getSelectedValue();
            if (selected != null) {
                selectedEnvironment = selected;
                // 一伦优化 v22：切换左侧 envList 选中项时，强制把"激活"标记同步到当前选中项，
                // 并立刻 refreshEnvList() 让 JList 重画「✓ 激活」勾选（DefaultListModel 不会自动触发 cell repaint）。
                // 否则用户切了列表项但勾选停留在旧 env，关闭弹窗后右侧 envCombo 也对不上。
                for (Environment ee : environments) ee.setActive(ee == selected);
                suppressEnvListSelectionAction = true;
                try {
                    refreshEnvList();
                    envList.setSelectedValue(selected, true);
                } finally {
                    suppressEnvListSelectionAction = false;
                }
                loadEnvironment(selected);
                // 一伦优化 v23：双向联动 —— 切左侧列表项立刻写回 settings + 通知主面板刷新右侧。
                settings.saveEnvironments(environments);
                settings.setActiveEnvironment(selected.getName());
                settings.setBaseUrl(selected.getBaseUrl());
                fireChange();
            }
        });
        refreshEnvList();

        JBScrollPane listScroll = new JBScrollPane(envList);
        leftPanel.add(new JBLabel("环境列表", AllIcons.Nodes.Plugin, SwingConstants.LEFT), BorderLayout.NORTH);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        // Buttons for add/delete
        JPanel envBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton("新建", AllIcons.General.Add);
        addBtn.addActionListener(e -> addNewEnvironment());
        JButton delBtn = new JButton("删除", AllIcons.General.Remove);
        delBtn.addActionListener(e -> deleteEnvironment());
        JButton activateBtn = new JButton("激活", AllIcons.Actions.Execute);
        activateBtn.addActionListener(e -> activateEnvironment());
        envBtnPanel.add(addBtn);
        envBtnPanel.add(delBtn);
        envBtnPanel.add(activateBtn);
        leftPanel.add(envBtnPanel, BorderLayout.SOUTH);

        panel.add(leftPanel, BorderLayout.WEST);

        // === Right: environment details ===
        JPanel rightPanel = new JPanel(new BorderLayout(0, 8));
        rightPanel.setBorder(JBUI.Borders.empty(4));

        // Basic info panel
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setBorder(JBUI.Borders.emptyBottom(8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 4, 2, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        infoPanel.add(new JBLabel("环境名称:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        nameField = new JBTextField();
        infoPanel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        infoPanel.add(new JBLabel("Base URL:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        baseUrlField = new JBTextField();
        infoPanel.add(baseUrlField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTH;
        infoPanel.add(new JBLabel("描述:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.weighty = 0;
        descField = new JBTextArea(2, 30);
        descField.setLineWrap(true);
        infoPanel.add(new JBScrollPane(descField), gbc);

        rightPanel.add(infoPanel, BorderLayout.NORTH);

        // Variables and headers tabbed pane
        JTabbedPane tabs = new JTabbedPane();

        // Variables table
        varTableModel = new DefaultTableModel(new Object[]{"变量名", "变量值"}, 0);
        JBTable varTable = new JBTable(varTableModel);
        JPanel varPanel = new JPanel(new BorderLayout());
        varPanel.add(new JBScrollPane(varTable), BorderLayout.CENTER);
        JPanel varBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addVarBtn = new JButton("添加", AllIcons.General.Add);
        addVarBtn.addActionListener(e -> varTableModel.addRow(new Object[]{"", ""}));
        JButton delVarBtn = new JButton("删除", AllIcons.General.Remove);
        delVarBtn.addActionListener(e -> {
            int row = varTable.getSelectedRow();
            if (row >= 0) varTableModel.removeRow(row);
        });
        varBtnPanel.add(addVarBtn);
        varBtnPanel.add(delVarBtn);
        varBtnPanel.add(new JBLabel("提示：在请求中使用 {{变量名}} 引用"));
        varPanel.add(varBtnPanel, BorderLayout.SOUTH);
        tabs.addTab("环境变量", varPanel);

        // Headers table
        headerTableModel = new DefaultTableModel(new Object[]{"Header名", "值"}, 0);
        JBTable headerTable = new JBTable(headerTableModel);
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JBScrollPane(headerTable), BorderLayout.CENTER);
        JPanel headerBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addHdrBtn = new JButton("添加", AllIcons.General.Add);
        addHdrBtn.addActionListener(e -> headerTableModel.addRow(new Object[]{"", ""}));
        JButton delHdrBtn = new JButton("删除", AllIcons.General.Remove);
        delHdrBtn.addActionListener(e -> {
            int row = headerTable.getSelectedRow();
            if (row >= 0) headerTableModel.removeRow(row);
        });
        headerBtnPanel.add(addHdrBtn);
        headerBtnPanel.add(delHdrBtn);
        headerBtnPanel.add(new JBLabel("提示：Header 值中也可以使用 {{变量名}}"));
        headerPanel.add(headerBtnPanel, BorderLayout.SOUTH);
        tabs.addTab("全局请求头", headerPanel);

        rightPanel.add(tabs, BorderLayout.CENTER);

        panel.add(rightPanel, BorderLayout.CENTER);

        // Load initial environment (select active or first) without triggering save
        if (selectedEnvironment != null) {
            envList.setSelectedValue(selectedEnvironment, false);
        } else if (!environments.isEmpty()) {
            envList.setSelectedIndex(0);
            selectedEnvironment = environments.get(0);
        }
        if (selectedEnvironment != null) {
            loadEnvironment(selectedEnvironment);
        }
        initializing = false;

        // 一伦优化 v23：装实时联动监听器（3 个文本字段 + 2 个表 model），
        // 任一字段改动 → 立刻写回 settings + 通知主面板刷新右侧。
        // #63 修复：createCenterPanel 会被调用两次（本对话框 init() 一次，
        // EnvAndDataManageDialog 嵌入时再一次），每次都会重建全新组件实例。
        // 旧版用 listenersInstalled 守卫只装一次 —— 监听器装在了被丢弃的第一套组件上，
        // 实际显示的第二套组件没有任何监听器，编辑不实时回写、主面板不回显（"保存和回显有问题"的根因之一）。
        // 现在每次调用都给当次新建的组件安装监听器（组件是全新的，不存在重复注册）。
        installLiveSyncListeners();

        return panel;
    }

    /**
     * 一伦优化 v23：双向联动 —— 左侧任一字段被改，立刻写回 settings，并触发 changeListeners 通知主面板。
     * <p>注意：loadEnvironment 装填字段期间会被 {@link #loadingFields} 守卫，不会回写风暴。</p>
     */
    private void installLiveSyncListeners() {
        DocumentListener docListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onFieldChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onFieldChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onFieldChanged(); }
        };
        nameField.getDocument().addDocumentListener(docListener);
        baseUrlField.getDocument().addDocumentListener(docListener);
        descField.getDocument().addDocumentListener(docListener);

        TableModelListener tableListener = new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                if (loadingFields) return;
                onFieldChanged();
            }
        };
        varTableModel.addTableModelListener(tableListener);
        headerTableModel.addTableModelListener(tableListener);
    }

    /**
     * 一伦优化 v23：任一字段被改 → 立刻持久化 + 通知主面板。
     * <p>同时把当前选中 env 的最新 baseUrl 写回 settings.activeEnvironment 关联的 env，
     * 这样右侧 envCombo / baseUrlField 取到的总是最新值。</p>
     */
    private void onFieldChanged() {
        if (initializing || loadingFields) return;
        if (selectedEnvironment == null) return;
        // #64：rename 也要同步 activeEnvironment —— 旧版只比对 name 与旧 activeName，
        // 用户改了激活 env 的 name 后 settings.activeEnvironment 仍指向旧名，下次 load 时
        // 激活态会落到其他 env 上、并且「应用」看似没生效。
        boolean wasActive = selectedEnvironment.isActive();
        String oldName = selectedEnvironment.getName();
        // 1. 把 UI 内容写回 selectedEnvironment
        saveCurrentEdits();
        String newName = selectedEnvironment.getName();
        // 2. 持久化（这才是真"联动"：右侧主面板下次 refresh 就能看到）
        settings.saveEnvironments(environments);
        // 3. 如果是当前激活 env，baseUrl + activeEnvironment 都要同步
        String activeName = settings.getActiveEnvironment();
        if (wasActive || oldName.equals(activeName) || newName.equals(activeName)) {
            settings.setActiveEnvironment(newName);
            settings.setBaseUrl(selectedEnvironment.getBaseUrl());
        }
        // 4. 通知主面板
        fireChange();
    }

    private void refreshEnvList() {
        // 一伦优化 v24：锁住 listener，避免 listModel.clear() 触发的 selection 事件
        // 重新进入 listener → refreshEnvList() → clear() 形成 StackOverflow 死循环。
        // 首次填充（listModel 为空）走 addElement；之后优先逐个 setElementAt 刷新「✓ 激活」勾选，
        // 不重建 model，从而不触发 selection 事件。size 不一致时退回到重建路径
        // （此时 selection 事件会被 refreshingEnvList 守卫挡住，不死循环）。
        refreshingEnvList = true;
        try {
            if (listModel.isEmpty()) {
                for (Environment env : environments) {
                    listModel.addElement(env);
                }
            } else if (listModel.size() == environments.size()) {
                for (int i = 0; i < listModel.size(); i++) {
                    listModel.setElementAt(environments.get(i), i);
                }
                envList.repaint();
            } else {
                // size 不一致（如外部增删 env），重建 model
                Object currentSel = envList.getSelectedValue();
                listModel.clear();
                for (Environment env : environments) {
                    listModel.addElement(env);
                }
                if (currentSel != null) {
                    suppressEnvListSelectionAction = true;
                    try {
                        envList.setSelectedValue(currentSel, false);
                    } finally {
                        suppressEnvListSelectionAction = false;
                    }
                }
            }
        } finally {
            refreshingEnvList = false;
        }
    }

    private void loadEnvironment(Environment env) {
        loadingFields = true;
        try {
            nameField.setText(env.getName());
            baseUrlField.setText(env.getBaseUrl());
            descField.setText(env.getDescription());

            varTableModel.setRowCount(0);
            for (var entry : env.getVariables().entrySet()) {
                varTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }

            headerTableModel.setRowCount(0);
            for (var entry : env.getGlobalHeaders().entrySet()) {
                headerTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
        } finally {
            loadingFields = false;
        }
    }

    private void saveCurrentEdits() {
        if (selectedEnvironment == null) return;
        if (!dirty && nameField.getText().equals(selectedEnvironment.getName())
                && baseUrlField.getText().equals(selectedEnvironment.getBaseUrl())
                && descField.getText().equals(selectedEnvironment.getDescription())) {
            // Only save if tables have changed too, but for simplicity we save anyway
        }

        selectedEnvironment.setName(nameField.getText().trim());
        selectedEnvironment.setBaseUrl(baseUrlField.getText().trim());
        selectedEnvironment.setDescription(descField.getText().trim());

        selectedEnvironment.getVariables().clear();
        for (int i = 0; i < varTableModel.getRowCount(); i++) {
            String k = (String) varTableModel.getValueAt(i, 0);
            String v = (String) varTableModel.getValueAt(i, 1);
            if (k != null && !k.isBlank()) {
                selectedEnvironment.getVariables().put(k, v != null ? v : "");
            }
        }

        selectedEnvironment.getGlobalHeaders().clear();
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            String k = (String) headerTableModel.getValueAt(i, 0);
            String v = (String) headerTableModel.getValueAt(i, 1);
            if (k != null && !k.isBlank()) {
                selectedEnvironment.getGlobalHeaders().put(k, v != null ? v : "");
            }
        }
        dirty = true;
    }

    private void addNewEnvironment() {
        // #64：恢复新增。生成唯一默认名（env-1, env-2 ...）避免与已有重名，
        // 让用户直接在「环境名称」输入框改名。
        Set<String> existing = new HashSet<>();
        for (Environment e : environments) {
            if (e.getName() != null) existing.add(e.getName().trim());
        }
        String baseName = "env";
        int i = 1;
        while (existing.contains(baseName + "-" + i)) i++;
        String name = baseName + "-" + i;

        Environment env = new Environment(name, "");
        env.setDescription("");
        // 新建环境默认不激活；如果当前没有激活项则让新建的成为激活
        boolean hasActive = false;
        for (Environment e : environments) {
            if (e.isActive()) { hasActive = true; break; }
        }
        env.setActive(!hasActive);
        environments.add(env);

        // 重建列表并选中新建项
        refreshingEnvList = true;
        try {
            listModel.clear();
            for (Environment e : environments) listModel.addElement(e);
        } finally {
            refreshingEnvList = false;
        }
        suppressEnvListSelectionAction = true;
        try {
            envList.setSelectedValue(env, true);
        } finally {
            suppressEnvListSelectionAction = false;
        }
        selectedEnvironment = env;
        loadEnvironment(env);
        // 立刻持久化 + 通知主面板，让右侧 envCombo 也能看到新增项
        settings.saveEnvironments(environments);
        if (env.isActive()) {
            settings.setActiveEnvironment(env.getName());
            settings.setBaseUrl(env.getBaseUrl());
        }
        fireChange();
        // 焦点落在名称字段，方便立刻改名
        SwingUtilities.invokeLater(() -> {
            nameField.requestFocusInWindow();
            nameField.selectAll();
        });
    }

    private void deleteEnvironment() {
        // #64：恢复删除。至少保留 1 个环境；删除激活项时把激活态迁到下一个剩余项。
        Environment sel = envList != null ? envList.getSelectedValue() : null;
        if (sel == null) return;
        if (environments.size() <= 1) {
            Messages.showWarningDialog(getContentPanel(),
                    "至少需要保留一个环境，无法删除。",
                    "提示");
            return;
        }
        int idx = envList.getSelectedIndex();
        boolean wasActive = sel.isActive();
        environments.remove(sel);
        // 清理：被删项如果是 activeEnvironment，先把 activeEnvironment 改个临时名，
        // 让后续归一化逻辑不会去匹配已经被删除的 env。
        if (wasActive) {
            settings.setActiveEnvironment("");
        }

        // 重建列表
        refreshingEnvList = true;
        try {
            listModel.clear();
            for (Environment e : environments) listModel.addElement(e);
        } finally {
            refreshingEnvList = false;
        }
        // 选中新位置
        int newIdx = idx;
        if (newIdx >= environments.size()) newIdx = environments.size() - 1;
        if (newIdx < 0) newIdx = 0;
        Environment newSel = environments.get(newIdx);
        suppressEnvListSelectionAction = true;
        try {
            envList.setSelectedIndex(newIdx);
        } finally {
            suppressEnvListSelectionAction = false;
        }
        selectedEnvironment = newSel;
        loadEnvironment(newSel);

        // 持久化 + 通知主面板
        settings.saveEnvironments(environments);
        // 重新从 settings 读一次（归一化激活态），保证 activeEnvironment 名称有效
        Environment activeNow = settings.getActiveEnvironmentObj();
        settings.setActiveEnvironment(activeNow.getName());
        settings.setBaseUrl(activeNow.getBaseUrl());
        refreshEnvList();
        fireChange();
    }

    private void activateEnvironment() {
        Environment selected = envList.getSelectedValue();
        if (selected == null) return;
        for (Environment e : environments) {
            e.setActive(e == selected);
        }
        selectedEnvironment = selected;
        settings.setActiveEnvironment(selected.getName());
        settings.setBaseUrl(selected.getBaseUrl());
        settings.saveEnvironments(environments);   // 一伦优化 v23：激活也持久化
        refreshEnvList();
        dirty = true;
        fireChange();  // 一伦优化 v23：通知主面板
    }

    /**
     * 持久化当前环境编辑，但【不关闭】对话框。
     * <p>#63 重构：把 doOKAction 里除「关闭」以外的全部保存逻辑收敛到这里，
     * 供「应用」按钮与 OK 按钮复用——「应用」只调本方法（不退出），OK 调本方法后再关闭。
     * 旧版 applyChanges() 直接转发 doOKAction()，而 doOKAction 末尾会调
     * {@code super.doOKAction()} 关闭对话框——被「环境 & 数据」合并弹窗当内嵌面板调用时
     * 会触发一次多余的 close，也无法支持「应用不退出」。</p>
     */
    public void applyChanges() {
        saveCurrentEdits();
        // 把「当前 envList 选中项」强制标记为激活（普通编辑后用户不会再点"激活"按钮）
        Environment sel = envList != null ? envList.getSelectedValue() : null;
        if (sel != null) {
            for (Environment e : environments) e.setActive(e == sel);
        }
        refreshEnvList();
        settings.saveEnvironments(environments);
        for (Environment e : environments) {
            if (e.isActive()) {
                settings.setActiveEnvironment(e.getName());
                settings.setBaseUrl(e.getBaseUrl());
                break;
            }
        }
        fireChange();
    }

    @Override
    protected void doOKAction() {
        applyChanges();
        super.doOKAction();
    }
}
