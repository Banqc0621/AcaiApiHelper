package com.hronline.chain;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;

/**
 * 依赖链批量测试执行器 - 按拓扑排序执行 API，自动传递响应值
 *
 * <p>核心能力：</p>
 * <ol>
 *   <li>拓扑排序：按依赖关系排列执行顺序，上游先执行</li>
 *   <li>值提取：上游执行后从响应中提取 JSON 字段值</li>
 *   <li>值注入：下游执行前将提取值注入参数 Map</li>
 *   <li>失败跳过：上游失败时，所有直接和间接下游标记为 SKIPPED</li>
 * </ol>
 *
 * <p>不修改 {@link HttpExecutorService}，直接调用其 {@code executeRequest()} 方法。</p>
 */
@Service(Service.Level.PROJECT)
public final class ChainTestExecutor {

    private static final Logger LOG = Logger.getInstance(ChainTestExecutor.class);

    private final Project project;
    private final HttpExecutorService httpExecutor;

    public ChainTestExecutor(Project project) {
        this.project = project;
        this.httpExecutor = HttpExecutorService.getInstance(project);
    }

    public static ChainTestExecutor getInstance(Project project) {
        return project.getService(ChainTestExecutor.class);
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ChainListener {
        void onTestComplete(TestResult result, int current, int total);
    }

    /**
     * 依赖链批量执行
     *
     * @param apis         待测试的 API 列表
     * @param dependencies 检测/编辑后的依赖关系列表
     * @param profile      测试配置（含参数、baseUrl、全局请求头）
     * @param environment  环境变量（可为 null）
     * @param listener     进度回调（可为 null）
     * @return 测试报告（含 SKIPPED 结果）
     */
    public TestReport execute(List<ApiDefinition> apis,
                              List<ApiDependency> dependencies,
                              TestProfile profile,
                              Environment environment,
                              ChainListener listener) {
        TestReport report = new TestReport();
        report.setTestName("依赖链测试");
        report.setStartTime(System.currentTimeMillis());

        // 1. 拓扑排序
        List<ApiDefinition> orderedApis = topologicalSort(apis, dependencies);
        int total = orderedApis.size();

        // 2. 执行
        Set<String> failedKeys = new HashSet<>();
        // producerKey -> {sourcePath -> extracted value}
        Map<String, Map<String, String>> extractedValues = new HashMap<>();

        for (int i = 0; i < orderedApis.size(); i++) {
            ApiDefinition api = orderedApis.get(i);
            TestResult result;

            if (failedKeys.contains(api.uniqueKey())) {
                // 上游失败，跳过
                result = new TestResult(api);
                result.setStatus(TestStatus.SKIPPED);
                result.setErrorMessage("依赖接口失败，已跳过");
                result.setTimestamp(System.currentTimeMillis());
                LOG.info("跳过依赖接口: " + api.displayLabel());
            } else {
                // 取参数副本
                Map<String, String> params = new LinkedHashMap<>(profile.getParams(api.uniqueKey()));

                // 注入依赖值
                injectDependencies(api, params, dependencies, extractedValues);

                // 执行请求
                result = httpExecutor.executeRequest(api, profile.getBaseUrl(), params,
                        profile.getGlobalHeaders(), null, HttpExecutorService.BODY_FORMAT_JSON,
                        environment, null);

                // 失败时标记所有下游
                if (result.getStatus() != TestStatus.PASSED) {
                    Set<String> downstream = transitiveConsumers(api.uniqueKey(), dependencies);
                    failedKeys.addAll(downstream);
                    LOG.info("接口失败，标记 " + downstream.size() + " 个下游跳过: " + api.displayLabel());
                }

                // 提取响应值供下游使用
                extractProducerValues(api, result, dependencies, extractedValues);
            }

            report.getResults().add(result);
            if (listener != null) {
                listener.onTestComplete(result, i + 1, total);
            }
        }

        report.setEndTime(System.currentTimeMillis());
        return report;
    }

    // ═══════════════════════════════════════════════════════════
    // 拓扑排序（Kahn 算法）
    // ═══════════════════════════════════════════════════════════

    /**
     * 按依赖关系拓扑排序 API 列表
     *
     * <p>使用 Kahn 算法：反复移除入度为 0 的节点。
     * 有环节点按原列表顺序追加，不会死循环。</p>
     */
    private List<ApiDefinition> topologicalSort(List<ApiDefinition> apis,
                                                List<ApiDependency> deps) {
        // 构建 key -> api 映射，保持原始顺序
        Map<String, ApiDefinition> apiByKey = new LinkedHashMap<>();
        for (ApiDefinition api : apis) {
            apiByKey.put(api.uniqueKey(), api);
        }

        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String key : apiByKey.keySet()) {
            inDegree.put(key, 0);
        }
        for (ApiDependency dep : deps) {
            if (apiByKey.containsKey(dep.getConsumerKey())) {
                inDegree.merge(dep.getConsumerKey(), 1, Integer::sum);
            }
        }

        // 邻接表：producerKey -> [consumerKey]
        Map<String, List<String>> adjacency = new HashMap<>();
        for (ApiDependency dep : deps) {
            if (apiByKey.containsKey(dep.getProducerKey()) && apiByKey.containsKey(dep.getConsumerKey())) {
                adjacency.computeIfAbsent(dep.getProducerKey(), k -> new ArrayList<>())
                        .add(dep.getConsumerKey());
            }
        }

        // Kahn 算法
        List<ApiDefinition> sorted = new ArrayList<>();
        // 用原始顺序的队列，保证同等优先级时保持用户选择顺序
        Queue<String> queue = new LinkedList<>();
        for (String key : apiByKey.keySet()) {
            if (inDegree.getOrDefault(key, 0) == 0) {
                queue.add(key);
            }
        }

        while (!queue.isEmpty()) {
            String key = queue.poll();
            ApiDefinition api = apiByKey.get(key);
            if (api != null) {
                sorted.add(api);
            }
            for (String consumer : adjacency.getOrDefault(key, Collections.emptyList())) {
                int newDeg = inDegree.merge(consumer, -1, Integer::sum);
                if (newDeg == 0) {
                    queue.add(consumer);
                }
            }
        }

        // 有环节点按原顺序追加
        if (sorted.size() < apis.size()) {
            LOG.warn("检测到循环依赖，环中节点按原始顺序执行");
            for (ApiDefinition api : apis) {
                if (!sorted.contains(api)) {
                    sorted.add(api);
                }
            }
        }

        return sorted;
    }

    // ═══════════════════════════════════════════════════════════
    // 响应值提取与注入
    // ═══════════════════════════════════════════════════════════

    /**
     * 从执行结果中提取所有以该 API 为 producer 的响应值
     */
    private void extractProducerValues(ApiDefinition api, TestResult result,
                                       List<ApiDependency> deps,
                                       Map<String, Map<String, String>> extractedValues) {
        if (result.getStatus() != TestStatus.PASSED) return;
        String responseBody = result.getResponseBody();
        if (responseBody == null || responseBody.isEmpty()) return;

        Map<String, String> values = new HashMap<>();
        for (ApiDependency dep : deps) {
            if (!api.uniqueKey().equals(dep.getProducerKey())) continue;
            for (ApiDependency.ValueMapping mapping : dep.getMappings()) {
                String val = extractWithFallback(responseBody, mapping.getSourcePath());
                if (val != null) {
                    values.put(mapping.getSourcePath(), val);
                    LOG.info("提取依赖值: " + api.uniqueKey() + " ." + mapping.getSourcePath() + " = " + val);
                }
            }
        }
        if (!values.isEmpty()) {
            extractedValues.put(api.uniqueKey(), values);
        }
    }

    /**
     * 将提取的依赖值注入到 consumer 的参数 Map 中
     */
    private void injectDependencies(ApiDefinition consumer,
                                    Map<String, String> params,
                                    List<ApiDependency> deps,
                                    Map<String, Map<String, String>> extractedValues) {
        for (ApiDependency dep : deps) {
            if (!consumer.uniqueKey().equals(dep.getConsumerKey())) continue;
            Map<String, String> producerValues = extractedValues.get(dep.getProducerKey());
            if (producerValues == null) continue;

            for (ApiDependency.ValueMapping mapping : dep.getMappings()) {
                String value = producerValues.get(mapping.getSourcePath());
                if (value != null) {
                    params.put(mapping.getTargetParam(), value);
                    LOG.info("注入依赖值: " + consumer.uniqueKey() + " ." + mapping.getTargetParam() + " = " + value);
                }
            }
        }
    }

    /**
     * 从 JSON 响应中提取值，自动尝试常见的数据包层级前缀
     *
     * <p>依次尝试：原路径、data.前缀、result.前缀、data.data.前缀。
     * 复用 {@link ResponseAssertion#extractJsonValue(String, String)}。</p>
     */
    static String extractWithFallback(String responseBody, String sourcePath) {
        if (responseBody == null || sourcePath == null) return null;
        String[] prefixes = {"", "data.", "result.", "data.data."};
        for (String prefix : prefixes) {
            String val = ResponseAssertion.extractJsonValue(responseBody, prefix + sourcePath);
            if (val != null && !val.equals("null") && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    /**
     * BFS 遍历依赖图，收集失败节点的所有直接和间接下游 consumer
     */
    private Set<String> transitiveConsumers(String failedKey, List<ApiDependency> deps) {
        Set<String> result = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(failedKey);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (ApiDependency dep : deps) {
                if (dep.getProducerKey().equals(current) && !result.contains(dep.getConsumerKey())) {
                    result.add(dep.getConsumerKey());
                    queue.add(dep.getConsumerKey());
                }
            }
        }
        return result;
    }
}
