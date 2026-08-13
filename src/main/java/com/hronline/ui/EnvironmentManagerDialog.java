package com.hronline.ui;

import com.hronline.model.Environment;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
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
        setSize(800, 550);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setPreferredSize(new Dimension(780, 500));

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
        varBtnPanel.add(new JBLabel("💡 在请求中使用 {{变量名}} 引用"));
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
        headerBtnPanel.add(new JBLabel("💡 Header值中也可以使用 {{变量名}}"));
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
        saveCurrentEdits();
        Environment newEnv = new Environment("新环境" + (environments.size() + 1), "http://localhost:8080");
        newEnv.setDescription("新建环境");
        environments.add(newEnv);
        refreshEnvList();
        envList.setSelectedValue(newEnv, true);
        dirty = true;
    }

    private void deleteEnvironment() {
        Environment selected = envList.getSelectedValue();
        if (selected == null) return;
        if (environments.size() <= 1) {
            JOptionPane.showMessageDialog(getContentPanel(), "至少保留一个环境", "无法删除", JOptionPane.WARNING_MESSAGE);
            return;
        }
        environments.remove(selected);
        refreshEnvList();
        envList.setSelectedIndex(0);
        dirty = true;
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
}