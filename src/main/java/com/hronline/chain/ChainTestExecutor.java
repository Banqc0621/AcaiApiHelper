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
 *   <li>发送门禁：上游未执行、失败、字段缺失/为空或目标参数不存在时，下游标记
 *       {@link TestStatus#SKIPPED}，不发送 HTTP 请求</li>
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
     * @return 测试报告（每个收藏夹接口都包含一条结果，未满足依赖门禁的接口为 SKIPPED）
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

        // 1. 收藏夹顺序就是执行顺序。依赖关系只负责值映射和下游发送门禁。
        List<ApiDefinition> orderedApis = inputApis;
        int total = orderedApis.size();

        // 2. 记录已经处理的结果和成功提取的值。未出现在 executionResults 中的
        // producer 说明它在收藏夹中尚未执行，consumer 必须跳过。
        Map<String, TestResult> executionResults = new LinkedHashMap<>();
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
            String gateReason = dependencyGateReason(api, effectiveDependencies, executionResults,
                    extractedValues);
            TestResult result;
            if (gateReason != null) {
                result = skippedResult(api, batchId, gateReason);
                LOG.info("[ChainTestExecutor] 跳过 " + api.displayLabel() + "：" + gateReason);
            } else {
                injectDependencies(api, params, effectiveDependencies, extractedValues);
                result = httpExecutor.executeRequest(api,
                        profile == null ? "" : profile.getBaseUrl(), params,
                        profile == null ? Collections.emptyMap() : profile.getGlobalHeaders(),
                        null, HttpExecutorService.BODY_FORMAT_JSON, environment, null);
            }
            result.setBatchId(batchId);
            executionResults.put(api.uniqueKey(), result);

            // 提取响应值供后续收藏夹接口使用（只有真实成功才提取）
            if (gateReason == null) {
                extractProducerValues(api, result, effectiveDependencies, extractedValues);
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
    // 响应值提取与注入
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查一个 consumer 的所有依赖。返回 null 表示可以发送；否则返回用户可读的跳过原因。
     */
    private String dependencyGateReason(ApiDefinition consumer,
                                        List<ApiDependency> deps,
                                        Map<String, TestResult> executionResults,
                                        Map<String, Map<String, String>> extractedValues) {
        for (ApiDependency dep : deps) {
            if (!consumer.uniqueKey().equals(dep.getConsumerKey())) continue;
            String producerKey = dep.getProducerKey();
            TestResult producerResult = executionResults.get(producerKey);
            if (producerResult == null) {
                return "依赖上游尚未按收藏夹顺序执行（" + producerKey + "）";
            }
            if (producerResult.getStatus() != TestStatus.PASSED) {
                return "依赖上游未成功（" + producerKey + "：" + producerResult.getStatus() + "）";
            }

            List<ApiDependency.ValueMapping> mappings = dep.getMappings() == null
                    ? Collections.emptyList() : dep.getMappings();
            if (mappings.isEmpty()) continue;
            Map<String, String> producerValues = extractedValues.get(producerKey);
            for (ApiDependency.ValueMapping mapping : mappings) {
                if (mapping == null || mapping.getSourcePath() == null || mapping.getSourcePath().isBlank()) {
                    return "依赖映射缺少上游响应字段路径";
                }
                String target = mapping.getTargetParam() == null ? "" : mapping.getTargetParam().trim();
                if (target.isEmpty()) return "依赖映射缺少下游目标参数";
                if (!containsParameterPath(consumer, target)) {
                    return "下游参数不存在（" + target + "）";
                }
                String source = mapping.getSourcePath().trim();
                String value = producerValues == null ? null : producerValues.get(source);
                if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
                    return "上游响应字段缺失或为空（" + source + "）";
                }
            }
        }
        return null;
    }

    /** 递归判断目标参数是否存在，兼容 request.id 这类嵌套参数路径。 */
    private boolean containsParameterPath(ApiDefinition api, String targetPath) {
        if (api == null || targetPath == null || targetPath.isBlank()) return false;
        String normalized = targetPath.trim();
        if (api.getParameters() == null) return false;
        for (ApiParameter root : api.getParameters()) {
            if (root == null || root.getName() == null) continue;
            String rootName = root.getName().trim();
            if (normalized.equals(rootName)) return true;
            String prefix = rootName + ".";
            if (normalized.startsWith(prefix)
                    && containsChildPath(root, normalized.substring(prefix.length()))) return true;
            // 兼容配置里直接保存 child.path 而不带复杂对象根名的旧格式。
            if (containsChildPath(root, normalized)) return true;
        }
        return false;
    }

    private boolean containsChildPath(ApiParameter parent, String path) {
        if (parent == null || path == null || path.isBlank() || parent.getChildren() == null) return false;
        String[] parts = path.split("\\.");
        ApiParameter current = parent;
        for (String raw : parts) {
            String segment = raw == null ? "" : raw.trim();
            if (segment.isEmpty() || current.getChildren() == null) return false;
            ApiParameter next = null;
            for (ApiParameter child : current.getChildren()) {
                if (child != null && segment.equals(child.getName())) {
                    next = child;
                    break;
                }
            }
            if (next == null) return false;
            current = next;
        }
        return true;
    }

    private TestResult skippedResult(ApiDefinition api, String batchId, String reason) {
        TestResult result = new TestResult(api);
        result.setStatus(TestStatus.SKIPPED);
        result.setErrorMessage(reason);
        result.setRequestUrl("");
        result.setRequestBody("");
        result.setRequestParameters(Collections.emptyMap());
        result.setRequestHeaders(Collections.emptyMap());
        result.setTimestamp(System.currentTimeMillis());
        result.setBatchId(batchId);
        return result;
    }

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
