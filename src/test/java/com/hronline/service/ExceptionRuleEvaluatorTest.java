package com.hronline.service;

import com.hronline.model.ExceptionRule;
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
 * <p>验证 {@link ExceptionRule} 模型本身的语义；evaluator 内部访问
 * {@code RestAutoLabSettingsState}，需要 Project，测试环境不便构造（参考旧测试的注释），
 * 这里只验证 model 的关键不变量。</p>
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
}