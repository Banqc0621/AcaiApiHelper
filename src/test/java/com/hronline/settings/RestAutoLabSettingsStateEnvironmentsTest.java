package com.hronline.settings;

import com.hronline.model.Environment;
import com.hronline.model.FolderApiStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #63 环境列表保存与回显修复的回归测试：
 * 覆盖 loadEnvironments 的激活状态归一化（名称优先 > active 勾选 > dev 回退）、
 * 旧默认值「开发环境」残留纠正，以及保存后重新加载的回显一致性。
 */
class RestAutoLabSettingsStateEnvironmentsTest {

    @Test
    void emptyStateFallsBackToThreeDefaultsAndActivatesDev() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        assertEquals(3, envs.size());
        assertEquals("dev", envs.get(0).getName());
        assertTrue(envs.get(0).isActive());
        assertEquals("dev", state.getActiveEnvironment());
        assertFalse(envs.get(1).isActive());
        assertFalse(envs.get(2).isActive());
    }

    @Test
    void legacyChineseDefaultActiveNameIsCorrectedToDev() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        state.setActiveEnvironment("开发环境");
        List<Environment> envs = state.loadEnvironments();
        assertEquals("dev", state.getActiveEnvironment(), "永不匹配的残留名称必须被纠正");
        assertTrue(envs.get(0).isActive());
    }

    @Test
    void activeFlagFromJsonWinsWhenNameIsStaleAndIsWrittenBack() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        envs.get(1).setActive(true); // test 勾激活
        envs.get(0).setActive(false);
        state.saveEnvironments(envs);
        state.setActiveEnvironment("不存在的名字");

        List<Environment> reloaded = state.loadEnvironments();
        assertEquals("test", reloaded.get(1).getName());
        assertTrue(reloaded.get(1).isActive(), "JSON 的 active 勾选应被尊重");
        assertFalse(reloaded.get(0).isActive());
        assertEquals("test", state.getActiveEnvironment(), "胜出名称必须写回，保证回显一致");
    }

    @Test
    void activeEnvironmentNameTakesPrecedenceOverActiveFlag() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        envs.get(0).setActive(true);
        state.saveEnvironments(envs);
        state.setActiveEnvironment("prod");

        List<Environment> reloaded = state.loadEnvironments();
        assertEquals("prod", reloaded.get(2).getName());
        assertTrue(reloaded.get(2).isActive(), "名称优先级高于勾选标记");
        assertFalse(reloaded.get(0).isActive(), "只能有一个激活环境");
        assertEquals("prod", state.getActiveEnvironment());
    }

    @Test
    void exactlyOneActiveEnvironmentAcrossReloads() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        // 模拟异常数据：多个 active
        envs.get(0).setActive(true);
        envs.get(1).setActive(true);
        envs.get(2).setActive(true);
        state.saveEnvironments(envs);

        List<Environment> reloaded = state.loadEnvironments();
        long activeCount = reloaded.stream().filter(Environment::isActive).count();
        assertEquals(1, activeCount, "归一化后必须只有一个激活环境");
        Environment activeObj = state.getActiveEnvironmentObj();
        assertEquals(state.getActiveEnvironment(), activeObj.getName());
    }

    @Test
    void savedBaseUrlSurvivesReloadAndRoundTrip() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        envs.get(1).setBaseUrl("http://sit.example.com:9090");
        envs.get(1).getVariables().put("token", "sit-token");
        state.saveEnvironments(envs);
        state.setActiveEnvironment("test");

        RestAutoLabSettingsState.State persisted = state.getState();
        RestAutoLabSettingsState restored = new RestAutoLabSettingsState();
        restored.loadState(persisted);

        List<Environment> reloaded = restored.loadEnvironments();
        assertEquals(3, reloaded.size());
        assertEquals("http://sit.example.com:9090", reloaded.get(1).getBaseUrl(), "保存的 baseUrl 必须原样回显");
        assertEquals("sit-token", reloaded.get(1).getVariables().get("token"), "保存的变量必须原样回显");
        assertEquals("test", restored.getActiveEnvironment());
        assertTrue(reloaded.get(1).isActive());
    }

    /**
     * #64 修复：loadEnvironments 不再强制只保留 dev/test/prod。
     * 用户新增的"staging"必须原样保留（之前会被截掉 → 「应用」「新建」看似不生效的根因）。
     */
    @Test
    void userAddedExtraEnvironmentSurvivesReload() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        Environment staging = new Environment("staging", "http://staging.example.com");
        staging.setDescription("预发布");
        envs.add(staging);
        state.saveEnvironments(envs);

        RestAutoLabSettingsState restored = new RestAutoLabSettingsState();
        restored.loadState(state.getState());
        List<Environment> reloaded = restored.loadEnvironments();

        assertEquals(4, reloaded.size(), "用户新增的 staging 必须保留");
        assertTrue(reloaded.stream().anyMatch(e -> "staging".equals(e.getName())),
                "新增 env 名字必须回显");
        assertEquals("http://staging.example.com",
                reloaded.stream().filter(e -> "staging".equals(e.getName())).findFirst().get().getBaseUrl(),
                "新增 env 的 baseUrl 必须原样回显");
    }

    /**
     * #64 修复：用户删除 dev → dev 不再被强行补回（之前固定 3 个会"补"出 dev）。
     */
    @Test
    void deletingDefaultEnvironmentIsRespected() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        // 用户主动删除 dev
        envs.removeIf(e -> "dev".equals(e.getName()));
        envs.get(0).setActive(true); // 激活 test
        state.saveEnvironments(envs);
        state.setActiveEnvironment("test");

        RestAutoLabSettingsState restored = new RestAutoLabSettingsState();
        restored.loadState(state.getState());
        List<Environment> reloaded = restored.loadEnvironments();

        assertEquals(2, reloaded.size(), "用户主动删除的 env 不能再被补回来");
        assertFalse(reloaded.stream().anyMatch(e -> "dev".equals(e.getName())),
                "dev 不应被自动补回");
    }

    /**
     * #64 修复：duplicate 同名 env 只保留第一个（用户编辑过程中可能产生重复）。
     */
    @Test
    void duplicateEnvironmentNamesAreDeduped() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<Environment> envs = state.loadEnvironments();
        Environment dup = new Environment("test", "http://dup.example.com");
        envs.add(dup);
        state.saveEnvironments(envs);

        List<Environment> reloaded = state.loadEnvironments();
        long testCount = reloaded.stream().filter(e -> "test".equals(e.getName())).count();
        assertEquals(1, testCount, "同名 env 必须去重");
        // 保留的是先出现的那条（test 的默认 baseUrl）
        Environment kept = reloaded.stream().filter(e -> "test".equals(e.getName())).findFirst().get();
        assertEquals("http://localhost:8080", kept.getBaseUrl(), "保留首次出现的实例");
    }

    /**
     * #65 修复：remapApiKeys 把 starredApis Set、folderApiParams/Status 等按旧 key 索引的字段
     * 统一改写到新 key，避免接口路径变更后收藏数据丢失。
     */
    @Test
    void remapApiKeysUpdatesAllKeyedFields() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        // 准备 starred set
        state.getState().starredApis.add("GET|/api/v1/old");
        state.getState().starredApis.add("POST|/api/v1/unchanged");
        // 准备 folderApiParams（key = folderId\napiKey）
        Map<String, Map<String, String>> params = new LinkedHashMap<>();
        params.put("folder1\nGET|/api/v1/old", Map.of("p1", "v1"));
        params.put("folder1\nPOST|/api/v1/unchanged", Map.of("p2", "v2"));
        state.saveFolderApiParams(params);
        // 准备 folderApiStatus
        Map<String, FolderApiStatus> status = new LinkedHashMap<>();
        FolderApiStatus s1 = new FolderApiStatus();
        s1.setTestedAt(123L);
        status.put("folder1\nGET|/api/v1/old", s1);
        state.saveFolderApiStatus(status);
        // 准备 apiCallCounts / apiLastCallTimes
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("GET|/api/v1/old", 7);
        state.getState().apiCallCounts.putAll(counts);
        Map<String, Long> times = new LinkedHashMap<>();
        times.put("GET|/api/v1/old", 999L);
        state.getState().apiLastCallTimes.putAll(times);
        // 准备 lastScanApiSignatures
        state.saveLastScanSignatures(List.of("GET|/api/v1/old", "POST|/api/v1/unchanged"));

        // 执行重映射
        Map<String, String> remap = Map.of("GET|/api/v1/old", "GET|/api/v2/new");
        state.remapApiKeys(remap);

        // 1. starredApis Set 应改写
        assertTrue(state.getStarredApis().contains("GET|/api/v2/new"), "starredApis 应改写为新 key");
        assertFalse(state.getStarredApis().contains("GET|/api/v1/old"), "旧 key 必须从 starredApis 移除");
        assertTrue(state.getStarredApis().contains("POST|/api/v1/unchanged"), "未涉及改写的 key 必须保留");

        // 2. folderApiParams 应改写（key 是 folderId\napiKey，只改写 suffix）
        Map<String, Map<String, String>> reloadedParams = state.loadFolderApiParams();
        assertTrue(reloadedParams.containsKey("folder1\nGET|/api/v2/new"), "params 顶层 key 应改写为 folderId\nnewKey");
        assertFalse(reloadedParams.containsKey("folder1\nGET|/api/v1/old"), "旧 params key 必须清理");
        assertEquals("v1", reloadedParams.get("folder1\nGET|/api/v2/new").get("p1"), "params 值必须保留");

        // 3. folderApiStatus
        Map<String, FolderApiStatus> reloadedStatus = state.loadFolderApiStatus();
        assertTrue(reloadedStatus.containsKey("folder1\nGET|/api/v2/new"), "status key 应改写");
        assertEquals(123L, reloadedStatus.get("folder1\nGET|/api/v2/new").getTestedAt(), "status 内容必须保留");

        // 4. apiCallCounts / apiLastCallTimes
        assertEquals(7, state.getApiCallCount("GET|/api/v2/new"), "call count 应迁移到新 key");
        assertEquals(999L, state.getApiLastCallTime("GET|/api/v2/new"), "last called 应迁移到新 key");

        // 5. lastScanApiSignatures
        List<String> sigs = state.getLastScanSignatures();
        assertTrue(sigs.contains("GET|/api/v2/new"), "signatures 应改写");
        assertFalse(sigs.contains("GET|/api/v1/old"), "旧 signature 必须清理");
    }

    @Test
    void remapApiKeysNoOpWhenEmptyOrNull() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        // 空 remap / null remap 必须为 no-op，不抛异常
        assertEquals(0, state.remapApiKeys(null));
        assertEquals(0, state.remapApiKeys(Map.of()));
    }
}
