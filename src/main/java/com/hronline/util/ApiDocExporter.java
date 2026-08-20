package com.hronline.util;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * API文档导出工具 - 生成Markdown格式的接口文档
 */
public class ApiDocExporter {

    /**
     * 导出选中接口的 Markdown 文档，内容与 Word 导出同一信息要求：
     * <ul>
     *   <li>一级标序「一、接口设计」（全文档唯一章节标题）</li>
     *   <li>二级标序「N、xxx接口：」按选中顺序连续编号 1、2、3…</li>
     *   <li>三级标序「（n）」每个接口从（1）重新开始：
     *       接口名称 / 接口地址 / 接口入参 / 接口出参 / 接口逻辑
     *       （与 Word 一致，不含请求方式/返回类型行）</li>
     *   <li>入参/出参三列表格（字段名 / 类型 / 注释），DTO 等嵌套对象字段
     *       以「父.子」点号路径全量展开；出参对 Result&lt;T&gt; 等泛型包装
     *       自动补全 code/msg/data 并继续展开 data 的具体字段</li>
     * </ul>
     *
     * @param apis       选中的接口列表
     * @param outputFile 输出文件路径，为null时仅返回字符串
     * @return Markdown 全文
     */
    public static String exportSelectedApis(List<ApiDefinition> apis, String outputFile) throws IOException {
        if (apis == null) apis = new ArrayList<>();
        StringBuilder md = new StringBuilder();

        // 一级标序「一、接口设计」：与 Word 导出一致，全文档唯一章节标题
        if (!apis.isEmpty()) {
            md.append("# 一、接口设计\n\n");
        }

        // 二级标序全文档连续编号（1、2、3…），三级标序每接口从（1）重新开始
        for (int i = 0; i < apis.size(); i++) {
            appendApiDoc(md, apis.get(i), i + 1);
        }

        if (outputFile != null) {
            Path path = Paths.get(outputFile);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8)) {
                writer.write(md.toString());
            }
        }
        return md.toString();
    }

    /**
     * 按类型名合成一个示例 JSON 字符串
     */
    static String synthesizeExampleForType(String retType) {
        if (retType == null || retType.isBlank()) {
            return "{\n  // 尚未配置返回类型\n}";
        }
        String t = retType.trim();
        String low = t.toLowerCase();

        // 基础类型
        if (low.equals("string")) return "\"\"";
        if (low.equals("void")) return "null";
        if (low.equals("int") || low.equals("integer")
                || low.equals("long") || low.equals("short")
                || low.equals("byte") || low.equals("number")) return "0";
        if (low.equals("double") || low.equals("float") || low.equals("bigdecimal")) return "0.0";
        if (low.equals("boolean")) return "false";
        if (low.equals("date") || low.endsWith("localdatetime")
                || low.endsWith("localdate") || low.endsWith("localtime")) return "\"2024-01-01 00:00:00\"";

        // 集合
        if (low.startsWith("list<") || low.startsWith("java.util.list<")
                || low.startsWith("collection<") || low.startsWith("set<")
                || low.endsWith("[]") || low.startsWith("arraylist<")) {
            return "[]";
        }
        if (low.startsWith("map<") || low.startsWith("java.util.map<")) {
            return "{}";
        }
        if (low.startsWith("page<") || low.startsWith("ipage<")
                || low.startsWith("com.baomidou.mybatisplus.core.metadataipage<")) {
            return "{\n  \"total\": 0,\n  \"records\": [],\n  \"size\": 10,\n  \"current\": 1\n}";
        }

        // 标准 Result<T> 包装（只在外层是 Result<...> 时才包，领域对象不要再包第二层）
        if (low.startsWith("result<") || low.startsWith("com.hronline.")) {
            String inner = extractGenericInner(t);
            String innerExample = synthesizeExampleForType(inner);
            return "{\n  \"code\": 0,\n  \"message\": \"success\",\n  \"data\": " + innerExample + "\n}";
        }

        // 其它领域对象（DTO/VO/Entity 等）→ 直接给一个空对象占位
        return "{}";
    }

    /** 提取泛型 <T> 里的 T；如 "Result<UserDTO>" → "UserDTO"，无泛型返回 Object */
    private static String extractGenericInner(String type) {
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return type.substring(lt + 1, gt);
        }
        return "Object";
    }

    private static void appendApiDoc(StringBuilder md, ApiDefinition api, int index) {
        String name = api.getName() == null || api.getName().isBlank()
                ? (api.getUrl() == null ? "" : api.getUrl()) : api.getName();
        String url = api.getUrl() == null ? "" : api.getUrl();

        // 二级标序「N、xxx接口：」，与 Word 导出同一层级标序
        md.append("### ").append(index).append("、").append(escapeMd(name)).append("接口：\n\n");

        // 三级标序「（n）」信息行（严格对齐 Word：无请求方式/返回类型行）
        md.append("（1）接口名称：").append(escapeMd(name)).append("\n\n");
        md.append("（2）接口地址：`").append(url).append("`\n\n");

        // （3）接口入参：与 Word 导出同一套扁平化（Path/Query/Header 平铺 + DTO 字段树全量展开）
        md.append("（3）接口入参：\n\n");
        appendFlatTable(md, ApiDocWordExporter.flattenRequestParams(api));

        // （4）接口出参：与 Word 导出同一套扁平化（泛型包装补全 code/msg/data + 嵌套字段全量展开）
        md.append("（4）接口出参：\n\n");
        appendFlatTable(md, ApiDocWordExporter.flattenResponseParams(api));

        // （5）接口逻辑
        md.append("（5）接口逻辑：\n\n");
        String desc = api.getDescription() == null ? "" : api.getDescription().trim();
        md.append(desc.isEmpty() ? "接口原有逻辑不变。" : escapeMd(desc)).append("\n\n");

        md.append("---\n\n");
    }

    /** 三列表格（字段名 / 类型 / 注释），与 Word 导出的入参/出参表同构 */
    private static void appendFlatTable(StringBuilder md, List<String[]> rows) {
        md.append("| 字段名 | 类型 | 注释 |\n");
        md.append("|--------|------|------|\n");
        for (String[] row : rows) {
            md.append("| ").append(escapeMd(row[0]))
              .append(" | ").append(escapeMd(row[1]))
              .append(" | ").append(escapeMd(row.length > 2 ? row[2] : ""))
              .append(" |\n");
        }
        md.append("\n");
    }

    /**
     * 基于真实字段树 + 返回类型，合成响应示例 JSON。
     * <p>对 Result/Page/List 这类包装类型，自动加 code/message/data 三层结构；</p>
     * <p>嵌套对象按字段类型递归生成；List 字段生成一个元素的数组示例。</p>
     */
    static String synthesizeExampleFromSchema(String retType, List<ApiParameter> fields) {
        if (fields == null || fields.isEmpty()) {
            return synthesizeExampleForType(retType);
        }
        StringBuilder sb = new StringBuilder();
        appendExampleForTypeAndFields(sb, retType, fields, 1);
        return sb.toString();
    }

    /**
     * 根据返回类型，决定最外层 JSON 形状。
     * <ul>
     *   <li>Result<T> / R<T> 等通用包装 → { code, message, data: T示例 }</li>
     *   <li>List<T> / Collection<T> / Set<T> → [T示例]</li>
     *   <li>Page<T> / IPage<T> → { total, records:[T示例], size, current }</li>
     *   <li>基础类型 → 字面量</li>
     *   <li>其它 → 字段树对应的对象</li>
     * </ul>
     */
    private static void appendExampleForTypeAndFields(StringBuilder sb, String retType,
                                                      List<ApiParameter> fields, int depth) {
        if (depth > 6) {
            sb.append("{}");
            return;
        }
        String t = retType == null ? "" : retType.trim();
        String low = t.toLowerCase();
        // void
        if (low.equals("void")) { sb.append("null"); return; }
        // 基础
        if (isPrimitiveType(t)) {
            if (low.equals("string")) sb.append("\"\"");
            else if (low.equals("boolean")) sb.append("false");
            else if (low.equals("bigdecimal") || low.equals("double") || low.equals("float")) sb.append("0.0");
            else sb.append("0");
            return;
        }
        // 集合
        if (low.startsWith("list<") || low.startsWith("java.util.list<")
                || low.startsWith("collection<") || low.startsWith("set<")
                || low.startsWith("arraylist<") || low.endsWith("[]")) {
            sb.append("[\n");
            appendObjectFromFields(sb, fields, 2);
            sb.append("\n]");
            return;
        }
        // Page
        if (low.startsWith("page<") || low.startsWith("ipage<")
                || low.startsWith("com.baomidou.mybatisplus.core.metadataipage<")) {
            sb.append("{\n");
            sb.append("  \"total\": 0,\n");
            sb.append("  \"size\": 10,\n");
            sb.append("  \"current\": 1,\n");
            sb.append("  \"records\": [\n");
            appendObjectFromFields(sb, fields, 3);
            sb.append("\n  ]\n}");
            return;
        }
        // 通用包装
        if (isCommonWrapperName(extractSimpleName(t))) {
            String innerType = extractGenericInner(t);
            sb.append("{\n");
            sb.append("  \"code\": 0,\n");
            sb.append("  \"message\": \"success\",\n");
            sb.append("  \"data\": ");
            // data 自身再递归
            String innerLow = innerType.toLowerCase();
            if (isPrimitiveType(innerType)) {
                if (innerLow.equals("string")) sb.append("\"\"");
                else if (innerLow.equals("boolean")) sb.append("false");
                else if (innerLow.equals("bigdecimal") || innerLow.equals("double") || innerLow.equals("float")) sb.append("0.0");
                else sb.append("0");
            } else if (innerLow.startsWith("list<") || innerLow.startsWith("java.util.list<")
                    || innerLow.endsWith("[]")) {
                sb.append("[\n");
                appendObjectFromFields(sb, fields, 3);
                sb.append("\n  ]");
            } else if (innerLow.startsWith("page<") || innerLow.startsWith("ipage<")) {
                sb.append("{\n");
                sb.append("    \"total\": 0,\n");
                sb.append("    \"size\": 10,\n");
                sb.append("    \"current\": 1,\n");
                sb.append("    \"records\": [\n");
                appendObjectFromFields(sb, fields, 4);
                sb.append("\n    ]\n  }");
            } else {
                appendObjectFromFields(sb, fields, 3);
            }
            sb.append("\n}");
            return;
        }
        // 其它领域对象
        appendObjectFromFields(sb, fields, depth);
    }

    private static void appendObjectFromFields(StringBuilder sb, List<ApiParameter> fields, int indentLevel) {
        if (fields == null || fields.isEmpty()) {
            sb.append("{}");
            return;
        }
        String indent = repeatStr("  ", indentLevel);
        String childIndent = repeatStr("  ", indentLevel + 1);
        sb.append("{\n");
        int count = 0;
        for (ApiParameter f : fields) {
            if (count > 0) sb.append(",\n");
            sb.append(childIndent).append("\"").append(f.getName()).append("\": ");
            if (f.getChildren() != null && !f.getChildren().isEmpty()) {
                appendObjectFromFields(sb, f.getChildren(), indentLevel + 1);
            } else {
                String t = f.getType() == null ? "" : f.getType();
                String low = t.toLowerCase();
                if (low.equals("string") || low.endsWith("char") || low.endsWith("string")) {
                    sb.append("\"\"");
                } else if (low.equals("boolean")) {
                    sb.append("false");
                } else if (low.equals("bigdecimal") || low.equals("double") || low.equals("float")) {
                    sb.append("0.0");
                } else if (low.equals("int") || low.equals("integer")
                        || low.equals("long") || low.equals("short") || low.equals("byte")) {
                    sb.append("0");
                } else if (low.startsWith("list<") || low.startsWith("java.util.list<")
                        || low.startsWith("collection<") || low.startsWith("set<")
                        || low.endsWith("[]")) {
                    sb.append("[]");
                } else if (low.startsWith("map<") || low.startsWith("java.util.map<")) {
                    sb.append("{}");
                } else if (low.endsWith("date") || low.endsWith("localdatetime")
                        || low.endsWith("localdate") || low.endsWith("localtime")) {
                    sb.append("\"2024-01-01 00:00:00\"");
                } else if (!f.getExample().isBlank()) {
                    String ex = f.getExample();
                    if (ex.matches("-?\\d+(\\.\\d+)?")) sb.append(ex);
                    else sb.append("\"").append(escapeMd(ex)).append("\"");
                } else {
                    // 未知类型对象，给个空对象占位
                    sb.append("{}");
                }
            }
            count++;
        }
        sb.append("\n").append(indent).append("}");
    }

    private static String repeatStr(String s, int n) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < n; i++) r.append(s);
        return r.toString();
    }

    private static String extractSimpleName(String type) {
        if (type == null) return "";
        int lt = type.indexOf('<');
        String head = lt >= 0 ? type.substring(0, lt) : type;
        int dot = head.lastIndexOf('.');
        return dot >= 0 ? head.substring(dot + 1) : head;
    }

    private static boolean isCommonWrapperName(String simpleName) {
        if (simpleName == null) return false;
        return simpleName.equals("Result") || simpleName.equals("R")
                || simpleName.equals("CommonResult") || simpleName.equals("ApiResult")
                || simpleName.equals("BaseResult") || simpleName.equals("ResponseResult")
                || simpleName.equals("Response") || simpleName.equals("Resp")
                || simpleName.equals("RespResult");
    }

    /**
     * 在 bodyParams 中查找与给定类型名匹配的 ApiParameter 字段树。
     * <p>匹配规则：body 的类型名或单字段的 type/名称与 typeSimple 相等（大小写不敏感、忽略泛型）。</p>
     * <p>典型用途：响应类型 = 请求体类型（如分页查询接口），可在 fallback 路径中复用 body 字段树。</p>
     */
    private static List<ApiParameter> findMatchingBodyParams(String typeName, List<ApiParameter> bodyParams) {
        if (bodyParams == null || bodyParams.isEmpty() || typeName == null || typeName.isEmpty()) return null;
        String simple = extractSimpleName(typeName).toLowerCase();
        if (simple.isEmpty()) return null;
        for (ApiParameter p : bodyParams) {
            if (p.getType() == null) continue;
            String pSimple = extractSimpleName(p.getType()).toLowerCase();
            if (pSimple.equals(simple) && !p.getChildren().isEmpty()) {
                return p.getChildren();
            }
        }
        return null;
    }

    /**
     * 兼容旧调用：仅基于类型名生成响应示例 JSON（不引用 body）。
     */
    private static String synthesizeExampleFromType(String type, List<ApiParameter> bodyParams) {
        // 尝试从 bodyParams 中复用（针对返回类型 = 请求体类型的简单接口）
        if (bodyParams != null && !bodyParams.isEmpty()) {
            String t = type == null ? "" : type.trim();
            String inner;
            boolean wrapped = false;
            if (t.startsWith("Result<") || t.startsWith("R<")) {
                inner = extractGenericInner(t);
                wrapped = true;
            } else if (t.endsWith("DTO") || t.endsWith("VO") || t.endsWith("Entity")) {
                inner = t;
            } else {
                inner = t;
            }
            List<ApiParameter> reuse = findMatchingBodyParams(inner, bodyParams);
            if (reuse != null && !reuse.isEmpty()) {
                return synthesizeExampleFromSchema(inner, reuse);
            }
        }
        return synthesizeExampleForType(type);
    }

    private static boolean isPrimitiveType(String t) {
        if (t == null) return false;
        String low = t.toLowerCase();
        return low.equals("string") || low.equals("integer") || low.equals("int")
                || low.equals("long") || low.equals("short") || low.equals("byte")
                || low.equals("double") || low.equals("float") || low.equals("boolean")
                || low.equals("bigdecimal") || low.equals("date")
                || low.endsWith("localdatetime") || low.endsWith("localdate")
                || low.endsWith("localtime") || low.equals("number") || low.equals("object");
    }

    /** 构造请求示例 JSON（含 path/query/body），嵌套对象按真实缩进展开 */
    private static String buildRequestExample(ApiDefinition api, List<ApiParameter> body,
                                              List<ApiParameter> query, List<ApiParameter> path) {
        if (body == null || body.isEmpty()) {
            if (query == null || query.isEmpty()) {
                return "{\n  // GET 请求无 Body，参数已在 URL 中\n}";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("// Query 参数示例：").append(api.getUrl()).append("?");
            boolean first = true;
            for (ApiParameter p : query) {
                if (!first) sb.append("&");
                sb.append(p.getName()).append("=").append(getExampleValueForType(p));
                first = false;
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder("{");
        appendRequestBodyJson(sb, body, 1);
        sb.append("\n}");
        return sb.toString();
    }

    /**
     * 递归追加请求体 JSON 段，缩进按嵌套深度递增。
     */
    private static void appendRequestBodyJson(StringBuilder sb, List<ApiParameter> fields, int indentLevel) {
        if (fields == null || fields.isEmpty()) {
            sb.append("}");
            return;
        }
        String indent = repeatStr("  ", indentLevel);
        String childIndent = repeatStr("  ", indentLevel + 1);
        int count = 0;
        for (ApiParameter p : fields) {
            if (count > 0) sb.append(",");
            sb.append("\n").append(childIndent).append("\"").append(p.getName()).append("\": ");
            if (!p.getChildren().isEmpty()) {
                sb.append("{");
                appendRequestBodyJson(sb, p.getChildren(), indentLevel + 1);
            } else {
                sb.append(getExampleValueForType(p));
            }
            count++;
        }
        sb.append("\n").append(indent).append("}");
    }

    private static String getExampleValueForType(ApiParameter p) {
        if (p.getExample() != null && !p.getExample().isBlank()) {
            // 简单判断：示例是不是数字
            String ex = p.getExample();
            if (ex.matches("-?\\d+(\\.\\d+)?")) return ex;
            return "\"" + ex + "\"";
        }
        String t = p.getType() == null ? "" : p.getType().toLowerCase();
        if (t.equals("string")) return "\"\"";
        if (t.equals("integer") || t.equals("int") || t.equals("long")
                || t.equals("short") || t.equals("byte")) return "0";
        if (t.equals("double") || t.equals("float") || t.equals("bigdecimal")) return "0.0";
        if (t.equals("boolean")) return "false";
        return "null";
    }

    // ================================================================
    // 模板导出便捷方法（供 TemplateEngine 调用）
    // ================================================================

    /**
     * 供模板占位符 ${api.requestExample} 使用：返回单接口的请求示例 JSON 字符串。
     */
    public static String buildRequestExampleForTemplate(ApiDefinition api) {
        if (api == null) return "{}";
        return buildRequestExample(api, api.bodyParameters(), api.queryParameters(), api.pathParameters());
    }

    /**
     * 供模板占位符 ${api.responseExample} 使用：返回单接口的响应示例 JSON 字符串。
     */
    public static String buildResponseExampleForTemplate(ApiDefinition api) {
        if (api == null) return "{}";
        String retType = api.getResponseBodyType();
        if (retType == null || retType.isBlank() || retType.equalsIgnoreCase("void")) {
            return "null";
        }
        List<ApiParameter> schema = api.getResponseSchema();
        if (schema != null && !schema.isEmpty()) {
            return synthesizeExampleFromSchema(retType, schema);
        }
        return synthesizeExampleFromType(retType, api.bodyParameters());
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    // ================================================================
    // Markdown 导入
    // ================================================================

    /**
     * 从 Markdown 文档导入 API 定义列表。
     *
     * 兼容本插件 {@link #exportAllDoc} / {@link #exportControllerDoc} 导出的格式，
     * 也尽量兼容结构相似的手写接口文档。导入的接口 source 标记为 MANUAL，
     * 解析失败的部分会被跳过而不抛异常。
     *
     * @param markdown Markdown 全文
     * @return 解析出的 API 定义列表
     */
    public static List<ApiDefinition> importFromMarkdown(String markdown) {
        List<ApiDefinition> apis = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return apis;

        String[] lines = markdown.split("\n", -1);
        ApiDefinition current = null;
        String currentController = "导入的接口";
        String section = null;       // PATH / QUERY / BODY / HEADER / RESPONSE
        boolean inCodeBlock = false;
        boolean nameParsed = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 代码块开关：块内内容一律跳过
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) continue;

            // Controller 标题：## xxx（排除 ###）
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                currentController = stripHash(line);
                continue;
            }
            // 单 controller 文档的顶层标题 # ControllerName
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                currentController = stripHash(line);
                continue;
            }

            // 接口标题：### `METHOD` url
            if (line.startsWith("### ")) {
                if (current != null) apis.add(current);
                current = parseApiHeading(line);
                if (current != null) {
                    current.setControllerName(currentController);
                    current.setSource(RestAutoLabConstants.API_SOURCE_MANUAL);
                    current.setScanTimestamp(System.currentTimeMillis());
                }
                section = null;
                nameParsed = false;
                continue;
            }

            if (current == null) continue;

            // 废弃标记
            if (line.contains("此接口已废弃")) {
                current.setDeprecated(true);
                continue;
            }

            // section 切换
            if (line.startsWith("**路径参数**")) { section = "PATH"; continue; }
            if (line.startsWith("**查询参数**")) { section = "QUERY"; continue; }
            if (line.startsWith("**请求体**")) {
                section = "BODY";
                String ct = extractParenBacktick(line);
                if (ct != null) current.setConsumes(ct);
                continue;
            }
            if (line.startsWith("**请求头**")) { section = "HEADER"; continue; }
            if (line.startsWith("**响应**")) {
                section = "RESPONSE";
                String ct = extractParenBacktick(line);
                if (ct != null) current.setProduces(ct);
                continue;
            }

            // 返回类型: - 返回类型: `UserVO`
            if (line.startsWith("- 返回类型")) {
                String t = extractBacktick(line);
                if (t != null) current.setResponseBodyType(t);
                continue;
            }
            // "请求示例:" 标记行，跳过
            if (line.startsWith("请求示例")) continue;

            // 接口名称：section 开始前，单独成行的 **xxx**（非分节标题）
            if (!nameParsed && line.startsWith("**") && line.endsWith("**") && !line.contains("```")) {
                current.setName(stripStars(line));
                nameParsed = true;
                continue;
            }

            // 描述行：section 开始前的普通文本
            if (section == null && (current.getDescription() == null || current.getDescription().isBlank())
                    && !line.startsWith("|") && !line.startsWith("#") && !line.startsWith(">")
                    && !line.startsWith("-")) {
                current.setDescription(unescapeMd(line));
                continue;
            }

            // 表格行
            if (line.startsWith("|") && section != null) {
                if (isTableSeparator(line)) continue;
                List<String> cells = parseTableRow(line);
                if (cells.isEmpty()) continue;
                switch (section) {
                    case "PATH" -> applyPathCell(current, cells);
                    case "QUERY" -> applyQueryCell(current, cells);
                    case "BODY" -> applyBodyCell(current, cells);
                    case "HEADER" -> applyHeaderCell(current, cells);
                    default -> { /* RESPONSE section 无表格需要解析为参数 */ }
                }
            }
        }
        if (current != null) apis.add(current);

        return apis;
    }

    /**
     * 从 Markdown 文件导入 API 定义列表。
     *
     * @param inputFile Markdown 文件绝对路径
     * @return 解析出的 API 定义列表
     */
    public static List<ApiDefinition> importFromFile(String inputFile) throws IOException {
        Path path = Paths.get(inputFile);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return importFromMarkdown(content);
    }

    // ── Markdown 导入辅助方法 ──

    /** 解析接口标题行 {@code ### `GET` /api/users/{id}} */
    private static ApiDefinition parseApiHeading(String heading) {
        String rest = heading.substring(4).trim(); // 去掉 "### "
        if (rest.isEmpty() || rest.charAt(0) != '`') return null;
        int btEnd = rest.indexOf('`', 1);
        if (btEnd < 0) return null;
        String method = rest.substring(1, btEnd).trim();
        String url = rest.substring(btEnd + 1).trim();
        if (url.isEmpty()) return null;
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(method.toUpperCase());
        api.setUrl(url);
        api.setName(url);
        return api;
    }

    /** 提取行中反引号包裹的内容，如 {@code - 返回类型: `UserVO`} -> UserVO */
    private static String extractBacktick(String line) {
        int start = line.indexOf('`');
        if (start < 0) return null;
        int end = line.indexOf('`', start + 1);
        if (end < 0) return null;
        return line.substring(start + 1, end);
    }

    /** 提取行中圆括号内反引号包裹的内容，如 {@code **请求体** (`application/json`)} -> application/json */
    private static String extractParenBacktick(String line) {
        int paren = line.indexOf('(');
        if (paren < 0) return null;
        return extractBacktick(line.substring(paren));
    }

    /** 去掉行首的 # 标记 */
    private static String stripHash(String line) {
        return line.replaceAll("^#+\\s*", "").trim();
    }

    /** 去掉行首行尾的 ** 标记 */
    private static String stripStars(String line) {
        return line.replaceAll("^\\*+|\\*+$", "").trim();
    }

    /** 反转义 Markdown 特殊字符 */
    private static String unescapeMd(String s) {
        if (s == null) return "";
        return s.replace("\\|", "|");
    }

    /** 判断表格分隔行 {@code |---|---|} */
    private static boolean isTableSeparator(String line) {
        String body = line.replaceAll("^\\|", "").replaceAll("\\|$", "");
        return body.matches("[\\s:|-]+");
    }

    /** 解析表格行为单元格列表 */
    private static List<String> parseTableRow(String line) {
        String body = line;
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : body.split("\\|", -1)) {
            cells.add(c.trim());
        }
        return cells;
    }

    /** 去掉单元格中的反引号包裹 */
    private static String cleanCell(String cell) {
        if (cell == null) return "";
        return cell.replace("`", "").trim();
    }

    private static void applyPathCell(ApiDefinition api, List<String> cells) {
        // | 参数名 | 类型 | 必填 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        p.setName(cleanCell(cells.get(0)));
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        p.setDescription(unescapeMd(cells.get(3)));
        p.setLocation(ParameterLocation.PATH);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyQueryCell(ApiDefinition api, List<String> cells) {
        // | 参数名 | 类型 | 必填 | 默认值 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        p.setName(cleanCell(cells.get(0)));
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        if (cells.size() >= 5) {
            p.setDefaultValue(cleanCell(cells.get(3)));
            p.setDescription(unescapeMd(cells.get(4)));
        } else {
            p.setDescription(unescapeMd(cells.get(3)));
        }
        p.setLocation(ParameterLocation.QUERY);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyBodyCell(ApiDefinition api, List<String> cells) {
        // | 字段 | 类型 | 必填 | 说明 |
        if (cells.size() < 4) return;
        ApiParameter p = new ApiParameter();
        String fullName = cleanCell(cells.get(0));
        // 嵌套字段 parent.child -> 取最后一段作为 name，全路径记入描述
        String name = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        p.setName(name);
        p.setType(cleanCell(cells.get(1)));
        p.setRequired(parseRequired(cells.get(2)));
        String desc = unescapeMd(cells.get(3));
        if (!fullName.equals(name)) {
            desc = (desc.isEmpty() ? "" : desc + " ") + "(嵌套路径: " + fullName + ")";
        }
        p.setDescription(desc);
        p.setLocation(ParameterLocation.BODY);
        if (!p.getName().isEmpty()) api.getParameters().add(p);
    }

    private static void applyHeaderCell(ApiDefinition api, List<String> cells) {
        // | Header名 | 必填 | 说明 |
        if (cells.size() < 3) return;
        String name = cleanCell(cells.get(0));
        if (name.isEmpty()) return;
        ApiParameter p = new ApiParameter();
        p.setName(name);
        p.setType("String");
        p.setRequired(parseRequired(cells.get(1)));
        p.setDescription(unescapeMd(cells.get(2)));
        p.setLocation(ParameterLocation.HEADER);
        api.getParameters().add(p);
        api.getHeaders().put(name, "");
    }

    private static boolean parseRequired(String cell) {
        String c = cell == null ? "" : cell.trim();
        return c.contains("是") || c.equalsIgnoreCase("true") || c.equalsIgnoreCase("yes");
    }
}