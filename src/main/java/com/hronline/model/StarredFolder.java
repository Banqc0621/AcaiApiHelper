package com.hronline.model;

import com.hronline.settings.RestAutoLabSettingsState;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏文件夹 —— 用户可在收藏管理界面中新建/删除/更名的分组容器。
 *
 * <p>收藏语义：一个接口可被收入多个文件夹（不同文件夹可出现相同接口），
 * 但同一文件夹内接口唯一（按 {@link ApiDefinition#uniqueKey()} 去重）。</p>
 *
 * <p>本类为可持久化的纯数据结构，由 {@link RestAutoLabSettingsState}
 * 序列化为 JSON 存储。{@code id} 为稳定标识（重命名不变），{@code apiKeys} 为有序列表，
 * 决定文件夹内的显示顺序。</p>
 *
 * <p>多级目录（v2.1）：{@code parentId} 指向父文件夹的 {@code id}；
 * {@code null}/空串表示顶层文件夹。旧数据无此字段，Gson 反序列化后为 {@code null}，
 * 自动视为顶层，天然向后兼容。</p>
 */
public class StarredFolder {

    /** 未分类文件夹的固定 id（v2.0.0 起可删除/重命名，删除后按需自动重建），用于收纳未归入任何文件夹的收藏接口 */
    public static final String UNCATEGORIZED_ID = "__uncategorized__";

    /** 未分类文件夹的显示名 */
    public static final String UNCATEGORIZED_NAME = "未分类";

    /** 稳定 id（UUID），重命名不变 */
    private String id;
    /** 文件夹显示名 */
    private String name;
    /** 父文件夹 id；null/空串 = 顶层文件夹（旧数据无此字段，反序列化后为 null） */
    private String parentId;
    /** 文件夹内接口的 uniqueKey 有序列表（同文件夹内唯一） */
    private List<String> apiKeys = new ArrayList<>();

    public StarredFolder() {}

    public StarredFolder(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public StarredFolder(String id, String name, String parentId) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    /** 是否为顶层文件夹（无父或父已被删除时按顶层处理） */
    public boolean isTopLevel() { return parentId == null || parentId.isBlank(); }

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> apiKeys) { this.apiKeys = apiKeys; }
}