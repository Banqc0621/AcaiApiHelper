package com.ban.acai.util;

import com.ban.acai.model.ApiDefinition;
import com.ban.acai.model.ApiParameter;
import com.ban.acai.model.ParameterLocation;
import com.ban.acai.model.RequestHistory;
import com.ban.acai.settings.RestAutoLabSettingsState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Postman Collection v2.1 导出器
 *
 * 输出符合 https://schema.getpostman.com/json/collection/v2.1.0/collection.json 的 JSON，
 * 可被 Postman 和 Apifox 直接导入并生成接口。
 *
 * 每个接口的 request 会尽量填入"最近一次真实测试"的请求头、请求体和参数值，
 * 使导入后即可看到真实测试数据。
 */
public class PostmanCollectionExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA_URL =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    /**
     * 导出选中接口为 Postman Collection v2.1 JSON 字符串。
     *
     * @param apis      选中的接口列表
     * @param baseUrl   基础URL（如 http://localhost:8080），用于拼接完整请求地址
     * @param history   请求历史列表（用于回填真实测试数据），可为null
     * @return Postman Collection JSON 字符串
     */
    public static String exportToJson(List<ApiDefinition> apis, String baseUrl, List<RequestHistory> history) {
        JsonObject root = new JsonObject();

        // info
        JsonObject info = new JsonObject();
        info.addProperty("_postman_id", UUID.randomUUID().toString());
        info.addProperty("name", "RestAutoLab Export");
        info.addProperty("schema", SCHEMA_URL);
        JsonObject desc = new JsonObject();
        desc.addProperty("content", "Exported by RestAutoLab at " + System.currentTimeMillis());
        desc.addProperty("type", "text/plain");
        info.add("description", desc);
        root.add("info", info);

        // item
        JsonArray items = new JsonArray();
        for (ApiDefinition api : apis) {
            items.add(buildItem(api, baseUrl, findLatestHistory(api, history)));
        }
        root.add("item", items);

        return GSON.toJson(root);
    }

    /**
     * 导出选中接口为 Postman Collection v2.1 JSON 文件。
     *
     * @param apis      选中的接口列表
     * @param baseUrl   基础URL
     * @param history   请求历史列表（可为null）
     * @param outputFile 输出文件绝对路径
     * @return 输出文件路径
     */
    public static String exportToFile(List<ApiDefinition> apis, String baseUrl,
                                      List<RequestHistory> history, String outputFile) throws IOException {
        String json = exportToJson(apis, baseUrl, history);
        Path path = Paths.get(outputFile);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return outputFile;
    }

    // ================================================================
    // 内部构建逻辑
    // ================================================================

    /** 构建单个接口的 item 节点 */
    private static JsonObject buildItem(ApiDefinition api, String baseUrl, RequestHistory latestHistory) {
        JsonObject item = new JsonObject();
        item.addProperty("name", (api.getName() == null || api.getName().isBlank())
                ? api.getUrl() : api.getName());

        JsonObject request = new JsonObject();
        request.addProperty("method", api.getHttpMethod().toUpperCase());

        // 描述
        if (api.getDescription() != null && !api.getDescription().isBlank()) {
            JsonObject d = new JsonObject();
            d.addProperty("content", api.getDescription());
            d.addProperty("type", "text/plain");
            request.add("description", d);
        }

        // 请求头
        JsonArray headers = buildHeaders(api, latestHistory);
        if (headers.size() > 0) request.add("header", headers);

        // 请求体
        JsonObject body = buildBody(api, latestHistory);
        if (body != null) request.add("body", body);

        // URL
        request.add("url", buildUrl(api, baseUrl, latestHistory));

        item.add("request", request);

        // response: 把最近一次真实测试响应作为 example 放入，便于导入后查看
        JsonArray responses = new JsonArray();
        if (latestHistory != null && latestHistory.getResponseBody() != null) {
            responses.add(buildExampleResponse(api, latestHistory));
        }
        item.add("response", responses);

        return item;
    }

    /** 构建请求头数组，优先使用真实测试历史的请求头 */
    private static JsonArray buildHeaders(ApiDefinition api, RequestHistory latestHistory) {
        JsonArray headers = new JsonArray();
        // 优先用真实历史的请求头
        if (latestHistory != null && latestHistory.getHeaders() != null
                && !latestHistory.getHeaders().isEmpty()) {
            for (Map.Entry<String, String> e : latestHistory.getHeaders().entrySet()) {
                headers.add(headerObj(e.getKey(), e.getValue()));
            }
            return headers;
        }
        // 否则用接口定义的 Content-Type + 显式 header 参数
        String consumes = api.getConsumes();
        if (consumes != null && !consumes.isBlank()) {
            headers.add(headerObj("Content-Type", consumes));
        }
        for (ApiParameter p : api.headerParameters()) {
            headers.add(headerObj(p.getName(), p.getDefaultValue()));
        }
        return headers;
    }

    private static JsonObject headerObj(String key, String value) {
        JsonObject h = new JsonObject();
        h.addProperty("key", key);
        h.addProperty("value", value == null ? "" : value);
        h.addProperty("type", "text");
        return h;
    }

    /**
     * 构建请求体。
     * 文件上传接口 → formdata；其余 → raw（优先真实历史请求体）。
     */
    private static JsonObject buildBody(ApiDefinition api, RequestHistory latestHistory) {
        boolean hasFile = api.getParameters().stream().anyMatch(ApiParameter::isFile);
        if (hasFile) {
            return buildFormdataBody(api, latestHistory);
        }

        // 优先用真实测试历史的请求体
        String rawBody = latestHistory != null ? latestHistory.getRequestBody() : null;
        if (rawBody == null || rawBody.isBlank()) {
            // 无历史时，用 BODY 参数构造一个示例 JSON
            rawBody = buildSampleJsonBody(api);
        }
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }

        JsonObject body = new JsonObject();
        body.addProperty("mode", "raw");
        body.addProperty("raw", rawBody);
        JsonObject options = new JsonObject();
        JsonObject raw = new JsonObject();
        raw.addProperty("language", "json");
        options.add("raw", raw);
        body.add("options", options);
        return body;
    }

    /** 文件上传接口的 formdata 请求体 */
    private static JsonObject buildFormdataBody(ApiDefinition api, RequestHistory latestHistory) {
        JsonObject body = new JsonObject();
        body.addProperty("mode", "formdata");
        JsonArray formdata = new JsonArray();
        for (ApiParameter p : api.getParameters()) {
            if (p.getLocation() == ParameterLocation.PATH || p.getLocation() == ParameterLocation.QUERY) continue;
            JsonObject field = new JsonObject();
            field.addProperty("key", p.getName());
            if (p.isFile()) {
                field.addProperty("type", "file");
                // src 用参数默认值（插件中文件参数的值即本地文件路径）
                String filePath = p.getDefaultValue();
                if (filePath != null && !filePath.isBlank()) {
                    JsonArray src = new JsonArray();
                    src.add(filePath);
                    field.add("src", src);
                }
            } else {
                field.addProperty("type", "text");
                field.addProperty("value", p.getDefaultValue() == null ? "" : p.getDefaultValue());
            }
            formdata.add(field);
        }
        body.add("formdata", formdata);
        return body;
    }

    /** 无历史时，用 BODY 参数生成示例 JSON */
    private static String buildSampleJsonBody(ApiDefinition api) {
        List<ApiParameter> bodyParams = api.bodyParameters();
        if (bodyParams.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < bodyParams.size(); i++) {
            ApiParameter p = bodyParams.get(i);
            if (i > 0) sb.append(",");
            sb.append("\"").append(p.getName()).append("\": ")
              .append(jsonValue(p.generateDefaultValue(), p.getType()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonValue(String value, String type) {
        if (value == null) return "null";
        String t = type == null ? "" : type.toLowerCase();
        return switch (t) {
            case "int", "integer", "long", "double", "float", "boolean",
                 "java.lang.integer", "java.lang.long", "java.lang.double",
                 "java.lang.float", "java.lang.boolean" -> value;
            default -> (value.startsWith("{") || value.startsWith("[")) ? value : "\"" + value.replace("\"", "\\\"") + "\"";
        };
    }

    /**
     * 构建 Postman URL 对象。
     * path 参数用 variable 表达；query 参数用 query 数组表达。
     * 完整地址优先取真实历史 URL，否则用 baseUrl + 接口路径拼接。
     */
    private static JsonObject buildUrl(ApiDefinition api, String baseUrl, RequestHistory latestHistory) {
        String fullUrl;
        if (latestHistory != null && latestHistory.getUrl() != null && !latestHistory.getUrl().isBlank()) {
            fullUrl = latestHistory.getUrl();
        } else {
            String base = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl;
            fullUrl = base + api.getUrl();
        }

        JsonObject url = new JsonObject();
        url.addProperty("raw", fullUrl);

        try {
            URI uri = URI.create(fullUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String pathPart = uri.getRawPath();
            String queryPart = uri.getRawQuery();

            if (scheme != null) url.addProperty("protocol", scheme);
            if (host != null) {
                JsonArray hostArr = new JsonArray();
                for (String seg : host.split("\\.")) hostArr.add(seg);
                url.add("host", hostArr);
            }
            if (port > 0) url.addProperty("port", String.valueOf(port));

            if (pathPart != null && !pathPart.isBlank()) {
                JsonArray pathArr = new JsonArray();
                for (String seg : pathPart.split("/")) {
                    if (seg.isEmpty()) continue;
                    // /users/{id} → Postman path 用 ":id"
                    if (seg.startsWith("{") && seg.endsWith("}")) {
                        pathArr.add(":" + seg.substring(1, seg.length() - 1));
                    } else {
                        pathArr.add(seg);
                    }
                }
                url.add("path", pathArr);
            }

            // query 参数
            JsonArray queryArr = new JsonArray();
            if (queryPart != null && !queryPart.isBlank()) {
                for (String pair : queryPart.split("&")) {
                    int eq = pair.indexOf('=');
                    String k = eq >= 0 ? pair.substring(0, eq) : pair;
                    String v = eq >= 0 ? pair.substring(eq + 1) : "";
                    JsonObject q = new JsonObject();
                    q.addProperty("key", k);
                    q.addProperty("value", v);
                    queryArr.add(q);
                }
            }
            // 接口定义里的 query 参数（历史 URL 没带时补上）
            for (ApiParameter p : api.queryParameters()) {
                if (queryPart == null || !queryPart.contains(p.getName() + "=")) {
                    JsonObject q = new JsonObject();
                    q.addProperty("key", p.getName());
                    q.addProperty("value", p.getDefaultValue() == null ? "" : p.getDefaultValue());
                    queryArr.add(q);
                }
            }
            if (queryArr.size() > 0) url.add("query", queryArr);

            // path 参数 variable
            JsonArray variables = new JsonArray();
            for (ApiParameter p : api.pathParameters()) {
                JsonObject v = new JsonObject();
                v.addProperty("key", p.getName());
                v.addProperty("value", p.getDefaultValue() == null ? "" : p.getDefaultValue());
                if (p.getDescription() != null && !p.getDescription().isBlank()) {
                    v.addProperty("description", p.getDescription());
                }
                variables.add(v);
            }
            if (variables.size() > 0) url.add("variable", variables);

        } catch (IllegalArgumentException ex) {
            // URI 解析失败时保留 raw 字段即可，Postman 仍可识别
        }

        return url;
    }

    /** 把最近一次真实测试响应构造成 Postman example，导入后可在接口下查看 */
    private static JsonObject buildExampleResponse(ApiDefinition api, RequestHistory h) {
        JsonObject resp = new JsonObject();
        resp.addProperty("name", "最近测试 " + h.timeDisplay() + " (" + h.getStatusCode() + ")");
        JsonObject originalRequest = new JsonObject();
        originalRequest.addProperty("method", h.getMethod());
        if (h.getUrl() != null) {
            JsonObject u = new JsonObject();
            u.addProperty("raw", h.getUrl());
            originalRequest.add("url", u);
        }
        resp.add("originalRequest", originalRequest);
        resp.addProperty("status", h.getStatusCode() >= 200 && h.getStatusCode() < 300 ? "OK" : "Error");
        resp.addProperty("code", h.getStatusCode());
        resp.addProperty("_postman_previewlanguage", "json");
        if (h.getResponseBody() != null) {
            JsonObject body = new JsonObject();
            body.addProperty("mode", "raw");
            body.addProperty("raw", h.getResponseBody());
            resp.add("body", body);
        }
        return resp;
    }

    /** 在历史列表中查找该接口最近一次的测试记录（按 url + method 匹配，取时间最新） */
    private static RequestHistory findLatestHistory(ApiDefinition api, List<RequestHistory> history) {
        if (history == null || history.isEmpty()) return null;
        RequestHistory latest = null;
        String apiPath = api.getUrl();
        String method = api.getHttpMethod().toUpperCase();
        for (RequestHistory h : history) {
            if (h.getMethod() == null || !h.getMethod().equalsIgnoreCase(method)) continue;
            // 历史里的 url 是完整地址（含 baseUrl），用 endsWith 匹配接口路径
            String hUrl = h.getUrl();
            if (hUrl == null) continue;
            boolean match = hUrl.endsWith(apiPath)
                    || hUrl.contains(apiPath)
                    || (apiPath.contains("{") && pathMatches(hUrl, apiPath));
            if (match && (latest == null || h.getTimestamp() > latest.getTimestamp())) {
                latest = h;
            }
        }
        return latest;
    }

    /** 路径参数模糊匹配：把 {xxx} 当作通配段比较 */
    private static boolean pathMatches(String fullUrl, String apiPath) {
        String[] apiSegs = apiPath.split("/");
        String[] urlSegs = fullUrl.split("[?]");
        String pathOnly = urlSegs[0];
        String[] pathSegs = pathOnly.split("/");
        if (apiSegs.length != pathSegs.length) return false;
        for (int i = 0; i < apiSegs.length; i++) {
            String a = apiSegs[i];
            if (a.startsWith("{") && a.endsWith("}")) continue;
            if (!a.equals(pathSegs[i])) return false;
        }
        return true;
    }
}