package com.hronline.util;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 自定义接口文档模板引擎。
 * <p>支持 Word（.docx）/ Markdown（.md, .markdown）模板，识别以下占位符：</p>
 * <ul>
 *   <li>项目级：<code>${project.name}</code> <code>${project.apiCount}</code>
 *       <code>${project.generatedAt}</code></li>
 *   <li>API 级（用于 <code>{#each apis}…{/each}</code> 循环体内）：<br>
 *       <code>${api.method}</code> <code>${api.url}</code> <code>${api.name}</code>
 *       <code>${api.description}</code> <code>${api.controller}</code>
 *       <code>${api.requestExample}</code> <code>${api.responseExample}</code>
 *       <code>${api.requestParams}</code> <code>${api.responseParams}</code>
 *       <code>${api.contentType}</code> <code>${api.returnType}</code></li>
 *   <li>条件：<code>{#if api.hasResponse}…{/if}</code> <code>{#if api.hasRequest}…{/if}</code></li>
 * </ul>
 * <p>Word 模板通过解压 docx、修改 word/document.xml 中的 <code>&lt;w:t&gt;</code> 节点后
 * 重新打包实现占位符替换，不依赖 Apache POI。</p>
 */
public class TemplateEngine {

    public static final String LOOP_EACH_APIS = "{#each apis}";
    public static final String LOOP_END = "{/each}";
    public static final String IF_HAS_RESPONSE_OPEN = "{#if api.hasResponse}";
    public static final String IF_HAS_REQUEST_OPEN = "{#if api.hasRequest}";
    public static final String IF_END = "{/if}";

    /** 模板类型枚举 */
    public enum TemplateType { DOCX, MARKDOWN, PDF, UNKNOWN }

    /**
     * 提取模板全文（docx 会拼回所有 {@code <w:t>} 文本），供占位符检测与调试。
     */
    public static String extractTemplateText(String templatePath) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(templatePath));
        if (templatePath.toLowerCase().endsWith(".docx")) {
            StringBuilder plain = new StringBuilder();
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (!entry.getName().equals("word/document.xml")) continue;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zin.read(buf)) > 0) baos.write(buf, 0, n);
                    String xml = baos.toString(StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>").matcher(xml);
                    while (m.find()) plain.append(m.group(1));
                }
            }
            return plain.toString();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 模板是否包含任何占位符（${...} 或 {#each apis}） */
    public static boolean hasPlaceholders(String templateText) {
        return templateText != null
                && (templateText.contains("${") || templateText.contains(LOOP_EACH_APIS));
    }

    /**
     * 生成内置示例 Markdown 模板内容（与《设计开发接口模版》结构一致的占位符版）。
     */
    public static String sampleMarkdownTemplate() {
        return "# 一、接口设计（${project.name}，共 ${project.apiCount} 个接口）\n\n"
                + "{#each apis}\n"
                + "### ${api.name}接口：\n\n"
                + "（1）接口名称：${api.name}\n\n"
                + "（2）接口地址：`${api.url}`\n\n"
                + "（3）请求方式：${api.method}（Content-Type: ${api.contentType}）\n\n"
                + "（4）返回类型：${api.returnType}\n\n"
                + "（5）接口入参：\n\n"
                + "${api.requestParams}\n\n"
                + "（6）接口出参：\n\n"
                + "${api.responseParams}\n\n"
                + "（7）请求示例：\n\n"
                + "```json\n${api.requestExample}\n```\n\n"
                + "（8）响应示例：\n\n"
                + "```json\n${api.responseExample}\n```\n\n"
                + "（9）接口逻辑：${api.description}\n\n"
                + "---\n\n"
                + "{/each}\n";
    }

    /**
     * 生成内置示例 Word 模板（.docx）。占位符与 Markdown 版相同；
     * docx 模板里循环标记与每个占位符行各自独占一个段落（Word 里一行一个），
     * 占位符值中的换行会被展开为同格式的多个段落。
     */
    public static void writeSampleDocxTemplate(String outputPath) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append(samplePara("${project.name} 接口文档（共 ${project.apiCount} 个接口，生成于 ${project.generatedAt}）"));
        String[] loopLines = {
                "{#each apis}",
                "${api.name}接口：",
                "（1）接口名称：${api.name}",
                "（2）接口地址：${api.url}",
                "（3）请求方式：${api.method}（Content-Type: ${api.contentType}）",
                "（4）返回类型：${api.returnType}",
                "（5）接口入参：",
                "${api.requestParams}",
                "（6）接口出参：",
                "${api.responseParams}",
                "（7）请求示例：${api.requestExample}",
                "（8）响应示例：${api.responseExample}",
                "（9）接口逻辑：${api.description}",
                "{/each}"
        };
        for (String line : loopLines) {
            body.append(samplePara(line));
        }

        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1800\" w:bottom=\"1440\" w:left=\"1800\" "
                + "w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            putEntry(zout, "[Content_Types].xml", contentTypes);
            putEntry(zout, "_rels/.rels", rels);
            putEntry(zout, "word/document.xml", documentXml);
        }
        Path p = Paths.get(outputPath);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, out.toByteArray());
    }

    private static String samplePara(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void putEntry(ZipOutputStream zout, String name, String content) throws IOException {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }

    /**
     * 根据文件名推断模板类型。
     */
    public static TemplateType detectType(String filename) {
        if (filename == null) return TemplateType.UNKNOWN;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) return TemplateType.DOCX;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return TemplateType.MARKDOWN;
        if (lower.endsWith(".pdf")) return TemplateType.PDF;
        return TemplateType.UNKNOWN;
    }

    /**
     * 用模板文件 + 选中接口生成文档，写入 outputPath。
     *
     * @param templatePath 模板文件绝对路径
     * @param apis         要填充的接口列表（已按用户选择排序）
     * @param projectName  当前项目名
     * @param outputPath   输出文件绝对路径
     */
    public static void render(String templatePath, List<ApiDefinition> apis,
                              String projectName, String outputPath) throws IOException {
        TemplateType type = detectType(templatePath);
        if (type == TemplateType.UNKNOWN) {
            throw new IOException("不支持的模板类型（仅支持 .docx / .md / .markdown）：" + templatePath);
        }
        if (type == TemplateType.PDF) {
            throw new IOException("PDF 模板需要表单域或专用库支持，请改用 .docx 或 .md 模板。"
                    + "\n如确需 PDF 导出，可先用 .md 模板生成后另存。");
        }
        // 读取模板内容
        byte[] bytes = Files.readAllBytes(Paths.get(templatePath));
        String templateText;
        if (type == TemplateType.MARKDOWN) {
            templateText = new String(bytes, StandardCharsets.UTF_8);
            String rendered = renderMarkdown(templateText, apis, projectName);
            Path out = Paths.get(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, rendered, StandardCharsets.UTF_8);
        } else {
            // DOCX：就地修改 document.xml 后整体写回
            byte[] rendered = renderDocx(bytes, apis, projectName);
            Path out = Paths.get(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, rendered);
        }
    }

    // ================================================================
    // Markdown 模板渲染
    // ================================================================

    static String renderMarkdown(String template, List<ApiDefinition> apis, String projectName) {
        if (template == null) template = "";
        if (apis == null) apis = new ArrayList<>();
        // 1) 展开 {#each apis}…{/each}
        StringBuilder result = new StringBuilder();
        int idx = 0;
        while (idx < template.length()) {
            int loopStart = template.indexOf(LOOP_EACH_APIS, idx);
            if (loopStart < 0) {
                result.append(template, idx, template.length());
                break;
            }
            // 拷贝循环外内容（先做项目级替换）
            String before = template.substring(idx, loopStart);
            result.append(replaceProjectPlaceholders(before, apis, projectName));
            int loopEndIdx = template.indexOf(LOOP_END, loopStart + LOOP_EACH_APIS.length());
            if (loopEndIdx < 0) {
                // 找不到结束标签 → 原样输出剩余内容
                result.append(template, loopStart, template.length());
                break;
            }
            String loopBody = template.substring(loopStart + LOOP_EACH_APIS.length(), loopEndIdx);
            // 对每个 api 渲染一次
            for (ApiDefinition api : apis) {
                result.append(renderApiBlock(loopBody, api));
            }
            idx = loopEndIdx + LOOP_END.length();
        }
        return replaceProjectPlaceholders(result.toString(), apis, projectName);
    }

    /** 替换项目级占位符（在循环外或循环内的字面位置） */
    private static String replaceProjectPlaceholders(String src, List<ApiDefinition> apis, String projectName) {
        String s = src;
        s = s.replace("${project.name}", projectName == null ? "" : projectName);
        s = s.replace("${project.apiCount}", String.valueOf(apis == null ? 0 : apis.size()));
        s = s.replace("${project.generatedAt}",
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        return s;
    }

    /** 渲染单个 API 块：对 api.xxx 占位符与 {#if}…{/if} 进行替换 */
    private static String renderApiBlock(String block, ApiDefinition api) {
        return replaceApiPlaceholders(processIfBlocks(block, api), api);
    }

    /** 替换 <code>${api.xxx}</code> 占位符 */
    private static String replaceApiPlaceholders(String s, ApiDefinition api) {
        s = s.replace("${api.method}", safe(api.getHttpMethod()));
        s = s.replace("${api.url}", safe(api.getUrl()));
        s = s.replace("${api.name}", safe(api.getName()));
        s = s.replace("${api.description}", safe(api.getDescription()));
        s = s.replace("${api.controller}", safe(api.getControllerName()));
        s = s.replace("${api.contentType}", safe(api.getConsumes(), "application/json"));
        s = s.replace("${api.returnType}", safe(api.getResponseBodyType(), "void"));
        s = s.replace("${api.requestParams}", renderRequestParamsTable(api));
        s = s.replace("${api.responseParams}", renderResponseParamsTable(api));
        s = s.replace("${api.requestExample}", renderRequestExample(api));
        s = s.replace("${api.responseExample}", renderResponseExample(api));
        return s;
    }

    /**
     * 处理 {#if api.hasResponse}…{#if api.hasRequest}…{/if} 块：
     * <p>对每个 {#if}…{/if} 段，先判断条件是否成立，成立则保留内部、去掉外层标记；
     * 不成立则整段移除（连同内部内容一起）。</p>
     */
    private static String processIfBlocks(String s, ApiDefinition api) {
        boolean hasResponse = api.getResponseBodyType() != null
                && !api.getResponseBodyType().isBlank()
                && !"void".equalsIgnoreCase(api.getResponseBodyType());
        boolean hasRequest = !api.bodyParameters().isEmpty()
                || !api.queryParameters().isEmpty()
                || !api.pathParameters().isEmpty();
        // 多次扫描以支持同一段内多个 if 块
        for (int iter = 0; iter < 8; iter++) {
            int start = s.indexOf("{#if");
            if (start < 0) break;
            int tagEnd = s.indexOf('}', start);
            if (tagEnd < 0) break;
            String tag = s.substring(start, tagEnd + 1);
            int endIdx = s.indexOf(IF_END, tagEnd + 1);
            if (endIdx < 0) break;
            boolean cond;
            if (tag.equals(IF_HAS_RESPONSE_OPEN)) cond = hasResponse;
            else if (tag.equals(IF_HAS_REQUEST_OPEN)) cond = hasRequest;
            else cond = false;
            String inner = s.substring(tagEnd + 1, endIdx);
            String replacement = cond ? inner : "";
            s = s.substring(0, start) + replacement + s.substring(endIdx + IF_END.length());
        }
        return s;
    }

    // ================================================================
    // DOCX 模板渲染
    // ================================================================

    /** docx 段落匹配（含自闭合） */
    private static final java.util.regex.Pattern PARA_PATTERN = java.util.regex.Pattern.compile(
            "<w:p\\b[^>]*>.*?</w:p>|<w:p\\b[^>]*/>", java.util.regex.Pattern.DOTALL);
    /** <w:t> 文本节点 */
    private static final java.util.regex.Pattern WT_PATTERN = java.util.regex.Pattern.compile(
            "<w:t(?:\\s[^>]*)?>([^<]*)</w:t>");

    /**
     * 渲染 docx 模板：解压 → 按段落渲染 word/document.xml（及页眉页脚）→ 重新打包。
     */
    static byte[] renderDocx(byte[] docxBytes, List<ApiDefinition> apis, String projectName) throws IOException {
        // 1) 解压
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zin.read(buf)) > 0) baos.write(buf, 0, n);
                entries.put(entry.getName(), baos.toByteArray());
            }
        }

        // 2) 替换 document.xml（主文档）与页眉页脚
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.equals("word/document.xml") || name.startsWith("word/header")
                    || name.startsWith("word/footer")) {
                String xml = new String(e.getValue(), StandardCharsets.UTF_8);
                String renderedXml = renderDocxXml(xml, apis, projectName);
                e.setValue(renderedXml.getBytes(StandardCharsets.UTF_8));
            }
        }

        // 3) 重新打包
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 段落级渲染 docx XML。
     * <p>模板约定：<code>{#each apis}</code> / <code>{/each}</code>、
     * <code>{#if ...}</code> / <code>{/if}</code> 各自独占一个段落（Word 里一行一个）；
     * 循环体段落对每个接口复制一份并替换 <code>${api.xxx}</code> 占位符。
     * 占位符值中的换行（如参数表）会拆成多个同格式段落。</p>
     */
    private static String renderDocxXml(String xml, List<ApiDefinition> apis, String projectName) {
        Matcher pm = PARA_PATTERN.matcher(xml);
        List<int[]> spans = new ArrayList<>();
        List<String> paraXmls = new ArrayList<>();
        List<String> paraTexts = new ArrayList<>();
        while (pm.find()) {
            spans.add(new int[]{pm.start(), pm.end()});
            paraXmls.add(pm.group());
            paraTexts.add(concatWT(pm.group()));
        }
        if (paraXmls.isEmpty()) return xml;

        StringBuilder out = new StringBuilder();
        int pos = 0;
        int i = 0;
        while (i < paraXmls.size()) {
            out.append(xml, pos, spans.get(i)[0]);
            String trimmed = paraTexts.get(i).trim();
            if (LOOP_EACH_APIS.equals(trimmed)) {
                int j = i + 1;
                while (j < paraXmls.size() && !LOOP_END.equals(paraTexts.get(j).trim())) j++;
                if (j >= paraXmls.size()) {
                    // 未闭合：原样保留
                    out.append(paraXmls.get(i));
                } else {
                    for (ApiDefinition api : apis) {
                        renderDocxLoopBody(paraXmls, paraTexts, i + 1, j, api, out);
                    }
                    pos = spans.get(j)[1];
                    i = j + 1;
                    continue;
                }
            } else if (replaceProjectPlaceholders(paraTexts.get(i), apis, projectName)
                    .equals(paraTexts.get(i))) {
                out.append(paraXmls.get(i));
            } else {
                out.append(textToParagraphXml(paraXmls.get(i),
                        replaceProjectPlaceholders(paraTexts.get(i), apis, projectName)));
            }
            pos = spans.get(i)[1];
            i++;
        }
        out.append(xml, pos, xml.length());
        return out.toString();
    }

    /** 渲染循环体段落区间 [from, to)，处理段落级 {#if} 标记 */
    private static void renderDocxLoopBody(List<String> paraXmls, List<String> paraTexts,
                                           int from, int to, ApiDefinition api, StringBuilder out) {
        int k = from;
        while (k < to) {
            String trimmed = paraTexts.get(k).trim();
            if (IF_HAS_RESPONSE_OPEN.equals(trimmed) || IF_HAS_REQUEST_OPEN.equals(trimmed)) {
                int end = k + 1;
                while (end < to && !IF_END.equals(paraTexts.get(end).trim())) end++;
                boolean cond = IF_HAS_RESPONSE_OPEN.equals(trimmed) ? hasResponse(api) : hasRequest(api);
                if (cond && end < to) {
                    renderDocxLoopBody(paraXmls, paraTexts, k + 1, end, api, out);
                }
                k = (end < to) ? end + 1 : to;
            } else if (IF_END.equals(trimmed) || LOOP_EACH_APIS.equals(trimmed) || LOOP_END.equals(trimmed)) {
                k++;
            } else {
                String t = processIfBlocks(paraTexts.get(k), api);
                t = replaceApiPlaceholders(t, api);
                out.append(t.equals(paraTexts.get(k)) ? paraXmls.get(k) : textToParagraphXml(paraXmls.get(k), t));
                k++;
            }
        }
    }

    /** 拼回段落内所有 <w:t> 文本（解码 XML 实体） */
    private static String concatWT(String paraXml) {
        Matcher m = WT_PATTERN.matcher(paraXml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append(decodeXml(m.group(1)));
        return sb.toString();
    }

    /**
     * 用新文本重建段落：第一行写入原段落（保留段落/运行格式），
     * 其余行复制为同格式的新段落。
     */
    private static String textToParagraphXml(String paraXml, String text) {
        String[] lines = text.split("\n", -1);
        Matcher pprm = java.util.regex.Pattern.compile("<w:pPr>.*?</w:pPr>", java.util.regex.Pattern.DOTALL)
                .matcher(paraXml);
        String pPr = pprm.find() ? pprm.group() : "";
        Matcher rr = java.util.regex.Pattern
                .compile("<w:r\\b[^>]*>\\s*(<w:rPr>.*?</w:rPr>)?", java.util.regex.Pattern.DOTALL)
                .matcher(paraXml);
        String rPr = rr.find() && rr.group(1) != null ? rr.group(1).trim() : "";

        Matcher wt = WT_PATTERN.matcher(paraXml);
        StringBuilder first = new StringBuilder();
        int p = 0;
        boolean firstDone = false;
        while (wt.find()) {
            first.append(paraXml, p, wt.start(1));
            first.append(esc(firstDone ? "" : lines[0]));
            firstDone = true;
            p = wt.end(1);
        }
        if (!firstDone) {
            StringBuilder sb = new StringBuilder("<w:p>").append(pPr);
            for (String line : lines) sb.append(runXml(rPr, line));
            return sb.append("</w:p>").toString();
        }
        first.append(paraXml, p, paraXml.length());
        StringBuilder out = new StringBuilder(first.toString());
        for (int li = 1; li < lines.length; li++) {
            out.append("<w:p>").append(pPr).append(runXml(rPr, lines[li])).append("</w:p>");
        }
        return out.toString();
    }

    private static String runXml(String rPr, String line) {
        return "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + esc(line) + "</w:t></w:r>";
    }

    private static String decodeXml(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static boolean hasResponse(ApiDefinition api) {
        return api.getResponseBodyType() != null
                && !api.getResponseBodyType().isBlank()
                && !"void".equalsIgnoreCase(api.getResponseBodyType());
    }

    private static boolean hasRequest(ApiDefinition api) {
        return !api.bodyParameters().isEmpty()
                || !api.queryParameters().isEmpty()
                || !api.pathParameters().isEmpty();
    }

    // ================================================================
    // 段落/示例渲染
    // ================================================================

    private static String renderRequestParamsTable(ApiDefinition api) {
        StringBuilder sb = new StringBuilder();
        appendParamsTable(sb, api.pathParameters(), "Path");
        appendParamsTable(sb, api.queryParameters(), "Query");
        appendParamsTable(sb, api.bodyParameters(), "Body");
        appendParamsTable(sb, api.headerParameters(), "Header");
        return sb.toString();
    }

    private static void appendParamsTable(StringBuilder sb, List<ApiParameter> params, String section) {
        if (params == null || params.isEmpty()) return;
        sb.append("**").append(section).append("**\n\n");
        sb.append("| 参数 | 类型 | 必填 | 说明 |\n");
        sb.append("|------|------|:---:|------|\n");
        for (ApiParameter p : params) {
            if ("Body".equals(section) && !p.getChildren().isEmpty()) {
                // @RequestBody 包装参数：直接展开 DTO 字段树（含全部嵌套字段）
                for (ApiParameter child : p.getChildren()) {
                    appendParamRowMd(sb, child, "");
                }
            } else {
                appendParamRowMd(sb, p, "");
            }
        }
        sb.append("\n");
    }

    private static void appendParamRowMd(StringBuilder sb, ApiParameter p, String prefix) {
        String name = prefix.isEmpty() ? p.getName() : prefix + "." + p.getName();
        sb.append("| `").append(safe(name)).append("` | ").append(safe(p.getType()))
          .append(" | ").append(p.isRequired() ? "✓" : "").append(" | ")
          .append(safe(p.getDescription()));
        if (!p.getDefaultValue().isBlank()) {
            sb.append(" (默认: `").append(safe(p.getDefaultValue())).append("`)");
        }
        if (!p.getExample().isBlank()) {
            sb.append(" 例: `").append(safe(p.getExample())).append("`");
        }
        sb.append(" |\n");
        if (!p.getChildren().isEmpty()) {
            for (ApiParameter c : p.getChildren()) {
                appendParamRowMd(sb, c, name);
            }
        }
    }

    private static String renderResponseParamsTable(ApiDefinition api) {
        // 复用 ApiDocExporter 现有逻辑
        StringBuilder md = new StringBuilder();
        String retType = api.getResponseBodyType();
        if (retType == null || retType.isBlank() || retType.equalsIgnoreCase("void")) {
            return "_无返回体_";
        }
        md.append("| 字段 | 类型 | 说明 |\n");
        md.append("|------|------|------|\n");
        List<ApiParameter> schema = api.getResponseSchema();
        if (schema != null && !schema.isEmpty()) {
            appendSchemaRows(md, schema, "");
        } else {
            // fallback：走原 ApiDocExporter 内部逻辑的简化版
            md.append("| - | ").append(safe(retType)).append(" | 复杂对象（未在扫描器中识别） |\n");
        }
        return md.toString();
    }

    private static void appendSchemaRows(StringBuilder md, List<ApiParameter> fields, String prefix) {
        for (ApiParameter f : fields) {
            String name = prefix + f.getName();
            md.append("| `").append(name).append("` | ").append(safe(f.getType()))
              .append(" | ").append(safe(f.getDescription())).append(" |\n");
            if (!f.getChildren().isEmpty()) {
                appendSchemaRows(md, f.getChildren(), name + ".");
            }
        }
    }

    private static String renderRequestExample(ApiDefinition api) {
        // 复用 ApiDocExporter
        try {
            return ApiDocExporter.buildRequestExampleForTemplate(api);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String renderResponseExample(ApiDefinition api) {
        // 复用 ApiDocExporter（公开一个用于模板的便捷方法）
        try {
            return ApiDocExporter.buildResponseExampleForTemplate(api);
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ================================================================
    // 工具
    // ================================================================

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String safe(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    /** 关闭 InputStream 的静默辅助（保留为工具 API） */
    public static void closeQuietly(InputStream in) {
        if (in != null) try { in.close(); } catch (IOException ignore) {}
    }
}
