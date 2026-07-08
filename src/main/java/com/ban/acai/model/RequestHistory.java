package com.ban.acai.model;

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
    private Map<String, String> headers = Collections.emptyMap();
    private String requestBody = "";
    private int statusCode = 0;
    private String responseBody = "";
    private long durationMs = 0;
    private String apiName = "";

    public RequestHistory() {}

    public RequestHistory(String method, String url, Map<String, String> headers, String requestBody,
                           int statusCode, String responseBody, long durationMs, String apiName) {
        this.id = String.valueOf(System.currentTimeMillis());
        this.method = method != null ? method : "";
        this.url = url != null ? url : "";
        this.headers = headers != null ? headers : Collections.emptyMap();
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
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getApiName() { return apiName; }
    public void setApiName(String apiName) { this.apiName = apiName; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
