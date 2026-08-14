package com.hronline.http;

import com.hronline.model.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全的接口级前置配置处理器。
 *
 * <p>前置脚本采用受限 DSL，避免在 IDE 进程内执行任意代码：</p>
 * <ul>
 *   <li>{@code set name=value}：设置本次请求变量</li>
 *   <li>{@code param name=value}：覆盖本次请求参数</li>
 *   <li>{@code header name=value}：覆盖本次请求头</li>
 * </ul>
 * <p>空行以及 {@code #}/{@code //} 开头的行会被忽略。</p>
 */
public final class PreRequestProcessor {

    private PreRequestProcessor() {}

    public static Result apply(String script, Map<String, String> variableOverrides,
                               Map<String, String> params, Map<String, String> headers,
                               Environment baseEnvironment) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (variableOverrides != null) variables.putAll(variableOverrides);
        Map<String, String> effectiveParams = new LinkedHashMap<>();
        if (params != null) effectiveParams.putAll(params);
        Map<String, String> effectiveHeaders = new LinkedHashMap<>();
        if (headers != null) effectiveHeaders.putAll(headers);

        if (script != null && !script.isBlank()) {
            String[] lines = script.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                int space = line.indexOf(' ');
                if (space <= 0 || space == line.length() - 1) {
                    throw new IllegalArgumentException("前置脚本第 " + (i + 1) + " 行格式错误");
                }
                String command = line.substring(0, space).trim().toLowerCase(java.util.Locale.ROOT);
                String assignment = line.substring(space + 1).trim();
                int equals = assignment.indexOf('=');
                if (equals <= 0) {
                    throw new IllegalArgumentException("前置脚本第 " + (i + 1) + " 行缺少 name=value");
                }
                String name = assignment.substring(0, equals).trim();
                String value = assignment.substring(equals + 1).trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("前置脚本第 " + (i + 1) + " 行名称不能为空");
                }
                switch (command) {
                    case "set" -> variables.put(name, value);
                    case "param" -> effectiveParams.put(name, value);
                    case "header" -> effectiveHeaders.put(name, value);
                    default -> throw new IllegalArgumentException(
                            "前置脚本第 " + (i + 1) + " 行命令不支持：" + command);
                }
            }
        }

        Environment effectiveEnvironment = copyEnvironment(baseEnvironment);
        effectiveEnvironment.getVariables().putAll(variables);
        effectiveParams.replaceAll((key, value) -> effectiveEnvironment.resolveVariables(value));
        effectiveHeaders.replaceAll((key, value) -> effectiveEnvironment.resolveVariables(value));
        return new Result(effectiveEnvironment, effectiveParams, effectiveHeaders);
    }

    private static Environment copyEnvironment(Environment source) {
        Environment copy = new Environment();
        if (source == null) return copy;
        copy.setName(source.getName());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setDescription(source.getDescription());
        copy.setActive(source.isActive());
        copy.setVariables(new LinkedHashMap<>(source.getVariables()));
        copy.setGlobalHeaders(new LinkedHashMap<>(source.getGlobalHeaders()));
        return copy;
    }

    public static final class Result {
        private final Environment environment;
        private final Map<String, String> params;
        private final Map<String, String> headers;

        private Result(Environment environment, Map<String, String> params, Map<String, String> headers) {
            this.environment = environment;
            this.params = params;
            this.headers = headers;
        }

        public Environment getEnvironment() { return environment; }
        public Map<String, String> getParams() { return params; }
        public Map<String, String> getHeaders() { return headers; }
    }
}
