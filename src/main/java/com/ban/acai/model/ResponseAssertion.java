package com.ban.acai.model;

import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.*;

/**
 * 响应断言配置
 *
 * v3改进:
 * - 支持字符串类型名称（兼容AI生成）
 * - 支持操作符（EQUALS/NOT_EQUALS/CONTAINS等）
 */
public class ResponseAssertion {

    public enum AssertionType {
        STATUS_CODE_EQUALS("状态码等于"),
        STATUS_CODE_IN("状态码属于集合"),
        RESPONSE_TIME_LESS_THAN("响应时间小于(ms)"),
        BODY_CONTAINS("响应体包含文本"),
        BODY_NOT_CONTAINS("响应体不包含文本"),
        JSON_FIELD_EXISTS("JSON字段存在"),
        JSON_FIELD_EQUALS("JSON字段等于"),
        HEADER_EXISTS("响应头存在"),
        HEADER_EQUALS("响应头等于");

        private final String displayName;
        AssertionType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }

        public static AssertionType fromString(String name) {
            if (name == null) return STATUS_CODE_EQUALS;
            String upper = name.toUpperCase().replace(" ", "_");
            try {
                return valueOf(upper);
            } catch (IllegalArgumentException e) {
                // Map common string names
                return switch (upper) {
                    case "STATUS_CODE", "STATUS" -> STATUS_CODE_EQUALS;
                    case "RESPONSE_TIME", "TIME" -> RESPONSE_TIME_LESS_THAN;
                    case "BODY_CONTAINS" -> BODY_CONTAINS;
                    case "HEADER_EXISTS" -> HEADER_EXISTS;
                    case "JSON_PATH", "JSON_FIELD" -> JSON_FIELD_EXISTS;
                    default -> STATUS_CODE_EQUALS;
                };
            }
        }
    }

    private AssertionType type = AssertionType.STATUS_CODE_EQUALS;
    private String target = "";
    private String expected = "200";
    private String operator = "EQUALS";
    private String actual = "";
    private boolean passed = false;
    private String message = "";

    public ResponseAssertion() {}

    public boolean check(int statusCode, String responseBody, Map<String, String> responseHeaders, long durationMs) {
        message = "";
        try {
            switch (type) {
                case STATUS_CODE_EQUALS -> {
                    actual = String.valueOf(statusCode);
                    passed = compareOp(String.valueOf(statusCode), expected, operator);
                }
                case STATUS_CODE_IN -> {
                    actual = String.valueOf(statusCode);
                    Set<String> allowed = new HashSet<>();
                    for (String s : expected.split(",")) allowed.add(s.trim());
                    passed = allowed.contains(actual);
                }
                case RESPONSE_TIME_LESS_THAN -> {
                    actual = durationMs + "ms";
                    try {
                        long expectedMs = Long.parseLong(expected.trim());
                        passed = durationMs < expectedMs;
                    } catch (NumberFormatException e) {
                        passed = false;
                        message = "期望值格式错误: " + expected;
                    }
                }
                case BODY_CONTAINS -> {
                    actual = responseBody != null && responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..." : (responseBody != null ? responseBody : "");
                    passed = responseBody != null && responseBody.contains(expected);
                    if (!passed) message = "响应体不包含: " + expected;
                }
                case BODY_NOT_CONTAINS -> {
                    actual = responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 200)) : "";
                    passed = responseBody == null || !responseBody.contains(expected);
                }
                case JSON_FIELD_EXISTS -> {
                    actual = extractJsonValue(responseBody, target);
                    passed = actual != null && !actual.equals("null") && !actual.isEmpty();
                    if (!passed) message = "JSON路径不存在: " + target;
                }
                case JSON_FIELD_EQUALS -> {
                    actual = extractJsonValue(responseBody, target);
                    passed = expected.equals(actual);
                    if (!passed) message = "期望 '" + expected + "' 实际 '" + actual + "'";
                }
                case HEADER_EXISTS -> {
                    if (responseHeaders != null) {
                        actual = responseHeaders.getOrDefault(target.toLowerCase(),
                                responseHeaders.getOrDefault(target, "(不存在)"));
                        passed = responseHeaders.containsKey(target.toLowerCase())
                                || responseHeaders.containsKey(target);
                    } else {
                        passed = false;
                    }
                    if (!passed) message = "响应头不存在: " + target;
                }
                case HEADER_EQUALS -> {
                    if (responseHeaders != null) {
                        actual = responseHeaders.getOrDefault(target.toLowerCase(),
                                responseHeaders.getOrDefault(target, "(不存在)"));
                        passed = expected.equals(actual);
                    } else {
                        passed = false;
                    }
                }
            }
        } catch (Exception e) {
            passed = false;
            message = "断言执行异常: " + e.getMessage();
        }
        return passed;
    }

    private boolean compareOp(String actual, String expected, String op) {
        if (op == null) op = "EQUALS";
        return switch (op.toUpperCase()) {
            case "EQUALS" -> expected.equals(actual);
            case "NOT_EQUALS" -> !expected.equals(actual);
            case "CONTAINS" -> actual != null && actual.contains(expected);
            case "NOT_CONTAINS" -> actual == null || !actual.contains(expected);
            case "GREATER_THAN" -> compareNumbers(actual, expected) > 0;
            case "LESS_THAN" -> compareNumbers(actual, expected) < 0;
            case "EXISTS" -> actual != null && !actual.isEmpty();
            case "NOT_EXISTS" -> actual == null || actual.isEmpty();
            default -> expected.equals(actual);
        };
    }

    private int compareNumbers(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    public static String extractJsonValue(String json, String path) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonElement element = JsonParser.parseString(json);
            String[] parts = path.split("\\.");
            JsonElement current = element;
            for (String key : parts) {
                if (current == null) return null;
                if (key.startsWith("[") && key.endsWith("]")) {
                    int idx = Integer.parseInt(key.substring(1, key.length() - 1));
                    if (current.isJsonArray()) {
                        JsonArray arr = current.getAsJsonArray();
                        current = idx < arr.size() ? arr.get(idx) : null;
                    } else return null;
                } else if (current.isJsonObject()) {
                    current = current.getAsJsonObject().get(key);
                } else if (current.isJsonArray()) {
                    try {
                        int idx = Integer.parseInt(key);
                        JsonArray arr = current.getAsJsonArray();
                        current = idx < arr.size() ? arr.get(idx) : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else return null;
            }
            if (current == null || current.isJsonNull()) return null;
            if (current.isJsonPrimitive()) return current.getAsString();
            return current.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Getters/Setters ──
    public AssertionType getType() { return type; }
    public void setType(AssertionType type) { this.type = type; }
    public void setType(String typeName) { this.type = AssertionType.fromString(typeName); }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getActual() { return actual; }
    public boolean isPassed() { return passed; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return type.getDisplayName() + " " + (target != null ? target + " " : "") + operator + " " + expected;
    }
}
