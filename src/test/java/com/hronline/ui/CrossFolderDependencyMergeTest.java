package com.hronline.ui;

import com.hronline.chain.ApiDependency;
import com.hronline.model.StarredFolder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：跨文件夹多选批量测试时，依赖设置不能被静默丢弃。
 * <p>复现场景：按 Controller 结构收藏后接口分散在多个子文件夹，
 * 用户在父文件夹配置了「上游响应字段 → 下游入参」映射，
 * 多选接口跨文件夹批量测试必须合并「所选文件夹及其祖先」的依赖。</p>
 */
class CrossFolderDependencyMergeTest {

    @Test
    void crossFolderSelectionMergesRootSavedDependencies() {
        // root ── ctrl-a ── api-login（所选）
        //      └─ ctrl-b ── api-orders（所选）
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("root", "根"));
        folders.add(new StarredFolder("ctrl-a", "ControllerA", "root"));
        folders.add(new StarredFolder("ctrl-b", "ControllerB", "root"));

        ApiDependency dep = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        dep.getMappings().add(new ApiDependency.ValueMapping("data.id", "userId"));
        Map<String, List<ApiDependency>> depsByFolder = new LinkedHashMap<>();
        depsByFolder.put("root", new ArrayList<>(List.of(dep)));

        // 修复前：跨文件夹多选 → Collections.emptyList()，上游值永远注入不到下游
        List<ApiDependency> merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("ctrl-a", "ctrl-b"));

        assertEquals(1, merged.size(), "跨文件夹多选必须带上祖先文件夹保存的依赖边");
        assertEquals(1, merged.get(0).getMappings().size());
        assertEquals("data.id", merged.get(0).getMappings().get(0).getSourcePath());
    }

    @Test
    void sameEdgeSavedInDifferentAncestorFoldersMergesMappings() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("root", "根"));
        folders.add(new StarredFolder("ctrl-a", "ControllerA", "root"));
        folders.add(new StarredFolder("ctrl-b", "ControllerB", "root"));

        ApiDependency rootDep = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        rootDep.getMappings().add(new ApiDependency.ValueMapping("data.id", "userId"));
        ApiDependency subDep = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        subDep.getMappings().add(new ApiDependency.ValueMapping("data.token", "token"));

        Map<String, List<ApiDependency>> depsByFolder = new LinkedHashMap<>();
        depsByFolder.put("root", new ArrayList<>(List.of(rootDep)));
        depsByFolder.put("ctrl-b", new ArrayList<>(List.of(subDep)));

        List<ApiDependency> merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("ctrl-a", "ctrl-b"));

        assertEquals(1, merged.size(), "相同 producer→consumer 只保留一条边");
        assertEquals(2, merged.get(0).getMappings().size(), "不同层保存的映射要合并到同一条边");
    }

    @Test
    void unrelatedSiblingFolderDependenciesAreExcluded() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("root", "根"));
        folders.add(new StarredFolder("ctrl-a", "ControllerA", "root"));
        folders.add(new StarredFolder("ctrl-b", "ControllerB", "root"));
        folders.add(new StarredFolder("other", "无关文件夹"));

        ApiDependency related = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        ApiDependency unrelated = new ApiDependency("GET|/x", "POST|/y", "MANUAL");
        Map<String, List<ApiDependency>> depsByFolder = new LinkedHashMap<>();
        depsByFolder.put("root", new ArrayList<>(List.of(related)));
        depsByFolder.put("other", new ArrayList<>(List.of(unrelated)));

        List<ApiDependency> merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("ctrl-a"));

        assertEquals(1, merged.size());
        assertEquals("GET|/login", merged.get(0).getProducerKey());
    }

    @Test
    void deepNestingWalksFullAncestorChain() {
        // level0 ── level1 ── level2 ── ctrl（所选，依赖保存在 level0）
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("level0", "零"));
        folders.add(new StarredFolder("level1", "一", "level0"));
        folders.add(new StarredFolder("level2", "二", "level1"));
        folders.add(new StarredFolder("ctrl", "控制器", "level2"));

        ApiDependency dep = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        Map<String, List<ApiDependency>> depsByFolder = new LinkedHashMap<>();
        depsByFolder.put("level0", new ArrayList<>(List.of(dep)));

        List<ApiDependency> merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("ctrl"));
        assertEquals(1, merged.size(), "祖先链要一路收集到顶");
    }

    @Test
    void orphanAndCycleFolderIdsAreSafe() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("a", "A", "b"));
        folders.add(new StarredFolder("b", "B", "a"));

        ApiDependency dep = new ApiDependency("GET|/login", "POST|/orders", "MANUAL");
        Map<String, List<ApiDependency>> depsByFolder = new LinkedHashMap<>();
        depsByFolder.put("a", new ArrayList<>(List.of(dep)));

        // 环 + 孤儿 id（不在 folders 里）都不能死循环或抛异常
        List<ApiDependency> merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("not-exist"));
        assertTrue(merged.isEmpty(), "孤儿 id 不该带出任何依赖");

        merged = ApiTreePanel.mergeAncestorFolderDependencies(
                folders, depsByFolder, List.of("a", "b"));
        assertEquals(1, merged.size(), "环结构下依赖仍能合并且不重复");
    }
}