package com.hronline.service;

import com.hronline.model.ExceptionRule;
import com.hronline.model.ApiDefinition;
import com.hronline.model.TestResult;
import com.hronline.model.TestStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 7（重构）：异常规则判定器单测。
 * <p>同时覆盖规则模型、纯规则评估入口以及 HTTP 200 下的业务字段异常，
 * 确保自定义规则失败能沿测试结果链路传递到 UI。</p>
 */
class ExceptionRuleEvaluatorTest {

    @Test
    void resultPassedAndFailedBasics() {
        ExceptionRuleEvaluator.Result ok = ExceptionRuleEvaluator.Result.passed();
        assertTrue(ok.isPassed());
        assertNull(ok.reason());

        ExceptionRuleEvaluator.Result bad = ExceptionRuleEvaluator.Result.failed("状态码不在白名单");
        assertFalse(bad.isPassed());
        assertEquals("状态码不在白名单", bad.reason());
    }

    @Test
    void exceptionRuleEqualsAndHashCode() {
        ExceptionRule a = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("0", "200"), true);
        ExceptionRule b = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("0", "200"), true);
        ExceptionRule c = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("0", "200"), false);
        ExceptionRule d = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "msg", Arrays.asList("ok"), true);
        ExceptionRule e = new ExceptionRule(ExceptionRule.RuleType.HTTP_STATUS, "code", Arrays.asList("0", "200"), true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c), "启用位不同 → 不等");
        assertFalse(a.equals(d), "字段名不同 → 不等");
        assertFalse(a.equals(e), "类型不同 → 不等");
    }

    @Test
    void exceptionRuleDefaultsAreSafe() {
        ExceptionRule r = new ExceptionRule();
        assertEquals(ExceptionRule.RuleType.HTTP_STATUS, r.getType(), "默认类型应为 HTTP_STATUS");
        assertEquals("", r.getFieldName());
        assertNotNull(r.getExpectedValues());
        assertTrue(r.getExpectedValues().isEmpty());
        assertTrue(r.isEnabled(), "默认启用");
    }

    @Test
    void exceptionRuleNullInputsAreDefensive() {
        ExceptionRule r = new ExceptionRule(null, null, null, true);
        r.setType(null);
        r.setFieldName(null);
        r.setExpectedValues(null);
        assertEquals(ExceptionRule.RuleType.HTTP_STATUS, r.getType(), "null 类型 → 默认 HTTP_STATUS");
        assertEquals("", r.getFieldName());
        assertNotNull(r.getExpectedValues());
        assertTrue(r.getExpectedValues().isEmpty());
    }

    @Test
    void expectedValuesListIsCopied() {
        ArrayList<String> in = new ArrayList<>(Arrays.asList("0"));
        ExceptionRule r = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", in, true);
        in.add("tampered");
        assertEquals(1, r.getExpectedValues().size(), "应拷贝而非持有引用");
    }

    @Test
    void expectedValuesSetterAlsoCopies() {
        ArrayList<String> in = new ArrayList<>(Arrays.asList("0"));
        ExceptionRule r = new ExceptionRule();
        r.setExpectedValues(in);
        in.clear();
        assertEquals(1, r.getExpectedValues().size(), "setter 也应拷贝");
    }

    // ---------- R7 完善：evaluator 内部取值 / 匹配逻辑 ----------

    @Test
    void extractActualValue_handlesAllPrimitiveTypes() {
        // string
        assertEquals("ok", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("\"ok\""), "k"));
        // boolean
        assertEquals("true", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("true"), "k"));
        assertEquals("false", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("false"), "k"));
        // number（保留原始字面量）
        assertEquals("200", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("200"), "k"));
        assertEquals("0", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("0"), "k"));
        // null → null（表示字段缺失）
        assertNull(ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("null"), "k"));
        // 缺失 → null
        assertNull(ExceptionRuleEvaluator.extractActualValue(null, "k"));
        // object → toString（不进白名单基本算异常）
        assertEquals("{\"a\":1}", ExceptionRuleEvaluator.extractActualValue(
                com.google.gson.JsonParser.parseString("{\"a\":1}"), "k"));
    }

    @Test
    void businessRuleFailureRemainsFailedEvenForHttp200() {
        TestResult result = TestResult.fromHttpCode(new ApiDefinition(), 200, "{\"code\":500}", 12);
        result.setErrorMessage("字段 [code]=500 不在白名单 [200] 中");
        result.setStatus(TestStatus.FAILED);
        assertFalse(result.isPassed());
        assertTrue(result.summary().contains("字段 [code]=500"));
    }

    @Test
    void fieldValueRuleRejectsCode500WhenOnly200IsAllowed() {
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("200"), true);
        ExceptionRuleEvaluator.Result evaluated = ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":500}");
        assertFalse(evaluated.isPassed());
        assertTrue(evaluated.reason().toLowerCase().contains("code"));
        assertTrue(evaluated.reason().contains("500"));
    }

    @Test
    void disabledFieldValueRuleDoesNotRejectResponse() {
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("200"), false);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(List.of(rule), 200,
                "{\"code\":500}").isPassed());
    }
}
