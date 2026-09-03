package com.hronline.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Round 7（重构）+ 一伦优化 #67：异常自定义规则（全局）。
 * <p>规则对项目内所有接口生效，不再挂在具体 apiKey 下。判定细节见
 * {@link com.hronline.service.ExceptionRuleEvaluator}。</p>
 *
 * <p>支持两种规则类型，两类语义相反：</p>
 * <ul>
 *   <li>{@link RuleType#HTTP_VALUE}：<b>白名单</b>——响应 HTTP 状态码在白名单 = 正常；
 *       不在白名单 = 异常（爆红告警）。例如期望状态码 {@code [200, 201, 204]}，
 *       实际返回 500 即触发告警。</li>
 *   <li>{@link RuleType#FIELD_VALUE}：<b>黑名单</b>——响应 JSON 第一级字段值出现在黑名单 = 异常；
 *       不在黑名单 = 正常。例如业务字段 {@code code} 黑名单 {@code [500, 9999]}，
 *       实际返回 code=500 即触发告警（即便 HTTP 200）。</li>
 * </ul>
 *
 * <p>语义反转背景：用户 9/3 反馈白名单记不住「哪些算正常」，改成「FIELD_VALUE 黑名单列
 * 出已知要告警的异常值」更直观（出问题时往里加就行，不用每次把所有正常值都列上）。</p>
 */
public class ExceptionRule {

    public enum RuleType {
        /** HTTP 状态码白名单：值在白名单 = 正常。 */
        @SerializedName("HTTP_VALUE")
        HTTP_VALUE,
        /** JSON 字段值黑名单：值在黑名单 = 异常。 */
        @SerializedName("FIELD_VALUE")
        FIELD_VALUE
    }

    private RuleType type = RuleType.HTTP_VALUE;
    /** {@link RuleType#FIELD_VALUE} 时为响应 JSON 第一级字段名（如 {@code code}、{@code success}）；{@link RuleType#HTTP_VALUE} 时不使用，留空。 */
    private String fieldName = "";
    /**
     * 值集合。{@link RuleType#HTTP_VALUE} 时为白名单（状态码字面量如 "200"），{@link RuleType#FIELD_VALUE}
     * 时为黑名单（字段值字面量如 "500"）。Gson 默认按枚举名序列化——HTTP_STATUS 旧配置在
     * 升级后会被忽略（异常规则 JSON 解析失败时 settings 层 catch 掉，不会阻塞启动）。
     */
    private List<String> expectedValues = new ArrayList<>();
    /** 用户开关；false 时这条规则不参与判定。 */
    private boolean enabled = true;

    public ExceptionRule() {}

    public ExceptionRule(RuleType type, String fieldName, List<String> expectedValues, boolean enabled) {
        this.type = type == null ? RuleType.HTTP_VALUE : type;
        this.fieldName = fieldName == null ? "" : fieldName;
        this.expectedValues = expectedValues == null ? new ArrayList<>() : new ArrayList<>(expectedValues);
        this.enabled = enabled;
    }

    public RuleType getType() { return type; }
    public void setType(RuleType type) { this.type = type == null ? RuleType.HTTP_VALUE : type; }

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
        if (type == RuleType.HTTP_VALUE) {
            return "ExceptionRule{HTTP_VALUE whitelist=" + expectedValues + ", enabled=" + enabled + "}";
        }
        return "ExceptionRule{FIELD_VALUE field=" + fieldName + ", blacklist=" + expectedValues + ", enabled=" + enabled + "}";
    }
}