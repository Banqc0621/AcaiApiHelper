package com.hronline.util;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.model.RequestHistory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
        md.append("> 自动生成 by RestAutoLab v1.0.3  \n");
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
        md.append("> 自动生成 by RestAutoLab v1.0.3  \n");
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

    /**
     * 导出选中接口的 Markdown 文档（含真实测试数据）。
     *
     * <p>在每个接口的标准文档之后，追加"最近一次真实测试"的请求头、请求体、
     * 响应状态码和响应体，便于离线回溯测试结果。该文档主要供人阅读，
     * <b>不能</b>被 Postman/Apifox 当作接口源导入（两者只认 JSON 格式）。</p>
     *
     * @param apis    选中的接口列表
     * @param history 请求历史列表（可为null，null 时不附测试数据）
     * @param outputFile 输出文件路径，为null时仅返回字符串
     * @return Markdown 全文
     */
    public static String exportSelectedApisWithHistory(List<ApiDefinition> apis,
                                                       List<RequestHistory> history,
                                                       String projectName,
                                                       String outputFile) throws IOException {
        Map<String, List<ApiDefinition>> grouped = apis.stream()
                .collect(Collectors.groupingBy(
                        api -> api.getControllerName() != null && !api.getControllerName().isBlank()
                                ? api.getControllerName() : "未分类"));

        StringBuilder md = new StringBuilder();
        md.append("# API 文档\n\n");
        md.append("> 项目名称：").append(projectName == null || projectName.isBlank() ? "未命名" : projectName).append("\n\n");
        md.append("---\n\n");

        for (Map.Entry<String, List<ApiDefinition>> entry : grouped.entrySet()) {
            for (ApiDefinition api : entry.getValue()) {
                appendApiDoc(md, api);
                appendTestHistory(md, api, history);
            }
        }

        if (outputFile != null) {
            Path path = Paths.get(outputFile);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8)) {
                writer.write(md.toString());
            }
        }
        return md.toString();
    }

    /** 在接口文档后追加最近一次真实测试数据（默认展开 details，便于阅读） */
    private static void appendTestHistory(StringBuilder md, ApiDefinition api, List<RequestHistory> history) {
        RequestHistory latest = findLatestHistory(api, history);
        if (latest == null) {
            // 没有测试记录：响应参数/示例段已基于返回类型推断展示
            md.append("> ℹ️ 该接口暂无实际测试记录，响应示例按返回类型推断生成。\n\n");
            return;
        }

        md.append("<details open>\n<summary>📝 最近测试记录 (")
          .append(latest.timeDisplay()).append(")</summary>\n\n");
        md.append("- 请求方法: `").append(latest.getMethod()).append("`\n");
        md.append("- 请求URL: `").append(escapeMd(latest.getUrl())).append("`\n");
        md.append("- 状态码: **").append(latest.getStatusCode())
          .append("**  耗时: ").append(latest.getDurationMs()).append("ms\n");

        if (latest.getHeaders() != null && !latest.getHeaders().isEmpty()) {
            md.append("\n**请求头**\n\n");
            for (Map.Entry<String, String> e : latest.getHeaders().entrySet()) {
                md.append("- `").append(escapeMd(e.getKey())).append("`: ")
                  .append(escapeMd(e.getValue())).append("\n");
            }
        }
        if (latest.getRequestBody() != null && !latest.getRequestBody().isBlank()) {
            md.append("\n**请求体**\n\n```json\n")
              .append(latest.getRequestBody()).append("\n```\n");
        } else {
            md.append("\n**请求体**\n\n```\n(无请求体)\n```\n");
        }
        if (latest.getResponseBody() != null && !latest.getResponseBody().isBlank()) {
            md.append("\n**响应体**\n\n```json\n")
              .append(latest.getResponseBody()).append("\n```\n");
        } else {
            md.append("\n**响应体**\n\n```\n(无响应体)\n```\n");
        }
        md.append("\n\n---\n\n");
    }

    /**
     * 按类型名合成一个示例 JSON 字符串
     */
    static String synthesizeExampleForType(String retType) {
        if (retType == null || retType.isBlank()) {
            return "{\n  // 尚未配置返回类型\n}";
        }
        String t = retType.trim();
        String low = t.toLowerCase();

        // 基础类型
        if (low.equals("string")) return "\"\"";
        if (low.equals("void")) return "null";
        if (low.equals("int") || low.equals("integer")
                || low.equals("long") || low.equals("short")
                || low.equals("byte") || low.equals("number")) return "0";
        if (low.equals("double") || low.equals("float") || low.equals("bigdecimal")) return "0.0";
        if (low.equals("boolean")) return "false";
        if (low.equals("date") || low.endsWith("localdatetime")
                || low.endsWith("localdate") || low.endsWith("localtime")) return "\"2024-01-01 00:00:00\"";

        // 集合
        if (low.startsWith("list<") || low.startsWith("java.util.list<")
                || low.startsWith("collection<") || low.startsWith("set<")
                || low.endsWith("[]") || low.startsWith("arraylist<")) {
            return "[]";
        }
        if (low.startsWith("map<") || low.startsWith("java.util.map<")) {
            return "{}";
        }
        if (low.startsWith("page<") || low.startsWith("ipage<")
                || low.startsWith("com.baomidou.mybatisplus.core.metadataipage<")) {
            return "{\n  \"total\": 0,\n  \"records\": [],\n  \"size\": 10,\n  \"current\": 1\n}";
        }

        // 标准 Result<T> 包装（只在外层是 Result<...> 时才包，领域对象不要再包第二层）
        if (low.startsWith("result<") || low.startsWith("com.ban.acai.")) {
            String inner = extractGenericInner(t);
            String innerExample = synthesizeExampleForType(inner);
            return "{\n  \"code\": 0,\n  \"message\": \"success\",\n  \"data\": " + innerExample + "\n}";
        }

        // 其它领域对象（DTO/VO/Entity 等）→ 直接给一个空对象占位
        return "{}";
    }

    /** 提取泛型 <T> 里的 T；如 "Result<UserDTO>" → "UserDTO"，无泛型返回 Object */
    private static String extractGenericInner(String type) {
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String inner = type.substring(lt + 1, gt);
            // 处理 List<Foo> 之类
            if (inner.contains("<")) {
                return inner; // 保留嵌套
            }
            return inner;
        }
        return "Object";
    }

    /**
     * 在历史列表中查找该接口最近一次测试记录。
     * <p>多策略匹配（按优先级）：</p>
     * <ol>
     *   <li><b>apiKey 精确匹配</b>（最稳）</li>
     *   <li>URL 归一化后按段对比（处理 {id} 占位符 vs 真实值）</li>
     *   <li>URL 后缀/包含匹配（兜底）</li>
     * </ol>
     */
    private static RequestHistory findLatestHistory(ApiDefinition api, List<RequestHistory> history) {
        if (history == null || history.isEmpty()) return null;
        String apiKey = api.uniqueKey();         // "GET|/api/users/{id}"
        String apiPath = api.getUrl();           // "/api/users/{id}"
        String method = api.getHttpMethod() == null ? "" : api.getHttpMethod().toUpperCase();
        String normApiPath = normalizePath(apiPath);

        RequestHistory latest = null;

        // 第 1 优先级：apiKey 精确匹配
        for (RequestHistory h : history) {
            if (!methodMatch(h.getMethod(), method)) continue;
            if (h.getApiKey() != null && h.getApiKey().equals(apiKey)) {
                if (latest == null || h.getTimestamp() > latest.getTimestamp()) latest = h;
            }
        }
        if (latest != null) return latest;

        // 第 2 优先级：归一化路径段匹配
        for (RequestHistory h : history) {
            if (!methodMatch(h.getMethod(), method)) continue;
            String hUrl = h.getUrl();
            if (hUrl == null) continue;
            String normHPath = normalizePath(hUrl);
            if (normHPath.equals(normApiPath) || pathSegmentsMatch(normHPath, normApiPath)) {
                if (latest == null || h.getTimestamp() > latest.getTimestamp()) latest = h;
            }
        }
        if (latest != null) return latest;

        // 第 3 优先级：URL 后缀/包含（兜底）
        for (RequestHistory h : history) {
            if (!methodMatch(h.getMethod(), method)) continue;
            String hUrl = h.getUrl();
            if (hUrl == null) continue;
            if (hUrl.endsWith(apiPath) || hUrl.contains(apiPath)
                    || (apiPath.contains("{") && pathMatches(hUrl, apiPath))) {
                if (latest == null || h.getTimestamp() > latest.getTimestamp()) latest = h;
            }
        }
        return latest;
    }

    private static boolean methodMatch(String h, String m) {
        if (h == null || m == null || m.isEmpty()) return true; // 历史方法为空则不限制
        return h.equalsIgnoreCase(m);
    }

    /**
     * URL 归一化：去掉协议/域名/端口/查询参数/花括号占位符
     * <p>例：<code>http://localhost:8080/api/users/{id}?x=1</code> → <code>/api/users</code></p>
     */
    private static String normalizePath(String url) {
        if (url == null) return "";
        // 去掉 query
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        // 去掉 fragment
        int h = url.indexOf('#');
        if (h >= 0) url = url.substring(0, h);
        // 去掉协议+域名
        int proto = url.indexOf("://");
        if (proto >= 0) {
            int slash = url.indexOf('/', proto + 3);
            if (slash >= 0) url = url.substring(slash);
            else url = "/";
        }
        // 归一化：把 /v1/users/{id} 和 /v1/users/123 视为相同路径
        StringBuilder sb = new StringBuilder();
        String[] segs = url.split("/");
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            // 跳过纯数字段（路径变量值）
            if (seg.matches("\\{[^{}]+\\}") || seg.matches("\\d+")) continue;
            sb.append('/').append(seg);
        }
        if (sb.length() == 0) sb.append('/');
        return sb.toString();
    }

    private static boolean pathSegmentsMatch(String a, String b) {
        if (a.equals(b)) return true;
        String[] aSegs = a.split("/");
        String[] bSegs = b.split("/");
        if (aSegs.length != bSegs.length) return false;
        for (int i = 0; i < aSegs.length; i++) {
            if (aSegs[i].equals(bSegs[i])) continue;
            // 允许 {id} 占位符
            if (aSegs[i].matches("\\{[^{}]+\\}") || bSegs[i].matches("\\{[^{}]+\\}")) continue;
            return false;
        }
        return true;
    }

    private static boolean pathMatches(String fullUrl, String apiPath) {
        String[] apiSegs = apiPath.split("/");
        String[] urlSegs = fullUrl.split("[?]");
        String[] pathSegs = urlSegs[0].split("/");
        if (apiSegs.length != pathSegs.length) return false;
        for (int i = 0; i < apiSegs.length; i++) {
            String a = apiSegs[i];
            if (a.startsWith("{") && a.endsWith("}")) continue;
            if (!a.equals(pathSegs[i])) return false;
        }
        return true;
    }

    private static void appendApiDoc(StringBuilder md, ApiDefinition api) {
        String method = api.getHttpMethod() == null ? "GET" : api.getHttpMethod().toUpperCase();
        String name = api.getName() == null || api.getName().isBlank() ? "" : api.getName();
        String url = api.getUrl() == null ? "" : api.getUrl();

        // ## `/collection/collectionInfo` （标题：仅 URL，不含方法名和接口名）
        md.append("## `").append(url).append("`\n\n");

        // 基本信息：<name> （接口名展示在基本信息前一行）
        if (!name.isEmpty() && !name.equals(url)) {
            md.append("基本信息：").append(escapeMd(name)).append("\n\n");
        } else {
            md.append("基本信息：").append(escapeMd(url)).append("\n\n");
        }

        // 基本信息表
        md.append("| 项目 | 内容 |\n");
        md.append("|------|------|\n");
        md.append("| 请求方式 | ").append(method).append(" |\n");
        md.append("| 请求地址 | `").append(url).append("` |\n");
        String contentType = api.getConsumes();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/json";
        }
        md.append("| Content-Type | ").append(contentType).append(" |\n");
        String retType = api.getResponseBodyType();
        md.append("| 返回类型 | ").append(retType == null || retType.isBlank() ? "void" : retType).append(" |\n\n");

        // === 请求参数（按段：路径参数 / 查询参数 / Body）===
        List<ApiParameter> pathParams = api.pathParameters();
        List<ApiParameter> queryParams = api.queryParameters();
        List<ApiParameter> bodyParams = api.bodyParameters();
        List<ApiParameter> headerParams = api.headerParameters();

        if (!pathParams.isEmpty() || !queryParams.isEmpty() || !bodyParams.isEmpty() || !headerParams.isEmpty()) {
            md.append("请求参数\n\n");

            // Path
            if (!pathParams.isEmpty()) {
                md.append("Path\n\n");
                md.append("| 参数 | 类型 | 必填 | 说明 |\n");
                md.append("|------|------|:---:|------|\n");
                for (ApiParameter p : pathParams) {
                    appendParamRow(md, p.getName(), p.getType(), p.isRequired(), p.getDescription(), null);
                }
                md.append("\n");
            }

            // Query
            if (!queryParams.isEmpty()) {
                md.append("Query\n\n");
                md.append("| 参数 | 类型 | 必填 | 说明 |\n");
                md.append("|------|------|:---:|------|\n");
                for (ApiParameter p : queryParams) {
                    appendParamRow(md, p.getName(), p.getType(), p.isRequired(), p.getDescription(), p.getDefaultValue());
                }
                md.append("\n");
            }

            // Body
            if (!bodyParams.isEmpty()) {
                md.append("Body（").append(contentType).append("）\n\n");
                md.append("| 参数 | 类型 | 必填 | 说明 |\n");
                md.append("|------|------|:---:|------|\n");
                for (ApiParameter p : bodyParams) {
                    appendParamRow(md, p.getName(), p.getType(), p.isRequired(), p.getDescription(), p.getDefaultValue());
                }
                md.append("\n");
            }

            // Header
            if (!headerParams.isEmpty()) {
                md.append("Header\n\n");
                md.append("| 参数 | 必填 | 说明 |\n");
                md.append("|------|:---:|------|\n");
                for (ApiParameter p : headerParams) {
                    md.append("| `").append(p.getName()).append("` | ")
                      .append(p.isRequired() ? "✓" : "").append(" | ")
                      .append(escapeMd(p.getDescription())).append(" |\n");
                }
                md.append("\n");
            }

            // 请求示例
            md.append("请求示例\n\n");
            md.append("```json\n");
            md.append(buildRequestExample(api, bodyParams, queryParams, pathParams));
            md.append("\n```\n\n");
        }

        // === 响应参数 ===
        md.append("响应参数\n\n");
        if (retType == null || retType.isBlank() || retType.equalsIgnoreCase("void")) {
            md.append("> 接口无返回体\n\n");
            md.append("响应示例\n\n```json\nnull\n```\n\n");
        } else {
            md.append("| 字段 | 类型 | 说明 |\n");
            md.append("|------|------|------|\n");

            // 优先用扫描器解析出的真实字段树（responseSchema）
            List<ApiParameter> schema = api.getResponseSchema();
            boolean hasSchema = schema != null && !schema.isEmpty();
            if (hasSchema) {
                appendResponseFieldRowsFromSchema(md, schema, "");
            } else {
                appendResponseParamRows(md, retType, 0);
            }
            md.append("\n");

            // 响应示例
            md.append("响应示例\n\n");
            md.append("```json\n");
            md.append(hasSchema
                    ? synthesizeExampleFromSchema(retType, schema)
                    : synthesizeExampleForType(retType));
            md.append("\n```\n\n");
        }

        md.append("---\n\n");
    }

    /**
     * 把扫描器返回的字段树渲染为响应参数表行（递归）。
     *
     * @param md     MD 文本缓冲
     * @param fields 字段列表
     * @param prefix 字段名前缀（嵌套时累积，如 "data." / "data.user."）
     */
    private static void appendResponseFieldRowsFromSchema(StringBuilder md, List<ApiParameter> fields, String prefix) {
        if (fields == null) return;
        for (ApiParameter f : fields) {
            String name = prefix + f.getName();
            String type = f.getType() == null ? "" : f.getType();
            String desc = f.getDescription() == null ? "" : f.getDescription();
            md.append("| `").append(name).append("` | ").append(type)
              .append(" | ").append(escapeMd(desc)).append(" |\n");
            if (f.getChildren() != null && !f.getChildren().isEmpty()) {
                appendResponseFieldRowsFromSchema(md, f.getChildren(), name + ".");
            }
        }
    }

    /**
     * 基于真实字段树 + 返回类型，合成响应示例 JSON。
     * <p>对 Result/Page/List 这类包装类型，自动加 code/message/data 三层结构；</p>
     * <p>嵌套对象按字段类型递归生成；List 字段生成一个元素的数组示例。</p>
     */
    static String synthesizeExampleFromSchema(String retType, List<ApiParameter> fields) {
        if (fields == null || fields.isEmpty()) {
            return synthesizeExampleForType(retType);
        }
        StringBuilder sb = new StringBuilder();
        appendExampleForTypeAndFields(sb, retType, fields, 1);
        return sb.toString();
    }

    /**
     * 根据返回类型，决定最外层 JSON 形状。
     * <ul>
     *   <li>Result<T> / R<T> 等通用包装 → { code, message, data: T示例 }</li>
     *   <li>List<T> / Collection<T> / Set<T> → [T示例]</li>
     *   <li>Page<T> / IPage<T> → { total, records:[T示例], size, current }</li>
     *   <li>基础类型 → 字面量</li>
     *   <li>其它 → 字段树对应的对象</li>
     * </ul>
     */
    private static void appendExampleForTypeAndFields(StringBuilder sb, String retType,
                                                      List<ApiParameter> fields, int depth) {
        if (depth > 6) {
            sb.append("{}");
            return;
        }
        String t = retType == null ? "" : retType.trim();
        String low = t.toLowerCase();
        // void
        if (low.equals("void")) { sb.append("null"); return; }
        // 基础
        if (isPrimitiveType(t)) {
            if (low.equals("string")) sb.append("\"\"");
            else if (low.equals("boolean")) sb.append("false");
            else if (low.equals("bigdecimal") || low.equals("double") || low.equals("float")) sb.append("0.0");
            else sb.append("0");
            return;
        }
        // 集合
        if (low.startsWith("list<") || low.startsWith("java.util.list<")
                || low.startsWith("collection<") || low.startsWith("set<")
                || low.startsWith("arraylist<") || low.endsWith("[]")) {
            sb.append("[\n");
            appendObjectFromFields(sb, fields, 2);
            sb.append("\n]");
            return;
        }
        // Page
        if (low.startsWith("page<") || low.startsWith("ipage<")
                || low.startsWith("com.baomidou.mybatisplus.core.metadataipage<")) {
            sb.append("{\n");
            sb.append("  \"total\": 0,\n");
            sb.append("  \"size\": 10,\n");
            sb.append("  \"current\": 1,\n");
            sb.append("  \"records\": [\n");
            appendObjectFromFields(sb, fields, 3);
            sb.append("\n  ]\n}");
            return;
        }
        // 通用包装
        if (isCommonWrapperName(extractSimpleName(t))) {
            String innerType = extractGenericInner(t);
            sb.append("{\n");
            sb.append("  \"code\": 0,\n");
            sb.append("  \"message\": \"success\",\n");
            sb.append("  \"data\": ");
            // data 自身再递归
            String innerLow = innerType.toLowerCase();
            if (isPrimitiveType(innerType)) {
                if (innerLow.equals("string")) sb.append("\"\"");
                else if (innerLow.equals("boolean")) sb.append("false");
                else if (innerLow.equals("bigdecimal") || innerLow.equals("double") || innerLow.equals("float")) sb.append("0.0");
                else sb.append("0");
            } else if (innerLow.startsWith("list<") || innerLow.startsWith("java.util.list<")
                    || innerLow.endsWith("[]")) {
                sb.append("[\n");
                appendObjectFromFields(sb, fields, 3);
                sb.append("\n  ]");
            } else if (innerLow.startsWith("page<") || innerLow.startsWith("ipage<")) {
                sb.append("{\n");
                sb.append("    \"total\": 0,\n");
                sb.append("    \"size\": 10,\n");
                sb.append("    \"current\": 1,\n");
                sb.append("    \"records\": [\n");
                appendObjectFromFields(sb, fields, 4);
                sb.append("\n    ]\n  }");
            } else {
                appendObjectFromFields(sb, fields, 3);
            }
            sb.append("\n}");
            return;
        }
        // 其它领域对象
        appendObjectFromFields(sb, fields, depth);
    }

    private static void appendObjectFromFields(StringBuilder sb, List<ApiParameter> fields, int indentLevel) {
        if (fields == null || fields.isEmpty()) {
            sb.append("{}");
            return;
        }
        String indent = repeatStr("  ", indentLevel);
        String childIndent = repeatStr("  ", indentLevel + 1);
        sb.append("{\n");
        int count = 0;
        for (ApiParameter f : fields) {
            if (count > 0) sb.append(",\n");
            sb.append(childIndent).append("\"").append(f.getName()).append("\": ");
            if (f.getChildren() != null && !f.getChildren().isEmpty()) {
                appendObjectFromFields(sb, f.getChildren(), indentLevel + 1);
            } else {
                String t = f.getType() == null ? "" : f.getType();
                String low = t.toLowerCase();
                if (low.equals("string") || low.endsWith("char") || low.endsWith("string")) {
                    sb.append("\"\"");
                } else if (low.equals("boolean")) {
                    sb.append("false");
                } else if (low.equals("bigdecimal") || low.equals("double") || low.equals("float")) {
                    sb.append("0.0");
                } else if (low.equals("int") || low.equals("integer")
                        || low.equals("long") || low.equals("short") || low.equals("byte")) {
                    sb.append("0");
                } else if (low.startsWith("list<") || low.startsWith("java.util.list<")
                        || low.startsWith("collection<") || low.startsWith("set<")
                        || low.endsWith("[]")) {
                    sb.append("[]");
                } else if (low.startsWith("map<") || low.startsWith("java.util.map<")) {
                    sb.append("{}");
                } else if (low.endsWith("date") || low.endsWith("localdatetime")
                        || low.endsWith("localdate") || low.endsWith("localtime")) {
                    sb.append("\"2024-01-01 00:00:00\"");
                } else if (!f.getExample().isBlank()) {
                    String ex = f.getExample();
                    if (ex.matches("-?\\d+(\\.\\d+)?")) sb.append(ex);
                    else sb.append("\"").append(escapeMd(ex)).append("\"");
                } else {
                    // 未知类型对象，给个空对象占位
                    sb.append("{}");
                }
            }
            count++;
        }
        sb.append("\n").append(indent).append("}");
    }

    private static String repeatStr(String s, int n) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < n; i++) r.append(s);
        return r.toString();
    }

    private static String extractSimpleName(String type) {
        if (type == null) return "";
        int lt = type.indexOf('<');
        String head = lt >= 0 ? type.substring(0, lt) : type;
        int dot = head.lastIndexOf('.');
        return dot >= 0 ? head.substring(dot + 1) : head;
    }

    private static boolean isCommonWrapperName(String simpleName) {
        if (simpleName == null) return false;
        return simpleName.equals("Result") || simpleName.equals("R")
                || simpleName.equals("CommonResult") || simpleName.equals("ApiResult")
                || simpleName.equals("BaseResult") || simpleName.equals("ResponseResult")
                || simpleName.equals("Response") || simpleName.equals("Resp")
                || simpleName.equals("RespResult");
    }

    /** 输出一行参数表。required=true 显示 ✓，否则空字符串。 */
    private static void appendParamRow(StringBuilder md, String name, String type, boolean required, String desc, String defaultVal) {
        md.append("| `").append(name == null ? "" : name).append("` | ")
          .append(type == null ? "" : type).append(" | ")
          .append(required ? "✓" : "").append(" | ")
          .append(escapeMd(desc == null ? "" : desc));
        if (defaultVal != null && !defaultVal.isBlank()) {
            md.append(" (默认: `").append(escapeMd(defaultVal)).append("`)");
        }
        md.append(" |\n");
    }

    /**
     * 递归输出响应参数表行。
     * <p>支持深度限制（maxDepth=3），避免对未知的嵌套对象无限展开。</p>
     */
    private static void appendResponseParamRows(StringBuilder md, String type, int depth) {
        if (depth > 3) {
            md.append("| `…` | ").append(escapeMd(type)).append(" | 嵌套对象 |\n");
            return;
        }
        String t = type == null ? "" : type.trim();
        if (t.isEmpty()) return;
        if (isPrimitiveType(t)) {
            md.append("| - | ").append(t).append(" | 原始返回值 |\n");
            return;
        }
        // 集合：data 字段
        if (t.startsWith("List<") || t.startsWith("Collection<") || t.startsWith("Set<") || t.endsWith("[]")) {
            String inner = t.startsWith("List<") || t.startsWith("Collection<") || t.startsWith("Set<")
                    ? extractGenericInner(t) : t.substring(0, t.length() - 2);
            md.append("| `data` | List<").append(inner).append("> | 返回数据 |\n");
            if (!isPrimitiveType(inner)) {
                md.append("| `data[].id` | Long | 列表项 ID |\n");
                appendResponseParamRows(md, inner, depth + 1);
            }
            return;
        }
        if (t.startsWith("Map<") || t.startsWith("java.util.Map<")) {
            md.append("| `data` | Object | 键值对集合 |\n");
            return;
        }
        if (t.startsWith("Page<") || t.startsWith("IPage<")) {
            String inner = extractGenericInner(t);
            md.append("| `data.total` | Long | 总条数 |\n");
            md.append("| `data.size` | Long | 页大小 |\n");
            md.append("| `data.current` | Long | 当前页 |\n");
            md.append("| `data.records` | List<").append(inner).append("> | 数据列表 |\n");
            return;
        }
        // 通用 Result<T> 包装或领域对象
        if (t.startsWith("Result<") || t.endsWith("dto") || t.endsWith("DTO")
                || t.endsWith("vo") || t.endsWith("VO")
                || t.endsWith("entity") || t.endsWith("Entity")) {
            String inner = t.startsWith("Result<") ? extractGenericInner(t) : t;
            if (inner.equals("Object") || inner.isEmpty()) {
                md.append("| `code` | Integer | 状态码，0 表示成功 |\n");
                md.append("| `message` | String | 返回信息 |\n");
                md.append("| `data` | Object | 返回数据 |\n");
            } else if (isPrimitiveType(inner)) {
                md.append("| `code` | Integer | 状态码，0 表示成功 |\n");
                md.append("| `message` | String | 返回信息 |\n");
                md.append("| `data` | ").append(inner).append(" | 返回数据 |\n");
            } else if (inner.startsWith("List<") || inner.startsWith("Collection<") || inner.endsWith("[]")) {
                String e = inner.startsWith("List<") ? extractGenericInner(inner) : inner.substring(0, inner.length() - 2);
                md.append("| `code` | Integer | 状态码，0 表示成功 |\n");
                md.append("| `message` | String | 返回信息 |\n");
                md.append("| `data` | List<").append(e).append("> | 返回数据 |\n");
            } else {
                md.append("| `code` | Integer | 状态码，0 表示成功 |\n");
                md.append("| `message` | String | 返回信息 |\n");
                md.append("| `data` | ").append(inner).append(" | 返回数据 |\n");
                // 给几个常见字段提示
                if (inner.endsWith("User") || inner.endsWith("UserDTO") || inner.endsWith("UserVO")) {
                    md.append("| `data.id` | Long | 主键 |\n");
                    md.append("| `data.name` | String | 名称 |\n");
                }
            }
            return;
        }
        // 兜底
        md.append("| - | ").append(escapeMd(t)).append(" | 复杂对象（字段未在扫描器中识别） |\n");
    }

    private static boolean isPrimitiveType(String t) {
        if (t == null) return false;
        String low = t.toLowerCase();
        return low.equals("string") || low.equals("integer") || low.equals("int")
                || low.equals("long") || low.equals("short") || low.equals("byte")
                || low.equals("double") || low.equals("float") || low.equals("boolean")
                || low.equals("bigdecimal") || low.equals("date")
                || low.endsWith("localdatetime") || low.endsWith("localdate")
                || low.endsWith("localtime") || low.equals("number") || low.equals("object");
    }

    /** 构造请求示例 JSON（含 path/query/body） */
    private static String buildRequestExample(ApiDefinition api, List<ApiParameter> body,
                                              List<ApiParameter> query, List<ApiParameter> path) {
        if (body == null || body.isEmpty()) {
            if (query == null || query.isEmpty()) {
                return "{\n  // GET 请求无 Body，参数已在 URL 中\n}";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("// Query 参数示例：").append(api.getUrl()).append("?");
            boolean first = true;
            for (ApiParameter p : query) {
                if (!first) sb.append("&");
                sb.append(p.getName()).append("=").append(getExampleValueForType(p));
                first = false;
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (ApiParameter p : body) {
            if (!first) sb.append(",");
            sb.append("\n  \"").append(p.getName()).append("\": ").append(getExampleValueForType(p));
            first = false;
        }
        if (!first) sb.append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String getExampleValueForType(ApiParameter p) {
        if (p.getExample() != null && !p.getExample().isBlank()) {
            // 简单判断：示例是不是数字
            String ex = p.getExample();
            if (ex.matches("-?\\d+(\\.\\d+)?")) return ex;
            return "\"" + ex + "\"";
        }
        String t = p.getType() == null ? "" : p.getType().toLowerCase();
        if (t.equals("string")) return "\"\"";
        if (t.equals("integer") || t.equals("int") || t.equals("long")
                || t.equals("short") || t.equals("byte")) return "0";
        if (t.equals("double") || t.equals("float") || t.equals("bigdecimal")) return "0.0";
        if (t.equals("boolean")) return "false";
        return "null";
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

    // ================================================================
    // Markdown 导入
    // ================================================================

    /**
     * 从 Markdown 文档导入 API 定义列表。
     *
     * 兼容本插件 {@link #exportAllDoc} / {@link #exportControllerDoc} 导出的格式，
     * 也尽量兼容结构相似的手写接口文档。导入的接口 source 标记为 MANUAL，
     * 解析失败的部分会被跳过而不抛异常。
     *
     * @param markdown Markdown 全文
     * @return 解析出的 API 定义列表
     */
    public static List<ApiDefinition> importFromMarkdown(String markdown) {
        List<ApiDefinition> apis = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return apis;

        String[] lines = markdown.split("\n", -1);
        ApiDefinition current = null;
        String currentController = "导入的接口";
        String section = null;       // PATH / QUERY / BODY / HEADER / RESPONSE
        boolean inCodeBlock = false;
        boolean nameParsed = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 代码块开关：块内内容一律跳过
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) continue;

            // Controller 标题：## xxx（排除 ###）
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                currentController = stripHash(line);
                continue;
            }
            // 单 controller 文档的顶层标题 # ControllerName
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                currentController = stripHash(line);
                continue;
            }

            // 接口标题：### `METHOD` url
            if (line.startsWith("### ")) {
                if (current != null) apis.add(current);
                current = parseApiHeading(line);
                if (current != null) {
                    current.setControllerName(currentController);
                    current.setSource(RestAutoLabConstants.API_SOURCE_MANUAL);
                    current.setScanTimestamp(System.currentTimeMillis());
                }
                section = null;
                nameParsed = false;
                continue;
            }

            if (current == null) continue;

            // 废弃标记
            if (line.contains("此接口已废弃")) {
                current.setDeprecated(true);
                continue;
            }

            // section 切换
            if (line.startsWith("**路径参数**")) { section = "PATH"; continue; }
            if (line.startsWith("**查询参数**")) { section = "QUERY"; continue; }
            if (line.startsWith("**请求体**")) {
                section = "BODY";
                String ct = extractParenBacktick(line);
                if (ct != null) current.setConsumes(ct);
                continue;
            }
            if (line.startsWith("**请求头**")) { section = "HEADER"; continue; }
            if (line.startsWith("**响应**")) {
                section = "RESPONSE";
                String ct = extractParenBacktick(line);
                if (ct != null) current.setProduces(ct);
                continue;
            }

            // 返回类型: - 返回类型: `UserVO`
            if (line.startsWith("- 返回类型")) {
                String t = extractBacktick(line);
                if (t != null) current.setResponseBodyType(t);
                continue;
            }
            // "请求示例:" 标记行，跳过
            if (line.startsWith("请求示例")) continue;

            // 接口名称：section 开始前，单独成行的 **xxx**（非分节标题）
            if (!nameParsed && line.startsWith("**") && line.endsWith("**") && !line.contains("```")) {
                current.setName(stripStars(line));
                nameParsed = true;
                continue;
            }

            // 描述行：section 开始前的普通文本
            if (section == null && (current.getDescription() == null || current.getDescription().isBlank())
                    && !line.startsWith("|") && !line.startsWith("#") && !line.startsWith(">")
                    && !line.startsWith("-")) {
                current.setDescription(unescapeMd(line));
                continue;
            }

            // 表格行
            if (line.startsWith("|") && section != null) {
                if (isTableSeparator(line)) continue;
                List<String> cells = parseTableRow(line);
                if (cells.isEmpty()) continue;
                switch (section) {
                    case "PATH" -> applyPathCell(current, cells);
                    case "QUERY" -> applyQueryCell(current, cells);
                    case "BODY" -> applyBodyCell(current, cells);
                    case "HEADER" -> applyHeaderCell(current, cells);
                    default -> { /* RESPONSE section 无表格需要解析为参数 */ }
                }
            }
        }
        if (current != null) apis.add(current);

        return apis;
    }

    /**
     * 从 Markdown 文件导入 API 定义列表。
     *
     * @param inputFile Markdown 文件绝对路径
     * @return 解析出的 API 定义列表
     */
    public static List<ApiDefinition> importFromFile(String inputFile) throws IOException {
        Path path = Paths.get(inputFile);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return importFromMarkdown(content);
    }

    // ── Markdown 导入辅助方法 ──

    /** 解析接口标题行 {@code ### `GET` /api/users/{id}} */
    private static ApiDefinition parseApiHeading(String heading) {
        String rest = heading.substring(4).trim(); // 去掉 "### "
        if (rest.isEmpty() || rest.charAt(0) != '`') return null;
        int btEnd = rest.indexOf('`', 1);
        if (btEnd < 0) return null;
        String method = rest.substring(1, btEnd).trim();
        String url = rest.substring(btEnd + 1).trim();
        if (url.isEmpty()) return null;
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(method.toUpperCase());
        api.setUrl(url);
        api.setName(url);
        return api;
    }

    /** 提取行中反引号包裹的内容，如 {@code - 返回类型: `UserVO`} -> UserVO */
    private static String extractBacktick(String line) {
        int start = line.indexOf('`');
        if (start < 0) return null;
        int end = line.indexOf('`', start + 1);
        if (end < 0) return null;
        return line.substring(start + 1, end);
    }

    /** 提取行中圆括号内反引号包裹的内容，如 {@code **请求体** (`application/json`)} -> application/json */
    private static String extractParenBacktick(String line) {
        int paren = line.indexOf('(');
        if (paren < 0) return null;
        return extractBacktick(line.substring(paren));
    }

    /** 去掉行首的 # 标记 */
    private static String stripHash(String line) {
        return line.replaceAll("^#+\\s*", "").trim();
    }

    /** 去掉行首行尾的 ** 标记 */
    private static String stripStars(String line) {
        return line.replaceAll("^\\*+|\\*+$", "").trim();
    }

    /** 反转义 Markdown 特殊字符 */
    private static String unescapeMd(String s) {
        if (s == null) return "";
        return s.replace("\\|", "|");
    }

    /** 判断表格分隔行 {@code |---|---|} */
    private static boolean isTableSeparator(String line) {
        String body = line.replaceAll("^\\|", "").replaceAll("\\|$", "");
        return body.matches("[\\s:|-]+");
    }

    /** 解析表格行为单元格列表 */
    private static List<String> parseTableRow(String line) {
        String body = line;
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : body.split("\\|", -1)) {
            cells.add(c.trim());
        }
        return cells;
    }

    /** 去掉单元格中的反引号包裹 */
    private static String cleanCell(String cell) {
        if (cell == null) return "";
        return cell.replace("`", "").trim();
    }

    private static void applyPathCell(ApiDefinition api, List<String> cells) {
        // | 参数名 | 类型 | 必填 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        p.setName(cleanCell(cells.get(0)));
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        p.setDescription(unescapeMd(cells.get(3)));
        p.setLocation(ParameterLocation.PATH);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyQueryCell(ApiDefinition api, List<String> cells) {
        // | 参数名 | 类型 | 必填 | 默认值 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        p.setName(cleanCell(cells.get(0)));
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        if (cells.size() >= 5) {
            p.setDefaultValue(cleanCell(cells.get(3)));
            p.setDescription(unescapeMd(cells.get(4)));
        } else {
            p.setDescription(unescapeMd(cells.get(3)));
        }
        p.setLocation(ParameterLocation.QUERY);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyBodyCell(ApiDefinition api, List<String> cells) {
        // | 字段 | 类型 | 必填 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        String fullName = cleanCell(cells.get(0));
        // 嵌套字段 parent.child -> 取最后一段作为 name，全路径记入描述
        String name = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        p.setName(name);
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        String desc = unescapeMd(cells.get(3));
        if (!fullName.equals(name)) {
            desc = (desc.isEmpty() ? "" : desc + " ") + "(嵌套路径: " + fullName + ")";
        }
        p.setDescription(desc);
        p.setLocation(ParameterLocation.BODY);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyHeaderCell(ApiDefinition api, List<String> cells) {
        // | Header名 | 必填 | 说明 |
        if (cells.size() < 3) return;
        String name = cleanCell(cells.get(0));
        if (name.isEmpty()) return;
        ApiParameter p = new ApiParameter();
        p.setName(name);
        p.setType("String");
        p.setRequired(parseRequired(cells.get(1)));
        p.setDescription(unescapeMd(cells.get(2)));
        p.setLocation(ParameterLocation.HEADER);
        api.getParameters().add(p);
        api.getHeaders().put(name, "");
    }

    private static boolean parseRequired(String cell) {
        String c = cell == null ? "" : cell.trim();
        return c.contains("是") || c.equalsIgnoreCase("true") || c.equalsIgnoreCase("yes");
    }
}