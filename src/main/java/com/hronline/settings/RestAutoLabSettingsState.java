package com.hronline.settings;

import com.hronline.RestAutoLabConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hronline.model.*;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.*;

/**
 * Project级持久化设置
 */
@State(
        name = "RestAutoLabSettings",
        storages = @Storage("restautolab.xml")
)
public class RestAutoLabSettingsState implements PersistentStateComponent<RestAutoLabSettingsState.State> {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static class State {
        public String baseUrl = RestAutoLabConstants.DEFAULT_BASE_URL;
        /** 自部署模型网关 API Key */
        public String arkApiKey = "";
        /** 自部署模型网关地址 */
        public String arkApiUrl = RestAutoLabConstants.AI_DEFAULT_GATEWAY_URL;
        /** AI API 路径（如 /chat/completions 或 /chat），可配置以适配不同模型网关 */
        public String aiApiPath = RestAutoLabConstants.AI_DEFAULT_API_PATH;
        /** 主模型名称 */
        public String arkModelPro = RestAutoLabConstants.AI_DEFAULT_PRIMARY_MODEL;
        /** 轻量模型名称 */
        public String arkModelCode = RestAutoLabConstants.AI_DEFAULT_SECONDARY_MODEL;
        /** 启用AI功能 */
        public boolean aiEnabled = true;
        public boolean gitCheckEnabled = true;
        /** accent 主题（BLUE / GREEN / HIGH_CONTRAST） */
        public String accentColor = "BLUE";
        public String gitAllowedStatusCodes = RestAutoLabConstants.DEFAULT_ALLOWED_STATUS_CODES;
        public int requestTimeout = RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS;
        public String globalHeadersJson = "{}";
        public boolean autoScanOnStartup = true;
        /** 扫描包过滤（逗号/分号分隔的包前缀，空=扫描全部） */
        public String scanPackageFilter = "";
        public String testReportDir = ".acai/reports";
        public String lastTestProfile = "";
        /** 单次扫描最大AI调用次数 */
        public int maxAiCallsPerScan = 50;
        /** 自定义系统提示词（为空时使用 RestAutoLabConstants.AI_SYSTEM_PROMPT） */
        public String aiSystemPrompt = RestAutoLabConstants.AI_SYSTEM_PROMPT;
        /** 自定义用户提示词模板（为空时使用 RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE） */
        public String aiUserPromptTemplate = RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE;
        /** 环境配置JSON */
        public String environmentsJson = "";
        /** 当前激活环境名（#63：与环境实际名称 dev 对齐，旧默认值「开发环境」永不匹配） */
        public String activeEnvironment = "dev";

        // ========== v3 新增持久化字段 ==========
        /** 收藏的API唯一标识集合 (uniqueKey) —— 兼容旧数据，新逻辑以 starredFoldersJson 为准 */
        public Set<String> starredApis = new HashSet<>();
        /** 收藏文件夹结构 JSON（List<StarredFolder>） */
        public String starredFoldersJson = "";
        /** 文件夹内接口测试参数 JSON（key=folderId\napiKey -> Map<paramName,value>） */
        public String folderApiParamsJson = "";
        /** 文件夹内接口测试状态 JSON（key=folderId\napiKey -> FolderApiStatus） */
        public String folderApiStatusJson = "";
        /** 接口级前置脚本（key=apiKey -> script） */
        public String preRequestScriptsJson = "";
        /** 接口级变量覆盖（key=apiKey -> Map<variable,value>） */
        public String apiVariableOverridesJson = "";
        /** 请求历史JSON */
        public String requestHistoryJson = "";
        /** 保存的测试Profile名称 -> JSON */
        public Map<String, String> savedProfilesJson = new LinkedHashMap<>();
        /** API调用统计 (uniqueKey -> callCount) */
        public Map<String, Integer> apiCallCounts = new LinkedHashMap<>();
        /** API最后调用时间 (uniqueKey -> timestamp) */
        public Map<String, Long> apiLastCallTimes = new LinkedHashMap<>();
        /** 上次扫描的API签名列表 (用于变更检测) */
        public List<String> lastScanApiSignatures = new ArrayList<>();
    }

    private State myState = new State();

    @Nullable
    @Override
    public State getState() { return myState; }

    @Override
    public void loadState(@NotNull State state) { this.myState = state; }

    // ── Convenience accessors ──

    public String getBaseUrl() { return myState.baseUrl; }
    public void setBaseUrl(String v) { myState.baseUrl = v; }

    public String getArkApiKey() { return myState.arkApiKey; }
    public void setArkApiKey(String v) { myState.arkApiKey = v; }

    public String getArkApiUrl() { return myState.arkApiUrl; }
    public void setArkApiUrl(String v) { myState.arkApiUrl = v; }

    public String getArkModelPro() { return myState.arkModelPro; }
    public void setArkModelPro(String v) { myState.arkModelPro = v; }

    public String getArkModelCode() { return myState.arkModelCode; }
    public void setArkModelCode(String v) { myState.arkModelCode = v; }

    public boolean isAiEnabled() { return myState.aiEnabled; }
    public void setAiEnabled(boolean v) { myState.aiEnabled = v; }

    /** accent 主题名，解析见 {@link com.hronline.ui.UiStyle#parseAccent(String)} */
    public String getAccentColor() {
        return (myState.accentColor == null || myState.accentColor.isBlank()) ? "BLUE" : myState.accentColor;
    }
    public void setAccentColor(String v) { myState.accentColor = v; }

    public boolean isGitCheckEnabled() { return myState.gitCheckEnabled; }
    public void setGitCheckEnabled(boolean v) { myState.gitCheckEnabled = v; }

    public String getGitAllowedStatusCodes() { return myState.gitAllowedStatusCodes; }
    public void setGitAllowedStatusCodes(String v) { myState.gitAllowedStatusCodes = v; }

    public int getRequestTimeout() { return myState.requestTimeout; }
    public void setRequestTimeout(int v) { myState.requestTimeout = v; }

    public Set<Integer> getAllowedStatusCodes() {
        Set<Integer> codes = new HashSet<>();
        for (String s : myState.gitAllowedStatusCodes.split(",")) {
            try { codes.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        }
        if (codes.isEmpty()) codes.add(200);
        return codes;
    }

    public int getMaxAiCallsPerScan() { return myState.maxAiCallsPerScan; }
    public void setMaxAiCallsPerScan(int v) { myState.maxAiCallsPerScan = v; }
    public String getEnvironmentsJson() { return myState.environmentsJson; }
    public void setEnvironmentsJson(String v) { myState.environmentsJson = v; }
    public String getActiveEnvironment() { return myState.activeEnvironment; }
    public void setActiveEnvironment(String v) { myState.activeEnvironment = v; }
    public boolean isAutoScanOnStartup() { return myState.autoScanOnStartup; }
    public void setAutoScanOnStartup(boolean v) { myState.autoScanOnStartup = v; }

    public String getScanPackageFilter() { return myState.scanPackageFilter == null ? "" : myState.scanPackageFilter; }
    public void setScanPackageFilter(String v) { myState.scanPackageFilter = v == null ? "" : v; }

    public String getGlobalHeadersJson() { return myState.globalHeadersJson; }
    public void setGlobalHeadersJson(String v) { myState.globalHeadersJson = v; }

    public String getTestReportDir() { return myState.testReportDir; }
    public void setTestReportDir(String v) { myState.testReportDir = v; }

    public String getLastTestProfile() { return myState.lastTestProfile; }
    public void setLastTestProfile(String v) { myState.lastTestProfile = v; }

    // ── AI Service compatibility getters (used by AiParameterService) ──

    /** 自部署模型网关 URL（兼容方法，等价于arkApiUrl） */
    public String getAiServerUrl() { return myState.arkApiUrl; }

    /** AI API Token（兼容方法，等价于arkApiKey） */
    public String getAiToken() { return myState.arkApiKey; }

    /** AI主模型（兼容方法，等价于arkModelPro） */
    public String getAiModel() { return myState.arkModelPro; }

    /** AI API 路径（如 /chat/completions 或 /chat）。
     *  <p>空值/空白时回退到默认 {@link RestAutoLabConstants#AI_DEFAULT_API_PATH}（即 /chat/completions），
     *  避免因持久化字段缺失或被清空导致请求只打到服务器根路径而 404。</p> */
    public String getAiApiPath() {
        if (myState.aiApiPath == null || myState.aiApiPath.isBlank()) {
            return RestAutoLabConstants.AI_DEFAULT_API_PATH;
        }
        return myState.aiApiPath;
    }

    /** 设置自部署模型网关 URL 并同步到arkApiUrl */
    public void setAiServerUrl(String url) { myState.arkApiUrl = url; }

    /** 设置AI Token并同步到arkApiKey */
    public void setAiToken(String token) { myState.arkApiKey = token; }

    /** 设置AI模型并同步到arkModelPro */
    public void setAiModel(String model) { myState.arkModelPro = model; }

    /** 设置AI API 路径 */
    public void setAiApiPath(String path) { myState.aiApiPath = path; }

    /** 自定义系统提示词（空值回退到默认常量） */
    public String getAiSystemPrompt() {
        return (myState.aiSystemPrompt == null || myState.aiSystemPrompt.isBlank())
                ? RestAutoLabConstants.AI_SYSTEM_PROMPT : myState.aiSystemPrompt;
    }
    public void setAiSystemPrompt(String v) { myState.aiSystemPrompt = v; }

    /** 自定义用户提示词模板（空值回退到默认模板） */
    public String getAiUserPromptTemplate() {
        return (myState.aiUserPromptTemplate == null || myState.aiUserPromptTemplate.isBlank())
                ? RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE : myState.aiUserPromptTemplate;
    }
    public void setAiUserPromptTemplate(String v) { myState.aiUserPromptTemplate = v; }

    // ========== v3 新增持久化方法 ==========

    /** 收藏API */
    public void starApi(String uniqueKey) {
        myState.starredApis.add(uniqueKey);
    }

    /** 取消收藏API */
    public void unstarApi(String uniqueKey) {
        myState.starredApis.remove(uniqueKey);
    }

    /** 检查API是否已收藏 */
    public boolean isApiStarred(String uniqueKey) {
        return myState.starredApis.contains(uniqueKey);
    }

    /** 获取所有收藏的API key */
    public Set<String> getStarredApis() {
        return Collections.unmodifiableSet(myState.starredApis);
    }

    // ========== 收藏文件夹（v3）持久化 ==========

    /** 加载收藏文件夹列表；空时返回含「未分类」的初始列表 */
    public List<StarredFolder> loadStarredFolders() {
        if (myState.starredFoldersJson == null || myState.starredFoldersJson.isBlank()) {
            List<StarredFolder> init = new ArrayList<>();
            init.add(new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME));
            return init;
        }
        try {
            Type t = new TypeToken<List<StarredFolder>>(){}.getType();
            List<StarredFolder> folders = gson.fromJson(myState.starredFoldersJson, t);
            if (folders == null || folders.isEmpty()) {
                List<StarredFolder> init = new ArrayList<>();
                init.add(new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME));
                return init;
            }
            // 确保未分类文件夹始终存在且在首位
            boolean hasUncat = false;
            for (StarredFolder f : folders) {
                if (StarredFolder.UNCATEGORIZED_ID.equals(f.getId())) { hasUncat = true; break; }
            }
            if (!hasUncat) {
                folders.add(0, new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME));
            }
            return folders;
        } catch (Exception e) {
            List<StarredFolder> init = new ArrayList<>();
            init.add(new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME));
            return init;
        }
    }

    /** 保存收藏文件夹列表 */
    public void saveStarredFolders(List<StarredFolder> folders) {
        myState.starredFoldersJson = gson.toJson(folders);
    }

    /** 加载文件夹内接口测试参数（key=folderId\napiKey -> paramValues） */
    public Map<String, Map<String, String>> loadFolderApiParams() {
        if (myState.folderApiParamsJson == null || myState.folderApiParamsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Type t = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
            Map<String, Map<String, String>> m = gson.fromJson(myState.folderApiParamsJson, t);
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 保存文件夹内接口测试参数 */
    public void saveFolderApiParams(Map<String, Map<String, String>> params) {
        myState.folderApiParamsJson = gson.toJson(params);
    }

    /** 加载文件夹内接口测试状态（key=folderId\napiKey -> FolderApiStatus） */
    public Map<String, FolderApiStatus> loadFolderApiStatus() {
        if (myState.folderApiStatusJson == null || myState.folderApiStatusJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Type t = new TypeToken<Map<String, FolderApiStatus>>(){}.getType();
            Map<String, FolderApiStatus> m = gson.fromJson(myState.folderApiStatusJson, t);
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 保存文件夹内接口测试状态 */
    public void saveFolderApiStatus(Map<String, FolderApiStatus> status) {
        myState.folderApiStatusJson = gson.toJson(status);
    }

    /** 加载接口级前置脚本。 */
    public Map<String, String> loadPreRequestScripts() {
        if (myState.preRequestScriptsJson == null || myState.preRequestScriptsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Type t = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> value = gson.fromJson(myState.preRequestScriptsJson, t);
            return value != null ? value : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void savePreRequestScript(String apiKey, String script) {
        if (apiKey == null || apiKey.isBlank()) return;
        Map<String, String> scripts = loadPreRequestScripts();
        if (script == null || script.isBlank()) scripts.remove(apiKey);
        else scripts.put(apiKey, script);
        myState.preRequestScriptsJson = gson.toJson(scripts);
    }

    /** 加载接口级变量覆盖。 */
    public Map<String, Map<String, String>> loadApiVariableOverrides() {
        if (myState.apiVariableOverridesJson == null || myState.apiVariableOverridesJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Type t = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
            Map<String, Map<String, String>> value = gson.fromJson(myState.apiVariableOverridesJson, t);
            return value != null ? value : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void saveApiVariableOverrides(String apiKey, Map<String, String> overrides) {
        if (apiKey == null || apiKey.isBlank()) return;
        Map<String, Map<String, String>> all = loadApiVariableOverrides();
        if (overrides == null || overrides.isEmpty()) all.remove(apiKey);
        else all.put(apiKey, new LinkedHashMap<>(overrides));
        myState.apiVariableOverridesJson = gson.toJson(all);
    }

    /** 记录API调用统计 */
    public void recordApiCall(String uniqueKey) {
        myState.apiCallCounts.merge(uniqueKey, 1, Integer::sum);
        myState.apiLastCallTimes.put(uniqueKey, System.currentTimeMillis());
    }

    /** 获取API调用次数 */
    public int getApiCallCount(String uniqueKey) {
        return myState.apiCallCounts.getOrDefault(uniqueKey, 0);
    }

    /** 获取API最后调用时间 */
    public long getApiLastCallTime(String uniqueKey) {
        return myState.apiLastCallTimes.getOrDefault(uniqueKey, 0L);
    }

    /** 保存请求历史 */
    public void saveRequestHistory(List<RequestHistory> history) {
        myState.requestHistoryJson = gson.toJson(history);
    }

    /** 加载请求历史 */
    public List<RequestHistory> loadRequestHistory() {
        if (myState.requestHistoryJson == null || myState.requestHistoryJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Type listType = new TypeToken<List<RequestHistory>>(){}.getType();
            List<RequestHistory> result = gson.fromJson(myState.requestHistoryJson, listType);
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 保存测试Profile */
    public void saveTestProfile(String name, TestProfile profile) {
        myState.savedProfilesJson.put(name, gson.toJson(profile));
    }

    /** 加载测试Profile */
    public TestProfile loadTestProfile(String name) {
        String json = myState.savedProfilesJson.get(name);
        if (json == null || json.isBlank()) return null;
        try {
            return gson.fromJson(json, TestProfile.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取所有已保存Profile名称 */
    public Set<String> getSavedProfileNames() {
        return Collections.unmodifiableSet(myState.savedProfilesJson.keySet());
    }

    /** 删除测试Profile */
    public void deleteTestProfile(String name) {
        myState.savedProfilesJson.remove(name);
    }

    /**
     * 加载环境列表 —— 始终只返回 dev / test / prod 三个默认环境。
     * <p>规则：</p>
     * <ol>
     *   <li>从持久化 json 解析，缺哪个默认环境就补哪个（保留用户已建的，方便回退）</li>
     *   <li>最终只保留 dev / test / prod 三个，多余的丢弃</li>
     *   <li>顺序固定：dev → test → prod</li>
     *   <li><b>归一化激活状态（#63 回显修复）</b>：active 勾选标记与 {@code activeEnvironment}
     *       名称必须收敛为同一个环境。优先以 {@code activeEnvironment} 名称匹配；匹配不到
     *       再信任 JSON 里的 active 勾选；都没有才回退激活 dev。最终把胜出的名称写回
     *       {@code activeEnvironment}，保证「✓ 勾选」「下拉框选中」「实际生效」三者一致。</li>
     *   <li>回写 json 持久化</li>
     * </ol>
     */
    public List<Environment> loadEnvironments() {
        // 1) 从持久化 json 解析
        List<Environment> envs = null;
        if (myState.environmentsJson != null && !myState.environmentsJson.isBlank()) {
            try {
                Type listType = new TypeToken<List<Environment>>(){}.getType();
                envs = gson.fromJson(myState.environmentsJson, listType);
            } catch (Exception ignore) {
                envs = null;
            }
        }
        if (envs == null) envs = new ArrayList<>();

        // 2) 兜底：解析出空列表 / 全部 name 为空 -> 整体替换为 3 个默认
        boolean allBlank = !envs.isEmpty() && envs.stream()
                .allMatch(e -> e.getName() == null || e.getName().isBlank());
        if (envs.isEmpty() || allBlank) {
            envs = new ArrayList<>();
            envs.add(Environment.dev());
            envs.add(Environment.test());
            envs.add(Environment.production());
        }

        // 3) 过滤：只保留 dev / test / prod（按固定顺序），从 envs 中取出（保留持久化里的 baseUrl / 变量 / 头）
        java.util.Map<String, Environment> byName = new java.util.LinkedHashMap<>();
        for (Environment e : envs) {
            if (e.getName() != null) byName.put(e.getName().trim(), e);
        }
        List<Environment> result = new ArrayList<>();
        for (String name : java.util.Arrays.asList("dev", "test", "prod")) {
            Environment e = byName.get(name);
            if (e == null) {
                // 缺哪个补哪个
                if ("dev".equals(name)) e = Environment.dev();
                else if ("test".equals(name)) e = Environment.test();
                else e = Environment.production();
            }
            result.add(e);
        }

        // 4) 归一化激活状态（#63）：activeEnvironment 名称 > JSON active 勾选 > dev。
        //    旧实现的缺陷：active 勾选与 activeEnvironment 名称各自为政，且默认值「开发环境」
        //    永远匹配不上任何环境名，导致「✓ 勾的是 test、下拉/实际生效却是 dev」的回显错乱。
        String activeName = myState.activeEnvironment;
        Environment chosen = null;
        if (activeName != null && !activeName.isBlank()) {
            for (Environment e : result) {
                if (activeName.equals(e.getName())) { chosen = e; break; }
            }
        }
        if (chosen == null) {
            for (Environment e : result) {
                if (e.isActive()) { chosen = e; break; }
            }
        }
        if (chosen == null) chosen = result.get(0); // 固定顺序，result.get(0) == dev
        for (Environment e : result) e.setActive(e == chosen);
        // 把胜出者名称写回，消除「开发环境」这类永不匹配的残留值
        myState.activeEnvironment = chosen.getName();

        myState.environmentsJson = gson.toJson(result);
        return result;
    }

    /** 保存环境列表 */
    public void saveEnvironments(List<Environment> envs) {
        myState.environmentsJson = gson.toJson(envs);
    }

    /** 获取当前激活环境（#63：按名称匹配，防 null 名） */
    public Environment getActiveEnvironmentObj() {
        List<Environment> envs = loadEnvironments();
        String activeName = myState.activeEnvironment;
        for (Environment e : envs) {
            if (e.getName() != null && e.getName().equals(activeName)) {
                return e;
            }
        }
        return envs.isEmpty() ? Environment.dev() : envs.get(0);
    }

    /** 保存上次扫描签名用于变更检测 */
    public void saveLastScanSignatures(List<String> signatures) {
        myState.lastScanApiSignatures = new ArrayList<>(signatures);
    }

    /** 获取上次扫描签名 */
    public List<String> getLastScanSignatures() {
        return myState.lastScanApiSignatures != null ? myState.lastScanApiSignatures : new ArrayList<>();
    }

    public static RestAutoLabSettingsState getInstance(@NotNull Project project) {
        return project.getService(RestAutoLabSettingsState.class);
    }
}
