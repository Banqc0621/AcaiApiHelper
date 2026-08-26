package com.hronline.scanner;

import com.hronline.model.ApiDefinition;
import com.hronline.model.FolderApiStatus;
import com.hronline.model.StarredFolder;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 收藏文件夹管理服务 —— 收藏 GUI 的数据层。
 *
 * <p>负责文件夹的增删改名、接口在文件夹间的加入/移除/移动、以及每对(文件夹,接口)绑定的
 * 测试参数与测试状态的持久化。所有写操作立即落盘到 {@link RestAutoLabSettingsState}。</p>
 *
 * <p>收藏语义：</p>
 * <ul>
 *   <li>同一接口可存在于多个文件夹（不同文件夹可重复）</li>
 *   <li>同一文件夹内接口唯一（按 {@link ApiDefinition#uniqueKey()} 去重）</li>
 *   <li>「未分类」文件夹与其他文件夹功能等价：可重命名、可删除（v2.0.0 起统一）</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class StarredFolderService {

    private static final Logger LOG = Logger.getInstance(StarredFolderService.class);

    private final Project project;

    public StarredFolderService(Project project) {
        this.project = project;
    }

    public static StarredFolderService getInstance(@NotNull Project project) {
        return project.getService(StarredFolderService.class);
    }

    private RestAutoLabSettingsState settings() {
        return RestAutoLabSettingsState.getInstance(project);
    }

    // ==================== 文件夹 CRUD ====================

    /** 加载全部文件夹（保证「未分类」在首位） */
    public List<StarredFolder> loadFolders() {
        return settings().loadStarredFolders();
    }

    /** 新建文件夹，返回新文件夹 id */
    public String createFolder(String name) {
        List<StarredFolder> folders = loadFolders();
        String finalName = uniqueFolderName(folders, name);
        StarredFolder folder = new StarredFolder(UUID.randomUUID().toString(), finalName);
        folders.add(folder);
        settings().saveStarredFolders(folders);
        return folder.getId();
    }

    /** 重命名文件夹（v2.0.0 起「未分类」也可重命名） */
    public boolean renameFolder(String folderId, String newName) {
        if (newName == null || newName.isBlank()) return false;
        List<StarredFolder> folders = loadFolders();
        for (StarredFolder f : folders) {
            if (f.getId().equals(folderId)) {
                f.setName(uniqueFolderName(folders, newName, folderId));
                settings().saveStarredFolders(folders);
                return true;
            }
        }
        return false;
    }

    /**
     * 删除文件夹（「未分类」与其他文件夹等价，均可删除；不自动重建任何文件夹）。
     * <p>删除文件夹 = 文件夹内接口取消收藏 + 文件夹消失。接口不迁回任何容器。</p>
     */
    public boolean deleteFolder(String folderId) {
        List<StarredFolder> folders = loadFolders();
        StarredFolder target = null;
        for (StarredFolder f : folders) {
            if (f.getId().equals(folderId)) { target = f; break; }
        }
        if (target == null) return false;
        folders.remove(target);
        removeParamsAndStatusForFolder(folderId);
        settings().saveStarredFolders(folders);
        syncStarredSet(folders);
        return true;
    }

    /** 查找已存在的「未分类」文件夹，不创建（删除场景不自动重建） */
    private StarredFolder findUncategorized(List<StarredFolder> folders) {
        for (StarredFolder f : folders) {
            if (StarredFolder.UNCATEGORIZED_ID.equals(f.getId())) return f;
        }
        return null;
    }

    private StarredFolder ensureUncategorized(List<StarredFolder> folders) {
        StarredFolder existing = findUncategorized(folders);
        if (existing != null) return existing;
        StarredFolder uncat = new StarredFolder(StarredFolder.UNCATEGORIZED_ID, StarredFolder.UNCATEGORIZED_NAME);
        folders.add(0, uncat);
        return uncat;
    }

    private String uniqueFolderName(List<StarredFolder> folders, String name) {
        return uniqueFolderName(folders, name, null);
    }

    /** 生成不重名的文件夹名（重名加 (2)、(3) 后缀） */
    private String uniqueFolderName(List<StarredFolder> folders, String name, String excludeId) {
        Set<String> existing = new HashSet<>();
        for (StarredFolder f : folders) {
            if (!f.getId().equals(excludeId)) existing.add(f.getName());
        }
        if (!existing.contains(name)) return name;
        for (int i = 2; ; i++) {
            String candidate = name + " (" + i + ")";
            if (!existing.contains(candidate)) return candidate;
        }
    }

    // ==================== 接口成员管理 ====================

    /** 把接口加入指定文件夹（同文件夹内去重）；返回是否新增成功 */
    public boolean addApiToFolder(String folderId, String apiKey) {
        List<StarredFolder> folders = loadFolders();
        for (StarredFolder f : folders) {
            if (f.getId().equals(folderId)) {
                if (f.getApiKeys().contains(apiKey)) return false;
                f.getApiKeys().add(apiKey);
                settings().saveStarredFolders(folders);
                syncStarredSet(folders);
                return true;
            }
        }
        return false;
    }

    /** 从指定文件夹移除接口 */
    public boolean removeApiFromFolder(String folderId, String apiKey) {
        List<StarredFolder> folders = loadFolders();
        for (StarredFolder f : folders) {
            if (f.getId().equals(folderId)) {
                boolean removed = f.getApiKeys().remove(apiKey);
                if (removed) {
                    removeParamsAndStatus(folderId, apiKey);
                    settings().saveStarredFolders(folders);
                    syncStarredSet(folders);
                }
                return removed;
            }
        }
        return false;
    }

    /** 在文件夹间移动接口：从源文件夹移除，加入目标文件夹（目标去重）。
     *  <p>语义同「先 remove 再 add」，但合并为一次落盘。</p> */
    public boolean moveApi(String apiKey, String fromFolderId, String toFolderId) {
        if (fromFolderId.equals(toFolderId)) return false;
        List<StarredFolder> folders = loadFolders();
        StarredFolder from = null, to = null;
        for (StarredFolder f : folders) {
            if (f.getId().equals(fromFolderId)) from = f;
            if (f.getId().equals(toFolderId)) to = f;
        }
        if (from == null || to == null) return false;
        if (!from.getApiKeys().remove(apiKey)) return false; // 源中不存在
        // 参数/状态随接口迁移到目标文件夹
        Map<String, Map<String, String>> params = migrateParams(fromFolderId, toFolderId, apiKey);
        migrateStatus(fromFolderId, toFolderId, apiKey);
        if (!to.getApiKeys().contains(apiKey)) to.getApiKeys().add(apiKey);
        settings().saveStarredFolders(folders);
        settings().saveFolderApiParams(params);
        syncStarredSet(folders);
        return true;
    }

    /** 把接口加入收藏（默认进「未分类」）；若已在任意文件夹则不重复加 */
    public boolean starApi(String apiKey) {
        List<StarredFolder> folders = loadFolders();
        for (StarredFolder f : folders) {
            if (f.getApiKeys().contains(apiKey)) return false;
        }
        ensureUncategorized(folders).getApiKeys().add(apiKey);
        settings().saveStarredFolders(folders);
        syncStarredSet(folders);
        return true;
    }

    /** 取消收藏：从所有文件夹移除该接口 */
    public boolean unstarApi(String apiKey) {
        List<StarredFolder> folders = loadFolders();
        boolean changed = false;
        for (StarredFolder f : folders) {
            if (f.getApiKeys().remove(apiKey)) changed = true;
        }
        if (changed) {
            removeAllParamsAndStatusForApi(apiKey);
            settings().saveStarredFolders(folders);
            syncStarredSet(folders);
        }
        return changed;
    }

    /** 接口是否已被收藏（存在于任一文件夹） */
    public boolean isStarred(String apiKey) {
        for (StarredFolder f : loadFolders()) {
            if (f.getApiKeys().contains(apiKey)) return true;
        }
        return false;
    }

    // ==================== 测试参数 ====================

    private static String pk(String folderId, String apiKey) {
        return folderId + "\n" + apiKey;
    }

    @Nullable
    public Map<String, String> getParams(String folderId, String apiKey) {
        return settings().loadFolderApiParams().get(pk(folderId, apiKey));
    }

    public void setParams(String folderId, String apiKey, Map<String, String> params) {
        Map<String, Map<String, String>> all = settings().loadFolderApiParams();
        all.put(pk(folderId, apiKey), params);
        settings().saveFolderApiParams(all);
    }

    // ==================== 测试状态 ====================

    @NotNull
    public FolderApiStatus getStatus(String folderId, String apiKey) {
        FolderApiStatus s = settings().loadFolderApiStatus().get(pk(folderId, apiKey));
        return s != null ? s : FolderApiStatus.untested();
    }

    public void setStatus(String folderId, String apiKey, FolderApiStatus status) {
        Map<String, FolderApiStatus> all = settings().loadFolderApiStatus();
        all.put(pk(folderId, apiKey), status);
        settings().saveFolderApiStatus(all);
    }

    /** 手动取消某接口在指定文件夹的警示 */
    public void clearWarning(String folderId, String apiKey) {
        FolderApiStatus s = getStatus(folderId, apiKey);
        s.setManuallyCleared(true);
        setStatus(folderId, apiKey, s);
    }

    // ==================== 内部：参数/状态清理与迁移 ====================

    /** 把「收藏中」的接口集合同步到兼容用的 starredApis 字段，保证主树 ⭐ 指示准确 */
    private void syncStarredSet(List<StarredFolder> folders) {
        Set<String> set = new LinkedHashSet<>();
        for (StarredFolder f : folders) set.addAll(f.getApiKeys());
        RestAutoLabSettingsState s = settings();
        s.getState().starredApis.clear();
        s.getState().starredApis.addAll(set);
    }

    /**
     * #65 修复：把所有收藏文件夹里的 apiKey 按 {@code oldKey → newKey} 改写，
     * 路径变更后旧 key 不会变成孤儿。同时刷新 settings.starredApis 与 syncStarredSet 行为一致。
     * <p>由 {@code ApiScannerService.scanProjectApisAsync} 在扫描完成后调用。</p>
     */
    public boolean remapApiKeys(Map<String, String> remap) {
        if (remap == null || remap.isEmpty()) return false;
        List<StarredFolder> folders = loadFolders();
        boolean changed = false;
        for (StarredFolder f : folders) {
            List<String> keys = f.getApiKeys();
            for (int i = 0; i < keys.size(); i++) {
                String newKey = remap.get(keys.get(i));
                if (newKey != null) {
                    keys.set(i, newKey);
                    changed = true;
                }
            }
        }
        if (changed) {
            settings().saveStarredFolders(folders);
            syncStarredSet(folders);
            // folderApiParams / folderApiStatus 是按 folderId\napiKey 索引的，
            // 由 RestAutoLabSettingsState.remapApiKeys 统一改写顶层 apiKey 段。
        }
        return changed;
    }

    private void removeParamsAndStatus(String folderId, String apiKey) {
        String k = pk(folderId, apiKey);
        Map<String, Map<String, String>> params = settings().loadFolderApiParams();
        if (params.remove(k) != null) settings().saveFolderApiParams(params);
        Map<String, FolderApiStatus> status = settings().loadFolderApiStatus();
        if (status.remove(k) != null) settings().saveFolderApiStatus(status);
    }

    private void removeParamsAndStatusForFolder(String folderId) {
        String prefix = folderId + "\n";
        Map<String, Map<String, String>> params = settings().loadFolderApiParams();
        params.keySet().removeIf(k -> k.startsWith(prefix));
        settings().saveFolderApiParams(params);
        Map<String, FolderApiStatus> status = settings().loadFolderApiStatus();
        status.keySet().removeIf(k -> k.startsWith(prefix));
        settings().saveFolderApiStatus(status);
    }

    private void removeAllParamsAndStatusForApi(String apiKey) {
        String suffix = "\n" + apiKey;
        Map<String, Map<String, String>> params = settings().loadFolderApiParams();
        params.keySet().removeIf(k -> k.endsWith(suffix));
        settings().saveFolderApiParams(params);
        Map<String, FolderApiStatus> status = settings().loadFolderApiStatus();
        status.keySet().removeIf(k -> k.endsWith(suffix));
        settings().saveFolderApiStatus(status);
    }

    private Map<String, Map<String, String>> migrateParams(String fromId, String toId, String apiKey) {
        Map<String, Map<String, String>> all = settings().loadFolderApiParams();
        Map<String, String> p = all.remove(pk(fromId, apiKey));
        if (p != null) all.put(pk(toId, apiKey), p);
        return all;
    }

    private void migrateStatus(String fromId, String toId, String apiKey) {
        Map<String, FolderApiStatus> all = settings().loadFolderApiStatus();
        FolderApiStatus s = all.remove(pk(fromId, apiKey));
        if (s != null) all.put(pk(toId, apiKey), s);
        settings().saveFolderApiStatus(all);
    }
}