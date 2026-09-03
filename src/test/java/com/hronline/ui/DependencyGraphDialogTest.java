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

    private static ApiDefinition api(String method, String path, String name) {
        ApiDefinition api = new ApiDefinition();
        api.setHttpMethod(method);
        api.setUrl(path);
        api.setName(name);
        return api;
    }
}
