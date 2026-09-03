package com.hronline.model;

import com.hronline.RestAutoLabConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次API测试结果数据模型
 *
 * 记录接口测试的完整执行信息，包括请求/响应详情、耗时、状态等。
 * 用于测试报告展示和Git预提交检查判定。
 */
public class TestResult {

    /** 被测试的API定义 */
    private ApiDefinition apiDefinition;
    /** 测试执行状态 */
    private TestStatus status = TestStatus.PENDING;
    /** HTTP响应状态码（如 200、404、500） */
    private int statusCode = 0;
    /** HTTP响应体内容 */
    private String responseBody = "";
    /** HTTP响应头 */
    private Map<String, String> responseHeaders = Collections.emptyMap();
    /** 实际发送的完整请求URL */
    private String requestUrl = "";
    /** 实际发送的请求体 */
    private String requestBody = "";
    /** 实际发送的请求头（含接口、环境及本次请求覆盖值） */
    private Map<String, String> requestHeaders = Collections.emptyMap();
    /** 实际发送的参数（路径 / 查询 / Header / Body 参数的键值快照） */
    private Map<String, String> requestParameters = Collections.emptyMap();
    /** 请求耗时（毫秒） */
    private long durationMs = 0;
    /** 异常信息（仅ERROR状态时有值） */
    private String errorMessage = "";
    /** 测试执行时间戳 */
    private long timestamp = System.currentTimeMillis();
    /** 断言结果列表 */
    private List<ResponseAssertion> assertions = new ArrayList<>();

    public TestResult() {}

    public TestResult(ApiDefinition apiDefinition) {
        this.apiDefinition = apiDefinition;
    }

    public TestResult(ApiDefinition apiDefinition, TestStatus status, int statusCode,
                      String responseBody, Map<String, String> responseHeaders,
                      String requestUrl, String requestBody, long durationMs,
                      String errorMessage, long timestamp) {
        this.apiDefinition = apiDefinition;
        this.status = status;
        this.statusCode = statusCode;
        this.responseBody = responseBody != null ? responseBody : "";
        this.responseHeaders = responseHeaders != null ? responseHeaders : Collections.emptyMap();
        this.requestUrl = requestUrl != null ? requestUrl : "";
        this.requestBody = requestBody != null ? requestBody : "";
        this.durationMs = durationMs;
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.timestamp = timestamp;
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public ApiDefinition getApiDefinition() { return apiDefinition; }
    public void setApiDefinition(ApiDefinition apiDefinition) { this.apiDefinition = apiDefinition; }

    public TestStatus getStatus() { return status; }
    public void setStatus(TestStatus status) { this.status = status; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }

    public String getRequestUrl() { return requestUrl; }
    public void setRequestUrl(String requestUrl) { this.requestUrl = requestUrl; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public Map<String, String> getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders != null ? requestHeaders : Collections.emptyMap();
    }

    public Map<String, String> getRequestParameters() { return requestParameters; }
    public void setRequestParameters(Map<String, String> requestParameters) {
        this.requestParameters = requestParameters != null ? requestParameters : Collections.emptyMap();
    }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public List<ResponseAssertion> getAssertions() { return assertions; }
    public void setAssertions(List<ResponseAssertion> assertions) { this.assertions = assertions != null ? assertions : new ArrayList<>(); }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 判断测试是否通过
     * 通过条件：状态为PASSED
     */
    public boolean isPassed() {
        return status == TestStatus.PASSED;
    }

    /**
     * 判断测试是否失败（状态码非2xx）
     * 此方法被Git预提交钩子使用，用于判定是否阻断提交
     */
    public boolean isFailed() {
        return status == TestStatus.FAILED;
    }

    /**
     * 获取用于展示的简要结果描述
     */
    public String summary() {
        return switch (status) {
            case PASSED -> "✓ " + statusCode + " (" + durationMs + "ms)";
            case FAILED -> "✗ " + statusCode + " (" + durationMs + "ms)"
                    + (errorMessage == null || errorMessage.isBlank() ? "" : " · " + errorMessage);
            case ERROR -> "⚠ " + errorMessage;
            case PENDING -> "○ 未执行";
            case RUNNING -> "◌ 执行中...";
            case CANCELLED -> "⊘ 已取消";
            case SKIPPED -> "⊘ 已跳过";
        };
    }

    // ================================================================
    // 静态工厂方法
    // ================================================================

    /**
     * 根据HTTP状态码创建测试结果
     * 2xx状态码视为通过，其他视为失败
     */
    public static TestResult fromHttpCode(ApiDefinition api, int code, String body, long duration) {
        TestStatus status = (code >= RestAutoLabConstants.HTTP_SUCCESS_MIN
                && code <= RestAutoLabConstants.HTTP_SUCCESS_MAX)
                ? TestStatus.PASSED : TestStatus.FAILED;
        TestResult result = new TestResult(api);
        result.setStatus(status);
        result.setStatusCode(code);
        result.setResponseBody(body);
        result.setDurationMs(duration);
        return result;
    }

    /**
     * 创建异常结果
     */
    public static TestResult fromError(ApiDefinition api, String error) {
        TestResult result = new TestResult(api);
        result.setStatus(TestStatus.ERROR);
        result.setErrorMessage(error);
        return result;
    }

    // ================================================================
    // equals / hashCode / toString
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestResult that = (TestResult) o;
        return statusCode == that.statusCode &&
                durationMs == that.durationMs &&
                timestamp == that.timestamp &&
                status == that.status &&
                Objects.equals(apiDefinition, that.apiDefinition) &&
                Objects.equals(responseBody, that.responseBody) &&
                Objects.equals(requestUrl, that.requestUrl) &&
                Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiDefinition, status, statusCode, responseBody,
                requestUrl, durationMs, errorMessage, timestamp);
    }

    @Override
    public String toString() {
        return "TestResult{" +
                "status=" + status +
                ", statusCode=" + statusCode +
                ", durationMs=" + durationMs +
                ", url='" + (apiDefinition != null ? apiDefinition.getUrl() : "") + '\'' +
                '}';
    }
}
