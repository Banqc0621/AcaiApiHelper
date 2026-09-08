package com.hronline.chain;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;

/**
 * 依赖链批量测试执行器 - 按依赖顺序执行 API，自动传递响应值
 *
 * <p>核心能力：</p>
 * <ol>
 *   <li>拓扑排序：按依赖关系排列执行顺序，无依赖时按入参原顺序</li>
 *   <li>值提取：上游执行后从响应中按用户配置的点号路径提取 JSON 字段值</li>
 *   <li>值注入：下游执行前将提取值注入参数 Map</li>
 *   <li>失败不跳过（#93）：上游失败不阻塞下游请求，每个接口都会被执行；缺失依赖值时
 *       下游参数保留原占位，由 HTTP 自然失败暴露问题</li>
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

    /**
     * 用于无 IDE 容器场景（例如集成测试）的构造器；生产代码仍通过项目服务获取执行器。
     */
    ChainTestExecutor(HttpExecutorService httpExecutor) {
        this.project = null;
        this.httpExecutor = Objects.requireNonNull(httpExecutor, "httpExecutor");
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
     * @return 测试报告（每个接口都会包含一条结果，不存在 SKIPPED）
     */
    public TestReport execute(List<ApiDefinition> apis,
                              List<ApiDependency> dependencies,
                              TestProfile profile,
                              Environment environment,
                              ChainListener listener) {
        TestReport report = new TestReport();
        report.setTestName("依赖链测试");
        report.setStartTime(System.currentTimeMillis());

        // 依赖配置可能来自较早版本或更大的收藏夹。只保留当前批次中存在的
        // producer/consumer，避免隐藏节点把当前批次错误地卡在入度队列中。
        List<ApiDefinition> inputApis = new ArrayList<>();
        if (apis != null) {
            for (ApiDefinition api : apis) if (api != null) inputApis.add(api);
        }
        Set<String> apiKeys = new LinkedHashSet<>();
        for (ApiDefinition api : inputApis) {
            if (api != null) apiKeys.add(api.uniqueKey());
        }
        List<ApiDependency> effectiveDependencies = new ArrayList<>();
        if (dependencies != null) {
            for (ApiDependency dep : dependencies) {
                if (dep == null || dep.getProducerKey() == null || dep.getConsumerKey() == null) continue;
                if (apiKeys.contains(dep.getProducerKey()) && apiKeys.contains(dep.getConsumerKey())) {
                    effectiveDependencies.add(dep);
                }
            }
        }

        // 1. 拓扑排序（无依赖时退化为原顺序）
        List<ApiDefinition> orderedApis = topologicalSort(inputApis, effectiveDependencies);
        int total = orderedApis.size();

        // 2. 执行：一伦优化 #93，上游失败不跳过下游，每个接口都请求
        Map<String, Map<String, String>> extractedValues = new HashMap<>();

        // 一伦优化：把本次实际执行顺序一次性打印出来，方便用户从 IDE log 核对
        // 收藏文件夹从上到下的接口顺序是否被正确传递到执行器。
        StringBuilder seq = new StringBuilder();
        for (int k = 0; k < orderedApis.size(); k++) {
            if (k > 0) seq.append(" -> ");
            seq.append(orderedApis.get(k).displayLabel());
        }
        LOG.info("[ChainTestExecutor] 执行顺序（" + orderedApis.size() + " 个）：" + seq);

        for (int i = 0; i < orderedApis.size(); i++) {
            ApiDefinition api = orderedApis.get(i);
            LOG.info("[ChainTestExecutor] 第 " + (i + 1) + "/" + total + " 个：" + api.displayLabel());

            // 取参数副本
            Map<String, String> params = new LinkedHashMap<>(profile.getParams(api.uniqueKey()));

            // 注入依赖值（上游字段缺失则参数保留原占位，请求自然失败）
            injectDependencies(api, params, effectiveDependencies, extractedValues);

            // 执行请求
            TestResult result = httpExecutor.executeRequest(api, profile.getBaseUrl(), params,
                    profile.getGlobalHeaders(), null, HttpExecutorService.BODY_FORMAT_JSON,
                    environment, null);

            // 提取响应值供下游使用（成功才提取）
            extractProducerValues(api, result, effectiveDependencies, extractedValues);

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
     * 有环节点按原列表顺序追加，不会死循环。无依赖时严格保持入参顺序。</p>
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
     * 一伦优化 #94：按用户配置的点号路径直接抽取，不再做硬编码 data./result. 前缀猜测。
     * 嵌套路径（如 {@code data.user.name}）通过 ResponseAssertion 的点号解析器直接走通。
     */
    private void extractProducerValues(ApiDefinition api, TestResult result,
                                       List<ApiDependency> deps,
                                       Map<String, Map<String, String>> extractedValues) {
        if (result == null || result.getStatus() != TestStatus.PASSED) return;
        String responseBody = result.getResponseBody();
        if (responseBody == null || responseBody.isEmpty()) return;

        Map<String, String> values = new HashMap<>();
        for (ApiDependency dep : deps) {
            if (!api.uniqueKey().equals(dep.getProducerKey())) continue;
            for (ApiDependency.ValueMapping mapping : dep.getMappings() == null
                    ? Collections.<ApiDependency.ValueMapping>emptyList() : dep.getMappings()) {
                String path = mapping.getSourcePath();
                if (path == null || path.isBlank()) continue;
                String val = ResponseAssertion.extractJsonValue(responseBody, path.trim());
                if (val != null && !val.equals("null") && !val.isEmpty()) {
                    values.put(path.trim(), val);
                    LOG.info("提取依赖值: " + api.uniqueKey() + " ." + path + " = " + val);
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

            for (ApiDependency.ValueMapping mapping : dep.getMappings() == null
                    ? Collections.<ApiDependency.ValueMapping>emptyList() : dep.getMappings()) {
                String key = mapping.getSourcePath() == null ? "" : mapping.getSourcePath().trim();
                if (key.isEmpty()) continue;
                String value = producerValues.get(key);
                if (value != null) {
                    params.put(mapping.getTargetParam(), value);
                    LOG.info("注入依赖值: " + consumer.uniqueKey() + " ." + mapping.getTargetParam() + " = " + value);
                }
            }
        }
    }
}
