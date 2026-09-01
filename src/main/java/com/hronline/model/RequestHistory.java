package com.hronline.model;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

/**
 * 请求历史记录
 */
public class RequestHistory {
    private String id = String.valueOf(System.currentTimeMillis());
    private long timestamp = System.currentTimeMillis();
    private String method = "";
    private String url = "";
    /** 接口稳定键（ApiDefinition.uniqueKey()）— 用于在历史中精确找回该接口的测试记录 */
    private String apiKey = "";
    private Map<String, String> headers = Collections.emptyMap();
    /** HTTP 响应头快照；旧版本历史数据没有该字段时按空集合兼容。 */
    private Map<String, String> responseHeaders = Collections.emptyMap();
    /** 请求执行时的参数快照（路径 / 查询 / Header / Body 参数） */
    private Map<String, String> requestParameters = Collections.emptyMap();
    private String requestBody = "";
    private int statusCode = 0;
    private String responseBody = "";
    /** 请求执行异常描述（例如 JSON 解析异常）；正常响应时为空。 */
    private String errorMessage = "";
    private long durationMs = 0;
    private String apiName = "";

    public RequestHistory() {}

    public RequestHistory(String method, String url, Map<String, String> headers, String requestBody,
                           int statusCode, String responseBody, long durationMs, String apiName) {
        this(method, url, "", headers, requestBody, statusCode, responseBody, durationMs, apiName);
    }

    public RequestHistory(String method, String url, String apiKey, Map<String, String> headers, String requestBody,
                           int statusCode, String responseBody, long durationMs, String apiName) {
        this(method, url, apiKey, headers, Collections.emptyMap(), requestBody,
                statusCode, responseBody, durationMs, apiName);
    }

    public RequestHistory(String method, String url, String apiKey, Map<String, String> headers,
                          Map<String, String> requestParameters, String requestBody,
                          int statusCode, String responseBody, long durationMs, String apiName) {
        this.id = String.valueOf(System.currentTimeMillis());
        this.method = method != null ? method : "";
        this.url = url != null ? url : "";
        this.apiKey = apiKey != null ? apiKey : "";
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.requestParameters = requestParameters != null ? requestParameters : Collections.emptyMap();
        this.requestBody = requestBody != null ? requestBody : "";
        this.statusCode = statusCode;
        this.responseBody = responseBody != null ? responseBody : "";
        this.durationMs = durationMs;
        this.apiName = apiName != null ? apiName : "";
    }

    public String timeDisplay() {
        return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    public String summary() {
        return "[" + method + "] " + statusCode + " - " +
                (url.length() > 40 ? url.substring(url.length() - 40) : url) + " (" + durationMs + "ms)";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    /** 请求头语义别名，保留 getHeaders 兼容已有导出代码。 */
    public Map<String, String> getRequestHeaders() { return getHeaders(); }
    public void setRequestHeaders(Map<String, String> requestHeaders) { setHeaders(requestHeaders); }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) {
        this.responseHeaders = responseHeaders != null ? responseHeaders : Collections.emptyMap();
    }
    public Map<String, String> getRequestParameters() { return requestParameters; }
    public void setRequestParameters(Map<String, String> requestParameters) {
        this.requestParameters = requestParameters != null ? requestParameters : Collections.emptyMap();
    }
    /** 兼容调用方的简写别名。 */
    public Map<String, String> getParameters() { return getRequestParameters(); }
    public void setParameters(Map<String, String> parameters) { setRequestParameters(parameters); }
    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null ? errorMessage : "";
    }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getApiName() { return apiName; }
    public void setApiName(String apiName) { this.apiName = apiName; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
