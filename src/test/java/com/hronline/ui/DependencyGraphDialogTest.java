package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.model.ApiDefinition;
import com.hronline.model.ApiParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphDialogTest {

    @Test
    void sequentialDependenciesFollowFolderOrder() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        ApiDefinition third = api("GET", "/third", "Third");

        List<ApiDependency> dependencies = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second, third));

        assertEquals(2, dependencies.size());
        assertEquals(first.uniqueKey(), dependencies.get(0).getProducerKey());
        assertEquals(second.uniqueKey(), dependencies.get(0).getConsumerKey());
        assertEquals(second.uniqueKey(), dependencies.get(1).getProducerKey());
        assertEquals(third.uniqueKey(), dependencies.get(1).getConsumerKey());
        assertTrue(dependencies.stream().allMatch(d -> "FOLDER_ORDER".equals(d.getDetectionType())));
        assertTrue(dependencies.stream().allMatch(d -> d.getMappings().isEmpty()),
                "顺序依赖初始不预设字段，用户可在页面补充多个映射");
    }

    @Test
    void duplicateApisAreNotLinkedToThemselves() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition same = api("GET", "/first", "Renamed");
        ApiDefinition second = api("GET", "/second", "Second");

        List<ApiDependency> dependencies = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, same, second));

        assertEquals(1, dependencies.size());
        assertEquals(first.uniqueKey(), dependencies.get(0).getProducerKey());
        assertEquals(second.uniqueKey(), dependencies.get(0).getConsumerKey());
    }

    @Test
    void shortApiLabelPrefersNameAndFallsBackToLastPathSegment() {
        ApiDefinition named = api("GET", "/admin/box/blindBoxList", "blindBoxList");
        ApiDefinition unnamed = api("GET", "/admin/box/blindBoxList", "");

        assertEquals("blindBoxList", DependencyGraphDialog.shortApiLabel(named));
        assertEquals("blindBoxList", DependencyGraphDialog.shortApiLabel(unnamed));
        assertEquals("blindBoxList", DependencyGraphDialog.shortDependencyKeyLabel(
                "GET|/admin/box/blindBoxList?tenant=1"));
    }

    @Test
    void duplicateShortNamesRemainReverseResolvableWithoutShowingFullPaths() {
        ApiDefinition first = api("GET", "/admin/one/list", "list");
        ApiDefinition second = api("GET", "/admin/two/list", "list");
        ApiDefinition third = api("POST", "/admin/three/list", "list");

        Map<String, String> labels = DependencyGraphDialog.buildDisplayLabels(
                List.of(first, second, third));

        assertEquals("[GET] list", labels.get(first.uniqueKey()));
        assertEquals("[GET] list (2)", labels.get(second.uniqueKey()));
        assertEquals("[POST] list", labels.get(third.uniqueKey()));
        assertTrue(labels.values().stream().noneMatch(label -> label.contains("/admin/")));
    }

    @Test
    void detectionTypeIsLocalizedForDisplayButStoredValueRemainsStable() {
        assertEquals("文件夹顺序", DependencyGraphDialog.detectionTypeDisplay("FOLDER_ORDER"));
        assertEquals("路径参数匹配", DependencyGraphDialog.detectionTypeDisplay("PATH_MATCH"));
        assertEquals("手动配置", DependencyGraphDialog.detectionTypeDisplay("MANUAL"));
        assertEquals("FOLDER_ORDER", DependencyGraphDialog.detectionTypeValue("文件夹顺序"));
        assertEquals("MANUAL", DependencyGraphDialog.detectionTypeValue("手动配置"));
    }

    @Test
    void fieldCandidatesIncludeNestedResponseAndRequestPaths() {
        ApiParameter data = new ApiParameter();
        data.setName("data");
        ApiParameter id = new ApiParameter();
        id.setName("id");
        ApiParameter profile = new ApiParameter();
        profile.setName("profile");
        ApiParameter nickname = new ApiParameter();
        nickname.setName("nickname");
        profile.setChildren(List.of(nickname));
        data.setChildren(List.of(id, profile));

        ApiDefinition api = api("GET", "/users", "users");
        api.setResponseSchema(List.of(data));
        api.setParameters(List.of(data));

        assertEquals(List.of("data", "data.id", "data.profile", "data.profile.nickname"),
                DependencyGraphDialog.fieldPaths(api, true));
        assertEquals(List.of("data", "data.id", "data.profile", "data.profile.nickname"),
                DependencyGraphDialog.fieldPaths(api, false));
    }

    // ---------- rebuildFromRows（syncFromTable 核心逻辑） ----------

    @Test
    void rebuildFromRows_keepsSequentialEdgesWhenUserEditsNothing() {
        // 用户首次打开依赖设置，看到 createSequentialDependencies 生成的 N-1 条空映射边，
        // 不修改任何字段直接点 OK → 顺序边必须保留下来，不能被 syncFromTable 误清空。
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        ApiDefinition third = api("GET", "/third", "Third");
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second, third));

        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "", "Second", ""},
                new String[]{"Second", "", "Third", ""}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second",
                third.uniqueKey(), "Third"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(2, rebuilt.size(), "未编辑也未删除时，顺序边必须保留");
        assertEquals(first.uniqueKey(), rebuilt.get(0).getProducerKey());
        assertEquals(second.uniqueKey(), rebuilt.get(0).getConsumerKey());
        assertEquals("FOLDER_ORDER", rebuilt.get(0).getDetectionType(), "原始 detectionType 必须保留");
        assertEquals(second.uniqueKey(), rebuilt.get(1).getProducerKey());
        assertEquals(third.uniqueKey(), rebuilt.get(1).getConsumerKey());
    }

    @Test
    void rebuildFromRows_addsMappingWhileKeepingOtherSequentialEdges() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        ApiDefinition third = api("GET", "/third", "Third");
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second, third));

        // 用户只填了第一条边的字段，第二条边的顺序关系应一并保留。
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "Second", "userId"},
                new String[]{"Second", "", "Third", ""}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second",
                third.uniqueKey(), "Third"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(2, rebuilt.size());
        ApiDependency withMapping = rebuilt.stream()
                .filter(d -> first.uniqueKey().equals(d.getProducerKey()))
                .findFirst().orElseThrow();
        assertEquals(1, withMapping.getMappings().size());
        assertEquals("data.id", withMapping.getMappings().get(0).getSourcePath());
        assertEquals("userId", withMapping.getMappings().get(0).getTargetParam());
    }

    @Test
    void rebuildFromRows_allowsMultipleMappingsForSameEdge() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second));

        // 同对接口连续添加多个 mapping
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "Second", "userId"},
                new String[]{"First", "data.token", "Second", "token"}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(1, rebuilt.size());
        assertEquals(2, rebuilt.get(0).getMappings().size());
    }

    @Test
    void rebuildFromRows_droppedRowIsNotResurrected() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        ApiDefinition third = api("GET", "/third", "Third");
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second, third));

        // 用户主动删掉了 Second->Third 这条边（行被删除），只剩一行
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "Second", "userId"}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second",
                third.uniqueKey(), "Third"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(1, rebuilt.size());
        assertEquals(first.uniqueKey(), rebuilt.get(0).getProducerKey());
        assertEquals(second.uniqueKey(), rebuilt.get(0).getConsumerKey());
    }

    @Test
    void rebuildFromRows_dropsHalfFilledRow() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        List<ApiDependency> original = List.of(); // 不带顺序边

        // 用户填了一半（只填了响应字段），视为未完成，不应落盘
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "Second", ""}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertTrue(rebuilt.isEmpty(), "半填字段不应被持久化");
    }

    @Test
    void rebuildFromRows_dropsUnresolvedLabels() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        List<ApiDependency> original = List.of();

        // 第三行 label 在 labelByKey 里找不到（理论上 UI 不会让用户选到，但兜底要丢）
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "Second", "userId"},
                new String[]{"未知接口", "x", "Second", "y"}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(1, rebuilt.size());
        assertEquals(first.uniqueKey(), rebuilt.get(0).getProducerKey());
    }

    @Test
    void rebuildFromRows_skipsSelfLoop() {
        ApiDefinition first = api("GET", "/first", "First");
        List<ApiDependency> original = List.of();

        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "data.id", "First", "userId"}
        );
        Map<String, String> labels = Map.of(first.uniqueKey(), "First");

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertTrue(rebuilt.isEmpty(), "自环边必须被跳过");
    }

    @Test
    void rebuildFromRows_skipsPlaceholderRow() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        List<ApiDependency> original = List.of();

        // 首行是「(无依赖)」占位（fillTable 在没有依赖时插入）
        List<String[]> rows = List.<String[]>of(
                new String[]{"(无依赖)", "", "", ""}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertTrue(rebuilt.isEmpty());
    }

    @Test
    void rebuildFromRows_supportsShortApiLabelsWithMethodPrefix() {
        // 重名短接口走 [GET] name / [POST] name 分桶，验证反向解析仍然命中
        ApiDefinition getList = api("GET", "/admin/one/list", "list");
        ApiDefinition postList = api("POST", "/admin/two/list", "list");
        List<ApiDependency> original = List.of();

        List<String[]> rows = List.<String[]>of(
                new String[]{"[GET] list", "data.id", "[POST] list", "userId"}
        );
        Map<String, String> labels = Map.of(
                getList.uniqueKey(), "[GET] list",
                postList.uniqueKey(), "[POST] list"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(1, rebuilt.size());
        assertEquals(getList.uniqueKey(), rebuilt.get(0).getProducerKey());
        assertEquals(postList.uniqueKey(), rebuilt.get(0).getConsumerKey());
    }

    @Test
    void rebuildFromRows_emptyRowsListClearsAllMappings() {
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        // 即便 original 有完整 mapping，全空表格 = 全部清空（用户主动删除场景）
        ApiDependency edge = new ApiDependency(first.uniqueKey(), second.uniqueKey(), "MANUAL");
        edge.getMappings().add(new ApiDependency.ValueMapping("data.id", "userId"));
        List<ApiDependency> original = List.of(edge);

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(
                original, List.of(), Map.of(
                        first.uniqueKey(), "First",
                        second.uniqueKey(), "Second"
                ));
        assertTrue(rebuilt.isEmpty(),
                "表格被清空时必须丢弃所有原有 mapping，否则用户删除行无法生效");
    }

    @Test
    void rebuildFromRows_emptyRowsListStillKeepsOriginalEdgesWhenUserViewsDefaultRows() {
        // 修复前的 bug：表格里只剩 fillTable 渲染出的原始顺序边（mapping 都空），
        // 用户没改一行点 OK 也要保留；这条 case 也覆盖了「空 rows」的等价分支。
        ApiDefinition first = api("GET", "/first", "First");
        ApiDefinition second = api("POST", "/second", "Second");
        List<ApiDependency> original = DependencyGraphDialog.createSequentialDependencies(
                List.of(first, second));
        List<String[]> rows = List.<String[]>of(
                new String[]{"First", "", "Second", ""}
        );
        Map<String, String> labels = Map.of(
                first.uniqueKey(), "First",
                second.uniqueKey(), "Second"
        );

        List<ApiDependency> rebuilt = DependencyGraphDialog.rebuildFromRows(original, rows, labels);
        assertEquals(1, rebuilt.size());
        assertEquals("FOLDER_ORDER", rebuilt.get(0).getDetectionType());
    }

    private static ApiDefinition api(String method, String path, String name) {
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(method);
        api.setUrl(path);
        api.setName(name);
        return api;
    }
}
