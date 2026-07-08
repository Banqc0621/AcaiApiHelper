package com.ban.acai.http;

import com.ban.acai.AcaiConstants;
import com.ban.acai.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP请求执行服务 - 负责构建和发送HTTP请求
 *
 * v3 改进:
 * - Cookie自动管理
 * - 环境变量 {{var}} 全链路替换
 * - 按接口预期状态码判定通过/失败
 * - form-urlencoded 支持
 * - 断言支持
 */
@Service(Service.Level.PROJECT)
public class HttpExecutorService {

    private final Project project;
    private final Logger log = Logger.getInstance(HttpExecutorService.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Cookie管理器（全局实例，跨请求保持会话） */
    private final CookieManager cookieManager = new CookieManager();

    /** HTTP客户端实例，配置合理的超时参数 */
    private final HttpClient httpClient;

    /** 请求体格式常量 */
    public static final String BODY_FORMAT_JSON = "JSON";
    public static final String BODY_FORMAT_FORM = "FORM";
    public static final String BODY_FORMAT_RAW = "RAW";

    public HttpExecutorService(Project project) {
        this.project = project;
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(AcaiConstants.HTTP_CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .build();
    }

    /**
     * 函数式接口 - 批量测试进度回调
     */
    @FunctionalInterface
    public interface BatchTestListener {
        void onTestComplete(TestResult result, int current, int total);
    }

    /**
     * 函数式接口 - 请求历史记录回调
     */
    @FunctionalInterface
    public interface HistoryListener {
        void onRequestCompleted(TestResult result);
    }

    private HistoryListener historyListener = null;

    public void setHistoryListener(HistoryListener listener) {
        this.historyListener = listener;
    }

    /**
     * 执行单个API请求
     *
     * @param api           API定义（包含URL模板和参数信息）
     * @param baseUrl       基础URL（如 http://localhost:8080）
     * @param paramValues   参数名到实际值的映射
     * @param extraHeaders  额外的请求头
     * @param requestBody   自定义请求体JSON（优先于参数自动构建）
     * @param bodyFormat    请求体格式: JSON / FORM / RAW
     * @param environment   环境（用于变量替换，可为null）
     * @param assertions    响应断言列表（可为null）
     * @return 测试结果
     */
    public TestResult executeRequest(ApiDefinition api, String baseUrl, Map<String, String> paramValues,
                                     Map<String, String> extraHeaders, String requestBody,
                                     String bodyFormat, Environment environment,
                                     List<ResponseAssertion> assertions) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 构建完整请求URL（含路径参数替换和查询参数拼接）
            String fullUrl = buildRequestUrl(api, baseUrl, paramValues, environment);

            // 2. 构建请求体
            //    若接口含文件上传参数（@RequestPart + MultipartFile），强制使用 multipart/form-data
            boolean hasFileParam = api.getParameters().stream().anyMatch(ApiParameter::isFile);

            String body = null;
            String contentType;
            byte[] multipartBytes = null;
            String requestBodyDisplay;

            if (hasFileParam && !BODY_FORMAT_RAW.equals(bodyFormat)) {
                // 文件上传：构建 multipart/form-data 请求体
                MultipartBody multipart = buildMultipartBody(api, paramValues, environment);
                multipartBytes = multipart.bytes;
                contentType = AcaiConstants.CONTENT_TYPE_FORM_DATA + "; boundary=" + multipart.boundary;
                requestBodyDisplay = multipart.summary;
            } else if (BODY_FORMAT_RAW.equals(bodyFormat)) {
                // RAW格式：直接使用传入的requestBody
                body = requestBody;
                contentType = api.getConsumes();
                requestBodyDisplay = body != null ? body : "";
            } else if (requestBody != null) {
                body = requestBody;
                contentType = resolveContentType(api, bodyFormat);
                requestBodyDisplay = body;
            } else {
                body = buildRequestBody(api, paramValues, bodyFormat);
                contentType = resolveContentType(api, bodyFormat);
                requestBodyDisplay = body != null ? body : "";
            }

            // 3. 构建HttpRequest
            HttpRequest request = buildHttpRequest(api.getHttpMethod(), fullUrl, body, multipartBytes, contentType,
                    api, extraHeaders, environment);

            // 4. 发送请求并接收响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long duration = System.currentTimeMillis() - startTime;

            // 5. 构建测试结果
            TestResult result = new TestResult();
            result.setApiDefinition(api);
            result.setStatusCode(response.statusCode());
            result.setResponseBody(formatResponseBody(response.body()));
            result.setResponseHeaders(extractResponseHeaders(response));
            result.setRequestUrl(fullUrl);
            result.setRequestBody(requestBodyDisplay);
            result.setDurationMs(duration);
            result.setTimestamp(System.currentTimeMillis());

            // 7. 执行断言（先执行自定义断言，再根据预期状态码判定）
            boolean passed = true;
            List<ResponseAssertion> assertResults = new ArrayList<>();
            if (assertions != null && !assertions.isEmpty()) {
                for (ResponseAssertion a : assertions) {
                    ResponseAssertion copy = new ResponseAssertion();
                    copy.setType(a.getType());
                    copy.setTarget(a.getTarget());
                    copy.setExpected(a.getExpected());
                    copy.check(response.statusCode(), response.body(),
                            extractResponseHeaders(response), duration);
                    assertResults.add(copy);
                    if (!copy.isPassed()) passed = false;
                }
                result.setAssertions(assertResults);
            } else {
                // 默认：使用接口的预期状态码判定
                passed = api.isStatusCodeExpected(response.statusCode());
            }
            result.setStatus(passed ? TestStatus.PASSED : TestStatus.FAILED);

            // 通知历史记录
            if (historyListener != null) {
                historyListener.onRequestCompleted(result);
            }

            log.info("请求完成: " + api.getHttpMethod() + " " + fullUrl + " -> " + response.statusCode() + " (" + duration + "ms)");
            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("请求异常: " + api.getHttpMethod() + " " + api.getUrl() + " - " + e.getMessage());

            TestResult result = new TestResult();
            result.setApiDefinition(api);
            result.setStatus(TestStatus.ERROR);
            result.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            result.setDurationMs(duration);
            result.setTimestamp(System.currentTimeMillis());

            if (historyListener != null) {
                historyListener.onRequestCompleted(result);
            }
            return result;
        }
    }

    /** 简化版本（使用默认JSON body格式，无断言） */
    public TestResult executeRequest(ApiDefinition api, String baseUrl, Map<String, String> paramValues,
                                     Map<String, String> extraHeaders, String requestBody) {
        return executeRequest(api, baseUrl, paramValues, extraHeaders, requestBody,
                BODY_FORMAT_JSON, null, null);
    }

    /** 不带额外头和请求体的简化版本 */
    public TestResult executeRequest(ApiDefinition api, String baseUrl, Map<String, String> paramValues) {
        return executeRequest(api, baseUrl, paramValues, Collections.emptyMap(), null,
                BODY_FORMAT_JSON, null, null);
    }

    /**
     * 批量执行自动化测试（支持按接口自定义预期状态码和断言）
     */
    public TestReport executeBatchTest(List<ApiDefinition> apis, TestProfile profile,
                                       Environment environment, BatchTestListener listener) {
        TestReport report = new TestReport();
        report.setTestName(profile.getName());
        report.setStartTime(System.currentTimeMillis());

        for (int i = 0; i < apis.size(); i++) {
            ApiDefinition api = apis.get(i);
            Map<String, String> params = profile.getParams(api.uniqueKey());
            TestResult result = executeRequest(api, profile.getBaseUrl(), params,
                    profile.getGlobalHeaders(), null, BODY_FORMAT_JSON, environment, null);
            report.getResults().add(result);

            if (listener != null) {
                listener.onTestComplete(result, i + 1, apis.size());
            }
        }

        report.setEndTime(System.currentTimeMillis());
        return report;
    }

    /** 不带environment的简化版本 */
    public TestReport executeBatchTest(List<ApiDefinition> apis, TestProfile profile, BatchTestListener listener) {
        return executeBatchTest(apis, profile, null, listener);
    }

    /** 不带listener的简化版本 */
    public TestReport executeBatchTest(List<ApiDefinition> apis, TestProfile profile) {
        return executeBatchTest(apis, profile, null, null);
    }

    /** 清空Cookie */
    public void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    /** 获取Cookie字符串（用于调试显示） */
    public String getCookieDebugString() {
        try {
            List<java.net.HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
            if (cookies.isEmpty()) return "(无Cookie)";
            StringBuilder sb = new StringBuilder();
            for (java.net.HttpCookie c : cookies) {
                sb.append(c.getName()).append("=").append(c.getValue()).append("; ");
            }
            return sb.toString();
        } catch (Exception e) {
            return "(Cookie读取失败)";
        }
    }

    // ================================================================
    // 私有方法
    // ================================================================

    /**
     * 解析环境变量: 将 {{varName}} 替换为对应值
     */
    private String resolveEnvVars(String text, Environment env) {
        if (text == null || env == null) return text;
        return env.resolveVariables(text);
    }

    /**
     * 确定Content-Type
     */
    private String resolveContentType(ApiDefinition api, String bodyFormat) {
        if (BODY_FORMAT_FORM.equals(bodyFormat)) {
            return AcaiConstants.CONTENT_TYPE_FORM_URLENCODED;
        }
        return api.getConsumes();
    }

    /**
     * 构建完整的请求URL
     * 处理路径参数替换和查询参数拼接，支持环境变量
     */
    private String buildRequestUrl(ApiDefinition api, String baseUrl, Map<String, String> paramValues,
                                   Environment env) {
        String resolvedBase = resolveEnvVars(baseUrl, env);
        String url = resolveEnvVars(api.getUrl(), env);

        // 路径参数替换
        for (ApiParameter param : api.pathParameters()) {
            String value = paramValues.getOrDefault(param.getName(), param.generateDefaultValue());
            value = resolveEnvVars(value, env);
            url = url.replace("{" + param.getName() + "}", URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        // 查询参数拼接
        List<String> queryParams = new ArrayList<>();
        for (ApiParameter param : api.queryParameters()) {
            String value = paramValues.get(param.getName());
            if (value != null) {
                value = resolveEnvVars(value, env);
                queryParams.add(URLEncoder.encode(param.getName(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        // FORM类型参数不作为查询参数（会放到body里）

        String normalizedBase = resolvedBase.endsWith("/") ? resolvedBase.substring(0, resolvedBase.length() - 1) : resolvedBase;
        if (!queryParams.isEmpty()) {
            return normalizedBase + url + "?" + String.join("&", queryParams);
        } else {
            return normalizedBase + url;
        }
    }

    /**
     * 构建请求体（支持JSON和form-urlencoded格式）
     */
    private String buildRequestBody(ApiDefinition api, Map<String, String> paramValues, String bodyFormat) {
        String method = api.getHttpMethod().toUpperCase();
        if (AcaiConstants.METHODS_WITHOUT_BODY.contains(method)) return null;

        List<ApiParameter> bodyParams = api.bodyParameters();
        List<ApiParameter> formParams = api.formParameters();

        // form-urlencoded
        if (BODY_FORMAT_FORM.equals(bodyFormat)) {
            // 收集BODY参数和FORM参数
            Map<String, String> formData = new LinkedHashMap<>();
            for (ApiParameter param : bodyParams) {
                String value = paramValues.get(param.getName());
                if (value != null) {
                    formData.put(param.getName(), value);
                } else {
                    formData.put(param.getName(), param.generateDefaultValue());
                }
            }
            for (ApiParameter param : formParams) {
                String value = paramValues.get(param.getName());
                if (value != null) {
                    formData.put(param.getName(), value);
                } else {
                    formData.put(param.getName(), param.generateDefaultValue());
                }
            }
            return formData.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
        }

        // JSON body (默认)
        if (bodyParams.isEmpty()) return null;

        Map<String, Object> jsonMap = new LinkedHashMap<>();
        for (ApiParameter param : bodyParams) {
            String value = paramValues.get(param.getName());
            if (value != null) {
                jsonMap.put(param.getName(), parseValueByType(value, param.getType()));
            } else if (param.isComplexType()) {
                jsonMap.put(param.getName(), gson.fromJson(param.generateDefaultValue(), Object.class));
            } else {
                jsonMap.put(param.getName(), parseValueByType(param.generateDefaultValue(), param.getType()));
            }
        }

        if (bodyParams.size() == 1 && bodyParams.get(0).isComplexType()) {
            Object singleValue = jsonMap.get(bodyParams.get(0).getName());
            return gson.toJson(singleValue);
        }

        return gson.toJson(jsonMap);
    }

    /**
     * 根据类型解析值（将字符串转为对应的Java类型）
     */
    private Object parseValueByType(String value, String type) {
        if (type == null) return value;
        return switch (type.toLowerCase()) {
            case "int", "integer", "java.lang.integer" -> {
                try { yield Integer.parseInt(value); } catch (NumberFormatException e) { yield 0; }
            }
            case "long", "java.lang.long" -> {
                try { yield Long.parseLong(value); } catch (NumberFormatException e) { yield 0L; }
            }
            case "double", "java.lang.double" -> {
                try { yield Double.parseDouble(value); } catch (NumberFormatException e) { yield 0.0; }
            }
            case "float", "java.lang.float" -> {
                try { yield Float.parseFloat(value); } catch (NumberFormatException e) { yield 0.0f; }
            }
            case "boolean", "java.lang.boolean" -> Boolean.parseBoolean(value);
            default -> {
                try {
                    if (value.startsWith("{") || value.startsWith("[")) {
                        yield gson.fromJson(value, Object.class);
                    } else {
                        yield value;
                    }
                } catch (Exception e) {
                    yield value;
                }
            }
        };
    }

    /**
     * 构建HttpRequest对象
     */
    private HttpRequest buildHttpRequest(String method, String url, String body, String contentType,
                                          ApiDefinition api, Map<String, String> extraHeaders,
                                          Environment env) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(AcaiConstants.HTTP_REQUEST_TIMEOUT_SECONDS));

        // 设置默认请求头
        builder.header(AcaiConstants.HEADER_CONTENT_TYPE, contentType);
        builder.header(AcaiConstants.HEADER_ACCEPT, api.getProduces());

        // 设置API定义中的自定义请求头（支持环境变量替换）
        api.getHeaders().forEach((k, v) -> builder.header(k, resolveEnvVars(v, env)));

        // 设置额外的请求头（优先级最高，支持环境变量替换）
        extraHeaders.forEach((k, v) -> builder.header(k, resolveEnvVars(v, env)));

        // 根据HTTP方法设置请求方法和请求体
        HttpRequest.BodyPublisher bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        switch (method.toUpperCase()) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(bodyPublisher);
            case "PUT" -> builder.PUT(bodyPublisher);
            case "DELETE" -> builder.DELETE();
            case "PATCH" -> builder.method("PATCH", bodyPublisher);
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            default -> builder.method(method.toUpperCase(), bodyPublisher);
        }

        return builder.build();
    }

    /**
     * 格式化响应体（尝试JSON美化）
     */
    private String formatResponseBody(String body) {
        if (body == null) return "";
        try {
            var jsonElement = JsonParser.parseString(body);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * 提取响应头为Map
     */
    private Map<String, String> extractResponseHeaders(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> headers.put(key, String.join(", ", values)));
        return headers;
    }

    /**
     * 获取HttpExecutorService实例的便捷方法
     */
    public static HttpExecutorService getInstance(Project project) {
        return project.getService(HttpExecutorService.class);
    }
}