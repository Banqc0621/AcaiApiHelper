package com.ban.acai.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * API定义数据模型 - 表示一个完整的REST API端点
 *
 * 该类是插件的核心数据结构，承载从源码中解析出的全部接口元信息，
 * 包括HTTP方法、URL路径、参数列表、源码定位等。
 * 被 API扫描器、HTTP执行器、AI参数生成器、UI面板 等模块共同使用。
 */
public class ApiDefinition {

    /** HTTP请求方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS） */
    private String httpMethod = "GET";
    /** 完整的URL路径模板，如 /api/users/{id} */
    private String url = "";
    /** 接口名称，优先取方法名或swagger注解中的summary */
    private String name = "";
    /** 接口描述，取自Javadoc注释或@Api注解 */
    private String description = "";
    /** 所属控制器类名 */
    private String controllerName = "";
    /** 所属模块名称（多模块项目时使用） */
    private String moduleName = "";
    /** 接口参数列表（路径参数、查询参数、请求体等） */
    private List<ApiParameter> parameters = new ArrayList<>();
    /** 自定义请求头列表 */
    private Map<String, String> headers = new HashMap<>();
    /** 请求Content-Type（如 application/json） */
    private String consumes = com.ban.acai.RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    /** 响应Content-Type（如 application/json） */
    private String produces = com.ban.acai.RestAutoLabConstants.DEFAULT_CONTENT_TYPE;
    /** 接口源码文件绝对路径（用于代码跳转） */
    private String sourceFilePath = "";
    /** 接口方法所在行号（用于精确定位） */
    private int sourceLineNumber = 0;
    /** 请求体的Java类型名（如 UserDTO、List<String>） */
    private String requestBodyType = "";
    /** 响应体的Java类型名（如 Result<UserVO>） */
    private String responseBodyType = "";
    /**
     * 响应体出参实体类字段树（扫描器从返回类型中递归解析得到）。
     * <p>对于 <code>Result&lt;UserVO&gt;</code>，这里存的是 <code>UserVO</code> 类的字段列表（嵌套字段放在每个字段的 children 里）。</p>
     * <p>无字段时为空列表（由扫描器未能解析对应类）。导出 Markdown 时优先用这里生成响应参数表与示例。</p>
     */
    private List<ApiParameter> responseSchema = new ArrayList<>();
    /** 接口是否已标记为@Deprecated */
    private boolean isDeprecated = false;
    /** 接口来源：AUTO（自动扫描）/ MANUAL（手动添加） */
    private String source = com.ban.acai.RestAutoLabConstants.API_SOURCE_AUTO;
    /** 扫描/创建时间戳（毫秒） */
    private long scanTimestamp = System.currentTimeMillis();
    /** 预期HTTP状态码集合（为空时默认2xx通过） */
    private java.util.Set<Integer> expectedStatusCodes = new java.util.HashSet<>();
    /** 是否收藏 */
    private boolean starred = false;
    /** 调用次数统计 */
    private int callCount = 0;
    /** 最后调用时间戳 */
    private long lastCalledAt = 0;
    /** 变更标记（用于API变更检测） */
    private String changeMarker = com.ban.acai.RestAutoLabConstants.CHANGE_NONE;
    /** 接口标签列表（用于自定义分组） */
    private java.util.List<String> tags = new java.util.ArrayList<>();

    public ApiDefinition() {}

    // ================================================================
    // Getters & Setters
    // ================================================================

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }

    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }

    public List<ApiParameter> getParameters() { return parameters; }
    public void setParameters(List<ApiParameter> parameters) { this.parameters = parameters; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getConsumes() { return consumes; }
    public void setConsumes(String consumes) { this.consumes = consumes; }

    public String getProduces() { return produces; }
    public void setProduces(String produces) { this.produces = produces; }

    public String getSourceFilePath() { return sourceFilePath; }
    public void setSourceFilePath(String sourceFilePath) { this.sourceFilePath = sourceFilePath; }

    public int getSourceLineNumber() { return sourceLineNumber; }
    public void setSourceLineNumber(int sourceLineNumber) { this.sourceLineNumber = sourceLineNumber; }

    public String getRequestBodyType() { return requestBodyType; }
    public void setRequestBodyType(String requestBodyType) { this.requestBodyType = requestBodyType; }

    public String getResponseBodyType() { return responseBodyType; }
    public void setResponseBodyType(String responseBodyType) { this.responseBodyType = responseBodyType; }

    public List<ApiParameter> getResponseSchema() { return responseSchema; }
    public void setResponseSchema(List<ApiParameter> responseSchema) { this.responseSchema = responseSchema != null ? responseSchema : new ArrayList<>(); }

    public boolean isDeprecated() { return isDeprecated; }
    public void setDeprecated(boolean deprecated) { isDeprecated = deprecated; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public long getScanTimestamp() { return scanTimestamp; }
    public void setScanTimestamp(long scanTimestamp) { this.scanTimestamp = scanTimestamp; }

    public java.util.Set<Integer> getExpectedStatusCodes() { return expectedStatusCodes; }
    public void setExpectedStatusCodes(java.util.Set<Integer> codes) { this.expectedStatusCodes = codes != null ? codes : new java.util.HashSet<>(); }

    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    public int getCallCount() { return callCount; }
    public void setCallCount(int callCount) { this.callCount = callCount; }
    public void incrementCallCount() { this.callCount++; this.lastCalledAt = System.currentTimeMillis(); }

    public long getLastCalledAt() { return lastCalledAt; }
    public void setLastCalledAt(long t) { this.lastCalledAt = t; }

    public String getChangeMarker() { return changeMarker; }
    public void setChangeMarker(String marker) { this.changeMarker = marker != null ? marker : com.ban.acai.RestAutoLabConstants.CHANGE_NONE; }

    public java.util.List<String> getTags() { return tags; }
    public void setTags(java.util.List<String> tags) { this.tags = tags != null ? tags : new java.util.ArrayList<>(); }

    /** 判断是否为自动扫描的接口 */
    public boolean isAutoDetected() {
        return com.ban.acai.RestAutoLabConstants.API_SOURCE_AUTO.equals(source);
    }

    /** 判断状态码是否符合预期（用于测试断言） */
    public boolean isStatusCodeExpected(int code) {
        if (expectedStatusCodes == null || expectedStatusCodes.isEmpty()) {
            return code >= com.ban.acai.RestAutoLabConstants.HTTP_SUCCESS_MIN
                    && code <= com.ban.acai.RestAutoLabConstants.HTTP_SUCCESS_MAX;
        }
        return expectedStatusCodes.contains(code);
    }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 获取唯一的接口标识，用于缓存键和去重
     * 格式: HTTP_METHOD + URL路径
     */
    public String uniqueKey() {
        return httpMethod.toUpperCase() + "|" + url;
    }

    /**
     * 获取用于展示的简短标签
     * 格式: [HTTP_METHOD] URL - 名称
     */
    public String displayLabel() {
        String sourceTag = isAutoDetected() ? "" : " \u270b"; // 手动API加手掌标记
        String methodTag = "[" + httpMethod + "]";
        return (name != null && !name.isBlank())
                ? methodTag + " " + url + " - " + name + sourceTag
                : methodTag + " " + url + sourceTag;
    }

    /**
     * 获取所有路径参数（URL中的 {param} 占位符对应的参数）
     */
    public List<ApiParameter> pathParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.PATH)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有查询参数（URL ? 后面的参数）
     */
    public List<ApiParameter> queryParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.QUERY)
                .collect(Collectors.toList());
    }

    /**
     * 获取请求体参数（@RequestBody标注的参数）
     */
    public List<ApiParameter> bodyParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.BODY)
                .collect(Collectors.toList());
    }

    /**
     * 获取请求头参数（@RequestHeader标注的参数）
     */
    public List<ApiParameter> headerParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.HEADER)
                .collect(Collectors.toList());
    }

    /**
     * 获取Cookie参数（@CookieValue标注的参数）
     */
    public List<ApiParameter> cookieParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.COOKIE)
                .collect(Collectors.toList());
    }

    /**
     * 获取表单参数（@FormParam等FORM位置参数）
     */
    public List<ApiParameter> formParameters() {
        return parameters.stream()
                .filter(p -> p.getLocation() == ParameterLocation.FORM)
                .collect(Collectors.toList());
    }

    /**
     * 将URL中的路径参数替换为实际值
     * @param values 参数名到值的映射
     * @return 替换后的完整URL
     */
    public String resolveUrl(Map<String, String> values) {
        String resolvedUrl = url;
        for (ApiParameter param : pathParameters()) {
            String value = values.getOrDefault(param.getName(), "{" + param.getName() + "}");
            resolvedUrl = resolvedUrl.replace("{" + param.getName() + "}", value);
        }
        return resolvedUrl;
    }

    /**
     * 构建带查询参数的完整URL
     * @param pathValues 路径参数名到值的映射
     * @param queryParams 查询参数名到值的映射
     * @return 带查询字符串的完整URL
     */
    public String buildFullUrl(Map<String, String> pathValues, Map<String, String> queryParams) {
        String baseUrl = resolveUrl(pathValues);
        if (queryParams == null || queryParams.isEmpty()) return baseUrl;
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return baseUrl + "?" + joiner;
    }

    // ================================================================
    // equals / hashCode / toString
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiDefinition that = (ApiDefinition) o;
        return sourceLineNumber == that.sourceLineNumber &&
                isDeprecated == that.isDeprecated &&
                Objects.equals(httpMethod, that.httpMethod) &&
                Objects.equals(url, that.url) &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(controllerName, that.controllerName) &&
                Objects.equals(moduleName, that.moduleName) &&
                Objects.equals(parameters, that.parameters) &&
                Objects.equals(headers, that.headers) &&
                Objects.equals(consumes, that.consumes) &&
                Objects.equals(produces, that.produces) &&
                Objects.equals(sourceFilePath, that.sourceFilePath) &&
                Objects.equals(requestBodyType, that.requestBodyType) &&
                Objects.equals(responseBodyType, that.responseBodyType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(httpMethod, url, name, description, controllerName, moduleName,
                parameters, headers, consumes, produces, sourceFilePath, sourceLineNumber,
                requestBodyType, responseBodyType, isDeprecated);
    }

    @Override
    public String toString() {
        return "ApiDefinition{" +
                "httpMethod='" + httpMethod + '\'' +
                ", url='" + url + '\'' +
                ", name='" + name + '\'' +
                ", controllerName='" + controllerName + '\'' +
                ", parameters=" + parameters.size() +
                '}';
    }
}