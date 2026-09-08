package com.hronline.chain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一伦优化 #94：跨面板共享的「最近一次响应 body」缓存。
 * <p>依赖设置弹窗（{@code DependencyGraphDialog}）需要为每个上游 API 生成响应字段候选路径，
 * 但 {@link com.hronline.model.ApiDefinition#getResponseSchema()} 通常是开发期手工维护的，
 * 真实跑出来的 body 字段（尤其是嵌套对象）大多不在 schema 里。
 * 把 {@code ApiDebuggerPanel} 测过的最近一次响应 body 缓存到这里，依赖设置就能从
 * body 递归出所有点号路径（如 {@code data.user.name}），用户在 combobox 里直接选。</p>
 *
 * <p>线程安全（{@link ConcurrentHashMap}），全进程共享。调用方负责在响应被丢弃时
 * （例如清空历史）调 {@link #remove(String)}。</p>
 */
public final class LastResponseCache {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private LastResponseCache() {}

    public static void put(String apiKey, String responseBody) {
        if (apiKey == null) return;
        if (responseBody == null || responseBody.isBlank()) {
            CACHE.remove(apiKey);
        } else {
            CACHE.put(apiKey, responseBody);
        }
    }

    public static String get(String apiKey) {
        return apiKey == null ? null : CACHE.get(apiKey);
    }

    public static void remove(String apiKey) {
        if (apiKey != null) CACHE.remove(apiKey);
    }
}