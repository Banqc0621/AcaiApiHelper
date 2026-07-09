package com.ban.acai.scanner;

import com.ban.acai.AcaiConstants;
import com.ban.acai.model.*;
import com.ban.acai.settings.AcaiSettingsState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import org.jetbrains.annotations.NotNull;

import java.util.*;
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
public class ApiScannerService {

    private static final Logger LOG = Logger.getInstance(ApiScannerService.class);

    private final Project project;

    /** 缓存已扫描的API列表 */
    private List<ApiDefinition> cachedApis = Collections.emptyList();

    /** 扫描监听器列表 */
    private final List<ScanListener> listeners = new ArrayList<>();

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

    // ================================================================
    // 扫描入口
    // ================================================================

    /**
     * 异步扫描项目中的全部API（带进度条）
     * v3: 增加变更检测
     */
    public void scanProjectApisAsync() {
        // 记录扫描前的状态
        List<String> beforeSignatures = cachedApis.stream()
                .map(ApiDefinition::uniqueKey)
                .collect(Collectors.toList());

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "扫描项目API...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("正在扫描REST控制器...");
                for (ScanListener listener : listeners) {
                    listener.onScanStarted();
                }

                List<ApiDefinition> apis = scanProjectApis(indicator);

                // v3: 变更检测
                detectChanges(beforeSignatures, apis);

                // v3: 恢复收藏和调用统计
                restoreApiMetadata(apis);

                cachedApis = apis;

                // v3: 保存签名
                List<String> newSignatures = apis.stream()
                        .map(ApiDefinition::uniqueKey)
                        .collect(Collectors.toList());
                AcaiSettingsState.getInstance(project).saveLastScanSignatures(newSignatures);

                indicator.setText("扫描完成，发现 " + apis.size() + " 个接口");
                indicator.setFraction(1.0);

                for (ScanListener listener : listeners) {
                    listener.onScanComplete(apis);
                }
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
    private List<ApiDefinition> scanProjectApis(ProgressIndicator indicator) {
        return ReadAction.nonBlocking(() -> {
            List<ApiDefinition> apis = new ArrayList<>();
            // allScope 包含项目源码 + 依赖库，确保注解类能被正确解析
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);

            // ── 1. 扫描 Spring MVC 控制器 ──
            List<PsiClass> allControllers = new ArrayList<>();
            for (String annotationFqn : AcaiConstants.SPRING_CONTROLLER_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass annotationClass = psiFacade.findClass(annotationFqn, scope);
                if (annotationClass != null) {
                    Collection<PsiClass> classes = AnnotatedElementsSearch
                            .searchPsiClasses(annotationClass, scope).findAll();
                    allControllers.addAll(classes);
                    LOG.info("Spring @" + annotationFqn.substring(annotationFqn.lastIndexOf('.') + 1)
                            + ": 发现 " + classes.size() + " 个类");
                } else {
                    LOG.info("Spring 注解类未找到: " + annotationFqn + "（项目可能未引入Spring）");
                }
            }

            // ── 2. 扫描 JAX-RS @Path 注解的类 ──
            for (String annotationFqn : AcaiConstants.JAXRS_CONTROLLER_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass annotationClass = psiFacade.findClass(annotationFqn, scope);
                if (annotationClass != null) {
                    Collection<PsiClass> classes = AnnotatedElementsSearch
                            .searchPsiClasses(annotationClass, scope).findAll();
                    allControllers.addAll(classes);
                    LOG.info("JAX-RS @Path: 发现 " + classes.size() + " 个类");
                }
            }

            // ── 3. 扫描 @FeignClient 接口 ──
            PsiClass feignAnnotation = psiFacade.findClass(AcaiConstants.ANNO_FEIGN_CLIENT, scope);
            if (feignAnnotation != null) {
                Collection<PsiClass> classes = AnnotatedElementsSearch
                        .searchPsiClasses(feignAnnotation, scope).findAll();
                allControllers.addAll(classes);
                LOG.info("@FeignClient: 发现 " + classes.size() + " 个接口");
            }

            // ── 3.5 补充：扫描带类级 @RequestMapping 的类 ──
            //    覆盖"组合注解控制器"场景：类用自定义组合注解（间接含 @RestController/@Controller），
            //    导致 AnnotatedElementsSearch 按 @RestController/@Controller 搜不到，
            //    但类级 @RequestMapping 通常仍在，可据此补充发现。
            //    候选类会经过 isFrameworkInternalController 过滤 + parseControllerClass 解析，
            //    无 HTTP 映射方法的类不会产出 API，故安全。
            PsiClass requestMappingAnno = psiFacade.findClass(AcaiConstants.ANNO_REQUEST_MAPPING, scope);
            if (requestMappingAnno != null) {
                Collection<PsiClass> classes = AnnotatedElementsSearch
                        .searchPsiClasses(requestMappingAnno, scope).findAll();
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
            for (String mappingFqn : AcaiConstants.SPRING_MAPPING_ANNOTATIONS) {
                if (indicator != null) indicator.checkCanceled();
                PsiClass mappingAnno = psiFacade.findClass(mappingFqn, scope);
                if (mappingAnno == null) continue;
                Collection<PsiMethod> methods = AnnotatedElementsSearch
                        .searchPsiMethods(mappingAnno, scope).findAll();
                for (PsiMethod m : methods) {
                    PsiClass owner = m.getContainingClass();
                    if (owner != null) allControllers.add(owner);
                }
                LOG.info("补充扫描方法级 @" + mappingFqn.substring(mappingFqn.lastIndexOf('.') + 1)
                        + ": 反向定位 " + methods.size() + " 个方法");
            }

            // 去重（某些类可能同时带多个注解）
            Set<String> seen = new HashSet<>();
            List<PsiClass> uniqueControllers = new ArrayList<>();
            for (PsiClass cls : allControllers) {
                String qfn = cls.getQualifiedName();
                if (qfn != null && seen.add(qfn)) {
                    uniqueControllers.add(cls);
                }
            }

            // ── 4. 逐个解析控制器 ──
            int total = uniqueControllers.size();
            for (int i = 0; i < total; i++) {
                if (indicator != null) {
                    indicator.checkCanceled();
                    indicator.setFraction((double) i / total);
                }
                PsiClass psiClass = uniqueControllers.get(i);
                if (indicator != null) {
                    indicator.setText("正在解析: " + psiClass.getName());
                }
                try {
                    List<ApiDefinition> classApis = parseControllerClass(psiClass);
                    apis.addAll(classApis);
                    int methodCount = psiClass.getAllMethods().length;
                    if (classApis.isEmpty()) {
                        LOG.info("解析 " + psiClass.getName()
                                + ": 0 个接口（共 " + methodCount + " 个方法，可能无HTTP映射注解或被过滤）");
                    } else {
                        LOG.info("解析 " + psiClass.getName() + ": 发现 " + classApis.size()
                                + " 个接口（共 " + methodCount + " 个方法）");
                    }
                } catch (Exception e) {
                    LOG.warn("解析控制器类失败: " + psiClass.getName() + " - " + e.getMessage(), e);
                }
            }

            LOG.info("扫描总结: " + uniqueControllers.size() + " 个控制器, " + apis.size() + " 个接口");
            return apis;
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

        // 提取类级别的基础路径（Spring @RequestMapping 或 JAX-RS @Path）
        String basePath = extractClassBasePath(psiClass);

        // 判断是否为 JAX-RS 风格
        boolean isJaxrs = isJaxrsClass(psiClass);

        // 同一方法在 getAllMethods() 中可能多次返回（继承链/接口默认方法），
        // 用 (控制器名 + 方法名 + 参数签名 + uniqueKey) 去重，避免重复；
        // 但保留方法重载（同 URL 不同参数）和不同控制器下的同名继承方法。
        Set<String> seenKeys = new HashSet<>();

        // 遍历类自身声明的方法（含从接口/父类继承的方法）
        for (PsiMethod method : psiClass.getAllMethods()) {
            try {
                List<ApiDefinition> methodApis;
                if (isJaxrs) {
                    methodApis = parseJaxrsMethod(method, controllerName, basePath, psiClass);
                } else {
                    methodApis = parseSpringMethod(method, controllerName, basePath, psiClass);
                }
                for (ApiDefinition api : methodApis) {
                    // 路径含未解析占位符（${...}）：尽量清理保留，而不是整条丢弃
                    if (hasUnresolvedPlaceholder(api.getUrl())) {
                        String cleaned = cleanPlaceholderInPath(api.getUrl());
                        if (cleaned == null || cleaned.isBlank() || cleaned.equals("/")) {
                            LOG.info("跳过含 SpEL 表达式或空路径的接口: " + api.getUrl()
                                    + "（来自 " + controllerName + "." + method.getName() + "）");
                            continue;
                        }
                        LOG.info("清理路径占位符: " + api.getUrl() + " -> " + cleaned
                                + "（来自 " + controllerName + "." + method.getName() + "）");
                        api.setUrl(cleaned);
                    }
                    // 去重键：控制器 + 方法名 + 参数签名 + uniqueKey(METHOD|URL)。
                    // 这样能去掉 getAllMethods() 对同一方法的重复返回，
                    // 同时保留重载方法（同 URL 不同参数）。
                    StringBuilder paramSig = new StringBuilder();
                    for (PsiParameter p : method.getParameterList().getParameters()) {
                        paramSig.append(p.getType().getCanonicalText()).append(',');
                    }
                    String dedupKey = controllerName + "#" + method.getName()
                            + "(" + paramSig + ")|" + api.uniqueKey();
                    if (seenKeys.add(dedupKey)) {
                        apis.add(api);
                    }
                }
            } catch (Exception e) {
                LOG.warn("解析方法失败: " + controllerName + "." + method.getName()
                        + " - " + e.getMessage());
            }
        }

        return apis;
    }

    /** 判断类是否为 JAX-RS 风格（有 @Path 注解但无 Spring 控制器注解） */
    private boolean isJaxrsClass(PsiClass psiClass) {
        boolean hasPath = psiClass.getAnnotation(AcaiConstants.JAXRS_PATH_JAVAX) != null
                || psiClass.getAnnotation(AcaiConstants.JAXRS_PATH_JAKARTA) != null;
        boolean hasSpring = psiClass.getAnnotation(AcaiConstants.ANNO_REST_CONTROLLER) != null
                || psiClass.getAnnotation(AcaiConstants.ANNO_CONTROLLER) != null
                || psiClass.getAnnotation(AcaiConstants.ANNO_FEIGN_CLIENT) != null;
        return hasPath && !hasSpring;
    }

    /**
     * 判断是否为框架/库内置控制器（非用户业务接口）。
     * <p>典型例子：Spring Boot 的 <code>BasicErrorController</code>，
     * 它的 <code>@RequestMapping("${server.error.path:${error.path:/error}}")</code> 含未解析占位符，
     * 不是用户业务接口，应当过滤掉。</p>
     * <p>判定依据：</p>
     * <ul>
     *   <li>类的全限定名命中黑名单（BasicErrorController、ErrorController 实现类、actuator 端点）</li>
     *   <li>包名以 <code>org.springframework.</code> 开头（Spring 框架本身及 Spring Boot 自动配置）</li>
     *   <li>实现了 <code>org.springframework.boot.web.servlet.error.ErrorController</code></li>
     * </ul>
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

        // 3. 实现了 ErrorController 接口（Spring Boot 错误处理控制器）
        for (PsiClass iface : psiClass.getInterfaces()) {
            String ifqfn = iface.getQualifiedName();
            if (ifqfn != null && ifqfn.endsWith(".ErrorController")) {
                return true;
            }
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
     * 提取控制器类级别的 @RequestMapping 基础路径（Spring MVC）
     */
    private String extractClassBasePath(PsiClass psiClass) {
        // Spring @RequestMapping
        PsiAnnotation requestMapping = psiClass.getAnnotation(AcaiConstants.ANNO_REQUEST_MAPPING);
        if (requestMapping != null) {
            return extractPathFromAnnotation(requestMapping);
        }
        // JAX-RS @Path
        PsiAnnotation jaxrsPath = psiClass.getAnnotation(AcaiConstants.JAXRS_PATH_JAVAX);
        if (jaxrsPath == null) jaxrsPath = psiClass.getAnnotation(AcaiConstants.JAXRS_PATH_JAKARTA);
        if (jaxrsPath != null) {
            return extractJaxrsPath(jaxrsPath);
        }
        // FeignClient @FeignClient(path = "...")
        PsiAnnotation feignClient = psiClass.getAnnotation(AcaiConstants.ANNO_FEIGN_CLIENT);
        if (feignClient != null) {
            PsiAnnotationMemberValue pathVal = feignClient.findAttributeValue("path");
            if (pathVal != null) {
                String path = cleanAnnotationValue(pathVal.getText());
                if (!path.isEmpty()) return path;
            }
            // 也尝试 value 属性
            PsiAnnotationMemberValue valVal = feignClient.findAttributeValue("value");
            if (valVal != null) {
                String path = cleanAnnotationValue(valVal.getText());
                if (!path.isEmpty() && path.startsWith("/")) return path;
            }
        }
        return "";
    }

    /**
     * 解析Spring风格的方法为API定义
     * 支持多路径注解（如 @GetMapping({"/list","/query"})），
     * 每个路径各生成一个 ApiDefinition。
     */
    private List<ApiDefinition> parseSpringMethod(PsiMethod method, String controllerName,
                                                  String basePath, PsiClass declaringClass) {
        PsiAnnotation mappingAnnotation = findSpringMappingAnnotation(method);
        if (mappingAnnotation == null) return Collections.emptyList();

        String httpMethod = resolveSpringHttpMethod(mappingAnnotation);
        List<String> methodPaths = extractPathsFromAnnotation(mappingAnnotation);

        List<ApiDefinition> result = new ArrayList<>();
        if (methodPaths.isEmpty()) {
            // 无显式路径（仅类级 basePath），生成单个接口
            String fullPath = normalizePath(basePath);
            ApiDefinition api = buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass);
            result.add(api);
            return result;
        }
        for (String methodPath : methodPaths) {
            String fullPath = normalizePath(basePath + methodPath);
            result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
        }
        return result;
    }

    /**
     * 查找方法上的Spring HTTP映射注解
     */
    private PsiAnnotation findSpringMappingAnnotation(PsiMethod method) {
        String[] annotations = {
                AcaiConstants.ANNO_GET_MAPPING,
                AcaiConstants.ANNO_POST_MAPPING,
                AcaiConstants.ANNO_PUT_MAPPING,
                AcaiConstants.ANNO_DELETE_MAPPING,
                AcaiConstants.ANNO_PATCH_MAPPING,
                AcaiConstants.ANNO_REQUEST_MAPPING
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
                                                 String basePath, PsiClass declaringClass) {
        // 查找 JAX-RS HTTP 方法注解
        String httpMethod = resolveJaxrsHttpMethod(method);
        if (httpMethod == null) return Collections.emptyList();

        // JAX-RS @Path（支持多路径）
        List<String> methodPaths = new ArrayList<>();
        PsiAnnotation pathAnno = method.getAnnotation(AcaiConstants.JAXRS_PATH_JAVAX);
        if (pathAnno == null) pathAnno = method.getAnnotation(AcaiConstants.JAXRS_PATH_JAKARTA);
        if (pathAnno != null) {
            methodPaths = extractJaxrsPaths(pathAnno);
        }

        List<ApiDefinition> result = new ArrayList<>();
        if (methodPaths.isEmpty()) {
            String fullPath = normalizePath(basePath);
            result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
            return result;
        }
        for (String methodPath : methodPaths) {
            String fullPath = normalizePath(basePath + methodPath);
            result.add(buildApiDefinition(method, controllerName, httpMethod, fullPath, declaringClass));
        }
        return result;
    }

    /**
     * 从JAX-RS注解推断HTTP方法
     */
    private String resolveJaxrsHttpMethod(PsiMethod method) {
        // javax.ws.rs 注解
        for (String ann : AcaiConstants.JAXRS_METHOD_ANNOTATIONS_JAVAX) {
            if (method.getAnnotation(ann) != null) {
                return ann.substring(ann.lastIndexOf('.') + 1).toUpperCase();
            }
        }
        // jakarta.ws.rs 注解
        for (String ann : AcaiConstants.JAXRS_METHOD_ANNOTATIONS_JAKARTA) {
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
        api.setDeprecated(method.hasAnnotation(AcaiConstants.ANNO_DEPRECATED));

        api.setParameters(new ArrayList<>(parseMethodParameters(method)));
        api.setResponseBodyType(extractReturnType(method));
        // v3: 解析返回类型的实体类字段树（用于在无测试记录时填充响应参数表/示例）
        api.setResponseSchema(extractResponseSchema(method));

        // 标记来源为自动扫描
        api.setSource(AcaiConstants.API_SOURCE_AUTO);
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
     * <p>处理 PSI 注解属性值的文本形式，如：</p>
     * <ul>
     *   <li><code>"/users"</code> → ["/users"]</li>
     *   <li><code>{"/list", "/query"}</code> → ["/list", "/query"]</li>
     *   <li><code>null</code> 或空 → []</li>
     * </ul>
     * <p>注意：跳过空字符串和纯空白项，避免产出无效路径。</p>
     */
    private List<String> splitAnnotationValues(PsiAnnotationMemberValue value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
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
        PsiAnnotation apiOperation = method.getAnnotation(AcaiConstants.SWAGGER_API_OPERATION);
        if (apiOperation != null) {
            PsiAnnotationMemberValue value = apiOperation.findAttributeValue("value");
            if (value != null) {
                String text = value.getText();
                if (text != null && !text.isBlank()) return cleanAnnotationValue(text);
            }
        }

        // OpenAPI 3.x @Operation
        PsiAnnotation operation = method.getAnnotation(AcaiConstants.OPENAPI_OPERATION);
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

        PsiAnnotation apiOperation = method.getAnnotation(AcaiConstants.SWAGGER_API_OPERATION);
        if (apiOperation != null) {
            PsiAnnotationMemberValue notes = apiOperation.findAttributeValue("notes");
            if (notes != null) {
                String text = notes.getText();
                if (text != null && !text.isBlank() && !text.equals("\"\"")) return cleanAnnotationValue(text);
            }
        }

        // OpenAPI 3: @Operation(summary=..., description=...) — summary 已被 extractApiName 使用，这里取 description
        PsiAnnotation operation = method.getAnnotation(AcaiConstants.OPENAPI_OPERATION);
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
        PsiAnnotation requestParam = param.getAnnotation(AcaiConstants.ANNO_REQUEST_PARAM);
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
        PsiAnnotation pathVariable = param.getAnnotation(AcaiConstants.ANNO_PATH_VARIABLE);
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
        PsiAnnotation requestBody = param.getAnnotation(AcaiConstants.ANNO_REQUEST_BODY);
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
        PsiAnnotation requestHeader = param.getAnnotation(AcaiConstants.ANNO_REQUEST_HEADER);
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
        PsiAnnotation cookieValue = param.getAnnotation(AcaiConstants.ANNO_COOKIE_VALUE);
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
        PsiAnnotation requestPart = param.getAnnotation(AcaiConstants.ANNO_REQUEST_PART);
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
        PsiAnnotation modelAttribute = param.getAnnotation(AcaiConstants.ANNO_MODEL_ATTRIBUTE);
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
                AcaiConstants.JAXRS_QUERY_PARAM_JAVAX, AcaiConstants.JAXRS_QUERY_PARAM_JAKARTA}) {
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
                AcaiConstants.JAXRS_PATH_PARAM_JAVAX, AcaiConstants.JAXRS_PATH_PARAM_JAKARTA}) {
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
                AcaiConstants.JAXRS_HEADER_PARAM_JAVAX, AcaiConstants.JAXRS_HEADER_PARAM_JAKARTA}) {
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
                AcaiConstants.JAXRS_FORM_PARAM_JAVAX, AcaiConstants.JAXRS_FORM_PARAM_JAKARTA}) {
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
                AcaiConstants.JAXRS_COOKIE_PARAM_JAVAX, AcaiConstants.JAXRS_COOKIE_PARAM_JAKARTA}) {
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

        PsiAnnotation apiParam = param.getAnnotation(AcaiConstants.SWAGGER_API_PARAM);
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
        for (String ann : AcaiConstants.VALIDATION_REQUIRED_ANNOTATIONS) {
            if (field.hasAnnotation(ann)) return true;
        }
        return false;
    }

    /** 从字段注解或Javadoc提取描述 */
    private String extractFieldDescription(PsiField field) {
        PsiAnnotation apiModelProperty = field.getAnnotation(AcaiConstants.SWAGGER_API_MODEL_PROPERTY);
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
        PsiAnnotation apiModelProperty = field.getAnnotation(AcaiConstants.SWAGGER_API_MODEL_PROPERTY);
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
        if (!result.equals(AcaiConstants.DEFAULT_CONTENT_TYPE)) return result;

        // 再从类级别取
        result = extractConsumesFromAnnotations(declaringClass.getAnnotations());
        if (!result.equals(AcaiConstants.DEFAULT_CONTENT_TYPE)) return result;

        return AcaiConstants.DEFAULT_CONTENT_TYPE;
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
        return AcaiConstants.DEFAULT_CONTENT_TYPE;
    }

    /**
     * 从映射注解或类级别提取 produces Content-Type
     */
    private String extractProduces(PsiMethod method, PsiClass declaringClass) {
        String result = extractProducesFromAnnotations(method.getAnnotations());
        if (!result.equals(AcaiConstants.DEFAULT_CONTENT_TYPE)) return result;

        result = extractProducesFromAnnotations(declaringClass.getAnnotations());
        if (!result.equals(AcaiConstants.DEFAULT_CONTENT_TYPE)) return result;

        return AcaiConstants.DEFAULT_CONTENT_TYPE;
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
        return AcaiConstants.DEFAULT_CONTENT_TYPE;
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
        } catch (Exception ex) {
            // 解析失败时返回空列表（导出器会回退到按类型名推断）
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
        api.setUrl(url);
        api.setName(name != null ? name : "");
        api.setControllerName("手动添加");
        api.setSource(AcaiConstants.API_SOURCE_MANUAL);
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
                api.setChangeMarker(AcaiConstants.CHANGE_ADDED);
                added++;
            } else {
                api.setChangeMarker(AcaiConstants.CHANGE_NONE);
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
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
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