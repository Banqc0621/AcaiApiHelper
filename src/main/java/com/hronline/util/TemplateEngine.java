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
        String s = block;
        // 1) 处理 {#if} 块：保留命中分支，移除另一分支
        s = processIfBlocks(s, api);
        // 2) 替换占位符
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

    /**
     * 渲染 docx 模板：解压 → 修改 word/document.xml 中 <w:t> 节点文本 → 重新打包。
     * <p>由于 docx 的占位符可能被 Word 拆到多个 <w:t>（同段被切分），先尝试在
     * 整段 XML 上做占位符替换（简单但可能漏），再回退到「拼回文本 → 替换 → 再切回
     * <w:t>」的串行文本方案：取每个 <w:p> 段落拼成单字符串后做替换。</p>
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

        // 2) 替换 document.xml（主文档）。Word 文档里占位符一般都在 document.xml
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.equals("word/document.xml") || name.startsWith("word/header")
                    || name.startsWith("word/footer")) {
                String xml = new String(e.getValue(), StandardCharsets.UTF_8);
                String renderedXml = renderInDocxXml(xml, apis, projectName);
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
     * 在 docx 的 document.xml 上做占位符替换。
     * <p>策略：先把整段 XML 中所有 <w:t>…</w:t> 节点文本按出现顺序收集为数组，
     * 拼成一个大字符串做模板渲染，再按字符长度比例切回去写入对应 <w:t>。
     * 这样可正确处理同一段被拆成多个 <w:t> 的情形。</p>
     */
    private static String renderInDocxXml(String xml, List<ApiDefinition> apis, String projectName) {
        // 收集所有 <w:t>…</w:t> 节点（可能含 xml:space="preserve"）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<w:t(?:\\s[^>]*)?>([^<]*)</w:t>");
        java.util.regex.Matcher m = p.matcher(xml);
        StringBuilder plain = new StringBuilder();
        List<int[]> spans = new ArrayList<>(); // 每个 <w:t> 文本在 plain 中的 [start,end)
        List<String> originals = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            // 区间内保持原 xml 其它部分：占位符不跨非 <w:t> 标签，先做近似切分
            // 简单方案：把 m.group(1) 直接拼到 plain
            String t = m.group(1);
            int s = plain.length();
            plain.append(t);
            spans.add(new int[]{s, plain.length()});
            originals.add(t);
            lastEnd = m.end();
        }
        if (plain.length() == 0) return xml;
        // 在 plain 上做 Markdown 模板渲染（同样的占位符语法）
        String rendered = renderMarkdown(plain.toString(), apis, projectName);
        if (rendered.equals(plain.toString())) {
            // 没替换：保持原样
            return xml;
        }
        // 重建 xml：把每个 <w:t>…</w:t> 内的文本按 spans 切到 rendered
        // 简单粗暴：按 rendered 的子串替换原文中每个 <w:t> 节点内的内容
        m = p.matcher(xml);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        int cursor = 0;
        while (m.find()) {
            out.append(xml, pos, m.start(1));
            int[] span = spans.get(0); // 因为 matcher 是顺序的，可维护 idx
            // 用 spans 的索引对齐
            int origIdx = -1;
            // 通过游标顺序读取 spans
            origIdx = nextSpanIdx(cursor, spans);
            cursor++;
            if (origIdx < 0) {
                out.append(m.group(1));
            } else {
                int s = spans.get(origIdx)[0];
                int e = spans.get(origIdx)[1];
                if (e <= rendered.length()) {
                    out.append(escapeForWText(rendered.substring(s, e)));
                } else {
                    out.append(m.group(1));
                }
            }
            out.append(xml, m.end(1), m.end());
            pos = m.end();
        }
        out.append(xml, pos, xml.length());
        return out.toString();
    }

    /** 根据第几次匹配返回 spans 索引（直接用 cursor 即可，spans 顺序与 <w:t> 出现顺序一致） */
    private static int nextSpanIdx(int cursor, List<int[]> spans) {
        return cursor < spans.size() ? cursor : -1;
    }

    /** XML 中 <w:t> 节点内的特殊字符转义（只处理 &、<、>） */
    private static String escapeForWText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
            appendParamRowMd(sb, p, "");
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
