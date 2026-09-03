package com.hronline.chain;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ResponseAssertion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * API 依赖关系模型 - 表示一条"上游接口 -> 下游接口"的值映射关系
 *
 * <p>例如：POST /users（producer）返回 {@code {id:1}}，
 * GET /users/{id}（consumer）需要这个 id 作为路径参数，
 * 则 mappings 中有一条 {@code sourcePath="data.id", targetParam="id"} 的映射。</p>
 */
public class ApiDependency {

    /** 上游接口的 uniqueKey（{@link ApiDefinition#uniqueKey()}） */
    private String producerKey;
    /** 下游接口的 uniqueKey */
    private String consumerKey;
    /** 值映射列表：上游响应字段路径 -> 下游参数名 */
    private List<ValueMapping> mappings = new ArrayList<>();
    /** 检测方式："CRUD" / "PATH_MATCH" / "BODY_MATCH" / "FOLDER_ORDER" / "MANUAL" */
    private String detectionType = "";

    /**
     * 单条值映射 - 上游响应 JSON 路径到下游参数名的对应关系
     */
    public static class ValueMapping {
        /** 上游响应 JSON 路径，如 "data.id"（传给 {@link ResponseAssertion#extractJsonValue}） */
        private String sourcePath;
        /** 下游参数名（注入到 paramValues 中） */
        private String targetParam;

        public ValueMapping() {}

        public ValueMapping(String sourcePath, String targetParam) {
            this.sourcePath = sourcePath;
            this.targetParam = targetParam;
        }

        public String getSourcePath() { return sourcePath; }
        public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

        public String getTargetParam() { return targetParam; }
        public void setTargetParam(String targetParam) { this.targetParam = targetParam; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ValueMapping that = (ValueMapping) o;
            return Objects.equals(sourcePath, that.sourcePath) &&
                    Objects.equals(targetParam, that.targetParam);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourcePath, targetParam);
        }
    }

    public ApiDependency() {}

    public ApiDependency(String producerKey, String consumerKey) {
        this.producerKey = producerKey;
        this.consumerKey = consumerKey;
    }

    public ApiDependency(String producerKey, String consumerKey, String detectionType) {
        this.producerKey = producerKey;
        this.consumerKey = consumerKey;
        this.detectionType = detectionType;
    }

    public String getProducerKey() { return producerKey; }
    public void setProducerKey(String producerKey) { this.producerKey = producerKey; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public List<ValueMapping> getMappings() { return mappings; }
    public void setMappings(List<ValueMapping> mappings) {
        this.mappings = mappings != null ? mappings : new ArrayList<>();
    }

    public String getDetectionType() { return detectionType; }
    public void setDetectionType(String detectionType) { this.detectionType = detectionType; }

    /**
     * 合并另一条依赖的映射（当 producerKey 和 consumerKey 相同时）
     */
    public void mergeMappings(ApiDependency other) {
        if (other == null || other.mappings == null) return;
        for (ValueMapping m : other.mappings) {
            if (!this.mappings.contains(m)) {
                this.mappings.add(m);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiDependency that = (ApiDependency) o;
        return Objects.equals(producerKey, that.producerKey) &&
                Objects.equals(consumerKey, that.consumerKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(producerKey, consumerKey);
    }

    @Override
    public String toString() {
        return "ApiDependency{" + producerKey + " -> " + consumerKey +
                ", mappings=" + mappings.size() + ", type=" + detectionType + '}';
    }
}
