package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.model.ApiDefinition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * 依赖关系配置对话框 - 展示自动检测到的依赖关系，支持用户确认/编辑/删除/添加
 *
 * <p>表格列：上游接口 | 响应字段路径 | 下游接口 | 目标参数 | 检测方式</p>
 */
public class DependencyGraphDialog extends DialogWrapper {

    private final Project project;
    private final List<ApiDefinition> apis;
    private List<ApiDependency> dependencies;

    private DefaultTableModel tableModel;
    private JBTable table;

    /** key = uniqueKey, value = displayLabel() 用于表格展示 */
    private final Map<String, String> labelByKey = new LinkedHashMap<>();

    public DependencyGraphDialog(Project project, List<ApiDefinition> apis,
                                 List<ApiDependency> dependencies) {
        super(project);
        this.project = project;
        this.apis = apis;
        this.dependencies = new ArrayList<>();
        for (ApiDependency dep : dependencies) {
            ApiDependency copy = new ApiDependency(dep.getProducerKey(), dep.getConsumerKey(), dep.getDetectionType());
            for (ApiDependency.ValueMapping m : dep.getMappings()) {
                copy.getMappings().add(new ApiDependency.ValueMapping(m.getSourcePath(), m.getTargetParam()));
            }
            this.dependencies.add(copy);
        }
        for (ApiDefinition a : apis) {
            labelByKey.put(a.uniqueKey(), a.displayLabel());
        }
        setTitle("API 依赖链配置");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(750, 400));

        // 说明
        JBLabel hint = new JBLabel(
                "<html>自动检测到以下接口依赖关系，执行时将按依赖顺序传递响应值。<br>" +
                "可编辑路径、删除误检项或手动添加依赖。</html>");
        hint.setBorder(JBUI.Borders.empty(0, 0, 4, 0));
        panel.add(hint, BorderLayout.NORTH);

        // 表格
        String[] columns = {"上游接口", "响应字段路径", "下游接口", "目标参数", "检测方式"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 1 || col == 3; // 路径和参数可编辑
            }
        };
        table = new JBTable(tableModel);
        table.setRowHeight(28);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);

        fillTable();

        JBScrollPane scrollPane = new JBScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 按钮栏
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton("添加依赖");
        JButton deleteBtn = new JButton("删除选中行");
        buttonBar.add(addBtn);
        buttonBar.add(deleteBtn);

        addBtn.addActionListener(e -> addDependency());
        deleteBtn.addActionListener(e -> deleteSelectedRow());

        panel.add(buttonBar, BorderLayout.SOUTH);

        return panel;
    }

    private void fillTable() {
        tableModel.setRowCount(0);
        for (ApiDependency dep : dependencies) {
            String producerLabel = labelByKey.getOrDefault(dep.getProducerKey(), dep.getProducerKey());
            String consumerLabel = labelByKey.getOrDefault(dep.getConsumerKey(), dep.getConsumerKey());
            for (ApiDependency.ValueMapping m : dep.getMappings()) {
                tableModel.addRow(new Object[]{
                        producerLabel, m.getSourcePath(), consumerLabel, m.getTargetParam(), dep.getDetectionType()
                });
            }
        }
        if (tableModel.getRowCount() == 0) {
            tableModel.addRow(new Object[]{"(无依赖)", "", "", "", ""});
        }
    }

    private void addDependency() {
        if (apis.size() < 2) {
            Messages.showInfoMessage(project, "至少需要 2 个接口才能添加依赖", "提示");
            return;
        }
        String[] apiLabels = apis.stream().map(ApiDefinition::displayLabel).toArray(String[]::new);

        String producerLabel = (String) JOptionPane.showInputDialog(
                null, "选择上游接口（producer）：", "添加依赖",
                JOptionPane.QUESTION_MESSAGE, null, apiLabels, apiLabels[0]);
        if (producerLabel == null) return;

        String consumerLabel = (String) JOptionPane.showInputDialog(
                null, "选择下游接口（consumer）：", "添加依赖",
                JOptionPane.QUESTION_MESSAGE, null, apiLabels, apiLabels[0]);
        if (consumerLabel == null || consumerLabel.equals(producerLabel)) {
            Messages.showWarningDialog(project, "上游和下游不能是同一个接口", "添加依赖");
            return;
        }

        String sourcePath = JOptionPane.showInputDialog(
                null, "上游响应字段路径（如 data.id）：", "添加依赖", JOptionPane.QUESTION_MESSAGE);
        if (sourcePath == null || sourcePath.isBlank()) return;

        String targetParam = JOptionPane.showInputDialog(
                null, "下游目标参数名：", "添加依赖", JOptionPane.QUESTION_MESSAGE);
        if (targetParam == null || targetParam.isBlank()) return;

        // 找到对应的 uniqueKey
        String producerKey = findKeyByLabel(producerLabel);
        String consumerKey = findKeyByLabel(consumerLabel);
        if (producerKey == null || consumerKey == null) return;

        // 添加或合并
        ApiDependency existing = null;
        for (ApiDependency dep : dependencies) {
            if (dep.getProducerKey().equals(producerKey) && dep.getConsumerKey().equals(consumerKey)) {
                existing = dep;
                break;
            }
        }
        if (existing != null) {
            existing.getMappings().add(new ApiDependency.ValueMapping(sourcePath, targetParam));
        } else {
            ApiDependency dep = new ApiDependency(producerKey, consumerKey, "MANUAL");
            dep.getMappings().add(new ApiDependency.ValueMapping(sourcePath, targetParam));
            dependencies.add(dep);
        }

        fillTable();
    }

    private void deleteSelectedRow() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showWarningDialog(project, "请先选中一行", "删除");
            return;
        }

        String producerLabel = (String) tableModel.getValueAt(row, 0);
        String sourcePath = (String) tableModel.getValueAt(row, 1);
        String consumerLabel = (String) tableModel.getValueAt(row, 2);
        String targetParam = (String) tableModel.getValueAt(row, 3);

        String producerKey = findKeyByLabel(producerLabel);
        String consumerKey = findKeyByLabel(consumerLabel);

        if (producerKey != null && consumerKey != null) {
            dependencies.removeIf(dep -> {
                if (dep.getProducerKey().equals(producerKey) && dep.getConsumerKey().equals(consumerKey)) {
                    dep.getMappings().removeIf(m ->
                            m.getSourcePath().equals(sourcePath) && m.getTargetParam().equals(targetParam));
                    return dep.getMappings().isEmpty();
                }
                return false;
            });
        }

        fillTable();
    }

    private String findKeyByLabel(String label) {
        for (Map.Entry<String, String> e : labelByKey.entrySet()) {
            if (e.getValue().equals(label)) return e.getKey();
        }
        return null;
    }

    /**
     * 将编辑后的表格内容同步回 dependencies 列表
     */
    private void syncFromTable() {
        // 重建 dependencies from table
        Map<String, ApiDependency> byKey = new LinkedHashMap<>();
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            String producerLabel = String.valueOf(tableModel.getValueAt(r, 0));
            String sourcePath = String.valueOf(tableModel.getValueAt(r, 1));
            String consumerLabel = String.valueOf(tableModel.getValueAt(r, 2));
            String targetParam = String.valueOf(tableModel.getValueAt(r, 3));
            String detectionType = String.valueOf(tableModel.getValueAt(r, 4));

            if ("(无依赖)".equals(producerLabel)) continue;

            String producerKey = findKeyByLabel(producerLabel);
            String consumerKey = findKeyByLabel(consumerLabel);
            if (producerKey == null || consumerKey == null) continue;
            if (sourcePath.isBlank() || targetParam.isBlank()) continue;

            String key = producerKey + "->" + consumerKey;
            ApiDependency dep = byKey.computeIfAbsent(key, k -> {
                ApiDependency d = new ApiDependency(producerKey, consumerKey, detectionType);
                return d;
            });
            // 避免重复 mapping
            boolean exists = dep.getMappings().stream()
                    .anyMatch(m -> m.getSourcePath().equals(sourcePath) && m.getTargetParam().equals(targetParam));
            if (!exists) {
                dep.getMappings().add(new ApiDependency.ValueMapping(sourcePath, targetParam));
            }
        }
        this.dependencies = new ArrayList<>(byKey.values());
    }

    /**
     * 返回编辑后的依赖列表
     */
    public List<ApiDependency> getDependencies() {
        syncFromTable();
        return dependencies;
    }

    @Override
    protected void doOKAction() {
        syncFromTable();
        super.doOKAction();
    }
}
