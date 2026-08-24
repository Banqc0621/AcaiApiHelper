package com.hronline;

import com.intellij.ui.JBColor;

import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RestAutoLab 常量类 - 集中管理所有硬编码常量
 *
 * 所有硬编码字面量（URL、注解全限定名、超时值、颜色、尺寸等）统一在此管理，
 * 避免魔法值散落在代码各处。
 *
 * 每个常量仅有一个唯一名，无双别名，便于统一维护。
 * 使用 JBColor 保证亮色/暗色主题自动适配。
 */
public final class RestAutoLabConstants {

    private RestAutoLabConstants() {
        // 工具类禁止实例化
    }

    // ═══════════════════════════════════════════════════════════
    // 插件基本信息
    // ═══════════════════════════════════════════════════════════

    /** 插件ID */
    public static final String PLUGIN_ID = "com.banqc.restautolab";

    /** 插件名称 */
    public static final String PLUGIN_NAME = "RestAutoLab";

    /** 插件版本 */
    public static final String PLUGIN_VERSION = "1.0.3";

    /** ToolWindow ID */
    public static final String TOOLWINDOW_ID = "RestAutoLab";

    /** 通知组ID */
    public static final String NOTIFICATION_GROUP = "RestAutoLab.Notification";

    // ═══════════════════════════════════════════════════════════
    // API 来源标记
    // ═══════════════════════════════════════════════════════════

    /** 自动扫描的API */
    public static final String API_SOURCE_AUTO = "AUTO";

    /** 手动添加的API */
    public static final String API_SOURCE_MANUAL = "MANUAL";

    // ═══════════════════════════════════════════════════════════
    // Spring MVC 注解全限定名
    // ═══════════════════════════════════════════════════════════

    /** @RestController */
    public static final String ANNO_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";

    /** @Controller */
    public static final String ANNO_CONTROLLER = "org.springframework.stereotype.Controller";

    /** @RequestMapping */
    public static final String ANNO_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    /** @GetMapping */
    public static final String ANNO_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";

    /** @PostMapping */
    public static final String ANNO_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";

    /** @PutMapping */
    public static final String ANNO_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";

    /** @DeleteMapping */
    public static final String ANNO_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping";

    /** @PatchMapping */
    public static final String ANNO_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping";

    /** @RequestParam */
    public static final String ANNO_REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";

    /** @PathVariable */
    public static final String ANNO_PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";

    /** @RequestBody */
    public static final String ANNO_REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";

    /** @RequestHeader */
    public static final String ANNO_REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader";

    /** @CookieValue */
    public static final String ANNO_COOKIE_VALUE = "org.springframework.web.bind.annotation.CookieValue";

    /** @ModelAttribute */
    public static final String ANNO_MODEL_ATTRIBUTE = "org.springframework.web.bind.annotation.ModelAttribute";

    /** @RequestPart */
    public static final String ANNO_REQUEST_PART = "org.springframework.web.bind.annotation.RequestPart";

    // ═══════════════════════════════════════════════════════════
    // JAX-RS 注解全限定名 - 明确区分 javax / jakarta 两个包空间
    // ═══════════════════════════════════════════════════════════

    /** @Path (javax.ws.rs) */
    public static final String JAXRS_PATH_JAVAX = "javax.ws.rs.Path";

    /** @Path (jakarta.ws.rs) */
    public static final String JAXRS_PATH_JAKARTA = "jakarta.ws.rs.Path";

    /** @GET (javax) */
    public static final String JAXRS_GET_JAVAX = "javax.ws.rs.GET";
    /** @POST (javax) */
    public static final String JAXRS_POST_JAVAX = "javax.ws.rs.POST";
    /** @PUT (javax) */
    public static final String JAXRS_PUT_JAVAX = "javax.ws.rs.PUT";
    /** @DELETE (javax) */
    public static final String JAXRS_DELETE_JAVAX = "javax.ws.rs.DELETE";
    /** @PATCH (javax) */
    public static final String JAXRS_PATCH_JAVAX = "javax.ws.rs.PATCH";

    /** @GET (jakarta) */
    public static final String JAXRS_GET_JAKARTA = "jakarta.ws.rs.GET";
    /** @POST (jakarta) */
    public static final String JAXRS_POST_JAKARTA = "jakarta.ws.rs.POST";
    /** @PUT (jakarta) */
    public static final String JAXRS_PUT_JAKARTA = "jakarta.ws.rs.PUT";
    /** @DELETE (jakarta) */
    public static final String JAXRS_DELETE_JAKARTA = "jakarta.ws.rs.DELETE";
    /** @PATCH (jakarta) */
    public static final String JAXRS_PATCH_JAKARTA = "jakarta.ws.rs.PATCH";

    /** @QueryParam (javax) */
    public static final String JAXRS_QUERY_PARAM_JAVAX = "javax.ws.rs.QueryParam";
    /** @QueryParam (jakarta) */
    public static final String JAXRS_QUERY_PARAM_JAKARTA = "jakarta.ws.rs.QueryParam";

    /** @PathParam (javax) */
    public static final String JAXRS_PATH_PARAM_JAVAX = "javax.ws.rs.PathParam";
    /** @PathParam (jakarta) */
    public static final String JAXRS_PATH_PARAM_JAKARTA = "jakarta.ws.rs.PathParam";

    /** @HeaderParam (javax) */
    public static final String JAXRS_HEADER_PARAM_JAVAX = "javax.ws.rs.HeaderParam";
    /** @HeaderParam (jakarta) */
    public static final String JAXRS_HEADER_PARAM_JAKARTA = "jakarta.ws.rs.HeaderParam";

    /** @FormParam (javax) */
    public static final String JAXRS_FORM_PARAM_JAVAX = "javax.ws.rs.FormParam";
    /** @FormParam (jakarta) */
    public static final String JAXRS_FORM_PARAM_JAKARTA = "jakarta.ws.rs.FormParam";

    /** @CookieParam (javax) */
    public static final String JAXRS_COOKIE_PARAM_JAVAX = "javax.ws.rs.CookieParam";
    /** @CookieParam (jakarta) */
    public static final String JAXRS_COOKIE_PARAM_JAKARTA = "jakarta.ws.rs.CookieParam";

    /** @Consumes (javax) */
    public static final String JAXRS_CONSUMES = "javax.ws.rs.Consumes";
    /** @Produces (javax) */
    public static final String JAXRS_PRODUCES = "javax.ws.rs.Produces";

    // ═══════════════════════════════════════════════════════════
    // OpenFeign 注解
    // ═══════════════════════════════════════════════════════════

    /** @FeignClient */
    public static final String ANNO_FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";

    // ═══════════════════════════════════════════════════════════
    // Swagger / OpenAPI 注解
    // ═══════════════════════════════════════════════════════════

    /** @Api (Swagger 2) */
    public static final String ANNO_SWAGGER_API = "io.swagger.annotations.Api";

    /** @ApiOperation (Swagger 2) */
    public static final String SWAGGER_API_OPERATION = "io.swagger.annotations.ApiOperation";

    /** @ApiModelProperty (Swagger 2) */
    public static final String SWAGGER_API_MODEL_PROPERTY = "io.swagger.annotations.ApiModelProperty";

    /** @ApiParam (Swagger 2) */
    public static final String SWAGGER_API_PARAM = "io.swagger.annotations.ApiParam";

    /** @Operation (OpenAPI 3 - io.swagger.v3) */
    public static final String OPENAPI_OPERATION = "io.swagger.v3.oas.annotations.Operation";

    // ═══════════════════════════════════════════════════════════
    // 验证注解 (JSR-303 / JSR-380)
    // ═══════════════════════════════════════════════════════════

    public static final String VALIDATION_NOT_NULL_JAVAX = "javax.validation.constraints.NotNull";
    public static final String VALIDATION_NOT_BLANK_JAVAX = "javax.validation.constraints.NotBlank";
    public static final String VALIDATION_NOT_EMPTY_JAVAX = "javax.validation.constraints.NotEmpty";
    public static final String VALIDATION_NOT_NULL_JAKARTA = "jakarta.validation.constraints.NotNull";
    public static final String VALIDATION_NOT_BLANK_JAKARTA = "jakarta.validation.constraints.NotBlank";
    public static final String VALIDATION_NOT_EMPTY_JAKARTA = "jakarta.validation.constraints.NotEmpty";
    public static final String VALIDATION_NON_NULL_LOMBOK = "lombok.NonNull";

    /** 标识字段必填的验证注解列表 */
    public static final List<String> VALIDATION_REQUIRED_ANNOTATIONS = Arrays.asList(
            VALIDATION_NOT_NULL_JAVAX, VALIDATION_NOT_BLANK_JAVAX, VALIDATION_NOT_EMPTY_JAVAX,
            VALIDATION_NOT_NULL_JAKARTA, VALIDATION_NOT_BLANK_JAKARTA, VALIDATION_NOT_EMPTY_JAKARTA,
            VALIDATION_NON_NULL_LOMBOK
    );

    /** @Deprecated */
    public static final String ANNO_DEPRECATED = "java.lang.Deprecated";

    // ═══════════════════════════════════════════════════════════
    // HTTP 相关常量
    // ═══════════════════════════════════════════════════════════

    /** 默认Content-Type */
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    /** HTTP连接超时 (秒) */
    public static final int HTTP_CONNECT_TIMEOUT_SECONDS = 10;

    /** HTTP请求超时 (秒) */
    public static final int HTTP_REQUEST_TIMEOUT_SECONDS = 30;

    /** HTTP Header 名 */
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 自部署模型的占位 Token。
     * <p>对于 Ollama / vLLM / Qwen 等自部署网关，HTTP Header 通常要求必须有
     * <code>Authorization: Bearer &lt;xxx&gt;</code>，但 token 值可以是任意字符串。</p>
     * <p>约定：用户在配置面板勾选"自部署模型"后，API Key 字段自动填入字面量 "Bearer"，
     * 最终发送的 Header 为 <code>Authorization: Bearer Bearer</code>。</p>
     * <p>判断网关是否无需真实 Token 用 {@link #isSelfHostedGatewayWithoutToken(String)}：token 为空或等于该字面量都视为不鉴权网关。</p>
     */
    public static final String AI_LOCAL_BEARER_TOKEN = "Bearer";

    /**
     * 判断给定的 token 是否表示自部署模型网关无需真实 API Key。
     * <p>特征：token 为空，或 token 等于字面量 "Bearer"（用户在面板勾选自部署模型后自动填入）。</p>
     */
    public static boolean isSelfHostedGatewayWithoutToken(String token) {
        return token == null || token.isBlank() || AI_LOCAL_BEARER_TOKEN.equals(token.trim());
    }

    /** 成功状态码范围 */
    public static final int HTTP_SUCCESS_MIN = 200;
    public static final int HTTP_SUCCESS_MAX = 299;

    /** 默认允许的状态码（Git检查） */
    public static final String DEFAULT_ALLOWED_STATUS_CODES = "200";

    /** 默认基础URL */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    /** 最大历史记录数 */
    public static final int MAX_HISTORY_SIZE = 100;

    /** 表单URL编码Content-Type */
    public static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";
    /** multipart/form-data Content-Type */
    public static final String CONTENT_TYPE_FORM_DATA = "multipart/form-data";
    /** XML Content-Type */
    public static final String CONTENT_TYPE_XML = "application/xml";
    /** 纯文本 Content-Type */
    public static final String CONTENT_TYPE_TEXT = "text/plain";
    /** HTML Content-Type */
    public static final String CONTENT_TYPE_HTML = "text/html";

    /** API变更标记：新增 */
    public static final String CHANGE_ADDED = "ADDED";
    /** API变更标记：修改 */
    public static final String CHANGE_MODIFIED = "MODIFIED";
    /** API变更标记：删除 */
    public static final String CHANGE_DELETED = "DELETED";
    /** API变更标记：无变化 */
    public static final String CHANGE_NONE = "NONE";

    /** 最大读取字节数 */
    public static final int MAX_READ_BYTES = 10 * 1024 * 1024; // 10MB

    // ═══════════════════════════════════════════════════════════
    // 默认 AI 配置（可替换为自部署模型网关）
    // ═══════════════════════════════════════════════════════════

    /** 默认模型网关地址（用户可替换为自部署模型地址） */
    public static final String AI_DEFAULT_GATEWAY_URL = "http://localhost:8000/v1";

    /** 默认主模型（用户可替换为自部署模型名） */
    public static final String AI_DEFAULT_PRIMARY_MODEL = "Qwen3.5-35B-A3B";

    /** 默认轻量模型（用户可替换为自部署模型名） */
    public static final String AI_DEFAULT_SECONDARY_MODEL = "Qwen2.5-72B-Instruct";

    /** AI连接超时秒数 */
    public static final int AI_CONNECT_TIMEOUT_SECONDS = 10;

    /** AI请求超时秒数 */
    public static final int AI_REQUEST_TIMEOUT_SECONDS = 60;

    /** AI最大重试次数 */
    public static final int AI_MAX_RETRIES = 3;

    /** AI Chat Completions 路径（OpenAI 标准） */
    public static final String AI_CHAT_COMPLETIONS_PATH = "/chat/completions";

    /** AI Chat 路径（部分自部署/简化协议用，如 vLLM、Qwen 网关） */
    public static final String AI_CHAT_PATH = "/chat";

    /** AI API 路径默认值（可在设置中修改，支持 /chat/completions 和 /chat 两种风格） */
    public static final String AI_DEFAULT_API_PATH = AI_CHAT_COMPLETIONS_PATH;

    /** AI System Prompt */
    public static final String AI_SYSTEM_PROMPT =
            "你是一个专业的API测试参数生成助手。你需要根据REST API接口的定义信息，生成符合要求的测试参数。\n" +
            "严格遵循以下规则：\n" +
            "1. 返回纯JSON格式，不要包含markdown代码块标记(```)或其他解释文字\n" +
            "2. 参数值必须真实可用，禁止使用test_xxx、mock_xxx、example_xxx等占位符\n" +
            "3. 根据参数名的语义生成对应含义的真实值（如email生成真实邮箱，phone生成手机号）\n" +
            "4. 数字类型返回数字，布尔类型返回true/false，字符串加双引号\n" +
            "5. 日期格式: 日期用\"2024-06-15\"，日期时间用\"2024-06-15 10:30:00\"\n" +
            "6. 如果参数是对象类型，递归生成其字段的示例值\n" +
            "7. 数组类型返回数组格式";

    /** AI 断言生成 System Prompt */
    public static final String AI_ASSERTION_SYSTEM_PROMPT =
            "你是一个专业的API测试断言生成助手。根据API接口信息，生成合理的响应断言规则。\n" +
            "返回纯JSON数组格式，每个断言对象包含:\n" +
            "- type: 断言类型（STATUS_CODE/BODY_CONTAINS/JSON_PATH/RESPONSE_TIME/HEADER_EXISTS）\n" +
            "- target: 断言目标（jsonPath表达式、header名等）\n" +
            "- expected: 期望值\n" +
            "- operator: 操作符（EQUALS/NOT_EQUALS/CONTAINS/NOT_CONTAINS/GREATER_THAN/LESS_THAN/EXISTS/NOT_EXISTS）\n" +
            "只返回JSON数组，不要包含markdown标记或其他文字。\n" +
            "常见断言示例：状态码200，响应包含code字段且值为0，响应时间小于3000ms。";

    /**
     * AI 用户提示词默认模板（支持占位符替换）
     * <p>占位符:
     * <ul>
     *   <li>接口信息: ${API_URL} ${HTTP_METHOD} ${API_NAME} ${CONTROLLER_NAME}
     *       ${DESCRIPTION} ${CONTENT_TYPE} ${PARAMETERS}</li>
     *   <li>场景信息: ${SCENARIO_NAME} ${SCENARIO_DESC}</li>
     *   <li>场景规则与返回格式（由 {@code AiParameterService.buildScenarioRules/buildReturnFormat}
     *       按场景动态注入，确保正常/边界/异常/全量各有针对性指令）:
     *       ${SCENARIO_RULES} ${RETURN_FORMAT}</li>
     *   <li>兼容旧模板: ${FULL_HINT}（仅全量场景注入数组提示，已被 ${RETURN_FORMAT} 取代）</li>
     * </ul></p>
     */
    public static final String AI_DEFAULT_USER_PROMPT_TEMPLATE =
            "请为以下REST API接口生成测试参数数据。\n\n" +
            "## 接口信息\n" +
            "- URL: ${API_URL}\n" +
            "- HTTP方法: ${HTTP_METHOD}\n" +
            "- 名称: ${API_NAME}\n" +
            "- 控制器: ${CONTROLLER_NAME}\n" +
            "- 描述: ${DESCRIPTION}\n" +
            "- Content-Type: ${CONTENT_TYPE}\n\n" +
            "## 参数列表\n" +
            "${PARAMETERS}\n\n" +
            "## 测试场景\n" +
            "${SCENARIO_NAME} - ${SCENARIO_DESC}\n\n" +
            "${SCENARIO_RULES}\n\n" +
            "## 返回格式\n" +
            "${RETURN_FORMAT}";

    /** 可选模型列表 */
    public static final String[] AI_MODEL_OPTIONS = {
            "Qwen3.5-35B-A3B",
            "Qwen2.5-72B-Instruct",
            "DeepSeek-R1-Distill-Qwen-32B",
            "Llama-3.3-70B-Instruct",
            "custom-model"
    };

    // ═══════════════════════════════════════════════════════════
    // Git 相关常量
    // ═══════════════════════════════════════════════════════════

    /** Git检查配置名 */
    public static final String GIT_CHECKIN_CHECKBOX = "执行 RestAutoLab 接口检查";

    /** 大合并阈值文件数 */
    public static final int LARGE_MERGE_THRESHOLD = 100;

    // ═══════════════════════════════════════════════════════════
    // UI 常量 - 颜色 (全部使用JBColor主题感知)
    // ═══════════════════════════════════════════════════════════

    /** GET方法颜色 - 绿色 */
    public static final JBColor COLOR_GET = new JBColor(
            new Color(0x2E, 0x7D, 0x32),
            new Color(0x66, 0xBB, 0x6A)
    );
    /** POST方法颜色 - 蓝色 */
    public static final JBColor COLOR_POST = new JBColor(
            new Color(0x15, 0x65, 0xC0),
            new Color(0x42, 0xA5, 0xF5)
    );
    /** PUT方法颜色 - 橙色 */
    public static final JBColor COLOR_PUT = new JBColor(
            new Color(0xED, 0x6C, 0x02),
            new Color(0xFF, 0xA7, 0x26)
    );
    /** DELETE方法颜色 - 红色 */
    public static final JBColor COLOR_DELETE = new JBColor(
            new Color(0xC6, 0x28, 0x28),
            new Color(0xEF, 0x53, 0x50)
    );
    /** PATCH方法颜色 - 紫色 */
    public static final JBColor COLOR_PATCH = new JBColor(
            new Color(0x6B, 0x21, 0xA8),
            new Color(0xAB, 0x47, 0xBC)
    );
    /** HEAD方法颜色 - 灰色 */
    public static final JBColor COLOR_HEAD = new JBColor(
            new Color(0x75, 0x75, 0x75),
            new Color(0x9E, 0x9E, 0x9E)
    );
    /** OPTIONS方法颜色 - 青色 */
    public static final JBColor COLOR_OPTIONS = new JBColor(
            new Color(0x00, 0x8B, 0x8B),
            new Color(0x26, 0xA6, 0x9A)
    );
    /** 默认方法颜色 */
    public static final JBColor COLOR_DEFAULT_METHOD = COLOR_GET;

    /** 发送按钮蓝色 */
    public static final JBColor COLOR_SEND_BUTTON = new JBColor(
            new Color(0x00, 0x7A, 0xCC),
            new Color(0x1E, 0x88, 0xE5)
    );
    /** AI按钮紫色 */
    public static final JBColor COLOR_AI_BUTTON = new JBColor(
            new Color(0x4B, 0x00, 0x82),
            new Color(0x7C, 0x4D, 0xFF)
    );
    /** 通过绿色 */
    public static final JBColor COLOR_PASS = new JBColor(
            new Color(0x2E, 0x7D, 0x32),
            new Color(0x66, 0xBB, 0x6A)
    );
    /** 失败红色 */
    public static final JBColor COLOR_FAIL = new JBColor(
            new Color(0xC6, 0x28, 0x28),
            new Color(0xEF, 0x53, 0x50)
    );
    /** 警告橙色 */
    public static final JBColor COLOR_WARN = new JBColor(
            new Color(0xED, 0x6C, 0x02),
            new Color(0xFF, 0xA7, 0x26)
    );
    /** 树节点Deprecated颜色 */
    public static final JBColor COLOR_TREE_DEPRECATED = new JBColor(
            new Color(0x99, 0x99, 0x99),
            new Color(0x75, 0x75, 0x75)
    );
    /** 手动API标记颜色 */
    public static final JBColor COLOR_TREE_MANUAL = new JBColor(
            new Color(0x00, 0x7A, 0xCC),
            new Color(0x42, 0xA5, 0xF5)
    );

    // ═══════════════════════════════════════════════════════════
    // UI 常量 - 尺寸
    // ═══════════════════════════════════════════════════════════

    /** 默认行高 */
    public static final int TABLE_ROW_HEIGHT = 28;
    /** 参数名列宽 */
    public static final int COL_WIDTH_NAME = 130;
    /** 类型列宽 */
    public static final int COL_WIDTH_TYPE = 100;
    /** 位置列宽 */
    public static final int COL_WIDTH_LOCATION = 70;
    /** 值列宽 */
    public static final int COL_WIDTH_VALUE = 280;
    /** 必填列宽 */
    public static final int COL_WIDTH_REQUIRED = 50;
    /** 描述列宽 */
    public static final int COL_WIDTH_DESCRIPTION = 200;
    /** Header名列宽 */
    public static final int COL_WIDTH_HEADER_NAME = 200;
    /** Header值列宽 */
    public static final int COL_WIDTH_HEADER_VALUE = 300;
    /** 方法下拉框宽度 */
    public static final int COMBO_METHOD_WIDTH = 100;
    /** 环境下拉框宽度 */
    public static final int COMBO_ENV_WIDTH = 150;
    /** 小按钮尺寸 */
    public static final int BUTTON_SMALL_SIZE = 30;
    /** 分割条初始比例 */
    public static final float SPLITTER_PROPORTION = 0.3f;
    /** 请求体编辑器行数 */
    public static final int BODY_EDITOR_ROWS = 10;
    /** 请求体编辑器列数 */
    public static final int BODY_EDITOR_COLS = 60;
    /** 等宽字体大小 */
    public static final int FONT_SIZE_MONO = 13;

    // ═══════════════════════════════════════════════════════════
    // HTTP方法列表
    // ═══════════════════════════════════════════════════════════

    /** 支持的所有HTTP方法 */
    public static final String[] HTTP_METHOD_NAMES = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};

    /** 无请求体的HTTP方法 */
    public static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "HEAD", "OPTIONS");

    /** 简单类型集合 */
    public static final Set<String> SIMPLE_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean",
            "Short", "Byte", "Character", "int", "long", "double",
            "float", "boolean", "short", "byte", "char",
            "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalTime",
            "LocalDateTime", "UUID", "MultipartFile", "Instant",
            "OffsetDateTime", "ZonedDateTime", "String[]",
            "java.lang.String", "java.lang.Integer", "java.lang.Long",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.math.BigDecimal", "java.util.Date", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.util.UUID"
    );

    // ═══════════════════════════════════════════════════════════
    // 类型默认值映射
    // ═══════════════════════════════════════════════════════════

    /** 类型到默认值的映射 */
    public static final Map<String, String> TYPE_DEFAULTS = Map.ofEntries(
            Map.entry("string", "test_value"),
            Map.entry("java.lang.string", "test_value"),
            Map.entry("int", "1"),
            Map.entry("integer", "1"),
            Map.entry("java.lang.integer", "1"),
            Map.entry("long", "100"),
            Map.entry("java.lang.long", "100"),
            Map.entry("double", "1.0"),
            Map.entry("java.lang.double", "1.0"),
            Map.entry("float", "1.0"),
            Map.entry("java.lang.float", "1.0"),
            Map.entry("boolean", "true"),
            Map.entry("java.lang.boolean", "true"),
            Map.entry("bigdecimal", "100.00"),
            Map.entry("java.math.bigdecimal", "100.00"),
            Map.entry("date", "2025-01-01"),
            Map.entry("java.util.date", "2025-01-01"),
            Map.entry("localdate", "2025-01-01"),
            Map.entry("java.time.localdate", "2025-01-01"),
            Map.entry("localdatetime", "2025-01-01T00:00:00"),
            Map.entry("java.time.localdatetime", "2025-01-01T00:00:00"),
            Map.entry("uuid", "550e8400-e29b-41d4-a716-446655440000"),
            Map.entry("java.util.uuid", "550e8400-e29b-41d4-a716-446655440000"),
            Map.entry("list", "[]"),
            Map.entry("arraylist", "[]"),
            Map.entry("java.util.list", "[]"),
            Map.entry("map", "{}"),
            Map.entry("hashmap", "{}"),
            Map.entry("java.util.map", "{}")
    );

    // ═══════════════════════════════════════════════════════════
    // 注解分组列表（用于批量扫描）
    // ═══════════════════════════════════════════════════════════

    /** Spring MVC所有方法级映射注解 */
    public static final List<String> SPRING_MAPPING_ANNOTATIONS = Arrays.asList(
            ANNO_GET_MAPPING,
            ANNO_POST_MAPPING,
            ANNO_PUT_MAPPING,
            ANNO_DELETE_MAPPING,
            ANNO_PATCH_MAPPING,
            ANNO_REQUEST_MAPPING
    );

    /** Spring MVC控制器注解 */
    public static final List<String> SPRING_CONTROLLER_ANNOTATIONS = Arrays.asList(
            ANNO_REST_CONTROLLER,
            ANNO_CONTROLLER
    );

    /** JAX-RS控制器注解（@Path） */
    public static final List<String> JAXRS_CONTROLLER_ANNOTATIONS = Arrays.asList(
            JAXRS_PATH_JAVAX, JAXRS_PATH_JAKARTA
    );

    /** JAX-RS HTTP方法注解 (javax) */
    public static final List<String> JAXRS_METHOD_ANNOTATIONS_JAVAX = Arrays.asList(
            JAXRS_GET_JAVAX, JAXRS_POST_JAVAX, JAXRS_PUT_JAVAX,
            JAXRS_DELETE_JAVAX, JAXRS_PATCH_JAVAX
    );

    /** JAX-RS HTTP方法注解 (jakarta) */
    public static final List<String> JAXRS_METHOD_ANNOTATIONS_JAKARTA = Arrays.asList(
            JAXRS_GET_JAKARTA, JAXRS_POST_JAKARTA, JAXRS_PUT_JAKARTA,
            JAXRS_DELETE_JAKARTA, JAXRS_PATCH_JAKARTA
    );

    /** JAX-RS方法注解（全部） */
    public static final List<String> JAXRS_METHOD_ANNOTATIONS = Arrays.asList(
            JAXRS_GET_JAVAX, JAXRS_POST_JAVAX, JAXRS_PUT_JAVAX,
            JAXRS_DELETE_JAVAX, JAXRS_PATCH_JAVAX,
            JAXRS_GET_JAKARTA, JAXRS_POST_JAKARTA, JAXRS_PUT_JAKARTA,
            JAXRS_DELETE_JAKARTA, JAXRS_PATCH_JAKARTA
    );

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据HTTP方法名获取对应颜色（JBColor主题感知）
     * 支持7种HTTP方法：GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS
     */
    public static JBColor colorForMethod(String method) {
        if (method == null) return COLOR_DEFAULT_METHOD;
        switch (method.toUpperCase()) {
            case "GET": return COLOR_GET;
            case "POST": return COLOR_POST;
            case "PUT": return COLOR_PUT;
            case "DELETE": return COLOR_DELETE;
            case "PATCH": return COLOR_PATCH;
            case "HEAD": return COLOR_HEAD;
            case "OPTIONS": return COLOR_OPTIONS;
            default: return COLOR_DEFAULT_METHOD;
        }
    }

    /**
     * 根据注解全限定名解析HTTP方法
     */
    public static String methodFromAnnotation(String fqn) {
        if (fqn == null) return "GET";
        String upper = fqn.toUpperCase();
        if (upper.endsWith("GETMAPPING") || upper.endsWith(".GET")) return "GET";
        if (upper.endsWith("POSTMAPPING") || upper.endsWith(".POST")) return "POST";
        if (upper.endsWith("PUTMAPPING") || upper.endsWith(".PUT")) return "PUT";
        if (upper.endsWith("DELETEMAPPING") || upper.endsWith(".DELETE")) return "DELETE";
        if (upper.endsWith("PATCHMAPPING") || upper.endsWith(".PATCH")) return "PATCH";
        return "GET";
    }
}
