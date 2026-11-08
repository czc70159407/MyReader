# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源入口，面向 agent 驱动开发：任何会话先读「当前状态」「工作规则」「文档导航」，再**按任务按需**查阅 docs/ 分册，避免一次性加载全部设计细节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

### 最近状态

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

> 状态取值：未开始 / 进行中 / 已完成。标记「已完成」必须附验收证据（命令输出摘要），并同步更新「下一步」。里程碑任务与验收标准详见 docs/process.md。

### 环境与卡点

- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要

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
| 改 UI 交互 / 文案 / 视觉基线 / 无障碍 / 新建或评审设计条目 | [docs/design/README.md](docs/design/README.md)（设计意图事实源：索引与决策日志 D1~D8、tokens、copy、screens、flows、accessibility） |
| 推进里程碑 / 写测试 / 排风险 / 查文件位置 | [docs/process.md](docs/process.md)（里程碑分解、测试策略、风险对策、关键文件清单） |
| 执行 M9（多格式支持） | [docs/m9-plan.md](docs/m9-plan.md)（M9 执行计划与验收方案：缺口清单、技术决策、S0 预研门禁、阶段 A~G、夹具/测试矩阵、真机验收场景） |
| 执行 M10（书签 + 首个 Room Migration） | [docs/m10-plan.md](docs/m10-plan.md)（M10 执行计划与验收方案：S0 锚点/表结构预研、阶段 A~D、MigrationTest 门禁、备份双版本兼容、真机验收场景） |
| 执行 M11（epub 锚点稳健性 / 比例锚点） | [docs/m11-plan.md](docs/m11-plan.md)（M11 执行计划：S0-1 fitz API 边界结论、跨会话/跨排版探针、P/Q/R 决策树、`cp:ch:cp:ct` 锚点与备份 v3、验收场景） |
| 执行 M12（沉浸模式 + 高亮/笔记） | [docs/m12-plan.md](docs/m12-plan.md)（M12 执行计划：A 沉浸模式实现分解（契约纠偏/接口/状态/验收）+ B 高亮/笔记 S0 立项、Room v3 + 备份 v4 草案） |
| detekt 存量削减（与 M10 并行） | [docs/detekt-baseline-reduction.md](docs/detekt-baseline-reduction.md)（baseline 渐进清理任务说明：分层范围/削减循环/进度追踪/并行冲突防范） |
| 真机验收产物落盘 / 查历史 dump 与截图 | [docs/acceptance-files.md](docs/acceptance-files.md)（设备侧唯一目录 `/sdcard/Download/MyReaderTest`：分类子目录、命名约定、2026-10-29 归位记录） |
| 追溯历史会话日志 / 踩坑 / 决策 | [docs/history.md](docs/history.md)（M0~M12 逐会话验收证据与决策归档，历史快照只增不改） |

> **分册纪律**：每份分册单一职责，同一内容禁止两处维护；状态与验收证据只进本文件，设计规范只进分册。
