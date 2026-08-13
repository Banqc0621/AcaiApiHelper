package com.hronline.ui;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.scanner.ApiScannerService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class RestAutoLabToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ApiTreePanel treePanel = new ApiTreePanel(project);
        ApiDebuggerPanel debuggerPanel = new ApiDebuggerPanel(project);

        JBSplitter splitter = new JBSplitter(false, RestAutoLabConstants.SPLITTER_PROPORTION);
        splitter.setFirstComponent(treePanel);
        splitter.setSecondComponent(debuggerPanel);
        // 解除子组件最小尺寸限制，使分割条可自由左右拖动（默认 honorComponentsMinimumSize=true 会因面板内大组件而拖不动）
        splitter.setHonorComponentsMinimumSize(false);
        // 持久化拖动比例，下次打开工具窗口自动恢复
        splitter.setSplitterProportionKey("RestAutoLabToolWindow.splitter.proportion");

        // 把 treePanel 注入到 debuggerPanel，使其能获取用户在树中的多选（用于 Markdown 导出）
        debuggerPanel.setTreePanel(treePanel);

        // 注册到持有服务，供编辑键 Action 拿到面板实例并精准定位到选中接口
        RestAutoLabToolWindowHolder.getInstance(project).setPanels(treePanel, debuggerPanel);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(splitter, BorderLayout.CENTER);

        Content content = ContentFactory.getInstance().createContent(mainPanel, null, false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);

        treePanel.setOnApiSelected(api -> debuggerPanel.loadApi(api, treePanel.getSelectedFolderId()));

        ApiScannerService scanner = ApiScannerService.getInstance(project);
        scanner.addListener(new ApiScannerService.ScanListener() {
            @Override
            public void onScanComplete(List<ApiDefinition> apis) { treePanel.updateTree(apis); }
            @Override
            public void onScanStarted() {}
        });

        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        if (settings.getState() != null && settings.getState().autoScanOnStartup) {
            ApplicationManager.getApplication().invokeLater(scanner::scanProjectApisAsync);
        }
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) { return true; }
}