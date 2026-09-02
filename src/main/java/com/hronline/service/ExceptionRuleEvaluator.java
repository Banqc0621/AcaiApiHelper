package com.hronline.service;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ExceptionRule;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Round 7：异常自定义规则判定器。
 * <p>判定语义：HTTP 2xx 通过后再跑本接口的字段规则。
 * 每条启用的规则检查响应 JSON 第一级：
 * <ul>
 *   <li>字段不存在 → 算异常（提示字段名）</li>
 *   <li>字段值不在 expectedValues 白名单 → 算异常（提示字段名 + 实际值）</li>
 *   <li>expectedValues 为空 → 仅检查字段存在性</li>
 * </ul>
 * 任一规则不通过 → 整体判 FAILED，携带第一条失败原因到 message。
 * </p>
 */
public final class ExceptionRuleEvaluator {

    private ExceptionRuleEvaluator() {}

    /**
     * 对当前 API 跑异常规则判定。
     *
     * @param project  当前 project（用于拿到 settings 实例）
     * @param api      当前接口
     * @param responseBody 响应 body（可为 null）
     * @return {@link Result#passed()} == true 表示规则全部通过；
     *         否则 {@link Result#reason()} 给出第一条失败原因（用于测试结果 message）。
     */
    public static Result evaluate(@NotNull Project project, @Nullable ApiDefinition api, @Nullable String responseBody) {
        if (api == null) return Result.passed();
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        Map<String, List<ExceptionRule>> all = settings.loadExceptionRules();
        List<ExceptionRule> rules = all.get(api.uniqueKey());
        if (rules == null || rules.isEmpty()) return Result.passed();
        // 容错解析：parse 失败等同字段全缺失（任何规则都会失败）
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
            String fname = r.getFieldName();
            if (fname == null || fname.isBlank()) continue;
            if (parsed == null || !parsed.isJsonObject()) {
                return Result.failed("接口响应不是 JSON 对象，规则 [字段=" + fname + "] 无法校验");
            }
            com.google.gson.JsonElement val = parsed.getAsJsonObject().get(fname);
            if (val == null || val.isJsonNull()) {
                return Result.failed("响应缺少字段 [" + fname + "]");
            }
            // 一伦优化 R7 完善：响应字段值类型不止字符串——
            //   number → 保留原始字面量（getAsNumber().toString()）
            //   boolean → 转 "true" / "false"
            //   string → 原样
            //   object/array → 退化为 toString（基本不会进白名单）
            String actual;
            if (val.isJsonPrimitive()) {
                com.google.gson.JsonPrimitive p = val.getAsJsonPrimitive();
                if (p.isString()) {
                    actual = p.getAsString();
                } else if (p.isBoolean()) {
                    actual = String.valueOf(p.getAsBoolean());
                } else if (p.isNumber()) {
                    actual = p.getAsNumber().toString();
                } else {
                    actual = p.getAsString();
                }
            } else {
                actual = val.toString();
            }
            List<String> expected = r.getExpectedValues();
            if (expected == null || expected.isEmpty()) continue; // 仅做存在性检查
            boolean hit = false;
            for (String e : expected) {
                if (e == null) continue;
                if (e.equals(actual)) { hit = true; break; }
            }
            if (!hit) {
                return Result.failed("字段 [" + fname + "]=" + actual + " 不在白名单 " + expected + " 中");
            }
        }
        return Result.passed();
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