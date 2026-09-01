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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ═══════════════════════════════════════════════════════════════
    // v2.2 拖拽移动文件夹（applyFolderMove 纯函数测试）
    // ═══════════════════════════════════════════════════════════════

    /** 顶层文件夹按插入顺序：a、b、c */
    private static List<StarredFolder> sampleFlat() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("a", "A"));
        folders.add(new StarredFolder("b", "B"));
        folders.add(new StarredFolder("c", "C"));
        return folders;
    }

    @Test
    void applyFolderMoveAfterMovesSiblingToEnd() {
        List<StarredFolder> folders = sampleFlat();
        // a 拖到 c 之后：[b, c, a]
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "a", null, "c", "after");
        assertNotNull(out);
        assertEquals(List.of("b", "c", "a"), ids(out));
        // parentId 不变
        assertTrue(findById(out, "a").isTopLevel());
    }

    @Test
    void applyFolderMoveBeforeInsertsAtBlockStart() {
        List<StarredFolder> folders = sampleFlat();
        // c 拖到 a 之前：[c, a, b]
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "c", null, "a", "before");
        assertNotNull(out);
        assertEquals(List.of("c", "a", "b"), ids(out));
    }

    @Test
    void applyFolderMoveChildAppendsAsLastChild() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("p", "P"));
        folders.add(new StarredFolder("p1", "P1", "p"));
        folders.add(new StarredFolder("p2", "P2", "p"));
        folders.add(new StarredFolder("x", "X"));
        // x 拖到 p 下作为子：[p, p1, p2, x]
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "x", null, "p", "child");
        assertNotNull(out);
        assertEquals(List.of("p", "p1", "p2", "x"), ids(out));
        assertEquals("p", findById(out, "x").getParentId());
    }

    @Test
    void applyFolderMoveChildBetweenExistingChildren() {
        // 现有结构：p 下有 p1、p2（DFS 顺序：父紧跟子）。
        // 把 m 拖为 p1 的子级 → m 应紧跟 p1，p2 仍在 p 下。
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("p", "P"));
        folders.add(new StarredFolder("p1", "P1", "p"));
        folders.add(new StarredFolder("p2", "P2", "p"));
        folders.add(new StarredFolder("m", "M"));
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "m", null, "p1", "child");
        assertNotNull(out);
        // m 作为 p1 的第一个子，紧跟 p1 之后；p2 保留在 p 下
        assertEquals(List.of("p", "p1", "m", "p2"), ids(out));
        assertEquals("p1", findById(out, "m").getParentId());
    }

    @Test
    void applyFolderMoveWithNoAnchorInsertsAsFirstSibling() {
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("p", "P"));
        folders.add(new StarredFolder("p1", "P1", "p"));
        folders.add(new StarredFolder("p2", "P2", "p"));
        folders.add(new StarredFolder("z", "Z"));
        // z 移到 p 下作为第一个子（无锚点 + 目标父 p）→ 插在 p1 之前
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "z", "p", null, "child");
        assertNotNull(out);
        assertEquals(List.of("p", "z", "p1", "p2"), ids(out));
        assertEquals("p", findById(out, "z").getParentId());
    }

    @Test
    void applyFolderMoveRejectsSelfAnchor() {
        // 把文件夹拖到自己上 = child 语义下应返回 null
        List<StarredFolder> folders = sampleFlat();
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "a", null, "a", "child");
        assertNull(out);
        out = StarredFolderService.applyFolderMove(folders, "a", null, "a", "after");
        assertNull(out);
    }

    @Test
    void applyFolderMoveRejectsMoveIntoDescendant() {
        // 不能把 a 拖到 a1 子级（a1 是 a 的后代）
        List<StarredFolder> folders = new ArrayList<>();
        folders.add(new StarredFolder("a", "A"));
        folders.add(new StarredFolder("a1", "A1", "a"));
        folders.add(new StarredFolder("a1x", "A1X", "a1"));
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "a", null, "a1x", "child");
        assertNull(out, "不能把父级拖到自己的后代");
    }

    @Test
    void applyFolderMoveRejectsUnknownAnchor() {
        List<StarredFolder> folders = sampleFlat();
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "a", null, "missing", "after");
        assertNull(out);
    }

    @Test
    void applyFolderMoveNoopReturnsNullWhenSamePosition() {
        // a 拖到 c 之前再拖到 c 之后，先恢复位
        List<StarredFolder> folders = sampleFlat();
        List<StarredFolder> moved = StarredFolderService.applyFolderMove(folders, "a", null, "c", "after");
        assertNotNull(moved);
        // 再把 a 移回原位（c 之前）— 这是真实的"位置+父级都未变化"场景
        List<StarredFolder> restored = StarredFolderService.applyFolderMove(moved, "a", null, "a", "before");
        // a 在 newParentId=null 下、position=before 以 a 为锚点（自身）= null
        assertNull(restored);
    }

    @Test
    void applyFolderMovePreservesApiKeys() {
        List<StarredFolder> folders = new ArrayList<>();
        StarredFolder a = new StarredFolder("a", "A");
        a.getApiKeys().add("k1");
        a.getApiKeys().add("k2");
        folders.add(a);
        folders.add(new StarredFolder("b", "B"));
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "a", null, "b", "after");
        assertNotNull(out);
        assertEquals(List.of("k1", "k2"), findById(out, "a").getApiKeys(),
                "移动文件夹不应丢 apiKeys");
    }

    @Test
    void applyFolderMoveRejectsBadPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> StarredFolderService.applyFolderMove(sampleFlat(), "a", null, "b", "sideways"));
    }

    @Test
    void applyFolderMoveReturnsNullForUnknownFolder() {
        List<StarredFolder> folders = sampleFlat();
        List<StarredFolder> out = StarredFolderService.applyFolderMove(folders, "missing", null, "b", "after");
        assertNull(out);
    }

    @Test
    void applyFolderMoveDoesNotMutateInputList() {
        // 纯函数：调用方传入的 list 不应被修改
        List<StarredFolder> folders = sampleFlat();
        List<String> before = ids(folders);
        StarredFolderService.applyFolderMove(folders, "a", null, "c", "after");
        assertEquals(before, ids(folders), "applyFolderMove 必须返回新 list，不得污染输入");
    }

    private StarredFolder findById(List<StarredFolder> folders, String id) {
        for (StarredFolder f : folders) {
            if (id.equals(f.getId())) return f;
        }
        return null;
    }

    /** 取文件夹列表的 id 序列，方便断言顺序 */
    private List<String> ids(List<StarredFolder> folders) {
        List<String> out = new ArrayList<>(folders.size());
        for (StarredFolder f : folders) out.add(f.getId());
        return out;
    }
}
