package com.hronline.util;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内置 Word（.docx）接口文档导出器。
 *
 * <p>格式与样式参考《设计开发接口模版-2024.09.03.docx》，保留模板的三级层级与标序：</p>
 * <ul>
 *   <li>一级标序「一、接口设计」（加粗 16pt，全文档唯一章节标题）</li>
 *   <li>二级标序「N、xxx接口：」（按接口顺序编号 1、2、3…）</li>
 *   <li>三级标序「（n）」：接口名称 / 接口地址 / 接口入参 / 接口出参 / 接口逻辑
 *       （与模板一致，不含请求方式/返回类型行）</li>
 *   <li>「接口入参」「接口出参」三列表格（字段名 / 类型 / 注释），
 *       表头灰底 F2F2F2 加粗，表格边框 CBCDD1，单元格垂直居中</li>
 *   <li>DTO / 嵌套对象字段以「父.子」点号路径<b>全部展开</b>到表格行；
 *       出参对 Result&lt;T&gt; 等泛型包装自动补全包装字段（code/msg/data），
 *       data 为具体对象时继续递归展开其字段</li>
 *   <li>结尾「接口逻辑」段落</li>
 * </ul>
 *
 * <p>不依赖 Apache POI：直接用 {@link ZipOutputStream} 构造 docx 包
 * （[Content_Types].xml + rels + word/document.xml + word/styles.xml）。</p>
 */
public class ApiDocWordExporter {

    /** 表格三列宽度（dxa），与参考模板一致 */
    private static final int[] COL_WIDTHS = {3035, 2100, 3079};

    /**
     * 将选中接口导出为 Word 文档。
     *
     * @param apis        选中的接口列表
     * @param projectName 当前项目名
     * @param outputPath  输出 .docx 文件绝对路径
     */
    public static void exportWord(List<ApiDefinition> apis, String projectName,
                                  String outputPath) throws IOException {
        if (apis == null) apis = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        // 一级标序「一、接口设计」：与参考模板一致，全文档唯一章节标题
        if (!apis.isEmpty()) {
            body.append(para("接口设计", true, 32, "1A1A1A", true, 240, 200, 1));
        }

        for (int i = 0; i < apis.size(); i++) {
            if (i > 0) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
            body.append(renderApi(apis.get(i), i + 1));
        }

        byte[] docx = buildDocx(body.toString(), apis.size());
        Path out = Paths.get(outputPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, docx);
    }

    // ================================================================
    // 单接口段落（层级与标序对齐参考模板：一、 → N、 → （n））
    // ================================================================

    /**
     * 渲染单个接口段落。
     *
     * @param index 接口序号（从 1 开始）。二级标序 numId=2 全文档连续递增（1、2、3…）；
     *              三级标序每个接口使用独立 numId（2+index），使「（n）」从（1）重新开始。
     */
    private static String renderApi(ApiDefinition api, int index) {
        StringBuilder sb = new StringBuilder();
        String name = api.getName() == null || api.getName().isBlank() ? nz(api.getUrl()) : api.getName();
        String url = nz(api.getUrl());
        int subNumId = 2 + index; // 三级标序（n）：每个接口一个独立编号实例，从（1）重新计数

        // 二级标序「N、xxx接口：」（十进制自动编号，全文档连续）
        sb.append(para(name + "接口：", true, 26, "1A1A1A", true, 120, 120, 2));

        // 三级标序「（n）」信息行（严格对齐参考模板：无请求方式/返回类型行）
        sb.append(para("接口名称：" + name, false, 22, "333333", false, 60, 0, subNumId));
        sb.append(para("接口地址：" + url, false, 22, "333333", false, 60, 0, subNumId));

        // 接口入参
        sb.append(para("接口入参：", true, 22, "333333", false, 120, 60, subNumId));
        sb.append(table(flattenRequestParams(api)));

        // 接口出参
        sb.append(para("接口出参：", true, 22, "333333", false, 180, 60, subNumId));
        sb.append(table(flattenResponseParams(api)));

        // 接口逻辑
        sb.append(para("接口逻辑：", true, 22, "333333", false, 180, 60, subNumId));
        String desc = api.getDescription() == null ? "" : api.getDescription().trim();
        sb.append(para(desc.isEmpty() ? "接口原有逻辑不变。" : desc,
                false, 22, "333333", false, 60, 120));
        return sb.toString();
    }

    // ================================================================
    // 参数扁平化（DTO / 嵌套对象全量展开，字段名用点号路径）
    // ================================================================

    /** 入参：Path / Query / Header 平铺 + Body 的 DTO 字段树递归展开 */
    public static List<String[]> flattenRequestParams(ApiDefinition api) {
        List<String[]> rows = new ArrayList<>();
        appendFlat(rows, api.pathParameters(), "", "路径参数");
        appendFlat(rows, api.queryParameters(), "", "查询参数");
        appendFlat(rows, api.headerParameters(), "", "请求头");
        for (ApiParameter body : api.bodyParameters()) {
            if (body.getChildren() != null && !body.getChildren().isEmpty()) {
                // @RequestBody 包装参数：直接展开其 DTO 字段树
                flattenTree(rows, body.getChildren(), "");
            } else {
                rows.add(new String[]{nz(body.getName()), nz(body.getType()), commentOf(body)});
            }
        }
        if (rows.isEmpty()) {
            rows.add(new String[]{"（无入参）", "-", "-"});
        }
        return rows;
    }

    /** 出参：响应字段树递归展开；识别 Result&lt;T&gt; 等泛型包装并补全包装字段，
     *  data 为具体对象时继续递归展开其包含的全部字段；无 schema 时回退到返回类型单行 */
    public static List<String[]> flattenResponseParams(ApiDefinition api) {
        List<String[]> rows = new ArrayList<>();
        List<ApiParameter> schema = api.getResponseSchema();
        String retType = api.getResponseBodyType();
        String[] wrapper = analyzeWrapper(retType);

        if (wrapper != null) {
            // Result<T> / R<T> 等泛型包装：补全包装层字段（与扫描器剥壳逻辑一致）
            rows.add(new String[]{"code", "Integer", "状态码，0-成功，其他码值-失败"});
            rows.add(new String[]{"msg", "String", "响应消息"});
            rows.add(new String[]{"data", wrapper[0], "返回数据"});
            // data 包含的具体对象字段：优先用扫描器解析出的字段树，
            // 其次尝试复用请求体中同类型的 DTO 字段树
            List<ApiParameter> dataFields = (schema != null && !schema.isEmpty())
                    ? schema : findBodyTreeByType(wrapper[1], api);
            if (dataFields != null && !dataFields.isEmpty()) {
                flattenTree(rows, dataFields, "data");
            }
        } else if (schema != null && !schema.isEmpty()) {
            flattenTree(rows, schema, "");
        } else if (retType != null && !retType.isBlank() && !"void".equalsIgnoreCase(retType)) {
            rows.add(new String[]{"data", retType, "返回数据（字段未在扫描器中识别）"});
        }
        if (rows.isEmpty()) {
            rows.add(new String[]{"（无返回体）", "void", "-"});
        }
        return rows;
    }

    /** 常见泛型包装类名（与扫描器 unwrapCommonResult 保持一致） */
    private static final java.util.Set<String> WRAPPER_NAMES = java.util.Set.of(
            "Result", "R", "CommonResult", "ApiResult", "BaseResult",
            "ResponseResult", "Response", "Resp", "RespResult", "AjaxResult");

    /**
     * 按字符串逐层剥壳返回类型：ResponseEntity&lt;T&gt; → 集合 → Result&lt;T&gt; 包装。
     *
     * @return null 表示不是泛型包装；否则 [0]=data 展示类型（含集合壳），[1]=data 内具体类型的简单名
     */
    private static String[] analyzeWrapper(String retType) {
        if (retType == null || retType.isBlank()) return null;
        String t = retType.trim();
        String inner = peelGeneric(t, "ResponseEntity");
        if (inner != null) t = inner.trim();
        t = peelCollection(t);
        int lt = t.indexOf('<');
        String head = lt > 0 ? t.substring(0, lt).trim() : t;
        String simpleName = head.contains(".") ? head.substring(head.lastIndexOf('.') + 1) : head;
        if (lt <= 0 || !WRAPPER_NAMES.contains(simpleName) || !t.endsWith(">")) return null;
        String arg = t.substring(lt + 1, t.length() - 1).trim();
        String dataDisplay = arg;
        String innerSimple = peelCollection(arg);
        int innerLt = innerSimple.indexOf('<');
        if (innerLt > 0) innerSimple = innerSimple.substring(0, innerLt).trim();
        if (innerSimple.contains(".")) innerSimple = innerSimple.substring(innerSimple.lastIndexOf('.') + 1);
        return new String[]{dataDisplay, innerSimple};
    }

    /** 剥掉 List&lt;T&gt;/Page&lt;T&gt; 等集合壳，返回元素类型；非集合原样返回 */
    private static String peelCollection(String t) {
        String s = t.trim();
        for (String c : new String[]{"List", "ArrayList", "Collection", "Set", "HashSet", "Page", "IPage"}) {
            String e = peelGeneric(s, c);
            if (e != null) return e.trim();
        }
        if (s.endsWith("[]")) return s.substring(0, s.length() - 2).trim();
        return s;
    }

    /** 匹配 "Name&lt;...&gt;" 或 "pkg.Name&lt;...&gt;"，返回泛型实参；不匹配返回 null */
    private static String peelGeneric(String t, String name) {
        String s = t.trim();
        int lt = s.indexOf('<');
        if (lt <= 0 || !s.endsWith(">")) return null;
        String head = s.substring(0, lt).trim();
        String simple = head.contains(".") ? head.substring(head.lastIndexOf('.') + 1) : head;
        if (!simple.equals(name)) return null;
        return s.substring(lt + 1, s.length() - 1);
    }

    /** 在请求体参数树中查找与类型简单名同名的 DTO 字段树（含嵌套字段） */
    private static List<ApiParameter> findBodyTreeByType(String typeSimpleName, ApiDefinition api) {
        if (typeSimpleName == null || typeSimpleName.isBlank()) return null;
        for (ApiParameter body : api.bodyParameters()) {
            List<ApiParameter> hit = matchTreeByType(body, typeSimpleName);
            if (hit != null) return hit;
        }
        return null;
    }

    private static List<ApiParameter> matchTreeByType(ApiParameter p, String typeSimpleName) {
        if (p == null) return null;
        String t = p.getType() == null ? "" : p.getType();
        int lt = t.indexOf('<');
        String simple = lt > 0 ? t.substring(0, lt) : t;
        if (simple.contains(".")) simple = simple.substring(simple.lastIndexOf('.') + 1);
        if (simple.equalsIgnoreCase(typeSimpleName)
                && p.getChildren() != null && !p.getChildren().isEmpty()) {
            return p.getChildren();
        }
        if (p.getChildren() != null) {
            for (ApiParameter c : p.getChildren()) {
                List<ApiParameter> hit = matchTreeByType(c, typeSimpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 递归展开字段树：name 以「父.子」点号路径拼接，全部字段一行不落地输出 */
    private static void flattenTree(List<String[]> rows, List<ApiParameter> fields, String prefix) {
        if (fields == null) return;
        for (ApiParameter f : fields) {
            String name = prefix.isEmpty() ? nz(f.getName()) : prefix + "." + nz(f.getName());
            rows.add(new String[]{name, nz(f.getType()), commentOf(f)});
            if (f.getChildren() != null && !f.getChildren().isEmpty()) {
                flattenTree(rows, f.getChildren(), name);
            }
        }
    }

    private static void appendFlat(List<String[]> rows, List<ApiParameter> params,
                                   String prefix, String posNote) {
        if (params == null) return;
        for (ApiParameter p : params) {
            String name = prefix.isEmpty() ? nz(p.getName()) : prefix + "." + nz(p.getName());
            String comment = commentOf(p);
            rows.add(new String[]{name, nz(p.getType()), comment.isEmpty() ? posNote : posNote + "；" + comment});
            if (p.getChildren() != null && !p.getChildren().isEmpty()) {
                flattenTree(rows, p.getChildren(), name);
            }
        }
    }

    /** 注释列：描述 + 必填 + 默认值 + 示例 */
    private static String commentOf(ApiParameter p) {
        StringBuilder sb = new StringBuilder();
        if (p.getDescription() != null && !p.getDescription().isBlank()) {
            sb.append(p.getDescription().trim());
        }
        if (p.isRequired()) {
            sb.append(sb.length() > 0 ? "（必填）" : "必填");
        }
        if (p.getDefaultValue() != null && !p.getDefaultValue().isBlank()) {
            sb.append(sb.length() > 0 ? "，" : "").append("默认: ").append(p.getDefaultValue().trim());
        }
        if (p.getExample() != null && !p.getExample().isBlank()) {
            sb.append(sb.length() > 0 ? "，" : "").append("例: ").append(p.getExample().trim());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ================================================================
    // OOXML 片段构造
    // ================================================================

    /**
     * 段落。sz 为半磅（22=11pt，32=16pt，21=10.5pt）。
     * numberingLevel：0=无编号；1=一级「一、」（japaneseCounting）；
     * 2=二级「N、」（decimal）；3=三级「（n）」（decimal 全角括号）。
     */
    private static String para(String text, boolean bold, int sz, String color,
                               boolean heading, int spacingBefore, int spacingAfter,
                               int numberingLevel) {
        String rpr = runProps(bold, sz, color);
        StringBuilder ppr = new StringBuilder("<w:pPr>");
        ppr.append("<w:spacing w:before=\"").append(spacingBefore)
                .append("\" w:after=\"").append(spacingAfter).append("\" w:line=\"312\" w:lineRule=\"auto\"/>");
        if (numberingLevel > 0) {
            ppr.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"")
                    .append(numberingLevel).append("\"/></w:numPr>");
        }
        if (heading) ppr.append("<w:outlineLvl w:val=\"").append(numberingLevel > 1 ? numberingLevel - 1 : 0).append("\"/>");
        ppr.append(rpr).append("</w:pPr>");
        return "<w:p>" + ppr + "<w:r>" + rpr
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    /** 无编号段落（接口逻辑正文等） */
    private static String para(String text, boolean bold, int sz, String color,
                               boolean heading, int spacingBefore, int spacingAfter) {
        return para(text, bold, sz, color, heading, spacingBefore, spacingAfter, 0);
    }

    private static String runProps(boolean bold, int sz, String color) {
        StringBuilder sb = new StringBuilder("<w:rPr>");
        sb.append("<w:rFonts w:ascii=\"等线\" w:eastAsia=\"等线\" w:hAnsi=\"等线\"/>");
        if (bold) sb.append("<w:b/><w:bCs/>");
        sb.append("<w:color w:val=\"").append(color).append("\"/>");
        sb.append("<w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/>");
        sb.append("</w:rPr>");
        return sb.toString();
    }

    /** 三列表格：表头灰底加粗，边框 CBCDD1（与参考模板一致） */
    private static String table(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"8214\" w:type=\"dxa\"/>");
        sb.append("<w:tblBorders>");
        for (String edge : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            sb.append("<w:").append(edge)
                    .append(" w:val=\"single\" w:color=\"CBCDD1\" w:sz=\"6\" w:space=\"0\"/>");
        }
        sb.append("</w:tblBorders>");
        sb.append("<w:tblLayout w:type=\"fixed\"/></w:tblPr>");
        sb.append("<w:tblGrid>");
        for (int w : COL_WIDTHS) sb.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        sb.append("</w:tblGrid>");
        sb.append(tableRow(new String[]{"字段名", "类型", "注释"}, true));
        for (String[] r : rows) {
            sb.append(tableRow(new String[]{r[0], r[1], r.length > 2 ? r[2] : ""}, false));
        }
        sb.append("</w:tbl>");
        return sb.toString();
    }

    private static String tableRow(String[] cells, boolean header) {
        StringBuilder sb = new StringBuilder("<w:tr>");
        for (int i = 0; i < 3; i++) {
            sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(COL_WIDTHS[i]).append("\" w:type=\"dxa\"/>");
            if (header) {
                sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>");
            }
            sb.append("<w:vAlign w:val=\"center\"/></w:tcPr>");
            sb.append(cellPara(cells[i], header));
            sb.append("</w:tc>");
        }
        sb.append("</w:tr>");
        return sb.toString();
    }

    private static String cellPara(String text, boolean header) {
        String rpr = runProps(header, 21, "333333");
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"276\" w:lineRule=\"auto\"/>"
                + rpr + "</w:pPr><w:r>" + rpr
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ================================================================
    // docx 打包
    // ================================================================

    private static byte[] buildDocx(String bodyXml, int apiCount) throws IOException {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + bodyXml
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1800\" w:bottom=\"1440\" w:left=\"1800\" "
                + "w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                + "</Types>";

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        String documentRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"
                + "</Relationships>";

        // numbering.xml：三级标序定义，与参考模板一致
        // numId=1 → 一、（japaneseCounting）；numId=2 → N、（decimal）
        // numId=3..2+N → （n）（decimal 全角括号），每个接口一个独立编号实例。
        // 每个实例必须指向【各自独立的 abstractNum 定义】：多个实例共享同一
        // abstractNum 时（即使带 startOverride），部分 Word / WPS 版本仍会把它们
        // 合并成同一计数器，导致第 2 个接口的（n）接着前一个接口继续编号（出现
        // （11）（12）…）；只有独立定义才能保证每个小节从（1）重新开始
        StringBuilder numbering = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + abstractNum("0", "japaneseCounting", "%1、")
                + abstractNum("1", "decimal", "%1、"));
        for (int i = 1; i <= apiCount; i++) {
            numbering.append(abstractNum(String.valueOf(1 + i), "decimal", "（%1）"));
        }
        numbering.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
                .append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>");
        for (int i = 1; i <= apiCount; i++) {
            numbering.append("<w:num w:numId=\"").append(2 + i)
                    .append("\"><w:abstractNumId w:val=\"").append(1 + i).append("\"/></w:num>");
        }
        numbering.append("</w:numbering>");
        String numberingXml = numbering.toString();

        // 最小 styles.xml：默认正文（等线 11pt 深灰），段内已做显式格式，兼容性最好
        String stylesXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"等线\" w:eastAsia=\"等线\" w:hAnsi=\"等线\"/>"
                + "<w:color w:val=\"333333\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>"
                + "</w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:before=\"60\" w:after=\"60\" w:line=\"312\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
                + "</w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
                + "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "</w:styles>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            putEntry(zout, "[Content_Types].xml", contentTypes);
            putEntry(zout, "_rels/.rels", rootRels);
            putEntry(zout, "word/document.xml", documentXml);
            putEntry(zout, "word/_rels/document.xml.rels", documentRels);
            putEntry(zout, "word/styles.xml", stylesXml);
            putEntry(zout, "word/numbering.xml", numberingXml);
        }
        return out.toByteArray();
    }

    /** 构造 abstractNum（只定义 lvl0，缩进对齐参考模板） */
    private static String abstractNum(String id, String fmt, String lvlText) {
        int ind = "0".equals(id) ? 640 : ("1".equals(id) ? 360 : 720);
        return "<w:abstractNum w:abstractNumId=\"" + id + "\">"
                + "<w:multiLevelType w:val=\"multilevel\"/>"
                + "<w:lvl w:ilvl=\"0\">"
                + "<w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"" + fmt + "\"/>"
                + "<w:lvlText w:val=\"" + lvlText + "\"/>"
                + "<w:lvlJc w:val=\"left\"/>"
                + "<w:pPr><w:ind w:left=\"" + ind + "\" w:hanging=\"" + ind + "\"/></w:pPr>"
                + "</w:lvl>"
                + "</w:abstractNum>";
    }

    private static void putEntry(ZipOutputStream zout, String name, String content) throws IOException {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }
}
