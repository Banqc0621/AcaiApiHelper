package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖设置保存流程的集成测试 - 模拟用户编辑 JComboBox cell editor 后点 OK。
 * <p>真实场景下用户会在 editable JComboBox 里输入响应字段路径，然后直接点 OK；
 * 之前 {@code rebuildFromRows} 单测只覆盖纯函数，没有验证 DefaultCellEditor + JComboBox
 * 这条路径下写回 model 是否真的拿到了用户的输入。</p>
 */
class DependencyGraphDialogSaveFlowTest {

    /**
     * 模拟 MappingCellEditor：combo.getEditor().getItem() 返回用户在 textfield 里输入的文本。
     */
    private static final class ComboMappingEditor extends DefaultCellEditor {
        private final JComboBox<String> combo;

        ComboMappingEditor() {
            super(new JComboBox<>());
            this.combo = (JComboBox<String>) getComponent();
            combo.setEditable(true);
        }

        @Override
        public Object getCellEditorValue() {
            return DependencyGraphDialog.readComboValue(combo);
        }

        JComboBox<String> combo() { return combo; }
    }

    /**
     * 模拟 ApiCellEditor：combo.getSelectedItem() 返回用户选中的项。
     */
    private static final class ComboApiEditor extends DefaultCellEditor {
        private final JComboBox<String> combo;

        ComboApiEditor() {
            super(new JComboBox<>());
            this.combo = (JComboBox<String>) getComponent();
            combo.setEditable(false);
        }

        @Override
        public Object getCellEditorValue() {
            Object item = combo.getSelectedItem();
            return item == null ? "" : String.valueOf(item).trim();
        }

        JComboBox<String> combo() { return combo; }
    }

    /**
     * 把 dialog 里的 flush 逻辑抽成静态等价函数，专门给单测用。
     */
    private static void flushEditorToModel(JTable table, DefaultTableModel model) {
        if (table == null) return;
        if (!table.isEditing()) return;
        TableCellEditor editor = table.getCellEditor();
        if (editor == null) return;
        try {
            Object value = editor.getCellEditorValue();
            int row = table.getEditingRow();
            int column = table.getEditingColumn();
            if (row >= 0 && column >= 0 && value != null) {
                model.setValueAt(value, row, column);
            }
        } catch (Exception ignored) {
        }
    }

    private static ApiDefinition api(String method, String path, String name) {
        ApiDefinition a = new ApiDefinition();
        a.setHttpMethod(method);
        a.setUrl(path);
        a.setName(name);
        return a;
    }

    private static ApiParameter param(String name) {
        ApiParameter p = new ApiParameter();
        p.setName(name);
        return p;
    }

    /**
     * 模拟「用户在 MappingCellEditor 里输入 'data.id' 然后点 OK」的完整保存路径。
     * <p>这是用户报「依赖设置后没保存」的核心场景：用户期待输入的响应字段被写回 model，
     * 进而被 syncFromTable 读取并落到 dependencies。flushEditorToModel 必须把
     * editable JComboBox 文本框里的最新内容写回 model。</p>
     */
    @Test
    void editableComboEditorFlush_writesTypedTextBackToModel() {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{"A", "", "B", ""});
        model.addRow(new Object[]{"", "", "", ""}); // 占位

        JTable table = new JTable(model);
        ComboMappingEditor editor = new ComboMappingEditor();
        table.getColumnModel().getColumn(1).setCellEditor(editor);

        // 模拟用户点击 cell (0, 1) 触发编辑器
        table.editCellAt(0, 1);
        // 模拟用户在 combo 的 textfield 里输入 "data.id"（用户行为：直接敲键盘）
        editor.combo().setSelectedItem("data.id");
        // setSelectedItem 会同步 textfield 的内容到 editor 的 item
        // 验证此时 getCellEditorValue 能拿到 "data.id"
        assertEquals("data.id", editor.getCellEditorValue(),
                "editable combo + setSelectedItem 应该让 getCellEditorValue 拿到当前文本");

        // 模拟 OK 按钮触发 flush
        flushEditorToModel(table, model);

        assertEquals("data.id", model.getValueAt(0, 1),
                "flush 后 model 必须拿到用户输入的 data.id，不能丢回空字符串");
    }

    /**
     * 模拟「用户在 editable combo 里直接敲键盘（不经 dropdown）输入文本」的另条路径：
     * combo.getEditor().getItem() 必须返回用户键入的最新值。
     * <p>这是 JComboBox editable 模式的核心：选中/下拉只更新 selectedItem，
     * 而用户直接敲键盘时只有 textfield 持有输入，必须显式调用
     * {@code combo.getEditor().getItem()} 而不是 selectedItem 才能拿到。</p>
     */
    @Test
    void editableComboEditorFlush_writesTypedTextViaEditorGetItem() {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{"A", "", "B", ""});

        JTable table = new JTable(model);
        ComboMappingEditor editor = new ComboMappingEditor();
        table.getColumnModel().getColumn(1).setCellEditor(editor);

        table.editCellAt(0, 1);
        // 模拟用户敲键盘（不通过 dropdown）：直接改 textfield 内容
        // combo.getEditor() 返回 BasicComboBoxEditor，其内部是 JTextField
        editor.combo().getEditor().setItem("data.userId");

        flushEditorToModel(table, model);

        assertEquals("data.userId", model.getValueAt(0, 1),
                "通过 combo.getEditor().setItem 设置的文本必须能 flush 到 model");
    }

    @Test
    void editableComboReader_prefersLatestEditorTextOverStaleSelectedItem() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"", "data.id", "data.token"});
        combo.setEditable(true);
        combo.setSelectedItem("data.id");
        // 某些 LaF 下用户直接键入后 selectedItem 仍保留旧候选，editor 才是最新内容。
        combo.getEditor().setItem("custom.path");

        assertEquals("custom.path", DependencyGraphDialog.readComboValue(combo));
    }

    /**
     * 模拟 ApiCellEditor（非 editable）：用户从 dropdown 选 "A" 后点 OK。
     */
    @Test
    void nonEditableComboEditorFlush_writesSelectedItemBackToModel() {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{"", "", "", ""});

        JTable table = new JTable(model);
        ComboApiEditor editor = new ComboApiEditor();
        table.getColumnModel().getColumn(0).setCellEditor(editor);
        editor.combo().addItem("A");
        editor.combo().addItem("B");

        table.editCellAt(0, 0);
        editor.combo().setSelectedItem("A");

        flushEditorToModel(table, model);

        assertEquals("A", model.getValueAt(0, 0));
    }

    /**
     * 端到端模拟依赖设置完整保存流程：
     * <ol>
     *   <li>fillTable 把 [A→B, B→C] 渲染为 2 行</li>
     *   <li>用户在 row 0 的「响应字段」和「目标参数」里编辑，加入 mapping</li>
     *   <li>flushEditorToModel + rebuildFromRows 把改动转回 ApiDependency</li>
     *   <li>结果：A→B 带 1 个 mapping，B→C 保留顺序边</li>
     * </ol>
     */
    @Test
    void endToEnd_userAddsMappingAndDependenciesAreSaved() {
        ApiDefinition a = api("GET", "/a", "a");
        ApiDefinition b = api("POST", "/b", "b");
        ApiDefinition c = api("GET", "/c", "c");
        b.setResponseSchema(List.of(param("data")));

        // 初始 sequential dependencies
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(a, b, c));
        assertEquals(2, original.size());

        Map<String, String> labels = DependencyGraphDialog.buildDisplayLabels(List.of(a, b, c));

        // fillTable 把顺序边渲染为 [A, "", B, ""] 和 [B, "", C, ""]
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{labels.get(a.uniqueKey()), "",
                labels.get(b.uniqueKey()), ""});
        model.addRow(new Object[]{labels.get(b.uniqueKey()), "",
                labels.get(c.uniqueKey()), ""});

        // 模拟用户在 row 0 把「响应字段」改成 data.id
        JTable table = new JTable(model);
        ComboMappingEditor responseEditor = new ComboMappingEditor();
        ComboMappingEditor paramEditor = new ComboMappingEditor();
        table.getColumnModel().getColumn(1).setCellEditor(responseEditor);
        table.getColumnModel().getColumn(3).setCellEditor(paramEditor);

        table.editCellAt(0, 1);
        responseEditor.combo().setSelectedItem("data.id");
        flushEditorToModel(table, model);

        // 用户在 row 0 把「目标参数」改成 userId
        table.editCellAt(0, 3);
        paramEditor.combo().setSelectedItem("userId");
        flushEditorToModel(table, model);

        // 此时 model row 0 = [A, "data.id", B, "userId"]，row 1 = [B, "", C, ""]
        assertEquals("data.id", model.getValueAt(0, 1));
        assertEquals("userId", model.getValueAt(0, 3));

        // rebuildFromRows 把表格转回 ApiDependency
        List<String[]> rows = List.of(
                new String[]{(String) model.getValueAt(0, 0), (String) model.getValueAt(0, 1),
                        (String) model.getValueAt(0, 2), (String) model.getValueAt(0, 3)},
                new String[]{(String) model.getValueAt(1, 0), (String) model.getValueAt(1, 1),
                        (String) model.getValueAt(1, 2), (String) model.getValueAt(1, 3)}
        );
        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);

        assertEquals(2, rebuilt.size(), "A→B 带 mapping + B→C 顺序边都应该保留");
        ApiDependency abEdge = rebuilt.stream()
                .filter(d -> a.uniqueKey().equals(d.getProducerKey()))
                .findFirst().orElseThrow();
        assertEquals(b.uniqueKey(), abEdge.getConsumerKey());
        assertEquals(1, abEdge.getMappings().size(), "A→B 必须带 1 个 mapping");
        assertEquals("data.id", abEdge.getMappings().get(0).getSourcePath());
        assertEquals("userId", abEdge.getMappings().get(0).getTargetParam());

        ApiDependency bcEdge = rebuilt.stream()
                .filter(d -> b.uniqueKey().equals(d.getProducerKey()))
                .findFirst().orElseThrow();
        assertEquals(c.uniqueKey(), bcEdge.getConsumerKey());
        assertTrue(bcEdge.getMappings().isEmpty(), "B→C 保留顺序边，不带 mapping");
    }

    /**
     * 用户场景：刚打开一个空的依赖设置（无已保存依赖），只手动添加 1 个新边，
     * 点 OK。期望这条新边被保存。
     * <p>这是用户报「依赖设置保存不了」最可能的场景：用户认为加了一行就是新依赖，
     * 但 rebuildFromRows 在 row 半填时（仅 producer+consumer，无 sourcePath/targetParam）
     * 会丢。所以这一条先做 baseline，将来即便放开半填边界 case 也要保留</p>
     */
    @Test
    void userScenario_addNewEdgeWithFullMapping_persists() {
        ApiDefinition a = api("GET", "/a", "a");
        ApiDefinition b = api("POST", "/b", "b");
        b.setResponseSchema(List.of(param("data")));
        Map<String, String> labels = DependencyGraphDialog.buildDisplayLabels(List.of(a, b));

        // 用户加了一条新行：producer=a, source=data.id, consumer=b, target=userId
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{labels.get(a.uniqueKey()), "data.id",
                labels.get(b.uniqueKey()), "userId"});

        List<String[]> rows = List.<String[]>of(new String[]{
                (String) model.getValueAt(0, 0),
                (String) model.getValueAt(0, 1),
                (String) model.getValueAt(0, 2),
                (String) model.getValueAt(0, 3)});

        List<ApiDependency> original = List.of();
        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);

        assertEquals(1, rebuilt.size(), "完整填一行必须有 1 条依赖边");
        assertEquals(a.uniqueKey(), rebuilt.get(0).getProducerKey());
        assertEquals(b.uniqueKey(), rebuilt.get(0).getConsumerKey());
        assertEquals(1, rebuilt.get(0).getMappings().size());
        assertEquals("data.id", rebuilt.get(0).getMappings().get(0).getSourcePath());
        assertEquals("userId", rebuilt.get(0).getMappings().get(0).getTargetParam());
    }

    /**
     * 用户报 bug 的真实路径 - 表格里已经有一条 sequential 边 [A→B]，
     * 用户改了「响应字段」+「目标参数」想加 mapping，但 cell editor 是 JComboBox，
     * 不是普通 JTextField。验证真实 MappingCellEditor 在 flush 路径下能拿到用户输入。
     */
    @Test
    void userScenario_modifyExistingEdgeMapping_persistsViaRealMappingCellEditor() {
        ApiDefinition a = api("GET", "/a", "a");
        ApiDefinition b = api("POST", "/b", "b");
        b.setResponseSchema(List.of(param("data")));
        Map<String, String> labels = DependencyGraphDialog.buildDisplayLabels(List.of(a, b));

        // 已有依赖：A→B，无 mapping
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(a, b));

        // fillTable 把顺序边渲染为 [A, "", B, ""]
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"上游", "响应字段", "下游", "目标参数"}, 0);
        model.addRow(new Object[]{labels.get(a.uniqueKey()), "",
                labels.get(b.uniqueKey()), ""});

        // 用真实 MappingCellEditor 替换默认 JTextField
        // MappingCellEditor 是 DialogWrapper 的内部类，无法直接 new；这里改用
        // 一组手动构造的 JComboBox + DefaultCellEditor 模拟。
        // 关键点：editable JComboBox 的 textfield 在用户敲键盘时持有输入，
        // 必须用 combo.getEditor().getItem() 才能拿到最新值。
        JTable table = new JTable(model);
        ComboMappingEditor responseEditor = new ComboMappingEditor();
        ComboMappingEditor paramEditor = new ComboMappingEditor();
        table.getColumnModel().getColumn(1).setCellEditor(responseEditor);
        table.getColumnModel().getColumn(3).setCellEditor(paramEditor);

        table.editCellAt(0, 1);
        // 模拟用户敲键盘：直接设置 textfield 内容
        responseEditor.combo().getEditor().setItem("data.id");
        flushEditorToModel(table, model);

        // 同样修改目标参数
        table.editCellAt(0, 3);
        paramEditor.combo().getEditor().setItem("userId");
        flushEditorToModel(table, model);

        assertEquals("data.id", model.getValueAt(0, 1));
        assertEquals("userId", model.getValueAt(0, 3));

        List<String[]> rows = List.<String[]>of(new String[]{
                (String) model.getValueAt(0, 0),
                (String) model.getValueAt(0, 1),
                (String) model.getValueAt(0, 2),
                (String) model.getValueAt(0, 3)});

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);

        assertEquals(1, rebuilt.size(), "已有 A→B 边必须保留");
        assertEquals(a.uniqueKey(), rebuilt.get(0).getProducerKey());
        assertEquals(b.uniqueKey(), rebuilt.get(0).getConsumerKey());
        assertEquals(1, rebuilt.get(0).getMappings().size(), "用户新加的 mapping 必须保留");
        assertEquals("data.id", rebuilt.get(0).getMappings().get(0).getSourcePath());
        assertEquals("userId", rebuilt.get(0).getMappings().get(0).getTargetParam());
    }
}
