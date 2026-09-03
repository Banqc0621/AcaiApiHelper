package com.hronline.ui;

import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import org.junit.jupiter.api.Test;

import javax.swing.table.DefaultTableModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 一伦优化 #66：「保存参数」按钮 + loadApi 替换语义回归测试。
 * <p>核心变更：loadApi 在 saved 非空时不再 merge 默认参数，而是完全按 saved 重建。</p>
 */
class SavedParameterReplaceTest {

    private static DefaultTableModel newModel() {
        // 列：name / type / position / value / required / description
        return new DefaultTableModel(
                new Object[]{"name", "type", "position", "value", "required", "description"}, 0);
    }

    private static ApiParameter param(String name, String type, ParameterLocation loc,
                                      boolean required, String desc) {
        ApiParameter p = new ApiParameter();
        p.setName(name);
        p.setType(type);
        p.setLocation(loc);
        p.setRequired(required);
        p.setDescription(desc);
        return p;
    }

    // ---------- collectAllParameterRows（saveCurrentParameters 内部用的全量快照） ----------

    @Test
    void collectAllParameterRows_keepsAllLocationsRegardlessOfMethod() {
        // saveCurrentParameters 不按 method 过滤——保存是完整快照，不是 send 用的子集
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"pageSize", "Integer", "QUERY", "20", "否", ""});
        model.addRow(new Object[]{"token", "String", "HEADER", "abc", "否", ""});
        model.addRow(new Object[]{"userId", "String", "PATH", "100", "是", ""});
        model.addRow(new Object[]{"name", "String", "BODY", "tom", "否", ""});

        Map<String, String> snapshot = ApiDebuggerPanel.collectAllParameterRows(model, true);

        assertEquals(4, snapshot.size(), "全量快照包含所有 location 的行");
        assertEquals("20", snapshot.get("pageSize"));
        assertEquals("abc", snapshot.get("token"));
        assertEquals("100", snapshot.get("userId"));
        assertEquals("tom", snapshot.get("name"));
    }

    @Test
    void collectAllParameterRows_includeBlankTruePreservesClearedValues() {
        // 用户主动清空某行 value 后保存——快照必须保留空值，不能用默认值「救活」
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"a", "String", "QUERY", "oldA", "否", ""});
        model.addRow(new Object[]{"b", "String", "QUERY", "", "否", ""});

        Map<String, String> snap = ApiDebuggerPanel.collectAllParameterRows(model, true);
        assertEquals(2, snap.size());
        assertEquals("oldA", snap.get("a"));
        assertTrue(snap.containsKey("b"));
        assertEquals("", snap.get("b"));
    }

    @Test
    void collectAllParameterRows_skipsBlankNameRows() {
        // 名字为空（用户新建后没填名）的行不入持久化（没意义）
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"", "String", "QUERY", "x", "否", ""});
        model.addRow(new Object[]{"   ", "String", "QUERY", "y", "否", ""});
        model.addRow(new Object[]{"real", "String", "QUERY", "z", "否", ""});

        Map<String, String> snap = ApiDebuggerPanel.collectAllParameterRows(model, true);
        assertEquals(1, snap.size());
        assertTrue(snap.containsKey("real"));
    }

    // ---------- replaceParametersWithSaved（loadApi 新行为） ----------

    @Test
    void replaceParametersWithSaved_clearsDefaultsAndRebuildsFromSaved() {
        // 模型当前有默认参数（模拟 loadApi 第一步的 PATH/QUERY/BODY 添加）
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"pageSize", "Integer", "QUERY", "10", "否", "页大小"});
        model.addRow(new Object[]{"pageNum", "Integer", "QUERY", "1", "否", ""});

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("pageSize", "20");      // 覆盖默认
        saved.put("pageSize", "30");      // 同名二次写（实际不会出现；这里验证后者覆盖前者的 LinkedHashMap 行为）

        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(
                param("pageSize", "Integer", ParameterLocation.QUERY, false, "页大小")
        ), saved);

        assertEquals(1, model.getRowCount(), "默认参数里没在 saved 出现的行被清掉");
        assertEquals("pageSize", model.getValueAt(0, 0));
        assertEquals("30", model.getValueAt(0, 3), "saved value 覆盖默认 value");
        assertEquals("页大小", model.getValueAt(0, 5), "description 来自默认参数");
    }

    @Test
    void replaceParametersWithSaved_dropsDefaultsNotInSaved() {
        // 关键测试：#66 用户语义——saved 是唯一真相，默认参数里没出现在 saved 的字段完全消失
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"pageSize", "Integer", "QUERY", "10", "否", ""});
        model.addRow(new Object[]{"pageNum", "Integer", "QUERY", "1", "否", ""});
        model.addRow(new Object[]{"newParam", "String", "HEADER", "abc", "否", ""});

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("pageSize", "20");
        // saved 里没 pageNum / newParam——它们必须消失

        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(
                param("pageSize", "Integer", ParameterLocation.QUERY, false, ""),
                param("pageNum", "Integer", ParameterLocation.QUERY, false, ""),
                param("newParam", "String", ParameterLocation.HEADER, false, "")
        ), saved);

        assertEquals(1, model.getRowCount());
        assertEquals("pageSize", model.getValueAt(0, 0));
        assertEquals("20", model.getValueAt(0, 3));
        assertFalse(saved.containsKey("pageNum"));
        assertFalse(saved.containsKey("newParam"));
    }

    @Test
    void replaceParametersWithSaved_appendsEchoFieldsNotInDefaults() {
        // saved 里回显字段（请求体非预期 JSON 自动生成的 request.appId 之类）
        // 默认参数里没有该 key——按 String/BODY/否 追加
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"old", "String", "BODY", "v", "否", ""});

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("old", "v2");
        saved.put("echoed", "fromResponse");

        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(
                param("old", "String", ParameterLocation.BODY, false, "")
        ), saved);

        assertEquals(2, model.getRowCount());
        assertEquals("old", model.getValueAt(0, 0));
        assertEquals("v2", model.getValueAt(0, 3));
        assertEquals("echoed", model.getValueAt(1, 0));
        assertEquals("String", model.getValueAt(1, 1));
        assertEquals("BODY", model.getValueAt(1, 2));
        assertEquals("fromResponse", model.getValueAt(1, 3));
        assertEquals("否", model.getValueAt(1, 4));
        assertEquals("恢复（保存中新增）", model.getValueAt(1, 5));
    }

    @Test
    void replaceParametersWithSaved_usesPositionFromDefault() {
        // 默认参数是 HEADER → 替换出来的 position 列也应是 HEADER
        DefaultTableModel model = newModel();

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("authToken", "Bearer xyz");

        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(
                param("authToken", "String", ParameterLocation.HEADER, false, "鉴权 token")
        ), saved);

        assertEquals(1, model.getRowCount());
        assertEquals("HEADER", model.getValueAt(0, 2));
    }

    @Test
    void replaceParametersWithSaved_nullOrEmptyIsNoOp() {
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"existing", "String", "QUERY", "v", "否", ""});

        // null model / null saved / empty saved 全部不抛异常、保留 model 原状
        ApiDebuggerPanel.replaceParametersWithSaved(null, List.of(), new LinkedHashMap<>());
        ApiDebuggerPanel.replaceParametersWithSaved(model, null, null);
        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(), new LinkedHashMap<>());

        assertEquals(1, model.getRowCount(), "no-op 路径不动 model");
        assertEquals("existing", model.getValueAt(0, 0));
    }

    @Test
    void replaceParametersWithSaved_preservesRequiredFromDefault() {
        DefaultTableModel model = newModel();

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("userId", "123");

        ApiDebuggerPanel.replaceParametersWithSaved(model, List.of(
                param("userId", "String", ParameterLocation.PATH, true, "")
        ), saved);

        assertEquals("是", model.getValueAt(0, 4), "默认参数里 required=true → 替换后仍是「是」");
    }

    // ---------- saveCurrentParameters 全链路（防止逻辑退化为发送过滤版本） ----------

    @Test
    void collectAllParameterRows_distinguishesFromSendFilter() {
        // saveCurrentParameters 必须收齐所有 location——不能错用 sendRequest 用的过滤方法
        DefaultTableModel model = newModel();
        model.addRow(new Object[]{"pageSize", "Integer", "QUERY", "20", "否", ""});
        model.addRow(new Object[]{"name", "String", "BODY", "tom", "否", ""});

        // filterParamsByMethod (send 用) 只返回 body 侧的 name
        Map<String, String> sending = ApiDebuggerPanel.filterParamsByMethod(model, true, false);
        assertEquals(1, sending.size());

        // collectAllParameterRows (save 用) 返回全量
        Map<String, String> saving = ApiDebuggerPanel.collectAllParameterRows(model, true);
        assertEquals(2, saving.size(), "保存时 pageSize 也必须保留，不能只留 BODY 侧");

        Object sentinel = new Object();
        assertNotNull(sentinel);
    }
}