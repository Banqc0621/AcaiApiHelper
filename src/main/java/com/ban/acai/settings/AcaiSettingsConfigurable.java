package com.ban.acai.settings;

import com.ban.acai.AcaiConstants;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 插件设置页面 - JBColor主题感知、分区标题描述
 */
public class AcaiSettingsConfigurable implements Configurable {

    private final Project project;
    private JBTextField baseUrlField;
    private JBTextField arkApiUrlField;
    private JBPasswordField arkApiKeyField;
    private JBTextField arkApiPathField;
    private JBTextField arkModelProField;
    private JBTextField arkModelCodeField;
    private JBCheckBox aiEnabledBox;
    private JBCheckBox gitCheckEnabledBox;
    private JBTextField gitAllowedCodesField;
    private JBTextField timeoutField;
    private JBTextField maxAiCallsField;
    private JBCheckBox autoScanBox;
    private JBTextArea systemPromptArea;
    private JBTextArea userPromptTemplateArea;

    public AcaiSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    @Nls(capitalization = Nls.Capitalization.Title)
    public String getDisplayName() {
        return AcaiConstants.PLUGIN_NAME;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return baseUrlField;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        AcaiSettingsState settings = AcaiSettingsState.getInstance(project);
        AcaiSettingsState.State state = settings.getState();

        baseUrlField = new JBTextField(state != null ? state.baseUrl : AcaiConstants.DEFAULT_BASE_URL);
        arkApiUrlField = new JBTextField(state != null ? state.arkApiUrl : AcaiConstants.ARK_API_BASE_URL);
        arkApiKeyField = new JBPasswordField();
        if (state != null) arkApiKeyField.setText(state.arkApiKey);
        arkApiPathField = new JBTextField(state != null && state.aiApiPath != null && !state.aiApiPath.isBlank()
                ? state.aiApiPath : AcaiConstants.AI_DEFAULT_API_PATH);
        arkModelProField = new JBTextField(state != null ? state.arkModelPro : AcaiConstants.ARK_MODEL_PRO);
        arkModelCodeField = new JBTextField(state != null ? state.arkModelCode : AcaiConstants.ARK_MODEL_CODE);
        aiEnabledBox = new JBCheckBox("启用AI参数生成", state != null && state.aiEnabled);
        gitCheckEnabledBox = new JBCheckBox("启用Git预提交API检查", state == null || state.gitCheckEnabled);
        gitAllowedCodesField = new JBTextField(state != null ? state.gitAllowedStatusCodes : AcaiConstants.DEFAULT_ALLOWED_STATUS_CODES);
        timeoutField = new JBTextField(String.valueOf(state != null ? state.requestTimeout : AcaiConstants.HTTP_REQUEST_TIMEOUT_SECONDS));
        maxAiCallsField = new JBTextField(String.valueOf(state != null ? state.maxAiCallsPerScan : 50));
        autoScanBox = new JBCheckBox("项目打开时自动扫描API", state == null || state.autoScanOnStartup);

        // AI 提示词自定义
        systemPromptArea = new JBTextArea(state != null ? state.aiSystemPrompt : AcaiConstants.AI_SYSTEM_PROMPT);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        JScrollPane systemPromptScroll = new JScrollPane(systemPromptArea);
        systemPromptScroll.setPreferredSize(new Dimension(520, 110));

        userPromptTemplateArea = new JBTextArea(
                state != null ? state.aiUserPromptTemplate : AcaiConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        userPromptTemplateArea.setLineWrap(true);
        userPromptTemplateArea.setWrapStyleWord(true);
        JScrollPane userPromptScroll = new JScrollPane(userPromptTemplateArea);
        userPromptScroll.setPreferredSize(new Dimension(520, 220));

        // Section header style
        JBColor sectionColor = new JBColor(new Color(0x4B, 0x00, 0x82), new Color(0xBB, 0x86, 0xFC));
        Font sectionFont = UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f);

        // ── Section 1: API Service ──
        JBLabel apiHeader = new JBLabel("API 服务配置");
        apiHeader.setForeground(sectionColor);
        apiHeader.setFont(sectionFont);
        JBLabel apiDesc = new JBLabel("<html><font color='#888888' size='2'>配置接口调试的基础参数</font></html>");

        // ── Section 2: Volcano Ark AI ──
        JBLabel aiHeader = new JBLabel("AI 模型配置（OpenAI 兼容协议）");
        aiHeader.setForeground(sectionColor);
        aiHeader.setFont(sectionFont);
        JBLabel aiDesc = new JBLabel("<html><font color='#888888' size='2'>"
                + "支持火山方舟 / OpenAI / Qwen / vLLM 等 OpenAI 兼容服务。<br>"
                + "认证方式：HTTP Header <code>Authorization: Bearer &lt;API Key&gt;</code>；"
                + "请求体必含 <code>model</code> 字段。"
                + "</font></html>");

        // ── Section 2.5: AI Prompt Custom ──
        JBLabel promptHeader = new JBLabel("AI 提示词自定义");
        promptHeader.setForeground(sectionColor);
        promptHeader.setFont(sectionFont);
        JBLabel promptDesc = new JBLabel("<html><font color='#888888' size='2'>自定义系统/用户提示词，用户提示词支持占位符自动注入接口信息</font></html>");
        JBLabel promptHint = new JBLabel("<html><font color='#888888' size='2'>用户提示词占位符: ${API_URL} ${HTTP_METHOD} ${API_NAME} ${CONTROLLER_NAME} ${DESCRIPTION} ${CONTENT_TYPE} ${PARAMETERS} ${SCENARIO_NAME} ${SCENARIO_DESC} ${FULL_HINT}</font></html>");
        JButton resetPromptBtn = new JButton("恢复默认提示词");
        resetPromptBtn.addActionListener(e -> {
            systemPromptArea.setText(AcaiConstants.AI_SYSTEM_PROMPT);
            userPromptTemplateArea.setText(AcaiConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        });

        // ── Section 3: Git Integration ──
        JBLabel gitHeader = new JBLabel("Git 预提交检查配置");
        gitHeader.setForeground(sectionColor);
        gitHeader.setFont(sectionFont);
        JBLabel gitDesc = new JBLabel("<html><font color='#888888' size='2'>接口测试失败时阻断git commit</font></html>");

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(apiHeader)
                .addComponent(apiDesc)
                .addSeparator()
                .addLabeledComponent(new JBLabel("API 基础 URL:"), baseUrlField, 1, false)
                .addLabeledComponent(new JBLabel("请求超时(秒):"), timeoutField, 1, false)
                .addComponent(autoScanBox, 1)
                .addVerticalGap(16)

                .addComponent(aiHeader)
                .addComponent(aiDesc)
                .addSeparator()
                .addComponent(aiEnabledBox, 1)
                .addLabeledComponent(new JBLabel("API 服务地址:"), arkApiUrlField, 1, false)
                .addLabeledComponent(new JBLabel("API Key (Bearer):"), arkApiKeyField, 1, false)
                .addLabeledComponent(new JBLabel("API 路径:"), arkApiPathField, 1, false)
                .addLabeledComponent(new JBLabel("主模型(pro):"), arkModelProField, 1, false)
                .addLabeledComponent(new JBLabel("轻量模型(code):"), arkModelCodeField, 1, false)
                .addLabeledComponent(new JBLabel("单次最大AI调用:"), maxAiCallsField, 1, false)
                .addVerticalGap(16)

                .addComponent(promptHeader)
                .addComponent(promptDesc)
                .addSeparator()
                .addLabeledComponent(new JBLabel("系统提示词 (System Prompt):"), systemPromptScroll, 1, false)
                .addLabeledComponent(new JBLabel("用户提示词模板 (User Prompt):"), userPromptScroll, 1, false)
                .addComponent(promptHint, 1)
                .addComponent(resetPromptBtn, 1)
                .addVerticalGap(16)

                .addComponent(gitHeader)
                .addComponent(gitDesc)
                .addSeparator()
                .addComponent(gitCheckEnabledBox, 1)
                .addLabeledComponent(new JBLabel("允许的HTTP状态码:"), gitAllowedCodesField, 1, false)

                .addVerticalGap(16)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        // Wrap in scroll pane
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scrollPane;
    }

    @Override
    public boolean isModified() {
        AcaiSettingsState s = AcaiSettingsState.getInstance(project);
        AcaiSettingsState.State state = s.getState();
        if (state == null) return true;
        return !fieldText(baseUrlField).equals(state.baseUrl)
                || !fieldText(arkApiUrlField).equals(state.arkApiUrl)
                || !new String(arkApiKeyField.getPassword()).equals(state.arkApiKey)
                || !fieldText(arkApiPathField).equals(state.aiApiPath)
                || !fieldText(arkModelProField).equals(state.arkModelPro)
                || !fieldText(arkModelCodeField).equals(state.arkModelCode)
                || aiEnabledBox.isSelected() != state.aiEnabled
                || gitCheckEnabledBox.isSelected() != state.gitCheckEnabled
                || !fieldText(gitAllowedCodesField).equals(state.gitAllowedStatusCodes)
                || autoScanBox.isSelected() != state.autoScanOnStartup
                || !fieldText(timeoutField).equals(String.valueOf(state.requestTimeout))
                || !fieldText(maxAiCallsField).equals(String.valueOf(state.maxAiCallsPerScan))
                || !systemPromptArea.getText().equals(state.aiSystemPrompt)
                || !userPromptTemplateArea.getText().equals(state.aiUserPromptTemplate);
    }

    @Override
    public void apply() {
        AcaiSettingsState s = AcaiSettingsState.getInstance(project);
        s.setBaseUrl(fieldText(baseUrlField));
        s.setArkApiUrl(fieldText(arkApiUrlField));
        s.setArkApiKey(new String(arkApiKeyField.getPassword()));
        s.setAiApiPath(fieldText(arkApiPathField));
        s.setArkModelPro(fieldText(arkModelProField));
        s.setArkModelCode(fieldText(arkModelCodeField));
        s.setAiEnabled(aiEnabledBox.isSelected());
        s.setGitCheckEnabled(gitCheckEnabledBox.isSelected());
        s.setGitAllowedStatusCodes(fieldText(gitAllowedCodesField));
        s.setRequestTimeout(parseInt(fieldText(timeoutField), AcaiConstants.HTTP_REQUEST_TIMEOUT_SECONDS));
        s.setMaxAiCallsPerScan(parseInt(fieldText(maxAiCallsField), 50));
        s.setAutoScanOnStartup(autoScanBox.isSelected());
        s.setAiSystemPrompt(systemPromptArea.getText());
        s.setAiUserPromptTemplate(userPromptTemplateArea.getText());
    }

    @Override
    public void reset() {
        AcaiSettingsState s = AcaiSettingsState.getInstance(project);
        AcaiSettingsState.State state = s.getState();
        if (state == null) return;
        baseUrlField.setText(state.baseUrl);
        arkApiUrlField.setText(state.arkApiUrl);
        arkApiKeyField.setText(state.arkApiKey);
        arkApiPathField.setText(state.aiApiPath != null && !state.aiApiPath.isBlank()
                ? state.aiApiPath : AcaiConstants.AI_DEFAULT_API_PATH);
        arkModelProField.setText(state.arkModelPro);
        arkModelCodeField.setText(state.arkModelCode);
        aiEnabledBox.setSelected(state.aiEnabled);
        gitCheckEnabledBox.setSelected(state.gitCheckEnabled);
        gitAllowedCodesField.setText(state.gitAllowedStatusCodes);
        timeoutField.setText(String.valueOf(state.requestTimeout));
        maxAiCallsField.setText(String.valueOf(state.maxAiCallsPerScan));
        autoScanBox.setSelected(state.autoScanOnStartup);
        systemPromptArea.setText(state.aiSystemPrompt);
        userPromptTemplateArea.setText(state.aiUserPromptTemplate);
    }

    private static String fieldText(JTextField f) { return f.getText().trim(); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
}