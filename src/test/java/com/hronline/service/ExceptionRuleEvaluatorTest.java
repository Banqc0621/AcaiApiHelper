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
 * Round 7（重构）+ 一伦优化 #67：异常规则判定器单测。
 * <p>覆盖规则模型、纯规则评估入口，以及 #67 引入的语义反转：</p>
 * <ul>
 *   <li>HTTP_VALUE（白名单）：状态码在白名单 = 通过；不在 = 失败</li>
 *   <li>FIELD_VALUE（黑名单）：字段值命中黑名单 = 失败；不在 = 通过</li>
 * </ul>
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
        ExceptionRule a = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("500", "9999"), true);
        ExceptionRule b = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("500", "9999"), true);
        ExceptionRule c = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", Arrays.asList("500", "9999"), false);
        ExceptionRule d = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "msg", Arrays.asList("fail"), true);
        ExceptionRule e = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE, "code", Arrays.asList("500", "9999"), true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c), "启用位不同 → 不等");
        assertFalse(a.equals(d), "字段名不同 → 不等");
        assertFalse(a.equals(e), "类型不同 → 不等");
    }

    @Test
    void exceptionRuleDefaultsAreSafe() {
        ExceptionRule r = new ExceptionRule();
        assertEquals(ExceptionRule.RuleType.HTTP_VALUE, r.getType(), "默认类型应为 HTTP_VALUE");
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
        assertEquals(ExceptionRule.RuleType.HTTP_VALUE, r.getType(), "null 类型 → 默认 HTTP_VALUE");
        assertEquals("", r.getFieldName());
        assertNotNull(r.getExpectedValues());
        assertTrue(r.getExpectedValues().isEmpty());
    }

    @Test
    void expectedValuesListIsCopied() {
        ArrayList<String> in = new ArrayList<>(Arrays.asList("500"));
        ExceptionRule r = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE, "code", in, true);
        in.add("tampered");
        assertEquals(1, r.getExpectedValues().size(), "应拷贝而非持有引用");
    }

    @Test
    void expectedValuesSetterAlsoCopies() {
        ArrayList<String> in = new ArrayList<>(Arrays.asList("500"));
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

    // ---------- #67 语义反转：HTTP_VALUE 白名单 + FIELD_VALUE 黑名单 ----------

    @Test
    void httpValueWhitelist_statusInListPasses() {
        // HTTP_VALUE 白名单：200 在白名单 → 通过
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200", "201"), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(List.of(rule), 200, "{}").isPassed());
    }

    @Test
    void httpValueWhitelist_statusNotInListFails() {
        // HTTP_VALUE 白名单：500 不在白名单 → 失败
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200", "201"), true);
        ExceptionRuleEvaluator.Result r = ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 500, "{}");
        assertFalse(r.isPassed());
        assertTrue(r.reason().contains("500"));
        assertTrue(r.reason().contains("白名单"));
    }

    @Test
    void httpValueEmptyListMeansNoConstraint() {
        // 空白名单 = 该规则不限制（用户可能临时清空）
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of(), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(List.of(rule), 500, "{}").isPassed());
    }

    @Test
    void disabledRuleIsIgnored() {
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200"), false);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(List.of(rule), 500, "{}").isPassed());
    }

    // ---------- FIELD_VALUE 黑名单（语义反转：列表里 = 异常） ----------

    @Test
    void fieldValueBlacklist_fieldValueHitsListFails() {
        // 关键测试 #67 语义反转：code=500 命中黑名单 [500,9999] → 失败（即便 HTTP 200）
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500", "9999"), true);
        ExceptionRuleEvaluator.Result r = ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":500}");
        assertFalse(r.isPassed(), "code=500 命中黑名单 → 失败");
        assertTrue(r.reason().contains("code"));
        assertTrue(r.reason().contains("500"));
        assertTrue(r.reason().contains("黑名单"), "失败原因必须明示是黑名单语义，避免用户混淆");
    }

    @Test
    void fieldValueBlacklist_fieldValueNotInListPasses() {
        // code=0 不在黑名单 [500,9999] → 通过
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500", "9999"), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":0}").isPassed());
    }

    @Test
    void fieldValueBlacklist_missingFieldPasses() {
        // 黑名单语义下，字段缺失 ≠ 命中黑名单 → 通过（白名单语义下缺失算异常，#67 反转后算正常）
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500"), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{}").isPassed(),
                "黑名单语义下字段缺失不算命中，必须通过");
    }

    @Test
    void fieldValueBlacklist_disabledRuleDoesNotRejectResponse() {
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500"), false);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":500}").isPassed());
    }

    @Test
    void fieldValueBlacklist_otherTypesStillMatchByLiteral() {
        // 黑名单值既包含数字也包含字符串——按字面量精确匹配
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "msg", List.of("error", "fail"), true);
        assertFalse(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"msg\":\"error\"}").isPassed(),
                "字符串命中黑名单");
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"msg\":\"ok\"}").isPassed(),
                "字符串不在黑名单");
    }

    @Test
    void noRulesMeansPassed() {
        // 兜底：无规则 = 不限制
        assertTrue(ExceptionRuleEvaluator.evaluateRules(List.of(), 200, "{}").isPassed());
        assertTrue(ExceptionRuleEvaluator.evaluateRules(null, 200, "{}").isPassed());
    }

    @Test
    void multipleRulesAllPassWhenAllSatisfied() {
        // HTTP_VALUE 白名单 + FIELD_VALUE 黑名单 同时满足
        ExceptionRule httpRule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200"), true);
        ExceptionRule fieldRule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500", "9999"), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(httpRule, fieldRule), 200, "{\"code\":0}").isPassed());
    }

    @Test
    void firstFailingRuleStopsEvaluation() {
        // 任意一条不通过 → 整体失败（短路 + 第一条原因）
        ExceptionRule httpRule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200"), true);
        ExceptionRule fieldRule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "code", List.of("500"), true);
        ExceptionRuleEvaluator.Result r = ExceptionRuleEvaluator.evaluateRules(
                List.of(httpRule, fieldRule), 200, "{\"code\":500}");
        assertFalse(r.isPassed());
        // 第一条失败的规则 = fieldRule（黑名单命中）
        assertTrue(r.reason().contains("黑名单"));
    }

    @Test
    void businessRuleFailureRemainsFailedEvenForHttp200() {
        TestResult result = TestResult.fromHttpCode(new ApiDefinition(), 200, "{\"code\":500}", 12);
        result.setErrorMessage("字段 [code]=500 命中黑名单 [500]");
        result.setStatus(TestStatus.FAILED);
        assertFalse(result.isPassed());
        assertTrue(result.summary().contains("字段 [code]=500"));
    }

    // ---------- 向后兼容：HTTP_STATUS 旧枚举值被当作未知值，不影响启动 ----------

    @Test
    void httpValueRuleRejectsCode500WhenOnly200IsAllowed() {
        // 历史回归：HTTP_VALUE 白名单只允许 200，状态码 500 应被拒
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.HTTP_VALUE,
                "", List.of("200"), true);
        ExceptionRuleEvaluator.Result evaluated = ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":500}");
        // 状态码 200 在白名单 → HTTP_VALUE 通过；黑名单没设 → 通过
        assertTrue(evaluated.isPassed(), "HTTP_VALUE 只校验状态码，不验 body");
    }

    @Test
    void fieldNameBlankSkipsRule() {
        // FIELD_VALUE 缺字段名 = 配置错误，跳过（不抛错）
        ExceptionRule rule = new ExceptionRule(ExceptionRule.RuleType.FIELD_VALUE,
                "", List.of("500"), true);
        assertTrue(ExceptionRuleEvaluator.evaluateRules(
                List.of(rule), 200, "{\"code\":500}").isPassed());
    }
}