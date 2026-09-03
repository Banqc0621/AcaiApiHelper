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
 *   <li>{@link ExceptionRule.RuleType#HTTP_VALUE}：HTTP 状态码白名单 —— 落在白名单 = 正常，
 *       不在白名单 = 异常。</li>
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
     * @param statusCode   HTTP 响应状态码（{@code HTTP_VALUE} 规则会用）
     * @param responseBody 响应 body（{@code FIELD_VALUE} 规则会用，可为 null）
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
    static Result evaluateRules(List<ExceptionRule> rules, int statusCode, @Nullable String responseBody) {
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
                    // 白名单语义：值在白名单 = 正常；不在白名单 = 异常
                    String actual = String.valueOf(statusCode);
                    if (!contains(expected, actual)) {
                        return Result.failed("HTTP 状态码 [" + actual + "] 不在白名单 " + expected + " 中");
                    }
                    break;
                }
                case FIELD_VALUE: {
                    // 黑名单语义（#67）：值在黑名单 = 异常；不在黑名单 = 正常
                    String fname = r.getFieldName();
                    if (fname == null || fname.isBlank()) continue;
                    if (parsed == null || !parsed.isJsonObject()) {
                        return Result.failed("接口响应不是 JSON 对象，规则 [字段=" + fname + "] 无法校验");
                    }
                    com.google.gson.JsonElement val = parsed.getAsJsonObject().get(fname);
                    String actual = extractActualValue(val, fname);
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
            if (s.equals(key)) return true;
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
