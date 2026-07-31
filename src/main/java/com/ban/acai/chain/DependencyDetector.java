package com.ban.acai.chain;

import com.ban.acai.model.ApiDefinition;
import com.ban.acai.model.ApiParameter;
import com.ban.acai.model.ParameterLocation;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API 依赖关系自动检测器 - 无状态工具类
 *
 * <p>三种启发式策略自动检测选中接口间的依赖关系：</p>
 * <ol>
 *   <li><b>CRUD 模式</b>：同一 URL base path 下，POST 创建资源 -> GET/PUT/DELETE 带 path param 消费</li>
 *   <li><b>PATH_MATCH</b>：下游 PATH 参数名与上游响应字段名模糊匹配</li>
 *   <li><b>BODY_MATCH</b>：下游 BODY 参数名与上游响应字段名模糊匹配</li>
 * </ol>
 */
public final class DependencyDetector {

    private DependencyDetector() {}

    /**
     * 自动检测给定 API 列表中的依赖关系
     *
     * @param apis 待检测的 API 列表
     * @return 检测到的依赖关系列表（已按 producerKey+consumerKey 去重）
     */
    public static List<ApiDependency> detect(List<ApiDefinition> apis) {
        if (apis == null || apis.size() < 2) return Collections.emptyList();

        List<ApiDependency> result = new ArrayList<>();
        detectCrud(apis, result);
        detectPathMatch(apis, result);
        detectBodyMatch(apis, result);

        // 按 (producerKey, consumerKey) 去重，合并 mappings
        return deduplicate(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 启发式 1：CRUD 模式
    // ═══════════════════════════════════════════════════════════

    /**
     * 检测 CRUD 模式依赖：
     * - 按 URL base path 分组（去掉尾部 {param} 段）
     * - POST 是 producer（创建资源）
     * - GET/PUT/DELETE 带 path param 是 consumer
     * - 映射 producer 响应中的 "id" 字段 -> consumer 的 path param
     */
    private static void detectCrud(List<ApiDefinition> apis, List<ApiDependency> result) {
        // 按 base path 分组
        Map<String, List<ApiDefinition>> byBasePath = new LinkedHashMap<>();
        for (ApiDefinition api : apis) {
            String base = urlBasePath(api.getUrl());
            byBasePath.computeIfAbsent(base, k -> new ArrayList<>()).add(api);
        }

        for (Map.Entry<String, List<ApiDefinition>> entry : byBasePath.entrySet()) {
            List<ApiDefinition> group = entry.getValue();
            if (group.size() < 2) continue;

            // 找 POST 作为 producer
            List<ApiDefinition> producers = group.stream()
                    .filter(a -> "POST".equalsIgnoreCase(a.getHttpMethod()))
                    .collect(Collectors.toList());
            if (producers.isEmpty()) continue;

            // 找带 path param 的 GET/PUT/DELETE 作为 consumer
            List<ApiDefinition> consumers = group.stream()
                    .filter(a -> {
                        String m = a.getHttpMethod().toUpperCase();
                        return (m.equals("GET") || m.equals("PUT") || m.equals("DELETE") || m.equals("PATCH"))
                                && !a.pathParameters().isEmpty();
                    })
                    .collect(Collectors.toList());
            if (consumers.isEmpty()) continue;

            // 收集 producer 响应字段名，优先找 "id" 或以 "Id" 结尾的字段
            Set<String> producerResponseFields = new LinkedHashSet<>();
            for (ApiDefinition producer : producers) {
                for (String path : collectResponseFieldPaths(producer)) {
                    producerResponseFields.add(path);
                }
            }

            for (ApiDefinition producer : producers) {
                for (ApiDefinition consumer : consumers) {
                    // 为 consumer 的每个 path param 找匹配的响应字段
                    for (ApiParameter pathParam : consumer.pathParameters()) {
                        String matchedField = findBestMatch(pathParam.getName(), producerResponseFields);
                        if (matchedField == null) {
                            // 默认用 "id"
                            if (producerResponseFields.contains("id")) {
                                matchedField = "id";
                            } else if (producerResponseFields.stream().anyMatch(f -> f.endsWith(".id"))) {
                                matchedField = producerResponseFields.stream()
                                        .filter(f -> f.endsWith(".id")).findFirst().orElse(null);
                            }
                        }
                        if (matchedField != null) {
                            ApiDependency dep = new ApiDependency(
                                    producer.uniqueKey(), consumer.uniqueKey(), "CRUD");
                            dep.getMappings().add(new ApiDependency.ValueMapping(matchedField, pathParam.getName()));
                            result.add(dep);
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 启发式 2：PATH 参数 ↔ 响应字段匹配
    // ═══════════════════════════════════════════════════════════

    /**
     * 检测 PATH 参数与响应字段的匹配：
     * - 对每个有 PATH 参数的 API B（consumer）
     * - 扫描所有其他 API A（producer）的响应字段
     * - 若 nameMatches(B.pathParam, A.responseField) 则建立依赖
     */
    private static void detectPathMatch(List<ApiDefinition> apis, List<ApiDependency> result) {
        for (ApiDefinition consumer : apis) {
            List<ApiParameter> pathParams = consumer.pathParameters();
            if (pathParams.isEmpty()) continue;

            for (ApiDefinition producer : apis) {
                if (producer.uniqueKey().equals(consumer.uniqueKey())) continue;
                if (producer.getResponseSchema() == null || producer.getResponseSchema().isEmpty()) continue;

                Set<String> responseFields = new LinkedHashSet<>(collectResponseFieldPaths(producer));
                for (ApiParameter pathParam : pathParams) {
                    String matched = findBestMatch(pathParam.getName(), responseFields);
                    if (matched != null) {
                        ApiDependency dep = new ApiDependency(
                                producer.uniqueKey(), consumer.uniqueKey(), "PATH_MATCH");
                        dep.getMappings().add(new ApiDependency.ValueMapping(matched, pathParam.getName()));
                        result.add(dep);
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 启发式 3：BODY 参数 ↔ 响应字段匹配
    // ═══════════════════════════════════════════════════════════

    /**
     * 检测 BODY 参数与响应字段的匹配：
     * - 对每个有 BODY 参数的 API B（consumer）
     * - 扫描所有其他 API A（producer）的响应字段
     * - 若 nameMatches(B.bodyParam, A.responseField) 则建立依赖
     */
    private static void detectBodyMatch(List<ApiDefinition> apis, List<ApiDependency> result) {
        for (ApiDefinition consumer : apis) {
            List<ApiParameter> bodyParams = consumer.bodyParameters();
            if (bodyParams.isEmpty()) continue;

            for (ApiDefinition producer : apis) {
                if (producer.uniqueKey().equals(consumer.uniqueKey())) continue;
                if (producer.getResponseSchema() == null || producer.getResponseSchema().isEmpty()) continue;

                Set<String> responseFields = new LinkedHashSet<>(collectResponseFieldPaths(producer));
                for (ApiParameter bodyParam : bodyParams) {
                    // 跳过复杂类型（其子字段可能各自有匹配）
                    if (bodyParam.isComplexType()) continue;

                    String matched = findBestMatch(bodyParam.getName(), responseFields);
                    if (matched != null) {
                        ApiDependency dep = new ApiDependency(
                                producer.uniqueKey(), consumer.uniqueKey(), "BODY_MATCH");
                        dep.getMappings().add(new ApiDependency.ValueMapping(matched, bodyParam.getName()));
                        result.add(dep);
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 递归收集 API 响应 schema 的所有字段路径（含嵌套，用 dot 连接）
     *
     * @return 字段路径列表，如 ["id", "name", "address.city"]
     */
    static List<String> collectResponseFieldPaths(ApiDefinition api) {
        List<String> paths = new ArrayList<>();
        if (api == null || api.getResponseSchema() == null) return paths;
        for (ApiParameter field : api.getResponseSchema()) {
            collectFieldPaths(field, "", paths);
        }
        return paths;
    }

    private static void collectFieldPaths(ApiParameter param, String prefix, List<String> paths) {
        if (param == null) return;
        String currentPath = prefix.isEmpty() ? param.getName() : prefix + "." + param.getName();
        paths.add(currentPath);
        if (param.getChildren() != null) {
            for (ApiParameter child : param.getChildren()) {
                collectFieldPaths(child, currentPath, paths);
            }
        }
    }

    /**
     * 在候选字段中找到与参数名最佳匹配的字段
     *
     * <p>匹配优先级：</p>
     * <ol>
     *   <li>精确匹配（大小写不敏感）</li>
     *   <li>参数名以字段名结尾（如 "userId" 以 "Id" 结尾 -> 匹配 "id"）</li>
     *   <li>字段名以参数名结尾</li>
     * </ol>
     */
    static String findBestMatch(String paramName, Collection<String> candidates) {
        if (paramName == null || candidates == null || candidates.isEmpty()) return null;
        String paramLower = paramName.toLowerCase();

        // 1. 精确匹配
        for (String c : candidates) {
            if (c.equalsIgnoreCase(paramName)) return c;
        }

        // 2. 参数名以字段名结尾（userId -> id）
        for (String c : candidates) {
            String cLower = c.toLowerCase();
            if (paramLower.endsWith(cLower) && paramLower.length() > cLower.length()) {
                return c;
            }
        }

        // 3. 字段名以参数名结尾（id -> userId，不太常见但可能）
        for (String c : candidates) {
            String cLower = c.toLowerCase();
            if (cLower.endsWith(paramLower) && cLower.length() > paramLower.length()) {
                return c;
            }
        }

        return null;
    }

    /**
     * 提取 URL base path：去掉末尾的 {param} 段
     *
     * <p>例如：
     * <ul>
     *   <li>{@code /api/users/{id}} -> {@code /api/users}</li>
     *   <li>{@code /api/users} -> {@code /api/users}</li>
     *   <li>{@code /api/users/{id}/orders/{orderId}} -> {@code /api/users/{id}/orders}</li>
     * </ul></p>
     */
    static String urlBasePath(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.replaceAll("/\\{[^}]+}$", "");
    }

    /**
     * 按 (producerKey, consumerKey) 去重，合并 mappings
     */
    private static List<ApiDependency> deduplicate(List<ApiDependency> deps) {
        Map<String, ApiDependency> byKey = new LinkedHashMap<>();
        for (ApiDependency dep : deps) {
            String key = dep.getProducerKey() + "->" + dep.getConsumerKey();
            ApiDependency existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, dep);
            } else {
                existing.mergeMappings(dep);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
