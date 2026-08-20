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
 * MD 导出冒烟测试：内容与 Word 导出同一信息要求
 * （层级标序 + DTO 嵌套字段全量展开 + 泛型 data 展开，文件名在 UI 层验证）。
 */
class ApiDocExporterTest {

    @TempDir
    Path tempDir;

    /** 构造带两层嵌套 DTO 的接口：OrderDTO → AddressDTO / List&lt;ItemDTO&gt; */
    private ApiDefinition buildNestedApi(String name, String url) {
        ApiDefinition api = new ApiDefinition();
        api.setName(name);
        api.setUrl(url);
        api.setHttpMethod("POST");
        api.setControllerName("OrderController");
        api.setResponseBodyType("Result<OrderDTO>");

        // Body 参数：OrderDTO（含 address: AddressDTO、items: List<ItemDTO>）
        ApiParameter body = new ApiParameter("order", "OrderDTO", ParameterLocation.BODY, true, "", "订单请求", "", null);
        ApiParameter orderId = new ApiParameter("orderId", "Long", ParameterLocation.BODY, true, "", "订单ID", "", null);
        ApiParameter address = new ApiParameter("address", "AddressDTO", ParameterLocation.BODY, true, "", "收货地址", "", null);
        address.getChildren().add(new ApiParameter("province", "String", ParameterLocation.BODY, true, "", "省", "", null));
        address.getChildren().add(new ApiParameter("city", "String", ParameterLocation.BODY, true, "", "市", "", null));
        ApiParameter items = new ApiParameter("items", "List<ItemDTO>", ParameterLocation.BODY, true, "", "订单项", "", null);
        ApiParameter item = new ApiParameter("item", "ItemDTO", ParameterLocation.BODY, false, "", "订单项", "", null);
        item.getChildren().add(new ApiParameter("skuId", "Long", ParameterLocation.BODY, true, "", "商品SKU", "", null));
        item.getChildren().add(new ApiParameter("qty", "Integer", ParameterLocation.BODY, true, "", "数量", "", null));
        items.getChildren().add(item);
        body.getChildren().add(orderId);
        body.getChildren().add(address);
        body.getChildren().add(items);
        api.getParameters().add(body);

        // 出参字段树：扫描器已解析 OrderDTO（与 Word 导出共用同一棵树）
        List<ApiParameter> schema = new ArrayList<>();
        schema.add(new ApiParameter("orderId", "Long", ParameterLocation.BODY, false, "", "订单ID", "", null));
        ApiParameter addrOut = new ApiParameter("address", "AddressDTO", ParameterLocation.BODY, false, "", "收货地址", "", null);
        addrOut.getChildren().add(new ApiParameter("province", "String", ParameterLocation.BODY, false, "", "省", "", null));
        addrOut.getChildren().add(new ApiParameter("city", "String", ParameterLocation.BODY, false, "", "市", "", null));
        schema.add(addrOut);
        ApiParameter itemsOut = new ApiParameter("items", "List<ItemDTO>", ParameterLocation.BODY, false, "", "订单项列表", "", null);
        ApiParameter itemOut = new ApiParameter("item", "ItemDTO", ParameterLocation.BODY, false, "", "订单项", "", null);
        itemOut.getChildren().add(new ApiParameter("skuId", "Long", ParameterLocation.BODY, false, "", "商品SKU", "", null));
        itemOut.getChildren().add(new ApiParameter("qty", "Integer", ParameterLocation.BODY, false, "", "数量", "", null));
        itemsOut.getChildren().add(itemOut);
        schema.add(itemsOut);
        api.setResponseSchema(schema);

        return api;
    }

    @Test
    void mdExport_alignsWithWord_structureAndNesting() throws IOException {
        ApiDefinition api1 = buildNestedApi("提交订单", "/sys/payorder/submitOrder");
        ApiDefinition api2 = buildNestedApi("airDrop", "/admin/collection/airDrop");

        Path out = tempDir.resolve("RestAutoLab-test.md");
        String md = ApiDocExporter.exportSelectedApis(List.of(api1, api2), out.toString());

        // 与 Word 一致的一级/二级/三级标序
        assertTrue(md.contains("# 一、接口设计"), "缺一级标序");
        assertTrue(md.contains("### 1、提交订单接口："), "第 1 个接口二级标序错误");
        assertTrue(md.contains("### 2、airDrop接口："), "第 2 个接口二级标序错误");
        // 三级标序每个接口从（1）重新开始
        assertTrue(md.indexOf("（1）接口名称：提交订单") >= 0);
        assertTrue(md.indexOf("（1）接口名称：airDrop") >= 0, "第 2 个接口（n）未从（1）重新计数");
        assertTrue(md.contains("（2）接口地址：`/admin/collection/airDrop`"));
        assertTrue(md.contains("（3）接口入参："));
        assertTrue(md.contains("（4）接口出参："));
        assertTrue(md.contains("（5）接口逻辑："));

        // 与 Word 一致：无请求方式/返回类型行
        assertFalse(md.contains("请求方式"), "MD 不应包含请求方式行");
        assertFalse(md.contains("返回类型"), "MD 不应包含返回类型行");
        assertFalse(md.contains("未在扫描器中识别"), "不应出现未识别占位");

        // 入参 DTO 嵌套字段点号路径全量展开
        assertTrue(md.contains("address"), "缺 address 字段");
        assertTrue(md.contains("address.province"), "缺嵌套字段 address.province");
        assertTrue(md.contains("address.city"), "缺嵌套字段 address.city");
        assertTrue(md.contains("items.item.skuId"), "缺嵌套字段 items.item.skuId");
        assertTrue(md.contains("items.item.qty"), "缺嵌套字段 items.item.qty");

        // 出参：Result<T> 包装补全 code/msg/data，data 的具体对象字段全量展开
        assertTrue(md.contains("| code | Integer |"), "缺泛型包装字段 code");
        assertTrue(md.contains("| msg | String |"), "缺泛型包装字段 msg");
        assertTrue(md.contains("| data | OrderDTO | 返回数据 |"), "缺 data 行");
        assertTrue(md.contains("data.orderId"), "缺 data 嵌套字段 data.orderId");
        assertTrue(md.contains("data.address.province"), "缺 data 嵌套字段 data.address.province");
        assertTrue(md.contains("data.items.item.skuId"), "缺 data 嵌套字段 data.items.item.skuId");

        // 三列表格与 Word 同构
        assertTrue(md.contains("| 字段名 | 类型 | 注释 |"));

        // 文件真实落盘
        String onDisk = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("# 一、接口设计"));
    }

    @Test
    void mdExport_voidReturnAndNoParams() throws IOException {
        ApiDefinition api = new ApiDefinition();
        api.setName("清空缓存");
        api.setUrl("/sys/cache/clear");
        api.setHttpMethod("POST");
        api.setResponseBodyType("void");

        String md = ApiDocExporter.exportSelectedApis(List.of(api), null);
        assertTrue(md.contains("（无入参）"), "无入参应有占位行");
        assertTrue(md.contains("（无返回体）"), "void 返回应有占位行");
    }

    /**
     * Word 导出：第 2 个接口的（n）必须从（1）重新计数。
     * 回归锁定：每个接口的三级标序编号实例必须指向各自独立的 abstractNum
     * 定义（共享 abstractNum 时部分 Word/WPS 版本会合并计数器，出现（11）（12）…）。
     */
    @Test
    void wordExport_subNumberingResetsPerApi() throws IOException {
        ApiDefinition api1 = buildNestedApi("提交订单", "/sys/payorder/submitOrder");
        ApiDefinition api2 = buildNestedApi("airDrop", "/admin/collection/airDrop");

        Path out = tempDir.resolve("RestAutoLab-test.docx");
        ApiDocWordExporter.exportWord(List.of(api1, api2), "RestAutoLab", out.toString());

        String numbering = readZipEntry(out, "word/numbering.xml");
        String document = readZipEntry(out, "word/document.xml");

        // 两个（n）标序定义相互独立，不共享 abstractNum
        int cnt = 0, idx = 0;
        while ((idx = numbering.indexOf("（%1）", idx)) >= 0) { cnt++; idx++; }
        assertTrue(cnt == 2, "每个接口应有独立的（n）abstractNum 定义，实际 " + cnt);
        assertTrue(!numbering.contains("startOverride"), "不再依赖 startOverride 合并修复");

        // 文档中两个接口段落分别引用不同的 numId，且各自映射到不同 abstractNumId
        String firstSubNum = "w:numId w:val=\"3\"", secondSubNum = "w:numId w:val=\"4\"";
        assertTrue(document.contains(firstSubNum), "第 1 个接口应引用 numId=3");
        assertTrue(document.contains(secondSubNum), "第 2 个接口应引用 numId=4");
        assertTrue(numbering.contains("<w:num w:numId=\"3\"><w:abstractNumId w:val=\"2\"/></w:num>"));
        assertTrue(numbering.contains("<w:num w:numId=\"4\"><w:abstractNumId w:val=\"3\"/></w:num>"));
    }

    private static String readZipEntry(Path zip, String entryName) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.zip.ZipEntry e = zf.getEntry(entryName);
            assertTrue(e != null, "docx 缺少 " + entryName);
            return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
