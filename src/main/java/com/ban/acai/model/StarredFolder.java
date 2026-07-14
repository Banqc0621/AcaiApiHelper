package com.ban.acai.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏文件夹 —— 用户可在收藏管理界面中新建/删除/更名的分组容器。
 *
 * <p>收藏语义：一个接口可被收入多个文件夹（不同文件夹可出现相同接口），
 * 但同一文件夹内接口唯一（按 {@link ApiDefinition#uniqueKey()} 去重）。</p>
 *
 * <p>本类为可持久化的纯数据结构，由 {@link com.ban.acai.settings.AcaiSettingsState}
 * 序列化为 JSON 存储。{@code id} 为稳定标识（重命名不变），{@code apiKeys} 为有序列表，
 * 决定文件夹内的显示顺序。</p>
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
    /** 文件夹内接口的 uniqueKey 有序列表（同文件夹内唯一） */
    private List<String> apiKeys = new ArrayList<>();

    public StarredFolder() {}

    public StarredFolder(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> apiKeys) { this.apiKeys = apiKeys; }
}