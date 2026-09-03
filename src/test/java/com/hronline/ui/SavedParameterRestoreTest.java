package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.DefaultCellEditor;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 回归测试：「回显 → 保存 → 切走再切回来」链路里，恢复阶段必须把保存的数据带回来。
 * <p>关键修复点：之前 {@code applySavedParameterValues} 只按 name 匹配默认参数行
 * 覆盖 value 列，找不到的 key 直接丢弃；回显新增的字段因此在恢复后凭空消失，
 * 看上去像「保存没生效」。修复后新增字段会作为一行追加到表尾。</p>
 */
class SavedParameterRestoreTest {

    private static DefaultTableModel newModel() {
        // 列：name / type / position / value / required / description
        return new DefaultTableModel(
                new Object[]{"name", "type", "position", "value", "required", "description"}, 0);
    }

    private static void addRow(DefaultTableModel m, String name, String type, String position,
                               String value, String required, String desc) {
        m.addRow(new Object[]{name, type, position, value, required, desc});
    }

    private static String nameOf(DefaultTableModel m, int row) {
        return (String) m.getValueAt(row, 0);
    }

    private static String valueOf(DefaultTableModel m, int row) {
        return (String) m.getValueAt(row, 3);
    }

    private static String descOf(DefaultTableModel m, int row) {
        return (String) m.getValueAt(row, 5);
    }

    @Test
    void overwrite_existingRowWhenNameMatches() {
        DefaultTableModel model = newModel();
        addRow(model, "pageSize", "Integer", "QUERY", "10", "否", "页大小");

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("pageSize", "20");

        ApiDebuggerPanel.mergeSavedParameterValues(model, saved);

        assertEquals(1, model.getRowCount());
        assertEquals("20", valueOf(model, 0));
        assertEquals("页大小", descOf(model, 0));
    }

    @Test
    void appendMissingRow_whenSavedHasKeyButDefaultParamsLackIt() {
        // 模拟「回显新增的字段保存后再切回来」：默认参数里没有该 key
        DefaultTableModel model = newModel();
        addRow(model, "request.appId", "String", "BODY", "", "否", "应用ID");

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("foo", "bar");

        ApiDebuggerPanel.mergeSavedParameterValues(model, saved);

        assertEquals(2, model.getRowCount());
        // 已有行不动
        assertEquals("request.appId", nameOf(model, 0));
        assertEquals("", valueOf(model, 0));
        // 缺失行被追加（不是被丢弃）
        assertEquals("foo", nameOf(model, 1));
        assertEquals("bar", valueOf(model, 1));
        assertEquals("String", model.getValueAt(1, 1));
        assertEquals("BODY", model.getValueAt(1, 2));
        assertEquals("否", model.getValueAt(1, 4));
        assertEquals("恢复（保存中新增）", descOf(model, 1));
    }

    @Test
    void mixOfOverwriteAndAppend() {
        DefaultTableModel model = newModel();
        addRow(model, "request.appId", "String", "BODY", "", "否", "");
        addRow(model, "request.appName", "String", "BODY", "", "否", "");

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("request.appId", "a1");     // 匹配覆盖
        saved.put("foo", "bar");              // 缺失追加

        ApiDebuggerPanel.mergeSavedParameterValues(model, saved);

        assertEquals(3, model.getRowCount());
        assertEquals("request.appId", nameOf(model, 0));
        assertEquals("a1", valueOf(model, 0));
        assertEquals("request.appName", nameOf(model, 1));
        assertEquals("", valueOf(model, 1));   // 没保存，不动
        assertEquals("foo", nameOf(model, 2));
        assertEquals("bar", valueOf(model, 2));
    }

    @Test
    void emptySaved_isNoOp() {
        DefaultTableModel model = newModel();
        addRow(model, "x", "String", "QUERY", "1", "否", "");

        ApiDebuggerPanel.mergeSavedParameterValues(model, null);
        ApiDebuggerPanel.mergeSavedParameterValues(model, new LinkedHashMap<>());

        assertEquals(1, model.getRowCount());
        assertEquals("1", valueOf(model, 0));
    }

    @Test
    void duplicateNameInModel_lastRowWinsAndNoDuplicateAppend() {
        // 同名参数理论上不会出现；当前实现取最后一个匹配行做覆盖，不追加新行
        DefaultTableModel model = newModel();
        addRow(model, "id", "String", "QUERY", "oldA", "否", "first");
        addRow(model, "id", "String", "BODY", "oldB", "否", "second");

        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("id", "new");

        ApiDebuggerPanel.mergeSavedParameterValues(model, saved);

        // 没有重复追加到 3 行
        assertEquals(2, model.getRowCount());
        // 最后一个同名行被覆盖
        assertEquals("new", valueOf(model, 1));
    }

    /**
     * 端到端：模拟回显 → 保存 → loadApi 恢复的完整链路。
     * <p>默认参数 + 回显后保存的数据 → 重新加载接口时，回显字段必须带回来。</p>
     */
    @Test
    void endToEnd_echoThenSaveThenRestore_preservesEchoedFields() {
        DefaultTableModel defaultModel = newModel();
        addRow(defaultModel, "request.appId", "String", "BODY", "", "否", "");
        addRow(defaultModel, "request.appName", "String", "BODY", "", "否", "");

        // 用户在请求体填了非预期 JSON，点回显：表格清空 + 写入 JSON 字段
        DefaultTableModel echoModel = newModel();
        addRow(echoModel, "foo", "String", "BODY", "bar", "否", "回显");

        // 保存：把当前可见的全部 name → value 写入 settings
        Map<String, String> saved = new LinkedHashMap<>();
        for (int i = 0; i < echoModel.getRowCount(); i++) {
            saved.put(nameOf(echoModel, i), valueOf(echoModel, i));
        }

        // 切走再切回来：loadApi 重新加载默认参数 + 合并 saved
        ApiDebuggerPanel.mergeSavedParameterValues(defaultModel, saved);

        assertEquals(3, defaultModel.getRowCount());
        // 默认参数保留
        assertEquals("request.appId", nameOf(defaultModel, 0));
        assertEquals("request.appName", nameOf(defaultModel, 1));
        // 回显字段以新行追加，不是被覆盖丢失
        assertEquals("foo", nameOf(defaultModel, 2));
        assertEquals("bar", valueOf(defaultModel, 2));
    }

    // ---------- forceCommitCellEditor（保存按钮的 cellEditor flush 防御） ----------

    @Test
    void forceCommitCellEditor_writesEditorValueBackToModel() {
        // 模拟用户在 cellEditor（JTextField）里编辑了某行，但没按 Enter 就直接点保存按钮。
        // DefaultCellEditor.editingStopped 不会触发，model 里仍是旧值。
        // forceCommitCellEditor 必须主动把编辑器的当前值写回 model。
        JTextField field = new JTextField("oldValue");
        DefaultCellEditor editor = new DefaultCellEditor(field);
        field.setText("newValue"); // 用户在 cellEditor 里改了 text

        DefaultTableModel model = newModel();
        addRow(model, "pageSize", "Integer", "QUERY", "oldValue", "否", "");

        ApiDebuggerPanel.forceCommitCellEditor(editor, 0, 3, model);

        // 模型拿到了最新值（不是 oldValue）
        assertEquals("newValue", valueOf(model, 0));
        // 编辑器当前值也确认是 newValue（说明确实是写入 editor 当前值而非 model 旧值）
        assertEquals("newValue", field.getText());
    }

    @Test
    void forceCommitCellEditor_nullEditorIsNoOp() {
        // null 兜底：依赖 dialog 被 dispose 时偶发调用，不抛异常
        DefaultTableModel model = newModel();
        addRow(model, "x", "String", "QUERY", "1", "否", "");
        ApiDebuggerPanel.forceCommitCellEditor(null, 0, 3, model);
        assertEquals("1", valueOf(model, 0));
    }

    @Test
    void forceCommitCellEditor_invalidCoordinatesIsNoOp() {
        // row/column 为负时不能写回 model（防止 stopCellEditing 路径里 editingRow=-1 误写）
        JTextField field = new JTextField("value");
        DefaultCellEditor editor = new DefaultCellEditor(field);
        DefaultTableModel model = newModel();
        addRow(model, "x", "String", "QUERY", "1", "否", "");

        ApiDebuggerPanel.forceCommitCellEditor(editor, -1, 3, model);
        ApiDebuggerPanel.forceCommitCellEditor(editor, 0, -1, model);

        // 行没被改
        assertEquals("1", valueOf(model, 0));
    }

    @Test
    void flushTableCellEditor_nullTableIsNoOp() {
        // JTable 实例入口：null 安全
        ApiDebuggerPanel.flushTableCellEditor(null);
        // 不抛异常即可 — 走完一行即视为通过
        Object sentinel = new Object();
        assertNotNull(sentinel);
    }
}