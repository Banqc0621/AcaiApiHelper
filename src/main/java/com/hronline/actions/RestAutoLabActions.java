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
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

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

    /** update() 只读 project，声明 BGT 让平台在后台线程更新，兼容新版 EDT 限制 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
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

    /** update() 只读 project/editor，声明 BGT 让平台在后台线程更新，兼容新版 EDT 限制 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
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

    /** update() 只读 project，声明 BGT 让平台在后台线程更新，兼容新版 EDT 限制 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}

/**
 * 仅显示此包接口（项目视图右键包 → 收窄左侧全量接口列表）
 *
 * <p>将右键包的完整包名写入「扫描包过滤」配置（<b>替换语义</b>：只保留当前右键的包，
 * 与「仅显示当前包的接口」的预期一致），随后：</p>
 *
 * <p><b>支持任意层级包，不必右键到 controller 层</b>：过滤按「包前缀 + 段边界」匹配
 * （见 {@code ApiScannerService.matchesPackagePrefix}），右键
 * {@code cn.hollis.nft.turbo.auth} 即覆盖其下所有子包（含 {@code .controller}）中的接口；
 * 即时收窄按「源文件位于右键目录下」判定，同样天然包含子目录。</p>
 * <ol>
 *   <li><b>立即收窄</b>：用「源文件位于右键目录下」对已扫描缓存做即时过滤并刷新左侧树，
 *       用户无需等待重扫即可看到效果；</li>
 *   <li><b>重扫固化</b>：触发异步重扫，扫描层按包前缀精确裁剪，结果覆盖缓存作为持久视图。</li>
 * </ol>
 * <p>点击左侧「全量」按钮会清空过滤并重扫，恢复全量列表（闭环）。</p>
 *
 * <p><b>可见性教训（#51 四次修复）</b>：旧实现在 {@link #update} 里以「PSI 能否解析出包名」
 * 决定菜单项可见性——解析任一环节返回空，菜单项就静默消失，{@link #actionPerformed}
 * 永远得不到执行（沙箱日志实锤：actionPerformed 日志 0 次）。现在 update 只做
 * 「选中包含目录」的轻量判定（不读 PSI），保证菜单项稳定出现；包名解析移到点击时执行，
 * PSI 失败时回退按目录路径推导包名，仍失败则给出明确弹窗提示，不再静默失败。</p>
 */
public static final class AddToScanPackageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(AddToScanPackageAction.class);

    /** update() 只读 VIRTUAL_FILE_ARRAY（轻量判定），声明 BGT 兼容新版线程模型 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        List<VirtualFile> dirs = new java.util.ArrayList<>();
        List<VirtualFile> javaFiles = new java.util.ArrayList<>();
        if (files != null) {
            for (VirtualFile vf : files) {
                if (vf == null) continue;
                if (vf.isDirectory()) {
                    dirs.add(vf);
                } else if (isJavaOrKtFile(vf)) {
                    // 单文件右键支持（#53 新增）：右键 1 个或多个 .java/.kt 文件，
                    // 收窄到「这些文件内定义的接口」——常见诉求：调试单文件时不想被全包淹没
                    javaFiles.add(vf);
                }
            }
        }
        LOG.info("仅显示此包接口：触发，目录数 = " + dirs.size() + "，文件数 = " + javaFiles.size());
        if (dirs.isEmpty() && javaFiles.isEmpty()) {
            Messages.showWarningDialog(project,
                    "请先在项目视图中右键一个包目录或 .java/.kt 源文件。", "仅显示此包接口");
            return;
        }

        ApiScannerService scanner = ApiScannerService.getInstance(project);

        List<String> packageNames = java.util.Collections.emptyList();
        try {
            // 一伦反馈 #56：移除所有「宽松匹配」降级（磁盘遍历/向上寻包/模块描述符猜 basename），
            // 用户明确要求「没扫到就是没扫到」「不要降级」——这些降级会把同名但不同包的
            // Controller 误收进右键目录的结果里（典型：右键 my-feature（无 Controller）
            // 看到 com.myfeature.* 下的接口）。
            // 现在只剩 3 种严格解析：PSI / 路径标记 / 已扫描接口目录归属；解析失败 → 直接给空反馈，
            // 不再静默尝试"猜"包名。
            //
            // 1) PSI 解析包名（标准路径）
            packageNames = resolvePackageNames(project, dirs.toArray(new VirtualFile[0]));
            LOG.info("仅显示此包接口：PSI 解析包名 = " + packageNames);
            // 2) 回退：按目录路径推导（Maven/Gradle 标准 src/main/java 布局）
            if (packageNames.isEmpty()) {
                packageNames = resolvePackageNamesByPath(dirs);
                LOG.info("仅显示此包接口：路径推导包名 = " + packageNames);
            }
            // 3) 回退：模块根目录等非源码目录——收集已扫描接口中位于该目录下的源文件所属包。
            //    用户常直接右键模块根（如 nft-turbo-gateway），此时按「目录下的已扫描接口」
            //    推导包集合，同样能实现「仅显示该模块下接口」的诉求。
            //    注意：这不是降级，而是利用已有数据精确反推，不会把同名异包的接口误收进来。
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromCachedApis(scanner.getCachedApis(), dirs);
                LOG.info("仅显示此包接口：已扫描接口推导包名 = " + packageNames);
            }
            // 4) 单文件右键快路径：右键 1 个或多个 .java/.kt 文件但目录链解析失败时，
            //    读文件首部 package 声明（这是用户明确选中的文件里的真实内容，不是猜）。
            if (packageNames.isEmpty() && !javaFiles.isEmpty()) {
                List<String> filePackages = resolvePackagesFromFiles(javaFiles);
                LOG.info("仅显示此包接口：文件推导包名 = " + filePackages);
                if (!filePackages.isEmpty()) {
                    packageNames = filePackages;
                }
            }
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            // 用户按 Esc 或 IDE 因内存压力取消时不弹错、记录日志后正常退出
            LOG.warn("仅显示此包接口：包名解析被取消（ProcessCanceledException），右键目录 = " + dirs);
            return;
        } catch (Exception ex) {
            LOG.warn("仅显示此包接口：包名解析异常", ex);
            try {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                        .createNotification("仅显示此包接口",
                                "解析右键目录的包名时发生异常：" + ex.getMessage(),
                                NotificationType.WARNING)
                        .notify(project);
            } catch (Exception ignored) {}
            return;
        }
        if (packageNames.isEmpty()) {
            // 兜底链全部失败（无 PSI / 无路径标记 / 无缓存 / 无磁盘源文件 / 无祖先链源 /
            // 无模块描述符 / 无文件推导）时不再弹模态阻塞对话框，改用通知气泡轻提示，
            // 并把右键目录路径写入诊断日志便于排查——之前强弹窗被反馈「始终处理不好」。
            // 一伦反馈 #55：当前右键目录若无对外 Controller，必须立即清空树并清空
            // 上一次的过滤，**不再保留 / 显示上一次的扫描结果**——「没有就是没有」
            // 是契约，不允许任何形式的"降级显示"（既不能按目录名猜包，也不能挂着
            // 旧 filter 让旧缓存继续显示）。用户用左侧「全量」按钮恢复全量列表。
            LOG.warn("仅显示此包接口：兜底链全失败（当前右键目录/文件无对外 Controller 接口），右键目录 = "
                    + dirs + "，右键文件 = " + javaFiles);
            try {
                String selectionDesc;
                if (!javaFiles.isEmpty()) {
                    selectionDesc = "右键文件：" + javaFiles.get(0).getPath();
                } else if (!dirs.isEmpty()) {
                    selectionDesc = "右键目录：" + dirs.get(0).getPath();
                } else {
                    selectionDesc = "右键项：(空)";
                }
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                        .createNotification(
                                "仅显示此包接口",
                                "所选目录/文件中没有可解析的 Java/Kotlin 源文件，过滤未生效。\n" +
                                        selectionDesc +
                                        "\n（这是预期的【无】——左侧树已清空，无降级显示）" +
                                        "\n如需恢复全量列表，请点击左侧「全量」按钮。",
                                NotificationType.WARNING)
                        .notify(project);
            } catch (Exception ex) {
                Messages.showWarningDialog(project,
                        "所选目录/文件中没有可解析的 Java/Kotlin 源文件。如需恢复全量列表，请点击左侧「全量」按钮。",
                        "仅显示此包接口");
            }
            // 清空过滤 + 清空树 + 不触发重扫——按用户契约：右键无 Controller 的目录，
            // 看不到任何东西；用户主动点「全量」才会再重扫
            RestAutoLabSettingsState.getInstance(project).setScanPackageFilter("");
            try {
                RestAutoLabToolWindowHolder holder = RestAutoLabToolWindowHolder.getInstance(project);
                ApiTreePanel treePanel = holder.getTreePanel();
                if (treePanel != null) {
                    treePanel.updateTree(java.util.Collections.emptyList());
                    LOG.info("仅显示此包接口：兜底链失败，已清空树（清掉上次扫描结果，不做降级显示）");
                }
            } catch (Exception ignored) {
                // 工具窗口未初始化等异常——吞掉，不阻断通知气泡
            }
            // 激活工具窗口，让用户立即看到清空后的树
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(RestAutoLabConstants.TOOLWINDOW_ID);
            if (tw != null) tw.activate(null);
            return;
        }

        // 3) 写入过滤配置（替换语义：只保留当前右键的包）
        String newFilter = String.join(",", packageNames);
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        settings.setScanPackageFilter(newFilter);
        LOG.info("仅显示此包接口：过滤配置更新为 [" + newFilter + "]");

        // 4) 立即收窄：用「源文件位于右键目录下」或「源文件路径等于选中文件」
        //    即时过滤已扫描缓存并刷新左侧树，用户不必等重扫就能看到收窄效果
        //    （#53 新增文件精确匹配：单文件右键时按 .java/.kt 路径精准收窄到该文件内接口）
        List<ApiDefinition> cached = scanner.getCachedApis();
        if (!cached.isEmpty()) {
            // 预先归一化选中文件的路径（大小写不敏感按平台默认值）
            java.util.Set<String> selectedFilePaths = new java.util.HashSet<>();
            for (VirtualFile f : javaFiles) {
                if (f != null) selectedFilePaths.add(f.getPath());
            }
            List<ApiDefinition> narrowed = new java.util.ArrayList<>();
            for (ApiDefinition api : cached) {
                String path = api.getSourceFilePath();
                if (path == null || path.isEmpty()) continue;
                // a) 文件精确匹配（最高优先级：仅匹配选中的 .java/.kt 路径本身）
                if (!selectedFilePaths.isEmpty()) {
                    boolean hit = false;
                    for (String fp : selectedFilePaths) {
                        if (path.equals(fp)) { hit = true; break; }
                    }
                    if (hit) { narrowed.add(api); continue; }
                    // 选中文件但当前接口不在选中文件里 → 即使在选中目录下也不算（用户明确选文件）
                    continue;
                }
                // b) 目录前缀匹配（原有行为：所有选中目录下源文件接口都收窄）
                for (VirtualFile dir : dirs) {
                    String dirPath = dir.getPath();
                    if (path.equals(dirPath) || path.startsWith(dirPath + "/")) {
                        narrowed.add(api);
                        break;
                    }
                }
            }
            LOG.info("仅显示此包接口：即时过滤 缓存 " + cached.size() + " -> " + narrowed.size()
                    + "（选中目录数 = " + dirs.size() + "，选中文件数 = " + javaFiles.size() + "）");
            RestAutoLabToolWindowHolder holder = RestAutoLabToolWindowHolder.getInstance(project);
            ApiTreePanel treePanel = holder.getTreePanel();
            if (treePanel != null) treePanel.updateTree(narrowed);
        }
        // 激活工具窗口，让用户立即看到收窄效果
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(RestAutoLabConstants.TOOLWINDOW_ID);
        if (tw != null) tw.activate(null);

        // 5) 触发重扫（扫描层按包前缀精确裁剪，完成后覆盖缓存为持久的过滤结果）
        // 一伦 #56：先弹一条轻量级「正在收窄」提示，立即给反馈；扫描真正完成后，
        // 再用一次性监听器根据实际命中数发"发现 X 个"或"未发现接口（0 个）"的明确反馈。
        // 满足用户的「没有就显示没有、有就显示有」诉求——不再静默失败、不再降级到上一份缓存。
        scanner.scanProjectApisAsync();

        String immediateTitle = "仅显示此包接口";
        String immediateContent;
        if (!javaFiles.isEmpty()) {
            immediateContent = "已收窄为 " + javaFiles.size() + " 个源文件内的接口，正在重新扫描...\n" +
                    "（单文件/多文件右键——按文件精确匹配，非文件内接口会被过滤）\n" +
                    "点击左侧「全量」按钮可恢复全量列表";
        } else {
            immediateContent = "已收窄为包 " + String.join(", ", packageNames)
                    + " 及其子包下的接口，正在重新扫描...\n"
                    + "（支持右键任意层级包，无需精确到 controller 层）\n"
                    + "点击左侧「全量」按钮可恢复全量列表";
        }
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                    .createNotification(immediateTitle, immediateContent, NotificationType.INFORMATION)
                    .notify(project);
        } catch (Exception ex) {
            Messages.showInfoMessage(project, immediateContent, immediateTitle);
        }

        // 一次性监听器：扫描完成后按实际命中数发明确反馈
        final java.util.List<String> finalPackageNames = java.util.Collections.unmodifiableList(packageNames);
        final int fileCount = javaFiles.size();
        ApiScannerService.ScanListener oneShot = new ApiScannerService.ScanListener() {
            @Override
            public void onScanStarted() {}
            @Override
            public void onScanComplete(List<ApiDefinition> apis) {
                scanner.removeListener(this);
                int hitCount = apis == null ? 0 : apis.size();
                StringBuilder sb = new StringBuilder();
                if (fileCount > 0) {
                    sb.append("已收窄为 ").append(fileCount).append(" 个源文件内的接口");
                } else if (finalPackageNames.size() == 1) {
                    sb.append("已收窄为包 ").append(finalPackageNames.get(0));
                } else {
                    sb.append("已收窄为包 ").append(String.join(", ", finalPackageNames));
                }
                sb.append("：\n");
                if (hitCount == 0) {
                    // 明确反馈"没有"——用户原话：「如果没有就显示没有」。
                    // 这里没有兜底到上次扫描缓存，是用户明确要求的「没扫到就是没扫到」。
                    sb.append("未发现对外 Controller 接口（0 个）\n");
                    if (fileCount > 0) {
                        sb.append("（所选源文件内未含 Controller 接口——按文件精确匹配，非文件内接口会被过滤）\n");
                    } else {
                        sb.append("（所选包及其子包下未含对外 Controller 接口——扫描结果为真实的【无】）\n");
                    }
                    sb.append("如需恢复全量列表，请点击左侧「全量」按钮");
                    try {
                        NotificationGroupManager.getInstance()
                                .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                                .createNotification(immediateTitle, sb.toString(), NotificationType.WARNING)
                                .notify(project);
                    } catch (Exception ex) {
                        Messages.showWarningDialog(project, sb.toString(), immediateTitle);
                    }
                } else {
                    // 明确反馈"有 + 个数"——用户原话：「如果有就显示有」。
                    sb.append("发现 ").append(hitCount).append(" 个接口\n");
                    sb.append("（实时扫描结果——非上次扫描缓存；点击左侧「全量」按钮可恢复全量列表）");
                    try {
                        NotificationGroupManager.getInstance()
                                .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                                .createNotification(immediateTitle, sb.toString(), NotificationType.INFORMATION)
                                .notify(project);
                    } catch (Exception ex) {
                        Messages.showInfoMessage(project, sb.toString(), immediateTitle);
                    }
                }
            }
        };
        scanner.addListener(oneShot);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean visible = false;
        if (project != null && !project.isDisposed()) {
            // 只做轻量判定：选中包含目录或 .java/.kt 文件即显示（#53 新增文件支持）。
            // 不在此处做 PSI 包名解析——以解析结果决定可见性，会让任一解析波动
            // 导致菜单项静默消失（#51 根因）。包名解析放到 actionPerformed 中执行，
            // 失败也有明确弹窗反馈。
            VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (files != null) {
                for (VirtualFile vf : files) {
                    if (vf == null) continue;
                    if (vf.isDirectory() || isJavaOrKtFile(vf)) { visible = true; break; }
                }
            }
        }
        e.getPresentation().setEnabledAndVisible(visible);
    }

    /**
     * 回退包名推导：按目录路径中的源码根标记（src/main/java 等标准布局）截取相对路径并转为包名。
     * <p>仅当 PSI 反查失败时兜底使用；无法识别返回空列表。</p>
     */
    /**
     * 文件后缀判定（仅 .java / .kt）。右键单文件时用于识别源文件，
     * 避免把 .class / .jar 等附属文件当成源文件去推包名。
     */
    private static boolean isJavaOrKtFile(@NotNull VirtualFile vf) {
        String ext = vf.getExtension();
        return "java".equalsIgnoreCase(ext) || "kt".equalsIgnoreCase(ext);
    }

    /**
     * 单文件右键包名推导（#53 新增，#58 修正）：从 1 个或多个 .java/.kt 文件推包名。
     * <p><b>#58 修正</b>：旧版用 {@link #aggregateToCommonPrefix} 把多文件的包名聚合成最长公共前缀，
     * 导致多文件跨子包时（如 auth.controller + auth.service）公共前缀退到 auth.* —— 用户看到比预期更广的结果。
     * 现在改为：每个文件独立取包名，按出现顺序去重，返回所有不同包名（逗号拼接传给过滤器）。</p>
     * <p>推导路径：先按路径标记（src/main/java 等）截；截不到再读 package 声明（最多 DISK_READ_PACKAGE_LIMIT 个）。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromFiles(@NotNull List<VirtualFile> files) {
        // LinkedHashSet 保证「首次出现顺序」稳定，单测断言与日志输出都更可读
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
        int readCount = 0;
        for (VirtualFile vf : files) {
            if (vf == null || !isJavaOrKtFile(vf)) continue;
            String pkg = packageFromSourcePath(vf.getPath());
            if (pkg == null && readCount < DISK_READ_PACKAGE_LIMIT) {
                readCount++;
                pkg = readPackageDeclaration(vf);
            }
            if (pkg != null && !pkg.isEmpty()) packages.add(pkg);
        }
        return new java.util.ArrayList<>(packages);
    }

    // 注：#58 删除了 aggregateToCommonPrefix——多文件跨子包时聚合到公共前缀会让过滤范围过宽
    // （auth.controller + auth.service 退化为 auth.*），改由 resolvePackagesFromFiles / resolvePackagesFromPathStrings
    // 直接返回每个文件的包名。longestCommonPackagePrefix 仍保留作为核心纯字符串 helper 单测守住边界。

    /**
     * 文件粒度包名推导（可测试纯字符串版，#53 新增）：与 {@link #resolvePackagesFromFiles}
     * 行为一致，但接受路径字符串+可选内容回调，便于单测在无 VFS 的沙箱里直接验证。
     * <p><b>#58 修正</b>：与 resolvePackagesFromFiles 同步改为「按文件独立返回包名」——
     * 多文件跨子包时不再聚合成最长公共前缀（auth.controller + auth.service 之前会被退化成
     * auth.*，过宽；现在按出现顺序返回所有不同包名）。</p>
     * <p>每个文件按路径标记截包；截不到时回调 reader 读取首部 package 声明。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromPathStrings(@NotNull List<String> paths,
                                                       @NotNull java.util.function.Function<String, String> reader) {
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
        int readCount = 0;
        for (String path : paths) {
            if (path == null) continue;
            String pkg = packageFromSourcePath(path);
            if (pkg == null && readCount < DISK_READ_PACKAGE_LIMIT) {
                readCount++;
                String content = reader.apply(path);
                if (content != null) {
                    java.util.regex.Matcher m = PACKAGE_DECL.matcher(content);
                    if (m.find()) pkg = m.group(1);
                }
            }
            if (pkg != null && !pkg.isEmpty()) packages.add(pkg);
        }
        return new java.util.ArrayList<>(packages);
    }

    @NotNull
    static List<String> resolvePackageNamesByPath(@NotNull List<VirtualFile> dirs) {
        List<String> result = new java.util.ArrayList<>();
        String[] markers = {"/src/main/java/", "/src/test/java/", "/src/main/kotlin/", "/src/test/kotlin/"};
        for (VirtualFile dir : dirs) {
            if (dir == null) continue;
            String path = dir.getPath() + "/";
            for (String marker : markers) {
                int idx = path.indexOf(marker);
                if (idx >= 0) {
                    String pkg = path.substring(idx + marker.length()).replace('/', '.');
                    while (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                    if (!pkg.isEmpty() && !result.contains(pkg)) result.add(pkg);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 回退包名推导（模块根等非源码目录）：收集已扫描接口中源文件位于所选目录下的接口，
     * 取其源文件所在包名作为过滤集合——实现「右键模块根 → 仅显示该模块下接口」。
     * <p>源文件路径按 src/main/java 等标记截取包路径；无标记时取文件父目录相对项目根的最后一段近似处理失败则跳过。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromCachedApis(@NotNull List<ApiDefinition> apis, @NotNull List<VirtualFile> dirs) {
        List<String> result = new java.util.ArrayList<>();
        String[] markers = {"/src/main/java/", "/src/test/java/", "/src/main/kotlin/", "/src/test/kotlin/"};
        for (ApiDefinition api : apis) {
            String path = api.getSourceFilePath();
            if (path == null || path.isEmpty()) continue;
            boolean underDir = false;
            for (VirtualFile dir : dirs) {
                if (dir == null) continue;
                String dirPath = dir.getPath();
                if (path.equals(dirPath) || path.startsWith(dirPath + "/")) { underDir = true; break; }
            }
            if (!underDir) continue;
            for (String marker : markers) {
                int idx = path.indexOf(marker);
                if (idx >= 0) {
                    String rel = path.substring(idx + marker.length());
                    int lastSlash = rel.lastIndexOf('/');
                    if (lastSlash > 0) {
                        String pkg = rel.substring(0, lastSlash).replace('/', '.');
                        if (!pkg.isEmpty() && !result.contains(pkg)) result.add(pkg);
                    }
                    break;
                }
            }
        }
        return result;
    }

    /** 一伦反馈 #56：所有「宽松匹配」降级兜底（磁盘遍历 / 向上寻包 / 模块描述符 / 目录名猜包）
 *  已删除——用户明确要求「没扫到就是没扫到」「不要降级」。
 *  曾经的 4-7 级兜底会把同名但不同包的 Controller 误收进右键目录结果里
 *  （典型：右键 my-feature（无 Controller）看到 com.myfeature.* 下的接口）。
 *  解析失败 → 直接给空反馈，不再静默尝试"猜"包名。 */

    /** 单文件右键包名推导中「读 package 声明」采样的上限（路径标记能推导的无需读文件，故采样数很小） */
    private static final int DISK_READ_PACKAGE_LIMIT = 50;

    /** package 声明识别：仅匹配行首声明，避免被注释/字符串里的 "package" 误导 */
    private static final java.util.regex.Pattern PACKAGE_DECL =
            java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?");

/** 按 src/main/java 等标准布局标记从源文件路径推导包名；无标记返回 null */
    @Nullable
    private static String packageFromSourcePath(String path) {
        if (path == null) return null;
        String[] markers = {"/src/main/java/", "/src/test/java/", "/src/main/kotlin/", "/src/test/kotlin/"};
        for (String marker : markers) {
            int idx = path.indexOf(marker);
            if (idx >= 0) {
                String rel = path.substring(idx + marker.length());
                int lastSlash = rel.lastIndexOf('/');
                if (lastSlash > 0) {
                    return rel.substring(0, lastSlash).replace('/', '.');
                }
                return null; // 源码根直属文件，无包名
            }
        }
        return null;
    }

    /** 读源文件首部 package 声明（最多扫 200 行）；读失败或无声明返回 null */
    @Nullable
    private static String readPackageDeclaration(@NotNull VirtualFile file) {
        try {
            String content = new String(file.contentsToByteArray(), file.getCharset());
            int max = Math.min(content.length(), 8000); // 包声明必在文件首部，无需读全文
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.StringReader(content.substring(0, max)));
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 200) {
                java.util.regex.Matcher m = PACKAGE_DECL.matcher(line);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {
            // 单文件读取失败不阻断整体推导
        }
        return null;
    }

    /**
     * 求包名集合的最长公共包前缀（按段边界）。
     * <p>如 {cn.hollis.nft.turbo.auth, cn.hollis.nft.turbo.auth.controller} →
     * {@code cn.hollis.nft.turbo.auth}；单元素集合原样返回。</p>
     */
    @NotNull
    static String longestCommonPackagePrefix(@NotNull Set<String> packages) {
        if (packages.isEmpty()) return "";
        List<String> sorted = new java.util.ArrayList<>(packages);
        java.util.Collections.sort(sorted);
        String first = sorted.get(0);
        String last = sorted.get(sorted.size() - 1);
        String[] a = first.split("\\.");
        String[] b = last.split("\\.");
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i].equals(b[i])) i++;
        return String.join(".", java.util.Arrays.copyOf(a, i));
    }

    /** 一伦反馈 #56：「向上寻包 / 模块描述符 / 目录名宽松匹配」等降级兜底全部删除——
 *  用户明确要求「没扫到就是没扫到」「不要降级」。详见 #56 注释。 */

static List<String> resolvePackageNames(@NotNull Project project, @Nullable VirtualFile[] files) {
        if (files == null || files.length == 0) return List.of();
        try {
            com.intellij.openapi.util.Computable<List<String>> compute = () -> {
                List<String> result = new java.util.ArrayList<>();
                PsiManager psiManager = PsiManager.getInstance(project);
                for (VirtualFile vf : files) {
                    if (vf == null || !vf.isDirectory()) continue;
                    PsiDirectory dir = psiManager.findDirectory(vf);
                    if (dir == null) continue;
                    try {
                        PsiPackage pkg = JavaDirectoryService.getInstance().getPackage(dir);
                        if (pkg != null) addQualifiedName(result, pkg);
                    } catch (Exception ignored) {
                        // 非源码根目录等异常情况：忽略，不阻断其它目录
                    }
                }
                return result;
            };
            return ApplicationManager.getApplication().runReadAction(compute);
        } catch (Exception ex) {
            LOG.warn("解析右键包名失败: " + ex.getMessage());
            return List.of();
        }
    }

    private static void addQualifiedName(@NotNull List<String> result, @NotNull PsiPackage pkg) {
        String name = pkg.getQualifiedName();
        if (name != null && !name.isEmpty() && !result.contains(name)) {
            result.add(name);
        }
    }
}

}
