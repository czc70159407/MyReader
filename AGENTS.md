# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源入口，面向 agent 驱动开发：任何会话先读「当前状态」「工作规则」「文档导航」，再**按任务按需**查阅 docs/ 分册，避免一次性加载全部设计细节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

### 最近状态

- **M13 批量操作（2026-11-10）**：书架「多选 → 批量移动 / 批量删除」。入口：浏览态长按卡片菜单「多选」（预选该卡）/ ⋮ 菜单「多选」（搜索态不提供）；多选顶栏（✕ /「已选 {n} 项」/ 全选 / 移动 / 删除）+ 卡片选择态（`Role.Checkbox` + `contentDescription` + 勾选覆盖层）；批量移动复用 `FolderPickerDialog`（防环 = 选中文件夹子树并集，红字 + 确定禁用）；批量删除混合后果文案 + 单事务删记录 + 物理文件后置删。核心：`core:model/Batch.kt`（`BatchMoveResult`/`BatchDeleteResult`/`MAX_BATCH_SELECTION=500`）+ `LibraryRepository.moveItems/deleteItems` + DAO 批量查询（**无 schema 变更**）。`.\gradlew.bat build` 全绿；`LibraryRepositoryBatchTest`（8 例）+ `SelectionTest`（5 例）+ `LibraryComponentsTest` 多选态 a11y 断言全绿。**真机验收通过（PKC110 / Android 17 / API 37，2026-11-10）**：长按菜单 + ⋮ 菜单两入口进多选；顶栏「已选 n 项」+ 全选/移动/删除；卡片 dump 为 `CheckBox` + `content-desc`（文件名/文件夹名）+ `checked` 状态；多选态隐藏最近阅读条；全选 2 项 + 取消全选；批量移动（选文件夹自身红字「不能移动到自身或其子目录」+ 确定禁用；移文档到文件夹 Snackbar「已移动 1 个文档」）；批量删除（文件夹确认框「将删除 1 个文件夹，其中的文档将移至根目录」→ 删除后内部文档回根 + Snackbar「已删除 1 个文件夹」；文档确认框「物理文件一并删除」→ Snackbar「已删除 1 个文档」）；操作后自动退出多选。证据：`/sdcard/Download/MyReaderTest/{xml,png}/m13_*`（16 项），本地副本 `tools/acceptance/verify/m13_*`。
- **应用图标与启动画面（D11，2026-11-10 观感升级）**：图标由「描边折角文档 + 深绿 `#1B5E20`」改为**实心「打开的书」+ 深墨绿 `#103D2C` / 奶油白 `#F5F1E4`**（原 4dp 描边在 48dp 下过细、且与系统文件图标语汇雷同）；SplashScreen 背景同步换色。`.\gradlew.bat build` 全绿、aapt 验证 APK 各密度均指向 adaptive icon。**真机验收通过（PKC110 / Android 17 / API 37；设备近期 OTA，历史验收记录为 Android 16）**：桌面图标实心书清晰、48dp 辨识度好；冷启动 SplashScreen 中心色实测 `#103D2C`。注：ColorOS launcher 覆盖安装后缓存旧图标，需 `am force-stop com.android.launcher` 才刷新。路线 C 不变（图标资源色独立于 UI 主题），色值见 `docs/design/tokens.md` §1.1，实现落点见 `docs/ui-design.md`「应用图标与启动画面」。证据：`/sdcard/Download/MyReaderTest/m13/png/{launcher_final2,boot_v2_1,boot_v2_2}.png`，本地副本 `tools/acceptance/verify/m13/`。
- **关于页（2026-11-09）**：应用标识 + 运行读取 `versionName` + 五个链接可点击/长按复制/链接语义已落地，`.\gradlew.bat build` 全绿。**真机验收通过（PKC110 / Android 17 / API 37，2026-11-09；设备近期 OTA，历史验收记录为 Android 16）**：版本号显示 `1.5.0`；五链接 UI dump 均为 `Button` + `focusable/clickable/long-clickable`，CD 为「标签，链接。双击打开，长按复制」；长按出 Snackbar「链接已复制」；点击打开系统浏览器且 URL 正确；字体 200% 下 URL 换行无裁切、可滚至末条；深浅主题配色随 `colorScheme` 变化。`[建议 P2]` 已闭环——修正与上游口径不符的 GitLab 链接，分列本项目主仓 / MuPDF 上游 / 实际集成的 viewer fork（直达 `myreader-patches` 分支）。**N/A 一项**：无浏览器兜底无法真机构造（系统浏览器 `pm disable-user` 返回 enabled、`pm hide` 需 `MANAGE_USERS`、suspend 后 intent 仍可解析），代码层已有反编译 `ui-android:1.7.4` 实证 + build 门禁覆盖。证据：`/sdcard/Download/MyReaderTest/m13/{png,xml}/`（21 项），本地副本 `tools/acceptance/verify/m13/`。规格 `docs/design/screens/about.md`，文案 `docs/design/copy.md` §10。

- **M12（v1.5）已完成并真机验收通过（2026-11-01~03）**：沉浸模式（顶栏 overlay + fade 显隐，7 条真机通过）+ 高亮/笔记（Room schema **v3** + `MIGRATION_2_3`、备份 **v4**，含 5 色板 / 笔记编辑 / 顶栏收纳「标注」双 Tab）。**已知限制（已拍板不做）**：①epub 水平模式自绘 overlay；②归一化 quad 跨重排页级近似提升；③epub 未端到端真机验证（SAF「最近」不索引 `.epub`，以 PDF 垂直同代码路径覆盖）。详见 [docs/m12-plan.md](docs/m12-plan.md)。

- **M11（v1.4）已完成并真机验收通过（2026-10-31）**：epub 比例锚点 `cp:<chapter>:<章内页>:<章页数>` + `EpubProgressCodec` v2 + 备份 `schemaVersion=3`；六项真机验收通过并修 2 缺陷。详见 [docs/m11-plan.md](docs/m11-plan.md)。

- **P0 detekt 存量清零（2026-10-31）**：173 条 baseline 全部清零，9 个 `detekt-baseline.xml` 删除，detekt 回归纯 config 门禁。详见 [docs/detekt-baseline-reduction.md](docs/detekt-baseline-reduction.md)。

### 里程碑状态表

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + Maven fitz | 已完成 |
| M2 | 数据层（Room/Hilt） | 已完成 |
| M3 | 导入与书架 | 已完成 |
| M4 | 阅读器（水平模式） | 已完成 |
| M5 | 目录树与文档管理 | 已完成 |
| M6 | 搜索/排序/最近阅读 | 已完成 |
| M7 | 垂直滚动 + 设置（v1.1） | 已完成 |
| M8 | 备份与恢复（v1.1） | 已完成 |
| M9 | 多格式支持（EPUB/CBZ/XPS + TXT 扩展点，v1.2） | 已完成（插桩 9/9；修 2 bug；XPS 正向渲染按 R3 待真实样本） |
| 安全审计与加固 | 全仓 code 安全审查 + 按清单修复 | 已完成（build SUCCESSFUL、双变体单测 322 用例 0 失败；R8 runtime 冒烟已关闭） |
| M10 | 书签（章节级）+ 首个 Room Migration（v1.3） | 已完成（schema v2 + `MIGRATION_1_2`；D 真机五项通过并修 2 缺陷） |
| M11 | epub 锚点稳健性 / 比例锚点（v1.4） | 已完成（`cp:` 比例锚点 + 备份 v3；六项真机验收通过并修 2 缺陷） |
| M12 | 沉浸模式 + 高亮/笔记（v1.5） | 已完成（A 沉浸模式 7 条真机通过；B 高亮/笔记 Room v3 + 备份 v4，两项低价值项拍板不做） |
| M13 | 批量操作（多选/批量移动/批量删除） | 已完成（真机验收通过 + `build` 全绿 + 单测/a11y 全绿） |

> 状态取值：未开始 / 进行中 / 已完成。标记「已完成」必须附验收证据（命令输出摘要），并同步更新「下一步」。里程碑任务与验收标准详见 docs/process.md。

### 环境与卡点

- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要；③**GitHub 443 间歇阻断**（本机 DNS 正常，TCP 握手时通时断，报 `443 after 21s` / `Empty reply from server` / `Connection was reset`）——`git push/pull` 会失败但**本地提交不受影响**；应对：先 `Test-NetConnection github.com -Port 443 -InformationLevel Quiet` 判可达，再**按分支分步推送**、间隔 15~30s 重试（一次推一条比连推两条成功率高），仍不行则需开代理。判据是链路层，不要误判为仓库或权限问题

### 历史归档

> M0~M12 逐会话验收证据、踩坑记录、决策与已知限制已归档至 [docs/history.md](docs/history.md)（历史快照，只增不改，非开发事实源）。需追溯历史细节时按需查阅。

## 工作规则（硬约束）

1. **MuPDF 边界**：MuPDF 具体类只允许出现在 `feature:viewer-mupdf` 与 `third-party:mupdf`；`feature:reader`/`feature:library` 只依赖 `core:document` 接口。新增格式 = 新建 `feature:viewer-xxx` + `@FileTypeKey`，不改 reader 与 schema
2. **AGPL 合规**：APP 整体开源；README + About 页注明 MuPDF 来源与 AGPL 全文链接；不引入 AGPL 不兼容的闭源组件
3. **Room 迁移纪律**：schema JSON 随仓库提交；禁用 `fallbackToDestructiveMigration`（阅读进度是核心数据）；改 schema 必须写 Migration
4. **完成定义**：代码变更须通过对应里程碑「验收」（见 docs/process.md）+ `.\gradlew.bat build` 无错，才算完成；声称完成必须附证据
5. **文档同步**：schema/模块结构/依赖版本/接口签名变更，同一任务内更新对应分册（导航见下方「文档导航」）；状态、验收证据、卡点只更新本文件
6. **不过度工程**：不引入文档未列出的依赖/模块；确需偏离选型，先改 docs/architecture.md「技术选型」章节并写明理由
7. **测试下沉**：纯逻辑放 `core:*` 做 JVM 测试；改 `core:data`/`core:storage` 必须补跑对应单测
8. **命令环境**：Windows PowerShell；clone 后执行 `git submodule update --init`——**勿加 `--recursive`**：viewer 的嵌套 jni 子模块刻意不初始化以走 fitz Maven 预编译分支（见 docs/architecture.md「MuPDF 集成方式」第 4 条）
9. **提交门禁**：所有代码 commit / push 前必须交由用户决策——用户可选择「先 review」或「直接提交推送」；未经用户明确同意，不得执行任何 commit 或 push
10. **真机验收文件落盘（红线）**：真机验收产生的所有测试文件（截图 / `uiautomator` dump / 日志 / 拉取产物）在设备上一律写入 `/sdcard/Download/MyReaderTest/`（与测试夹具同目录），**禁止散落到 `/sdcard` 根目录或任何其它目录**；收尾只清理本轮临时文件，留档产物按分类子目录保留。本地留档放 `tools/acceptance/verify/`（已被 `.gitignore` 排除），仓库不提交验收截图。内部结构、命名与归位记录见 [docs/acceptance-files.md](docs/acceptance-files.md)；两者冲突时以本条为准
11. **子模块提交门禁**：改 `third-party/mupdf-android-viewer` 后，子模块新 commit **必须** `git -C third-party/mupdf-android-viewer push fork myreader-patches`（推自己的 fork `czc70159407/mupdf-android-viewer` 的 `myreader-patches` 分支，**不是** `origin` 上游 ArtifexSoftware），并确认主仓 gitlink（`git ls-tree HEAD third-party/mupdf-android-viewer`）与 fork 分支顶端一致——否则 CI 的 `git submodule update --init` 拉不到 gitlink 指向的 commit，编译失败（M12 B/C 阶段曾踩坑：本地 commit 后未 push fork，主仓 gitlink `6785e32` 超前 fork 顶端 `9df85e4`，CI 红，修复即 push fork）
12. **分支主干与版本 tag**：主干固定为 `main`（仓库默认分支），日常开发切 `dev` 或按里程碑开 `feature/xxx`，完成后合并回 `main`；版本发布须打附注 tag——每次 `versionName`/`versionCode` 升级执行 `git tag -a vX.Y.Z <sha> -m "..."` 并 `git push origin --tags`（tag 名与 `app/build.gradle.kts` 的 `versionName` 一致，如 `v1.5.0`）

## 常用命令（PowerShell）

```powershell
.\gradlew.bat build                              # 全量构建 + 测试
.\gradlew.bat :core:data:test                    # 数据层单测
.\gradlew.bat :app:assembleDebug                 # 装配 APK
.\gradlew.bat :third-party:mupdf:assembleDebug   # 构建 MuPDF viewer 库（fitz 走官方 Maven 预编译，无 native 编译）
.\gradlew.bat detekt koverHtmlReport             # T7 质量报告（detekt 静态检查 + 覆盖率，第一轮 ignoreFailures 仅报告）
```

## Context

项目目录 `D:\develop\code\MyReader`，安卓 PDF 阅读器 APP。核心需求三点：

1. **本地 PDF 阅读**：从设备存储选取 PDF 导入并阅读
2. **多级目录管理**：树形文件夹结构，可新建/重命名/删除/移动，PDF 可归入任意层级
3. **阅读进度记忆**：每文档记住上次页码，再次打开恢复

APP 整体开源（MuPDF AGPL 要求）。

## 文档导航（按需加载）

| 任务场景 | 分册 |
|---|---|
| 改模块结构 / 依赖版本 / MuPDF 集成 / 偏离选型 | [docs/architecture.md](docs/architecture.md)（技术选型、项目结构、模块依赖、MuPDF 集成方式、依赖项清单） |
| 改 Room schema / DAO / Repository / 存储策略 / 进度写库 | [docs/data-layer.md](docs/data-layer.md)（Room Schema、DAO、删除目录策略、存储策略、进度记忆） |
| 改阅读器渲染 / 路由 / 目录树 / 导入流程 / 错误边界 | [docs/ui-design.md](docs/ui-design.md)（Compose 集成、渲染与内存策略、UI 架构、导入流程、错误处理） |
| 改 UI 交互 / 文案 / 视觉基线 / 无障碍 / 新建或评审设计条目 | [docs/design/README.md](docs/design/README.md)（设计意图事实源：索引与决策日志、tokens、copy、screens、flows、accessibility） |
| 推进里程碑 / 写测试 / 排风险 / 查文件位置 | [docs/process.md](docs/process.md)（里程碑分解、测试策略、风险对策、关键文件清单） |
| 执行 / 追溯里程碑计划 | 对应 `docs/mXX-plan.md`（按里程碑号，含执行计划、S0 预研、验收方案）；现有里程碑清单与状态以上方「里程碑状态表」为准 |
| detekt 存量削减（已完成） | [docs/detekt-baseline-reduction.md](docs/detekt-baseline-reduction.md)（P0 已 100% 完成 173→0；削减方法学供 baseline 复发时参考） |
| 真机验收产物落盘 / 查历史 dump 与截图 | [docs/acceptance-files.md](docs/acceptance-files.md)（设备侧唯一目录 `/sdcard/Download/MyReaderTest`：分类子目录、命名约定、2026-10-29 归位记录） |
| 追溯历史会话日志 / 踩坑 / 决策 | [docs/history.md](docs/history.md)（M0~M12 逐会话验收证据与决策归档，历史快照只增不改；**设计决策以 `mXX-plan.md` 为准**） |

> **分册纪律**：每份分册单一职责，同一内容禁止两处维护；状态与验收证据只进本文件，设计规范只进分册。
