package com.hronline.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Round 7（重构）：异常自定义规则（全局）。
 * <p>每条规则表达「该值落在白名单 = 正常；不在白名单 = 异常」。规则对项目内所有接口生效，
 * 不再挂在具体 apiKey 下。判定细节见 {@link com.hronline.service.ExceptionRuleEvaluator}。</p>
 *
 * <p>支持两种规则类型：
 * <ul>
 *   <li>{@link RuleType#HTTP_STATUS}：白名单匹配 HTTP 状态码（如 {@code [200, 201, 204]}）。
 *       用于替代原先写死的 {@code statusCode >= 200 && < 300} 判定。</li>
 *   <li>{@link RuleType#FIELD_VALUE}：白名单匹配响应 JSON 第一级字段值（如字段 {@code code}
 *       的白名单 {@code [0, 200]}）。用于「HTTP 200 但 body.code != 0」这种业务层异常。</li>
 * </ul>
 */
public class ExceptionRule {

    public enum RuleType {
        HTTP_STATUS,
        FIELD_VALUE
    }

    private RuleType type = RuleType.HTTP_STATUS;
    /** {@link RuleType#FIELD_VALUE} 时为响应 JSON 第一级字段名（如 {@code code}、{@code success}）；{@link RuleType#HTTP_STATUS} 时不使用，留空。 */
    private String fieldName = "";
    /** "正常" 值白名单。HTTP_STATUS 时为状态码字面量（如 "200"），FIELD_VALUE 时为字段值字面量（如 "0"）。 */
    private List<String> expectedValues = new ArrayList<>();
    /** 用户开关；false 时这条规则不参与判定。 */
    private boolean enabled = true;

    public ExceptionRule() {}

    public ExceptionRule(RuleType type, String fieldName, List<String> expectedValues, boolean enabled) {
        this.type = type == null ? RuleType.HTTP_STATUS : type;
        this.fieldName = fieldName == null ? "" : fieldName;
        this.expectedValues = expectedValues == null ? new ArrayList<>() : new ArrayList<>(expectedValues);
        this.enabled = enabled;
    }

    public RuleType getType() { return type; }
    public void setType(RuleType type) { this.type = type == null ? RuleType.HTTP_STATUS : type; }

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
                && type == that.type
                && Objects.equals(fieldName, that.fieldName)
                && Objects.equals(expectedValues, that.expectedValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, fieldName, expectedValues, enabled);
    }

    @Override
    public String toString() {
        if (type == RuleType.HTTP_STATUS) {
            return "ExceptionRule{HTTP_STATUS, expected=" + expectedValues + ", enabled=" + enabled + "}";
        }
        return "ExceptionRule{FIELD_VALUE field=" + fieldName + ", expected=" + expectedValues + ", enabled=" + enabled + "}";
    }
}