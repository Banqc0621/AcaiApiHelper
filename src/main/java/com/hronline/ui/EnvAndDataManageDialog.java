package com.hronline.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一伦优化 #3：合并「环境管理」与「数据管理」到同一个对话框。
 * <p>原工具栏上两个独立按钮（环境管理 / 数据管理）现在统一为一个入口
 * 「环境 & 数据」 → 打开本对话框，使用 JTabbedPane 在「环境」「数据」两个 Tab 间切换。</p>
 */
public class EnvAndDataManageDialog extends DialogWrapper {

    private final Project project;
    private final EnvironmentManagerDialog envDialog;
    private final JTabbedPane tabbedPane = new JTabbedPane();

    private Runnable pendingDataAction = null;
    private final List<DataManagePanel.Action> configActions = new ArrayList<>();
    private final List<DataManagePanel.Action> apiDataActions = new ArrayList<>();

    private final Consumer<Runnable> onDataActionChosen = pending -> {
        this.pendingDataAction = pending;
        close(OK_EXIT_CODE);
    };

    public EnvAndDataManageDialog(Project project,
                                  List<DataManagePanel.Action> configActions,
                                  List<DataManagePanel.Action> apiDataActions) {
        super(project);
        this.project = project;
        this.envDialog = new EnvironmentManagerDialog(project);
        this.configActions.addAll(configActions);
        this.apiDataActions.addAll(apiDataActions);
        setTitle("环境 & 数据管理");
        setSize(820, 600);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_BODY));

        JComponent envContent = envDialog.createCenterPanel();
        envContent.setBorder(JBUI.Borders.empty());
        JScrollPane envScroll = new JScrollPane(envContent);
        envScroll.setBorder(JBUI.Borders.empty());
        envScroll.getVerticalScrollBar().setUnitIncrement(16);
        tabbedPane.addTab("环境", AllIcons.Nodes.Plugin, envScroll, "环境列表 / 变量 / 全局请求头");

        List<DataManagePanel.Section> sections = new ArrayList<>();
        sections.add(new DataManagePanel.Section(
                "配置", "AI 设置 · 环境配置 · 测试配置", new ArrayList<>(configActions)));
        sections.add(new DataManagePanel.Section(
                "接口数据", "全量接口定义 · 已测接口测试数据", new ArrayList<>(apiDataActions)));

        JPanel dataContent = DataManagePanel.build(sections, onDataActionChosen);
        tabbedPane.addTab("数据", AllIcons.ToolbarDecorator.Export, dataContent, "导入/导出测试配置与接口数据");

        return tabbedPane;
    }

    @Override
    @NotNull
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected void doOKAction() {
        try {
            envDialog.applyChanges();
        } catch (Exception ex) {
            // 失败不阻断关闭
        }
        super.doOKAction();
    }

    public Runnable getPendingDataAction() {
        return pendingDataAction;
    }
}
