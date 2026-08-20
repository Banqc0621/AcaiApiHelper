package com.hronline.settings;

import com.hronline.RestAutoLabConstants;
import com.hronline.ui.UiStyle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 插件设置页面 - JBColor主题感知、分区标题描述
 */
public class RestAutoLabSettingsConfigurable implements Configurable {

    private final Project project;
    private JBTextField baseUrlField;
    private JBTextField arkApiUrlField;
    private JBPasswordField arkApiKeyField;
    private JBTextField arkApiPathField;
    private JBTextField arkModelProField;
    private JBTextField arkModelCodeField;
    private JBCheckBox aiEnabledBox;
    private JComboBox<String> accentColorCombo;
    private JBCheckBox gitCheckEnabledBox;
    private JBTextField gitAllowedCodesField;
    private JBTextField timeoutField;
    private JBTextField maxAiCallsField;
    private JBCheckBox autoScanBox;
    private JBTextField scanPackageField;
    private JBTextArea systemPromptArea;
    private JBTextArea userPromptTemplateArea;

    public RestAutoLabSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    @Nls(capitalization = Nls.Capitalization.Title)
    public String getDisplayName() {
        return RestAutoLabConstants.PLUGIN_NAME;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return baseUrlField;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        RestAutoLabSettingsState settings = RestAutoLabSettingsState.getInstance(project);
        RestAutoLabSettingsState.State state = settings.getState();

        baseUrlField = new JBTextField(state != null ? state.baseUrl : RestAutoLabConstants.DEFAULT_BASE_URL);
        arkApiUrlField = new JBTextField(state != null ? state.arkApiUrl : RestAutoLabConstants.ARK_API_BASE_URL);
        arkApiKeyField = new JBPasswordField();
        if (state != null) arkApiKeyField.setText(state.arkApiKey);
        arkApiPathField = new JBTextField(state != null && state.aiApiPath != null
                ? state.aiApiPath : "");
        arkModelProField = new JBTextField(state != null ? state.arkModelPro : RestAutoLabConstants.ARK_MODEL_PRO);
        arkModelCodeField = new JBTextField(state != null ? state.arkModelCode : RestAutoLabConstants.ARK_MODEL_CODE);
        aiEnabledBox = new JBCheckBox("启用AI参数生成", state != null && state.aiEnabled);
        // Accent 主题下拉，包含明确的高对比度方案
        accentColorCombo = new JComboBox<>();
        for (UiStyle.AccentColor a : UiStyle.AccentColor.values()) {
            accentColorCombo.addItem(a.name());
        }
        accentColorCombo.setSelectedItem(
                (state != null && state.accentColor != null) ? state.accentColor : "BLUE");
        accentColorCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String s) {
                    UiStyle.AccentColor a = UiStyle.parseAccent(s);
                    setText(a.displayName);
                }
                return this;
            }
        });
        gitCheckEnabledBox = new JBCheckBox("启用Git预提交API检查", state == null || state.gitCheckEnabled);
        gitAllowedCodesField = new JBTextField(state != null ? state.gitAllowedStatusCodes : RestAutoLabConstants.DEFAULT_ALLOWED_STATUS_CODES);
        timeoutField = new JBTextField(String.valueOf(state != null ? state.requestTimeout : RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS));
        maxAiCallsField = new JBTextField(String.valueOf(state != null ? state.maxAiCallsPerScan : 50));
        autoScanBox = new JBCheckBox("项目打开时自动扫描API", state == null || state.autoScanOnStartup);
        scanPackageField = new JBTextField(state != null && state.scanPackageFilter != null
                ? state.scanPackageFilter : "");
        scanPackageField.setToolTipText("留空=扫描全部；多个包前缀用逗号分隔，如 com.xxx.sys, com.xxx.admin");

        // AI 提示词自定义
        systemPromptArea = new JBTextArea(state != null ? state.aiSystemPrompt : RestAutoLabConstants.AI_SYSTEM_PROMPT);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        JScrollPane systemPromptScroll = new JScrollPane(systemPromptArea);
        systemPromptScroll.setPreferredSize(new Dimension(520, 110));

        userPromptTemplateArea = new JBTextArea(
                state != null ? state.aiUserPromptTemplate : RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        userPromptTemplateArea.setLineWrap(true);
        userPromptTemplateArea.setWrapStyleWord(true);
        JScrollPane userPromptScroll = new JScrollPane(userPromptTemplateArea);
        userPromptScroll.setPreferredSize(new Dimension(520, 220));

        // Section header style
        JBColor sectionColor = new JBColor(new Color(0x4B, 0x00, 0x82), new Color(0xBB, 0x86, 0xFC));
        Font sectionFont = UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f);

        // ── Section 0: 外观（一伦优化 #9：accent 主题）──
        JBLabel appearanceHeader = new JBLabel("外观");
        appearanceHeader.setForeground(sectionColor);
        appearanceHeader.setFont(sectionFont);
        JBLabel appearanceDesc = new JBLabel("<html><font color='#888888' size='2'>跟随 IDE 明暗主题，并提供默认蓝、翠绿和高对比度方案</font></html>");

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
        JButton resetPromptBtn = UiStyle.button("恢复默认提示词", AllIcons.Actions.Refresh, e -> {
            int confirm = Messages.showYesNoDialog(
                    systemPromptArea,
                    "确定恢复系统提示词和用户提示词模板为默认值吗?\n当前的自定义内容会丢失。",
                    "恢复默认提示词",
                    Messages.getQuestionIcon());
            if (confirm != Messages.YES) return;
            systemPromptArea.setText(RestAutoLabConstants.AI_SYSTEM_PROMPT);
            userPromptTemplateArea.setText(RestAutoLabConstants.AI_DEFAULT_USER_PROMPT_TEMPLATE);
        });
        resetPromptBtn.setToolTipText("恢复系统/用户提示词到默认内容(当前自定义会丢失)");

        // ── Section 3: Git Integration ──
        JBLabel gitHeader = new JBLabel("Git 预提交检查配置");
        gitHeader.setForeground(sectionColor);
        gitHeader.setFont(sectionFont);
        JBLabel gitDesc = new JBLabel("<html><font color='#888888' size='2'>接口测试失败时阻断git commit</font></html>");

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(appearanceHeader)
                .addComponent(appearanceDesc)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Accent 主题:"), accentColorCombo, 1, false)
                .addVerticalGap(16)

                .addComponent(apiHeader)
                .addComponent(apiDesc)
                .addSeparator()
                .addLabeledComponent(new JBLabel("API 基础 URL:"), baseUrlField, 1, false)
                .addLabeledComponent(new JBLabel("请求超时(秒):"), timeoutField, 1, false)
                .addComponent(autoScanBox, 1)
                .addLabeledComponent(new JBLabel("扫描包过滤:"), scanPackageField, 1, false)
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
                .addComponent(buildPromptBottomBar(resetPromptBtn), 1)
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
        RestAutoLabSettingsState s = RestAutoLabSettingsState.getInstance(project);
        RestAutoLabSettingsState.State state = s.getState();
        if (state == null) return true;
        return !fieldText(baseUrlField).equals(state.baseUrl)
                || !fieldText(arkApiUrlField).equals(state.arkApiUrl)
                || !new String(arkApiKeyField.getPassword()).equals(state.arkApiKey)
                || !fieldText(arkApiPathField).equals(state.aiApiPath)
                || !fieldText(arkModelProField).equals(state.arkModelPro)
                || !fieldText(arkModelCodeField).equals(state.arkModelCode)
                || aiEnabledBox.isSelected() != state.aiEnabled
                || !comboSelected(accentColorCombo).equals(state.accentColor == null ? "BLUE" : state.accentColor)
                || gitCheckEnabledBox.isSelected() != state.gitCheckEnabled
                || !fieldText(gitAllowedCodesField).equals(state.gitAllowedStatusCodes)
                || autoScanBox.isSelected() != state.autoScanOnStartup
                || !fieldText(scanPackageField).equals(state.scanPackageFilter == null ? "" : state.scanPackageFilter)
                || !fieldText(timeoutField).equals(String.valueOf(state.requestTimeout))
                || !fieldText(maxAiCallsField).equals(String.valueOf(state.maxAiCallsPerScan))
                || !systemPromptArea.getText().equals(state.aiSystemPrompt)
                || !userPromptTemplateArea.getText().equals(state.aiUserPromptTemplate);
    }

    @Override
    public void apply() {
        RestAutoLabSettingsState s = RestAutoLabSettingsState.getInstance(project);
        s.setBaseUrl(fieldText(baseUrlField));
        s.setArkApiUrl(fieldText(arkApiUrlField));
        s.setArkApiKey(new String(arkApiKeyField.getPassword()));
        s.setAiApiPath(fieldText(arkApiPathField));
        s.setArkModelPro(fieldText(arkModelProField));
        s.setArkModelCode(fieldText(arkModelCodeField));
        s.setAiEnabled(aiEnabledBox.isSelected());
        s.setAccentColor(comboSelected(accentColorCombo));
        s.setGitCheckEnabled(gitCheckEnabledBox.isSelected());
        s.setGitAllowedStatusCodes(fieldText(gitAllowedCodesField));
        s.setRequestTimeout(parseInt(fieldText(timeoutField), RestAutoLabConstants.HTTP_REQUEST_TIMEOUT_SECONDS));
        s.setMaxAiCallsPerScan(parseInt(fieldText(maxAiCallsField), 50));
        s.setAutoScanOnStartup(autoScanBox.isSelected());
        s.setScanPackageFilter(fieldText(scanPackageField));
        s.setAiSystemPrompt(systemPromptArea.getText());
        s.setAiUserPromptTemplate(userPromptTemplateArea.getText());
    }

    @Override
    public void reset() {
        RestAutoLabSettingsState s = RestAutoLabSettingsState.getInstance(project);
        RestAutoLabSettingsState.State state = s.getState();
        if (state == null) return;
        baseUrlField.setText(state.baseUrl);
        arkApiUrlField.setText(state.arkApiUrl);
        arkApiKeyField.setText(state.arkApiKey);
        arkApiPathField.setText(state.aiApiPath != null ? state.aiApiPath : "");
        arkModelProField.setText(state.arkModelPro);
        arkModelCodeField.setText(state.arkModelCode);
        aiEnabledBox.setSelected(state.aiEnabled);
        accentColorCombo.setSelectedItem(state.accentColor == null ? "BLUE" : state.accentColor);
        gitCheckEnabledBox.setSelected(state.gitCheckEnabled);
        gitAllowedCodesField.setText(state.gitAllowedStatusCodes);
        timeoutField.setText(String.valueOf(state.requestTimeout));
        maxAiCallsField.setText(String.valueOf(state.maxAiCallsPerScan));
        autoScanBox.setSelected(state.autoScanOnStartup);
        scanPackageField.setText(state.scanPackageFilter == null ? "" : state.scanPackageFilter);
        systemPromptArea.setText(state.aiSystemPrompt);
        userPromptTemplateArea.setText(state.aiUserPromptTemplate);
    }

    private static String fieldText(JTextField f) { return f.getText().trim(); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }

    /**
     * AI 提示词区底部按钮行:[恢复默认]
     * 「恢复默认」靠左(roundRect 描边样式,带 Refresh 图标,加 yes/no 防误操作)
     * 「保存」由 IDEA 右下角标准 OK/Apply 按钮承担,不在此处重复
     */
    private static JPanel buildPromptBottomBar(JButton resetBtn) {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(JBUI.Borders.emptyTop(4));

        JPanel leftBox = new JPanel();
        leftBox.setLayout(new BoxLayout(leftBox, BoxLayout.X_AXIS));
        leftBox.setOpaque(false);
        leftBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftBox.add(resetBtn);

        bar.add(leftBox);
        bar.add(Box.createHorizontalGlue());

        return bar;
    }
    private static String comboSelected(JComboBox<?> c) {
        Object o = c.getSelectedItem();
        return o == null ? "" : o.toString();
    }
}
