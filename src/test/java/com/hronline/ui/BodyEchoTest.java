package com.hronline.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「回显」功能回归测试：
 * <p>请求体 Tab 的「回显」按钮把用户手动填写的 JSON 解析后写入参数列表（位置=BODY）。
 * 核心展平逻辑 {@link ApiDebuggerPanel#flattenJsonToParamRows} 与类型推断
 * {@link ApiDebuggerPanel#inferNumberType} 为静态方法，可脱离 UI 直接验证。</p>
 */
class BodyEchoTest {

    private static List<Object[]> rows(String json) {
        return ApiDebuggerPanel.flattenJsonToParamRows(
                JsonParser.parseString(json).getAsJsonObject(), "", 0);
    }

    @Test
    void flatJson_producesTypedBodyRows() {
        List<Object[]> rows = rows("{\"name\":\"tom\",\"age\":18,\"vip\":true,\"score\":9.5}");
        assertEquals(4, rows.size());
        // name
        assertEquals("name", rows.get(0)[0]);
        assertEquals("String", rows.get(0)[1]);
        assertEquals("BODY", rows.get(0)[2]);
        assertEquals("tom", rows.get(0)[3]);
        // age → Integer
        assertEquals("Integer", rows.get(1)[1]);
        assertEquals("18", rows.get(1)[3]);
        // vip → Boolean
        assertEquals("Boolean", rows.get(2)[1]);
        assertEquals("true", rows.get(2)[3]);
        // score → Double
        assertEquals("Double", rows.get(3)[1]);
        assertEquals("9.5", rows.get(3)[3]);
    }

    @Test
    void nestedObject_expandsToDotPathRows() {
        List<Object[]> rows = rows("{\"request\":{\"appId\":\"a1\",\"count\":2}}");
        assertEquals(3, rows.size());
        // 父行：Object，值留空
        assertEquals("request", rows.get(0)[0]);
        assertEquals("Object", rows.get(0)[1]);
        assertEquals("", rows.get(0)[3]);
        // 子行：点号路径
        assertEquals("request.appId", rows.get(1)[0]);
        assertEquals("a1", rows.get(1)[3]);
        assertEquals("request.count", rows.get(2)[0]);
    }

    @Test
    void array_keptAsSingleRowWithCompactJson() {
        List<Object[]> rows = rows("{\"items\":[1,2,3]}");
        assertEquals(1, rows.size());
        assertEquals("items", rows.get(0)[0]);
        assertEquals("Array", rows.get(0)[1]);
        assertEquals("[1,2,3]", rows.get(0)[3]);
    }

    @Test
    void nullValue_emitsEmptyStringRow() {
        List<Object[]> rows = rows("{\"remark\":null}");
        assertEquals(1, rows.size());
        assertEquals("remark", rows.get(0)[0]);
        assertEquals("", rows.get(0)[3]);
    }

    @Test
    void deepNesting_stopsAtDepthLimit() {
        // 6 层嵌套：前 4 层对象正常展开，第 5 层对象（depth=4）不再展开、整体序列化为字符串
        String json = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":\"deep\"}}}}}}";
        List<Object[]> rows = rows(json);
        assertEquals(5, rows.size());
        assertEquals("a", rows.get(0)[0]);
        assertEquals("a.b", rows.get(1)[0]);
        assertEquals("a.b.c", rows.get(2)[0]);
        assertEquals("a.b.c.d", rows.get(3)[0]);
        // depth=4 的对象整体作为字符串，不再展开 f
        assertEquals("a.b.c.d.e", rows.get(4)[0]);
        assertTrue(rows.get(4)[3].toString().contains("\"f\""));
    }

    @Test
    void inferNumberType_coversIntegerLongDouble() {
        assertEquals("Integer", ApiDebuggerPanel.inferNumberType("42"));
        assertEquals("Integer", ApiDebuggerPanel.inferNumberType("-2147483648"));
        assertEquals("Long", ApiDebuggerPanel.inferNumberType("9999999999"));
        assertEquals("Double", ApiDebuggerPanel.inferNumberType("3.14"));
        assertEquals("Double", ApiDebuggerPanel.inferNumberType("1e10"));
    }
}
