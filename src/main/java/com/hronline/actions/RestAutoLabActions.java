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
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromCachedApis(scanner.getCachedApis(), dirs);
                LOG.info("仅显示此包接口：已扫描接口推导包名 = " + packageNames);
            }
            // 4) 回退：磁盘遍历（右键模块根且已扫描缓存里没有该模块接口时，前三级全部失败，
            //    沙箱日志 8/23 15:44:31 实锤）。递归搜集目录下源文件，推导各文件包名并
            //    取最长公共包前缀（模块根 → 模块根包），不再静默失败。
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromDisk(dirs);
                LOG.info("仅显示此包接口：磁盘遍历推导包名 = " + packageNames);
            }
            // 5) 兜底：向上寻包（右键纯资源目录/空目录/非标准布局时，磁盘遍历找不到任何源文件。
            //    沿父目录链逐级向上，每层先做廉价单层探测再决定要不要做完整磁盘遍历，
            //    避免每层都跑 2 万次 IO 卡死 EDT）。找到第一个含 .java/.kt 的祖先后
            //    只在该层做一次完整扫描——通常已能定位到模块根或父包。
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromAncestors(dirs);
                LOG.info("仅显示此包接口：向上寻包推导包名 = " + packageNames);
            }
            // 6) 兜底：模块描述符（向上找 pom.xml / build.gradle[.kts] / settings.gradle[.kts]，
            //    提取 groupId 或目录 basename 作为松散包前缀。子模块是纯配置/Scala 等无 Java
            //    源码的场景仍能定位到模块根）。
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromModuleDescriptor(dirs);
                LOG.info("仅显示此包接口：模块描述符推导包名 = " + packageNames);
            }
            // 7) 兜底：目录名宽松匹配（向上找最近一个有意义的目录名作为 filter hint，
            //    按包前缀匹配规则仍能收窄到同名包下的接口）。
            if (packageNames.isEmpty()) {
                packageNames = resolvePackagesFromDirName(dirs);
                LOG.info("仅显示此包接口：目录名兜底推导包名 = " + packageNames);
            }
            // 8) 单文件右键快路径（#53 新增）：上面 7 级目录兜底链全部失败、但用户实际选的是
            //    1 个或多个 .java/.kt 文件时，再走一遍文件粒度的包名推导——这种情形常见于
            //    「右键单个 controller 文件调试」，目录链上不一定能拿到该文件所属包。
            //    文件粒度命中后写 filter 即可生效；不命中时直接走 7 级失败提示。
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
            // 7 级全部失败时不再弹模态阻塞对话框，改用通知气泡轻提示，
            // 并把右键目录路径写入诊断日志便于排查——之前强弹窗被反馈「始终处理不好」。
            LOG.warn("仅显示此包接口：7 级兜底仍无法解析，右键目录 = " + dirs + "，右键文件 = " + javaFiles);
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
                                "所选目录/文件及其祖先链中均未发现 Java/Kotlin 源文件，过滤未生效。\n" +
                                        selectionDesc +
                                        "\n请尝试在含 .java/.kt 文件的目录或源文件本身上右键。",
                                NotificationType.WARNING)
                        .notify(project);
            } catch (Exception ex) {
                Messages.showWarningDialog(project,
                        "所选目录及其祖先链中均未发现 Java/Kotlin 源文件。",
                        "仅显示此包接口");
            }
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
        scanner.scanProjectApisAsync();

        String title = "仅显示此包接口";
        String content;
        if (!javaFiles.isEmpty()) {
            content = "已收窄为 " + javaFiles.size() + " 个源文件内的接口，正在重新扫描...\n" +
                    "（单文件/多文件右键——按文件精确匹配，非文件内接口会被过滤）\n" +
                    "点击左侧「全量」按钮可恢复全量列表";
        } else {
            content = "已收窄为包 " + String.join(", ", packageNames)
                    + " 及其子包下的接口，正在重新扫描...\n"
                    + "（支持右键任意层级包，无需精确到 controller 层）\n"
                    + "点击左侧「全量」按钮可恢复全量列表";
        }
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(RestAutoLabConstants.NOTIFICATION_GROUP)
                    .createNotification(title, content, NotificationType.INFORMATION)
                    .notify(project);
        } catch (Exception ex) {
            Messages.showInfoMessage(project, content, title);
        }
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
     * 单文件右键包名推导（#53 新增）：从 1 个或多个 .java/.kt 文件推包名。
     * <p>每个文件先按路径标记（src/main/java 等）截；截不到再读 package 声明；
     * 最终对所有非空包名取<b>最长公共包前缀</b>——多文件跨包时仍能收紧到合适的祖先包。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromFiles(@NotNull List<VirtualFile> files) {
        Set<String> packages = new java.util.HashSet<>();
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
        return aggregateToCommonPrefix(packages);
    }

    /**
     * 包名集合收尾：从非空集合取最长公共前缀，单元素原样返回，空集合返回空列表。
     * 与 {@link #longestCommonPackagePrefix} 配合使用，封装"空集合→空列表"的语义。
     */
    @NotNull
    static List<String> aggregateToCommonPrefix(@NotNull Set<String> packages) {
        if (packages.isEmpty()) return List.of();
        String prefix = longestCommonPackagePrefix(packages);
        return prefix.isEmpty() ? List.of() : List.of(prefix);
    }

    /**
     * 文件粒度包名推导（可测试纯字符串版，#53 新增）：与 {@link #resolvePackagesFromFiles}
     * 行为一致，但接受路径字符串+可选内容回调，便于单测在无 VFS 的沙箱里直接验证。
     * <p>每个文件按路径标记截包；截不到时回调 reader 读取首部 package 声明。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromPathStrings(@NotNull List<String> paths,
                                                       @NotNull java.util.function.Function<String, String> reader) {
        Set<String> packages = new java.util.HashSet<>();
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
        return aggregateToCommonPrefix(packages);
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

    /** 磁盘遍历兜底的采样上限：最多递归搜集多少个源文件（防止超大模块遍历失控） */
    private static final int DISK_SCAN_FILE_LIMIT = 20000;

    /** 磁盘遍历兜底中「读 package 声明」采样的上限（路径标记能推导的无需读文件，故采样数很小） */
    private static final int DISK_READ_PACKAGE_LIMIT = 50;

    /** 磁盘遍历兜底时跳过的高噪音目录（构建产物/依赖/版本库，不含业务源码） */
    private static final Set<String> DISK_SCAN_SKIP_DIRS = Set.of(
            "build", "target", "out", "bin", ".git", ".gradle", ".idea", "node_modules");

    /** package 声明识别：仅匹配行首声明，避免被注释/字符串里的 "package" 误导 */
    private static final java.util.regex.Pattern PACKAGE_DECL =
            java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?");

    /**
     * 回退包名推导（终极兜底）：右键模块根/聚合目录且已扫描缓存里没有该模块接口时，
     * PSI/路径标记/缓存推导全部失败。此时用 {@code java.nio} <b>直接遍历真实文件系统</b>
     * （沙箱日志 8/23 17:47:17 实锤：旧实现走 IntelliJ VFS {@code getChildren()}，项目刚打开、
     * VFS 子树尚未刷新时返回空，2ms 误判「无源文件」弹「无法解析」；NIO 遍历免疫 VFS 状态）。
     *
     * <p>包名推导规则：</p>
     * <ol>
     *   <li>路径含 {@code src/main/java} 等标准标记 → 按标记截取（零 IO，绝大多数 Maven/Gradle 模块命中）；</li>
     *   <li>无标记 → 读文件首部 {@code package} 声明（采样上限内）。</li>
     * </ol>
     *
     * <p><b>按模块根分组取前缀</b>（而非全量取公共前缀）：聚合模块（如右键
     * {@code nft-turbo-business}，下挂 collection/box/order 等多个子模块）若整体取公共前缀
     * 会得到过粗的 {@code cn.hollis.nft.turbo}，把 auth/admin/gateway 等其它模块接口也误纳入。
     * 现按「源文件所属模块根」（最近的 src/main/java 标记之上的目录）分组，每组各取最长公共
     * 包前缀——单模块目录仍得到唯一前缀，聚合目录得到每个子模块一条精确前缀。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromDisk(@NotNull List<VirtualFile> dirs) {
        List<String> dirPaths = new java.util.ArrayList<>();
        for (VirtualFile dir : dirs) {
            if (dir != null && dir.isDirectory()) dirPaths.add(dir.getPath());
        }
        return resolvePackagesFromDiskPaths(dirPaths);
    }

    /**
     * 磁盘遍历推导的纯 NIO 版本（不依赖 VFS，单测可直接用 {@code @TempDir} 验证）。
     * 搜集各目录下源文件 → 按模块根分组 → 每组取最长公共包前缀。
     */
    @NotNull
    static List<String> resolvePackagesFromDiskPaths(@NotNull List<String> dirPaths) {
        java.util.Map<String, Set<String>> byModuleRoot = new java.util.LinkedHashMap<>();
        int[] fileCount = {0};
        int[] readCount = {0};
        for (String dirPath : dirPaths) {
            if (dirPath == null || dirPath.isEmpty()) continue;
            collectSourcePackagesFromDiskPath(dirPath, byModuleRoot, fileCount, readCount);
            if (fileCount[0] >= DISK_SCAN_FILE_LIMIT) break;
        }
        List<String> result = new java.util.ArrayList<>();
        for (Set<String> packages : byModuleRoot.values()) {
            if (packages.isEmpty()) continue;
            String prefix = longestCommonPackagePrefix(packages);
            if (!prefix.isEmpty() && !result.contains(prefix)) result.add(prefix);
        }
        return result;
    }

    /** NIO 深度遍历的目录层数上限（聚合模块嵌套 3~4 层 + src/main/java + 包深已足够） */
    private static final int DISK_WALK_MAX_DEPTH = 40;

    /** NIO 遍历单个目录，按模块根分组搜集源文件包名（受文件数与采样上限约束） */
    private static void collectSourcePackagesFromDiskPath(@NotNull String dirPath,
                                                          @NotNull java.util.Map<String, Set<String>> byModuleRoot,
                                                          int[] fileCount, int[] readCount) {
        java.nio.file.Path root;
        try {
            root = java.nio.file.Paths.get(dirPath);
        } catch (Exception e) {
            return;
        }
        if (!java.nio.file.Files.isDirectory(root)) return;
        try {
            java.nio.file.Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    DISK_WALK_MAX_DEPTH, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                                                                               java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (fileCount[0] >= DISK_SCAN_FILE_LIMIT) return java.nio.file.FileVisitResult.TERMINATE;
                            if (dir.equals(root)) return java.nio.file.FileVisitResult.CONTINUE;
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (DISK_SCAN_SKIP_DIRS.contains(name) || name.startsWith(".")) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                                                       java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (fileCount[0] >= DISK_SCAN_FILE_LIMIT) return java.nio.file.FileVisitResult.TERMINATE;
                            String name = file.getFileName() == null ? "" : file.getFileName().toString();
                            String lower = name.toLowerCase(java.util.Locale.ROOT);
                            if (!lower.endsWith(".java") && !lower.endsWith(".kt")) {
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            fileCount[0]++;
                            String normalized = file.toString().replace(java.io.File.separatorChar, '/');
                            String pkg = packageFromSourcePath(normalized);
                            if (pkg == null && readCount[0] < DISK_READ_PACKAGE_LIMIT) {
                                readCount[0]++;
                                pkg = readPackageDeclarationFromDisk(file);
                            }
                            if (pkg != null && !pkg.isEmpty()) {
                                Set<String> group = byModuleRoot.computeIfAbsent(
                                        moduleRootOf(normalized), k -> new java.util.HashSet<>());
                                group.add(pkg);
                                // 同时登记上一级包：单文件/单子包场景下 LCP 才能回退到
                                // 模块根包（否则单元素集合的 LCP 就是文件自身的深层包）
                                int lastDot = pkg.lastIndexOf('.');
                                if (lastDot > 0) group.add(pkg.substring(0, lastDot));
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, java.io.IOException exc) {
                            return java.nio.file.FileVisitResult.CONTINUE; // 单文件失败不阻断整体推导
                        }
                    });
        } catch (java.io.IOException ignored) {
            // 单目录遍历失败不阻断其它目录推导
        }
    }

    /**
     * 源文件所属「模块根」：路径中最近的 src/main/java 等标记<b>之上</b>的目录；
     * 无标记时取文件父目录。用于聚合目录按子模块分组取前缀。
     */
    @NotNull
    static String moduleRootOf(@NotNull String normalizedPath) {
        String[] markers = {"/src/main/java/", "/src/test/java/", "/src/main/kotlin/", "/src/test/kotlin/"};
        for (String marker : markers) {
            int idx = normalizedPath.indexOf(marker);
            if (idx >= 0) return normalizedPath.substring(0, idx);
        }
        int lastSlash = normalizedPath.lastIndexOf('/');
        return lastSlash > 0 ? normalizedPath.substring(0, lastSlash) : normalizedPath;
    }

    /** 读磁盘源文件首部 package 声明（最多扫 200 行）；读失败或无声明返回 null */
    @Nullable
    private static String readPackageDeclarationFromDisk(@NotNull java.nio.file.Path file) {
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file)) {
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

    /** 5 级兜底：向上寻包时最多向上走的层数（防多模块嵌套 + 卡 EDT） */
    private static final int ANCESTOR_WALK_MAX_DEPTH = 6;

    /** 5 级兜底中「单层廉价探测」时最多检查的子项数（避免超宽目录树慢查） */
    private static final int SHALLOW_HINT_MAX_CHECKS = 50;

    /**
     * 5 级兜底（向上寻包）：右键纯资源目录/空目录/非标准布局时，磁盘遍历在 dir 内部找不到
     * 任何源文件。沿父目录链向上找第一个"看起来有源文件"的祖先，再在该祖先做<b>一次</b>
     * 完整磁盘扫描——避免之前每层祖先都跑 2 万次 IO 卡死 EDT。
     * <p>"看起来有源文件"的判定用 {@link #hasShallowSourceHint} 单层廉价探测
     * （限深 2 层、最多检查 50 个子项），O(6 × 50) = O(300) 次廉价操作 + 1 次深扫，EDT 上也快。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromAncestors(@NotNull List<VirtualFile> dirs) {
        for (VirtualFile dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            VirtualFile cur = dir.getParent();
            int depth = 0;
            while (cur != null && depth++ < ANCESTOR_WALK_MAX_DEPTH) {
                if (cur.isDirectory() && hasShallowSourceHint(cur)) {
                    List<String> found = resolvePackagesFromDisk(java.util.Collections.singletonList(cur));
                    if (!found.isEmpty()) return found;
                }
                cur = cur.getParent();
            }
        }
        return List.of();
    }

    /**
     * 单层廉价探测：当前目录及其直接子目录（限深 1 层、最多 50 个子项）中是否有
     * {@code .java}/{@code .kt} 文件。命中即返回 true，给 {@link #resolvePackagesFromAncestors}
     * 作为「值得深扫」的信号——避免每层都跑完整磁盘遍历把 EDT 卡死。
     */
    private static boolean hasShallowSourceHint(@NotNull VirtualFile dir) {
        int[] checks = {0};
        return scanShallowForSource(dir, 0, 1, checks);
    }

    private static boolean scanShallowForSource(@NotNull VirtualFile dir, int depth, int maxDepth, int[] checks) {
        if (depth > maxDepth) return false;
        if (checks[0] >= SHALLOW_HINT_MAX_CHECKS) return false;
        VirtualFile[] children = dir.getChildren();
        if (children == null) return false;
        for (VirtualFile child : children) {
            if (++checks[0] > SHALLOW_HINT_MAX_CHECKS) return false;
            if (child.isDirectory()) {
                if (DISK_SCAN_SKIP_DIRS.contains(child.getName())) continue;
                if (scanShallowForSource(child, depth + 1, maxDepth, checks)) return true;
            } else {
                String ext = child.getExtension();
                if ("java".equalsIgnoreCase(ext) || "kt".equalsIgnoreCase(ext)) return true;
            }
        }
        return false;
    }

    /** Maven groupId / Gradle project name 的常见分隔符 */
    private static final java.util.regex.Pattern MODULE_NAME_SPLIT =
            java.util.regex.Pattern.compile("[.\\-_/]+");

    /**
     * 6 级兜底（模块描述符）：向上找 {@code pom.xml} / {@code build.gradle} /
     * {@code build.gradle.kts} / {@code settings.gradle} / {@code settings.gradle.kts}，
     * 按以下优先级提取一个松散包前缀：
     * <ol>
     *   <li>pom.xml：正则匹配 {@code <groupId>...</groupId>}（取首个非空匹配）</li>
     *   <li>build.gradle / .kts：匹配 {@code rootProject.name = '...'} 或 {@code group = '...'}</li>
     *   <li>settings.gradle / .kts：匹配 {@code rootProject.name = '...'}</li>
     *   <li>未匹配到 → 用模块描述符所在目录的 basename 作为单段 filter（至少能让用户
     *       点开「仅显示此包接口」后有可见的过滤效果，而不是完全 no-op）</li>
     * </ol>
     * 适用于纯配置模块、Scala/纯前端子模块、或右键目录在标准布局之外但能定位到
     * 模块根的场景。
     */
    @NotNull
    static List<String> resolvePackagesFromModuleDescriptor(@NotNull List<VirtualFile> dirs) {
        for (VirtualFile dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            VirtualFile cur = dir;
            int depth = 0;
            while (cur != null && depth++ < ANCESTOR_WALK_MAX_DEPTH) {
                String[] candidates = {"pom.xml", "build.gradle", "build.gradle.kts",
                        "settings.gradle", "settings.gradle.kts"};
                for (String name : candidates) {
                    VirtualFile child = cur.findChild(name);
                    if (child == null) continue;
                    String pkg = extractPackageFromModuleFile(child);
                    if (pkg != null && !pkg.isEmpty()) return java.util.Collections.singletonList(pkg);
                }
                cur = cur.getParent();
            }
        }
        return List.of();
    }

    /**
     * 从模块描述符文件提取包/模块名（纯字符串逻辑，便于单测）。
     * pom.xml → groupId（XML 标签内容），无则退化 artifactId；
     * gradle → {@code group = 'x'} 或 {@code rootProject.name = 'x'}；
     * 无匹配返回 null。
     */
    @Nullable
    static String extractPackageFromModuleText(@NotNull String filename, @NotNull String content) {
        if ("pom.xml".equals(filename)) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<groupId>\\s*([\\w.\\-_]+)\\s*</groupId>").matcher(content);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern
                    .compile("<artifactId>\\s*([\\w.\\-_]+)\\s*</artifactId>").matcher(content);
            return m.find() ? m.group(1) : null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:rootProject\\.name|project\\.name|group)\\s*=?\\s*['\"]([^'\"]+)['\"]")
                .matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从模块描述符文件读取并提取包名（VFS 包装层）。
     */
    @Nullable
    private static String extractPackageFromModuleFile(@NotNull VirtualFile file) {
        try {
            String content = new String(file.contentsToByteArray(), file.getCharset());
            return extractPackageFromModuleText(file.getName(), content);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 7 级兜底（目录名宽松匹配）：用右键目录链中第一个"看起来像包名片段"的目录
     * basename 作为单段 filter hint。{@code matchesPackagePrefix} 会按段边界匹配，
     * 任何含同名段的包都会被收窄——例如右键 {@code docs} 会过滤含 {@code .docs.} 的接口
     * （多数为 false positive，但至少不会完全 no-op）。
     * <p>过滤规则：跳过 {@code .} / {@code ..} / {@code src} / 噪音目录 / 全数字目录；
     * 优先用最深的有意义目录名（最贴近用户意图）。</p>
     */
    @NotNull
    static List<String> resolvePackagesFromDirName(@NotNull List<VirtualFile> dirs) {
        for (VirtualFile dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            // 从 dir 开始向上找第一个有意义的目录名
            java.util.List<String> chain = new java.util.ArrayList<>();
            VirtualFile cur = dir;
            int depth = 0;
            while (cur != null && depth++ < ANCESTOR_WALK_MAX_DEPTH) {
                chain.add(cur.getName());
                cur = cur.getParent();
            }
            String hint = pickDirNameHint(chain);
            if (hint != null) return java.util.Collections.singletonList(hint);
        }
        return List.of();
    }

    /**
     * 从「目录链」（从最深到最浅）中挑第一个有意义的目录名作为 filter hint。
     * 噪音集合来自 IDE 构建产物 / 标准源码目录 / VFS 元目录。
     */
    @Nullable
    static String pickDirNameHint(@NotNull java.util.List<String> dirChain) {
        Set<String> noise = new java.util.HashSet<>(DISK_SCAN_SKIP_DIRS);
        noise.add("src"); noise.add("main"); noise.add("test"); noise.add("java"); noise.add("kotlin");
        noise.add("resources"); noise.add("public"); noise.add("private"); noise.add(".");
        noise.add("..");
        for (String name : dirChain) {
            if (name == null || noise.contains(name)) continue;
            if (!name.matches(".*[A-Za-z].*")) continue; // 全数字/全符号目录也跳过
            String norm = name.replaceAll("[^A-Za-z0-9_]", "");
            if (!norm.isEmpty()) return norm;
        }
        return null;
    }

    /**
     * 将右键选中的目录解析为包名列表（读动作内执行，EDT/后台线程均可调用）；
     * 解析不到（选的不是源码包目录）返回空列表。
     *
     * <p>只经 {@link JavaDirectoryService} 反查 {@code PsiDirectory} 所属包，
     * 标准 Project 视图右键包目录即此形态；非 Java 源码根目录等异常目录被跳过。</p>
     */
    @NotNull
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
