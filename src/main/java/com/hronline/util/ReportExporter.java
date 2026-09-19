package com.hronline.util;

import com.hronline.model.TestReport;
import com.hronline.model.TestResult;
import com.hronline.model.TestStatus;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 测试报告导出工具 - 生成HTML格式的可视化测试报告
 */
public class ReportExporter {

    /**
     * 导出测试报告为HTML文件
     */
    public static String exportHtmlReport(TestReport report, String outputDir) throws IOException {
        Path dirPath = Paths.get(outputDir);
        Files.createDirectories(dirPath);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(report.getStartTime()));
        String fileName = "test_report_" + timestamp + ".html";
        File outputFile = dirPath.resolve(fileName).toFile();

        String html = generateHtml(report);

        try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(html);
        }

        return outputFile.getAbsolutePath();
    }

    private static String generateHtml(TestReport report) {
        int total = report.getResults().size();
        int passed = report.getPassedCount();
        int failed = report.getFailedCount();
        int errors = report.getErrorCount();
        int skipped = report.getSkippedCount();
        double passRate = report.getPassRate();
        long duration = report.getTotalDuration();

        String passColor = passed == total ? "#2E7D32" : (failed > 0 || errors > 0 ? "#C62828" : "#ED6C02");

        boolean hasSkipped = skipped > 0;
        int gridColumns = hasSkipped ? 5 : 4;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>RestAutoLab 测试报告 - ").append(escapeHtml(report.getTestName())).append("</title>\n");
        sb.append("<style>\n");
        sb.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f5f5; padding: 20px; color: #333; }\n");
        sb.append(".container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); overflow: hidden; }\n");
        sb.append(".header { background: linear-gradient(135deg, #1976D2, #0D47A1); color: white; padding: 24px 32px; }\n");
        sb.append(".header h1 { font-size: 24px; margin-bottom: 8px; word-break: break-all; }\n");
        sb.append(".header .meta { opacity: 0.9; font-size: 13px; }\n");
        sb.append(".summary { display: grid; grid-template-columns: repeat(").append(gridColumns).append(", 1fr); gap: 16px; padding: 24px 32px; border-bottom: 1px solid #eee; }\n");
        sb.append(".stat-card { text-align: center; padding: 16px; border-radius: 8px; background: #f8f9fa; }\n");
        sb.append(".stat-card .number { font-size: 32px; font-weight: bold; margin-bottom: 4px; }\n");
        sb.append(".stat-card .label { font-size: 13px; color: #666; }\n");
        sb.append(".stat-pass .number { color: #2E7D32; }\n");
        sb.append(".stat-fail .number { color: #C62828; }\n");
        sb.append(".stat-error .number { color: #ED6C02; }\n");
        sb.append(".stat-skip .number { color: #6B7280; }\n");
        sb.append(".stat-total .number { color: #1976D2; }\n");
        sb.append(".pass-rate { padding: 24px 32px; text-align: center; border-bottom: 1px solid #eee; }\n");
        sb.append(".pass-rate .rate { font-size: 48px; font-weight: bold; color: ").append(passColor).append("; }\n");
        sb.append(".pass-rate .label { font-size: 14px; color: #666; margin-top: 4px; }\n");
        sb.append(".progress-bar { height: 8px; background: #eee; border-radius: 4px; margin: 16px 32px 0; overflow: hidden; }\n");
        sb.append(".progress-fill { height: 100%; background: linear-gradient(90deg, #4CAF50, #2E7D32); transition: width 0.5s; border-radius: 4px; }\n");
        sb.append(".results { padding: 24px 32px; }\n");
        sb.append(".results h2 { font-size: 18px; margin-bottom: 16px; color: #333; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; font-size: 13px; }\n");
        sb.append("th { background: #f8f9fa; padding: 12px; text-align: left; font-weight: 600; border-bottom: 2px solid #dee2e6; }\n");
        sb.append("td { padding: 10px 12px; border-bottom: 1px solid #eee; }\n");
        sb.append("tr:hover { background: #f8f9fa; }\n");
        sb.append(".status-pass { color: #2E7D32; font-weight: bold; }\n");
        sb.append(".status-fail { color: #C62828; font-weight: bold; }\n");
        sb.append(".status-error { color: #ED6C02; font-weight: bold; }\n");
        sb.append(".status-skip { color: #6B7280; font-weight: bold; }\n");
        sb.append(".method { display: inline-block; padding: 2px 8px; border-radius: 4px; color: white; font-weight: bold; font-size: 11px; min-width: 50px; text-align: center; }\n");
        sb.append(".method-GET { background: #2E7D32; }\n");
        sb.append(".method-POST { background: #1565C0; }\n");
        sb.append(".method-PUT { background: #ED6C02; }\n");
        sb.append(".method-DELETE { background: #C62828; }\n");
        sb.append(".method-PATCH { background: #6B21A8; }\n");
        sb.append(".footer { padding: 16px 32px; background: #f8f9fa; text-align: center; font-size: 12px; color: #999; border-top: 1px solid #eee; }\n");
        sb.append(".error-msg { color: #C62828; font-size: 12px; margin-top: 4px; }\n");
        // 可展开详情样式
        sb.append(".detail-toggle { cursor: pointer; color: #1976D2; font-size: 12px; margin-top: 4px; user-select: none; }\n");
        sb.append(".detail-toggle:hover { text-decoration: underline; }\n");
        sb.append(".detail-content { display: none; margin-top: 8px; padding: 12px; background: #f8f9fa; border-radius: 4px; border: 1px solid #eee; font-family: 'Courier New', Courier, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; max-height: 400px; overflow-y: auto; }\n");
        sb.append(".detail-section { margin-bottom: 12px; }\n");
        sb.append(".detail-section:last-child { margin-bottom: 0; }\n");
        sb.append(".detail-title { font-weight: bold; color: #333; margin-bottom: 4px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }\n");
        sb.append(".detail-url { color: #1565C0; word-break: break-all; }\n");
        sb.append(".detail-headers { color: #555; }\n");
        sb.append("</style>\n");
        sb.append("<script>\n");
        sb.append("function toggleDetail(id) {\n");
        sb.append("  var el = document.getElementById(id);\n");
        sb.append("  var toggle = document.getElementById('toggle-' + id);\n");
        sb.append("  if (el.style.display === 'block') {\n");
        sb.append("    el.style.display = 'none';\n");
        sb.append("    toggle.textContent = '▶ 查看请求/响应详情';\n");
        sb.append("  } else {\n");
        sb.append("    el.style.display = 'block';\n");
        sb.append("    toggle.textContent = '▼ 收起详情';\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("</script>\n");
        sb.append("</head>\n<body>\n");

        sb.append("<div class=\"container\">\n");

        // Header
        sb.append("<div class=\"header\">\n");
        sb.append("<h1>").append(escapeHtml(report.getTestName())).append("</h1>\n");
        sb.append("<div class=\"meta\">\n");
        sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(report.getEndTime())));
        sb.append(" | 总耗时: ").append(duration).append("ms\n");
        sb.append("</div>\n</div>\n");

        // Pass rate
        sb.append("<div class=\"pass-rate\">\n");
        sb.append("<div class=\"rate\">").append(String.format("%.1f", passRate)).append("%</div>\n");
        sb.append("<div class=\"label\">测试通过率</div>\n");
        sb.append("<div class=\"progress-bar\"><div class=\"progress-fill\" style=\"width:").append(passRate).append("%\"></div></div>\n");
        sb.append("</div>\n");

        // Summary stats
        sb.append("<div class=\"summary\">\n");
        sb.append("<div class=\"stat-card stat-total\"><div class=\"number\">").append(total).append("</div><div class=\"label\">总接口数</div></div>\n");
        sb.append("<div class=\"stat-card stat-pass\"><div class=\"number\">").append(passed).append("</div><div class=\"label\">通过</div></div>\n");
        sb.append("<div class=\"stat-card stat-fail\"><div class=\"number\">").append(failed).append("</div><div class=\"label\">失败</div></div>\n");
        sb.append("<div class=\"stat-card stat-error\"><div class=\"number\">").append(errors).append("</div><div class=\"label\">异常</div></div>\n");
        if (hasSkipped) {
            sb.append("<div class=\"stat-card stat-skip\"><div class=\"number\">").append(skipped).append("</div><div class=\"label\">跳过</div></div>\n");
        }
        sb.append("</div>\n");

        // Results table
        sb.append("<div class=\"results\">\n");
        sb.append("<h2>详细结果</h2>\n");
        sb.append("<table>\n<thead><tr>\n");
        sb.append("<th>#</th><th>方法</th><th>接口</th><th>状态码</th><th>耗时</th><th>结果</th>\n");
        sb.append("</tr></thead>\n<tbody>\n");

        int idx = 1;
        for (TestResult r : report.getResults()) {
            String method = r.getApiDefinition().getHttpMethod();
            String statusClass;
            String statusText;
            if (r.getStatus() == TestStatus.PASSED) {
                statusClass = "status-pass";
                statusText = "✓ 通过";
            } else if (r.getStatus() == TestStatus.SKIPPED) {
                statusClass = "status-skip";
                statusText = "⊘ 跳过";
            } else if (r.getStatus() == TestStatus.ERROR) {
                statusClass = "status-error";
                statusText = "⚠ 异常";
            } else {
                statusClass = "status-fail";
                statusText = "✗ 失败";
            }

            sb.append("<tr>\n");
            sb.append("<td>").append(idx).append("</td>\n");
            sb.append("<td><span class=\"method method-").append(method).append("\">").append(method).append("</span></td>\n");
            sb.append("<td>").append(escapeHtml(r.getApiDefinition().getUrl()));
            if (r.getApiDefinition().getName() != null && !r.getApiDefinition().getName().isBlank()) {
                sb.append("<br><small style=\"color:#999\">").append(escapeHtml(r.getApiDefinition().getName())).append("</small>");
            }
            if ((r.getStatus() == TestStatus.ERROR || r.getStatus() == TestStatus.FAILED)
                    && r.getErrorMessage() != null && !r.getErrorMessage().isBlank()) {
                sb.append("<div class=\"error-msg\">").append(escapeHtml(r.getErrorMessage())).append("</div>");
            }
            // 添加展开详情按钮
            String detailId = "detail-" + idx;
            sb.append("<div class=\"detail-toggle\" id=\"toggle-").append(detailId).append("\" onclick=\"toggleDetail('").append(detailId).append("')\">▶ 查看请求/响应详情</div>");
            sb.append("<div class=\"detail-content\" id=\"").append(detailId).append("\">");
            // 请求URL
            sb.append("<div class=\"detail-section\"><div class=\"detail-title\">请求URL:</div><div class=\"detail-url\">").append(escapeHtml(r.getRequestUrl())).append("</div></div>");
            // 请求头
            if (r.getRequestHeaders() != null && !r.getRequestHeaders().isEmpty()) {
                sb.append("<div class=\"detail-section\"><div class=\"detail-title\">请求头:</div><div class=\"detail-headers\">");
                for (java.util.Map.Entry<String, String> entry : r.getRequestHeaders().entrySet()) {
                    sb.append(escapeHtml(entry.getKey())).append(": ").append(escapeHtml(entry.getValue())).append("\n");
                }
                sb.append("</div></div>");
            }
            // 请求体
            if (r.getRequestBody() != null && !r.getRequestBody().isBlank()) {
                sb.append("<div class=\"detail-section\"><div class=\"detail-title\">请求体:</div><div>").append(escapeHtml(formatJsonIfPossible(r.getRequestBody()))).append("</div></div>");
            }
            // 响应头
            if (r.getResponseHeaders() != null && !r.getResponseHeaders().isEmpty()) {
                sb.append("<div class=\"detail-section\"><div class=\"detail-title\">响应头:</div><div class=\"detail-headers\">");
                for (java.util.Map.Entry<String, String> entry : r.getResponseHeaders().entrySet()) {
                    sb.append(escapeHtml(entry.getKey())).append(": ").append(escapeHtml(entry.getValue())).append("\n");
                }
                sb.append("</div></div>");
            }
            // 响应体
            if (r.getResponseBody() != null && !r.getResponseBody().isBlank()) {
                sb.append("<div class=\"detail-section\"><div class=\"detail-title\">响应体:</div><div>").append(escapeHtml(formatJsonIfPossible(r.getResponseBody()))).append("</div></div>");
            }
            sb.append("</div>");
            sb.append("</td>\n");
            String statusCodeDisplay = r.getStatusCode() > 0 ? String.valueOf(r.getStatusCode()) : "-";
            sb.append("<td>").append(statusCodeDisplay).append("</td>\n");
            sb.append("<td>").append(r.getDurationMs()).append("ms</td>\n");
            sb.append("<td class=\"").append(statusClass).append("\">").append(statusText).append("</td>\n");
            sb.append("</tr>\n");
            idx++;
        }

        sb.append("</tbody>\n</table>\n</div>\n");

        // Footer
        sb.append("<div class=\"footer\">\n");
        sb.append("Generated by RestAutoLab v1.0.3\n");
        sb.append("</div>\n");

        sb.append("</div>\n</body>\n</html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 如果内容是JSON格式则格式化缩进，否则原样返回
     */
    private static String formatJsonIfPossible(String content) {
        if (content == null || content.isBlank()) return "";
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return content;
        }
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(trimmed);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(element);
        } catch (Exception e) {
            return content;
        }
    }
}