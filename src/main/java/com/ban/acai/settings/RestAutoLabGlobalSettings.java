package com.ban.acai.settings;

import com.ban.acai.RestAutoLabConstants;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 应用全局配置（Application级持久化）
 *
 * 提供9个核心配置项，每个配置项均有对应的getter/setter，
 * 配置数据在IDE级别全局共享，不随Project变化。
 */
@State(
        name = "RestAutoLabGlobalSettings",
        storages = @Storage("restautolab-global.xml")
)
public class RestAutoLabGlobalSettings implements PersistentStateComponent<RestAutoLabGlobalSettings.State> {

    /**
     * 全局配置状态 - 恰好9个可配置字段
     */
    public static class State {
        /** 字段1: 默认API基础URL */
        public String defaultBaseUrl = RestAutoLabConstants.DEFAULT_BASE_URL;
        /** 字段2: 火山引擎方舟API全局地址 */
        public String arkApiUrl = RestAutoLabConstants.ARK_API_BASE_URL;
        /** 字段3: 方舟API Key */
        public String arkApiKey = "";
        /** 字段4: 默认AI模型 */
        public String defaultAiModel = RestAutoLabConstants.ARK_MODEL_PRO;
        /** 字段5: 是否启用AI功能 */
        public boolean aiEnabled = true;
        /** 字段6: 是否启用Git预提交检查 */
        public boolean gitCheckEnabled = true;
        /** 字段7: Git检查允许的HTTP状态码 */
        public String allowedStatusCodes = RestAutoLabConstants.DEFAULT_ALLOWED_STATUS_CODES;
        /** 字段8: 默认请求超时时间(秒) */
        public int requestTimeoutSeconds = RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS;
        /** 字段9: 项目启动时是否自动扫描API */
        public boolean autoScanOnStartup = true;
    }

    private State myState = new State();

    @Nullable
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    // ═══════════════════════════════════════════════════════════
    // 9个字段的Getter/Setter (严格对应State中的9个字段)
    // ═══════════════════════════════════════════════════════════

    /** 字段1: 默认API基础URL - Getter */
    public String getDefaultBaseUrl() {
        return myState.defaultBaseUrl;
    }

    /** 字段1: 默认API基础URL - Setter */
    public void setDefaultBaseUrl(String defaultBaseUrl) {
        myState.defaultBaseUrl = defaultBaseUrl;
    }

    /** 字段2: 方舟API地址 - Getter */
    public String getArkApiUrl() {
        return myState.arkApiUrl;
    }

    /** 字段2: 方舟API地址 - Setter */
    public void setArkApiUrl(String arkApiUrl) {
        myState.arkApiUrl = arkApiUrl;
    }

    /** 字段3: 方舟API Key - Getter */
    public String getArkApiKey() {
        return myState.arkApiKey;
    }

    /** 字段3: 方舟API Key - Setter */
    public void setArkApiKey(String arkApiKey) {
        myState.arkApiKey = arkApiKey;
    }

    /** 字段4: 默认AI模型 - Getter */
    public String getDefaultAiModel() {
        return myState.defaultAiModel;
    }

    /** 字段4: 默认AI模型 - Setter */
    public void setDefaultAiModel(String defaultAiModel) {
        myState.defaultAiModel = defaultAiModel;
    }

    /** 字段5: AI功能启用状态 - Getter */
    public boolean isAiEnabled() {
        return myState.aiEnabled;
    }

    /** 字段5: AI功能启用状态 - Setter */
    public void setAiEnabled(boolean aiEnabled) {
        myState.aiEnabled = aiEnabled;
    }

    /** 字段6: Git检查启用状态 - Getter */
    public boolean isGitCheckEnabled() {
        return myState.gitCheckEnabled;
    }

    /** 字段6: Git检查启用状态 - Setter */
    public void setGitCheckEnabled(boolean gitCheckEnabled) {
        myState.gitCheckEnabled = gitCheckEnabled;
    }

    /** 字段7: 允许的HTTP状态码 - Getter */
    public String getAllowedStatusCodes() {
        return myState.allowedStatusCodes;
    }

    /** 字段7: 允许的HTTP状态码 - Setter */
    public void setAllowedStatusCodes(String allowedStatusCodes) {
        myState.allowedStatusCodes = allowedStatusCodes;
    }

    /** 字段8: 请求超时秒数 - Getter */
    public int getRequestTimeoutSeconds() {
        return myState.requestTimeoutSeconds;
    }

    /** 字段8: 请求超时秒数 - Setter */
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        myState.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /** 字段9: 启动自动扫描 - Getter */
    public boolean isAutoScanOnStartup() {
        return myState.autoScanOnStartup;
    }

    /** 字段9: 启动自动扫描 - Setter */
    public void setAutoScanOnStartup(boolean autoScanOnStartup) {
        myState.autoScanOnStartup = autoScanOnStartup;
    }

    /**
     * 获取RestAutoLabGlobalSettings实例的便捷方法（Application级单例）
     */
    public static RestAutoLabGlobalSettings getInstance() {
        return ApplicationManager.getApplication().getService(RestAutoLabGlobalSettings.class);
    }
}