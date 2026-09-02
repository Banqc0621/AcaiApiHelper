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
 *
 * <p>v2：一伦优化 R4 — 左上角"…"弹层去重，前置脚本&变量覆盖、AI 配置作为新 Tab 注入。
 * 外部通过 {@link #addTab(String, Icon, JComponent, String)} 追加 Tab 即可。
 * 在弹窗关闭时额外 Tab 的 {@link ExtraTab}（如需）也可参与保存，调用方自行在
 * doOKAction 前注册回调即可。</p>
 */
public class EnvAndDataManageDialog extends DialogWrapper {

    private final Project project;
    private final EnvironmentManagerDialog envDialog;
    private final JTabbedPane tabbedPane = new JTabbedPane();

    private Runnable pendingDataAction = null;
    private final List<DataManagePanel.Action> configActions = new ArrayList<>();
    private final List<DataManagePanel.Action> apiDataActions = new ArrayList<>();
    /** 关闭时（OK 前）依次调用的回调，外部可用于保存"前置脚本&变量覆盖"等实时表单 */
    private final List<Runnable> onCommit = new ArrayList<>();

    private final Consumer<Runnable> onDataActionChosen = pending -> {
        this.pendingDataAction = pending;
        close(OK_EXIT_CODE);
    };

    public EnvAndDataManageDialog(Project project,
                                  List<DataManagePanel.Action> configActions,
                                  List<DataManagePanel.Action> apiDataActions) {
        super(project);
        this.project = project;
        // 一伦优化 v24：构造时把异常从 logger 记一次，避免静默丢；上层 openEnvAndDataManageDialog
        // 的 try-catch 会捕获并向用户弹真实堆栈。
        EnvironmentManagerDialog env;
        try {
            env = new EnvironmentManagerDialog(project);
        } catch (Throwable t) {
            com.intellij.openapi.diagnostic.Logger.getInstance(EnvAndDataManageDialog.class)
                    .error("[RestAutoLab] EnvironmentManagerDialog 构造失败", t);
            throw t;
        }
        this.envDialog = env;
        this.configActions.addAll(configActions);
        this.apiDataActions.addAll(apiDataActions);
        setTitle("环境 & 数据管理");
        setSize(820, 600);
        init();
    }

    /**
     * 一伦优化 R4：追加一个 Tab。供"前置脚本&变量覆盖"、"AI 配置"等合并入口使用。
     *
     * @param title   Tab 标题
     * @param icon    Tab 图标
     * @param content Tab 内容
     * @param tip     Tab 悬浮提示
     */
    public void addTab(String title, Icon icon, JComponent content, String tip) {
        tabbedPane.addTab(title, icon, content, tip);
    }

    /**
     * 一伦优化 R4：注册"关闭时立即保存"的回调。
     * 用于把"前置脚本&变量覆盖"面板中编辑过的内容持久化（与 model 实时持久化可并存），
     * 让用户关闭对话框即可视为完成，无需额外的"确定"步骤。
     */
    public void addOnCommit(Runnable r) {
        if (r != null) onCommit.add(r);
    }

    /**
     * 一伦优化 R7：根据标题选中 Tab。
     * <p>供 {@code ApiDebuggerPanel.openExceptionRulesDialog()} 等入口调，
     * 让用户跳到「环境 & 数据」弹窗时直接落到目标 Tab。</p>
     * @return 是否找到并切到该 Tab（找不到返回 false，保持原选中）
     */
    public boolean selectTabByTitle(@NotNull String title) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (title.equals(tabbedPane.getTitleAt(i))) {
                tabbedPane.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 一伦优化 v23：暴露内嵌的 EnvironmentManagerDialog 引用，
     * 主面板用它安装实时联动回调（左侧字段改 → 右侧 envCombo 立即刷新）。
     */
    public EnvironmentManagerDialog getEnvDialog() {
        return envDialog;
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
        // #63：OK 左侧增加「应用」按钮——点击只保存不退出，OK 保存并退出。
        return new Action[]{new ApplyAction(), getOKAction()};
    }

    /** 把环境编辑 + 外部注册的 onCommit 回调（AI 配置等）统一提交，不关闭对话框。 */
    private void commitAll() {
        try {
            envDialog.applyChanges();
        } catch (Exception ex) {
            // 失败不阻断
        }
        for (Runnable r : onCommit) {
            try { r.run(); } catch (Exception ex) { /* 忽略单个失败 */ }
        }
    }

    /** #63：「应用」按钮动作——保存所有改动但不退出对话框。 */
    private final class ApplyAction extends DialogWrapperAction {
        private ApplyAction() {
            super("应用");
        }

        @Override
        protected void doAction(java.awt.event.ActionEvent e) {
            commitAll();
            // 「应用」只保存不退出：对话框保持打开，用户可继续编辑。
            // 不调 super.doAction / close —— 与 OK 的区别即在于此。
        }
    }

    @Override
    protected void doOKAction() {
        commitAll();
        super.doOKAction();
    }

    public Runnable getPendingDataAction() {
        return pendingDataAction;
    }
}
