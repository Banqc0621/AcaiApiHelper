package com.hronline.service;

import com.hronline.model.ExceptionRule;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Round 7（重构）+ 一伦优化 #67：异常自定义规则判定器。
 * <p>规则对项目内所有接口生效，两种类型语义相反：
 * <ul>
 *   <li>{@link ExceptionRule.RuleType#HTTP_VALUE}：字段名为空时是 HTTP 状态码白名单；填写字段名时是
 *       响应 JSON 字段白名单。落在白名单 = 正常，不在白名单 = 异常。</li>
 *   <li>{@link ExceptionRule.RuleType#FIELD_VALUE}：JSON 字段值黑名单 —— 出现在黑名单 = 异常，
 *       不在黑名单 = 正常。</li>
 * </ul>
 * 任意一条启用的规则不通过 → 整体判 FAILED，携带第一条失败原因到 message。
 * </p>
 */
public final class ExceptionRuleEvaluator {

    private ExceptionRuleEvaluator() {}

    /**
     * 对当前请求跑全局异常规则判定。
     *
     * @param project      当前 project（用于拿到 settings 实例）
     * @param statusCode   HTTP 响应状态码（{@code HTTP_VALUE} 且字段名为空时使用）
     * @param responseBody 响应 body（字段规则使用，可为 null）
     * @return {@link Result#isPassed()} == true 表示规则全部通过；
     *         否则 {@link Result#reason()} 给出第一条失败原因（用于测试结果 message）。
     */
    public static Result evaluate(@NotNull Project project, int statusCode, @Nullable String responseBody) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        List<ExceptionRule> rules = settings.loadExceptionRules();
        return evaluateRules(rules, statusCode, responseBody);
    }

    /**
     * 纯规则评估入口，供无 IntelliJ Project 的单测和批处理调用。
     * 规则语义与 {@link #evaluate(Project, int, String)} 完全一致。
     */
    public static Result evaluateRules(List<ExceptionRule> rules, int statusCode, @Nullable String responseBody) {
        if (rules == null || rules.isEmpty()) return Result.passed();

        // 预解析 body 一次，给 FIELD_VALUE 复用
        com.google.gson.JsonElement parsed = null;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                parsed = com.google.gson.JsonParser.parseString(responseBody);
            } catch (Exception ignored) {
                parsed = null;
            }
        }

        for (ExceptionRule r : rules) {
            if (r == null || !r.isEnabled()) continue;
            List<String> expected = r.getExpectedValues();
            if (expected == null || expected.isEmpty()) continue; // 空集合 = 该条不限制，跳过

            switch (r.getType()) {
                case HTTP_VALUE: {
                    String fname = normalizeFieldName(r.getFieldName());
                    if (fname.isEmpty()) {
                        // 一伦优化 #90：字段名留空 → HTTP 状态码白名单。
                        // 历史 JSON 里可能混入了非整数字面量（如 "SYSTEM_ERROR"），
                        // 评估时只保留能解析为 100-599 整数的项，跳过其他项避免
                        // 「HTTP 状态码 [200] 不在白名单 [SYSTEM_ERROR] 中」这种歧义错误。
                        java.util.List<String> intWhitelist = new java.util.ArrayList<>();
                        for (String v : expected) {
                            try {
                                int code = Integer.parseInt(v.trim());
                                if (code >= 100 && code <= 599) intWhitelist.add(v.trim());
                            } catch (NumberFormatException ignored) {
                                // 跳过非整数字面量（如 "SYSTEM_ERROR"）
                            }
                        }
                        if (intWhitelist.isEmpty()) break; // 空白名单 = 该条规则不限制
                        String actual = String.valueOf(statusCode);
                        if (!contains(intWhitelist, actual)) {
                            return Result.failed("HTTP 状态码 [" + actual + "] 不在白名单 " + intWhitelist + " 中");
                        }
                    } else {
                        // 字段名不为空：按响应 JSON 字段白名单处理。这样 HTTP 200 + code=301
                        // 可以明确表示业务成功，避免把字段规则误拿去和 HTTP 状态码比较。
                        if (parsed == null || !parsed.isJsonObject()) {
                            return Result.failed("接口响应不是 JSON 对象，规则 [字段=" + fname + "] 无法校验");
                        }
                        String actual = extractJsonPathValue(parsed, fname);
                        if (actual == null) {
                            return Result.failed("响应字段 [" + fname + "] 缺失，不在白名单 " + expected + " 中");
                        }
                        if (!contains(expected, actual)) {
                            return Result.failed("响应字段 [" + fname + "]=" + actual
                                    + " 不在白名单 " + expected + " 中");
                        }
                    }
                    break;
                }
                case FIELD_VALUE: {
                    // 黑名单语义（#67）：值在黑名单 = 异常；不在黑名单 = 正常
                    String fname = normalizeFieldName(r.getFieldName());
                    if (fname.isEmpty()) continue;
                    if (parsed == null || !parsed.isJsonObject()) {
                        return Result.failed("接口响应不是 JSON 对象，规则 [字段=" + fname + "] 无法校验");
                    }
                    String actual = extractJsonPathValue(parsed, fname);
                    if (actual == null) {
                        // 字段缺失 = 不在黑名单 = 通过（黑名单语义下缺失值不算命中）
                        break;
                    }
                    if (contains(expected, actual)) {
                        return Result.failed("字段 [" + fname + "]=" + actual + " 命中黑名单 " + expected);
                    }
                    break;
                }
            }
        }
        return Result.passed();
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName == null ? "" : fieldName.trim();
    }

    /** 读取顶层或点号路径字段（如 {@code data.code}），供两类响应字段规则复用。 */
    private static String extractJsonPathValue(com.google.gson.JsonElement parsed, String path) {
        if (parsed == null || path == null || path.isBlank()) return null;
        com.google.gson.JsonElement current = parsed;
        for (String segment : path.split("\\.")) {
            if (segment == null || segment.isBlank() || current == null || !current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(segment);
        }
        return extractActualValue(current, path);
    }

    /**
     * 一伦优化 R7：从 JsonElement 提取判定用的字符串字面量。
     * <ul>
     *   <li>missing / null → 返回 null（调用方当作「字段缺失」）</li>
     *   <li>string → 原样</li>
     *   <li>boolean → "true" / "false"</li>
     *   <li>number → 数字字面量字符串</li>
     *   <li>其他（object/array） → toString()</li>
     * </ul>
     * 拆出来是为了让单测能覆盖关键取值路径。
     */
    static String extractActualValue(com.google.gson.JsonElement val, String fieldName) {
        if (val == null || val.isJsonNull()) return null;
        if (val.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = val.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return String.valueOf(p.getAsBoolean());
            if (p.isNumber()) return p.getAsNumber().toString();
            return p.getAsString();
        }
        return val.toString();
    }

    private static boolean contains(List<String> list, String key) {
        if (list == null) return false;
        for (String s : list) {
            if (s == null) continue;
            if (s.trim().equals(key)) return true;
        }
        return false;
    }

    public static final class Result {
        private final boolean pass;
        private final String reason;
        private Result(boolean pass, String reason) {
            this.pass = pass;
            this.reason = reason;
        }
        public static Result passed() { return new Result(true, null); }
        public static Result failed(String reason) { return new Result(false, reason); }
        public boolean isPassed() { return pass; }
        public String reason() { return reason; }
    }
}
