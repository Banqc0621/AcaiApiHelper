package com.ban.acai.ai;

import com.ban.acai.AcaiConstants;
import com.ban.acai.model.*;
import com.ban.acai.settings.AcaiSettingsState;
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
public class AiParameterService {

    private final Project project;
    private final Logger log = Logger.getInstance(AiParameterService.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** HTTP客户端（AI服务调用专用） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(AcaiConstants.AI_CONNECT_TIMEOUT_SECONDS))
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
        if (AcaiConstants.isLocalModelToken(token)) {
            return AcaiConstants.BEARER_PREFIX + AcaiConstants.AI_LOCAL_BEARER_TOKEN;
        }
        return AcaiConstants.BEARER_PREFIX + token;
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
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        log.info("[AI生成参数] 开始(简版) => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 场景=" + scenario + ", 参数个数=" + api.getParameters().size());

        // 检查AI服务是否配置（本地模型无需 API Key，仅校验服务器URL）
        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成参数] 跳过(简版)：AI服务器URL未配置，使用默认值生成策略");
            return List.of(generateDefaultParameters(api));
        }
        if (AcaiConstants.isLocalModelToken(settings.getAiToken())) {
            log.info("[AI生成参数] 检测到本地模型（token 为空或字面量 'Bearer'），将使用占位 Bearer 调用");
        }

        try {
            List<Map<String, String>> result = callArkChatCompletions(api, scenario, settings);
            log.info("[AI生成参数] 成功(简版) => API=" + api.getUrl() + ", 参数组数=" + result.size());
            return result;
        } catch (Exception e) {
            log.warn("[AI生成参数] 失败(简版) => API=" + api.getUrl()
                    + ", 异常=" + e.getClass().getSimpleName() + ": " + e.getMessage() + "，降级使用默认值");
            return List.of(generateDefaultParameters(api));
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
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        log.info("[AI生成参数] 开始 => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 控制器=" + api.getControllerName() + ", 场景=" + scenario
                + ", 参数个数=" + api.getParameters().size());

        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成参数] 跳过：AI服务器URL未配置，使用本地默认值生成策略");
            return new GenerateResult(
                    "⚠ AI未配置，使用本地默认值生成（非AI生成）\n请在AI Tab中配置服务器URL以使用AI生成真实参数",
                    List.of(generateDefaultParameters(api)),
                    false,
                    "AI服务器URL未配置，请在「AI配置」中填写服务器URL"
            );
        }
        if (AcaiConstants.isLocalModelToken(settings.getAiToken())) {
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
                    List.of(generateDefaultParameters(api)),
                    false,
                    "AI调用失败: " + e.getClass().getSimpleName() + " - " + e.getMessage()
            );
        }
    }

    /**
     * 调用ARK并返回原始响应内容（不解析）
     */
    private String callArkChatCompletionsRaw(ApiDefinition api, TestScenario scenario,
                                               AcaiSettingsState settings) throws Exception {
        JsonObject requestBody = buildChatCompletionRequest(api, scenario, settings);
        String baseUrl = settings.getAiServerUrl();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        // 路径可配置：OpenAI 标准用 /chat/completions，部分私有部署/Qwen 网关用 /chat
        String apiPath = settings.getAiApiPath();
        if (!apiPath.startsWith("/")) apiPath = "/" + apiPath;
        String fullUrl = baseUrl + apiPath;

        String requestJson = gson.toJson(requestBody);
        log.info("[AI-HTTP] 请求 => URL=" + fullUrl
                + ", model=" + settings.getAiModel()
                + ", stream=false"
                + ", 请求体长度=" + requestJson.length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(AcaiConstants.AI_REQUEST_TIMEOUT_SECONDS))
                .header(AcaiConstants.HEADER_CONTENT_TYPE, AcaiConstants.DEFAULT_CONTENT_TYPE)
                .header(AcaiConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        long httpStart = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long httpDuration = System.currentTimeMillis() - httpStart;

        log.info("[AI-HTTP] 响应 => 状态码=" + response.statusCode()
                + ", 耗时=" + httpDuration + "ms"
                + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

        if (response.statusCode() < AcaiConstants.HTTP_SUCCESS_MIN
                || response.statusCode() > AcaiConstants.HTTP_SUCCESS_MAX) {
            log.warn("[AI-HTTP] 失败 => 状态码=" + response.statusCode()
                    + ", 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK API返回状态码: " + response.statusCode() + ", body: " + response.body());
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
                                                              AcaiSettingsState settings) throws Exception {
        // 构建 Chat Completions 请求体
        JsonObject requestBody = buildChatCompletionRequest(api, scenario, settings);

        // 拼接完整URL
        String baseUrl = settings.getAiServerUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String fullUrl = baseUrl + AcaiConstants.AI_CHAT_COMPLETIONS_PATH;

        String requestJson = gson.toJson(requestBody);
        log.info("[AI-HTTP] 请求(callArkChatCompletions) => URL=" + fullUrl
                + ", model=" + settings.getAiModel()
                + ", 请求体长度=" + requestJson.length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(AcaiConstants.AI_REQUEST_TIMEOUT_SECONDS))
                .header(AcaiConstants.HEADER_CONTENT_TYPE, AcaiConstants.DEFAULT_CONTENT_TYPE)
                .header(AcaiConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        long httpStart = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long httpDuration = System.currentTimeMillis() - httpStart;

        log.info("[AI-HTTP] 响(callArkChatCompletions) => 状态码=" + response.statusCode()
                + ", 耗时=" + httpDuration + "ms"
                + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

        if (response.statusCode() < AcaiConstants.HTTP_SUCCESS_MIN
                || response.statusCode() > AcaiConstants.HTTP_SUCCESS_MAX) {
            log.warn("[AI-HTTP] 失败(callArkChatCompletions) => 状态码=" + response.statusCode()
                    + ", 响应体=" + truncate(response.body(), 2000));
            throw new RuntimeException("ARK API返回状态码: " + response.statusCode() + ", body: " + response.body());
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
                                                   AcaiSettingsState settings) {
        JsonObject root = new JsonObject();
        root.addProperty("model", settings.getAiModel());

        JsonArray messages = new JsonArray();

        // System message
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", settings.getAiSystemPrompt());
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
     * 构建发送给AI的User Prompt
     * 基于用户在设置中自定义的提示词模板，通过占位符注入API动态信息。
     * 支持占位符见 AcaiConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE 注释
     */
    private String buildUserPrompt(ApiDefinition api, TestScenario scenario) {
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        String template = settings.getAiUserPromptTemplate();

        String description = api.getDescription().isBlank() ? "(无)" : api.getDescription();
        String fullHint = (scenario == TestScenario.FULL)
                ? "全量覆盖返回数组: [{\"正常值\": ...}, {\"边界值\": ...}, {\"异常值\": ...}]\n"
                : "";

        return template
                .replace("${API_URL}", String.valueOf(api.getUrl()))
                .replace("${HTTP_METHOD}", String.valueOf(api.getHttpMethod()))
                .replace("${API_NAME}", String.valueOf(api.getName()))
                .replace("${CONTROLLER_NAME}", String.valueOf(api.getControllerName()))
                .replace("${DESCRIPTION}", description)
                .replace("${CONTENT_TYPE}", String.valueOf(api.getConsumes()))
                .replace("${PARAMETERS}", buildParametersText(api))
                .replace("${SCENARIO_NAME}", scenario.getDisplayName())
                .replace("${SCENARIO_DESC}", scenario.getDescription())
                .replace("${FULL_HINT}", fullHint);
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
                    JsonObject jsonContent = content.getAsJsonObject(AcaiConstants.DEFAULT_CONTENT_TYPE);
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
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        log.info("[AI生成断言] 开始 => API=" + api.getHttpMethod() + " " + api.getUrl()
                + ", 返回类型=" + api.getResponseBodyType());

        if (settings.getAiServerUrl().isBlank()) {
            log.info("[AI生成断言] 跳过：AI服务器URL未配置，生成默认断言");
            return generateDefaultAssertions(api);
        }
        if (AcaiConstants.isLocalModelToken(settings.getAiToken())) {
            log.info("[AI生成断言] 检测到本地模型（token 为空或字面量 'Bearer'），将使用占位 Bearer 调用");
        }

        try {
            JsonObject requestBody = buildAssertionRequest(api, settings);
            String baseUrl = settings.getAiServerUrl();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            // 路径可配置：与参数生成保持一致
            String apiPath = settings.getAiApiPath();
            if (!apiPath.startsWith("/")) apiPath = "/" + apiPath;
            String fullUrl = baseUrl + apiPath;

            String requestJson = gson.toJson(requestBody);
            log.info("[AI生成断言] HTTP请求 => URL=" + fullUrl
                    + ", model=" + settings.getAiModel()
                    + ", stream=false"
                    + ", 请求体长度=" + requestJson.length());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(AcaiConstants.AI_REQUEST_TIMEOUT_SECONDS))
                    .header(AcaiConstants.HEADER_CONTENT_TYPE, AcaiConstants.DEFAULT_CONTENT_TYPE)
                    .header(AcaiConstants.HEADER_AUTHORIZATION, buildAuthHeader(settings.getAiToken()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            long httpStart = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long httpDuration = System.currentTimeMillis() - httpStart;
            log.info("[AI生成断言] HTTP响应 => 状态码=" + response.statusCode()
                    + ", 耗时=" + httpDuration + "ms"
                    + ", 响应体长度=" + (response.body() != null ? response.body().length() : 0));

            if (response.statusCode() < AcaiConstants.HTTP_SUCCESS_MIN
                    || response.statusCode() > AcaiConstants.HTTP_SUCCESS_MAX) {
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

    private JsonObject buildAssertionRequest(ApiDefinition api, AcaiSettingsState settings) {
        JsonObject root = new JsonObject();
        root.addProperty("model", settings.getAiModel());

        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", AcaiConstants.AI_ASSERTION_SYSTEM_PROMPT);
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