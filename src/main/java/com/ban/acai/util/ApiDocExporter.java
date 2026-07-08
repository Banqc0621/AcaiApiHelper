package com.ban.acai.util;

import com.ban.acai.model.ApiDefinition;
import com.ban.acai.model.ApiParameter;
import com.ban.acai.model.ParameterLocation;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API文档导出工具 - 生成Markdown格式的接口文档
 */
public class ApiDocExporter {

    /**
     * 导出单个Controller的API文档
     */
    public static String exportControllerDoc(String controllerName, List<ApiDefinition> apis, String outputFile) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(controllerName).append("\n\n");
        md.append("> 自动生成 by Acai API Helper v3.0.0  \n");
        md.append("> 生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        md.append("---\n\n");

        // Group APIs by controller sub-path or keep flat
        for (ApiDefinition api : apis) {
            appendApiDoc(md, api);
        }

        if (outputFile != null) {
            Path path = Paths.get(outputFile);
            Files.createDirectories(path.getParent());
            try (FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8)) {
                writer.write(md.toString());
            }
        }

        return md.toString();
    }

    /**
     * 导出所有API文档
     */
    public static String exportAllDoc(List<ApiDefinition> apis, String outputFile) throws IOException {
        // Group by controller
        Map<String, List<ApiDefinition>> grouped = apis.stream()
                .collect(Collectors.groupingBy(
                        api -> api.getControllerName() != null ? api.getControllerName() : "未分类"));

        StringBuilder md = new StringBuilder();
        md.append("# API 接口文档\n\n");
        md.append("> 自动生成 by Acai API Helper v3.0.0  \n");
        md.append("> 接口总数: **").append(apis.size()).append("** 个  \n");
        md.append("> 生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");

        // Table of contents
        md.append("## 目录\n\n");
        int idx = 1;
        for (String controller : grouped.keySet()) {
            md.append(idx++).append(". [").append(controller).append("](#")
              .append(controller.toLowerCase().replaceAll("\\s+", "-")).append(")\n");
        }
        md.append("\n---\n\n");

        // Content
        for (Map.Entry<String, List<ApiDefinition>> entry : grouped.entrySet()) {
            md.append("## ").append(entry.getKey()).append("\n\n");
            for (ApiDefinition api : entry.getValue()) {
                appendApiDoc(md, api);
            }
        }

        if (outputFile != null) {
            Path path = Paths.get(outputFile);
            Files.createDirectories(path.getParent());
            try (FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8)) {
                writer.write(md.toString());
            }
        }

        return md.toString();
    }

    private static void appendApiDoc(StringBuilder md, ApiDefinition api) {
        String method = api.getHttpMethod().toUpperCase();
        String methodBadgeColor = getMethodBadgeColor(method);

        md.append("### `").append(method).append("` ").append(api.getUrl()).append("\n\n");

        // Name / description
        if (api.getName() != null && !api.getName().isBlank() && !api.getName().equals(api.getUrl())) {
            md.append("**").append(escapeMd(api.getName())).append("**\n\n");
        }
        if (api.getDescription() != null && !api.getDescription().isBlank()) {
            md.append(escapeMd(api.getDescription())).append("\n\n");
        }

        if (api.isDeprecated()) {
            md.append("> ⚠️ **此接口已废弃**\n\n");
        }

        // Path parameters
        List<ApiParameter> pathParams = api.pathParameters();
        if (!pathParams.isEmpty()) {
            md.append("**路径参数**\n\n");
            md.append("| 参数名 | 类型 | 必填 | 说明 |\n");
            md.append("|--------|------|------|------|\n");
            for (ApiParameter p : pathParams) {
                md.append("| `").append(p.getName()).append("` | ").append(p.getType())
                  .append(" | ").append(p.isRequired() ? "是" : "否")
                  .append(" | ").append(escapeMd(p.getDescription())).append(" |\n");
            }
            md.append("\n");
        }

        // Query parameters
        List<ApiParameter> queryParams = api.queryParameters();
        if (!queryParams.isEmpty()) {
            md.append("**查询参数**\n\n");
            md.append("| 参数名 | 类型 | 必填 | 默认值 | 说明 |\n");
            md.append("|--------|------|------|--------|------|\n");
            for (ApiParameter p : queryParams) {
                md.append("| `").append(p.getName()).append("` | ").append(p.getType())
                  .append(" | ").append(p.isRequired() ? "是" : "否")
                  .append(" | ").append(escapeMd(p.getDefaultValue()))
                  .append(" | ").append(escapeMd(p.getDescription())).append(" |\n");
            }
            md.append("\n");
        }

        // Request body
        List<ApiParameter> bodyParams = api.bodyParameters();
        if (!bodyParams.isEmpty()) {
            md.append("**请求体** (`").append(api.getConsumes()).append("`)\n\n");
            md.append("| 字段 | 类型 | 必填 | 说明 |\n");
            md.append("|------|------|------|------|\n");
            appendBodyParamsTable(md, bodyParams, "");
            md.append("\n");

            // Request example
            md.append("请求示例:\n");
            md.append("```json\n");
            md.append(generateJsonExample(api)).append("\n");
            md.append("```\n\n");
        }

        // Header parameters
        List<ApiParameter> headerParams = api.headerParameters();
        if (!headerParams.isEmpty()) {
            md.append("**请求头**\n\n");
            md.append("| Header名 | 必填 | 说明 |\n");
            md.append("|----------|------|------|\n");
            for (ApiParameter p : headerParams) {
                md.append("| `").append(p.getName()).append("` | ")
                  .append(p.isRequired() ? "是" : "否")
                  .append(" | ").append(escapeMd(p.getDescription())).append(" |\n");
            }
            md.append("\n");
        }

        // Response
        md.append("**响应** (`").append(api.getProduces()).append("`)\n\n");
        if (api.getResponseBodyType() != null && !api.getResponseBodyType().isBlank()) {
            md.append("- 返回类型: `").append(api.getResponseBodyType()).append("`\n");
        }
        md.append("- 成功状态码: 200\n\n");

        md.append("---\n\n");
    }

    private static void appendBodyParamsTable(StringBuilder md, List<ApiParameter> params, String prefix) {
        for (ApiParameter p : params) {
            String name = prefix.isEmpty() ? p.getName() : prefix + "." + p.getName();
            md.append("| `").append(name).append("` | ").append(p.getType())
              .append(" | ").append(p.isRequired() ? "是" : "否")
              .append(" | ").append(escapeMd(p.getDescription()));
            if (!p.getDefaultValue().isBlank()) {
                md.append(" (默认: `").append(escapeMd(p.getDefaultValue())).append("`)");
            }
            if (!p.getExample().isBlank()) {
                md.append(" 例: `").append(escapeMd(p.getExample())).append("`");
            }
            md.append(" |\n");
            if (p.getChildren() != null && !p.getChildren().isEmpty()) {
                appendBodyParamsTable(md, p.getChildren(), name);
            }
        }
    }

    private static String generateJsonExample(ApiDefinition api) {
        StringBuilder sb = new StringBuilder("{");
        List<ApiParameter> bodyParams = api.bodyParameters();
        boolean first = true;
        for (ApiParameter p : bodyParams) {
            if (!first) sb.append(",");
            sb.append("\n  \"").append(p.getName()).append("\": ").append(getJsonExampleValue(p));
            first = false;
        }
        if (!first) sb.append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String getJsonExampleValue(ApiParameter p) {
        String type = p.getType().toLowerCase();
        if (!p.getChildren().isEmpty()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (ApiParameter child : p.getChildren()) {
                if (!first) sb.append(",");
                sb.append("\n    \"").append(child.getName()).append("\": ").append(getJsonExampleValue(child));
                first = false;
            }
            if (!first) sb.append("\n  ");
            sb.append("}");
            return sb.toString();
        }
        if (type.contains("int") || type.contains("long") || type.contains("double")
                || type.contains("float") || type.contains("bigdecimal")) {
            return "1";
        }
        if (type.contains("boolean")) {
            return "true";
        }
        if (type.contains("list") || type.contains("array")) {
            return "[]";
        }
        return "\"" + p.generateDefaultValue().replace("\"", "\\\"") + "\"";
    }

    private static String getMethodBadgeColor(String method) {
        return switch (method) {
            case "GET" -> "#2E7D32";
            case "POST" -> "#1565C0";
            case "PUT" -> "#ED6C02";
            case "DELETE" -> "#C62828";
            case "PATCH" -> "#6B21A8";
            default -> "#757575";
        };
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
