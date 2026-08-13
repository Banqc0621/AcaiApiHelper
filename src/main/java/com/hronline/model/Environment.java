package com.hronline.model;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 环境配置模型 - 管理不同部署环境的BaseURL、全局Header、环境变量
 *
 * v3 改进:
 * - 内置开发/测试/生产默认环境
 * - 支持 {{variableName}} 语法的变量替换（递归解析）
 * - 环境级全局Header
 */
public class Environment {
    private String name = "";
    private String baseUrl = "";
    private Map<String, String> variables = new LinkedHashMap<>();
    private Map<String, String> globalHeaders = new LinkedHashMap<>();
    private boolean active = false;
    private String description = "";

    /** 变量替换正则：{{varName}} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    public Environment() {}

    public Environment(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
    }

    // ================================================================
    // 静态工厂 - 默认环境
    // ================================================================

    public static Environment dev() {
        Environment env = new Environment("开发环境", "http://localhost:8080");
        env.setDescription("本地开发环境");
        env.getVariables().put("token", "dev-token-xxx");
        env.getGlobalHeaders().put("Authorization", "Bearer {{token}}");
        return env;
    }

    public static Environment test() {
        Environment env = new Environment("测试环境", "http://test.example.com");
        env.setDescription("测试/SIT环境");
        env.getVariables().put("token", "test-token-xxx");
        env.getGlobalHeaders().put("Authorization", "Bearer {{token}}");
        return env;
    }

    public static Environment production() {
        Environment env = new Environment("生产环境", "https://api.example.com");
        env.setDescription("生产环境");
        env.getVariables().put("token", "");
        env.getGlobalHeaders().put("Authorization", "Bearer {{token}}");
        return env;
    }

    // ================================================================
    // 变量解析
    // ================================================================

    /**
     * 递归解析文本中的 {{varName}} 变量
     * 支持变量间引用（最多5层递归防止死循环）
     */
    public String resolveVariables(String text) {
        if (text == null || text.isEmpty()) return text;
        return resolveVariables(text, 0);
    }

    private String resolveVariables(String text, int depth) {
        if (depth > 5) return text; // 防止无限递归
        Matcher matcher = VAR_PATTERN.matcher(text);
        if (!matcher.find()) return text;

        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        matcher.reset();

        while (matcher.find()) {
            sb.append(text, lastEnd, matcher.start());
            String varName = matcher.group(1).trim();
            String value = variables.getOrDefault(varName, "");
            // 递归解析值（变量可能引用其他变量）
            value = resolveVariables(value, depth + 1);
            sb.append(value);
            lastEnd = matcher.end();
        }
        sb.append(text, lastEnd, text.length());
        return sb.toString();
    }

    /**
     * 将环境变量和全局Header合并到参数Map
     */
    public Map<String, String> mergeHeaders(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(globalHeaders);
        if (extra != null) {
            merged.putAll(extra);
        }
        // 解析变量
        Map<String, String> resolved = new LinkedHashMap<>();
        merged.forEach((k, v) -> resolved.put(k, resolveVariables(v)));
        return resolved;
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }

    public Map<String, String> getGlobalHeaders() { return globalHeaders; }
    public void setGlobalHeaders(Map<String, String> globalHeaders) { this.globalHeaders = globalHeaders; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Environment that = (Environment) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}