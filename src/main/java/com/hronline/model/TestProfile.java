package com.hronline.model;

import com.hronline.RestAutoLabConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 测试配置文件数据模型 - 保存一组接口的测试参数配置
 *
 * 支持保存/加载测试配置，便于复用和分享测试用例。
 * 可通过JSON格式导出到本地文件，也支持从文件导入。
 */
public class TestProfile {

    /** 测试配置名称（如 "用户模块冒烟测试"） */
    private String name = "默认测试配置";
    /** 测试配置描述 */
    private String description = "";
    /** 基础URL（如 http://localhost:8080） */
    private String baseUrl = RestAutoLabConstants.DEFAULT_BASE_URL;
    /** 各接口的参数配置映射（接口uniqueKey -> 参数名到值的映射） */
    private Map<String, Map<String, String>> entries = new HashMap<>();
    /** 全局请求头（如 Authorization、Content-Type） */
    private Map<String, String> globalHeaders = new HashMap<>();
    /** 创建时间 */
    private long createdAt = System.currentTimeMillis();
    /** 最后更新时间 */
    private long updatedAt = System.currentTimeMillis();

    public TestProfile() {}

    public TestProfile(String name, String baseUrl) {
        this.name = name != null ? name : "默认测试配置";
        this.baseUrl = baseUrl != null ? baseUrl : RestAutoLabConstants.DEFAULT_BASE_URL;
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Map<String, Map<String, String>> getEntries() { return entries; }
    public void setEntries(Map<String, Map<String, String>> entries) { this.entries = entries; }

    public Map<String, String> getGlobalHeaders() { return globalHeaders; }
    public void setGlobalHeaders(Map<String, String> globalHeaders) { this.globalHeaders = globalHeaders; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    // ================================================================
    // 业务方法
    // ================================================================

    /**
     * 获取指定接口的参数配置
     * @param apiKey 接口唯一标识（ApiDefinition.uniqueKey()）
     * @return 参数名到值的映射，不存在时返回空Map
     */
    public Map<String, String> getParams(String apiKey) {
        Map<String, String> params = entries.get(apiKey);
        return params != null ? params : Collections.emptyMap();
    }

    /**
     * 设置指定接口的参数配置
     * @param apiKey 接口唯一标识
     * @param params 参数名到值的映射
     */
    public void setParams(String apiKey, Map<String, String> params) {
        entries.put(apiKey, new HashMap<>(params));
        updatedAt = System.currentTimeMillis();
    }

    // ================================================================
    // equals / hashCode / toString
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestProfile that = (TestProfile) o;
        return createdAt == that.createdAt &&
                updatedAt == that.updatedAt &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(baseUrl, that.baseUrl) &&
                Objects.equals(entries, that.entries) &&
                Objects.equals(globalHeaders, that.globalHeaders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, baseUrl, entries, globalHeaders, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "TestProfile{" +
                "name='" + name + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", entries=" + entries.size() +
                '}';
    }
}