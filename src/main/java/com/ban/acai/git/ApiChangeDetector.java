package com.ban.acai.git;

import com.ban.acai.model.ApiDefinition;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 接口 Git 变更检测器 —— 用于「最新」过滤。
 *
 * <p>判定一个接口是否「最近有变更」的逻辑：
 * <ol>
 *   <li>获取最近 N 天 Git 变更过的文件集合（已提交的 {@code git log} + 本地未提交的 {@link ChangeListManager}）</li>
 *   <li>对每个接口，通过 PSI 解析其 Controller 方法，收集方法体内涉及的全栈文件：
 *       <ul>
 *         <li>Controller 方法所在源文件本身</li>
 *         <li>方法体内所有方法调用（Service/Mapper 层）所在文件</li>
 *         <li>方法体内所有 new 表达式、引用到的实体类（DTO/VO/Entity）所在文件</li>
 *       </ul>
 *       这样覆盖了「该接口涉及的全栈逻辑」——只要 Controller、被调 Service、请求/响应实体任一文件近期被改动，该接口即视为「最新」。</li>
 *   <li>若接口关联文件集合与变更文件集合有交集，则判定为近期有变更。</li>
 * </ol></p>
 *
 * <p>性能：变更文件集合带 60 秒 TTL 缓存；接口关联文件按 uniqueKey 缓存，扫描完成后清空。
 * PSI 分析只遍历方法体语法树（不递归进被调方法的方法体），单接口开销可控。
 * 整体 {@link #filterChangedApis} 调用应在后台线程执行（git 命令与 PSI 读取不宜在 EDT）。</p>
 */
@Service(Service.Level.PROJECT)
public final class ApiChangeDetector {
    private static final Logger LOG = Logger.getInstance(ApiChangeDetector.class);

    /** 变更文件缓存 TTL（毫秒），避免频繁执行 git log */
    private static final long CHANGED_FILES_CACHE_TTL_MS = 60_000L;

    private final Project project;

    /** 最近变更文件绝对路径集合缓存 */
    private volatile Set<String> changedFilesCache = Collections.emptySet();
    private volatile long changedFilesCacheTime = 0L;
    private volatile int changedFilesCacheDays = -1;

    /** 接口关联文件缓存：uniqueKey → 关联文件绝对路径集合。扫描完成后清空。 */
    private final Map<String, Set<String>> relatedFilesCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ApiChangeDetector(@NotNull Project project) {
        this.project = project;
    }

    public static ApiChangeDetector getInstance(@NotNull Project project) {
        return project.getService(ApiChangeDetector.class);
    }

    /**
     * 获取最近 {@code days} 天有 Git 变更的文件绝对路径集合（已提交 + 本地未提交）。
     * <p>带 60 秒 TTL 缓存。</p>
     */
    public Set<String> getRecentlyChangedFiles(int days) {
        long now = System.currentTimeMillis();
        if (changedFilesCacheDays == days
                && !changedFilesCache.isEmpty()
                && now - changedFilesCacheTime < CHANGED_FILES_CACHE_TTL_MS) {
            return changedFilesCache;
        }
        Set<String> files = new HashSet<>();
        // 1. 本地未提交变更（工作区改动）
        try {
            ChangeListManager clm = ChangeListManager.getInstance(project);
            for (java.io.File file : clm.getAffectedPaths()) {
                if (file != null) files.add(file.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.warn("[ApiChangeDetector] 获取本地变更失败: " + e.getMessage());
        }
        // 2. 已提交变更（git log --since）
        try {
            collectGitLogChangedFiles(days, files);
        } catch (Exception e) {
            LOG.warn("[ApiChangeDetector] git log 获取变更失败: " + e.getMessage());
        }
        Set<String> immutable = Collections.unmodifiableSet(files);
        changedFilesCache = immutable;
        changedFilesCacheTime = now;
        changedFilesCacheDays = days;
        return immutable;
    }

    /** 执行 git log --since 收集每个仓库最近 days 天的已提交变更文件 */
    private void collectGitLogChangedFiles(int days, Set<String> out) {
        GitRepositoryManager mgr = GitRepositoryManager.getInstance(project);
        if (mgr == null) return;
        List<GitRepository> repos = mgr.getRepositories();
        if (repos.isEmpty()) return;
        Git git = Git.getInstance();
        for (GitRepository repo : repos) {
            VirtualFile root = repo.getRoot();
            String rootPath = root.getPath();
            GitLineHandler handler = new GitLineHandler(project, root, GitCommand.LOG);
            // --since=N.days.ago 无空格，避免参数拆分问题
            handler.addParameters("--since=" + days + ".days.ago",
                    "--name-only", "--pretty=format:", "--no-merges");
            GitCommandResult result = git.runCommand(handler);
            if (!result.success()) {
                LOG.warn("[ApiChangeDetector] git log 失败: " + result.getErrorOutput());
                continue;
            }
            for (String line : result.getOutput()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // git log 输出相对仓库根的路径，拼接为绝对路径
                out.add(rootPath + "/" + trimmed);
            }
        }
    }

    /**
     * 获取接口关联的全栈文件绝对路径集合。
     * <p>包含 Controller 源文件 + 方法体内调用/引用到的所有类所在文件。
     * 结果按 uniqueKey 缓存，{@link #onScanComplete} 后清空。</p>
     */
    public Set<String> getRelatedFiles(ApiDefinition api) {
        String key = api.uniqueKey();
        Set<String> cached = relatedFilesCache.get(key);
        if (cached != null) return cached;
        Set<String> files = new HashSet<>();
        if (api.getSourceFilePath() != null && !api.getSourceFilePath().isBlank()) {
            files.add(api.getSourceFilePath());
        }
        // PSI 读取必须在 read action 内
        ApplicationManager.getApplication().runReadAction(() -> collectRelatedFilesViaPsi(api, files));
        Set<String> immutable = Collections.unmodifiableSet(files);
        relatedFilesCache.put(key, immutable);
        return immutable;
    }

    /**
     * PSI 分析：定位 Controller 方法，遍历方法体语法树，收集所有调用与引用涉及的全栈文件。
     * <p>用 {@link JavaRecursiveElementVisitor} 递归遍历当前方法体内的所有语法元素：
     * 方法调用、new 表达式、引用表达式都会被访问到（无论嵌套多深），
     * 但不会进入被调方法的方法体——这正好对应「该接口直接/间接调用的全栈逻辑」。</p>
     */
    private void collectRelatedFilesViaPsi(ApiDefinition api, Set<String> files) {
        try {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(api.getSourceFilePath());
            if (vf == null) return;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return;
            PsiMethod targetMethod = findMethodByLineNumber(psiFile, api.getSourceLineNumber());
            if (targetMethod == null) return;

            targetMethod.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitCallExpression(@NotNull PsiCallExpression call) {
                    super.visitCallExpression(call);
                    PsiMethod resolved = call.resolveMethod();
                    if (resolved != null) addMemberFile(resolved);
                }

                @Override
                public void visitNewExpression(@NotNull PsiNewExpression newExpr) {
                    super.visitNewExpression(newExpr);
                    PsiMethod resolved = newExpr.resolveMethod();
                    if (resolved != null) addMemberFile(resolved);
                    PsiJavaCodeReferenceElement ref = newExpr.getClassReference();
                    if (ref != null) {
                        PsiElement target = ref.resolve();
                        if (target instanceof PsiClass) addClassFile((PsiClass) target);
                    }
                }

                @Override
                public void visitReferenceExpression(@NotNull PsiReferenceExpression expr) {
                    super.visitReferenceExpression(expr);
                    PsiElement target = expr.resolve();
                    if (target instanceof PsiClass) {
                        addClassFile((PsiClass) target);
                    } else if (target instanceof PsiField) {
                        PsiClass containing = ((PsiField) target).getContainingClass();
                        if (containing != null) addClassFile(containing);
                    }
                }

                private void addMemberFile(PsiMember member) {
                    PsiFile f = member.getContainingFile();
                    if (f != null && f.getVirtualFile() != null) {
                        files.add(f.getVirtualFile().getPath());
                    }
                }

                private void addClassFile(PsiClass cls) {
                    // 跳过 JDK/第三方库类（无本地源文件）
                    PsiFile f = cls.getContainingFile();
                    if (f != null && f.getVirtualFile() != null) {
                        files.add(f.getVirtualFile().getPath());
                    }
                }
            });
        } catch (Exception e) {
            LOG.warn("[ApiChangeDetector] PSI 分析关联文件失败: " + api.getUrl() + " - " + e.getMessage());
        }
    }

    /** 根据行号在 PsiFile 中定位 PsiMethod（行号 1-based，与扫描时写入 sourceLineNumber 一致） */
    private PsiMethod findMethodByLineNumber(PsiFile psiFile, int lineNumber) {
        if (lineNumber <= 0) return null;
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null || lineNumber > document.getLineCount()) return null;
        int offset = document.getLineStartOffset(lineNumber - 1);
        PsiElement element = psiFile.findElementAt(offset);
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }

    /** 判断接口最近 {@code days} 天是否有变更 */
    public boolean isApiChangedRecently(ApiDefinition api, int days) {
        Set<String> changed = getRecentlyChangedFiles(days);
        Set<String> related = getRelatedFiles(api);
        for (String r : related) {
            if (changed.contains(r)) return true;
        }
        return false;
    }

    /**
     * 批量过滤出最近 {@code days} 天有变更的接口。
     * <p><b>应在后台线程调用</b>（内部执行 git 命令与 PSI 读取）。</p>
     */
    public List<ApiDefinition> filterChangedApis(List<ApiDefinition> apis, int days) {
        Set<String> changed = getRecentlyChangedFiles(days);
        List<ApiDefinition> result = new ArrayList<>();
        for (ApiDefinition api : apis) {
            Set<String> related = getRelatedFiles(api);
            boolean hit = false;
            for (String r : related) {
                if (changed.contains(r)) { hit = true; break; }
            }
            if (hit) result.add(api);
        }
        return result;
    }

    /** 扫描完成后调用，清空关联文件缓存（接口方法体可能变化） */
    public void onScanComplete() {
        relatedFilesCache.clear();
    }

    /** 强制清空变更文件缓存（手动刷新时） */
    public void invalidateChangedFilesCache() {
        changedFilesCache = Collections.emptySet();
        changedFilesCacheTime = 0L;
        changedFilesCacheDays = -1;
    }
}