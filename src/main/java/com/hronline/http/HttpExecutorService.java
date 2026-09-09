package com.hronline.http;

import com.hronline.RestAutoLabConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.hronline.model.*;
import com.hronline.service.ExceptionRuleEvaluator;
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
import java.util.concurrent.atomic.AtomicLong;

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
public final class HttpExecutorService {

    private final Project project;
    private final Logger log = Logger.getInstance(HttpExecutorService.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Cookie管理器（全局实例，跨请求保持会话） */
    private final CookieManager cookieManager = new CookieManager();

    /** HTTP客户端实例，配置合理的超时参数 */
    private final HttpClient httpClient;
    /** 历史时间必须反映发起顺序；同一毫秒内的批量请求也要保持可排序。 */
    private final AtomicLong lastRequestTimestamp = new AtomicLong();

    /** 请求体格式常量 */
    public static final String BODY_FORMAT_JSON = "JSON";
    public static final String BODY_FORMAT_FORM = "FORM";
    public static final String BODY_FORMAT_RAW = "RAW";
    public static final String BODY_FORMAT_XML = "XML";
    public static final String BODY_FORMAT_TEXT = "TEXT";
    public static final String BODY_FORMAT_HTML = "HTML";

    public HttpExecutorService(Project project) {
        this.project = project;
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(RestAutoLabConstants.HTTP_CONNECT_TIMEOUT_SECONDS))
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
        // 耗时必须从真实系统时间计算；历史排序时间单独保证同毫秒请求仍有稳定顺序。
        long requestStartMillis = System.currentTimeMillis();
        long historyTimestamp = nextRequestTimestamp();
        String method = api == null || api.getHttpMethod() == null
                ? "" : api.getHttpMethod().trim().toUpperCase(Locale.ROOT);
        boolean getRequest = "GET".equals(method);
        Map<String, String> historyParameters = getRequest
                ? new LinkedHashMap<>(paramValues == null ? Collections.emptyMap() : paramValues)
                : new LinkedHashMap<>();
        String historyBody = "";
        // 在构建请求 URL 后即保存快照；网络/解析异常也要把最终 URL写入历史，
        // 不能退回 /users/{id} 这类模板路径。
        String fullUrl = null;

        try {
            // 1. 构建完整请求URL（含路径参数替换和查询参数拼接）
            fullUrl = buildRequestUrl(api, baseUrl, paramValues, environment);

            // 2. 构建请求体
            //    若接口含文件上传参数（@RequestPart + MultipartFile），强制使用 multipart/form-data
            boolean hasFileParam = api.getParameters().stream().anyMatch(ApiParameter::isFile);

            String body = null;
            String contentType;
            byte[] multipartBytes = null;
            String requestBodyDisplay;

            if (getRequest) {
                // GET 的请求数据只来自 path/query 参数，忽略编辑器中的 body。
                body = null;
                contentType = resolveContentType(api, bodyFormat);
                requestBodyDisplay = "";
            } else if (hasFileParam && !BODY_FORMAT_RAW.equals(bodyFormat)) {
                // 文件上传：构建 multipart/form-data 请求体
                MultipartBody multipart = buildMultipartBody(api, paramValues, environment);
                multipartBytes = multipart.bytes;
                contentType = RestAutoLabConstants.CONTENT_TYPE_FORM_DATA + "; boundary=" + multipart.boundary;
                requestBodyDisplay = multipart.summary;
            } else if (BODY_FORMAT_RAW.equals(bodyFormat)) {
                // RAW格式：直接使用传入的requestBody
                body = resolveEnvVars(requestBody, environment);
                contentType = api.getConsumes();
                requestBodyDisplay = body != null ? body : "";
            } else if (requestBody != null) {
                body = resolveEnvVars(requestBody, environment);
                contentType = resolveContentType(api, bodyFormat);
                requestBodyDisplay = body;
            } else {
                body = buildRequestBody(api, paramValues, bodyFormat, environment);
                contentType = resolveContentType(api, bodyFormat);
                requestBodyDisplay = body != null ? body : "";
            }

            historyBody = getRequest ? "" : requestBodyDisplay;

            // 3. 构建HttpRequest
            HttpRequest request = buildHttpRequest(api.getHttpMethod(), fullUrl, body, multipartBytes, contentType,
                    api, extraHeaders, environment);

            // 4. 发送请求并接收响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long duration = System.currentTimeMillis() - requestStartMillis;

            // 5. 构建测试结果
            TestResult result = new TestResult();
            result.setApiDefinition(api);
            result.setStatusCode(response.statusCode());
            result.setResponseBody(formatResponseBody(response.body()));
            result.setResponseHeaders(extractResponseHeaders(response));
            result.setRequestUrl(fullUrl);
            result.setRequestBody(historyBody);
            result.setRequestParameters(historyParameters);
            result.setRequestHeaders(buildRequestHeaders(api, extraHeaders, contentType, environment));
            result.setDurationMs(duration);
            result.setTimestamp(historyTimestamp);

            // 7. 执行断言（先执行自定义断言，再根据预期状态码判定）
            boolean passed = true;
            List<ResponseAssertion> assertResults = new ArrayList<>();
            if (assertions != null && !assertions.isEmpty()) {
                List<String> failureReasons = new ArrayList<>();
                for (ResponseAssertion a : assertions) {
                    ResponseAssertion copy = new ResponseAssertion();
                    copy.setType(a.getType());
                    copy.setTarget(a.getTarget());
                    copy.setExpected(a.getExpected());
                    copy.check(response.statusCode(), response.body(),
                            extractResponseHeaders(response), duration);
                    assertResults.add(copy);
                    if (!copy.isPassed()) {
                        passed = false;
                        failureReasons.add(assertionFailureReason(copy));
                    }
                }
                result.setAssertions(assertResults);
                if (!failureReasons.isEmpty()) {
                    result.setErrorMessage(String.join("；", failureReasons));
                }
            } else {
                // 默认：HTTP_VALUE 规则 → 跑自定义 HTTP 层判定；否则走接口预期状态码
                // 一伦优化 #91：HTTP_VALUE 规则覆盖默认 2xx 判定。
                // 用户配置 HTTP_VALUE + 字段=code + 白名单=[500] 时，HTTP 500 + body.code=500
                // 应判通过（业务码说了算），不应被写死的 2xx 杀在 HTTP 层。
                List<ExceptionRule> allRules = project == null
                        ? Collections.emptyList()
                        : com.hronline.settings.RestAutoLabSettingsState
                                .getInstance(project).loadExceptionRules();
                List<ExceptionRule> httpRules = allRules.stream()
                        .filter(r -> r != null && r.isEnabled()
                                && r.getType() == ExceptionRule.RuleType.HTTP_VALUE)
                        .collect(Collectors.toList());
                if (!httpRules.isEmpty()) {
                    ExceptionRuleEvaluator.Result er = ExceptionRuleEvaluator.evaluateRules(
                            httpRules, response.statusCode(), result.getResponseBody());
                    passed = er.isPassed();
                    if (!passed) {
                        result.setErrorMessage(er.reason());
                    }
                } else {
                    passed = api.isStatusCodeExpected(response.statusCode());
                    if (!passed) {
                        result.setErrorMessage(expectedStatusFailureReason(api, response.statusCode()));
                    }
                }
                // Round 7：HTTP 通过后再跑 FIELD_VALUE 黑名单（业务字段告警值）
                if (passed && project != null) {
                    List<ExceptionRule> fieldRules = allRules.stream()
                            .filter(r -> r != null && r.isEnabled()
                                    && r.getType() == ExceptionRule.RuleType.FIELD_VALUE)
                            .collect(Collectors.toList());
                    if (!fieldRules.isEmpty()) {
                        ExceptionRuleEvaluator.Result er = ExceptionRuleEvaluator.evaluateRules(
                                fieldRules, response.statusCode(), result.getResponseBody());
                        if (!er.isPassed()) {
                            passed = false;
                            result.setErrorMessage(er.reason());
                        }
                    }
                }
            }
            result.setStatus(passed ? TestStatus.PASSED : TestStatus.FAILED);

            // 通知历史记录
            if (historyListener != null) {
                historyListener.onRequestCompleted(result);
            }

            log.info("请求完成: " + api.getHttpMethod() + " " + fullUrl + " -> " + response.statusCode() + " (" + duration + "ms)");
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TestResult result = new TestResult();
            result.setApiDefinition(api);
            result.setStatus(TestStatus.CANCELLED);
            result.setErrorMessage("请求已取消");
            result.setRequestUrl(fullUrl != null ? fullUrl : (api == null ? "" : api.getUrl()));
            result.setRequestBody(historyBody);
            result.setRequestParameters(historyParameters);
            result.setRequestHeaders(new LinkedHashMap<>(extraHeaders == null
                    ? Collections.emptyMap() : extraHeaders));
            result.setDurationMs(System.currentTimeMillis() - requestStartMillis);
            result.setTimestamp(historyTimestamp);
            if (historyListener != null) {
                historyListener.onRequestCompleted(result);
            }
            log.info("请求已取消: " + (api == null ? "" : api.getHttpMethod()) + " "
                    + (api == null ? "" : api.getUrl()));
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - requestStartMillis;
            log.warn("请求异常: " + (api == null ? "" : api.getHttpMethod()) + " "
                    + (api == null ? "" : api.getUrl()) + " - " + e.getMessage());

            TestResult result = new TestResult();
            result.setApiDefinition(api);
            result.setStatus(TestStatus.ERROR);
            result.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            result.setRequestUrl(fullUrl != null ? fullUrl : (api == null ? "" : api.getUrl()));
            result.setRequestBody(historyBody);
            result.setRequestParameters(historyParameters);
            result.setRequestHeaders(new LinkedHashMap<>(extraHeaders == null
                    ? Collections.emptyMap() : extraHeaders));
            result.setDurationMs(duration);
            result.setTimestamp(historyTimestamp);

            if (historyListener != null) {
                historyListener.onRequestCompleted(result);
            }
            return result;
        }
    }

    private long nextRequestTimestamp() {
        long now = System.currentTimeMillis();
        return lastRequestTimestamp.updateAndGet(previous -> Math.max(now, previous + 1));
    }

    /** 为断言失败生成始终可见的中文原因，避免状态码断言只显示一个红色叉号。 */
    private static String assertionFailureReason(ResponseAssertion assertion) {
        if (assertion == null) return "响应断言未通过";
        String detail = assertion.getMessage();
        if (detail != null && !detail.isBlank()) return detail;
        String type = assertion.getType() == null ? "响应断言" : assertion.getType().getDisplayName();
        String expected = assertion.getExpected() == null || assertion.getExpected().isBlank()
                ? "-" : assertion.getExpected();
        String actual = assertion.getActual() == null || assertion.getActual().isBlank()
                ? "-" : assertion.getActual();
        return type + "未通过：期望 " + expected + "，实际 " + actual;
    }

    /** 默认接口预期状态码失败时的异常详情（例如预期 200、实际 500）。 */
    private static String expectedStatusFailureReason(ApiDefinition api, int actual) {
        if (api == null || api.getExpectedStatusCodes() == null || api.getExpectedStatusCodes().isEmpty()) {
            return "预期状态码为 2xx，实际为 " + actual;
        }
        List<Integer> expected = new ArrayList<>(api.getExpectedStatusCodes());
        Collections.sort(expected);
        return "预期状态码为 " + expected + "，实际为 " + actual;
    }

    /**
     * 生成历史记录用的有效请求头快照，与 buildHttpRequest 的覆盖顺序保持一致。
     */
    private Map<String, String> buildRequestHeaders(ApiDefinition api, Map<String, String> extraHeaders,
                                                     String contentType, Environment env) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RestAutoLabConstants.HEADER_CONTENT_TYPE, resolveEnvVars(contentType, env));
        headers.put(RestAutoLabConstants.HEADER_ACCEPT, resolveEnvVars(api.getProduces(), env));
        if (api.getHeaders() != null) {
            api.getHeaders().forEach((k, v) -> headers.put(k, resolveEnvVars(v, env)));
        }
        if (extraHeaders != null) {
            extraHeaders.forEach((k, v) -> headers.put(k, resolveEnvVars(v, env)));
        }
        return headers;
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
            return RestAutoLabConstants.CONTENT_TYPE_FORM_URLENCODED;
        }
        if (BODY_FORMAT_XML.equals(bodyFormat)) {
            return RestAutoLabConstants.CONTENT_TYPE_XML;
        }
        if (BODY_FORMAT_TEXT.equals(bodyFormat)) {
            return RestAutoLabConstants.CONTENT_TYPE_TEXT;
        }
        if (BODY_FORMAT_HTML.equals(bodyFormat)) {
            return RestAutoLabConstants.CONTENT_TYPE_HTML;
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

        String method = api.getHttpMethod() == null ? "" : api.getHttpMethod().trim().toUpperCase(Locale.ROOT);
        // 只有 GET 使用 query 参数；非 GET 的业务数据统一进入请求体。
        List<String> queryParams = new ArrayList<>();
        List<ApiParameter> queryParameters = "GET".equals(method)
                ? api.queryParameters() : Collections.emptyList();
        for (ApiParameter param : queryParameters) {
            String value = paramValues == null ? null : paramValues.get(param.getName());
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
    private String buildRequestBody(ApiDefinition api, Map<String, String> paramValues, String bodyFormat,
                                    Environment environment) {
        String method = api == null || api.getHttpMethod() == null
                ? "" : api.getHttpMethod().trim().toUpperCase(Locale.ROOT);
        // 只有 GET 的业务数据来自 URL 参数；其余方法（包括 DELETE/HEAD/OPTIONS）
        // 统一允许请求体，避免编辑器中保存的 body 被静默丢弃。
        if ("GET".equals(method)) return null;

        Map<String, String> values = paramValues == null ? Collections.emptyMap() : paramValues;

        List<ApiParameter> bodyParams = api.bodyParameters();
        List<ApiParameter> formParams = api.formParameters();

        // form-urlencoded
        if (BODY_FORMAT_FORM.equals(bodyFormat)) {
            // 收集BODY参数和FORM参数
            Map<String, String> formData = new LinkedHashMap<>();
            for (ApiParameter param : bodyParams) {
                String value = values.get(param.getName());
                if (value != null) {
                    formData.put(param.getName(), resolveEnvVars(value, environment));
                } else {
                    formData.put(param.getName(), resolveEnvVars(param.generateDefaultValue(), environment));
                }
            }
            for (ApiParameter param : formParams) {
                String value = values.get(param.getName());
                if (value != null) {
                    formData.put(param.getName(), resolveEnvVars(value, environment));
                } else {
                    formData.put(param.getName(), resolveEnvVars(param.generateDefaultValue(), environment));
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
            if (param.isComplexType()) {
                // 复杂 BODY 参数在调试面板中会展开为 request.child.field 形式；
                // 依赖链映射也允许把上游值注入到同样的嵌套路径。先构造整棵默认对象，
                // 再覆盖点号路径，避免映射值只写进 params 却从未进入实际请求体。
                Object complexValue = value == null
                        ? parseComplexBodyValue(param.generateDefaultValue())
                        : parseComplexBodyValue(resolveEnvVars(value, environment));
                Map<String, Object> objectValue = asMutableObject(complexValue);
                if (objectValue == null && hasNestedOverrides(param, values)) {
                    objectValue = new LinkedHashMap<>();
                }
                if (objectValue != null) {
                    applyNestedOverrides(param, objectValue, values, environment);
                    jsonMap.put(param.getName(), objectValue);
                } else if (value != null) {
                    jsonMap.put(param.getName(), parseValueByType(
                            resolveEnvVars(value, environment), param.getType()));
                } else {
                    jsonMap.put(param.getName(), complexValue);
                }
            } else if (value != null) {
                value = resolveEnvVars(value, environment);
                jsonMap.put(param.getName(), parseValueByType(value, param.getType()));
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

    /** 解析复杂请求体默认值；非法 JSON 时返回原始字符串，交由调用方决定是否回退为空对象。 */
    private Object parseComplexBodyValue(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<String, Object>();
        try {
            return gson.fromJson(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMutableObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /** 判断复杂参数是否存在至少一个点号路径覆盖值。 */
    private boolean hasNestedOverrides(ApiParameter root, Map<String, String> paramValues) {
        if (root == null || paramValues == null || root.getName() == null) return false;
        return paramValues.keySet().stream().anyMatch(k -> nestedPathForKey(root, k) != null);
    }

    /** 把 request.child.field=value 写入复杂 JSON 对象；未知字段也允许手工输入并创建。 */
    private void applyNestedOverrides(ApiParameter root, Map<String, Object> target,
                                      Map<String, String> paramValues, Environment environment) {
        if (root == null || target == null || paramValues == null || root.getName() == null) return;
        for (Map.Entry<String, String> entry : paramValues.entrySet()) {
            String key = entry.getKey();
            String path = nestedPathForKey(root, key);
            if (path == null) continue;
            path = path.trim();
            if (path.isBlank()) continue;

            String[] segments = path.split("\\.");
            Map<String, Object> current = target;
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i].trim();
                if (segment.isBlank()) continue;
                Object child = current.get(segment);
                Map<String, Object> childMap = asMutableObject(child);
                if (childMap == null) {
                    childMap = new LinkedHashMap<>();
                    current.put(segment, childMap);
                }
                current = childMap;
            }
            String leaf = segments[segments.length - 1].trim();
            if (leaf.isBlank()) continue;
            String raw = entry.getValue() == null ? "" : resolveEnvVars(entry.getValue(), environment);
            ApiParameter mappedParameter = findNestedParameter(root, segments);
            current.put(leaf, parseValueByType(raw,
                    mappedParameter == null ? null : mappedParameter.getType()));
        }
    }

    /**
     * 将参数键转换成复杂对象内部路径。优先接受 UI 展示的 root.child 形式，
     * 同时兼容用户手动输入不带 root 前缀的 child 路径（如 id 或 profile.name）。
     */
    private String nestedPathForKey(ApiParameter root, String key) {
        if (root == null || key == null || key.isBlank() || root.getName() == null) return null;
        String trimmed = key.trim();
        String prefix = root.getName().trim() + ".";
        if (trimmed.startsWith(prefix) && trimmed.length() > prefix.length()) {
            return trimmed.substring(prefix.length());
        }
        String[] segments = trimmed.split("\\.");
        return findNestedParameter(root, segments) == null ? null : trimmed;
    }

    /** 按 rootName.childName... 路径查找字段定义，用于保留数字/布尔类型。 */
    private ApiParameter findNestedParameter(ApiParameter root, String[] segments) {
        if (root == null || segments == null || segments.length == 0) return null;
        ApiParameter current = root;
        for (String rawSegment : segments) {
            String segment = rawSegment == null ? "" : rawSegment.trim();
            if (segment.isBlank() || current.getChildren() == null) return null;
            ApiParameter next = null;
            for (ApiParameter child : current.getChildren()) {
                if (child != null && Objects.equals(child.getName(), segment)) {
                    next = child;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
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
     * 构建 multipart/form-data 请求体（用于文件上传场景）
     *
     * <p>遍历接口参数：
     * <ul>
     *   <li>文件参数（{@link ApiParameter#isFile()}）：从 paramValues 读取文件路径，读取文件字节作为二进制部分</li>
     *   <li>普通表单参数（FORM / BODY）：作为文本字段</li>
     * </ul>
     * 路径参数和查询参数已拼接到 URL，不进入请求体。
     *
     * @return MultipartBody 包含字节数组、boundary 和可读摘要
     */
    private MultipartBody buildMultipartBody(ApiDefinition api, Map<String, String> paramValues,
                                             Environment env) {
        String boundary = "----RestAutoLabBoundary" + System.currentTimeMillis();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> summaryParts = new ArrayList<>();
        String CRLF = "\r\n";

        try {
            for (ApiParameter param : api.getParameters()) {
                ParameterLocation loc = param.getLocation();
                if (loc == ParameterLocation.PATH || loc == ParameterLocation.QUERY) continue;

                String name = param.getName();
                String value = paramValues.getOrDefault(name, param.generateDefaultValue());
                value = resolveEnvVars(value, env);

                if (param.isFile()) {
                    if (value == null || value.isBlank() || !Files.exists(Paths.get(value))) {
                        summaryParts.add(name + "=[文件未提供: " + value + "]");
                        continue;
                    }
                    Path filePath = Paths.get(value);
                    byte[] fileBytes = Files.readAllBytes(filePath);
                    String fileName = filePath.getFileName().toString();

                    out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                            + fileName + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: application/octet-stream" + CRLF).getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF.getBytes(StandardCharsets.UTF_8));
                    out.write(fileBytes);
                    out.write(CRLF.getBytes(StandardCharsets.UTF_8));

                    summaryParts.add(name + "=@" + fileName + " (" + fileBytes.length + " bytes)");
                } else {
                    out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF.getBytes(StandardCharsets.UTF_8));
                    out.write(value.getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF.getBytes(StandardCharsets.UTF_8));
                    summaryParts.add(name + "=" + value);
                }
            }
            // 结束边界
            out.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("构建multipart请求体失败: " + e.getMessage());
        }

        MultipartBody result = new MultipartBody();
        result.boundary = boundary;
        result.bytes = out.toByteArray();
        result.summary = String.join("; ", summaryParts);
        return result;
    }

    /** multipart/form-data 请求体的载体（含二进制文件内容，必须用字节数组传输） */
    private static class MultipartBody {
        /** 请求体字节数组 */
        byte[] bytes;
        /** boundary 分隔符（需在 Content-Type 头中回传给服务端） */
        String boundary;
        /** 用于历史记录展示的可读摘要 */
        String summary;
    }

    /**
     * 构建HttpRequest对象
     *
     * @param multipartBytes multipart/form-data 请求体字节数组（非null时优先于 body 字符串使用，保证二进制安全）
     */
    private HttpRequest buildHttpRequest(String method, String url, String body, byte[] multipartBytes,
                                          String contentType, ApiDefinition api,
                                          Map<String, String> extraHeaders, Environment env) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS));

        // 设置默认请求头
        builder.header(RestAutoLabConstants.HEADER_CONTENT_TYPE, contentType);
        builder.header(RestAutoLabConstants.HEADER_ACCEPT, api.getProduces());

        // 设置API定义中的自定义请求头（支持环境变量替换）
        api.getHeaders().forEach((k, v) -> builder.header(k, resolveEnvVars(v, env)));

        // 设置额外的请求头（优先级最高，支持环境变量替换）
        extraHeaders.forEach((k, v) -> builder.header(k, resolveEnvVars(v, env)));

        // 根据请求体类型选择 BodyPublisher：
        //   1. multipart 字节数组（文件上传场景，二进制安全，不能用 String 传输）
        //   2. body 字符串（JSON / form-urlencoded / RAW）
        //   3. 空请求体
        HttpRequest.BodyPublisher bodyPublisher;
        if (multipartBytes != null) {
            bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(multipartBytes);
        } else if (body != null) {
            bodyPublisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        switch (method.toUpperCase()) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(bodyPublisher);
            case "PUT" -> builder.PUT(bodyPublisher);
            case "DELETE" -> builder.method("DELETE", bodyPublisher);
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