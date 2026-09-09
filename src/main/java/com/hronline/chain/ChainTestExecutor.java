package com.hronline.chain;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.*;

/**
 * 依赖链批量测试执行器 - 按收藏夹顺序执行 API，自动传递响应值
 *
 * <p>核心能力：</p>
 * <ol>
 *   <li>收藏夹顺序：严格按调用方传入的收藏夹顺序执行，不做拓扑排序</li>
 *   <li>值提取：上游执行后从响应中按用户配置的点号路径提取 JSON 字段值</li>
 *   <li>值注入：下游执行前将提取值注入参数 Map</li>
 *   <li>从不跳过：无论上游是否失败、依赖字段是否缺失，下游接口都照常发送真实
 *       请求；已成功提取的值会注入参数，提取不到的参数保留用户保存的原值</li>
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
     * @return 测试报告（每个收藏夹接口都包含一条结果，任何接口都不会被跳过）
     */
    public TestReport execute(List<ApiDefinition> apis,
                              List<ApiDependency> dependencies,
                              TestProfile profile,
                              Environment environment,
                              ChainListener listener) {
        TestReport report = new TestReport();
        report.setTestName("依赖链测试");
        report.setStartTime(System.currentTimeMillis());

        // 本批次唯一 ID，便于调用方在进度和结果中识别同一批测试。
        final String batchId = "batch-" + report.getStartTime() + "-" + java.util.UUID.randomUUID();

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

        // 1. 收藏夹顺序就是执行顺序。依赖关系只负责值映射，不再决定接口是否执行。
        List<ApiDefinition> orderedApis = inputApis;
        int total = orderedApis.size();

        // 2. 记录已成功提取的依赖值。下游接口无条件执行；依赖值只作为
        // 「能拿到就注入、拿不到就用保存的原值」的增强，不再作为发送门禁。
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

            Map<String, String> params = profile == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(profile.getParams(api.uniqueKey()));
            // 不做任何跳过判断：每个接口都发送真实请求。已提取到的上游依赖值
            // 会注入参数，提取不到（上游失败/字段缺失）则保留用户保存的原值。
            Set<String> injectedParams = injectDependencies(api, params, effectiveDependencies, extractedValues);
            // 非 GET 接口优先使用保存的请求体（GET 时 executeRequest 内部会忽略 body）。
            // 保存的请求体是用户编辑时定稿的静态内容，注入的依赖值必须合并进去，
            // 否则下游历史记录里看到的入参永远不是上游响应的真实内容。
            String savedBody = profile == null ? "" : profile.getRequestBody(api.uniqueKey());
            savedBody = mergeInjectedValuesIntoBody(savedBody, injectedParams, params);
            TestResult result = httpExecutor.executeRequest(api,
                    profile == null ? "" : profile.getBaseUrl(), params,
                    profile == null ? Collections.emptyMap() : profile.getGlobalHeaders(),
                    savedBody.isBlank() ? null : savedBody,
                    HttpExecutorService.BODY_FORMAT_JSON, environment, null);
            result.setBatchId(batchId);

            // 提取响应值供后续收藏夹接口使用（内部只在 PASSED 时提取）
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
     * 将提取的依赖值注入到 consumer 的参数 Map 中。
     *
     * @return 本次实际被注入的参数名集合（用于后续合并进已保存的请求体）
     */
    private Set<String> injectDependencies(ApiDefinition consumer,
                                    Map<String, String> params,
                                    List<ApiDependency> deps,
                                    Map<String, Map<String, String>> extractedValues) {
        Set<String> injected = new LinkedHashSet<>();
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
                    injected.add(mapping.getTargetParam());
                    LOG.info("注入依赖值: " + consumer.uniqueKey() + " ." + mapping.getTargetParam() + " = " + value);
                }
            }
        }
        return injected;
    }

    /**
     * 把注入的依赖值合并进已保存的 JSON 请求体。
     * <p>只合并本次真正注入的参数（{@code injected}），避免把用户编辑时保存的普通参数
     * 值也覆盖进请求体。支持 {@code a.b.c} 嵌套路径：缺失的中间层级会自动创建。</p>
     * <p>请求体为空或非 JSON 对象时原样返回（如 GET / RAW 场景由调用方保证）。</p>
     */
    private String mergeInjectedValuesIntoBody(String savedBody, Set<String> injected,
                                               Map<String, String> params) {
        if (savedBody == null || savedBody.isBlank() || injected == null || injected.isEmpty()) {
            return savedBody;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(savedBody);
            if (!root.isJsonObject()) return savedBody;
            com.google.gson.JsonObject obj = root.getAsJsonObject();
            for (String target : injected) {
                if (target == null || target.isBlank()) continue;
                String value = params.get(target);
                if (value == null) continue;
                setNestedJson(obj, target.trim().split("\\."), value);
            }
            return obj.toString();
        } catch (Exception e) {
            LOG.warn("合并依赖值到请求体失败，使用原始请求体: " + e.getMessage());
            return savedBody;
        }
    }

    /** 按路径段逐层写入 JSON 对象；中间层级缺失或非对象时自动替换为对象。 */
    private void setNestedJson(com.google.gson.JsonObject root, String[] segments, String value) {
        com.google.gson.JsonObject current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            String seg = segments[i].trim();
            if (seg.isEmpty()) return;
            com.google.gson.JsonElement child = current.get(seg);
            if (child == null || !child.isJsonObject()) {
                com.google.gson.JsonObject created = new com.google.gson.JsonObject();
                current.add(seg, created);
                current = created;
            } else {
                current = child.getAsJsonObject();
            }
        }
        String leaf = segments[segments.length - 1].trim();
        if (leaf.isEmpty()) return;
        current.addProperty(leaf, value);
    }
}