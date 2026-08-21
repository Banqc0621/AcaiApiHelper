# RestAutoLab 异常处理归档

- 归档时间：2026-08-21
- 来源：群聊中历次报错提单与修复（一伦优化 #45–#52 + 早期版本加固）
- 配套文档：设计优化要求见 `design-optimizations.md`
- 格式：异常现象 → 根因 → 修复方案 → 证据提交

## 一、平台线程与生命周期类

| 异常 | 根因 | 修复 | 证据 |
|---|---|---|---|
| 「跳转到源码失败：Access is allowed from write thread only」 | 在 EDT 上直接执行 `LocalFileSystem.refresh(false)` 全量同步 VFS 刷新，内部需写锁，EDT 只持读权限 | 全量刷新彻底移除；文件解析（VFS 查找 + 按文件名回退）移到后台线程池，用 `refreshAndFindFileByPath` 定向刷新；完成后 `invokeLater` 回 EDT 打开编辑器，带 `project.isDisposed()` 保护 | `7e68577` |
| 按钮卡死在 loading 态 | `executeRequest` 异常未兜底，`endLoading` 不一定被调用 | `HttpExecutorService.executeRequest` 包 try/catch，失败转 `TestResult.fromError`，保证 `endLoading` 一定执行 | `cf2951f` |
| 请求与面板状态不一致（闭包 race） | `currentApi`/`baseUrlField`/`currentAssertions` 为字段，`executeOnPooledThread` 后 EDT 可能已改字段 | 改为 final 局部变量在线程切换前捕获 | `cf2951f` |
| 禁用→启用后按钮文字被灰色锁死 | `attachInteractionFeedback` 恢复前景色写成 `setForeground(btn.getForeground())` no-op；监听器重复挂载多次回调 | 缓存 baseForeground 正确恢复；clientProperty 哨兵防重复挂载；loading 结束保存/恢复 cursor 不强制抹 default；魔字符串提为命名常量 | `75e5293` |
| 「跳转到源码」IllegalArgumentException | 非法行号传给编辑器 | 非法行号退化为不指定行 | `77c57ee` |
| 「调试此接口」编译失败/运行异常 | `Consumer.accept(api, null)` 签名错误；回调无异常保护 | 修正签名；回调包 try-catch，异常记日志并弹错误对话框 | `77c57ee` |

## 二、IntelliJ 平台 API 陷阱（高复用价值）

| 陷阱 | 现象 | 正确做法 | 证据 |
|---|---|---|---|
| 右键包拿到的是 `PsiDirectory` 不是 `PsiPackage` | 右键菜单项 `update()` 判 `instanceof PsiPackage` 永远不显示 | 经 `JavaDirectoryService.getInstance().getPackage(directory)` 反查包名；同时兼容 `PsiPackage` 与多选 `PSI_ELEMENT_ARRAY`；`update()` 与执行逻辑共用同一解析 | `573b2ed` |
| DarculaButtonUI 忽略组件 setBackground/setForeground | 主按钮明暗主题图标/文字不可见，hover/按下/禁用三态失效 | LaF 只认 `JButton.backgroundColor`/`JButton.textColor` client property（2026.1 SDK 字节码确认）；但**最终决策是不再自绘背景**，全部交回 LaF 原生渲染 | `a23e718`→`8126d50` |
| roundRect 按钮背景 LaF 自绘且忽略 foreground | 浅色主题白字白底不可见 | `button()` 不设 roundRect；后经 #49 收敛为 LaF 默认外观 | `de44c4b`→`8126d50` |
| `setPrototypeDisplayValue` 泛型约束 | `DefaultComboBoxModel<Environment>` 传 String 直接 javac 报错 | 传 Environment 临时实例按目标宽度算 | `586240d` |
| envCombo 重建期间误触发切换 | `removeAllItems`/`setSelectedItem` 触发 ActionListener 覆盖激活状态 | `suppressEnvComboAction` 标志抑制重建期回调 | `eae5fe2`、v1.0.3 |
| IDE 反射实例化包级可见类 IllegalAccessException | plugin.xml 注册的 Action/Factory 无法实例化 | 收进**公开静态嵌套类**，plugin.xml class 指向 `Outer$Inner` | `1165d61` |
| EDT 上序列化/写盘大文件卡死（Windows 明显） | 导出时文件对话框弹不出，疑似卡死 | 导出走 `Task.Backgroundable` 后台线程 + 进度提示 | `3e142aa` |

## 三、提单 8 条异常（#45，`7c4d061`）

1. **URL 缺斜杠**：类级与方法级路径拼接改用 `joinPaths`，缺斜杠补齐、双斜杠折叠。
2. **指定包扫描**：新增「扫描包过滤」设置项，扫描时只保留命中控制器。
3. **字段注释不显示**：复核确认此前已修复。
4. **切 JSON 格式不生效**：格式下拉加监听，切到 JSON 自动格式化内容并同步 Content-Type 头。
5. **扫描出不存在的 API**：过滤 `org.springdoc` / `springfox` / `io.swagger` / `actuator` 内置端点。
6. **快速跳转无效**：VFS 刷新重试 + 按文件名在项目内回退查找（同包后缀优先）。
7. **参数解析 bug**：`appId` 等 Id 后缀默认值误匹配修复；BODY DTO 参数表递归展开为点号路径行。
8. **DTO 文档不展开**：复核确认此前已修复。

## 四、扫描健壮性（v1.0.3 扫描增强，防接口丢失）

- 路径占位符 `${key:default}` 取默认值、`${key}` 取变量名，仅 `#{...}` SpEL 丢弃。
- 组合注解控制器按类级 `@RequestMapping` 补充搜索；方法级 `@*Mapping` 反向定位兜底。
- 多值注解 `{"/list","/query"}` 完整展开为多个接口（此前只取第一个）。
- 去重键改 `控制器名#方法名(参数签名)|METHOD|URL`，方法重载不再被误去重。
- Kotlin `KtLightClass`/局部类 qfn 为 null 时用「类名 + 源文件路径」兜底，不整类丢弃。
- 移除「实现 ErrorController 即整类丢弃」误杀逻辑，BasicErrorController 走黑名单。
- `static final String` 常量引用路径用 PSI 语义解析实际值。
- 多类级基路径 `@RequestMapping({"/api/v1","/api/v2"})` 笛卡尔积展开。
- 全链路诊断日志：候选数 → 去重数 → 产出数；0 接口区分「无映射注解」与「全被过滤」。

## 五、数据与导出类

- **AI 请求体双重转义乱码**（v1.0.3）：嵌套对象先被序列化为 JSON 字符串存入 Map，再次 `gson.toJson` 二次转义。修复：`mapToNestedJson` 解析回真实结构后单次美化输出。
- **AI 配置导出为默认值**（v1.0.4）：`arkModelCode` 等字段导出默认值伪装实时值；统一 `fillAiSettings`，DTO 去除默认值。
- **环境下拉 ✓ 永远指向开发环境**（v1.0.4）：`Environment.toString()` 依赖冗余 active 字段；改用渲染器实时跟随选中项。
- **Word（n）标序串号**（#41/#43）：多 w:num 共享 abstractNumId 被 Word 合并计数器 → startOverride=1 → 最终改独立 abstractNum 彻底修复。
- **模板导出「没效果」**（#44）：用户模板不含 `${api.xxx}` 占位符；加 `hasPlaceholders` 检测 + 内置示例模板 + 弹窗说明；DOCX 兼容占位符被 Word 拆到多个 run。
- **包扫描过滤误匹配**（#50）：`com.foo` 误命中 `com.foobar.*`；改包段边界匹配 + 13 项单测。
- **jar 包内 Controller 双击跳转**（#52 附带发现）：第三方库 class 无源文件，走「找不到源文件」回退提示，不再报线程错误；如需整体排除库内端点可加过滤（未实施，待需求确认）。
- **包重命名后编译失败**（`3cf25d4`）：8 处残留旧包名 import 清理；`com.ban.acai.` 字符串判断同步改 `com.hronline.`，否则导出时 Result<T> 包装识别失败。

## 六、请求状态机异常约定（设计规格固化）

- 状态机：`idle → running → succeeded | failed | cancelled`。
- 异常必须转换为可展示错误结果，**不得**把按钮永久留在禁用/loading 态。
- `cancelled` 必须终止或忽略后台结果；迟到回调由请求序号丢弃。
- 取消结果标记 `TestStatus.CANCELLED`，防止取消误报失败。
- 前置脚本错误在请求发出前阻断；脚本只在环境副本生效，不污染项目环境。

## 复盘原则（后续开发必须遵守）

1. **EDT 只做展示**：VFS/IO/序列化一律后台线程，回 EDT 前查 `project.isDisposed()`。
2. **平台行为先字节码验证再写代码**：LaF 渲染规则以 UI 类实现为准，组件级 set 可能被忽略。
3. **报错先定位根因再修**：#47→#49 三轮按钮配色迭代证明，绕过 LaF 自绘会持续产生新 bug，收敛到平台默认才是稳态。
4. **修复必须配回归测试**：#50 配 13 项、#48/#49 配像素级双主题断言、#26 配无头布局三宽度定位测试。
5. **用户报错截图里的堆栈要核对构建时间**：旧构建残留堆栈不代表当前代码（#52 复核经验）。
