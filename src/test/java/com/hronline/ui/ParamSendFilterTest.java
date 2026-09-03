package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.table.DefaultTableModel;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：sendRequest 按 HTTP method 过滤参数行。
 * <ul>
 *   <li>POST/PUT/PATCH → 只发请求体（location=BODY/FILE 的行）</li>
 *   <li>GET/DELETE/HEAD 等 → 只发 path/query/header（location 非 BODY/FILE 的行）</li>
 * </ul>
 * 这样避免 POST 请求同时把参数写进 query string + body 造成服务端重复解析。
 */
class ParamSendFilterTest {

    private static DefaultTableModel newModel() {
        return new DefaultTableModel(
                new Object[]{"name", "type", "position", "value", "required", "description"}, 0);
    }

    private static void addRow(DefaultTableModel m, String name, String position, String value) {
        m.addRow(new Object[]{name, "String", position, value, "否", ""});
    }

    @Test
    void isBodyMethod_recognizesStandardBodyMethods() {
        assertTrue(ApiDebuggerPanel.isBodyMethod("POST"));
        assertTrue(ApiDebuggerPanel.isBodyMethod("PUT"));
        assertTrue(ApiDebuggerPanel.isBodyMethod("PATCH"));
        assertTrue(ApiDebuggerPanel.isBodyMethod("post"));  // 大小写不敏感
        assertTrue(ApiDebuggerPanel.isBodyMethod(" Post "));
        assertFalse(ApiDebuggerPanel.isBodyMethod("GET"));
        assertFalse(ApiDebuggerPanel.isBodyMethod("DELETE"));
        assertFalse(ApiDebuggerPanel.isBodyMethod("HEAD"));
        assertFalse(ApiDebuggerPanel.isBodyMethod("OPTIONS"));
        assertFalse(ApiDebuggerPanel.isBodyMethod(null));
        assertFalse(ApiDebuggerPanel.isBodyMethod(""));
    }

    @Test
    void postRequest_keepsOnlyBodyAndFileRows() {
        DefaultTableModel model = newModel();
        addRow(model, "pageSize", "QUERY", "20");
        addRow(model, "token", "HEADER", "abc");
        addRow(model, "userId", "PATH", "100");
        addRow(model, "name", "BODY", "tom");
        addRow(model, "avatar", "FILE", "/tmp/a.png");
        addRow(model, "emptyBody", "BODY", "");  // 空 value 也按 BODY 行处理

        Map<String, String> filtered = ApiDebuggerPanel.filterParamsByMethod(model, true, false);

        assertEquals(2, filtered.size());
        assertEquals("tom", filtered.get("name"));
        assertEquals("/tmp/a.png", filtered.get("avatar"));
        assertFalse(filtered.containsKey("pageSize"));
        assertFalse(filtered.containsKey("token"));
        assertFalse(filtered.containsKey("userId"));
    }

    @Test
    void getRequest_keepsOnlyNonBodyRows() {
        DefaultTableModel model = newModel();
        addRow(model, "pageSize", "QUERY", "20");
        addRow(model, "token", "HEADER", "abc");
        addRow(model, "userId", "PATH", "100");
        addRow(model, "name", "BODY", "tom");
        addRow(model, "avatar", "FILE", "/tmp/a.png");

        Map<String, String> filtered = ApiDebuggerPanel.filterParamsByMethod(model, false, false);

        assertEquals(3, filtered.size());
        assertEquals("20", filtered.get("pageSize"));
        assertEquals("abc", filtered.get("token"));
        assertEquals("100", filtered.get("userId"));
        assertFalse(filtered.containsKey("name"));
        assertFalse(filtered.containsKey("avatar"));
    }

    @Test
    void blankValuesAreExcludedFromSendButPreservedForSave() {
        DefaultTableModel model = newModel();
        addRow(model, "pageSize", "QUERY", "");  // 空 value
        addRow(model, "pageNum", "QUERY", "1");

        // 发请求时 includeBlank=false：空 value 不发送
        Map<String, String> sending = ApiDebuggerPanel.filterParamsByMethod(model, false, false);
        assertEquals(1, sending.size());
        assertFalse(sending.containsKey("pageSize"));
        assertTrue(sending.containsKey("pageNum"));

        // 保存 / 快照时 includeBlank=true：保留空 value（用户可能想清空后保存成"空"）
        Map<String, String> saving = ApiDebuggerPanel.filterParamsByMethod(model, false, true);
        assertEquals(2, saving.size());
        assertTrue(saving.containsKey("pageSize"));
        assertEquals("", saving.get("pageSize"));
    }

    @Test
    void emptyModelAndNullModelBothReturnEmptyMap() {
        assertEquals(0, ApiDebuggerPanel.filterParamsByMethod(null, true, false).size());
        assertEquals(0, ApiDebuggerPanel.filterParamsByMethod(newModel(), true, false).size());
    }

    @Test
    void endToEnd_postWithMixedParamTable_keepsOnlyBodySide() {
        // 真实场景：用户填了混合参数，POST 请求只发 body 侧
        DefaultTableModel model = newModel();
        addRow(model, "tenantId", "HEADER", "t1");      // 全局头
        addRow(model, "debug", "QUERY", "true");         // 调试开关
        addRow(model, "id", "PATH", "123");              // 路径参数
        addRow(model, "request.appId", "BODY", "a1");   // body 点号路径
        addRow(model, "request.appName", "BODY", "tom"); // body 点号路径
        addRow(model, "config.json", "FILE", "/tmp/cfg.json"); // 文件附件

        Map<String, String> params = ApiDebuggerPanel.filterParamsByMethod(model, true, false);

        assertEquals(3, params.size());
        assertTrue(params.containsKey("request.appId"));
        assertTrue(params.containsKey("request.appName"));
        assertTrue(params.containsKey("config.json"));
        // 关键断言：POST 不能把 tenantId / debug / id 重复发到 query string + body
        assertFalse(params.containsKey("tenantId"));
        assertFalse(params.containsKey("debug"));
        assertFalse(params.containsKey("id"));
    }
}