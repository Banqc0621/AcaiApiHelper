package com.hronline.git;

import com.hronline.http.HttpExecutorService;
import com.hronline.model.*;
import com.hronline.scanner.ApiScannerService;
import com.hronline.settings.RestAutoLabSettingsState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class RestAutoLabCheckinHandler extends CheckinHandler {

    /** plugin.xml 反射实例化入口，必须公开。 */
    public static final class Factory extends CheckinHandlerFactory {
        @Override
        @NotNull
        public CheckinHandler createHandler(@NotNull CheckinProjectPanel panel, @NotNull CommitContext context) {
            return new RestAutoLabCheckinHandler(panel.getProject(), panel);
        }
    }

    private static final Logger LOG = Logger.getInstance(RestAutoLabCheckinHandler.class);
    private final Project project;
    private final CheckinProjectPanel panel;
    private final RestAutoLabSettingsState settings;
    private boolean skipCheck = false;

    RestAutoLabCheckinHandler(Project project, CheckinProjectPanel panel) {
        this.project = project;
        this.panel = panel;
        this.settings = RestAutoLabSettingsState.getInstance(project);
    }

    @Nullable
    @Override
    public RefreshableOnComponent getBeforeCheckinConfigurationPanel() {
        if (!settings.isGitCheckEnabled()) return null;

        JPanel panel = new JPanel(new BorderLayout());
        JCheckBox checkBox = new JCheckBox("执行 RestAutoLab 接口检查", true);
        panel.add(checkBox, BorderLayout.WEST);
        return new RefreshableOnComponent() {
            @Override
            public JComponent getComponent() { return panel; }
            @SuppressWarnings("deprecation")
            @Override
            public void refresh() {}
            @Override
            public void saveState() { skipCheck = !checkBox.isSelected(); }
            @Override
            public void restoreState() { checkBox.setSelected(!skipCheck); }
        };
    }

    @Override
    @NotNull
    public ReturnResult beforeCheckin() {
        if (!settings.isGitCheckEnabled()) return ReturnResult.COMMIT;
        if (skipCheck) return ReturnResult.COMMIT;

        ApiScannerService scanner = ApiScannerService.getInstance(project);
        List<ApiDefinition> allApis = scanner.getCachedApis();
        if (allApis.isEmpty()) return ReturnResult.COMMIT;

        Set<String> changedFiles = new HashSet<>();
        for (VirtualFile vf : panel.getVirtualFiles()) changedFiles.add(vf.getPath());

        List<ApiDefinition> affected = new ArrayList<>();
        for (ApiDefinition api : allApis) {
            if (changedFiles.contains(api.getSourceFilePath())) affected.add(api);
        }
        if (affected.isEmpty()) {
            LOG.info("本次提交未涉及Controller文件");
            return ReturnResult.COMMIT;
        }

        LOG.info("检测到 " + affected.size() + " 个受影响API");
        HttpExecutorService httpService = HttpExecutorService.getInstance(project);
        TestProfile profile = new TestProfile();
        profile.setName("Git预提交检查");
        profile.setBaseUrl(settings.getBaseUrl());
        Set<Integer> allowedCodes = settings.getAllowedStatusCodes();

        TestReport report = httpService.executeBatchTest(affected, profile);

        List<TestResult> failed = new ArrayList<>();
        for (TestResult r : report.getResults()) {
            // 使用接口自身的预期状态码判定，而非全局一刀切
            ApiDefinition api = r.getApiDefinition();
            Set<Integer> allowedForApi = api.getExpectedStatusCodes();
            boolean passed;
            if (allowedForApi != null && !allowedForApi.isEmpty()) {
                passed = allowedForApi.contains(r.getStatusCode()) && r.getStatus() == TestStatus.PASSED;
            } else {
                passed = allowedCodes.contains(r.getStatusCode()) && r.getStatus() == TestStatus.PASSED;
            }
            if (!passed) {
                failed.add(r);
            }
        }

        if (failed.isEmpty()) {
            return ReturnResult.COMMIT;
        }

        return showFailureDialog(report, failed, allowedCodes);
    }

    private ReturnResult showFailureDialog(TestReport report, List<TestResult> failed, Set<Integer> allowedCodes) {
        StringBuilder msg = new StringBuilder();
        msg.append("⚠ RestAutoLab 预提交检查未通过\n");
        msg.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        msg.append("检测到 ").append(failed.size()).append(" 个接口测试失败（允许状态码: ").append(allowedCodes).append("）\n\n");
        for (TestResult r : failed) {
            msg.append("✗ ").append(r.getApiDefinition().displayLabel()).append("\n");
            msg.append("  状态码: ").append(r.getStatusCode()).append(" | 耗时: ").append(r.getDurationMs()).append("ms\n");
            if (!r.getErrorMessage().isBlank()) msg.append("  错误: ").append(r.getErrorMessage()).append("\n");
            msg.append("\n");
        }

        ReturnResult[] result = new ReturnResult[]{ReturnResult.CANCEL};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            int choice = Messages.showYesNoDialog(
                    project, msg.toString(),
                    "RestAutoLab 预提交检查失败",
                    "取消提交", "忽略并继续提交",
                    Messages.getWarningIcon()
            );
            result[0] = (choice == Messages.NO) ? ReturnResult.COMMIT : ReturnResult.CANCEL;
        });
        return result[0];
    }
}
