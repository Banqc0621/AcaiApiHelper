package com.hronline.ui;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 项目级服务：持有 ToolWindow 中的 ApiTreePanel 与 ApiDebuggerPanel 引用。
 *
 * <p>因为这两个面板在 {@link RestAutoLabToolWindowFactory#createToolWindowContent} 中以局部变量创建，
 * 编辑器右键菜单的 Action（如「扫描项目API」定位到选中接口）无法直接拿到它们。
 * 通过本服务做一次注册，Action 即可拿到面板实例，调用 {@link ApiTreePanel#selectApi}
 * 与 {@link ApiDebuggerPanel#loadApi} 实现精准定位。</p>
 */
public final class RestAutoLabToolWindowHolder {

    private ApiTreePanel treePanel;
    private ApiDebuggerPanel debuggerPanel;

    public void setPanels(@NotNull ApiTreePanel treePanel, @NotNull ApiDebuggerPanel debuggerPanel) {
        this.treePanel = treePanel;
        this.debuggerPanel = debuggerPanel;
    }

    public ApiTreePanel getTreePanel() { return treePanel; }

    public ApiDebuggerPanel getDebuggerPanel() { return debuggerPanel; }

    public static RestAutoLabToolWindowHolder getInstance(@NotNull Project project) {
        return project.getService(RestAutoLabToolWindowHolder.class);
    }
}