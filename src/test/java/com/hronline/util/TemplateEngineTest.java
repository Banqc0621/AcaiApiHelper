package com.hronline.util;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板导出（用模板导出）冒烟测试：
 * <p>1. 内置示例模板必须含占位符（占位符检测不报「没效果」）；
 * 2. MD / DOCX 示例模板渲染后占位符被替换为真实接口数据，
 *    嵌套 DTO 字段以点号路径展开。</p>
 */
class TemplateEngineTest {

    @TempDir
    Path tempDir;

    /** 构造带两层嵌套 DTO 的接口（与 ApiDocExporterTest 同款 fixture） */
    private ApiDefinition buildNestedApi(String name, String url) {
        ApiDefinition api = new ApiDefinition();
        api.setName(name);
        api.setUrl(url);
        api.setHttpMethod("POST");
        api.setDescription(name + "的逻辑说明");
        api.setResponseBodyType("Result<OrderDTO>");

        ApiParameter body = new ApiParameter("order", "OrderDTO", ParameterLocation.BODY, true, "", "订单请求", "", null);
        ApiParameter orderId = new ApiParameter("orderId", "Long", ParameterLocation.BODY, true, "", "订单ID", "", null);
        ApiParameter address = new ApiParameter("address", "AddressDTO", ParameterLocation.BODY, true, "", "收货地址", "", null);
        address.getChildren().add(new ApiParameter("province", "String", ParameterLocation.BODY, true, "", "省", "", null));
        address.getChildren().add(new ApiParameter("city", "String", ParameterLocation.BODY, true, "", "市", "", null));
        body.getChildren().add(orderId);
        body.getChildren().add(address);
        api.getParameters().add(body);

        List<ApiParameter> schema = new ArrayList<>();
        schema.add(new ApiParameter("orderId", "Long", ParameterLocation.BODY, false, "", "订单ID", "", null));
        ApiParameter addrOut = new ApiParameter("address", "AddressDTO", ParameterLocation.BODY, false, "", "收货地址", "", null);
        addrOut.getChildren().add(new ApiParameter("province", "String", ParameterLocation.BODY, false, "", "省", "", null));
        addrOut.getChildren().add(new ApiParameter("city", "String", ParameterLocation.BODY, false, "", "市", "", null));
        schema.add(addrOut);
        api.setResponseSchema(schema);

        return api;
    }

    @Test
    void sampleMarkdownTemplate_rendersPlaceholders() throws IOException {
        List<ApiDefinition> apis = List.of(
                buildNestedApi("提交订单", "/sys/payorder/submitOrder"),
                buildNestedApi("airDrop", "/admin/collection/airDrop"));
        String rendered = TemplateEngine.renderMarkdown(
                TemplateEngine.sampleMarkdownTemplate(), apis, "demo-project");

        assertTrue(TemplateEngine.hasPlaceholders(TemplateEngine.sampleMarkdownTemplate()),
                "内置 MD 示例模板必须含占位符");
        assertTrue(rendered.contains("接口名称：提交订单"), "接口名称未替换");
        assertTrue(rendered.contains("接口地址：`/sys/payorder/submitOrder`"), "接口地址未替换");
        assertTrue(rendered.contains("airDrop"), "第二个接口未展开");
        assertTrue(rendered.contains("`address.province`"), "嵌套字段未按点号路径展开");
        assertTrue(rendered.contains("demo-project"), "项目级占位符未替换");
        assertFalse(rendered.contains("${api."), "仍有未替换的 api 占位符: " + firstOccurrence(rendered, "${api."));
        assertFalse(rendered.contains("{#each"), "循环标记未消费");
    }

    @Test
    void sampleDocxTemplate_rendersPlaceholders() throws IOException {
        Path template = tempDir.resolve("sample-template.docx");
        TemplateEngine.writeSampleDocxTemplate(template.toString());

        String templateText = TemplateEngine.extractTemplateText(template.toString());
        assertTrue(TemplateEngine.hasPlaceholders(templateText), "内置 DOCX 示例模板必须含占位符");
        assertTrue(templateText.contains("{#each apis}"), "DOCX 示例模板缺循环标记");

        Path out = tempDir.resolve("out.docx");
        TemplateEngine.render(template.toString(), List.of(
                buildNestedApi("提交订单", "/sys/payorder/submitOrder")), "demo-project", out.toString());

        String renderedText = TemplateEngine.extractTemplateText(out.toString());
        assertTrue(renderedText.contains("提交订单"), "接口名称未替换");
        assertTrue(renderedText.contains("/sys/payorder/submitOrder"), "接口地址未替换");
        assertTrue(renderedText.contains("address.province"), "嵌套字段未按点号路径展开");
        assertFalse(renderedText.contains("${api."), "仍有未替换的 api 占位符");
    }

    @Test
    void docxPlaceholderSplitAcrossRuns_stillDetectedAndRendered() throws IOException {
        // 模拟 Word 把 ${api.name} 拆成多个 <w:t> run 的常见情况
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>{#each</w:t></w:r><w:r><w:t> apis}</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>接口名称：</w:t></w:r><w:r><w:t>${api.</w:t></w:r><w:r><w:t>name}</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>{/each}</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        Path docx = tempDir.resolve("split.docx");
        writeMinimalDocx(docx, xml);

        Path out = tempDir.resolve("split-out.docx");
        TemplateEngine.render(docx.toString(), List.of(
                buildNestedApi("提交订单", "/x"), buildNestedApi("airDrop", "/y")), "p", out.toString());

        String rendered = TemplateEngine.extractTemplateText(out.toString());
        assertTrue(rendered.contains("接口名称：提交订单"), "拆分的占位符未合并替换");
        assertTrue(rendered.contains("接口名称：airDrop"), "循环未对每个接口生效");
        assertFalse(rendered.contains("${api."), "仍有未替换占位符");
    }

    private static void writeMinimalDocx(Path path, String documentXml) throws IOException {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(path))) {
            putEntry(zout, "[Content_Types].xml", contentTypes);
            putEntry(zout, "_rels/.rels", rels);
            putEntry(zout, "word/document.xml", documentXml);
        }
    }

    private static void putEntry(java.util.zip.ZipOutputStream zout, String name, String content) throws IOException {
        zout.putNextEntry(new java.util.zip.ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }

    private static String firstOccurrence(String s, String marker) {
        int i = s.indexOf(marker);
        return i < 0 ? "" : s.substring(i, Math.min(i + 60, s.length()));
    }
}
