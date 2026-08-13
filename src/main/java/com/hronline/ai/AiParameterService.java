package com.hronline.ai;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import com.hronline.model.ResponseAssertion;
import com.hronline.settings.RestAutoLabSettingsState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * AI参数生成服务 - 对接火山引擎ARK（OpenAI兼容协议）自动生成API测试入参
 *
 * 核心功能：
 * 1. 使用OpenAI Chat Completions协议调用ARK大模型
 * 2. 将API元信息构建为Prompt发送给AI模型
 * 3. 解析AI返回的JSON参数值
 * 4. 支持正常值、边界值、异常值等多种测试场景
 * 5. AI服务不可用时自动降级为基于类型的默认值生成
 * 6. 支持从JSON/API文档导入参数
 *
 * 通信协议：
 * - OpenAI Chat Completions API (POST /v3/chat/completions)
 * - 认证方式: Bearer Token
 * - 模型: doubao-seed-2.0-pro（可在设置中切换）
 */
@Service(Service.Level.PROJECT)
public final class AiParameterService {

    private final Project project;
    private final Logger log = Logger.getInstance(AiParameterService.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** HTTP客户端（AI服务调用专用） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(RestAutoLabConstants.AI_CONNECT_TIMEOUT_SECONDS))
            .build();

    public AiParameterService(Project project) {
        this.project = project;
    }

    /**
     * 构造 Authorization Header 值。
     * <p>规则：</p>
     * <ul>
     *   <li>云端模型（token 非空、非字面量 "Bearer"）：返回 <code>Bearer {token}</code></li>
     *   <li>本地模型（token 为空）：返回 <code>Bearer Bearer</code>，满足网关必须有 Bearer Header 的要求</li>
     *   <li>本地模型（token 为字面量 "Bearer"）：返回 <code>Bearer Bearer</code></li>
     * </ul>
     */
    private String buildAuthHeader(String token) {
        if (RestAutoLabConstants.isLocalModelToken(token)) {
            return RestAutoLabConstants.BEARER_PREFIX + RestAutoLabConstants.AI_LOCAL_BEARER_TOKEN;
        }
        return RestAutoLabConstants.BEARER_PREFIX + token;
    }

    /**
     * 测试场景枚举 - 指定AI生成参数的测试场景类型
     */
    public enum TestScenario {
        NORMAL("正常值", "生成符合预期的正常测试数据"),
        BOUNDARY("边界值", "生成边界条件的测试数据（空值、最大值、最小值等）"),
        ABNORMAL("异常值", "生成异常测试数据（非法格式、超长、特殊字符等）"),
        FULL("全量覆盖", "同时生成正常/边界/异常多组测试数据");

        private final String displayName;
        private final String description;

        TestScenario(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }

        @Override
        public String toString() { return displayName; }
    }

    /**
     * AI生成API测试参数
     *
     * @param api       待生成参数的API定义
     * @param scenario  测试场景（正常/边界/异常/全量）
     * @return 参数名到生成值的映射列表（全量模式可能返回多组）
     */
    public List<Map<String, String>> generateParameters(ApiDefinition api, TestScenario scenario) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        log.info("[AI生成参数] 开始(简版) => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 场景=" + scenario + ", 参数个数=" + api.getParameters().size());

        // 检查AI服务是否配置（本地模型无需 API Key，仅校验服务器URL）
        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成参数] 跳过(简版)：AI服务器URL未配置，使用默认值生成策略");
            return generateDefaultParameters(api, scenario);
        }
        if (RestAutoLabConstants.isLocalModelToken(settings.getAiToken())) {
            log.info("[AI生成参数] 检测到本地模型（token 为空或字面量 'Bearer'），将使用占位 Bearer 调用");
        }

        try {
            List<Map<String, String>> result = callArkChatCompletions(api, scenario, settings);
            log.info("[AI生成参数] 成功(简版) => API=" + api.getUrl() + ", 参数组数=" + result.size());
            return result;
        } catch (Exception e) {
            log.warn("[AI生成参数] 失败(简版) => API=" + api.getUrl()
                    + ", 异常=" + e.getClass().getSimpleName() + ": " + e.getMessage() + "，降级使用默认值");
            return generateDefaultParameters(api, scenario);
        }
    }

    /** 默认场景为NORMAL */
    public List<Map<String, String>> generateParameters(ApiDefinition api) {
        return generateParameters(api, TestScenario.NORMAL);
    }

    /**
     * AI生成结果封装 - 包含原始响应和解析后的参数
     */
    public static class GenerateResult {
        private final String rawResponse;
        private final List<Map<String, String>> parameters;
        private final boolean usedAi;
        private final String errorMessage;

        public GenerateResult(String rawResponse, List<Map<String, String>> parameters, boolean usedAi) {
            this(rawResponse, parameters, usedAi, null);
        }

        public GenerateResult(String rawResponse, List<Map<String, String>> parameters,
                              boolean usedAi, String errorMessage) {
            this.rawResponse = rawResponse;
            this.parameters = parameters;
            this.usedAi = usedAi;
            this.errorMessage = errorMessage;
        }

        public String getRawResponse() { return rawResponse; }
        public List<Map<String, String>> getParameters() { return parameters; }
        public boolean isUsedAi() { return usedAi; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isSuccess() { return errorMessage == null && usedAi; }
    }

    /**
     * 生成参数并返回原始AI响应（供UI展示完整流程）
     */
    public GenerateResult generateParametersWithRaw(ApiDefinition api, TestScenario scenario) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        log.info("[AI生成参数] 开始 => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 控制器=" + api.getControllerName() + ", 场景=" + scenario
                + ", 参数个数=" + api.getParameters().size());

        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成参数] 跳过：AI服务器URL未配置，使用本地默认值生成策略");
            return new GenerateResult(
                    "⚠ AI未配置，使用本地默认值生成（非AI生成）\n请在AI Tab中配置服务器URL以使用AI生成真实参数",
                    generateDefaultParameters(api, scenario),
                    false,
                    "AI服务器URL未配置，请在「AI配置」中填写服务器URL"
            );
        }
        if (RestAutoLabConstants.isLocalModelToken(settings.getAiToken())) {
            log.info("[AI生成参数] 检测到本地模型（token 为空或字面量 'Bearer'），将使用占位 Bearer 调用");
        }

        try {
            String rawContent = callArkChatCompletionsRaw(api, scenario, settings);
            String cleanedContent = cleanMarkdownCodeBlock(rawContent);
            List<Map<String, String>> params = parseParameterJson(cleanedContent);
            log.info("[AI生成参数] 成功 => API=" + api.getUrl()
                    + ", 生成参数组数=" + params.size()
                    + ", 原始响应长度=" + (rawContent != null ? rawContent.length() : 0));
            for (int i = 0; i < params.size(); i++) {
                log.info("[AI生成参数] 第" + (i + 1) + "组: " + params.get(i));
            }
            if (params.isEmpty()) {
                return new GenerateResult(
                        rawContent,
                        params,
                        true,
                        "AI返回内容未解析出有效参数，请检查模型输出格式或调整提示词。\n原始响应预览: "
                                + (rawContent != null && rawContent.length() > 500
                                    ? rawContent.substring(0, 500) + "..." : rawContent)
                );
            }
            return new GenerateResult(rawContent, params, true);
        } catch (Exception e) {
            log.warn("[AI生成参数] 失败 => API=" + api.getUrl()
                    + ", 异常=" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "，降级使用默认值");
            return new GenerateResult(
                    "⚠ AI调用失败: " + e.getMessage() + "\n降级使用本地默认值生成",
                    generateDefaultParameters(api, scenario),
                    false,
                    "AI调用失败: " + e.getClass().getSimpleName() + " - " + e.getMessage()
            );
        }
    }

    /**
     * 调用ARK并返回原始响应内容（不解析）
     */
    private String callArkChatCompletionsRaw(ApiDefinition api, TestScenario scenario,
                                               RestAutoLabSettingsState settings) throws Exception {
        JsonObject requestBody = buildChatCompletionRequest(api, scenario, settings);
        String baseUrl = settings.getAiServerUrl();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        // 路径可配置：OpenAI 标准用 /chat/completions，部分私有部署/Qwen 网关用 /chat；
        // 留空时直接请求服务器URL根路径
        String apiPath = settings.getAiApiPath();
        String fullUrl = apiPath.isBlank() ? baseUrl
                : baseUrl + (apiPath.startsWith("/") ? apiPath : "/" + apiPath);

        String requestJson = gson.toJson(requestBody);
        log.info("[AI-HTTP] 请求 => URL=" + fullUrl
                + ", model=" + settings.getAiModel()
                + ", stream=false"
                + ", 请求体长度=" + requestJson.length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(RestAutoLabConstants.AI_REQUEST_TIMEOUT_SECONDS))
                .header(RestAutoLabConstants.HEADER_CONTENT_TYPE, RestAutoLabConstants.DEFAULT_CONTENT_TYPE)
                .header(RestAutoLabConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        long httpStart = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long httpDuration = System.currentTimeMillis() - httpStart;

        log.info("[AI-HTTP] 响应 => 状态码=" + response.statusCode()
                + ", 耗时=" + httpDuration + "ms"
                + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

        if (response.statusCode() < RestAutoLabConstants.HTTP_SUCCESS_MIN
                || response.statusCode() > RestAutoLabConstants.HTTP_SUCCESS_MAX) {
            log.warn("[AI-HTTP] 失败 => 状态码=" + response.statusCode()
                    + ", 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK API返回状态码: " + response.statusCode()
                    + " (URL: " + fullUrl + "), body: " + response.body());
        }

        // 提取content字段
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[AI-HTTP] 响应解析失败：无choices字段, 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK响应中无choices字段");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            log.warn("[AI-HTTP] 响应解析失败：无message字段, 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK响应中无message字段");
        }
        String content = message.get("content").getAsString();
        log.info("[AI-HTTP] 解析成功 => content长度=" + content.length()
                + ", content预览=" + truncate(content, 500));
        return content;
    }

    // ================================================================
    // ARK Chat Completions 调用
    // ================================================================

    /**
     * 调用火山引擎ARK Chat Completions API（OpenAI兼容协议）
     */
    private List<Map<String, String>> callArkChatCompletions(ApiDefinition api, TestScenario scenario,
                                                              RestAutoLabSettingsState settings) throws Exception {
        // 构建 Chat Completions 请求体
        JsonObject requestBody = buildChatCompletionRequest(api, scenario, settings);

        // 拼接完整URL（路径可配置，留空时直接请求服务器URL根路径）
        String baseUrl = settings.getAiServerUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String apiPath = settings.getAiApiPath();
        String fullUrl = apiPath.isBlank() ? baseUrl
                : baseUrl + (apiPath.startsWith("/") ? apiPath : "/" + apiPath);

        String requestJson = gson.toJson(requestBody);
        log.info("[AI-HTTP] 请求(callArkChatCompletions) => URL=" + fullUrl
                + ", model=" + settings.getAiModel()
                + ", 请求体长度=" + requestJson.length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(RestAutoLabConstants.AI_REQUEST_TIMEOUT_SECONDS))
                .header(RestAutoLabConstants.HEADER_CONTENT_TYPE, RestAutoLabConstants.DEFAULT_CONTENT_TYPE)
                .header(RestAutoLabConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        long httpStart = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long httpDuration = System.currentTimeMillis() - httpStart;

        log.info("[AI-HTTP] 响(callArkChatCompletions) => 状态码=" + response.statusCode()
                + ", 耗时=" + httpDuration + "ms"
                + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

        if (response.statusCode() < RestAutoLabConstants.HTTP_SUCCESS_MIN
                || response.statusCode() > RestAutoLabConstants.HTTP_SUCCESS_MAX) {
            log.warn("[AI-HTTP] 失败(callArkChatCompletions) => 状态码=" + response.statusCode()
                    + ", 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK API返回状态码: " + response.statusCode()
                    + " (URL: " + fullUrl + "), body: " + response.body());
        }

        // 解析 Chat Completions 响应
        List<Map<String, String>> result = parseChatCompletionResponse(response.body());
        log.info("[AI-HTTP] 解析成功(callArkChatCompletions) => 参数组数=" + result.size());
        return result;
    }

    /**
     * 构建 OpenAI Chat Completions 请求体
     *
     * 格式：
     * {
     *   "model": "doubao-seed-2.0-pro",
     *   "messages": [
     *     {"role": "system", "content": "..."},
     *     {"role": "user", "content": "..."}
     *   ],
     *   "temperature": 0.7,
     *   "stream": false
     * }
     *
     * 兼容性说明：
     * - model 字段为必填，由设置中的"主模型"提供（如 doubao-seed-2.0-pro / Qwen3.5-35B-A3B）
     * - stream:false 显式声明非流式响应，兼容 vLLM、Qwen 网关等私有部署
     * - 认证通过 HTTP Header Authorization: Bearer {token} 传递（在调用方设置）
     */
    private JsonObject buildChatCompletionRequest(ApiDefinition api, TestScenario scenario,
                                                   RestAutoLabSettingsState settings) {
        JsonObject root = new JsonObject();
        root.addProperty("model", settings.getAiModel());

        JsonArray messages = new JsonArray();

        // System message —— 在用户 system prompt 基础上追加场景指令，确保 system 层也场景感知
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", buildSystemPrompt(settings.getAiSystemPrompt(), scenario));
        messages.add(systemMsg);

        // User message - 包含API详情和生成要求
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", buildUserPrompt(api, scenario));
        messages.add(userMsg);

        root.add("messages", messages);
        root.addProperty("temperature", 0.7);
        // 显式声明非流式响应，兼容 vLLM/Qwen 等私有部署网关
        root.addProperty("stream", false);

        return root;
    }

    /**
     * 构建场景感知的 System Prompt。
     * <p>在用户自定义的 system prompt 基础上追加一条场景指令，使 system 层与 user 层
     * 对场景的认知一致，避免 system 层「生成真实值」与异常值场景产生矛盾。
     * 仅追加、不删改用户原文，保证自定义内容不受损。</p>
     */
    private String buildSystemPrompt(String baseSystemPrompt, TestScenario scenario) {
        String directive;
        switch (scenario) {
            case NORMAL:
                directive = "当前为【正常值】场景：只生成符合业务语义的真实正常值，"
                        + "禁止 0、-1、空串、null 及任何异常/非法值。";
                break;
            case BOUNDARY:
                directive = "当前为【边界值】场景：必须生成边界条件数据"
                        + "（0、-1、空串、null、最大值、最小值），这是边界测试的正当需求，"
                        + "优先级高于「生成真实值」的一般要求。";
                break;
            case ABNORMAL:
                directive = "当前为【异常值】场景：必须生成非法/异常数据"
                        + "（SQL注入、XSS、超长字符串、类型错误、特殊字符等），这是异常测试的正当需求，"
                        + "优先级高于「生成真实值」的一般要求。";
                break;
            case FULL:
                directive = "当前为【全量覆盖】场景：必须同时返回正常值、边界值、异常值三类数据组成的数组，"
                        + "并用 scenario 字段标注每组类型。";
                break;
            default:
                directive = "";
        }
        return baseSystemPrompt + "\n\n" + directive;
    }

    /**
     * 构建发送给AI的User Prompt
     * <p>基于用户在设置中自定义的提示词模板，通过占位符注入API动态信息与场景指令。
     * 场景相关的规则表、禁止项、返回格式由 {@link #buildScenarioRules} / {@link #buildReturnFormat}
     * 按场景动态生成，确保正常/边界/异常/全量四种场景各有针对性、互不矛盾。</p>
     * <p>兼容旧模板：若模板不含 {@code ${SCENARIO_RULES}} / {@code ${RETURN_FORMAT}} 占位符
     * （老版本自定义模板），则将场景指令追加到 prompt 末尾——末尾内容在 LLM 注意力中权重更高，
     * 可覆盖模板主体里可能与场景冲突的旧规则。</p>
     */
    private String buildUserPrompt(ApiDefinition api, TestScenario scenario) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        String template = settings.getAiUserPromptTemplate();

        String description = api.getDescription().isBlank() ? "(无)" : api.getDescription();
        String scenarioRules = buildScenarioRules(scenario);
        String returnFormat = buildReturnFormat(scenario);

        boolean hasRulesPlaceholder = template.contains("${SCENARIO_RULES}");
        boolean hasFormatPlaceholder = template.contains("${RETURN_FORMAT}");

        String prompt = template
                .replace("${API_URL}", String.valueOf(api.getUrl()))
                .replace("${HTTP_METHOD}", String.valueOf(api.getHttpMethod()))
                .replace("${API_NAME}", String.valueOf(api.getName()))
                .replace("${CONTROLLER_NAME}", String.valueOf(api.getControllerName()))
                .replace("${DESCRIPTION}", description)
                .replace("${CONTENT_TYPE}", String.valueOf(api.getConsumes()))
                .replace("${PARAMETERS}", buildParametersText(api))
                .replace("${SCENARIO_NAME}", scenario.getDisplayName())
                .replace("${SCENARIO_DESC}", scenario.getDescription())
                .replace("${SCENARIO_RULES}", scenarioRules)
                .replace("${RETURN_FORMAT}", returnFormat)
                .replace("${FULL_HINT}", returnFormat);

        // 旧自定义模板没有新占位符：把场景指令追加到末尾，确保场景生效（末尾优先级高）
        if (!hasRulesPlaceholder) {
            prompt = prompt + "\n\n" + scenarioRules;
        }
        if (!hasFormatPlaceholder) {
            prompt = prompt + "\n\n## 返回格式\n" + returnFormat;
        }
        return prompt;
    }

    /**
     * 按测试场景生成针对性的参数生成规则。
     * <p>核心设计：每种场景的规则、禁止项、取值方向互不矛盾。
     * 正常值场景禁止占位符与异常值；边界值/异常值场景则把 0、-1、空串、超长、注入payload 等
     * 明确列为「应当生成」的正当目标，避免与正常值规则冲突导致 AI 无所适从。</p>
     */
    private String buildScenarioRules(TestScenario scenario) {
        switch (scenario) {
            case NORMAL:
                return "## 参数生成规则（正常值场景，严格执行）\n" +
                        "生成符合业务语义的真实可用正常值，禁止占位符与异常值。\n\n" +
                        "### 禁止项\n" +
                        "- 禁止: test_xxx, mock_xxx, example_xxx, xxx_demo, sample_xxx\n" +
                        "- 禁止: 无意义随机字符(如 asdfgh, qwe123)\n" +
                        "- 禁止: 明显的模板值(如 your_name, your_email)\n" +
                        "- 禁止: 0、-1、空字符串、null 等非正常业务值\n\n" +
                        "### 根据参数名生成对应含义的真实值\n" +
                        "| 参数名包含 | 正确示例 |\n" +
                        "|---|---|\n" +
                        "| name/userName/nickname | 张三、李四、zhangsan |\n" +
                        "| email/mail | zhangsan@company.com |\n" +
                        "| phone/mobile/tel | 13800138000、15912345678 |\n" +
                        "| id/xxxId/xxx_id | 1、100、1001、2024 |\n" +
                        "| age | 18、25、35 |\n" +
                        "| password/pwd | Abc@123456、P@ssw0rd |\n" +
                        "| address/addr | 北京市朝阳区建国路88号 |\n" +
                        "| price/amount/money | 99.90、199.00、0.01 |\n" +
                        "| createTime/updateTime | 2024-06-15 10:30:00 |\n" +
                        "| startTime/endTime | 2024-01-01、2024-12-31 |\n" +
                        "| status/state | 0、1、ACTIVE、PENDING |\n" +
                        "| type/category | NORMAL、VIP、default |\n" +
                        "| title/subject | 项目进度报告、Q2季度总结 |\n" +
                        "| content/description | 这是一段描述信息、详细说明 |\n" +
                        "| url/link/website | https://www.example.com |\n" +
                        "| code/no/number | ORD20240615001、A10001 |\n" +
                        "| page/pageNum/pageNo | 1 |\n" +
                        "| size/pageSize/limit | 10、20、50 |\n" +
                        "| keyword/search/query | 手机、电脑、Java |\n\n" +
                        "### 类型匹配规则\n" +
                        "- Integer/int/Long/long: 纯数字，不带引号（如 1、100、1001）\n" +
                        "- Double/Float/BigDecimal: 小数，不带引号（如 99.90）\n" +
                        "- Boolean/boolean: true 或 false\n" +
                        "- String: 带引号的字符串\n" +
                        "- Date/LocalDate: \"2024-06-15\" 格式\n" +
                        "- DateTime/LocalDateTime: \"2024-06-15 10:30:00\" 格式\n" +
                        "- List/Array: [\"item1\", \"item2\"]";

            case BOUNDARY:
                return "## 参数生成规则（边界值场景，严格执行）\n" +
                        "本场景的目标是生成边界条件测试数据，必须覆盖最小值、最大值、空值/零值、临界值。\n" +
                        "【重要】本场景允许且应当使用 0、-1、空字符串 \"\"、null、最大值、最小值等边界值，\n" +
                        "这是边界测试的正当需求，优先级高于任何「禁止 0/-1/空」的一般规则。\n\n" +
                        "### 各类型边界值要求（每个参数须覆盖以下多种边界）\n" +
                        "- 整数(Integer/Long): 0、1、-1、2147483647(最大)、-2147483648(最小)\n" +
                        "- 浮点数(Double/Float): 0.0、0.01、-0.01、1.7976931348623157E308(最大)、4.9E-324(最小正)\n" +
                        "- 字符串(String): \"\"(空串)、\" \"(单空格)、\"a\"(单字符)、255字符(常见上限)、1000字符(超长)\n" +
                        "- Boolean: true、false\n" +
                        "- 日期(Date): \"1970-01-01\"(最早)、\"2099-12-31\"(最晚)、\"\"(空)\n" +
                        "- 日期时间(DateTime): \"1970-01-01 00:00:00\"、\"2099-12-31 23:59:59\"\n" +
                        "- 集合(List/Array): [](空数组)、[单元素]、[多元素]\n" +
                        "- 必填字段: 同样要测试空值/null 边界，以验证服务端校验是否生效\n\n" +
                        "### 数量要求\n" +
                        "整体返回 3-5 组完整参数组合，每组对应一种边界类型（如：全零值组、全最大值组、全空值组、临界值组）。";

            case ABNORMAL:
                return "## 参数生成规则（异常值场景，严格执行）\n" +
                        "本场景的目标是生成异常/非法测试数据，验证接口的容错性与校验逻辑。\n" +
                        "【重要】本场景必须使用非法格式、超长字符串、特殊字符、类型错误、注入payload 等异常值，\n" +
                        "这是异常测试的正当需求，优先级高于任何「禁止 test_xxx/随机字符/超长」的一般规则。\n\n" +
                        "### 各类型异常值要求\n" +
                        "- 字符串(String):\n" +
                        "  * 超长字符串: 10000个字符的连续 'a'\n" +
                        "  * 特殊字符: !@#$%^&*()<>?/|{}[]\n" +
                        "  * SQL注入: ' OR '1'='1、'; DROP TABLE users--\n" +
                        "  * XSS: <script>alert(1)</script>、<img src=x onerror=alert(1)>\n" +
                        "  * null、\"\"(空串)、\"   \"(纯空格)\n" +
                        "- 整数(Integer/Long):\n" +
                        "  * 类型错误: \"abc\"、\"NaN\"、\"Infinity\"\n" +
                        "  * 超大数: 99999999999999999999\n" +
                        "  * 负数(若业务不允许): -1\n" +
                        "  * 浮点数: 1.5\n" +
                        "- 浮点数(Double/Float): \"abc\"、Infinity、-1.#IND\n" +
                        "- Boolean: \"yes\"、\"1\"、null(非标准布尔值)\n" +
                        "- 日期(Date): \"2024-13-45\"(非法月日)、\"not-a-date\"、\"0000-00-00\"\n" +
                        "- 集合(List/Array): \"not_an_array\"(字符串代替数组)、[null,null]\n\n" +
                        "### 数量要求\n" +
                        "整体返回 3-5 组完整参数组合，每组针对一种异常类型（如：SQL注入组、XSS组、超长字符串组、类型错误组）。";

            case FULL:
                return "## 参数生成规则（全量覆盖场景，严格执行）\n" +
                        "本场景需同时生成正常值、边界值、异常值三类测试数据，全面覆盖接口测试场景。\n" +
                        "【重要】本场景优先级高于任何单一生成规则，必须返回包含三类数据的数组。\n\n" +
                        "### 三类数据要求\n" +
                        "- 正常值: 符合业务语义的真实可用值（如 id=1001, name=\"张三\", email=\"zhangsan@company.com\"）\n" +
                        "- 边界值: 0、-1、空字符串、最大值、最小值等边界条件\n" +
                        "- 异常值: 非法格式、超长字符串、特殊字符、SQL注入、XSS 等\n\n" +
                        "### 数量要求\n" +
                        "至少返回 3 组数据，每组用 \"scenario\" 字段标注类型：\n" +
                        "  {\"scenario\":\"正常值\", ...}、{\"scenario\":\"边界值\", ...}、{\"scenario\":\"异常值\", ...}";

            default:
                return buildScenarioRules(TestScenario.NORMAL);
        }
    }

    /**
     * 按测试场景生成返回格式指令。
     * <p>正常值返回单组 JSON 对象；边界值/异常值/全量返回 JSON 数组（多组），
     * 确保 AI 不会因「单组」格式约束而只生成一组数据。</p>
     */
    private String buildReturnFormat(TestScenario scenario) {
        switch (scenario) {
            case NORMAL:
                return "直接返回纯JSON对象，不要包含 ```json 标记或其他文字。\n" +
                        "单组: {\"param_name\": \"value\", \"id\": 1001}";
            case BOUNDARY:
                return "直接返回纯JSON数组（3-5组边界数据），不要包含 ```json 标记或其他文字。\n" +
                        "示例: [{\"id\":0,\"name\":\"\"}, {\"id\":1,\"name\":\"a\"}, {\"id\":2147483647,\"name\":\"255字符...\"}]";
            case ABNORMAL:
                return "直接返回纯JSON数组（3-5组异常数据），不要包含 ```json 标记或其他文字。\n" +
                        "示例: [{\"id\":\"abc\",\"name\":\"' OR '1'='1\"}, {\"id\":-1,\"name\":\"<script>alert(1)</script>\"}, {\"id\":99999999999999999999,\"name\":\"10000个a...\"}]";
            case FULL:
                return "全量覆盖返回JSON数组，每组用 \"scenario\" 字段标注类型，不要包含 ```json 标记或其他文字。\n" +
                        "示例: [{\"scenario\":\"正常值\",\"id\":1001,\"name\":\"张三\"}, {\"scenario\":\"边界值\",\"id\":0,\"name\":\"\"}, {\"scenario\":\"异常值\",\"id\":\"abc\",\"name\":\"' OR '1'='1\"}]";
            default:
                return buildReturnFormat(TestScenario.NORMAL);
        }
    }

    /**
     * 构建参数列表文本（注入到 ${PARAMETERS} 占位符）
     */
    private String buildParametersText(ApiDefinition api) {
        StringBuilder sb = new StringBuilder();
        if (api.getParameters().isEmpty()) {
            sb.append("（该接口无入参）\n");
        }
        for (ApiParameter param : api.getParameters()) {
            sb.append("- **").append(param.getName()).append("**");
            sb.append(" (类型: ").append(param.getType());
            sb.append(", 位置: ").append(param.getLocation().name());
            sb.append(", 必填: ").append(param.isRequired()).append(")\n");
            if (!param.getDescription().isBlank()) {
                sb.append("  描述: ").append(param.getDescription()).append("\n");
            }
            if (!param.getDefaultValue().isBlank()) {
                sb.append("  默认值: ").append(param.getDefaultValue()).append("\n");
            }
            if (!param.getChildren().isEmpty()) {
                sb.append("  子字段:\n");
                for (ApiParameter child : param.getChildren()) {
                    sb.append("    - ").append(child.getName())
                            .append(" (").append(child.getType()).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析 Chat Completions 响应
     *
     * 响应格式：
     * {
     *   "choices": [{
     *     "message": {
     *       "role": "assistant",
     *       "content": "{\"param_name\": \"value\"}"
     *     }
     *   }]
     * }
     */
    private List<Map<String, String>> parseChatCompletionResponse(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("ARK响应中无choices字段");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new RuntimeException("ARK响应中无message字段");
        }

        String content = message.get("content").getAsString();
        // 清理可能的 markdown 代码块标记
        content = cleanMarkdownCodeBlock(content);

        return parseParameterJson(content);
    }

    /**
     * 清理AI返回内容中可能包含的markdown代码块标记
     */
    private String cleanMarkdownCodeBlock(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /**
     * 解析参数JSON（支持单对象和数组）
     */
    private List<Map<String, String>> parseParameterJson(String jsonContent) {
        JsonElement jsonElement = JsonParser.parseString(jsonContent);

        if (jsonElement.isJsonObject()) {
            return List.of(jsonObjectToMap(jsonElement.getAsJsonObject()));
        } else if (jsonElement.isJsonArray()) {
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonElement element : jsonElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    result.add(jsonObjectToMap(element.getAsJsonObject()));
                }
            }
            return result.isEmpty() ? List.of(Collections.emptyMap()) : result;
        } else {
            throw new RuntimeException("AI响应格式无法解析: " + jsonContent);
        }
    }

    /**
     * JsonObject 转为 Map<String, String>
     */
    private Map<String, String> jsonObjectToMap(JsonObject obj) {
        Map<String, String> map = new LinkedHashMap<>();
        obj.entrySet().forEach(entry -> {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                map.put(key, value.getAsString());
            } else if (value.isJsonObject() || value.isJsonArray()) {
                map.put(key, gson.toJson(value));
            } else {
                map.put(key, value.toString());
            }
        });
        return map;
    }

    // ================================================================
    // 降级方案 & 辅助功能
    // ================================================================

    /**
     * 基于类型的默认参数生成（AI服务不可用时的降级方案）
     */
    public Map<String, String> generateDefaultParameters(ApiDefinition api) {
        Map<String, String> params = new LinkedHashMap<>();
        for (ApiParameter param : api.getParameters()) {
            String defaultValue = param.generateDefaultValue();
            // 上下文感知：根据参数名语义生成更合理的测试数据
            defaultValue = applyContextAwareHeuristic(param.getName(), param.getType(), defaultValue);
            params.put(param.getName(), defaultValue);
            if (param.isComplexType() && param.getLocation() == ParameterLocation.BODY) {
                params.put(param.getName(), defaultValue);
            }
        }
        return params;
    }

    /**
     * 场景感知的降级参数生成（AI 不可用时按场景生成对应的默认测试数据）。
     * <p>确保即使 AI 调用失败，不同场景仍能产出场景相符的数据，而非一律返回正常默认值：
     * <ul>
     *   <li>NORMAL: 单组上下文感知的正常值</li>
     *   <li>BOUNDARY: 多组边界值（零值组、最大值组、空值组）</li>
     *   <li>ABNORMAL: 多组异常值（SQL注入组、类型错误组、超长字符串组）</li>
     *   <li>FULL: 正常 + 边界 + 异常 三组</li>
     * </ul></p>
     */
    public List<Map<String, String>> generateDefaultParameters(ApiDefinition api, TestScenario scenario) {
        switch (scenario) {
            case BOUNDARY:
                return buildBoundaryDefaults(api);
            case ABNORMAL:
                return buildAbnormalDefaults(api);
            case FULL:
                List<Map<String, String>> full = new ArrayList<>();
                full.add(generateDefaultParameters(api));
                full.add(buildBoundaryDefaults(api).get(0));
                full.add(buildAbnormalDefaults(api).get(0));
                return full;
            case NORMAL:
            default:
                return List.of(generateDefaultParameters(api));
        }
    }

    /** 生成边界值默认参数组：零值组、最大值组、空值组 */
    private List<Map<String, String>> buildBoundaryDefaults(ApiDefinition api) {
        List<Map<String, String>> groups = new ArrayList<>();
        groups.add(buildTypedDefaults(api, BoundaryKind.ZERO));
        groups.add(buildTypedDefaults(api, BoundaryKind.MAX));
        groups.add(buildTypedDefaults(api, BoundaryKind.EMPTY));
        return groups;
    }

    /** 生成异常值默认参数组：SQL注入组、类型错误组、超长字符串组 */
    private List<Map<String, String>> buildAbnormalDefaults(ApiDefinition api) {
        List<Map<String, String>> groups = new ArrayList<>();
        groups.add(buildTypedDefaults(api, BoundaryKind.SQL_INJECT));
        groups.add(buildTypedDefaults(api, BoundaryKind.TYPE_ERROR));
        groups.add(buildTypedDefaults(api, BoundaryKind.LONG_STR));
        return groups;
    }

    private enum BoundaryKind { ZERO, MAX, EMPTY, SQL_INJECT, TYPE_ERROR, LONG_STR }

    /** 按边界/异常类型为每个参数生成对应的默认值 */
    private Map<String, String> buildTypedDefaults(ApiDefinition api, BoundaryKind kind) {
        Map<String, String> params = new LinkedHashMap<>();
        for (ApiParameter param : api.getParameters()) {
            params.put(param.getName(), boundaryValueFor(param.getName(), param.getType(), kind));
        }
        return params;
    }

    /** 根据参数 Java 类型与边界/异常类型，返回对应的边界或异常测试值 */
    private String boundaryValueFor(String paramName, String javaType, BoundaryKind kind) {
        boolean isInt = "Integer".equals(javaType) || "int".equals(javaType) || "Long".equals(javaType) || "long".equals(javaType);
        boolean isFloat = "Double".equals(javaType) || "double".equals(javaType)
                || "Float".equals(javaType) || "float".equals(javaType) || "BigDecimal".equals(javaType);
        boolean isBool = "Boolean".equals(javaType) || "boolean".equals(javaType);
        boolean isStr = "String".equals(javaType);

        switch (kind) {
            case ZERO:
                if (isInt) return "0";
                if (isFloat) return "0.0";
                if (isBool) return "false";
                if (isStr) return "";
                return "";
            case MAX:
                if (isInt) return "2147483647";
                if (isFloat) return "1.7976931348623157E308";
                if (isBool) return "true";
                if (isStr) return "边界字符串边界字符串边界字符串边界字符串边界字符串边界字符串";
                return "";
            case EMPTY:
                if (isStr) return "";
                if (isInt) return "0";
                if (isFloat) return "0.0";
                if (isBool) return "false";
                return "";
            case SQL_INJECT:
                if (isStr) return "' OR '1'='1";
                if (isInt) return "1; DROP TABLE users--";
                return "' OR '1'='1";
            case TYPE_ERROR:
                if (isInt) return "abc";
                if (isFloat) return "NaN";
                if (isBool) return "yes";
                if (isStr) return "12345";
                return "abc";
            case LONG_STR:
                StringBuilder sb = new StringBuilder(10000);
                for (int i = 0; i < 10000; i++) sb.append('a');
                if (isStr) return sb.toString();
                if (isInt) return "99999999999999999999";
                return sb.toString();
            default:
                return "";
        }
    }

    /**
     * 上下文启发式：根据参数字段名生成更符合业务语义的测试数据
     * 即使AI服务不可用，也能生成合理的phone/email/id等格式值
     */
    private String applyContextAwareHeuristic(String paramName, String javaType, String defaultValue) {
        if (paramName == null || paramName.isBlank()) return defaultValue;
        String name = paramName.toLowerCase();

        // 手机号识别 (phone/mobile/tel/手机/电话)
        if (name.contains("phone") || name.contains("mobile") || name.contains("tel")
                || name.contains("手机") || name.contains("电话")) {
            if ("String".equals(javaType)) return "13800138000";
        }

        // 邮箱识别 (email/mail/邮箱)
        if (name.contains("email") || name.contains("mail") || name.contains("邮箱")) {
            if ("String".equals(javaType)) return "test@example.com";
        }

        // 身份证识别 (idcard/id_card/cert/身份证)
        if (name.contains("idcard") || name.contains("id_card") || name.contains("certno")
                || name.contains("身份证") || name.endsWith("id_no")) {
            if ("String".equals(javaType)) return "110101199001011234";
        }

        // 用户名/账号 (username/account/login/name if not containing id)
        if ((name.contains("username") || name.contains("account") || name.contains("loginname"))
                && "String".equals(javaType)) {
            return "testuser01";
        }

        // 密码 (password/pwd)
        if ((name.contains("password") || name.contains("pwd")) && "String".equals(javaType)) {
            return "Test@123456";
        }

        // URL识别
        if (name.contains("url") || name.contains("website") || name.contains("avatar")) {
            if ("String".equals(javaType)) return "https://example.com/test.png";
        }

        // 地址 (address/addr)
        if (name.contains("address") || name.contains("addr") || name.endsWith("地址")) {
            if ("String".equals(javaType)) return "北京市海淀区中关村大街1号";
        }

        // 姓名 (name 但不是 username/filename)
        if (name.equals("name") || name.contains("realname") || name.endsWith("姓名")) {
            if ("String".equals(javaType)) return "张三";
        }

        // 金额/价格 (price/amount/money/fee/salary)
        if ((name.contains("price") || name.contains("amount") || name.contains("money")
                || name.contains("fee") || name.contains("salary") || name.contains("total"))
                && ("BigDecimal".equals(javaType) || "Double".equals(javaType) || "double".equals(javaType)
                || "Float".equals(javaType) || "float".equals(javaType))) {
            return "99.99";
        }

        // 分页页码
        if (name.equals("page") || name.equals("pagenum") || name.contains("page_no")) {
            if ("Integer".equals(javaType) || "int".equals(javaType) || "Long".equals(javaType)) {
                return "1";
            }
        }
        if (name.equals("pagesize") || name.equals("size") || name.contains("page_size")) {
            if ("Integer".equals(javaType) || "int".equals(javaType) || "Long".equals(javaType)) {
                return "10";
            }
        }

        return defaultValue;
    }

    /**
     * 从本地JSON导入测试用例参数
     */
    public List<Map<String, String>> importFromJson(String jsonContent) {
        try {
            JsonElement element = JsonParser.parseString(jsonContent);
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("params")) {
                    return List.of(jsonObjectToMap(obj.getAsJsonObject("params")));
                } else {
                    return List.of(jsonObjectToMap(obj));
                }
            } else if (element.isJsonArray()) {
                return parseParameterJson(jsonContent);
            } else {
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("导入测试用例失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从API文档（Swagger/OpenAPI JSON）中提取参数示例
     */
    public Map<String, String> extractFromApiDoc(String apiDocJson, String apiPath, String httpMethod) {
        Map<String, String> params = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(apiDocJson).getAsJsonObject();
            JsonObject paths = root.getAsJsonObject("paths");
            if (paths == null) return params;
            JsonObject pathItem = paths.getAsJsonObject(apiPath);
            if (pathItem == null) return params;
            JsonObject operation = pathItem.getAsJsonObject(httpMethod.toLowerCase());
            if (operation == null) return params;

            // 提取parameters中的示例值
            JsonArray parameters = operation.getAsJsonArray("parameters");
            if (parameters != null) {
                for (JsonElement paramElement : parameters) {
                    JsonObject param = paramElement.getAsJsonObject();
                    JsonElement nameEl = param.get("name");
                    if (nameEl == null) continue;
                    String name = nameEl.getAsString();

                    String example = null;
                    JsonElement exampleEl = param.get("example");
                    if (exampleEl != null && !exampleEl.isJsonNull()) {
                        example = exampleEl.getAsString();
                    }
                    if (example == null) {
                        JsonElement defaultEl = param.get("default");
                        if (defaultEl != null && !defaultEl.isJsonNull()) {
                            example = defaultEl.getAsString();
                        }
                    }
                    if (example == null) {
                        JsonObject schema = param.getAsJsonObject("schema");
                        if (schema != null) {
                            JsonElement schemaExample = schema.get("example");
                            if (schemaExample != null && !schemaExample.isJsonNull()) {
                                example = schemaExample.getAsString();
                            }
                        }
                    }
                    if (example != null) {
                        params.put(name, example);
                    }
                }
            }

            // 提取requestBody中的示例值（OpenAPI 3.x）
            JsonObject requestBody = operation.getAsJsonObject("requestBody");
            if (requestBody != null) {
                JsonObject content = requestBody.getAsJsonObject("content");
                if (content != null) {
                    JsonObject jsonContent = content.getAsJsonObject(RestAutoLabConstants.DEFAULT_CONTENT_TYPE);
                    if (jsonContent != null) {
                        JsonElement example = jsonContent.get("example");
                        if (example != null) {
                            params.put("_body", gson.toJson(example));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析API文档失败: " + e.getMessage());
        }
        return params;
    }

    /**
     * 将测试参数导出为JSON格式
     */
    public String exportToJson(Map<String, String> params, String profileName) {
        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("name", profileName);
        exportData.put("params", params);
        exportData.put("exportedAt", System.currentTimeMillis());
        return gson.toJson(exportData);
    }

    /** 默认profileName */
    public String exportToJson(Map<String, String> params) {
        return exportToJson(params, "测试配置");
    }

    /**
     * AI生成响应断言规则
     */
    public List<ResponseAssertion> generateAssertions(ApiDefinition api) {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        log.info("[AI生成断言] 开始 => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 返回类型=" + api.getResponseBodyType());

        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成断言] 跳过：AI服务器URL未配置，生成默认断言");
            return generateDefaultAssertions(api);
        }
        if (RestAutoLabConstants.isLocalModelToken(settings.getAiToken())) {
            log.info("[AI生成断言] 检测到本地模型（token 为空或字面量 'Bearer'），将使用占位 Bearer 调用");
        }

        try {
            JsonObject requestBody = buildAssertionRequest(api, settings);
            String baseUrl = settings.getAiServerUrl();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            // 路径可配置：与参数生成保持一致；留空时直接请求服务器URL根路径
            String apiPath = settings.getAiApiPath();
            String fullUrl = apiPath.isBlank() ? baseUrl
                    : baseUrl + (apiPath.startsWith("/") ? apiPath : "/" + apiPath);

            String requestJson = gson.toJson(requestBody);
            log.info("[AI生成断言] HTTP请求 => URL=" + fullUrl
                    + ", model=" + settings.getAiModel()
                    + ", stream=false"
                    + ", 请求体长度=" + requestJson.length());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(RestAutoLabConstants.AI_REQUEST_TIMEOUT_SECONDS))
                    .header(RestAutoLabConstants.HEADER_CONTENT_TYPE, RestAutoLabConstants.DEFAULT_CONTENT_TYPE)
                    .header(RestAutoLabConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            long httpStart = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long httpDuration = System.currentTimeMillis() - httpStart;
            log.info("[AI生成断言] HTTP响应 => 状态码=" + response.statusCode()
                    + ", 耗时=" + httpDuration + "ms"
                    + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

            if (response.statusCode() < RestAutoLabConstants.HTTP_SUCCESS_MIN
                    || response.statusCode() > RestAutoLabConstants.HTTP_SUCCESS_MAX) {
                log.warn("[AI生成断言] HTTP失败 => 状态码=" + response.statusCode()
                        + ", 响应体=" + truncate(response.body(), 2000));
                throw new RuntimeException("AI API返回: " + response.statusCode());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            content = content.trim();
            if (content.startsWith("```json")) content = content.substring(7);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();
            log.info("[AI生成断言] 解析content => 长度=" + content.length()
                    + ", 预览=" + truncate(content, 500));

            // Parse assertion array
            List<ResponseAssertion> assertions = parseAssertionsFromJson(content);
            log.info("[AI生成断言] 成功 => 生成断言数=" + assertions.size());
            return assertions;
        } catch (Exception e) {
            log.warn("[AI生成断言] 失败 => API=" + api.getUrl()
                    + ", 异常=" + e.getClass().getSimpleName() + ": " + e.getMessage() + "，使用默认断言");
            return generateDefaultAssertions(api);
        }
    }

    private JsonObject buildAssertionRequest(ApiDefinition api, RestAutoLabSettingsState settings) {
        JsonObject root = new JsonObject();
        root.addProperty("model", settings.getAiModel());

        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", RestAutoLabConstants.AI_ASSERTION_SYSTEM_PROMPT);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下API生成响应断言：\n\n");
        sb.append("- URL: ").append(api.getUrl()).append("\n");
        sb.append("- 方法: ").append(api.getHttpMethod()).append("\n");
        sb.append("- 名称: ").append(api.getName()).append("\n");
        sb.append("- 返回类型: ").append(api.getResponseBodyType()).append("\n\n");
        sb.append("至少生成3-5条合理断言。例如：状态码为200，响应时间小于3000ms，响应体包含code字段且值为0等。");
        userMsg.addProperty("content", sb.toString());
        messages.add(userMsg);

        root.add("messages", messages);
        root.addProperty("temperature", 0.3);
        // 显式声明非流式响应，兼容 vLLM/Qwen 等私有部署网关
        root.addProperty("stream", false);
        return root;
    }

    private List<ResponseAssertion> parseAssertionsFromJson(String json) {
        List<ResponseAssertion> result = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                ResponseAssertion a = new ResponseAssertion();
                if (obj.has("type")) a.setType(obj.get("type").getAsString());
                if (obj.has("target")) a.setTarget(obj.get("target").getAsString());
                if (obj.has("expected")) a.setExpected(obj.get("expected").getAsString());
                if (obj.has("operator")) a.setOperator(obj.get("operator").getAsString());
                result.add(a);
            }
        } catch (Exception e) {
            log.warn("解析AI断言JSON失败: " + e.getMessage());
        }
        if (result.isEmpty()) {
            return generateDefaultAssertions(null);
        }
        return result;
    }

    private List<ResponseAssertion> generateDefaultAssertions(ApiDefinition api) {
        List<ResponseAssertion> defaults = new ArrayList<>();
        ResponseAssertion statusAssert = new ResponseAssertion();
        statusAssert.setType("STATUS_CODE");
        statusAssert.setTarget("statusCode");
        statusAssert.setExpected("200");
        statusAssert.setOperator("EQUALS");
        defaults.add(statusAssert);

        ResponseAssertion timeAssert = new ResponseAssertion();
        timeAssert.setType("RESPONSE_TIME");
        timeAssert.setTarget("durationMs");
        timeAssert.setExpected("3000");
        timeAssert.setOperator("LESS_THAN");
        defaults.add(timeAssert);

        return defaults;
    }

    /**
     * 截断字符串用于日志输出，避免超长响应体刷爆日志
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated, total=" + s.length() + ")";
    }

    /**
     * 获取AiParameterService实例的便捷方法
     */
    public static AiParameterService getInstance(Project project) {
        return project.getService(AiParameterService.class);
    }
}