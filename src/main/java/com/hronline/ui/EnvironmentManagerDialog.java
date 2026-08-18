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
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

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
            if (e.getValueIsAdjusting() || initializing) return;
            saveCurrentEdits();
            Environment selected = envList.getSelectedValue();
            if (selected != null) {
                selectedEnvironment = selected;
                // 一伦优化 v22：切换左侧 envList 选中项时，强制把"激活"标记同步到当前选中项，
                // 并立刻 refreshEnvList() 让 JList 重画「✓ 激活」勾选（DefaultListModel 不会自动触发 cell repaint）。
                // 否则用户切了列表项但勾选停留在旧 env，关闭弹窗后右侧 envCombo 也对不上。
                for (Environment ee : environments) ee.setActive(ee == selected);
                refreshEnvList();
                envList.setSelectedValue(selected, true);
                loadEnvironment(selected);
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

        return panel;
    }

    private void refreshEnvList() {
        listModel.clear();
        for (Environment env : environments) {
            listModel.addElement(env);
        }
    }

    private void loadEnvironment(Environment env) {
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
        // 一伦优化 R5：环境列表固定为 dev / test / prod 三个，不允许新建。
        Messages.showWarningDialog(getContentPanel(),
                "环境列表已固定为 dev / test / prod 三个,不允许新建。\n如需新增,请直接编辑已有环境。",
                "提示");
    }

    private void deleteEnvironment() {
        // 一伦优化 R5：环境列表固定为 dev / test / prod 三个，不允许删除。
        Messages.showWarningDialog(getContentPanel(),
                "环境列表已固定为 dev / test / prod 三个,不允许删除。",
                "提示");
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
        refreshEnvList();
        dirty = true;
    }

    @Override
    protected void doOKAction() {
        saveCurrentEdits();
        // 一伦优化 v22：OK 关闭前，把「当前 envList 选中项」强制标记为激活（不再依赖
        // 之前的 isActive()，因为普通编辑后用户根本不会再点"激活"按钮）。
        // 这样 active 标记会随 envList 选中项实时对齐右侧 envCombo。
        Environment sel = envList != null ? envList.getSelectedValue() : null;
        if (sel != null) {
            for (Environment e : environments) e.setActive(e == sel);
        }
        // 一伦优化 v22：再 refreshEnvList 一次，确保 JList 重画「✓ 激活」勾选（DefaultListModel
        // 不会因为 Environment.setActive 字段变化自动触发 cell repaint，必须 clear+addElement 强制重画）。
        refreshEnvList();
        settings.saveEnvironments(environments);
        // Persist active environment
        for (Environment e : environments) {
            if (e.isActive()) {
                settings.setActiveEnvironment(e.getName());
                settings.setBaseUrl(e.getBaseUrl());
                break;
            }
        }
        super.doOKAction();
    }

    /**
     * 一伦优化 #3：把环境变更持久化逻辑独立为可复用方法，
     * 让 EnvAndDataManageDialog（合并对话框）能在自身 OK 时调用，
     * 不依赖本对话框的 OK 按钮。保持与 doOKAction 一致：保存当前编辑 + 写回 settings。
     */
    public void applyChanges() {
        doOKAction();
    }
}