package com.hronline.util;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内置 Word（.docx）接口文档导出器。
 *
 * <p>格式与样式参考《设计开发接口模版-2024.09.03.docx》：</p>
 * <ul>
 *   <li>大标题「接口设计」（加粗 18pt，色 1A1A1A）</li>
 *   <li>信息行：修改接口 / 接口名称 / 接口地址 / 请求方式 / 返回类型</li>
 *   <li>「接口入参」「接口出参」三列表格（字段名 / 类型 / 注释），
 *       表头灰底 F2F2F2 加粗，表格边框 CBCDD1，单元格垂直居中</li>
 *   <li>DTO / 嵌套对象字段以「父.子」点号路径<b>全部展开</b>到表格行</li>
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

        // 文档头：项目与生成信息
        body.append(para("项目名称：" + (projectName == null || projectName.isBlank() ? "未命名" : projectName),
                false, 22, "333333", false, 60, 0));
        body.append(para("接口数量：" + apis.size() + " 个    生成时间："
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                false, 22, "333333", false, 60, 120));

        for (int i = 0; i < apis.size(); i++) {
            if (i > 0) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
            body.append(renderApi(apis.get(i)));
        }

        byte[] docx = buildDocx(body.toString());
        Path out = Paths.get(outputPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, docx);
    }

    // ================================================================
    // 单接口段落
    // ================================================================

    private static String renderApi(ApiDefinition api) {
        StringBuilder sb = new StringBuilder();
        String name = api.getName() == null || api.getName().isBlank() ? nz(api.getUrl()) : api.getName();
        String url = nz(api.getUrl());
        String method = api.getHttpMethod() == null ? "GET" : api.getHttpMethod().toUpperCase();
        String contentType = api.getConsumes() == null || api.getConsumes().isBlank()
                ? "application/json" : api.getConsumes();
        String retType = api.getResponseBodyType() == null || api.getResponseBodyType().isBlank()
                ? "void" : api.getResponseBodyType();

        // 大标题（样式对齐参考模板 heading 1：加粗 18pt）
        sb.append(para("接口设计", true, 36, "1A1A1A", true, 240, 120));

        sb.append(para("修改接口：", false, 22, "333333", false, 60, 0));
        sb.append(para("接口名称：" + name, false, 22, "333333", false, 60, 0));
        sb.append(para("接口地址：" + url, false, 22, "333333", false, 60, 0));
        sb.append(para("请求方式：" + method + "（Content-Type: " + contentType + "）",
                false, 22, "333333", false, 60, 0));
        sb.append(para("返回类型：" + retType, false, 22, "333333", false, 60, 120));

        // 接口入参
        sb.append(para("接口入参：", true, 22, "333333", false, 120, 60));
        sb.append(table(flattenRequestParams(api)));

        // 接口出参
        sb.append(para("接口出参：", true, 22, "333333", false, 180, 60));
        sb.append(table(flattenResponseParams(api)));

        // 接口逻辑
        sb.append(para("接口逻辑：", true, 22, "333333", false, 180, 60));
        String desc = api.getDescription() == null ? "" : api.getDescription().trim();
        sb.append(para(desc.isEmpty() ? "接口原有逻辑不变。" : desc,
                false, 22, "333333", false, 60, 120));
        return sb.toString();
    }

    // ================================================================
    // 参数扁平化（DTO / 嵌套对象全量展开，字段名用点号路径）
    // ================================================================

    /** 入参：Path / Query / Header 平铺 + Body 的 DTO 字段树递归展开 */
    private static List<String[]> flattenRequestParams(ApiDefinition api) {
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

    /** 出参：响应字段树递归展开；无 schema 时回退到返回类型单行 */
    private static List<String[]> flattenResponseParams(ApiDefinition api) {
        List<String[]> rows = new ArrayList<>();
        List<ApiParameter> schema = api.getResponseSchema();
        if (schema != null && !schema.isEmpty()) {
            flattenTree(rows, schema, "");
        } else {
            String retType = api.getResponseBodyType();
            if (retType != null && !retType.isBlank() && !"void".equalsIgnoreCase(retType)) {
                rows.add(new String[]{"data", retType, "返回数据（字段未在扫描器中识别）"});
            }
        }
        if (rows.isEmpty()) {
            rows.add(new String[]{"（无返回体）", "void", "-"});
        }
        return rows;
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

    /** 段落。sz 为半磅（22=11pt，36=18pt，21=10.5pt） */
    private static String para(String text, boolean bold, int sz, String color,
                               boolean heading, int spacingBefore, int spacingAfter) {
        String rpr = runProps(bold, sz, color);
        StringBuilder ppr = new StringBuilder("<w:pPr>");
        ppr.append("<w:spacing w:before=\"").append(spacingBefore)
                .append("\" w:after=\"").append(spacingAfter).append("\" w:line=\"312\" w:lineRule=\"auto\"/>");
        if (heading) ppr.append("<w:outlineLvl w:val=\"0\"/>");
        ppr.append(rpr).append("</w:pPr>");
        return "<w:p>" + ppr + "<w:r>" + rpr
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
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

    private static byte[] buildDocx(String bodyXml) throws IOException {
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
                + "</Types>";

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";

        String documentRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";

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
        }
        return out.toByteArray();
    }

    private static void putEntry(ZipOutputStream zout, String name, String content) throws IOException {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }
}
