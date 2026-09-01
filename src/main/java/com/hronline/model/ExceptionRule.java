package com.hronline.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Round 7：接口异常自定义规则。
 * <p>每条规则表达"响应 JSON 第一级里 fieldName 的值在 expectedValues 白名单内 → 算正常；
 * 字段缺失或值不在白名单 → 算异常"。HTTP 状态码判定沿用原 2xx 通过的逻辑，
 * 这里只补充业务层判定（很多业务接口 HTTP 200 但 body.code != 0）。</p>
 *
 * <p>数据按 {@code apiKey} 索引到 settings；为空数组 = 该接口无自定义规则，
 * 走默认 HTTP 2xx 判定。</p>
 */
public class ExceptionRule {

    /** 响应 JSON 第一级字段名（如 {@code code}、{@code success}）。 */
    private String fieldName = "";
    /** 该字段被认定为"正常"的值集合；空集视同"任何值都接受"（仅作字段存在性检查）。 */
    private List<String> expectedValues = new ArrayList<>();
    /** 用户开关；false 时这条规则不参与判定（不写测试结果）。 */
    private boolean enabled = true;

    public ExceptionRule() {}

    public ExceptionRule(String fieldName, List<String> expectedValues, boolean enabled) {
        this.fieldName = fieldName == null ? "" : fieldName;
        this.expectedValues = expectedValues == null ? new ArrayList<>() : new ArrayList<>(expectedValues);
        this.enabled = enabled;
    }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName == null ? "" : fieldName; }

    public List<String> getExpectedValues() { return expectedValues; }
    public void setExpectedValues(List<String> expectedValues) {
        this.expectedValues = expectedValues == null ? new ArrayList<>() : new ArrayList<>(expectedValues);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExceptionRule)) return false;
        ExceptionRule that = (ExceptionRule) o;
        return enabled == that.enabled
                && Objects.equals(fieldName, that.fieldName)
                && Objects.equals(expectedValues, that.expectedValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, expectedValues, enabled);
    }

    @Override
    public String toString() {
        return "ExceptionRule{field=" + fieldName + ", expected=" + expectedValues + ", enabled=" + enabled + "}";
    }
}