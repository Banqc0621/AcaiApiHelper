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

    /** 新建顶层文件夹，返回新文件夹 id */
    public String createFolder(String name) {
        return createFolder(name, null);
    }

    /** 新建文件夹（parentId 为 null/空 = 顶层），返回新文件夹 id */
    public String createFolder(String name, String parentId) {
        List<StarredFolder> folders = loadFolders();
        String finalParent = normalizeParentId(folders, parentId);
        String finalName = uniqueFolderName(folders, name);
        StarredFolder folder = new StarredFolder(UUID.randomUUID().toString(), finalName, finalParent);
        folders.add(folder);
        settings().saveStarredFolders(folders);
        return folder.getId();
    }

    /** parentId 指向的文件夹不存在时视为顶层（防止脏数据产生孤儿节点） */
    private String normalizeParentId(List<StarredFolder> folders, String parentId) {
        if (parentId == null || parentId.isBlank()) return null;
        for (StarredFolder f : folders) {
            if (parentId.equals(f.getId())) return parentId;
        }
        return null;
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
     * <p>多级目录：删除会递归移除其全部后代文件夹。</p>
     * <p>删除文件夹 = 文件夹（含后代）内接口取消收藏 + 文件夹消失。接口不迁回任何容器。</p>
     */
    public boolean deleteFolder(String folderId) {
        List<StarredFolder> folders = loadFolders();
        StarredFolder target = null;
        for (StarredFolder f : folders) {
            if (f.getId().equals(folderId)) { target = f; break; }
        }
        if (target == null) return false;
        // 收集目标 + 全部后代，一并删除并清理各自的参数/状态
        List<String> subtreeIds = new ArrayList<>(collectSubtreeIds(folders, folderId));
        folders.removeIf(f -> subtreeIds.contains(f.getId()));
        for (String id : subtreeIds) removeParamsAndStatusForFolder(id);
        settings().saveStarredFolders(folders);
        syncStarredSet(folders);
        return true;
    }

    /** 收集 folderId 自身 + 全部后代的 id（含 folderId 本身，按发现顺序）。
     *  纯函数，便于单元测试与复用。 */
    public static List<String> collectSubtreeIds(List<StarredFolder> folders, String folderId) {
        List<String> result = new ArrayList<>();
        result.add(folderId);
        for (int i = 0; i < result.size(); i++) {
            String cur = result.get(i);
            for (StarredFolder f : folders) {
                if (cur.equals(f.getParentId()) && !result.contains(f.getId())) {
                    result.add(f.getId());
                }
            }
        }
        return result;
    }

    /**
     * 收集文件夹（含全部后代）内的全部接口 uniqueKey。
     * <p>用于文件夹级批量操作（批量测试 / AI 生成参数 / 依赖链测试 / 导出）递归覆盖子目录。</p>
     */
    public List<String> collectSubtreeApiKeys(String folderId) {
        List<StarredFolder> folders = loadFolders();
        List<String> keys = new ArrayList<>();
        Set<String> subtree = new HashSet<>(collectSubtreeIds(folders, folderId));
        for (StarredFolder f : folders) {
            if (subtree.contains(f.getId())) keys.addAll(f.getApiKeys());
        }
        return keys;
    }

    /**
     * 收集文件夹（含全部后代）的 {@link StarredFolder} 列表（含自身）。
     * <p>批量操作需要逐个文件夹处理，因为测试参数按 (文件夹, 接口) 维度持久化。</p>
     */
    public List<StarredFolder> collectSubtreeFolders(String folderId) {
        List<StarredFolder> folders = loadFolders();
        Set<String> subtree = new LinkedHashSet<>(collectSubtreeIds(folders, folderId));
        List<StarredFolder> result = new ArrayList<>();
        for (StarredFolder f : folders) {
            if (subtree.contains(f.getId())) result.add(f);
        }
        return result;
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

    // ==================== 文件夹层级移动 ====================

    /**
     * 把文件夹拖到目标位置（拖拽实现）。
     *
     * @param folderId    要移动的文件夹 id
     * @param newParentId 新的父 id；{@code null}/空 = 顶层
     * @param anchorId    同级锚点 id；{@code null}/空 = 作为该父下第一项（无锚点）
     * @param position    相对锚点的位置："before" / "after" / "child"（child 表示作为锚点的子级）
     * @return 是否真的发生了移动
     */
    public boolean moveFolder(String folderId, String newParentId, String anchorId, String position) {
        if (folderId == null || folderId.isBlank() || position == null) return false;
        if (!List.of("before", "after", "child").contains(position)) {
            throw new IllegalArgumentException("position 必须是 before/after/child，实际=" + position);
        }
        List<StarredFolder> folders = loadFolders();
        StarredFolder moved = null;
        for (StarredFolder f : folders) {
            if (folderId.equals(f.getId())) { moved = f; break; }
        }
        if (moved == null) return false;

        // 「child」语义：把 moved 挂到 anchorId 下；其他两个语义：作为 anchorId 的兄弟
        String targetParentId;
        if ("child".equals(position)) {
            // anchor 必须是另一文件夹（不能是自己或自己的后代）
            if (anchorId == null || anchorId.isBlank() || folderId.equals(anchorId)) return false;
            if (!existsFolder(folders, anchorId)) return false;
            if (isDescendantOf(folders, anchorId, folderId)) return false; // 不能移到自己的后代
            targetParentId = anchorId;
        } else {
            if (anchorId == null || anchorId.isBlank()) {
                targetParentId = normalizeParentId(folders, newParentId);
            } else {
                StarredFolder anchor = findById(folders, anchorId);
                if (anchor == null) return false;
                if (anchor.getId().equals(folderId)) return false; // 不能以自己为锚点
                // 锚点的父 = 新父（即使拖到不同层级的锚点旁，仍按锚点同级处理）
                targetParentId = anchor.getParentId();
            }
        }
        // 同父同位 = no-op
        if (java.util.Objects.equals(targetParentId, moved.getParentId())
                && position.equals("before") && sameParentNeighbour(folders, moved, anchorId, true)) {
            return false;
        }

        // 把 moved 从当前位置摘出（仅修改内存中的 list）
        folders.remove(moved);
        moved.setParentId(targetParentId);

        // 计算插入位置：基于 targetParentId 下所有 siblings 在 folders 列表中的实际下标
        int insertPos = computeInsertIndex(folders, targetParentId, anchorId, position);
        folders.add(insertPos, moved);

        settings().saveStarredFolders(folders);
        syncStarredSet(folders);
        return true;
    }

    private boolean existsFolder(List<StarredFolder> folders, String id) {
        for (StarredFolder f : folders) if (id.equals(f.getId())) return true;
        return false;
    }

    private StarredFolder findById(List<StarredFolder> folders, String id) {
        for (StarredFolder f : folders) if (id.equals(f.getId())) return f;
        return null;
    }

    /** anchor 是不是 moved 在同父下的直接邻居（用于识别 no-op）。 */
    private boolean sameParentNeighbour(List<StarredFolder> folders, StarredFolder moved,
                                         String anchorId, boolean beforeSide) {
        if (anchorId == null) return false;
        StarredFolder anchor = findById(folders, anchorId);
        if (anchor == null) return false;
        if (!java.util.Objects.equals(anchor.getParentId(), moved.getParentId())) return false;
        int anchorIdx = folders.indexOf(anchor);
        int movedIdx = folders.indexOf(moved);
        if (anchorIdx < 0 || movedIdx < 0) return false;
        return beforeSide ? anchorIdx == movedIdx + 1 : anchorIdx == movedIdx - 1;
    }

    /**
     * 计算把 moved 插入到 folders 列表中的下标。
     * <p>锚点所在的「同一父级下的兄弟块」在 folders 列表中是连续的；我们要插在锚点块前/后/锚点块开头。</p>
     */
    private int computeInsertIndex(List<StarredFolder> folders, String targetParentId,
                                    String anchorId, String position) {
        // 找到 targetParentId 下兄弟的第一个和最后一个下标
        int first = -1, last = -1;
        for (int i = 0; i < folders.size(); i++) {
            StarredFolder f = folders.get(i);
            String fp = f.getParentId();
            boolean sameParent = targetParentId == null
                    ? (fp == null || fp.isBlank())
                    : targetParentId.equals(fp);
            if (sameParent) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) {
            // 没有兄弟（罕见：拖到空目录的 child），直接 append
            return folders.size();
        }
        if (anchorId == null || anchorId.isBlank()) {
            // 无锚点：作为第一个兄弟
            return first;
        }
        StarredFolder anchor = findById(folders, anchorId);
        if (anchor == null) return folders.size();
        int anchorIdx = folders.indexOf(anchor);
        if ("before".equals(position)) {
            // 插在锚点所在兄弟块的开头
            return first;
        } else if ("after".equals(position)) {
            // 插在锚点所在兄弟块的末尾之后
            return last + 1;
        } else {
            // child：作为锚点的第一个子
            return anchorIdx + 1;
        }
    }

    /** id 是否为 ancestorId 的后代（防把文件夹移到自己的子树里）。 */
    public static boolean isDescendantOf(List<StarredFolder> folders, String id, String ancestorId) {
        if (id == null || ancestorId == null) return false;
        if (id.equals(ancestorId)) return true;
        // 沿 parentId 上溯
        Set<String> seen = new HashSet<>();
        String cur = id;
        while (cur != null && !cur.isBlank() && seen.add(cur)) {
            StarredFolder f = null;
            for (StarredFolder x : folders) if (cur.equals(x.getId())) { f = x; break; }
            if (f == null) return false;
            String p = f.getParentId();
            if (p == null || p.isBlank()) return false;
            if (p.equals(ancestorId)) return true;
            cur = p;
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
     * #66 修复：把收藏文件夹里"扫描缓存里查不到对应 ApiDefinition"的 apiKey 视为失效，
     * 一键清掉这些幽灵收藏（对应接口被删除、或文件被移除、或扫描配置变更不再产出）。
     * <p>与 {@link #remapApiKeys} 互补：remap 处理"接口还在但路径改了"，
     * dropStaleApiKeys 处理"接口真的没了"。每次扫描完成后由
     * {@code ApiScannerService.scanProjectApisAsync} 调用一次。</p>
     *
     * @param aliveKeys 当前项目里真实存在的 apiKey 集合
     * @return 被清理的失效条目总数（用于日志与统计）
     */
    public int dropStaleApiKeys(java.util.Collection<String> aliveKeys) {
        if (aliveKeys == null) aliveKeys = java.util.Collections.emptyList();
        java.util.Set<String> alive = new java.util.HashSet<>(aliveKeys);
        List<StarredFolder> folders = loadFolders();

        // folder.apiKeys 这一层（StarredFolder apiKeys List）
        int removedFromFolders = 0;
        for (StarredFolder f : folders) {
            List<String> keys = f.getApiKeys();
            for (int i = keys.size() - 1; i >= 0; i--) {
                if (!alive.contains(keys.get(i))) {
                    keys.remove(i);
                    removedFromFolders++;
                }
            }
        }
        if (removedFromFolders > 0) {
            settings().saveStarredFolders(folders);
            syncStarredSet(folders);
        }

        // 其他持久化字段（starredApis / folderApiParams / folderApiStatus /
        // preRequestScripts / apiVariableOverrides / apiCallCounts / apiLastCallTimes /
        // lastScanApiSignatures）的 stale 清理下沉到 settings.dropStaleApiKeys，
        // 单测可独立覆盖，无需 mock Project。
        int removedFromSettings = settings().dropStaleApiKeys(alive);

        return removedFromFolders + removedFromSettings;
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