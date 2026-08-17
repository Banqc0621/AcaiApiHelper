package com.hronline.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.io.IOException;

/**
 * v2.0.0 新增：统一导出 / 导入错误的提示工具。
 *
 * <p>原代码在 ApiDebuggerPanel / ApiTreePanel 中共有 9+ 处形如
 * {@code Messages.showErrorDialog(project, "导出失败: " + e.getMessage(), "错误")}
 * 的硬编码提示，标题统一为"错误"、文案不分类、缺少恢复建议。</p>
 *
 * <p>本工具按操作分类给标题，按异常类型给差异化文案，让教师/开发一眼看出
 * "是哪一类导出"出错了、"应该怎么办"。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   try {
 *       exporter.exportTo(path);
 *   } catch (IOException e) {
 *       ExportErrorReporter.reportExportFailure(project, "API 文档", e);
 *   }
 * }</pre>
 */
public final class ExportErrorReporter {

    private ExportErrorReporter() {}

    /** 导出操作类型 —— 用于生成标题前缀。 */
    public enum Operation {
        /** 导出 API 文档（Markdown） */
        API_DOC("导出 API 文档"),
        /** 用模板导出 API 文档 */
        API_DOC_TEMPLATE("模板导出 API 文档"),
        /** 导出测试报告（HTML） */
        TEST_REPORT("导出测试报告"),
        /** 导出 Postman Collection */
        POSTMAN_COLLECTION("导出 Postman"),
        /** 导出 cURL 命令 */
        CURL("复制 cURL"),
        /** 导出测试数据 */
        TEST_DATA("导出测试数据"),
        /** 导出配置 */
        CONFIG("导出配置"),
        /** 导入操作 */
        IMPORT("导入数据");

        public final String displayName;
        Operation(String displayName) { this.displayName = displayName; }
    }

    /**
     * 报告导出失败。
     *
     * @param project   当前项目（可空，为空则弹非模态错误）
     * @param operation 操作类型
     * @param cause     原始异常
     */
    public static void reportExportFailure(Project project, Operation operation, Throwable cause) {
        report(project, operation, "导出", cause);
    }

    /**
     * 报告导入失败。
     */
    public static void reportImportFailure(Project project, Operation operation, Throwable cause) {
        report(project, operation, "导入", cause);
    }

    /**
     * 报告复制/剪贴板类操作失败（如 cURL 复制）。
     */
    public static void reportCopyFailure(Project project, Operation operation, Throwable cause) {
        report(project, operation, "复制", cause);
    }

    private static void report(Project project, Operation operation, String verb, Throwable cause) {
        String title = verb + "失败 · " + operation.displayName;
        String message = buildMessage(verb, operation, cause);
        Messages.showErrorDialog(project, message, title);
    }

    /**
     * v2.0.0 新增：按异常类型生成差异化文案 + 恢复建议。
     */
    private static String buildMessage(String verb, Operation operation, Throwable cause) {
        StringBuilder sb = new StringBuilder();
        sb.append(verb).append(' ').append(operation.displayName).append(" 时发生错误。\n\n");

        if (cause instanceof SecurityException) {
            sb.append("可能原因：文件被占用、权限不足或路径无效。\n");
            sb.append("建议：检查目标路径是否可写、关闭正在打开该文件的程序后重试。\n\n");
        } else if (cause instanceof java.io.FileNotFoundException) {
            sb.append("可能原因：目标路径不存在或已被删除。\n");
            sb.append("建议：确认父目录存在，或选择其他输出位置。\n\n");
        } else if (cause instanceof IOException) {
            sb.append("可能原因：磁盘空间不足、文件被占用或网络路径不可达。\n");
            sb.append("建议：检查磁盘空间、目标路径可达性后重试。\n\n");
        } else if (cause instanceof IllegalArgumentException) {
            sb.append("可能原因：参数非法（如模板语法错误、字段缺失）。\n");
            sb.append("建议：核对输入内容是否符合要求。\n\n");
        } else {
            sb.append("可能原因：未知异常。\n");
            sb.append("建议：稍后重试；若反复出现请查看 IDEA 日志。\n\n");
        }

        String detail = cause == null ? "(无详细异常信息)" : cause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = cause == null ? "" : cause.getClass().getSimpleName();
        }
        sb.append("详细错误：").append(detail);
        return sb.toString();
    }
}
