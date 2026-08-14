package com.hronline.actions;

import com.hronline.RestAutoLabConstants;
import com.hronline.http.HttpExecutorService;
import com.hronline.model.ApiDefinition;
import com.hronline.model.TestProfile;
import com.hronline.model.TestReport;
import com.hronline.scanner.ApiScannerService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.hronline.ui.RestAutoLabToolWindowHolder;
import com.hronline.ui.ApiDebuggerPanel;
import com.hronline.ui.ApiTreePanel;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * plugin.xml 可直接实例化的公开 Action 容器。
 *
 * <p>各 Action 使用公开静态嵌套类，既保留单文件组织，也避免包级可见类被
 * IntelliJ 插件类加载器反射实例化时触发 IllegalAccessException。</p>
 */
public final class RestAutoLabActions {

    private RestAutoLabActions() {}

/**
 * 扫描项目API
 *
 * <p>增强：当光标位于 Controller 映射方法内时，扫描完成后自动在左侧树中定位并选中该接口，
 * 同时在右侧调试面板加载该接口，实现「右键 → 扫描项目API → 直接定位到选中接口」。
 * 否则退化为原有行为（仅扫描 + 打开面板）。</p>
 */
public static final class ScanApisAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // 识别光标所在的 Controller 映射方法（选中代码时也以光标位置为准）
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        final CursorApiRef cursorRef = (editor != null && psiFile != null)
                ? resolveCursorApi(project, editor, psiFile) : null;

        ApiScannerService scanner = ApiScannerService.getInstance(project);

        // 始终触发扫描：扫描会重建树并显示全部接口（这是「扫描项目API」的核心职责）。
        // 若光标在接口方法上，注册一次性监听器，扫描完成后顺带定位到该接口。
        if (cursorRef != null) {
            final String filePath = cursorRef.filePath;
            final int lineNumber = cursorRef.lineNumber;
            ApiScannerService.ScanListener oneShot = new ApiScannerService.ScanListener() {
                @Override
                public void onScanStarted() {}
                @Override
                public void onScanComplete(List<ApiDefinition> apis) {
                    scanner.removeListener(this);
                    // 扫描完成后树已重建并显示全部接口；再定位到光标接口
                    ApiDefinition hit = scanner.findApiByFileAndLine(filePath, lineNumber);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (hit != null) {
                            locateApi(project, hit);
                        }
                    });
                }
            };
            scanner.addListener(oneShot);
        }

        // 触发扫描（带进度条）。扫描完成会重建树（显示全部接口）并回调上面的监听器。
        scanner.scanProjectApisAsync();

        // 立即激活面板，避免扫描期间用户看不到工具窗口
        activateToolWindow(project);
    }

    /** 在树中选中接口 + 调试面板加载该接口（不重新激活窗口，调用方已激活） */
    private static void locateApi(Project project, ApiDefinition api) {
        RestAutoLabToolWindowHolder holder = RestAutoLabToolWindowHolder.getInstance(project);
        ApiTreePanel treePanel = holder.getTreePanel();
        ApiDebuggerPanel debuggerPanel = holder.getDebuggerPanel();
        if (treePanel != null) treePanel.selectApi(api);
        if (debuggerPanel != null) debuggerPanel.loadApi(api);
    }

    private static void activateToolWindow(Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(RestAutoLabConstants.TOOLWINDOW_ID);
        if (tw != null) tw.activate(null);
    }

    /** 解析光标位置所在的 Controller 映射方法，返回其源码文件路径与声明行号；非接口方法返回 null。 */
    @Nullable
    private CursorApiRef resolveCursorApi(Project project, Editor editor, PsiFile psiFile) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null) return null;
        // 优先向上找方法标识符所属方法，兼容光标在方法名/方法体内任意位置
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        // 仅当方法带 Spring/JAX-RS 映射注解时才认为是接口，避免普通方法也触发定位
        if (!isMappingMethod(method)) return null;

        com.intellij.openapi.vfs.VirtualFile vFile = psiFile.getVirtualFile();
        if (vFile == null) return null;
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) return null;
        int lineNumber = document.getLineNumber(method.getTextOffset()) + 1;
        return new CursorApiRef(vFile.getPath(), lineNumber);
    }

    /** 判断方法是否带 HTTP 映射注解（Spring *Mapping 或 JAX-RS @GET/@POST 等） */
    private boolean isMappingMethod(PsiMethod method) {
        for (String fqn : RestAutoLabConstants.SPRING_MAPPING_ANNOTATIONS) {
            if (method.getAnnotation(fqn) != null) return true;
        }
        for (String fqn : RestAutoLabConstants.JAXRS_METHOD_ANNOTATIONS) {
            if (method.getAnnotation(fqn) != null) return true;
        }
        return false;
    }

    /** 光标接口引用：源码文件路径 + 方法声明行号 */
    private static final class CursorApiRef {
        final String filePath;
        final int lineNumber;
        CursorApiRef(String filePath, int lineNumber) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}

/**
 * 调试当前光标所在接口
 *
 * <p>增强：识别光标所在 Controller 映射方法，扫描后精准定位到该接口（树选中 + 调试面板加载）。
 * 若光标不在接口方法上，提示用户。若尚未扫描，触发扫描并在完成后定位。</p>
 */
public static final class DebugApiAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        var editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || psiFile == null) return;

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

        if (method == null) {
            Messages.showWarningDialog(project, "请将光标定位到Controller方法上", "调试当前接口");
            return;
        }

        com.intellij.openapi.vfs.VirtualFile vFile = psiFile.getVirtualFile();
        if (vFile == null) {
            Messages.showWarningDialog(project, "无法定位文件，请确保代码已保存", "调试当前接口");
            return;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) return;
        final String filePath = vFile.getPath();
        final int lineNumber = document.getLineNumber(method.getTextOffset()) + 1;

        ApiScannerService scanner = ApiScannerService.getInstance(project);
        // 已有缓存则直接定位
        ApiDefinition hit = scanner.findApiByFileAndLine(filePath, lineNumber);
        if (hit != null) {
            activateAndLocate(project, hit);
            return;
        }

        // 缓存为空或未匹配：触发扫描，完成后定位
        ApiScannerService.ScanListener oneShot = new ApiScannerService.ScanListener() {
            @Override
            public void onScanStarted() {}
            @Override
            public void onScanComplete(List<ApiDefinition> apis) {
                scanner.removeListener(this);
                ApiDefinition found = scanner.findApiByFileAndLine(filePath, lineNumber);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (found != null) {
                        activateAndLocate(project, found);
                    } else {
                        Messages.showInfoMessage(project,
                                "未在当前光标方法上识别到接口（可能不是Controller映射方法）。",
                                "调试当前接口");
                    }
                });
            }
        };
        scanner.addListener(oneShot);
        scanner.scanProjectApisAsync();
        activateToolWindow(project);
    }

    /** 激活 ToolWindow 并在树中选中 + 调试面板加载指定接口 */
    private static void activateAndLocate(Project project, ApiDefinition api) {
        activateToolWindow(project);
        RestAutoLabToolWindowHolder holder = RestAutoLabToolWindowHolder.getInstance(project);
        ApiTreePanel treePanel = holder.getTreePanel();
        ApiDebuggerPanel debuggerPanel = holder.getDebuggerPanel();
        if (treePanel != null) treePanel.selectApi(api);
        if (debuggerPanel != null) debuggerPanel.loadApi(api);
    }

    private static void activateToolWindow(Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(RestAutoLabConstants.TOOLWINDOW_ID);
        if (tw != null) tw.activate(null);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }
}

/**
 * 运行全部接口测试
 */
public static final class RunAllTestsAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> apis = scanner.getCachedApis();
        if (apis.isEmpty()) {
            Messages.showWarningDialog(project, "暂无已扫描的API，请先执行「扫描项目API」", "提示");
            return;
        }
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpExecutorService httpService = HttpExecutorService.getInstance(project);
            TestProfile profile = new TestProfile();
            profile.setName("手动全量测试");
            profile.setBaseUrl(settings.getBaseUrl());
            TestReport report = httpService.executeBatchTest(apis, profile);
            ApplicationManager.getApplication().invokeLater(() -> showReport(project, report));
        });
    }

    private void showReport(Project project, TestReport report) {
        String summary = report.generateSummary();
        if (report.isAllPassed()) {
            Messages.showInfoMessage(project, summary, "RestAutoLab 测试报告 - 全部通过");
        } else {
            Messages.showWarningDialog(project, summary, "RestAutoLab 测试报告 - 存在失败");
        }
        try {
            NotificationType type = report.isAllPassed() ? NotificationType.INFORMATION : NotificationType.WARNING;
            NotificationGroupManager.getInstance().getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                    .createNotification(
                            "测试完成: " + report.getPassedCount() + "/" + report.getResults().size() + " 通过",
                            String.format("通过率: %.1f%% | 总耗时: %dms", report.getPassRate(), report.getTotalDuration()),
                            type
                    ).notify(project);
        } catch (Exception ignored) {}
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}

}
