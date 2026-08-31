package com.hronline.scanner;

import com.hronline.model.StarredFolder;
import com.hronline.settings.RestAutoLabSettingsState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多级收藏目录（v2.1）回归测试：
 * <ul>
 *   <li>StarredFolder.parentId 向后兼容（旧数据无该字段 → 顶层）</li>
 *   <li>{@link StarredFolderService#collectSubtreeIds} 子树收集（含多级、孤儿、环）</li>
 *   <li>持久化往返保留层级结构</li>
 * </ul>
 */
class StarredFolderNestedTest {

    @Test
    void folderWithoutParentIdIsTopLevel() {
        StarredFolder f = new StarredFolder("a", "A");
        assertNull(f.getParentId());
        assertTrue(f.isTopLevel());
        f.setParentId("");
        assertTrue(f.isTopLevel(), "空串 parentId 也视为顶层");
        f.setParentId("p1");
        assertFalse(f.isTopLevel());
    }

    @Test
    void legacyJsonWithoutParentIdFieldStaysTopLevel() {
        // 旧版本保存的数据不含 parentId 字段，Gson 反序列化后应为 null → 顶层，接口列表不丢
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        state.getState().starredFoldersJson =
                "[{\"id\":\"f1\",\"name\":\"旧文件夹\",\"apiKeys\":[\"k1\"]}]";
        List<StarredFolder> folders = state.loadStarredFolders();
        // loadStarredFolders 保证「未分类」存在且首位
        assertEquals(2, folders.size());
        StarredFolder legacy = findById(folders, "f1");
        assertNotNull(legacy);
        assertNull(legacy.getParentId(), "旧数据不应被臆造 parentId");
        assertTrue(legacy.isTopLevel());
        assertEquals(List.of("k1"), legacy.getApiKeys());
    }

    @Test
    void saveAndReloadPreservesNestedHierarchy() {
        RestAutoLabSettingsState state = new RestAutoLabSettingsState();
        List<StarredFolder> folders = new ArrayList<>();
        StarredFolder root = new StarredFolder("root", "根");
        StarredFolder child = new StarredFolder("child", "子", "root");
        StarredFolder grand = new StarredFolder("grand", "孙", "child");
        grand.getApiKeys().add("api-1");
        folders.add(root);
        folders.add(child);
        folders.add(grand);
        state.saveStarredFolders(folders);

        List<StarredFolder> reloaded = state.loadStarredFolders();
        // 「未分类」被补到首位，其余三个按保存内容回显
        assertEquals(4, reloaded.size());
        StarredFolder rootLoaded = findById(reloaded, "root");
        assertNotNull(rootLoaded);
        assertTrue(rootLoaded.isTopLevel());
        StarredFolder childLoaded = findById(reloaded, "child");
        assertNotNull(childLoaded);
        assertEquals("root", childLoaded.getParentId());
        StarredFolder grandLoaded = findById(reloaded, "grand");
        assertNotNull(grandLoaded);
        assertEquals("child", grandLoaded.getParentId());
        assertEquals(List.of("api-1"), grandLoaded.getApiKeys());
    }

    @Test
    void collectSubtreeIdsIncludesAllDescendants() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("root", "根"));
        folders.add(new StarredFolder("a", "A", "root"));
        folders.add(new StarredFolder("b", "B", "root"));
        folders.add(new StarredFolder("a1", "A1", "a"));
        folders.add(new StarredFolder("other", "其他"));

        assertEquals(List.of("root", "a", "b", "a1"),
                StarredFolderService.collectSubtreeIds(folders, "root"),
                "应收集自身与全部后代，不含兄弟节点");
        assertEquals(List.of("a", "a1"),
                StarredFolderService.collectSubtreeIds(folders, "a"),
                "中间节点只收集自身子树");
        assertEquals(List.of("b"),
                StarredFolderService.collectSubtreeIds(folders, "b"),
                "叶子节点只有自身");
    }

    @Test
    void orphanFolderNotIncludedInSubtree() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("root", "根"));
        folders.add(new StarredFolder("orphan", "孤儿", "missing-parent"));
        assertEquals(List.of("root"), StarredFolderService.collectSubtreeIds(folders, "root"));
    }

    @Test
    void collectSubtreeIdsTerminatesOnCycle() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("a", "A", "b"));
        folders.add(new StarredFolder("b", "B", "a"));
        assertEquals(List.of("a", "b"), StarredFolderService.collectSubtreeIds(folders, "a"),
                "脏数据成环时不得死循环");
    }

    private StarredFolder findById(List<StarredFolder> folders, String id) {
        for (StarredFolder f : folders) {
            if (id.equals(f.getId())) return f;
        }
        return null;
    }
}
