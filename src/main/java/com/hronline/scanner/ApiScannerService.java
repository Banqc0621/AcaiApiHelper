package com.hronline.scanner;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * API扫描服务 - 基于IntelliJ PSI解析Java源码中的REST API定义
 *
 * 支持的框架：
 * 1. Spring MVC（@Controller/@RestController + @RequestMapping/@GetMapping等）
 * 2. JAX-RS（@Path + @GET/@POST/@PUT/@DELETE/@PATCH）
 * 3. Spring Cloud OpenFeign（@FeignClient 接口）
 *
 * 核心功能：
 * 1. 扫描项目中所有REST控制器类/接口
 * 2. 解析HTTP映射注解，提取URL路径和方法
 * 3. 提取方法参数信息（支持Spring和JAX-RS注解）
 * 4. 解析@ModelAttribute展开为查询参数
 * 5. 解析Javadoc和Swagger注解获取接口描述
 * 6. 递归解析复杂类型的字段（含泛型展开）
 * 7. 在后台线程执行扫描，支持进度条和取消
 * 8. 每个类/方法独立容错，不会因单个错误中断整体扫描
 */
@Service(Service.Level.PROJECT)
public final class ApiScannerService {

    private static final Logger LOG = Logger.getInstance(ApiScannerService.class);

    private final Project project;

    /** 缓存已扫描的API列表 */
    private volatile List<ApiDefinition> cachedApis = Collections.emptyList();

    /**
     * 最近一次「无包过滤」全量扫描的 API 列表（即 setScanPackageFilter("") 时的扫描结果）。
     * <p>与 {@link #cachedApis} 的区别：cachedApis 会被任意扫描（含右键"仅显示此包接口"触发的包过滤扫描）
     * 覆盖；lastFullScanApis 只在无过滤时更新。这样点击左侧「全量」按钮时可以从 lastFullScanApis
     * 即时恢复全量列表，不必等待后台重扫，避免扫描期间用户看到空白列表的"降级感"。</p>
     */
    private volatile List<ApiDefinition> lastFullScanApis = Collections.emptyList();

    /** 当前 UI 是否处于右键目录/文件的路径范围视图。 */
    private volatile boolean sourceScopeActive;

    /**
     * 令牌化扫描请求。用户可以连续点击「仅显示此包接口」和「全量」；旧扫描完成后
     * 不得覆盖新请求的结果，否则会出现列表闪回、显示上一次范围等“偶发不稳定”。
     */
    private final java.util.concurrent.atomic.AtomicLong scanGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 扫描监听器列表
     * <p>一伦反馈 #60：用 {@link CopyOnWriteArrayList} 而非普通 ArrayList——避免一次性监听器
     * (oneShot) 在 {@code onScanComplete} 回调里 {@code removeListener(this)} 触发的
     * {@link java.util.ConcurrentModificationException}，同时覆盖 addListener(EDT) 与
     * 遍历(scan 后台线程)跨线程修改的场景。</p>
     */
    private final List<ScanListener> listeners = new CopyOnWriteArrayList<>();

    public ApiScannerService(Project project) {
        this.project = project;
    }

    /**
     * 扫描监听器接口 - 扫描完成或更新时回调
     */
    public interface ScanListener {
        void onScanComplete(List<ApiDefinition> apis);
        void onScanStarted();
    }

    /** 注册扫描监听器 */
    public void addListener(ScanListener listener) {
        listeners.add(listener);
    }

    /** 移除扫描监听器 */
    public void removeListener(ScanListener listener) {
        listeners.remove(listener);
    }

    /** 获取缓存的API列表 */
    public List<ApiDefinition> getCachedApis() {
        return cachedApis;
    }

    /**
     * 获取最近一次「无包过滤」全量扫描的 API 列表。
     * <p>只在包过滤为空时的扫描才会更新此缓存——点击「全量」按钮可直接从此缓存恢复列表，
     * 不必等待后台重扫；若从未做过无过滤扫描，则返回空列表。</p>
     */
    public List<ApiDefinition> getLastFullScanApis() {
        return lastFullScanApis;
    }

    // ================================================================
    // 扫描入口
    // ================================================================

    /**
     * 异步扫描项目中的全部API（带进度条）
     * v3: 增加变更检测
     */
    public void scanProjectApisAsync() {
        scanProjectApisAsync(null);
    }

    /** 启动全量/设置包过滤扫描，并只回调本次请求自己的有效结果。 */
    public void scanProjectApisAsync(java.util.function.Consumer<List<ApiDefinition>> completion) {
        // 无参数扫描明确表示“全量/配置包过滤扫描”，清除上一次右键产生的路径范围。
        sourceScopeActive = false;
        scanProjectApisAsync(Collections.emptySet(), completion);
    }

    /**
     * 只扫描选中的目录/源文件。
     * <p>路径范围是右键动作的唯一真相：目录使用路径前缀匹配，Java 文件使用
     * 精确路径匹配。这样不会把“单文件”扩大成同包其它文件，也不依赖 PSI 包名解析或
     * 上一次缓存。</p>
     */
    public void scanSelectedSourcesAsync(Collection<String> sourcePaths) {
        scanSelectedSourcesAsync(sourcePaths, null);
    }

    /** 启动严格路径范围扫描，并只把本次请求自己的有效结果回调给调用方。 */
    public void scanSelectedSourcesAsync(Collection<String> sourcePaths,
                                         java.util.function.Consumer<List<ApiDefinition>> completion) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (sourcePaths != null) {
            for (String path : sourcePaths) {
                if (path == null || path.isBlank()) continue;
                String value = path.replace('\\', '/');
                while (value.length() > 1 && value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
                normalized.add(value);
            }
        }
        sourceScopeActive = true;
        scanProjectApisAsync(Collections.unmodifiableSet(normalized), completion);
    }

    /** 返回右键路径范围是否仍是当前视图语义的一部分。 */
    public boolean isSourceScopeActive() {
        return sourceScopeActive;
    }

    /** 在右键扫描启动前切换空态文案，避免即时 0 结果短暂显示成普通初始空态。 */
    public void activateSourceScope() {
        sourceScopeActive = true;
    }

    private void scanProjectApisAsync(Set<String> sourcePathFilter,
                                      java.util.function.Consumer<List<ApiDefinition>> completion) {
        // #65：保留旧 API 列表的快照，用于检测「同一方法 / 文件位置下路径变化」
        // 并把所有按 uniqueKey 索引的持久化字段（starredApis / folder.apiKeys /
        // folderApiParams / folderApiStatus / 各项 call stats 等）从旧 key 改写到新 key。
        final List<ApiDefinition> beforeApisSnapshot = new ArrayList<>(cachedApis);
        // 记录扫描前的状态
        List<String> beforeSignatures = beforeApisSnapshot.stream()
                .map(ApiDefinition::uniqueKey)
                .collect(Collectors.toList());

        // 快照配置，避免后台扫描过程中用户修改设置导致一次扫描前后使用两套范围。
        final List<String> packageFilter = parsePackageFilter(
                RestAutoLabSettingsState.getInstance(project).getScanPackageFilter());
        final boolean isFullScan = sourcePathFilter.isEmpty() && packageFilter.isEmpty();
        final long requestGeneration = scanGeneration.incrementAndGet();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "扫描项目API...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                // 一伦反馈 #58：移除所有 indicator.setText 调用——
                // setText 会同时更新底部状态栏文字（"正在扫描…" / "正在解析: ClassName"），
                // 用户明确要求「取消扫描时右下角的文字提示」；进度条/取消按钮仍由 Task 框架提供。
                for (ScanListener listener : listeners) {
                    listener.onScanStarted();
                }

                List<ApiDefinition> apis = scanProjectApis(indicator, sourcePathFilter, packageFilter);

                // 如果用户已经发起了更新的范围请求，本次结果只能丢弃，不能通知 UI 或污染缓存。
                if (requestGeneration != scanGeneration.get()) {
                    LOG.info("忽略过期的 API 扫描结果 generation=" + requestGeneration
                            + ", current=" + scanGeneration.get());
                    return;
                }

                // #65：检测「同一 Controller 方法、同一源码位置，但路径（uniqueKey）变了」，
                // 在 detectChanges 与 restoreApiMetadata 之前把所有按旧 key 索引的持久化字段
                // 改写到新 key。否则 starredApis / folder.apiKeys / folderApiParams 等都会
                // 把 path 变更当成「新增 + 删除」的双重夹击，starred API 静默丢失。
                Map<String, String> pathRemap = buildPathRemap(beforeApisSnapshot, apis);
                if (!pathRemap.isEmpty()) {
                    RestAutoLabSettingsState s = RestAutoLabSettingsState.getInstance(project);
                    int settingsChanged = s.remapApiKeys(pathRemap);
                    boolean folderChanged = StarredFolderService.getInstance(project).remapApiKeys(pathRemap);
                    LOG.info("#65 路径变更重映射: " + pathRemap.size() + " 个，"
                            + "settings 命中 " + settingsChanged + "，folder 改写=" + folderChanged
                            + "，示例 " + pathRemap.entrySet().stream().findFirst()
                            .map(e -> e.getKey() + " -> " + e.getValue()).orElse(""));
                }

                // v3: 变更检测
                detectChanges(beforeSignatures, apis);

                // v3: 恢复收藏和调用统计
                restoreApiMetadata(apis);

                List<ApiDefinition> immutableApis = Collections.unmodifiableList(new ArrayList<>(apis));
                cachedApis = immutableApis;
                if (isFullScan) {
                    // 无包过滤的扫描才算"全量"，把结果备份；带过滤的扫描不能污染全量缓存
                    lastFullScanApis = immutableApis;
                }

                // v3: 保存签名
                List<String> newSignatures = immutableApis.stream()
                        .map(ApiDefinition::uniqueKey)
                        .collect(Collectors.toList());
                RestAutoLabSettingsState.getInstance(project).saveLastScanSignatures(newSignatures);

                indicator.setFraction(1.0);

                for (ScanListener listener : listeners) {
                    listener.onScanComplete(immutableApis);
                }
                if (completion != null) completion.accept(immutableApis);
                LOG.info("API扫描完成，共发现 " + apis.size() + " 个接口");
            }
        });
    }

    /**
     * 同步扫描项目中的全部API（内部使用，需在后台线程调用）
     * 使用 allScope 确保多模块项目和依赖库中的类都能被索引到
     * 使用 ReadAction.nonBlocking().inSmartMode(project) 确保索引就绪（智能模式）后再访问 PSI，
     * 避免项目刚启动时 JavaPsiFacade.findClass 抛出 IndexNotReadyException
     */
    private List<ApiDefinition> scanProjectApis(ProgressIndicator indicator,
                                                Set<String> sourcePathFilter,
                                                List<String> packageFilter) {
        return ReadAction.nonBlocking(() -> {
            List<ApiDefinition> apis = new ArrayList<>();
            // allScope 包含项目源码 + 依赖库，确保注解类能被正确解析
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);

            // ── 1. 扫描 Spring MVC 控制器 ──
            List<PsiClass> allControllers = new ArrayList<>();
            for (String annotationFqn : RestAutoLabConstants.SPRING_CONTROLLER_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass annotationClass = psiFacade.findClass(annotationFqn, scope);
                if (annotationClass != null) {
                    Collection<PsiClass> classes = findAllInReadAction(
                            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope));
                    allControllers.addAll(classes);
                    LOG.info("Spring @" + annotationFqn.substring(annotationFqn.lastIndexOf('.') + 1)
                            + ": 发现 " + classes.size() + " 个类");
                } else {
                    LOG.info("Spring 注解类未找到: " + annotationFqn + "（项目可能未引入Spring）");
                }
            }

            // ── 2. 扫描 JAX-RS @Path 注解的类 ──
            for (String annotationFqn : RestAutoLabConstants.JAXRS_CONTROLLER_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass annotationClass = psiFacade.findClass(annotationFqn, scope);
                if (annotationClass != null) {
                    Collection<PsiClass> classes = findAllInReadAction(
                            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope));
                    allControllers.addAll(classes);
                    LOG.info("JAX-RS @Path: 发现 " + classes.size() + " 个类");
                }
            }

            // ── 3. 扫描 @FeignClient 接口 ──
            PsiClass feignAnnotation = psiFacade.findClass(RestAutoLabConstants.ANNO_FEIGN_CLIENT, scope);
            if (feignAnnotation != null) {
                Collection<PsiClass> classes = findAllInReadAction(
                        AnnotatedElementsSearch.searchPsiClasses(feignAnnotation, scope));
                allControllers.addAll(classes);
                LOG.info("@FeignClient: 发现 " + classes.size() + " 个接口");
            }

            // ── 3.5 补充：扫描带类级 @RequestMapping 的类 ──
            //    覆盖"组合注解控制器"场景：类用自定义组合注解（间接含 @RestController/@Controller），
            //    导致 AnnotatedElementsSearch 按 @RestController/@Controller 搜不到，
            //    但类级 @RequestMapping 通常仍在，可据此补充发现。
            //    候选类会经过 isFrameworkInternalController 过滤 + parseControllerClass 解析，
            //    无 HTTP 映射方法的类不会产出 API，故安全。
            PsiClass requestMappingAnno = psiFacade.findClass(RestAutoLabConstants.ANNO_REQUEST_MAPPING, scope);
            if (requestMappingAnno != null) {
                Collection<PsiClass> classes = findAllInReadAction(
                        AnnotatedElementsSearch.searchPsiClasses(requestMappingAnno, scope));
                allControllers.addAll(classes);
                LOG.info("补充扫描 @RequestMapping 类级注解: 候选 " + classes.size() + " 个类");
            }

            // ── 3.6 补充：扫描方法级 @*Mapping 注解，反向定位控制器类 ──
            //    这是最稳妥的兜底：只要某个方法标了 @GetMapping/@PostMapping/...，
            //    就把它的所属类纳入候选。可覆盖以下"类级注解搜不到"的场景：
            //    - 组合注解控制器（类用自定义注解，间接含 @RestController）
            //    - 类级无任何注解、仅在方法上标 @*Mapping 的轻量控制器
            //    - Kotlin/其他 JVM 语言写的控制器（PSI 方法注解仍可被索引）
            //    候选类会经过 isFrameworkInternalController 过滤 + parseControllerClass 解析，
            //    无 HTTP 映射方法的类不会产出 API，故安全。
            for (String mappingFqn : RestAutoLabConstants.SPRING_MAPPING_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass mappingAnno = psiFacade.findClass(mappingFqn, scope);
                if (mappingAnno == null) continue;
                Collection<PsiMethod> methods = findAllInReadAction(
                        AnnotatedElementsSearch.searchPsiMethods(mappingAnno, scope));
                for (PsiMethod m : methods) {
                    PsiClass owner = m.getContainingClass();
                    if (owner != null) allControllers.add(owner);
                }
                LOG.info("补充扫描方法级 @" + mappingFqn.substring(mappingFqn.lastIndexOf('.') + 1)
                        + ": 反向定位 " + methods.size() + " 个方法");
            }

            // 去重（某些类可能同时带多个注解）
            // 注意：getQualifiedName() 对 Kotlin 的 KtLightClass、局部类、动态生成类等可能返回 null，
            // 若直接用 qfn 判空跳过，会导致整批接口被丢弃（表现为"接口列表显示不全"）。
            // 因此 qfn 为 null 时改用「类名 + 源文件路径」作为兜底去重键，保证此类仍被解析。
            Set<String> seen = new HashSet<>();
            List<PsiClass> uniqueControllers = new ArrayList<>();
            int skippedNullQfn = 0;
            for (PsiClass cls : allControllers) {
                String qfn = cls.getQualifiedName();
                String dedupKey;
                if (qfn != null) {
                    dedupKey = qfn;
                } else {
                    // 兜底：类名 + 源文件路径（尽量唯一）
                    String name = cls.getName();
                    if (name == null) {
                        // 连类名都没有（匿名类/lambda），无法稳定去重，跳过避免重复
                        skippedNullQfn++;
                        continue;
                    }
                    String filePath = "";
                    PsiFile f = cls.getContainingFile();
                    if (f != null && f.getVirtualFile() != null) {
                        filePath = f.getVirtualFile().getPath();
                    }
                    dedupKey = "@nullQfn:" + name + "@" + filePath;
                }
                if (seen.add(dedupKey)) {
                    uniqueControllers.add(cls);
                }
            }
            if (skippedNullQfn > 0) {
                LOG.info("控制器去重: " + skippedNullQfn + " 个匿名/lambda 类被跳过（无类名无法稳定解析）");
            }

            // ── 3.7 右键范围过滤 ──
            //    先按真实源码路径收窄，再按设置中的包前缀收窄。路径过滤优先用于右键
            //    目录/单文件，避免“单文件右键→同包其它接口”以及包名解析漂移。
            if (!sourcePathFilter.isEmpty()) {
                int before = uniqueControllers.size();
                uniqueControllers = uniqueControllers.stream()
                        .filter(cls -> matchesSourcePath(cls, sourcePathFilter))
                        .collect(Collectors.toList());
                LOG.info("源码路径过滤 " + sourcePathFilter + ": 控制器 " + before
                        + " -> " + uniqueControllers.size());
            }
            if (!packageFilter.isEmpty()) {
                int before = uniqueControllers.size();
                uniqueControllers = uniqueControllers.stream()
                        .filter(cls -> matchesPackageFilter(cls, packageFilter))
                        .collect(Collectors.toList());
                LOG.info("包过滤 " + packageFilter + ": 控制器 " + before + " -> " + uniqueControllers.size());
            }

            // ── 4. 逐个解析控制器 ──
            int total = uniqueControllers.size();
            for (int i = 0; i < total; i++) {
                if (indicator != null) {
                    indicator.checkCanceled();
                    indicator.setFraction((double) i / total);
                }
                PsiClass psiClass = uniqueControllers.get(i);
                // 一伦反馈 #58：不调用 indicator.setText，底部状态栏不显示 "正在解析: X"
                try {
                    List<ApiDefinition> classApis = parseControllerClass(psiClass);
                    apis.addAll(classApis);
                    // 详细的逐控制器日志已在 parseControllerClass 内输出（含 qfn、映射方法数、被过滤方法）
                } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                    // 用户取消扫描时立即向上抛——之前的 catch Exception 会把 PCE 当作普通
                    // 解析失败吞掉，导致取消信号丢失、扫描继续跑空轮、UI 不响应取消
                    throw pce;
                } catch (Exception e) {
                    LOG.warn("解析控制器类失败: " + psiClass.getName() + " - " + e.getMessage(), e);
                }
            }

            List<ApiDefinition> uniqueApis = deduplicateApis(apis);
            LOG.info("扫描总结: 候选控制器 " + allControllers.size()
                    + " 个(含重复/多注解), 去重后 " + uniqueControllers.size()
                    + " 个, 原始接口 " + apis.size()
                    + " 个, 端点去重后 " + uniqueApis.size() + " 个");
            return uniqueApis;
        }).inSmartMode(project).executeSynchronously();
    }

    // ================================================================
    // 控制器类解析
    // ================================================================

    /**
     * 解析单个控制器类/接口中的所有API端点
     * 同时处理 Spring MVC、JAX-RS、FeignClient 三种风格
     */
    private List<ApiDefinition> parseControllerClass(PsiClass psiClass) {
        List<ApiDefinition> apis = new ArrayList<>();
        String controllerName = psiClass.getName();
        if (controllerName == null) return apis;

        // 过滤框架/库内置控制器：这些类不是用户业务接口，路径常含未解析占位符
        if (isFrameworkInternalController(psiClass)) {
            LOG.info("跳过框架内置控制器: " + psiClass.getQualifiedName());
            return apis;
        }

        // 提取类级别的基础路径列表（Spring @RequestMapping / JAX-RS @Path，支持多路径）
        List<String> basePaths = extractClassBasePaths(psiClass);

        // 判断是否为 JAX-RS 风格
        boolean isJaxrs = isJaxrsClass(psiClass);

        // 对用户而言，同一 Controller 下相同 HTTP 方法 + URL 就是同一个接口。
        // getAllMethods() 可能同时返回接口方法、父类方法和当前类实现；旧逻辑把 Java
        // 方法名/参数签名也放进键里，会把同一端点重复显示多次。
        Map<String, ApiDefinition> endpointByKey = new LinkedHashMap<>();
        Set<String> declaredHereKeys = new HashSet<>();

        // 诊断计数：带HTTP映射注解的方法数、被占位符过滤的方法名
        int mappedMethodCount = 0;
        List<String> filteredMethods = new ArrayList<>();

        // 遍历类自身声明的方法（含从接口/父类继承的方法）
        for (PsiMethod method : psiClass.getAllMethods()) {
            try {
                List<ApiDefinition> methodApis;
                if (isJaxrs) {
                    methodApis = parseJaxrsMethod(method, controllerName, basePaths, psiClass);
                } else {
                    methodApis = parseSpringMethod(method, controllerName, basePaths, psiClass);
                }
                if (!methodApis.isEmpty()) {
                    mappedMethodCount++;
                }
                for (ApiDefinition api : methodApis) {
                    // 路径含未解析占位符（${...}）：尽量清理保留，而不是整条丢弃
                    if (hasUnresolvedPlaceholder(api.getUrl())) {
                        String cleaned = cleanPlaceholderInPath(api.getUrl());
                        if (cleaned == null || cleaned.isBlank() || cleaned.equals("/")) {
                            LOG.info("跳过含 SpEL 表达式或空路径的接口: " + api.getUrl()
                                    + "（来自 " + controllerName + "." + method.getName() + "）");
                            filteredMethods.add(method.getName() + ":" + api.getHttpMethod() + " " + api.getUrl());
                            continue;
                        }
                        LOG.info("清理路径占位符: " + api.getUrl() + " -> " + cleaned
                                + "（来自 " + controllerName + "." + method.getName() + "）");
                        api.setUrl(cleaned);
                    }
                    String endpointKey = canonicalEndpointKey(api);
                    boolean declaredHere = psiClass.equals(method.getContainingClass());
                    if (!endpointByKey.containsKey(endpointKey)) {
                        endpointByKey.put(endpointKey, api);
                        if (declaredHere) declaredHereKeys.add(endpointKey);
                    } else if (declaredHere && !declaredHereKeys.contains(endpointKey)) {
                        // 同一路由同时来自父接口和当前实现时，保留当前 Controller 的声明，
                        // 这样跳转源码、参数和注释信息都指向用户实际实现。
                        endpointByKey.put(endpointKey, api);
                        declaredHereKeys.add(endpointKey);
                    }
                }
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                // 用户取消时立即向上抛——之前的 catch Exception 会把 PCE 当作普通
                // 解析失败吞掉，导致取消信号丢失、扫描继续跑空轮、UI 不响应取消
                throw pce;
            } catch (Exception e) {
                LOG.warn("解析方法失败: " + controllerName + "." + method.getName()
                        + " - " + e.getMessage());
            }
        }

        apis.addAll(endpointByKey.values());

        // 增强诊断：输出 qfn + 带映射注解的方法数 + 产出接口数 + 被过滤的方法
        String qfn = psiClass.getQualifiedName();
        if (apis.isEmpty()) {
            String hint = mappedMethodCount > 0
                    ? "（有 " + mappedMethodCount + " 个方法带映射注解但全被占位符过滤: " + filteredMethods + "）"
                    : "（共 " + psiClass.getAllMethods().length + " 个方法，无HTTP映射注解）";
            LOG.info("解析控制器 [" + controllerName + "] " + qfn + ": 0 个接口 " + hint);
        } else {
            LOG.info("解析控制器 [" + controllerName + "] " + qfn + ": " + apis.size()
                    + " 个接口（带映射注解的方法 " + mappedMethodCount + " 个）"
                    + (filteredMethods.isEmpty() ? "" : "，被过滤: " + filteredMethods));
        }

        return apis;
    }

    /**
     * 解析包过滤配置：按逗号/分号/空白拆分，去空白并去掉尾部点；
     * 空配置返回空列表（=不过滤）。
     * <p>包私有 + static：便于单元测试直接调用。</p>
     */
    static List<String> parsePackageFilter(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> prefixes = new ArrayList<>();
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim();
            // 去掉尾部点：用户写 "com.foo." 也归一化为 "com.foo"，
            // 否则后续 prefix + "." 会拼成 "com.foo.." 永远匹配不到
            while (p.endsWith(".")) {
                p = p.substring(0, p.length() - 1);
            }
            if (!p.isEmpty()) prefixes.add(p);
        }
        return prefixes;
    }

    /** 控制器类的全限定名是否命中任一包前缀（qfn 为 null 的类不过滤，保留） */
    private boolean matchesPackageFilter(PsiClass cls, List<String> prefixes) {
        String qfn = cls.getQualifiedName();
        if (qfn == null) return true;
        return matchesPackagePrefix(qfn, prefixes);
    }

    /**
     * 判断控制器源码是否落在右键选择的路径范围内。
     * <p>必须在 read action 内调用：{@code getContainingFile()} 和
     * {@code getVirtualFile()} 都是 PSI/VFS 读取。没有源文件路径的库类明确排除，
     * 不能因为 qfn 可用而混入右键结果。</p>
     */
    private boolean matchesSourcePath(PsiClass cls, Set<String> selectedPaths) {
        if (cls == null || selectedPaths == null || selectedPaths.isEmpty()) return true;
        PsiFile containingFile = cls.getContainingFile();
        VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();
        if (virtualFile == null) return false;
        return matchesSourcePath(virtualFile.getPath(), selectedPaths);
    }

    /**
     * 查询 PSI 索引时显式包裹 read action。
     * <p>部分 IntelliJ 版本的 QueryExecutor 会在 pooled thread 上继续处理结果；仅依赖
     * 外层 non-blocking read action 在这些版本上仍可能触发
     * {@code Read access is allowed from inside read-action only}。在结果物化点再包一层，
     * 让 Maven/Java 查询实现也始终拥有合法的 PSI 读上下文。</p>
     */
    private static <T> Collection<T> findAllInReadAction(Query<T> query) {
        com.intellij.openapi.util.Computable<Collection<T>> computation = query::findAll;
        return ApplicationManager.getApplication().runReadAction(computation);
    }

    /**
     * 包前缀匹配（纯字符串逻辑，便于单元测试）。
     * <p>匹配规则（含包段边界校验）：</p>
     * <ul>
     *   <li>完全相等：prefix == qfn（配置精确到类名的少见场景）</li>
     *   <li>包前缀：qfn 以 prefix + "." 开头，命中该包及其所有子包</li>
     * </ul>
     * <p>边界校验保证配置 <code>com.foo</code> 不会误命中
     * <code>com.foobar.Xxx</code>（不同包）——这是「只显示指定包下接口」的关键。</p>
     *
     * @param qfn      类全限定名，null 时保留（不过滤）
     * @param prefixes 包前缀列表
     */
    static boolean matchesPackagePrefix(String qfn, List<String> prefixes) {
        if (qfn == null) return true;
        for (String prefix : prefixes) {
            if (qfn.equals(prefix)) return true;
            if (qfn.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    /** 源码路径范围匹配（严格分隔符边界，供测试与扫描层共用）。 */
    static boolean matchesSourcePath(String sourcePath, Collection<String> selectedPaths) {
        if (sourcePath == null || sourcePath.isBlank()
                || selectedPaths == null || selectedPaths.isEmpty()) return false;
        String path = sourcePath.replace('\\', '/');
        for (String selected : selectedPaths) {
            if (selected == null || selected.isBlank()) continue;
            String scope = selected.replace('\\', '/');
            while (scope.length() > 1 && scope.endsWith("/")) {
                scope = scope.substring(0, scope.length() - 1);
            }
            if (path.equals(scope) || path.startsWith(scope + "/")) return true;
        }
        return false;
    }

    /**
     * 按左侧树可见语义去重：同一 Controller 下相同 HTTP 方法 + 规范化 URL 只保留一条。
     * <p>这是扫描出口的第二道防线，覆盖 PSI 偶发返回多个等价 light element、同名
     * Controller 被不同发现入口解析等情况。不同 Controller 的相同路由仍分别保留。</p>
     */
    public static List<ApiDefinition> deduplicateApis(Collection<ApiDefinition> apis) {
        if (apis == null || apis.isEmpty()) return Collections.emptyList();
        Map<String, ApiDefinition> unique = new LinkedHashMap<>();
        for (ApiDefinition api : apis) {
            if (api == null) continue;
            String controller = api.getControllerName() == null
                    ? ""
                    : api.getControllerName().trim();
            // 左侧树按 Controller simple name 分组，因此这里也按同一展示身份去重。
            // 不能加入 sourceFile：同一 Controller 的继承方法可能分别来自接口/父类文件，
            // 加入来源文件会把本应合并的 GET /wehealth 再次拆成多条。
            unique.putIfAbsent(controller + "\u0000" + canonicalEndpointKey(api), api);
        }
        return new ArrayList<>(unique.values());
    }

    /** 为扫描去重生成稳定端点键；防御性处理历史数据中的空格、重复斜杠和尾斜杠。 */
    private static String canonicalEndpointKey(ApiDefinition api) {
        String method = api.getHttpMethod() == null
                ? ""
                : api.getHttpMethod().trim().toUpperCase(java.util.Locale.ROOT);
        String path = api.getUrl() == null ? "" : api.getUrl().trim().replace('\\', '/');
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
        while (path.contains("//")) path = path.replace("//", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return method + "|" + path;
    }

    /**
     * #65：检测同一源码位置（文件 + 行号）下 uniqueKey 发生变化的 API，
     * 生成 oldKey → newKey 重映射。
     * <p>用于扫描完成后把所有按旧 key 存的持久化字段（starredApis、 folderApiParams 等）
     * 改写到新 key，否则这些字段会因路径变更被当成「新增 + 删除」双重夹击而失效。</p>
     */
    private static Map<String, String> buildPathRemap(List<ApiDefinition> beforeApis,
                                                      List<ApiDefinition> afterApis) {
        Map<String, String> remap = new HashMap<>();
        if (beforeApis == null || beforeApis.isEmpty() || afterApis == null || afterApis.isEmpty()) {
            return remap;
        }
        Map<String, ApiDefinition> oldByLocation = new HashMap<>();
        for (ApiDefinition api : beforeApis) {
            String loc = locationKey(api);
            if (loc != null) oldByLocation.put(loc, api);
        }
        for (ApiDefinition newApi : afterApis) {
            String loc = locationKey(newApi);
            if (loc == null) continue;
            ApiDefinition oldApi = oldByLocation.get(loc);
            if (oldApi == null) continue;
            String oldKey = oldApi.uniqueKey();
            String newKey = newApi.uniqueKey();
            if (oldKey != null && newKey != null && !oldKey.equals(newKey)) {
                remap.put(oldKey, newKey);
            }
        }
        return remap;
    }

    /** 同一 Controller 方法的位置指纹（文件路径 + 声明行号），用于跨跨次扫描识别同一接口。 */
    private static String locationKey(ApiDefinition api) {
        if (api == null) return null;
        String file = api.getSourceFilePath();
        int line = api.getSourceLineNumber();
        if (file == null || file.isEmpty() || line <= 0) return null;
        return file + "|" + line;
    }

    /** 判断类是否为 JAX-RS 风格（有 @Path 注解但无 Spring 控制器注解） */
    private boolean isJaxrsClass(PsiClass psiClass) {
        boolean hasPath = psiClass.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAVAX) != null
                || psiClass.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAKARTA) != null;
        boolean hasSpring = psiClass.getAnnotation(RestAutoLabConstants.ANNO_REST_CONTROLLER) != null
                || psiClass.getAnnotation(RestAutoLabConstants.ANNO_CONTROLLER) != null
                || psiClass.getAnnotation(RestAutoLabConstants.ANNO_FEIGN_CLIENT) != null;
        return hasPath && !hasSpring;
    }

    /**
     * 判断是否为框架/库内置控制器（非用户业务接口）。
     * <p>典型例子：Spring Boot 的 <code>BasicErrorController</code>，
     * 它的 <code>@RequestMapping("${server.error.path:${error.path:/error}}")</code> 含未解析占位符，
     * 不是用户业务接口，应当过滤掉。</p>
     * <p>判定依据：</p>
     * <ul>
     *   <li>类的全限定名命中黑名单（BasicErrorController、DefaultErrorController、actuator 端点）</li>
     *   <li>包名以 <code>org.springframework.</code> 开头（Spring 框架本身及 Spring Boot 自动配置）</li>
     * </ul>
     * <p><b>注意</b>：不再因「实现了 ErrorController 接口」就丢弃整个类。
     * 早期版本有此判定，会误伤用户自定义的 ErrorController 实现类——这类类里往往还含其他业务接口，
     * 整类丢弃会导致「接口列表显示不全」。框架内置的 BasicErrorController 已被上面的类名黑名单 +
     * Spring 包名过滤覆盖；其 errorPath 方法的 <code>${...}</code> 占位符则由
     * {@link #hasUnresolvedPlaceholder} / {@link #cleanPlaceholderInPath} 在方法解析层单独处理。</p>
     */
    private boolean isFrameworkInternalController(PsiClass psiClass) {
        String qfn = psiClass.getQualifiedName();
        if (qfn == null) return false;

        // 1. 类名黑名单（Spring Boot 自动配置中常见的内置控制器）
        if (qfn.endsWith(".BasicErrorController")
                || qfn.endsWith(".DefaultErrorController")
                || qfn.endsWith(".HealthEndpointController")
                || qfn.endsWith(".AbstractEndpointHandlerMapping")) {
            return true;
        }

        // 2. Spring 框架本身的类（用户业务控制器不会在 org.springframework.* 包下）
        if (qfn.startsWith("org.springframework.")
                && !qfn.startsWith("org.springframework.samples.")) {
            return true;
        }

        // 3. 修复提单「扫描出来不存在的api」：springdoc/swagger/actuator 等
        //    依赖库内置端点（SwaggerUiHome、OpenApiActuatorResource 等）不是用户业务接口，
        //    且源码在 jar 里，双击跳转必然失败，统一过滤。
        if (qfn.startsWith("org.springdoc.")
                || qfn.startsWith("springfox.")
                || qfn.startsWith("io.swagger.")
                || qfn.startsWith("org.springframework.boot.actuate.")) {
            return true;
        }

        return false;
    }

    /**
     * 判断 URL 是否含未解析的占位符（如 <code>${server.error.path}</code>）。
     * <p>这类 URL 是 Spring 配置占位符未被环境注入导致的，
     * 用户无法实际调试，应当在扫描时过滤掉。</p>
     */
    private boolean hasUnresolvedPlaceholder(String url) {
        if (url == null) return false;
        // Spring 占位符 ${...} 或 #{...}（SpEL）
        return url.contains("${") || url.contains("#{") || url.contains("$${");
    }

    /**
     * 清理 URL 中的 Spring 占位符 ${...}，尽量保留接口而不是丢弃：
     * <ul>
     *   <li>带默认值 <code>${key:default}</code> → 取 default（如 <code>${server.error.path:/error}</code> → <code>/error</code>）</li>
     *   <li>无默认值 <code>${key}</code> → 取变量名 key（如 <code>${api.prefix}</code> → <code>api.prefix</code>），保留接口可被识别</li>
     * </ul>
     * SpEL 表达式 <code>#{...}</code> 无法静态求值，返回 null 表示应丢弃该接口。
     * 清理后会对路径重新规范化（去重复斜杠、补前导斜杠）。
     */
    private String cleanPlaceholderInPath(String url) {
        if (url == null) return null;
        if (url.contains("#{")) return null;  // SpEL，无法静态解析
        if (!url.contains("${")) return url;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(url);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            String replacement;
            int colon = inner.indexOf(':');
            if (colon >= 0) {
                replacement = inner.substring(colon + 1).trim();
            } else {
                replacement = inner.trim();
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return normalizePath(sb.toString());
    }

    // ================================================================
    // Spring MVC 方法解析
    // ================================================================

    /**
     * 提取控制器类级别的基础路径列表（支持多路径）。
     * <p>Spring <code>@RequestMapping({"/api/v1","/api/v2"})</code> 会返回两个基路径，
     * 方法路径会与每个基路径做笛卡尔积，确保多版本前缀下的接口都能被识别。</p>
     * <p>组合语义（修复 #54）：当多个类级注解都贡献路径时（如 Feign 接口同时有
     * <code>@FeignClient(path="/client")</code> + <code>@RequestMapping("/users")</code>），
     * 不再二选一丢弃另一份，而是把所有路径来源两两组合——既保证已有扫描结果不变，
     * 又把真实端点 <code>/client/users/...</code> 补回来。</p>
     * <p>无类级路径注解时返回 <code>[""]</code>（单元素空串），保持与旧逻辑兼容。</p>
     */
    private List<String> extractClassBasePaths(PsiClass psiClass) {
        List<String> requestPaths = extractSpringRequestMappingPaths(psiClass);
        List<String> jaxrsPaths = extractJaxrsClassPaths(psiClass);
        List<String> feignPaths = extractFeignClientPaths(psiClass);

        // 收集所有有内容的路径来源；笛卡尔积之前先按"是否真有路径"分类
        List<List<String>> sources = new ArrayList<>();
        if (!requestPaths.isEmpty()) sources.add(requestPaths);
        if (!jaxrsPaths.isEmpty()) sources.add(jaxrsPaths);
        if (!feignPaths.isEmpty()) sources.add(feignPaths);

        if (sources.isEmpty()) return Collections.singletonList("");
        if (sources.size() == 1) return sources.get(0);

        // 多源路径：笛卡尔积组合——Feign 接口 @FeignClient(path) + @RequestMapping("/users")
        // → 把每个 Feign path 与每个 RequestMapping path 两两 join，得到完整前缀集合
        List<String> combined = new ArrayList<>();
        combined.add(""); // 先放空串，与第一源第一元素组合时不让 joinPaths 吞掉前导斜杠
        for (List<String> src : sources) {
            List<String> next = new ArrayList<>();
            for (String prefix : combined) {
                for (String path : src) {
                    String joined = joinPaths(prefix, path);
                    if (!joined.isEmpty() && !next.contains(joined)) next.add(joined);
                }
            }
            combined = next;
        }
        return combined.isEmpty() ? Collections.singletonList("") : combined;
    }

    /** 提取 Spring 类级 @RequestMapping 多路径；无/为空返回空列表（非 [""]） */
    private List<String> extractSpringRequestMappingPaths(@NotNull PsiClass psiClass) {
        PsiAnnotation requestMapping = psiClass.getAnnotation(RestAutoLabConstants.ANNO_REQUEST_MAPPING);
        if (requestMapping == null) return Collections.emptyList();
        List<String> paths = extractPathsFromAnnotation(requestMapping);
        return paths == null ? Collections.emptyList() : paths;
    }

    /** 提取 JAX-RS 类级 @Path（javax + jakarta 双 namespace）多路径；无/为空返回空列表 */
    private List<String> extractJaxrsClassPaths(@NotNull PsiClass psiClass) {
        PsiAnnotation jaxrsPath = psiClass.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAVAX);
        if (jaxrsPath == null) jaxrsPath = psiClass.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAKARTA);
        if (jaxrsPath == null) return Collections.emptyList();
        List<String> paths = extractJaxrsPaths(jaxrsPath);
        return paths == null ? Collections.emptyList() : paths;
    }

    /** 提取 @FeignClient(path) 或 @FeignClient(value) 单路径；无/为空返回空列表 */
    private List<String> extractFeignClientPaths(@NotNull PsiClass psiClass) {
        PsiAnnotation feignClient = psiClass.getAnnotation(RestAutoLabConstants.ANNO_FEIGN_CLIENT);
        if (feignClient == null) return Collections.emptyList();
        PsiAnnotationMemberValue pathVal = feignClient.findAttributeValue("path");
        if (pathVal != null) {
            String path = cleanAnnotationValue(pathVal.getText());
            if (!path.isEmpty()) return Collections.singletonList(path);
        }
        PsiAnnotationMemberValue valVal = feignClient.findAttributeValue("value");
        if (valVal != null) {
            String path = cleanAnnotationValue(valVal.getText());
            if (!path.isEmpty() && path.startsWith("/")) return Collections.singletonList(path);
        }
        return Collections.emptyList();
    }

    /**
     * 解析Spring风格的方法为API定义
     * 支持多路径注解（如 @GetMapping({"/list","/query"})），
     * 每个方法路径与每个类级基路径做笛卡尔积，各生成一个 ApiDefinition。
     */
    private List<ApiDefinition> parseSpringMethod(PsiMethod method, String controllerName,
                                                  List<String> basePaths, PsiClass declaringClass) {
        PsiAnnotation mappingAnnotation = findSpringMappingAnnotation(method);
        if (mappingAnnotation == null) return Collections.emptyList();

        String httpMethod = resolveSpringHttpMethod(mappingAnnotation);
        List<String> methodPaths = extractPathsFromAnnotation(mappingAnnotation);

        List<ApiDefinition> result = new ArrayList<>();
        if (methodPaths.isEmpty()) {
            // 无显式方法路径，仅用类级基路径
            for (String basePath : basePaths) {
                String fullPath = normalizePath(basePath);
                result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
            }
            return result;
        }
        for (String basePath : basePaths) {
            for (String methodPath : methodPaths) {
                String fullPath = normalizePath(joinPaths(basePath, methodPath));
                result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
            }
        }
        return result;
    }

    /**
     * 查找方法上的Spring HTTP映射注解
     */
    private PsiAnnotation findSpringMappingAnnotation(PsiMethod method) {
        String[] annotations = {
                RestAutoLabConstants.ANNO_GET_MAPPING,
                RestAutoLabConstants.ANNO_POST_MAPPING,
                RestAutoLabConstants.ANNO_PUT_MAPPING,
                RestAutoLabConstants.ANNO_DELETE_MAPPING,
                RestAutoLabConstants.ANNO_PATCH_MAPPING,
                RestAutoLabConstants.ANNO_REQUEST_MAPPING
        };
        for (String ann : annotations) {
            PsiAnnotation annotation = method.getAnnotation(ann);
            if (annotation != null) return annotation;
        }
        return null;
    }

    /**
     * 根据Spring注解类型确定HTTP方法
     */
    private String resolveSpringHttpMethod(PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null) return "GET";
        String annotationName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);

        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            case "RequestMapping" -> {
                PsiAnnotationMemberValue methodAttr = annotation.findAttributeValue("method");
                String methodText = methodAttr != null ? methodAttr.getText() : "GET";
                yield resolveFromMethodText(methodText);
            }
            default -> "GET";
        };
    }

    private String resolveFromMethodText(String methodText) {
        if (methodText.contains("POST")) return "POST";
        if (methodText.contains("PUT")) return "PUT";
        if (methodText.contains("DELETE")) return "DELETE";
        if (methodText.contains("PATCH")) return "PATCH";
        if (methodText.contains("HEAD")) return "HEAD";
        if (methodText.contains("OPTIONS")) return "OPTIONS";
        return "GET";
    }

    // ================================================================
    // JAX-RS 方法解析
    // ================================================================

    /**
     * 解析JAX-RS风格的方法为API定义
     * 支持多路径 @Path 注解，每个路径各生成一个 ApiDefinition。
     */
    private List<ApiDefinition> parseJaxrsMethod(PsiMethod method, String controllerName,
                                                 List<String> basePaths, PsiClass declaringClass) {
        // 查找 JAX-RS HTTP 方法注解
        String httpMethod = resolveJaxrsHttpMethod(method);
        if (httpMethod == null) return Collections.emptyList();

        // JAX-RS @Path（支持多路径）
        List<String> methodPaths = new ArrayList<>();
        PsiAnnotation pathAnno = method.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAVAX);
        if (pathAnno == null) pathAnno = method.getAnnotation(RestAutoLabConstants.JAXRS_PATH_JAKARTA);
        if (pathAnno != null) {
            methodPaths = extractJaxrsPaths(pathAnno);
        }

        List<ApiDefinition> result = new ArrayList<>();
        if (methodPaths.isEmpty()) {
            for (String basePath : basePaths) {
                String fullPath = normalizePath(basePath);
                result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
            }
            return result;
        }
        for (String basePath : basePaths) {
            for (String methodPath : methodPaths) {
                String fullPath = normalizePath(joinPaths(basePath, methodPath));
                result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
            }
        }
        return result;
    }

    /**
     * 从JAX-RS注解推断HTTP方法
     */
    private String resolveJaxrsHttpMethod(PsiMethod method) {
        // javax.ws.rs 注解
        for (String ann : RestAutoLabConstants.JAXRS_METHOD_ANNOTATIONS_JAVAX) {
            if (method.getAnnotation(ann) != null) {
                return ann.substring(ann.lastIndexOf('.') + 1).toUpperCase();
            }
        }
        // jakarta.ws.rs 注解
        for (String ann : RestAutoLabConstants.JAXRS_METHOD_ANNOTATIONS_JAKARTA) {
            if (method.getAnnotation(ann) != null) {
                return ann.substring(ann.lastIndexOf('.') + 1).toUpperCase();
            }
        }
        return null;
    }

    /**
     * 从 JAX-RS @Path 注解提取路径（单路径，保留兼容）
     */
    private String extractJaxrsPath(PsiAnnotation pathAnnotation) {
        return extractJaxrsPaths(pathAnnotation).stream().findFirst().orElse("");
    }

    /**
     * 从 JAX-RS @Path 注解提取全部路径（支持数组多路径）。
     */
    private List<String> extractJaxrsPaths(PsiAnnotation pathAnnotation) {
        PsiAnnotationMemberValue value = pathAnnotation.findAttributeValue("value");
        return splitAnnotationValues(value);
    }

    // ================================================================
    // 通用 API 定义构建
    // ================================================================

    /**
     * 构建 ApiDefinition（Spring 和 JAX-RS 通用）
     */
    private ApiDefinition buildApiDefinition(PsiMethod method, String controllerName,
                                              String httpMethod, String fullPath,
                                              PsiClass declaringClass) {
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(httpMethod);
        api.setUrl(fullPath);
        api.setName(extractApiName(method));
        api.setDescription(extractMethodDescription(method));
        api.setControllerName(controllerName);

        PsiFile containingFile = method.getContainingFile();
        if (containingFile != null && containingFile.getVirtualFile() != null) {
            api.setSourceFilePath(containingFile.getVirtualFile().getPath());
        }
        api.setSourceLineNumber(getLineNumber(method));
        api.setConsumes(extractConsumes(method, declaringClass));
        api.setProduces(extractProduces(method, declaringClass));
        api.setDeprecated(method.hasAnnotation(RestAutoLabConstants.ANNO_DEPRECATED));

        api.setParameters(new ArrayList<>(parseMethodParameters(method)));
        api.setResponseBodyType(extractReturnType(method));
        // v3: 解析返回类型的实体类字段树（用于在无测试记录时填充响应参数表/示例）
        api.setResponseSchema(extractResponseSchema(method));

        // 标记来源为自动扫描
        api.setSource(RestAutoLabConstants.API_SOURCE_AUTO);
        api.setScanTimestamp(System.currentTimeMillis());

        return api;
    }

    // ================================================================
    // 路径提取
    // ================================================================

    /**
     * 从Spring注解中提取URL路径（单路径，保留兼容）
     */
    private String extractPathFromAnnotation(PsiAnnotation annotation) {
        return extractPathsFromAnnotation(annotation).stream().findFirst().orElse("");
    }

    /**
     * 从Spring注解中提取全部URL路径（支持数组多路径）。
     * <p>依次检查 value 和 path 属性。注解值可能为：</p>
     * <ul>
     *   <li>单路径：<code>"/users"</code> → ["/users"]</li>
     *   <li>多路径：<code>{"/list","/query"}</code> → ["/list", "/query"]</li>
     *   <li>未指定：返回空列表</li>
     * </ul>
     */
    private List<String> extractPathsFromAnnotation(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        List<String> paths = splitAnnotationValues(value);
        if (!paths.isEmpty()) return paths;

        PsiAnnotationMemberValue pathAttr = annotation.findAttributeValue("path");
        return splitAnnotationValues(pathAttr);
    }

    /**
     * 清理注解属性值（去引号、去花括号、取第一个）
     */
    private String cleanAnnotationValue(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            if (cleaned.contains(",")) {
                cleaned = cleaned.substring(0, cleaned.indexOf(',')).trim();
            }
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    /**
     * 将注解属性值拆分为多个字符串值（支持单值和数组多值）。
     * <p>处理 PSI 注解属性值，支持：</p>
     * <ul>
     *   <li>字符串字面量：<code>"/users"</code> → ["/users"]</li>
     *   <li>多路径：<code>{"/list", "/query"}</code> → ["/list", "/query"]</li>
     *   <li><b>常量引用</b>：<code>@GetMapping(ApiConstants.PATH)</code> 或 <code>@GetMapping(PATH)</code>
     *       —— 解析 <code>static final String</code> 常量的实际值，避免接口因路径不可识别而丢失</li>
     *   <li><code>null</code> 或空 → []</li>
     * </ul>
     * <p>优先用 PSI 语义解析（支持常量引用）；解析不到时回退到文本解析，保证兼容。</p>
     */
    private List<String> splitAnnotationValues(PsiAnnotationMemberValue value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;

        // ── 优先：PSI 语义解析（支持常量引用） ──
        List<String> resolved = resolveMemberValuePaths(value);
        if (!resolved.isEmpty()) return resolved;

        // ── 回退：文本解析（兼容旧逻辑） ──
        String text = value.getText();
        if (text == null) return result;
        text = text.trim();
        // 去掉外层花括号（数组）
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty() || text.equals(",")) return result;
        // 按逗号拆分（路径字符串内不会含逗号）
        for (String part : text.split(",")) {
            String s = part.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    /**
     * 用 PSI 语义解析注解属性值为字符串路径列表。
     * 支持：字符串字面量、数组初始化器、static final String 常量引用。
     */
    private List<String> resolveMemberValuePaths(PsiAnnotationMemberValue value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;

        // 字符串字面量 "/users"
        if (value instanceof PsiLiteralExpression lit) {
            Object v = lit.getValue();
            if (v instanceof String s && !s.isEmpty()) {
                result.add(s);
            }
            return result;
        }

        // 数组初始化 {"/a", "/b"} 或 {Const.A, Const.B}
        if (value instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue init : arr.getInitializers()) {
                result.addAll(resolveMemberValuePaths(init));
            }
            return result;
        }

        // 常量引用 ApiConstants.PATH 或 PATH（解析 static final String 常量值）
        if (value instanceof PsiReferenceExpression ref) {
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiVariable var) {
                Object constVal = var.computeConstantValue();
                if (constVal instanceof String s && !s.isEmpty()) {
                    result.add(s);
                }
            }
            return result;
        }

        return result;
    }

    /**
     * 拼接类级基路径与方法级路径。
     * <p>修复提单「扫描接口URL不完整，缺少/」：类级 @RequestMapping("/sys/xxx") 与
     * 方法级 @PostMapping("page")（无前导斜杠）直接拼接会变成 /sys/xxxpage。
     * 这里在两段之间缺斜杠时补上 /，双斜杠时折叠，保证结果与 Spring 运行时一致。</p>
     */
    private String joinPaths(String base, String sub) {
        if (base == null || base.isEmpty()) return sub == null ? "" : sub;
        if (sub == null || sub.isEmpty()) return base;
        boolean baseSlash = base.endsWith("/");
        boolean subSlash = sub.startsWith("/");
        if (baseSlash && subSlash) return base + sub.substring(1);
        if (!baseSlash && !subSlash) return base + "/" + sub;
        return base + sub;
    }

    /** 规范化URL路径 */
    private String normalizePath(String path) {
        String normalized = path.replace("//", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // ================================================================
    // API 名称 & 描述提取
    // ================================================================

    /**
     * 提取API名称（优先 Swagger → OpenAPI → mapping name → 方法名）
     */
    private String extractApiName(PsiMethod method) {
        // Swagger 2.x @ApiOperation
        PsiAnnotation apiOperation = method.getAnnotation(RestAutoLabConstants.SWAGGER_API_OPERATION);
        if (apiOperation != null) {
            PsiAnnotationMemberValue value = apiOperation.findAttributeValue("value");
            if (value != null) {
                String text = value.getText();
                if (text != null && !text.isBlank()) return cleanAnnotationValue(text);
            }
        }

        // OpenAPI 3.x @Operation
        PsiAnnotation operation = method.getAnnotation(RestAutoLabConstants.OPENAPI_OPERATION);
        if (operation != null) {
            PsiAnnotationMemberValue summary = operation.findAttributeValue("summary");
            if (summary != null) {
                String text = summary.getText();
                if (text != null && !text.isBlank()) return cleanAnnotationValue(text);
            }
        }

        return method.getName();
    }

    /**
     * 提取方法描述信息（Javadoc → Swagger notes → OpenAPI description）
     */
    private String extractMethodDescription(PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment != null) {
            PsiElement[] descElements = docComment.getDescriptionElements();
            if (descElements.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (PsiElement e : descElements) {
                    sb.append(e.getText());
                }
                String desc = cleanJavaDocText(sb.toString());
                if (!desc.isBlank()) return desc;
            }
        }

        PsiAnnotation apiOperation = method.getAnnotation(RestAutoLabConstants.SWAGGER_API_OPERATION);
        if (apiOperation != null) {
            PsiAnnotationMemberValue notes = apiOperation.findAttributeValue("notes");
            if (notes != null) {
                String text = notes.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }

        // OpenAPI 3: @Operation(summary=..., description=...) — summary 已被 extractApiName 使用，这里取 description
        PsiAnnotation operation = method.getAnnotation(RestAutoLabConstants.OPENAPI_OPERATION);
        if (operation != null) {
            PsiAnnotationMemberValue descVal = operation.findAttributeValue("description");
            if (descVal != null) {
                String text = descVal.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }

        return "";
    }

    /**
     * 清洗 JavaDoc 描述文本：去掉每行前导的 * 号、合并多余空白，只取第一段非空行作为摘要。
     * 例如 "\n * 藏品列表\n *\n * 根据状态查询\n" → "藏品列表"
     */
    private String cleanJavaDocText(String raw) {
        if (raw == null) return "";
        String[] lines = raw.split("\n");
        for (String line : lines) {
            String s = line.replaceAll("^\\s*\\*\\s*", "").trim();
            if (!s.isBlank()) return s;
        }
        return "";
    }

    // ================================================================
    // 参数解析（Spring + JAX-RS）
    // ================================================================

    /**
     * 解析方法参数列表
     */
    private List<ApiParameter> parseMethodParameters(PsiMethod method) {
        List<ApiParameter> params = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            try {
                List<ApiParameter> parsed = parseSingleParameter(param);
                params.addAll(parsed);
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                // 用户取消时立即向上抛——之前 catch Exception 会把 PCE 吞掉
                throw pce;
            } catch (Exception e) {
                LOG.warn("解析参数失败: " + param.getName() + " - " + e.getMessage());
            }
        }
        return params;
    }

    /**
     * 解析单个方法参数（可能返回多个，如 @ModelAttribute 展开为多个查询参数）
     */
    private List<ApiParameter> parseSingleParameter(PsiParameter param) {
        String paramName = param.getName();
        String paramType = param.getType().getPresentableText();
        List<ApiParameter> result = new ArrayList<>();

        if (isFrameworkParameter(param)) return result;

        // ── Spring 注解 ──

        // @RequestParam
        PsiAnnotation requestParam = param.getAnnotation(RestAutoLabConstants.ANNO_REQUEST_PARAM);
        if (requestParam != null) {
            ApiParameter p = new ApiParameter();
            p.setName(extractParamName(requestParam, paramName));
            p.setType(paramType);
            p.setLocation(ParameterLocation.QUERY);
            p.setRequired(extractRequired(requestParam, true));
            p.setDefaultValue(extractDefault(requestParam));
            p.setDescription(extractParamDescription(param));
            result.add(p);
            return result;
        }

        // @PathVariable
        PsiAnnotation pathVariable = param.getAnnotation(RestAutoLabConstants.ANNO_PATH_VARIABLE);
        if (pathVariable != null) {
            ApiParameter p = new ApiParameter();
            p.setName(extractParamName(pathVariable, paramName));
            p.setType(paramType);
            p.setLocation(ParameterLocation.PATH);
            p.setRequired(true);
            p.setDescription(extractParamDescription(param));
            result.add(p);
            return result;
        }

        // @RequestBody
        PsiAnnotation requestBody = param.getAnnotation(RestAutoLabConstants.ANNO_REQUEST_BODY);
        if (requestBody != null) {
            ApiParameter p = new ApiParameter();
            p.setName(paramName);
            p.setType(paramType);
            p.setLocation(ParameterLocation.BODY);
            p.setRequired(extractRequired(requestBody, true));
            p.setDescription(extractParamDescription(param));
            p.setChildren(new ArrayList<>(parseComplexType(param.getType(), new HashSet<>())));
            result.add(p);
            return result;
        }

        // @RequestHeader
        PsiAnnotation requestHeader = param.getAnnotation(RestAutoLabConstants.ANNO_REQUEST_HEADER);
        if (requestHeader != null) {
            ApiParameter p = new ApiParameter();
            p.setName(extractParamName(requestHeader, paramName));
            p.setType(paramType);
            p.setLocation(ParameterLocation.HEADER);
            p.setRequired(extractRequired(requestHeader, true));
            p.setDefaultValue(extractDefault(requestHeader));
            p.setDescription(extractParamDescription(param));
            result.add(p);
            return result;
        }

        // @CookieValue
        PsiAnnotation cookieValue = param.getAnnotation(RestAutoLabConstants.ANNO_COOKIE_VALUE);
        if (cookieValue != null) {
            ApiParameter p = new ApiParameter();
            p.setName(extractParamName(cookieValue, paramName));
            p.setType(paramType);
            p.setLocation(ParameterLocation.COOKIE);
            p.setRequired(extractRequired(cookieValue, true));
            p.setDefaultValue(extractDefault(cookieValue));
            p.setDescription(extractParamDescription(param));
            result.add(p);
            return result;
        }

        // @RequestPart (multipart 表单字段 / 文件上传)
        PsiAnnotation requestPart = param.getAnnotation(RestAutoLabConstants.ANNO_REQUEST_PART);
        if (requestPart != null) {
            ApiParameter p = new ApiParameter();
            p.setName(extractParamName(requestPart, paramName));
            p.setType(paramType);
            p.setLocation(ParameterLocation.FORM);
            p.setRequired(extractRequired(requestPart, true));
            p.setDescription(extractParamDescription(param));
            p.setFile(isMultipartFile(param.getType()));
            result.add(p);
            return result;
        }

        // @ModelAttribute → 展开字段为查询参数
        PsiAnnotation modelAttribute = param.getAnnotation(RestAutoLabConstants.ANNO_MODEL_ATTRIBUTE);
        if (modelAttribute != null) {
            List<ApiParameter> fields = parseComplexType(param.getType(), new HashSet<>());
            for (ApiParameter field : fields) {
                field.setLocation(ParameterLocation.QUERY);
                result.add(field);
            }
            if (result.isEmpty()) {
                // 如果展开为空，作为单个查询参数
                ApiParameter p = new ApiParameter();
                p.setName(paramName);
                p.setType(paramType);
                p.setLocation(ParameterLocation.QUERY);
                p.setRequired(false);
                p.setDescription(extractParamDescription(param));
                result.add(p);
            }
            return result;
        }

        // ── JAX-RS 注解 ──

        // JAX-RS @QueryParam
        for (String annFqn : new String[]{
                RestAutoLabConstants.JAXRS_QUERY_PARAM_JAVAX, RestAutoLabConstants.JAXRS_QUERY_PARAM_JAKARTA}) {
            PsiAnnotation qp = param.getAnnotation(annFqn);
            if (qp != null) {
                ApiParameter p = new ApiParameter();
                p.setName(extractJaxrsParamName(qp, paramName));
                p.setType(paramType);
                p.setLocation(ParameterLocation.QUERY);
                p.setRequired(false);
                p.setDescription(extractParamDescription(param));
                result.add(p);
                return result;
            }
        }

        // JAX-RS @PathParam
        for (String annFqn : new String[]{
                RestAutoLabConstants.JAXRS_PATH_PARAM_JAVAX, RestAutoLabConstants.JAXRS_PATH_PARAM_JAKARTA}) {
            PsiAnnotation pp = param.getAnnotation(annFqn);
            if (pp != null) {
                ApiParameter p = new ApiParameter();
                p.setName(extractJaxrsParamName(pp, paramName));
                p.setType(paramType);
                p.setLocation(ParameterLocation.PATH);
                p.setRequired(true);
                p.setDescription(extractParamDescription(param));
                result.add(p);
                return result;
            }
        }

        // JAX-RS @HeaderParam
        for (String annFqn : new String[]{
                RestAutoLabConstants.JAXRS_HEADER_PARAM_JAVAX, RestAutoLabConstants.JAXRS_HEADER_PARAM_JAKARTA}) {
            PsiAnnotation hp = param.getAnnotation(annFqn);
            if (hp != null) {
                ApiParameter p = new ApiParameter();
                p.setName(extractJaxrsParamName(hp, paramName));
                p.setType(paramType);
                p.setLocation(ParameterLocation.HEADER);
                p.setRequired(false);
                p.setDescription(extractParamDescription(param));
                result.add(p);
                return result;
            }
        }

        // JAX-RS @FormParam
        for (String annFqn : new String[]{
                RestAutoLabConstants.JAXRS_FORM_PARAM_JAVAX, RestAutoLabConstants.JAXRS_FORM_PARAM_JAKARTA}) {
            PsiAnnotation fp = param.getAnnotation(annFqn);
            if (fp != null) {
                ApiParameter p = new ApiParameter();
                p.setName(extractJaxrsParamName(fp, paramName));
                p.setType(paramType);
                p.setLocation(ParameterLocation.FORM);
                p.setRequired(false);
                p.setDescription(extractParamDescription(param));
                result.add(p);
                return result;
            }
        }

        // JAX-RS @CookieParam
        for (String annFqn : new String[]{
                RestAutoLabConstants.JAXRS_COOKIE_PARAM_JAVAX, RestAutoLabConstants.JAXRS_COOKIE_PARAM_JAKARTA}) {
            PsiAnnotation cp = param.getAnnotation(annFqn);
            if (cp != null) {
                ApiParameter p = new ApiParameter();
                p.setName(extractJaxrsParamName(cp, paramName));
                p.setType(paramType);
                p.setLocation(ParameterLocation.COOKIE);
                p.setRequired(false);
                p.setDescription(extractParamDescription(param));
                result.add(p);
                return result;
            }
        }

        // ── 无注解的默认处理 ──

        // 无注解的 MultipartFile 类型 → 文件上传表单参数
        if (isMultipartFile(param.getType())) {
            ApiParameter p = new ApiParameter();
            p.setName(paramName);
            p.setType(paramType);
            p.setLocation(ParameterLocation.FORM);
            p.setRequired(true);
            p.setDescription(extractParamDescription(param));
            p.setFile(true);
            result.add(p);
            return result;
        }

        // 无注解的简单类型 → 查询参数
        if (isSimpleType(param.getType())) {
            ApiParameter p = new ApiParameter();
            p.setName(paramName);
            p.setType(paramType);
            p.setLocation(ParameterLocation.QUERY);
            p.setRequired(true);
            p.setDescription(extractParamDescription(param));
            result.add(p);
            return result;
        }

        // 无注解的复杂类型 → 请求体（展开字段）
        ApiParameter p = new ApiParameter();
        p.setName(paramName);
        p.setType(paramType);
        p.setLocation(ParameterLocation.BODY);
        p.setRequired(true);
        p.setDescription(extractParamDescription(param));
        p.setChildren(new ArrayList<>(parseComplexType(param.getType(), new HashSet<>())));
        result.add(p);
        return result;
    }

    /** 判断是否为框架内置参数（Spring MVC / Servlet 注入的类型） */
    private boolean isFrameworkParameter(PsiParameter param) {
        Set<String> frameworkTypes = Set.of(
                "HttpServletRequest", "HttpServletResponse", "HttpSession",
                "Model", "ModelMap", "ModelAndView", "BindingResult",
                "Errors", "RedirectAttributes", "SessionStatus",
                "Principal", "Authentication", "Pageable",
                "UriComponentsBuilder", "ServletResponse", "ServletRequest",
                "InputStream", "OutputStream", "Reader", "Writer",
                "Locale", "TimeZone", "ZoneId"
        );
        return frameworkTypes.contains(param.getType().getPresentableText());
    }

    /**
     * 判断参数类型是否为文件上传类型（org.springframework.web.multipart.MultipartFile）
     * 支持: MultipartFile、MultipartFile[]、List/Multipart<MultipartFile> 等形式
     */
    private boolean isMultipartFile(PsiType type) {
        if (type == null) return false;
        String presentable = type.getPresentableText();
        if (presentable != null && presentable.contains("MultipartFile")) return true;
        // 处理数组类型 MultipartFile[]
        PsiType componentType = type.getDeepComponentType();
        if (componentType != null && componentType != type) {
            String comp = componentType.getPresentableText();
            if (comp != null && comp.contains("MultipartFile")) return true;
        }
        return false;
    }

    // ================================================================
    // 注解属性提取
    // ================================================================

    /** 从Spring注解中提取参数名（value → name → fallback） */
    private String extractParamName(PsiAnnotation annotation, String fallback) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value != null) {
            String text = value.getText();
            if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
        }
        PsiAnnotationMemberValue nameValue = annotation.findAttributeValue("name");
        if (nameValue != null) {
            String text = nameValue.getText();
            if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
        }
        return fallback;
    }

    /** 从JAX-RS注解中提取参数名（value属性） */
    private String extractJaxrsParamName(PsiAnnotation annotation, String fallback) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value != null) {
            String text = value.getText();
            if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
        }
        return fallback;
    }

    /** 从注解中提取 required 属性值 */
    private boolean extractRequired(PsiAnnotation annotation, boolean defaultValue) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("required");
        if (value == null) return defaultValue;
        String text = value.getText();
        if (text == null) return defaultValue;
        return switch (text.toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> defaultValue;
        };
    }

    /** 从注解中提取 defaultValue 属性值 */
    private String extractDefault(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("defaultValue");
        if (value == null) return "";
        String cleaned = cleanAnnotationValue(value.getText());
        if (cleaned.equals("\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n") || cleaned.isBlank()) return "";
        return cleaned;
    }

    /** 从Javadoc @param 标签提取参数描述 */
    private String extractParamDescription(PsiParameter param) {
        if (!(param.getDeclarationScope() instanceof PsiMethod method)) return "";
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) return "";

        for (PsiDocTag tag : docComment.getTags()) {
            if ("param".equals(tag.getName())) {
                String tagText = tag.getText();
                if (tagText != null && tagText.contains(param.getName())) {
                    String afterName = tagText.substring(tagText.indexOf(param.getName()) + param.getName().length());
                    String trimmed = afterName.trim();
                    if (trimmed.startsWith("-")) trimmed = trimmed.substring(1).trim();
                    return trimmed;
                }
            }
        }

        PsiAnnotation apiParam = param.getAnnotation(RestAutoLabConstants.SWAGGER_API_PARAM);
        if (apiParam != null) {
            PsiAnnotationMemberValue value = apiParam.findAttributeValue("value");
            if (value != null) {
                String text = value.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }

        return "";
    }

    // ================================================================
    // 复杂类型解析（含泛型展开）
    // ================================================================

    /**
     * 解析复杂类型的字段为子参数列表
     * @param visited 已访问类型集合，防止循环引用
     */
    private List<ApiParameter> parseComplexType(PsiType psiType, Set<String> visited) {
        List<ApiParameter> params = new ArrayList<>();

        PsiType resolvedType = resolveToConcreteType(psiType);
        if (resolvedType == null) return params;

        if (!(resolvedType instanceof PsiClassType classType)) return params;

        PsiClass psiClass = classType.resolve();
        if (psiClass == null) return params;

        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null || !visited.add(qualifiedName)) return params;

        // 构建泛型参数映射（如 T → User）
        Map<String, PsiType> genericMap = buildGenericMap(classType, psiClass);

        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if ("serialVersionUID".equals(field.getName())) continue;

            // 解析泛型字段类型
            PsiType fieldType = resolveGenericType(field.getType(), genericMap);

            ApiParameter fieldParam = new ApiParameter();
            fieldParam.setName(field.getName());
            fieldParam.setType(fieldType.getPresentableText());
            fieldParam.setLocation(ParameterLocation.BODY);
            fieldParam.setRequired(isFieldRequired(field));
            fieldParam.setDescription(extractFieldDescription(field));
            fieldParam.setExample(extractFieldExample(field));

            // 递归解析嵌套的复杂类型
            if (!isSimpleType(fieldType) && fieldType instanceof PsiClassType) {
                PsiClass nestedClass = ((PsiClassType) fieldType).resolve();
                if (nestedClass != null && !visited.contains(nestedClass.getQualifiedName())) {
                    Set<String> nestedVisited = new HashSet<>(visited);
                    fieldParam.setChildren(new ArrayList<>(parseComplexType(fieldType, nestedVisited)));
                }
            }

            params.add(fieldParam);
        }

        return params;
    }

    /**
     * 将类型解析为具体类型（处理泛型擦除、通配符等）
     */
    private PsiType resolveToConcreteType(PsiType psiType) {
        if (psiType instanceof PsiClassType classType) {
            PsiType[] typeParams = classType.getParameters();
            // 如果是 Collection<T>，取最后一个泛型参数
            if (typeParams.length > 0 && isCollectionType(classType)) {
                return typeParams[typeParams.length - 1];
            }
        }
        return psiType;
    }

    /** 判断类型是否为集合/容器类型 */
    private boolean isCollectionType(PsiClassType classType) {
        String name = classType.getClassName();
        return "List".equals(name) || "ArrayList".equals(name)
                || "Set".equals(name) || "HashSet".equals(name)
                || "Collection".equals(name) || "Iterable".equals(name)
                || "Page".equals(name) || "Pageable".equals(name);
    }

    /** 构建泛型参数映射 */
    private Map<String, PsiType> buildGenericMap(PsiClassType classType, PsiClass psiClass) {
        Map<String, PsiType> map = new HashMap<>();
        PsiTypeParameter[] typeParameters = psiClass.getTypeParameters();
        PsiType[] actualArgs = classType.getParameters();
        for (int i = 0; i < Math.min(typeParameters.length, actualArgs.length); i++) {
            map.put(typeParameters[i].getName(), actualArgs[i]);
        }
        return map;
    }

    /** 解析泛型类型（如 T → User） */
    private PsiType resolveGenericType(PsiType type, Map<String, PsiType> genericMap) {
        if (type instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            if (resolved instanceof PsiTypeParameter) {
                PsiType actual = genericMap.get(resolved.getName());
                if (actual != null) return actual;
            }
        }
        return type;
    }

    /** 判断字段是否必填 */
    private boolean isFieldRequired(PsiField field) {
        for (String ann : RestAutoLabConstants.VALIDATION_REQUIRED_ANNOTATIONS) {
            if (field.hasAnnotation(ann)) return true;
        }
        return false;
    }

    /** 从字段注解或Javadoc提取描述 */
    private String extractFieldDescription(PsiField field) {
        PsiAnnotation apiModelProperty = field.getAnnotation(RestAutoLabConstants.SWAGGER_API_MODEL_PROPERTY);
        if (apiModelProperty != null) {
            PsiAnnotationMemberValue value = apiModelProperty.findAttributeValue("value");
            if (value != null) {
                String text = value.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }

        PsiDocComment docComment = field.getDocComment();
        if (docComment != null) {
            PsiElement[] descElements = docComment.getDescriptionElements();
            if (descElements.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (PsiElement e : docComment.getDescriptionElements()) sb.append(e.getText());
                String desc = sb.toString().trim();
                if (!desc.isBlank()) return desc;
            }
        }

        return "";
    }

    /** 从 @ApiModelProperty 提取示例值 */
    private String extractFieldExample(PsiField field) {
        PsiAnnotation apiModelProperty = field.getAnnotation(RestAutoLabConstants.SWAGGER_API_MODEL_PROPERTY);
        if (apiModelProperty != null) {
            PsiAnnotationMemberValue example = apiModelProperty.findAttributeValue("example");
            if (example != null) {
                String text = example.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }
        return "";
    }

    // ================================================================
    // Content-Type 提取（Spring + JAX-RS）
    // ================================================================

    /**
     * 从映射注解或类级别提取 consumes Content-Type
     * 优先级：方法级 → 类级 → 默认值
     */
    private String extractConsumes(PsiMethod method, PsiClass declaringClass) {
        // 先从方法注解取
        String result = extractConsumesFromAnnotations(method.getAnnotations());
        if (!result.equals(RestAutoLabConstants.DEFAULT_CONTENT_TYPE)) return result;

        // 再从类级别取
        result = extractConsumesFromAnnotations(declaringClass.getAnnotations());
        if (!result.equals(RestAutoLabConstants.DEFAULT_CONTENT_TYPE)) return result;

        return RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    }

    private String extractConsumesFromAnnotations(PsiAnnotation[] annotations) {
        for (PsiAnnotation ann : annotations) {
            String qfn = ann.getQualifiedName();
            if (qfn == null) continue;
            // Spring consumes
            if (qfn.endsWith("RequestMapping") || qfn.endsWith("PostMapping")
                    || qfn.endsWith("PutMapping") || qfn.endsWith("PatchMapping")) {
                PsiAnnotationMemberValue value = ann.findAttributeValue("consumes");
                if (value != null) {
                    String text = value.getText();
                    if (text != null && !text.isBlank() && !text.equals("{}")) {
                        return cleanAnnotationValue(text);
                    }
                }
            }
            // JAX-RS @Consumes
            if (qfn.endsWith(".Consumes")) {
                PsiAnnotationMemberValue value = ann.findAttributeValue("value");
                if (value != null) {
                    String text = value.getText();
                    if (text != null && !text.isBlank() && !text.equals("{}")) {
                        return cleanAnnotationValue(text);
                    }
                }
            }
        }
        return RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    }

    /**
     * 从映射注解或类级别提取 produces Content-Type
     */
    private String extractProduces(PsiMethod method, PsiClass declaringClass) {
        String result = extractProducesFromAnnotations(method.getAnnotations());
        if (!result.equals(RestAutoLabConstants.DEFAULT_CONTENT_TYPE)) return result;

        result = extractProducesFromAnnotations(declaringClass.getAnnotations());
        if (!result.equals(RestAutoLabConstants.DEFAULT_CONTENT_TYPE)) return result;

        return RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    }

    private String extractProducesFromAnnotations(PsiAnnotation[] annotations) {
        for (PsiAnnotation ann : annotations) {
            String qfn = ann.getQualifiedName();
            if (qfn == null) continue;
            if (qfn.endsWith("RequestMapping") || qfn.endsWith("GetMapping")
                    || qfn.endsWith("PostMapping") || qfn.endsWith("PutMapping")
                    || qfn.endsWith("DeleteMapping") || qfn.endsWith("PatchMapping")) {
                PsiAnnotationMemberValue value = ann.findAttributeValue("produces");
                if (value != null) {
                    String text = value.getText();
                    if (text != null && !text.isBlank() && !text.equals("{}")) {
                        return cleanAnnotationValue(text);
                    }
                }
            }
            if (qfn.endsWith(".Produces")) {
                PsiAnnotationMemberValue value = ann.findAttributeValue("value");
                if (value != null) {
                    String text = value.getText();
                    if (text != null && !text.isBlank() && !text.equals("{}")) {
                        return cleanAnnotationValue(text);
                    }
                }
            }
        }
        return RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /** 判断类型是否为简单类型 */
    private boolean isSimpleType(PsiType psiType) {
        Set<String> simpleTypes = Set.of(
                "String", "Integer", "Long", "Double", "Float", "Boolean",
                "Short", "Byte", "Character", "int", "long", "double",
                "float", "boolean", "short", "byte", "char",
                "BigDecimal", "BigInteger", "Date", "LocalDate",
                "LocalDateTime", "LocalTime", "UUID", "MultipartFile",
                "Instant", "OffsetDateTime", "ZonedDateTime"
        );
        return simpleTypes.contains(psiType.getPresentableText());
    }

    /** 提取方法的返回类型描述 */
    private String extractReturnType(PsiMethod method) {
        PsiType returnType = method.getReturnType();
        if (returnType == null) return "void";
        return returnType.getPresentableText();
    }

    /**
     * 解析方法的返回类型的实体字段树，用于生成响应参数表与示例 JSON。
     * <ul>
     *   <li>对 <code>Result&lt;UserVO&gt;</code> / <code>ResponseEntity&lt;UserVO&gt;</code> 这类带一层包装的，
     *       取第一个泛型参数 <code>UserVO</code> 作为根类型</li>
     *   <li>对 <code>Page&lt;UserVO&gt;</code> / <code>IPage&lt;UserVO&gt;</code>，根类型为 <code>UserVO</code>，并附带 records/total 等固定字段</li>
     *   <li>对 <code>UserVO</code> 这种裸领域对象，直接作为根类型</li>
     *   <li>void / 基础类型 / 解析失败 → 返回空列表</li>
     * </ul>
     */
    private List<ApiParameter> extractResponseSchema(PsiMethod method) {
        PsiType returnType = method.getReturnType();
        if (returnType == null) return new ArrayList<>();

        // 逐层剥壳：ResponseEntity<T> → List<T>/Page<T> → Result<T>/R<T> → 实体类
        PsiType t = returnType;
        t = orSelf(unwrapResponseEntity(t));
        PsiType afterCollection = unwrapCollection(t);
        if (afterCollection != null) t = afterCollection;
        PsiType afterWrapper = unwrapCommonResult(t);
        if (afterWrapper != null) t = afterWrapper;

        // 基础类型不解析
        if (isSimpleType(t)) return new ArrayList<>();

        // 递归解析实体类字段
        try {
            return new ArrayList<>(parseComplexType(t, new HashSet<>()));
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            // 用户取消时立即向上抛——之前的兜底只 catch Exception 会把 PCE 当作普通
            // 解析失败吞掉，导致取消信号丢失、扫描继续跑空轮、UI 不响应取消
            throw pce;
        } catch (Exception ex) {
            // 解析失败时记录日志并返回空列表（导出器会回退到按类型名推断）——
            // 之前完全静默吞掉导致「接口看起来正常但 responseSchema 为空」，用户
            // 无任何线索可排查；这里把异常路径暴露到日志里
            LOG.warn("解析响应类型失败（已回退为空 schema）: " + t.getCanonicalText()
                    + " - " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** unwrap 没剥成功时返回原值 */
    private PsiType orSelf(PsiType t) {
        return t != null ? t : null;
    }

    /** 去掉 ResponseEntity<T> 包装 */
    private PsiType unwrapResponseEntity(PsiType t) {
        if (t instanceof PsiClassType ct) {
            PsiClass c = ct.resolve();
            if (c != null) {
                String qn = c.getQualifiedName();
                if ("org.springframework.http.ResponseEntity".equals(qn)) {
                    PsiType[] args = ct.getParameters();
                    if (args.length == 1) return args[0];
                }
            }
        }
        return t;
    }

    /** 去掉 List<>/Page<> 集合包装，返回元素类型；非集合返回 null */
    private PsiType unwrapCollection(PsiType t) {
        if (t instanceof PsiClassType ct) {
            PsiClass c = ct.resolve();
            if (c != null) {
                String qn = c.getQualifiedName();
                if (qn == null) return null;
                if (qn.startsWith("java.util.List")
                        || qn.startsWith("java.util.Collection")
                        || qn.startsWith("java.util.Set")
                        || qn.startsWith("java.util.ArrayList")
                        || qn.endsWith("[]")) {
                    PsiType[] args = ct.getParameters();
                    if (args.length == 1) return args[0];
                }
                if (qn.equals("com.baomidou.mybatisplus.core.metadata.IPage")
                        || qn.endsWith(".Page")
                        || qn.endsWith("Page")) {
                    PsiType[] args = ct.getParameters();
                    if (args.length == 1) return args[0];
                }
            }
        }
        return null;
    }

    /** 去掉 Result<T> / R<T> / CommonResult<T> 等通用包装，返回 T；非包装返回 null */
    private PsiType unwrapCommonResult(PsiType t) {
        if (!(t instanceof PsiClassType ct)) return null;
        PsiClass c = ct.resolve();
        if (c == null) return null;
        String qn = c.getQualifiedName();
        if (qn == null) return null;
        // 常见包装类名
        String name = c.getName();
        if (name == null) return null;
        boolean isWrapper = name.equals("Result")
                || name.equals("R")
                || name.equals("CommonResult")
                || name.equals("ApiResult")
                || name.equals("BaseResult")
                || name.equals("ResponseResult")
                || name.equals("Response")
                || name.equals("Resp")
                || name.equals("RespResult");
        if (isWrapper) {
            PsiType[] args = ct.getParameters();
            if (args.length >= 1) return args[0];
        }
        return null;
    }

    /** 获取方法在文件中的行号 */
    private int getLineNumber(PsiMethod method) {
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        com.intellij.openapi.editor.Document document = docManager.getDocument(method.getContainingFile());
        if (document == null) return 0;
        return document.getLineNumber(method.getTextOffset()) + 1;
    }

    // ================================================================
    // 手动API / 查询API
    // ================================================================

    /**
     * 手动添加API定义
     */
    public ApiDefinition addManualApi(String httpMethod, String url, String name) {
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(httpMethod);
        api.setUrl(normalizePath(url));
        api.setName(name != null ? name : "");
        api.setControllerName("手动添加");
        api.setSource(RestAutoLabConstants.API_SOURCE_MANUAL);
        api.setScanTimestamp(System.currentTimeMillis());

        List<ApiDefinition> newApis = new ArrayList<>(cachedApis);
        newApis.add(api);
        cachedApis = newApis;

        for (ScanListener listener : listeners) {
            listener.onScanComplete(cachedApis);
        }
        return api;
    }

    /** 获取自动扫描的API列表 */
    public List<ApiDefinition> getAutoApis() {
        return cachedApis.stream()
                .filter(ApiDefinition::isAutoDetected)
                .collect(Collectors.toList());
    }

    /**
     * 导入接口定义到缓存，按 uniqueKey 去重：
     * 本地已存在该接口（相同 method|url）则保留本地不覆盖，否则新增导入接口。
     *
     * @param importApis 待导入的接口定义列表
     * @return int[2]：[新增数量, 跳过(已存在)数量]
     */
    public int[] importApis(List<ApiDefinition> importApis) {
        if (importApis == null || importApis.isEmpty()) return new int[]{0, 0};
        Set<String> localKeys = new HashSet<>();
        for (ApiDefinition a : cachedApis) localKeys.add(a.uniqueKey());

        List<ApiDefinition> newApis = new ArrayList<>(cachedApis);
        int added = 0;
        int skipped = 0;
        for (ApiDefinition imp : importApis) {
            if (imp == null || imp.getHttpMethod() == null || imp.getUrl() == null) continue;
            String key = imp.uniqueKey();
            if (localKeys.contains(key)) {
                skipped++;
            } else {
                // 标记为手动来源，避免被自动扫描去重逻辑误删
                imp.setSource(RestAutoLabConstants.API_SOURCE_MANUAL);
                newApis.add(imp);
                localKeys.add(key);
                added++;
            }
        }
        if (added > 0) {
            cachedApis = newApis;
            for (ScanListener listener : listeners) {
                listener.onScanComplete(cachedApis);
            }
        }
        return new int[]{added, skipped};
    }

    /** 获取手动添加的API列表 */
    public List<ApiDefinition> getManualApis() {
        return cachedApis.stream()
                .filter(api -> !api.isAutoDetected())
                .collect(Collectors.toList());
    }

    /** 获取最近扫描的API */
    public List<ApiDefinition> getLatestApis(long sinceTimestamp) {
        return cachedApis.stream()
                .filter(api -> api.getScanTimestamp() >= sinceTimestamp)
                .collect(Collectors.toList());
    }

    /** 根据URL和HTTP方法查找已扫描的API定义 */
    public ApiDefinition findApi(String url, String method) {
        for (ApiDefinition api : cachedApis) {
            if (api.getUrl().equals(url) && api.getHttpMethod().equalsIgnoreCase(method)) {
                return api;
            }
        }
        return null;
    }

    /** 根据源码文件路径查找关联的API列表 */
    public List<ApiDefinition> findApisByFilePath(String filePath) {
        return cachedApis.stream()
                .filter(api -> api.getSourceFilePath().equals(filePath))
                .collect(Collectors.toList());
    }

    /** 根据源码文件路径 + 方法所在行号精确查找 API（用于右键定位光标所在接口）。
     *  <p>行号取方法声明起始行，与扫描时写入的 sourceLineNumber 一致。</p> */
    public ApiDefinition findApiByFileAndLine(String filePath, int lineNumber) {
        if (filePath == null || filePath.isBlank() || lineNumber <= 0) return null;
        for (ApiDefinition api : cachedApis) {
            if (filePath.equals(api.getSourceFilePath()) && lineNumber == api.getSourceLineNumber()) {
                return api;
            }
        }
        return null;
    }

    /** 根据关键字搜索API */
    public List<ApiDefinition> searchApis(String keyword) {
        if (keyword == null || keyword.isBlank()) return cachedApis;
        String lowerKeyword = keyword.toLowerCase();
        return cachedApis.stream()
                .filter(api -> api.getUrl().toLowerCase().contains(lowerKeyword) ||
                        api.getName().toLowerCase().contains(lowerKeyword) ||
                        api.getControllerName().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());
    }

    /**
     * 获取ApiScannerService实例的便捷方法
     */
    public static ApiScannerService getInstance(@NotNull Project project) {
        return project.getService(ApiScannerService.class);
    }

    // ================================================================
    // v3 新增：变更检测和元数据恢复
    // ================================================================

    /**
     * 检测API变更（新增/删除）
     */
    private void detectChanges(List<String> beforeKeys, List<ApiDefinition> newApis) {
        if (beforeKeys.isEmpty()) return; // 首次扫描不标记

        Set<String> beforeSet = new HashSet<>(beforeKeys);
        Set<String> newKeys = new HashSet<>();
        int added = 0, removed = 0;

        for (ApiDefinition api : newApis) {
            String key = api.uniqueKey();
            newKeys.add(key);
            if (!beforeSet.contains(key)) {
                api.setChangeMarker(RestAutoLabConstants.CHANGE_ADDED);
                added++;
            } else {
                api.setChangeMarker(RestAutoLabConstants.CHANGE_NONE);
            }
        }

        // 统计删除数
        for (String oldKey : beforeSet) {
            if (!newKeys.contains(oldKey)) removed++;
        }

        if (added > 0 || removed > 0) {
            LOG.info("API变更检测: 新增 " + added + " 个, 删除 " + removed + " 个");
        }
    }

    /**
     * 从设置恢复API收藏状态和调用统计
     */
    private void restoreApiMetadata(List<ApiDefinition> apis) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        Set<String> starred = settings.getStarredApis();

        for (ApiDefinition api : apis) {
            String key = api.uniqueKey();
            if (starred.contains(key)) {
                api.setStarred(true);
            }
            api.setCallCount(settings.getApiCallCount(key));
            api.setLastCalledAt(settings.getApiLastCallTime(key));
        }
    }
}
