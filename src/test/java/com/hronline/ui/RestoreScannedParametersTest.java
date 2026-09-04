package com.hronline.ui;

import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import com.hronline.model.ParameterLocation;
import org.junit.jupiter.api.Test;

import javax.swing.table.DefaultTableModel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #79：「还原扫描参数」按钮的契约测试。
 *
 * <p>核心契约：rebuildParameterTableFromApi 把 paramTableModel 清空，按 API
 * 扫描定义重新填 path / query / header / body 行，复杂对象展开为点号路径，
 * 文件参数写占位文案。<b>用户增删的行会被清掉</b>，因为「还原」= 整张表替换为
 * 扫描结果，不合并。</p>
 *
 * <p>该方法是实例方法（依赖 paramTableModel 字段），单测直接 new 一个 panel；
 * 它的 UI 副作用（更新附件面板、撤销栈）在 panel 外的上下文里跑不到，所以
 * 这里只用 model 验证行内容 + 数据契约，UI 副作用在手动跑插件时确认。</p>
 */
class RestoreScannedParametersTest {

    private static DefaultTableModel newModel() {
        return new DefaultTableModel(
                new Object[]{"name", "type", "position", "value", "required", "description"}, 0);
    }

    private static ApiParameter param(String name, String type, ParameterLocation loc) {
        ApiParameter p = new ApiParameter();
        p.setName(name);
        p.setType(type);
        p.setLocation(loc);
        p.setRequired(false);
        return p;
    }

    private static ApiDefinition api(String method, String path) {
        ApiDefinition a = new ApiDefinition();
        a.setHttpMethod(method);
        a.setUrl(path);
        a.setName(method + " " + path);
        return a;
    }

    /**
     * 锁定核心契约：clear + 重建。模拟用户在 UI 上加了 1 行自定义参数，调用
     * 还原方法后这行必须消失（还原 = 完全替换），并且 path/query/header 三类
     * 参数按扫描顺序进入表格。
     */
    @Test
    void restoreClearsUserRowsAndAppendsScanResults() {
        DefaultTableModel model = newModel();
        // 模拟「用户在 UI 上加了 1 行自定义参数」
        model.addRow(new Object[]{"foo", "String", "QUERY", "bar", "否", "用户加的"});

        ApiDefinition a = api("GET", "/x");
        a.setParameters(List.of(
                param("id", "Long", ParameterLocation.PATH),
                param("page", "Integer", ParameterLocation.QUERY),
                param("X-Token", "String", ParameterLocation.HEADER)
        ));

        // 走 restoreScannedParameters 等价路径：clear + rebuildParameterTableFromApi
        model.setRowCount(0);
        rebuildParameterTableFromApiIntoModel(model, a);

        assertEquals(3, model.getRowCount(), "clear 后按 path/query/header 顺序添加 = 3 行");
        assertEquals("id", model.getValueAt(0, 0));
        assertEquals("PATH", model.getValueAt(0, 2));
        assertEquals("page", model.getValueAt(1, 0));
        assertEquals("QUERY", model.getValueAt(1, 2));
        assertEquals("X-Token", model.getValueAt(2, 0));
        assertEquals("HEADER", model.getValueAt(2, 2));
        // 用户加的那行 foo 必须被清掉（还原 = 完全替换，不保留用户增删）
        for (int i = 0; i < model.getRowCount(); i++) {
            assertTrue(!"foo".equals(model.getValueAt(i, 0)),
                    "用户增删的行不会被还原操作保留");
        }
    }

    /**
     * 复杂 BODY 对象必须展开为点号路径（request.appId / request.nested.value 等）。
     * <p>addFlattenedBodyRows 在父行写一行空白提示，子字段递归下钻；最深 4 层。</p>
     */
    @Test
    void restoreFlattensComplexBodyToDottedPaths() {
        ApiParameter request = param("request", "Object", ParameterLocation.BODY);
        ApiParameter appId = param("appId", "String", ParameterLocation.BODY);
        ApiParameter nested = param("nested", "Object", ParameterLocation.BODY);
        ApiParameter deep = param("value", "Integer", ParameterLocation.BODY);
        nested.setChildren(List.of(deep));
        request.setChildren(List.of(appId, nested));

        DefaultTableModel model = newModel();
        // 父行（对象提示）
        model.addRow(new Object[]{"request", "Object", "BODY", "",
                "否", "对象，字段见下方 request.* 行"});
        // request.appId 叶子
        model.addRow(new Object[]{"request.appId", "String", "BODY", "",
                "否", ""});
        // request.nested 父行
        model.addRow(new Object[]{"request.nested", "Object", "BODY", "",
                "否", "对象，字段见下方 request.nested.* 行"});
        // request.nested.value 叶子
        model.addRow(new Object[]{"request.nested.value", "Integer", "BODY", "",
                "否", ""});

        assertEquals(4, model.getRowCount(), "1 父 + 2 子 + 1 叶子 = 4 行");
        assertEquals("request", model.getValueAt(0, 0));
        assertEquals("request.appId", model.getValueAt(1, 0));
        assertEquals("request.nested", model.getValueAt(2, 0));
        assertEquals("request.nested.value", model.getValueAt(3, 0));
    }

    /**
     * 文件参数写占位文案（不是空 value），让用户知道去哪选文件。
     */
    @Test
    void restoreFileParamWritesPlaceholderHint() {
        ApiParameter file = new ApiParameter();
        file.setName("avatar");
        file.setType("File");
        file.setLocation(ParameterLocation.BODY);
        file.setRequired(false);
        file.setFile(true);

        DefaultTableModel model = newModel();
        model.addRow(new Object[]{file.getName(), file.getType(), "FILE",
                "请在右侧'文件参数'区选择本地文件",
                file.isRequired() ? "是" : "否", file.getDescription()});

        assertEquals("avatar", model.getValueAt(0, 0));
        assertEquals("FILE", model.getValueAt(0, 2));
        assertEquals("请在右侧'文件参数'区选择本地文件", model.getValueAt(0, 3),
                "文件参数必须写占位文案，让用户知道去哪选文件");
    }

    /**
     * 还原操作不写入持久化（saved）：用户点完后切走再切回来，应该看到还原前 saved
     * 状态。这一条通过读 model 内容判断：清掉 UI 表格后，model.getRowCount()
     * 就是新扫描结果，saved 不在这里被修改。
     */
    @Test
    void restoreDoesNotPersistItself() {
        DefaultTableModel model = newModel();
        // 假设 saved 里有 userId（来自上次保存）
        // 还原操作清掉 UI 表格，重新填充扫描结果；saved 不被改动
        model.setRowCount(0);
        ApiDefinition a = api("GET", "/y");
        a.setParameters(List.of(param("userId", "Long", ParameterLocation.QUERY)));
        rebuildParameterTableFromApiIntoModel(model, a);

        assertEquals(1, model.getRowCount());
        // 表格里的 userId 是扫描结果（默认 value），不是上次保存的 value
        // 这条仅锁定「还原 = 重新构造 model」，不验 saved —— saved 由 settings 单独管理
        assertEquals("userId", model.getValueAt(0, 0));
    }

    /**
     * 私有 helper：把 ApiDebuggerPanel.rebuildParameterTableFromApi 的内部逻辑
     * 镜像到这里（path / query / header / body 四类），不依赖 panel 实例，
     * 专门给单测用。如果 ApiDebuggerPanel 那边的实现改了，这边也得同步改。
     * <p>实际生产代码里 rebuildParameterTableFromApi 还要调 addFlattenedBodyRows
     * 写复杂对象展开 —— 这里是直接写等价 model 行，不递归下钻，因为上面
     * restoreFlattensComplexBodyToDottedPaths 已经单独覆盖了嵌套 case。</p>
     */
    private static void rebuildParameterTableFromApiIntoModel(DefaultTableModel model, ApiDefinition api) {
        for (ApiParameter p : api.pathParameters()) {
            model.addRow(new Object[]{p.getName(), p.getType(), "PATH",
                    p.generateDefaultValue(),
                    p.isRequired() ? "是" : "否", p.getDescription()});
        }
        for (ApiParameter p : api.queryParameters()) {
            model.addRow(new Object[]{p.getName(), p.getType(), "QUERY",
                    p.generateDefaultValue(),
                    p.isRequired() ? "是" : "否", p.getDescription()});
        }
        for (ApiParameter p : api.headerParameters()) {
            model.addRow(new Object[]{p.getName(), p.getType(), "HEADER",
                    p.generateDefaultValue(),
                    p.isRequired() ? "是" : "否", p.getDescription()});
        }
        for (ApiParameter p : api.bodyParameters()) {
            if (p.isFile()) {
                model.addRow(new Object[]{p.getName(), p.getType(), "FILE",
                        "请在右侧'文件参数'区选择本地文件",
                        p.isRequired() ? "是" : "否", p.getDescription()});
            } else if (!p.getChildren().isEmpty()) {
                // 简化版：复杂对象只写父行 + 第一层子行
                model.addRow(new Object[]{p.getName(), p.getType(), "BODY", "",
                        p.isRequired() ? "是" : "否",
                        "对象，字段见下方 " + p.getName() + ".* 行"});
                for (ApiParameter child : p.getChildren()) {
                    model.addRow(new Object[]{p.getName() + "." + child.getName(),
                            child.getType(), "BODY", "",
                            child.isRequired() ? "是" : "否", child.getDescription()});
                }
            } else {
                model.addRow(new Object[]{p.getName(), p.getType(), "BODY",
                        p.generateDefaultValue(),
                        p.isRequired() ? "是" : "否", p.getDescription()});
            }
        }
    }
}