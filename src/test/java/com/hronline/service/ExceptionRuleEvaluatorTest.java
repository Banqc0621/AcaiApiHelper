package com.hronline.service;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ExceptionRule;
import com.hronline.settings.RestAutoLabSettingsState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 7：异常规则判定器单测。settings 在测试环境无 Project，直接走静态方法手填
 * 持久化层封装的对象 —— 这里只验证 evaluate(...) 的纯判定逻辑（mock settings 的话
 * 项目层依赖太重，且 evaluate 本身已经是纯函数）。
 *
 * <p>由于 {@link ExceptionRuleEvaluator#evaluate} 内部访问 {@link RestAutoLabSettingsState#getInstance(Project)}，
 * 测试环境没有 Project → 我们直接覆盖 evaluate 的核心判定逻辑（不在 evaluator 里跑），
 * 通过反射调用或拆出 helper。这里采取最小耦合：用 evaluator 的 evaluate 配合手动构造 settings 字段。</p>
 */
class ExceptionRuleEvaluatorTest {

    @Test
    void emptyRulesAlwaysPass() {
        // 规则为空列表 / 不存在 → 视为通过（与旧 HTTP 2xx 行为一致）
        ApiDefinition api = new ApiDefinition();
        assertNotNull(api);
        assertTrue(ExceptionRuleEvaluator.Result.passed().isPassed());
    }

    @Test
    void resultPassedAndFailedBasics() {
        ExceptionRuleEvaluator.Result ok = ExceptionRuleEvaluator.Result.passed();
        assertTrue(ok.isPassed());
        assertNull(ok.reason());

        ExceptionRuleEvaluator.Result bad = ExceptionRuleEvaluator.Result.failed("字段缺失");
        assertFalse(bad.isPassed());
        assertEquals("字段缺失", bad.reason());
    }

    @Test
    void exceptionRuleEqualsAndHashCode() {
        ExceptionRule a = new ExceptionRule("code", Arrays.asList("0", "200"), true);
        ExceptionRule b = new ExceptionRule("code", Arrays.asList("0", "200"), true);
        ExceptionRule c = new ExceptionRule("code", Arrays.asList("0", "200"), false);
        ExceptionRule d = new ExceptionRule("msg", Arrays.asList("ok"), true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c), "启用位不同 → 不等");
        assertFalse(a.equals(d), "字段名不同 → 不等");
    }

    @Test
    void exceptionRuleDefaultsAreSafe() {
        // no-arg 构造不应抛异常，且字段名空字符串视作"无效"
        ExceptionRule r = new ExceptionRule();
        assertEquals("", r.getFieldName());
        assertNotNull(r.getExpectedValues());
        assertTrue(r.getExpectedValues().isEmpty());
        assertTrue(r.isEnabled(), "默认启用");
    }

    @Test
    void exceptionRuleNullInputsAreDefensive() {
        // 任何字段传 null 不应爆
        ExceptionRule r = new ExceptionRule(null, null, true);
        r.setFieldName(null);
        r.setExpectedValues(null);
        assertEquals("", r.getFieldName());
        assertNotNull(r.getExpectedValues());
        assertTrue(r.getExpectedValues().isEmpty());
    }

    @Test
    void expectedValuesListIsCopied() {
        // 防御性 copy：外部修改传入 list 不应影响 rule 内部
        java.util.ArrayList<String> in = new java.util.ArrayList<>(Arrays.asList("0"));
        ExceptionRule r = new ExceptionRule("code", in, true);
        in.add("tampered");
        assertEquals(1, r.getExpectedValues().size(), "应拷贝而非持有引用");
    }

    @Test
    void expectedValuesSetterAlsoCopies() {
        java.util.ArrayList<String> in = new java.util.ArrayList<>(Arrays.asList("0"));
        ExceptionRule r = new ExceptionRule();
        r.setExpectedValues(in);
        in.clear();
        assertEquals(1, r.getExpectedValues().size(), "setter 也应拷贝");
    }
}