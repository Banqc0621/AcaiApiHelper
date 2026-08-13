package com.hronline.gutter;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.scanner.ApiScannerService;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * API Gutter图标提供器 - 在Java/Kotlin Controller方法行号旁显示调试图标
 *
 * 功能：
 * 1. 自动识别带Spring MVC映射注解的方法
 * 2. 在方法左侧Gutter区域显示绿色HTTP方法图标
 * 3. 点击图标在右侧ToolWindow中打开该接口的调试面板
 * 4. 悬浮tooltip显示完整的URL路径和HTTP方法
 *
 * 识别的注解类型：
 * - @RequestMapping
 * - @GetMapping / @PostMapping / @PutMapping / @DeleteMapping / @PatchMapping
 *
 * 注意：
 * 此Provider仅在语言为"JAVA"的文件中生效（plugin.xml中配置了language="JAVA"）。
 * 图标复用IntelliJ内置的ApiDescription图标，避免额外的图标资源。
 */
public class ApiLineMarkerProvider implements LineMarkerProvider {

    /** 支持的Spring MVC映射注解全限定名 */
    private static final Set<String> MAPPING_ANNOTATIONS = new java.util.HashSet<>(RestAutoLabConstants.SPRING_MAPPING_ANNOTATIONS);

    /**
     * 获取指定PSI元素的行标记信息
     *
     * IntelliJ平台对编辑器中每个可见元素调用此方法。
     * 仅处理 PsiMethod（Java方法），并且该方法必须带有Spring MVC映射注解。
     *
     * @param element 当前PSI元素
     * @return LineMarkerInfo（匹配时）或 null（不匹配时）
     */
    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // 仅处理方法名标识符（避免在同一方法上重复显示图标）
        if (!(element instanceof PsiIdentifier)) return null;
        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod)) return null;
        PsiMethod method = (PsiMethod) parent;

        // 检查方法是否有Spring MVC映射注解
        PsiAnnotation mappingAnnotation = null;
        for (String annotationFqn : MAPPING_ANNOTATIONS) {
            mappingAnnotation = method.getAnnotation(annotationFqn);
            if (mappingAnnotation != null) break;
        }
        if (mappingAnnotation == null) return null;

        // 提取HTTP方法和路径信息
        String qualifiedName = mappingAnnotation.getQualifiedName();
        String httpMethod = resolveHttpMethod(qualifiedName != null ? qualifiedName : "");
        String path = extractPath(mappingAnnotation);

        // 获取类级别的基础路径
        PsiClass containingClass = method.getContainingClass();
        String basePath = "";
        if (containingClass != null) {
            PsiAnnotation classAnnotation = containingClass.getAnnotation(
                    RestAutoLabConstants.ANNO_REQUEST_MAPPING);
            if (classAnnotation != null) {
                basePath = extractPath(classAnnotation);
            }
        }

        String fullPath = normalizePath(basePath + path);

        // 创建Gutter图标
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AllIcons.Nodes.Plugin,  // 使用内置的插件图标
                elem -> httpMethod + " " + fullPath,  // tooltip文本
                (elem, event) -> {
                    // 点击事件：打开ToolWindow并定位到该API
                    Project project = method.getProject();
                    navigateToApiInToolWindow(project, httpMethod, fullPath);
                },
                GutterIconRenderer.Alignment.RIGHT,
                () -> "RestAutoLab: " + httpMethod + " " + fullPath
        );
    }

    /**
     * 根据注解全限定名解析HTTP方法
     */
    private String resolveHttpMethod(String annotationFqn) {
        if (annotationFqn.endsWith("GetMapping")) return "GET";
        if (annotationFqn.endsWith("PostMapping")) return "POST";
        if (annotationFqn.endsWith("PutMapping")) return "PUT";
        if (annotationFqn.endsWith("DeleteMapping")) return "DELETE";
        if (annotationFqn.endsWith("PatchMapping")) return "PATCH";
        return "GET";  // @RequestMapping 默认为GET
    }

    /**
     * 从映射注解中提取路径值
     */
    private String extractPath(PsiAnnotation annotation) {
        String value = null;
        var attrValue = annotation.findAttributeValue("value");
        if (attrValue != null) {
            value = attrValue.getText();
        }
        if (value == null || value.isBlank() || value.equals("{}")) {
            var pathValue = annotation.findAttributeValue("path");
            if (pathValue != null) {
                value = pathValue.getText();
            }
        }
        String cleaned = value != null ? value.trim() : "";
        // 去除数组包裹
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            if (cleaned.contains(",")) {
                cleaned = cleaned.substring(0, cleaned.indexOf(",")).trim();
            }
        }
        cleaned = removeSurroundingQuotes(cleaned);
        return cleaned;
    }

    /**
     * 去除字符串两端的引号
     */
    private String removeSurroundingQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 规范化URL路径
     */
    private String normalizePath(String path) {
        String normalized = path.replace("//", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    /**
     * 在ToolWindow中定位到指定的API
     *
     * 打开右侧的RestAutoLab面板，并尝试在树中选中匹配的API节点。
     * 如果API尚未被扫描到，则触发一次新的扫描。
     */
    private void navigateToApiInToolWindow(Project project, String httpMethod, String url) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // 打开ToolWindow
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(RestAutoLabConstants.TOOLWINDOW_ID);
            if (toolWindow != null) {
                toolWindow.activate(null);
            }

            // 尝试在已扫描的API中查找匹配项
            ApiScannerService scannerService = ApiScannerService.getInstance(project);
            ApiDefinition matchedApi = scannerService.findApi(url, httpMethod);

            if (matchedApi == null && scannerService.getCachedApis().isEmpty()) {
                // 如果尚未扫描，触发扫描
                scannerService.scanProjectApisAsync();
            }
        });
    }
}