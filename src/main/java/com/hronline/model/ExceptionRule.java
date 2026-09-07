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
 *   <li>{@link RuleType#HTTP_VALUE}：<b>白名单</b>——字段名留空时校验响应 HTTP 状态码；
 *       填写字段名时校验响应 JSON 字段。值命中白名单 = 正常，不命中 = 异常（爆红告警）。
 *       例如字段 {@code code} 白名单 {@code [200, 301]} 时，HTTP 200 + code=301 通过。</li>
 *   <li>{@link RuleType#FIELD_VALUE}：<b>黑名单</b>——响应 JSON 字段值出现在黑名单 = 异常；
 *       不在黑名单 = 正常。例如业务字段 {@code code} 黑名单 {@code [500, 9999]}，
 *       实际返回 code=500 即触发告警（即便 HTTP 200）。</li>
 * </ul>
 *
 * <p>语义反转背景：用户 9/3 反馈白名单记不住「哪些算正常」，改成「FIELD_VALUE 黑名单列
 * 出已知要告警的异常值」更直观（出问题时往里加就行，不用每次把所有正常值都列上）。</p>
 */
public class ExceptionRule {

    /**
     * 一伦优化 #89：开箱即用的通用规则。首次进入异常规则弹框且已存规则为空时自动注入并落盘，
     * 用户可直接使用 / 编辑 / 删除。语义遵循：
     * <ul>
     *   <li>HTTP_VALUE 白名单 [200,201,204] —— 标准成功状态码，之外的才爆红（不加这条会
     *       让所有非 200/201/204 都告警，太严）</li>
     *   <li>FIELD_VALUE 黑名单 code=[500,501,9999] —— 业务字段 code 等于这些值时告警
     *       （最常见的失败码，覆盖大半业务接口）</li>
     *   <li>HTTP_VALUE 字段白名单 code=[200] —— 作为默认关闭的示例模板展示；用户确认项目
     *       的响应都包含 code 字段后可启用，避免默认影响不返回 code 的接口。</li>
     * </ul>
     */
    public static java.util.List<ExceptionRule> defaultRules() {
        java.util.List<ExceptionRule> defaults = new java.util.ArrayList<>();
        defaults.add(new ExceptionRule(RuleType.HTTP_VALUE, "",
                java.util.Arrays.asList("200", "201", "204"), true));
        defaults.add(new ExceptionRule(RuleType.FIELD_VALUE, "code",
                java.util.Arrays.asList("500", "501", "9999"), true));
        defaults.add(new ExceptionRule(RuleType.HTTP_VALUE, "code",
                java.util.Collections.singletonList("200"), false));
        return defaults;
    }

    public enum RuleType {
        /** HTTP 状态码白名单：值在白名单 = 正常。 */
        @SerializedName("HTTP_VALUE")
        HTTP_VALUE,
        /** JSON 字段值黑名单：值在黑名单 = 异常。 */
        @SerializedName("FIELD_VALUE")
        FIELD_VALUE
    }

    private RuleType type = RuleType.HTTP_VALUE;
    /** {@link RuleType#FIELD_VALUE} 时为响应 JSON 字段路径；{@link RuleType#HTTP_VALUE} 留空表示 HTTP 状态码，填写则表示响应字段路径。 */
    private String fieldName = "";
    /**
     * 值集合。{@link RuleType#HTTP_VALUE} 时为白名单（状态码或响应字段字面量如 "200"），{@link RuleType#FIELD_VALUE}
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

    /**
     * 迁移旧版异常规则编辑器保存的数据。
     * <p>旧界面对 HTTP_VALUE 强制隐藏字段名，却允许保存 {@code SYSTEM_ERROR} 之类字符串，
     * 导致升级后被误当作 HTTP 状态码并出现“必须是整数”。当一条无字段 HTTP_VALUE 的所有
     * 取值都不可能是合法 HTTP 状态码时，可无歧义地还原为常见的响应字段 {@code code}
     * 白名单。只要存在一个合法状态码就不迁移，避免猜测混合配置。</p>
     *
     * @return 是否完成了迁移
     */
    public boolean migrateLegacyStringHttpValuesToCodeField() {
        if (type != RuleType.HTTP_VALUE || (fieldName != null && !fieldName.isBlank())
                || expectedValues == null || expectedValues.isEmpty()) {
            return false;
        }
        boolean hasLiteral = false;
        for (String value : expectedValues) {
            if (value == null || value.isBlank()) continue;
            hasLiteral = true;
            try {
                int status = Integer.parseInt(value.trim());
                if (status >= 100 && status <= 599) return false;
            } catch (NumberFormatException ignored) {
                // 非整数字符串正是旧界面无法表达的响应字段白名单值。
            }
        }
        if (!hasLiteral) return false;
        fieldName = "code";
        return true;
    }

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
            return "ExceptionRule{HTTP_VALUE field=" + (fieldName == null || fieldName.isBlank() ? "HTTP_STATUS" : fieldName)
                    + ", whitelist=" + expectedValues + ", enabled=" + enabled + "}";
        }
        return "ExceptionRule{FIELD_VALUE field=" + fieldName + ", blacklist=" + expectedValues + ", enabled=" + enabled + "}";
    }
}
