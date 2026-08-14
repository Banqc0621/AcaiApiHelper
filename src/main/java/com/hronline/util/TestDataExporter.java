package com.hronline.util;

import com.hronline.RestAutoLabConstants;
import com.hronline.model.ApiDefinition;
import com.hronline.model.Environment;
import com.hronline.model.FolderApiStatus;
import com.hronline.model.RequestHistory;
import com.hronline.model.StarredFolder;
import com.hronline.model.TestProfile;
import com.hronline.scanner.ApiScannerService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 测试配置与接口数据的导入导出工具
 *
 * <p>提供两类数据的跨用户共享能力：</p>
 * <ul>
 *   <li><b>配置信息</b>（{@link #exportTestConfig} / {@link #importTestConfig}）：
 *       导出当前界面的全部配置——AI 模型设置（服务器/Key/模型/提示词）、环境配置、
 *       已保存测试 Profile，用于团队成员复用整套配置。导入时 AI 设置覆盖当前，
 *       环境/Profile 采用合并（本地已有同名则保留本地）。</li>
 *   <li><b>接口数据</b>（{@link #exportTestData} / {@link #importTestData}）：
 *       导出全量接口定义 + 已测接口的测试数据（请求历史）。导入时按接口粒度合并：
 *       本地已测过的接口（已有测试数据）保留本地不覆盖，未测过的接口补入导入数据。</li>
 * </ul>
 *
 * <p>导出文件为 JSON，使用相同插件的用户可直接导入。</p>
 */
public class TestDataExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String FORMAT_TEST_CONFIG = "acai-test-config";
    public static final String FORMAT_TEST_DATA = "acai-test-data";
    public static final String FORMAT_FAVORITES = "restautolab-favorites";
    public static final String EXPORT_VERSION = "1.0";

    private TestDataExporter() {}

    // ================================================================
    // 配置信息（AI 设置 + 环境 + 测试 Profile）
    // ================================================================

    /** 配置信息导出载体：包含当前界面全部配置相关内容 */
    public static class TestConfigExport {
        public String format = FORMAT_TEST_CONFIG;
        public String version = EXPORT_VERSION;
        public long exportedAt = System.currentTimeMillis();
        public String exportedBy = "";
        public AiSettingsDto aiSettings = new AiSettingsDto();
        /** 环境配置列表（开发/测试/生产等，含 baseUrl、全局请求头、变量） */
        public List<Environment> environments = new ArrayList<>();
        /** 当前激活的环境名 */
        public String activeEnvironment = "";
        public Map<String, TestProfile> testProfiles = new LinkedHashMap<>();
    }

    /** AI 相关设置快照（脱壳自 RestAutoLabSettingsState.State）。
     *  <p><b>字段不设默认值</b>：导出时必须由 {@link #fillAiSettings} 显式填充。
     *  这样任何遗漏的字段在 JSON 中会显示为 null，而非伪装成合法的默认占位值，
     *  便于在导出结果中一眼发现“未读到实时配置”的问题。</p> */
    public static class AiSettingsDto {
        public String arkApiUrl = null;
        public String arkApiKey = null;
        public String aiApiPath = null;
        public String arkModelPro = null;
        public Boolean aiEnabled = null;
        public String aiSystemPrompt = null;
        public String aiUserPromptTemplate = null;
        public Integer maxAiCallsPerScan = null;
    }

    /**
     * 用 settings 中当前生效的实时 AI 配置填充 {@link AiSettingsDto}。
     *
     * <p>统一从 getter 读取，确保导出内容与「AI 配置」对话框显示口径一致，
     * 避免任一导出路径遗漏字段导致 JSON 中残留默认占位值。
     * 所有导出方法（exportTestConfig / exportSingleProfile）都必须调用本方法。</p>
     */
    private static void fillAiSettings(AiSettingsDto dto, RestAutoLabSettingsState settings) {
        if (dto == null || settings == null) return;
        dto.arkApiUrl = settings.getAiServerUrl();
        dto.arkApiKey = settings.getAiToken();
        dto.aiApiPath = settings.getAiApiPath();
        dto.arkModelPro = settings.getAiModel();
        dto.aiEnabled = settings.isAiEnabled();
        dto.aiSystemPrompt = settings.getAiSystemPrompt();
        dto.aiUserPromptTemplate = settings.getAiUserPromptTemplate();
        dto.maxAiCallsPerScan = settings.getMaxAiCallsPerScan();
    }

    /**
     * 导出配置信息到文件：AI 设置 + 环境配置 + 已保存测试 Profile。
     *
     * @param settings    项目级设置
     * @param projectName 导出方项目名（用于标识来源）
     * @param outputFile  输出文件绝对路径
     * @return 输出文件路径
     */
    public static String exportTestConfig(RestAutoLabSettingsState settings, String projectName, String outputFile) throws IOException {
        TestConfigExport data = new TestConfigExport();
        data.exportedBy = projectName;

        RestAutoLabSettingsState.State st = settings.getState();
        // 统一通过 fillAiSettings 读取当前实时 AI 配置，确保与「AI 配置」对话框口径一致
        fillAiSettings(data.aiSettings, settings);
        if (st != null) {
            // 环境配置（含 baseUrl、全局请求头、变量、激活状态）
            data.environments = settings.loadEnvironments();
            data.activeEnvironment = settings.getActiveEnvironment();
            collectProfiles(st.savedProfilesJson, data.testProfiles);
        }

        return writeJson(data, outputFile);
    }

    /**
     * 导入配置信息。
     * <p>AI 设置直接覆盖本地（用于采用别人的模型/提示词配置）；
     * 环境配置与测试 Profile 采用合并策略——本地已存在同名则保留本地，否则新增。</p>
     *
     * @param settings  项目级设置
     * @param inputFile 输入文件绝对路径
     * @return 导入结果摘要
     */
    public static String importTestConfig(RestAutoLabSettingsState settings, String inputFile) throws IOException {
        TestConfigExport data = readJson(inputFile, TestConfigExport.class);
        if (data == null) throw new IOException("配置文件格式无效或为空");
        if (!FORMAT_TEST_CONFIG.equals(data.format)) {
            throw new IOException("文件格式不匹配：期望 " + FORMAT_TEST_CONFIG + "，实际 " + data.format);
        }

        RestAutoLabSettingsState.State st = settings.getState();
        int profileAdded = 0;
        int profileSkipped = 0;
        int envAdded = 0;
        int envSkipped = 0;

        if (data.aiSettings != null) {
            AiSettingsDto a = data.aiSettings;
            if (a.arkApiUrl != null) settings.setArkApiUrl(a.arkApiUrl);
            if (a.arkApiKey != null) settings.setArkApiKey(a.arkApiKey);
            if (a.aiApiPath != null) settings.setAiApiPath(a.aiApiPath);
            if (a.arkModelPro != null) settings.setArkModelPro(a.arkModelPro);
            if (a.aiEnabled != null) settings.setAiEnabled(a.aiEnabled);
            if (a.aiSystemPrompt != null && !a.aiSystemPrompt.isBlank()) {
                settings.setAiSystemPrompt(a.aiSystemPrompt);
            }
            if (a.aiUserPromptTemplate != null && !a.aiUserPromptTemplate.isBlank()) {
                settings.setAiUserPromptTemplate(a.aiUserPromptTemplate);
            }
            if (a.maxAiCallsPerScan != null) settings.setMaxAiCallsPerScan(a.maxAiCallsPerScan);
        }

        // 环境合并：本地已有同名环境保留本地，否则新增；激活环境仅在该环境存在时切换
        if (data.environments != null && !data.environments.isEmpty()) {
            List<Environment> localEnvs = settings.loadEnvironments();
            Set<String> localNames = new LinkedHashSet<>();
            for (Environment e : localEnvs) localNames.add(e.getName());
            for (Environment imp : data.environments) {
                if (imp == null || imp.getName() == null) continue;
                if (localNames.contains(imp.getName())) {
                    envSkipped++;
                } else {
                    localEnvs.add(imp);
                    localNames.add(imp.getName());
                    envAdded++;
                }
            }
            settings.saveEnvironments(localEnvs);
            if (data.activeEnvironment != null && !data.activeEnvironment.isBlank() && localNames.contains(data.activeEnvironment)) {
                settings.setActiveEnvironment(data.activeEnvironment);
            }
        }

        if (st != null && data.testProfiles != null) {
            for (Map.Entry<String, TestProfile> e : data.testProfiles.entrySet()) {
                if (st.savedProfilesJson.containsKey(e.getKey())) {
                    profileSkipped++;
                } else {
                    settings.saveTestProfile(e.getKey(), e.getValue());
                    profileAdded++;
                }
            }
        }

        return "配置已导入。新增环境 " + envAdded + " 个（跳过已存在 " + envSkipped + " 个），"
                + "新增测试配置 " + profileAdded + " 个（跳过已存在 " + profileSkipped + " 个），"
                + "AI 设置已覆盖当前。";
    }

    /**
     * 导出单个测试 Profile 到文件（包装为 TestConfigExport 格式，可通过「导入测试配置」导入）。
     *
     * <p><b>已废弃</b>：此重载不导出 AI 配置，aiSettings 字段将全部为 null。
     * 请改用 {@link #exportSingleProfile(TestProfile, RestAutoLabSettingsState, String, String)}。</p>
     *
     * @param profile     要导出的测试配置
     * @param projectName 导出方项目名
     * @param outputFile  输出文件绝对路径
     * @return 输出文件路径
     * @deprecated 改用带 settings 的重载，确保导出实时 AI 配置
     */
    @Deprecated
    public static String exportSingleProfile(TestProfile profile, String projectName, String outputFile) throws IOException {
        TestConfigExport data = new TestConfigExport();
        data.exportedBy = projectName;
        if (profile != null && profile.getName() != null) {
            data.testProfiles.put(profile.getName(), profile);
        }
        return writeJson(data, outputFile);
    }

    /**
     * 导出单个测试 Profile + 当前实时 AI 配置 到文件。
     *
     * <p>通过 {@link #fillAiSettings} 读取 {@code settings} 中当前生效的 AI 配置
     * （服务器地址/Key/模型/提示词等，口径与「AI 配置」对话框一致），
     * 确保导出的不是默认占位值，而是用户当前真实配置。</p>
     *
     * @param profile     要导出的测试配置
     * @param settings    项目级设置（用于读取当前实时 AI 配置）
     * @param projectName 导出方项目名
     * @param outputFile  输出文件绝对路径
     * @return 输出文件路径
     */
    public static String exportSingleProfile(TestProfile profile, RestAutoLabSettingsState settings,
                                             String projectName, String outputFile) throws IOException {
        TestConfigExport data = new TestConfigExport();
        data.exportedBy = projectName;
        if (profile != null && profile.getName() != null) {
            data.testProfiles.put(profile.getName(), profile);
        }
        fillAiSettings(data.aiSettings, settings);
        return writeJson(data, outputFile);
    }

    // ================================================================
    // 收藏列表（文件夹 + 接口成员 + 文件夹级参数/状态）
    // ================================================================

    /** 独立收藏列表交换格式，不携带接口定义、历史记录或 AI 凭据。 */
    public static class FavoritesExport {
        public String format = FORMAT_FAVORITES;
        public String version = EXPORT_VERSION;
        public long exportedAt = System.currentTimeMillis();
        public String exportedBy = "";
        public List<StarredFolder> folders = new ArrayList<>();
        public Map<String, Map<String, String>> folderApiParams = new LinkedHashMap<>();
        public Map<String, FolderApiStatus> folderApiStatus = new LinkedHashMap<>();
    }

    /** 导出独立收藏列表，便于团队共享收藏分组和文件夹级测试数据。 */
    public static String exportFavorites(RestAutoLabSettingsState settings, String projectName,
                                         String outputFile) throws IOException {
        FavoritesExport data = new FavoritesExport();
        data.exportedBy = projectName == null ? "" : projectName;
        data.folders = settings.loadStarredFolders();
        data.folderApiParams = settings.loadFolderApiParams();
        data.folderApiStatus = settings.loadFolderApiStatus();
        return writeJson(data, outputFile);
    }

    /**
     * 导入独立收藏列表。
     * <p>合并原则：本地数据优先；同名文件夹视为同一分组并合并成员；同 ID 同名也合并；
     * 同 ID 异名时为导入文件夹生成新 ID；未分类始终映射到本地未分类。参数和状态随文件夹
     * ID 映射迁移，仅补入本地不存在的键。最后从文件夹成员重建兼容用 starredApis 索引。</p>
     */
    public static String importFavorites(RestAutoLabSettingsState settings, String inputFile) throws IOException {
        FavoritesExport data = readJson(inputFile, FavoritesExport.class);
        if (data == null) throw new IOException("收藏文件格式无效或为空");
        if (!FORMAT_FAVORITES.equals(data.format)) {
            throw new IOException("文件格式不匹配：期望 " + FORMAT_FAVORITES + "，实际 " + data.format);
        }
        if (!EXPORT_VERSION.equals(data.version)) {
            throw new IOException("不支持的收藏文件版本：" + data.version);
        }
        if (data.folders == null) throw new IOException("收藏文件缺少 folders 字段");

        validateFavoriteFolders(data.folders);

        List<StarredFolder> localFolders = settings.loadStarredFolders();
        Map<String, StarredFolder> localById = new LinkedHashMap<>();
        Map<String, StarredFolder> localByName = new LinkedHashMap<>();
        for (StarredFolder folder : localFolders) {
            localById.put(folder.getId(), folder);
            localByName.put(folder.getName(), folder);
        }

        Map<String, String> importedIdMapping = new LinkedHashMap<>();
        int folderAdded = 0;
        int folderMerged = 0;
        int apiAdded = 0;

        for (StarredFolder imported : data.folders) {
            String importedId = imported.getId();
            String importedName = imported.getName().trim();
            StarredFolder target;
            boolean created = false;

            if (StarredFolder.UNCATEGORIZED_ID.equals(importedId)) {
                target = localById.get(StarredFolder.UNCATEGORIZED_ID);
            } else {
                StarredFolder sameId = localById.get(importedId);
                StarredFolder sameName = localByName.get(importedName);
                if (sameId != null && importedName.equals(sameId.getName())) {
                    target = sameId;
                } else if (sameName != null) {
                    target = sameName;
                } else {
                    String targetId = importedId;
                    if (targetId == null || targetId.isBlank() || localById.containsKey(targetId)) {
                        targetId = uniqueFolderId(localById);
                    }
                    target = new StarredFolder(targetId, importedName);
                    localFolders.add(target);
                    localById.put(targetId, target);
                    localByName.put(importedName, target);
                    folderAdded++;
                    created = true;
                }
            }

            if (target == null) {
                target = new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME);
                localFolders.add(0, target);
                localById.put(target.getId(), target);
                localByName.put(target.getName(), target);
                folderAdded++;
                created = true;
            }
            if (!created) {
                folderMerged++;
            }

            importedIdMapping.put(importedId, target.getId());
            for (String apiKey : imported.getApiKeys()) {
                if (apiKey != null && !apiKey.isBlank() && !target.getApiKeys().contains(apiKey)) {
                    target.getApiKeys().add(apiKey);
                    apiAdded++;
                }
            }
        }

        settings.saveStarredFolders(localFolders);

        int paramsAdded = mergeFavoriteMap(settings.loadFolderApiParams(), data.folderApiParams,
                importedIdMapping, settings::saveFolderApiParams);
        int statusAdded = mergeFavoriteMap(settings.loadFolderApiStatus(), data.folderApiStatus,
                importedIdMapping, settings::saveFolderApiStatus);

        RestAutoLabSettingsState.State state = settings.getState();
        if (state != null) {
            state.starredApis.clear();
            for (StarredFolder folder : localFolders) state.starredApis.addAll(folder.getApiKeys());
        }

        return "收藏列表已导入。新增文件夹 " + folderAdded + " 个，合并文件夹 " + folderMerged
                + " 个，新增收藏接口 " + apiAdded + " 个，补入参数 " + paramsAdded
                + " 份、状态 " + statusAdded + " 份。";
    }

    private static void validateFavoriteFolders(List<StarredFolder> folders) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (StarredFolder folder : folders) {
            if (folder == null) throw new IOException("收藏文件包含空文件夹记录");
            if (folder.getId() == null || folder.getId().isBlank()) {
                throw new IOException("收藏文件夹 ID 不能为空");
            }
            if (!ids.add(folder.getId())) {
                throw new IOException("收藏文件包含重复文件夹 ID：" + folder.getId());
            }
            if (folder.getName() == null || folder.getName().isBlank()) {
                throw new IOException("收藏文件夹名称不能为空");
            }
            if (folder.getApiKeys() == null) folder.setApiKeys(new ArrayList<>());
        }
    }

    private static String uniqueFolderId(Map<String, StarredFolder> localById) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (localById.containsKey(id));
        return id;
    }

    private interface FavoriteMapSaver<T> {
        void save(Map<String, T> value);
    }

    private static <T> int mergeFavoriteMap(Map<String, T> local, Map<String, T> imported,
                                            Map<String, String> idMapping,
                                            FavoriteMapSaver<T> saver) {
        if (imported == null || imported.isEmpty()) return 0;
        int added = 0;
        for (Map.Entry<String, T> entry : imported.entrySet()) {
            String mappedKey = remapFavoriteKey(entry.getKey(), idMapping);
            if (mappedKey != null && !local.containsKey(mappedKey)) {
                local.put(mappedKey, entry.getValue());
                added++;
            }
        }
        saver.save(local);
        return added;
    }

    private static String remapFavoriteKey(String key, Map<String, String> idMapping) {
        if (key == null) return null;
        int separator = key.indexOf('\n');
        if (separator <= 0 || separator == key.length() - 1) return null;
        String mappedFolderId = idMapping.get(key.substring(0, separator));
        if (mappedFolderId == null) return null;
        return mappedFolderId + key.substring(separator);
    }

    // ================================================================
    // 接口数据（全量接口定义 + 已测接口的测试数据）
    // ================================================================

    /** 接口数据导出载体：全量接口定义 + 已测接口的测试数据（请求历史/统计/收藏） */
    public static class TestDataExport {
        public String format = FORMAT_TEST_DATA;
        public String version = EXPORT_VERSION;
        public long exportedAt = System.currentTimeMillis();
        public String exportedBy = "";
        /** 全量接口定义（扫描出的全部接口，含参数定义等） */
        public List<ApiDefinition> apis = new ArrayList<>();
        /** 已测接口的请求历史（测试数据） */
        public List<RequestHistory> requestHistory = new ArrayList<>();
        public Map<String, TestProfile> testProfiles = new LinkedHashMap<>();
        public Map<String, Integer> apiCallCounts = new LinkedHashMap<>();
        public Map<String, Long> apiLastCallTimes = new LinkedHashMap<>();
        public Set<String> starredApis = new LinkedHashSet<>();
        /** 收藏文件夹结构（List<StarredFolder>） */
        public List<StarredFolder> starredFolders = new ArrayList<>();
        /** 各文件夹下接口的实时参数（key=folderId\napiKey -> Map<paramName,value>）。
         *  同一接口在不同文件夹中的参数各自独立，导出时全部保留。 */
        public Map<String, Map<String, String>> folderApiParams = new LinkedHashMap<>();
        /** 各文件夹下接口的测试状态（key=folderId\napiKey -> FolderApiStatus） */
        public Map<String, FolderApiStatus> folderApiStatus = new LinkedHashMap<>();
        /** 接口级安全前置脚本（key=apiKey -> script） */
        public Map<String, String> preRequestScripts = new LinkedHashMap<>();
        /** 接口级变量覆盖（key=apiKey -> Map<variable,value>） */
        public Map<String, Map<String, String>> apiVariableOverrides = new LinkedHashMap<>();
    }

    /**
     * 导出接口数据到文件：全量接口定义 + 已测接口的测试数据。
     *
     * @param settings    项目级设置
     * @param apis        全量接口定义（来自扫描器缓存）
     * @param projectName 导出方项目名
     * @param outputFile  输出文件绝对路径
     * @return 输出文件路径
     */
    public static String exportTestData(RestAutoLabSettingsState settings, List<ApiDefinition> apis,
                                        String projectName, String outputFile) throws IOException {
        TestDataExport data = new TestDataExport();
        data.exportedBy = projectName;
        data.apis = apis != null ? new ArrayList<>(apis) : new ArrayList<>();
        data.requestHistory = settings.loadRequestHistory();

        RestAutoLabSettingsState.State st = settings.getState();
        if (st != null) {
            collectProfiles(st.savedProfilesJson, data.testProfiles);
            if (st.apiCallCounts != null) data.apiCallCounts.putAll(st.apiCallCounts);
            if (st.apiLastCallTimes != null) data.apiLastCallTimes.putAll(st.apiLastCallTimes);
            if (st.starredApis != null) data.starredApis.addAll(st.starredApis);
            // 收藏文件夹结构 + 各文件夹下的实时参数/状态：同一接口在不同文件夹中的参数
            // 各自独立归档，导出时全部保留（避免只导出一份导致另一份丢失）
            data.starredFolders = settings.loadStarredFolders();
            data.folderApiParams = settings.loadFolderApiParams();
            data.folderApiStatus = settings.loadFolderApiStatus();
            data.preRequestScripts = settings.loadPreRequestScripts();
            data.apiVariableOverrides = settings.loadApiVariableOverrides();
        }

        return writeJson(data, outputFile);
    }

    /**
     * 导入接口数据，按接口粒度合并：
     * <ul>
     *   <li><b>接口定义</b>：按 uniqueKey(method|url) 判断。本地已有该接口则保留本地不覆盖；
     *       否则新增导入接口定义（含参数定义）。</li>
     *   <li><b>请求历史(测试数据)</b>：按接口 apiKey 判断。本地已测过该接口（已有测试记录）
     *       则保留本地不覆盖；未测过的接口可被覆盖（导入其测试记录）。</li>
     *   <li><b>测试 Profile</b>：本地已存在同名则保留本地；否则新增。</li>
     *   <li><b>调用统计 / 最后调用时间 / 收藏</b>：本地已有的保留本地值，没有的补入导入值。</li>
     * </ul>
     *
     * @param settings  项目级设置
     * @param scanner   接口扫描器（用于注入导入的接口定义）
     * @param inputFile 输入文件绝对路径
     * @return 导入结果摘要
     */
    public static String importTestData(RestAutoLabSettingsState settings, ApiScannerService scanner,
                                        String inputFile) throws IOException {
        TestDataExport data = readJson(inputFile, TestDataExport.class);
        if (data == null) throw new IOException("数据文件格式无效或为空");
        if (!FORMAT_TEST_DATA.equals(data.format)) {
            throw new IOException("文件格式不匹配：期望 " + FORMAT_TEST_DATA + "，实际 " + data.format);
        }

        RestAutoLabSettingsState.State st = settings.getState();
        int apiAdded = 0;
        int apiSkipped = 0;
        int historyAdded = 0;
        int historySkipped = 0;
        int profileAdded = 0;
        int profileSkipped = 0;
        int folderAdded = 0;
        int folderParamsAdded = 0;
        int preRequestConfigAdded = 0;

        // 0) 接口定义合并：本地已存在的接口保留，没有的新增
        if (scanner != null && data.apis != null && !data.apis.isEmpty()) {
            int[] r = scanner.importApis(data.apis);
            apiAdded = r[0];
            apiSkipped = r[1];
        }

        // 1) 请求历史合并：按 apiKey 判断是否已测——已测的接口不覆盖，未测的可覆盖
        List<RequestHistory> localHistory = settings.loadRequestHistory();
        Set<String> localApiKeys = new LinkedHashSet<>();
        for (RequestHistory h : localHistory) {
            String k = historyKey(h);
            if (k != null) localApiKeys.add(k);
        }

        // 导入数据中同一接口的多条记录取最新一条
        Map<String, RequestHistory> importedLatest = new LinkedHashMap<>();
        if (data.requestHistory != null) {
            for (RequestHistory h : data.requestHistory) {
                String key = historyKey(h);
                if (key == null) continue;
                RequestHistory exist = importedLatest.get(key);
                if (exist == null || h.getTimestamp() > exist.getTimestamp()) {
                    importedLatest.put(key, h);
                }
            }
        }
        for (Map.Entry<String, RequestHistory> e : importedLatest.entrySet()) {
            if (localApiKeys.contains(e.getKey())) {
                historySkipped++;
            } else {
                localHistory.add(0, e.getValue());
                localApiKeys.add(e.getKey());
                historyAdded++;
            }
        }
        while (localHistory.size() > RestAutoLabConstants.MAX_HISTORY_SIZE) {
            localHistory.remove(localHistory.size() - 1);
        }
        settings.saveRequestHistory(localHistory);

        if (st != null) {
            // 2) Profile 合并
            if (data.testProfiles != null) {
                for (Map.Entry<String, TestProfile> e : data.testProfiles.entrySet()) {
                    if (st.savedProfilesJson.containsKey(e.getKey())) {
                        profileSkipped++;
                    } else {
                        settings.saveTestProfile(e.getKey(), e.getValue());
                        profileAdded++;
                    }
                }
            }
            // 3) 调用统计合并（仅补入本地没有的）
            if (data.apiCallCounts != null) {
                for (Map.Entry<String, Integer> e : data.apiCallCounts.entrySet()) {
                    st.apiCallCounts.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            if (data.apiLastCallTimes != null) {
                for (Map.Entry<String, Long> e : data.apiLastCallTimes.entrySet()) {
                    st.apiLastCallTimes.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            // 4) 收藏合并
            if (data.starredApis != null) {
                st.starredApis.addAll(data.starredApis);
            }
            // 5) 收藏文件夹 + 各文件夹下的实时参数/状态合并：
            //    文件夹按 id 合并（本地已有同 id 则并入其接口，否则新增文件夹）；
            //    实时参数/状态按 folderId\napiKey 合并，本地已有的键保留本地，没有的补入——
            //    保证同一接口在不同文件夹中的参数互不覆盖、各自归档。
            if (data.starredFolders != null && !data.starredFolders.isEmpty()) {
                List<StarredFolder> localFolders = settings.loadStarredFolders();
                Map<String, StarredFolder> byId = new LinkedHashMap<>();
                for (StarredFolder f : localFolders) byId.put(f.getId(), f);
                for (StarredFolder imp : data.starredFolders) {
                    StarredFolder exist = imp.getId() != null ? byId.get(imp.getId()) : null;
                    if (exist != null) {
                        for (String k : imp.getApiKeys()) {
                            if (!exist.getApiKeys().contains(k)) exist.getApiKeys().add(k);
                        }
                    } else {
                        byId.put(imp.getId(), imp);
                        folderAdded++;
                    }
                }
                settings.saveStarredFolders(new ArrayList<>(byId.values()));
            }
            Map<String, Map<String, String>> localParams = settings.loadFolderApiParams();
            if (data.folderApiParams != null) {
                for (Map.Entry<String, Map<String, String>> e : data.folderApiParams.entrySet()) {
                    if (!localParams.containsKey(e.getKey())) {
                        localParams.put(e.getKey(), e.getValue());
                        folderParamsAdded++;
                    }
                }
                settings.saveFolderApiParams(localParams);
            }
            Map<String, FolderApiStatus> localStatus = settings.loadFolderApiStatus();
            if (data.folderApiStatus != null) {
                for (Map.Entry<String, FolderApiStatus> e : data.folderApiStatus.entrySet()) {
                    localStatus.putIfAbsent(e.getKey(), e.getValue());
                }
                settings.saveFolderApiStatus(localStatus);
            }
            // 6) 接口级前置配置本地优先合并
            Map<String, String> localScripts = settings.loadPreRequestScripts();
            if (data.preRequestScripts != null) {
                for (Map.Entry<String, String> e : data.preRequestScripts.entrySet()) {
                    if (!localScripts.containsKey(e.getKey())) {
                        settings.savePreRequestScript(e.getKey(), e.getValue());
                        localScripts.put(e.getKey(), e.getValue());
                        preRequestConfigAdded++;
                    }
                }
            }
            Map<String, Map<String, String>> localOverrides = settings.loadApiVariableOverrides();
            if (data.apiVariableOverrides != null) {
                for (Map.Entry<String, Map<String, String>> e : data.apiVariableOverrides.entrySet()) {
                    if (!localOverrides.containsKey(e.getKey())) {
                        settings.saveApiVariableOverrides(e.getKey(), e.getValue());
                        localOverrides.put(e.getKey(), e.getValue());
                        preRequestConfigAdded++;
                    }
                }
            }
        }

        return "接口数据已导入。新增接口 " + apiAdded + " 个（跳过已存在 " + apiSkipped + " 个），"
                + "新增测试数据 " + historyAdded + " 条（已测接口保留本地 " + historySkipped + " 条），"
                + "新增测试配置 " + profileAdded + " 个（跳过已存在 " + profileSkipped + " 个），"
                + "新增收藏文件夹 " + folderAdded + " 个，补入实时参数 " + folderParamsAdded
                + " 份、接口前置配置 " + preRequestConfigAdded + " 份。";
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 从 savedProfilesJson（name -> profile JSON）收集为 TestProfile 映射 */
    private static void collectProfiles(Map<String, String> savedProfilesJson, Map<String, TestProfile> out) {
        if (savedProfilesJson == null) return;
        for (Map.Entry<String, String> e : savedProfilesJson.entrySet()) {
            try {
                TestProfile p = GSON.fromJson(e.getValue(), TestProfile.class);
                if (p != null) out.put(e.getKey(), p);
            } catch (Exception ignored) {
                // 单个 Profile 解析失败不影响整体导出
            }
        }
    }

    /** 计算历史记录的接口稳定键，回退到 method|url */
    private static String historyKey(RequestHistory h) {
        if (h == null) return null;
        if (h.getApiKey() != null && !h.getApiKey().isBlank()) return h.getApiKey();
        String method = h.getMethod() == null ? "" : h.getMethod().toUpperCase();
        String url = h.getUrl() == null ? "" : h.getUrl();
        if (method.isEmpty() && url.isEmpty()) return null;
        return method + "|" + url;
    }

    private static String writeJson(Object data, String outputFile) throws IOException {
        String json = GSON.toJson(data);
        Path path = Paths.get(outputFile);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return outputFile;
    }

    private static <T> T readJson(String inputFile, Class<T> clazz) throws IOException {
        try (Reader reader = Files.newBufferedReader(Paths.get(inputFile), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, clazz);
        }
    }

    /** 生成带时间戳的默认文件名 */
    public static String suggestFileName(String prefix, String ext) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
        return prefix + "-" + sdf.format(new Date()) + "." + ext;
    }

    /** 选择导出文件保存目录，返回完整保存路径。
     *  <p>使用 {@link FileChooser#chooseFile}（与导入功能完全相同的机制），跨平台一致地
     *  弹出目录选择框，由用户指定保存位置。不使用 {@code FileSaverDescriptor}/
     *  {@code createSaveFileDialog}：后者在 Windows 上与 IntelliJ 模态对话框栈存在
     *  兼容性问题，原生保存对话框经常无法弹出（点击无反应）。导入用的是 chooseFile
     *  能正常弹出，导出也用 chooseFile 即可保持一致。</p>
     *
     *  @param project     当前项目
     *  @param suggestName 建议的文件名（已含时间戳与扩展名），拼到所选目录后
     *  @return 完整保存路径；用户取消返回 null */
    public static String chooseExportPath(Project project, String suggestName) {
        FileChooserDescriptor fd = new FileChooserDescriptor(false, true, false, false, false, false);
        fd.setTitle("选择保存目录");
        fd.setDescription("选择文件保存的目录，文件名：" + suggestName);
        VirtualFile selected = FileChooser.chooseFile(fd, project, null);
        if (selected == null) return null;
        return selected.getPath() + "/" + suggestName;
    }
}
