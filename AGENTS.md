# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源入口，面向 agent 驱动开发：任何会话先读「当前状态」「工作规则」「文档导航」，再**按任务按需**查阅 docs/ 分册，避免一次性加载全部设计细节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

- **阶段**：M0~M4 均已完成——数据层（Room/Hilt + 35 单测）+ MuPDF viewer 模块 + 导入与书架 + 阅读器水平模式（ReaderView 桥接 / 跳页 / 大纲导航 / 进度防抖回写），M4 真机验收全场景通过
- **下一步**：M5 目录树与文档管理（FolderTreeList + 新建/重命名/删除/移动含循环校验 + 文档长按操作）
- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要
- **M0 验收证据**：`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 53s`（35 tasks），产物 `app/build/outputs/apk/debug/app-debug.apk`（9.01MB）；Hello 界面目视已由 M1 真机验收覆盖闭环（同一 MainActivity 启动链路，M1 截图确认应用正常启动与渲染）
- **M1 验收证据**：`.\gradlew.bat :third-party:mupdf:assembleDebug` → `BUILD SUCCESSFUL in 21s`（fitz 1.28.2 官方 Maven 构件，免 NDK）；`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 26s`，APK 31.1MB 含 `lib/arm64-v8a/libmupdf_java.so`（10.3MB）+ `lib/x86_64/libmupdf_java.so`（10.7MB），ABI 裁剪生效；**真机验收通过**（Android 16 / SDK 36 真机 `adb install` 成功，截图确认 `countPages() = 3（预期 3）` 且 PDF 首页位图正常渲染）
- **M2 验收证据**：`.\gradlew.bat :core:data:test` → `BUILD SUCCESSFUL`，**31/31 测试通过**（FolderDaoTest 5 / DocumentDaoTest 9 / LibraryRepositoryImplTest 9 / DocumentStorageTest 8，Robolectric+inMemory Room）；`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 28s`（315 tasks）；schema 已导出 `core/data/schemas/…/1.json`；经 code-review + security-review 双审查，6 项 minor 问题全部修复（moveFolder 目标校验/路径穿越防护/ID 生成器提取+碰撞重试/LIKE 转义/删死代码/coroutines-core 修正），安全审查零可利用问题
- **M3 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 17s`（615 tasks，含 35/35 单测）；真机验收（Android 16 / SDK 36）全场景通过：①SAF 导入 demo_test.pdf → 书架条目 + 缩略图 + 「3 页」标注（displayName/mime/size 解析正确）；②重复导入 → 「文档已存在」弹窗，跳过/仍导入（force 重导）双路径均验证，查重按内容 hash 而非文件名；③corrupt_test.pdf → 「1 个损坏被拒收」+ 书架无新增 + 半成品副本已清理（library/ 仅保留成功导入副本）；④加密 PDF（pikepdf R=6 生成，密码 1234）→ 入库带锁角标、pageCount=-1、无缩略图，snackbar「1 个已加密（打开时需密码）」；⑤验收后移除 debug 直通与 M3Debug 临时日志，清理包复装冒烟通过（Room 数据无损保留）
- **M4 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL`（717 tasks；单测 feature:reader 9 例 + core:data 11 例）；code-review 双验证员交叉审查 3 项问题全修复（OutlineSheet 重复 key 崩溃 / 跳页进度帧窗口 / 取消异常吞没）；真机验收（一加 PKC110 / Android 16）五场景通过：①打开 outline_test.pdf（12 页带两级大纲）渲染 + 滑动翻页 + 页码联动，DB `currentPage` 防抖落库精确对应；②跳页第 8 页 → BACK 回书架 → 重进恢复第 8 页（核心需求，截图 + DB `currentPage=7` 双向佐证）；③OutlineSheet 展平缩进层级正确，点「Section 1.2 Usage」精确跳至第 4 页；④加密 PDF 密码框（打开键初始禁用）→ 错密码「密码错误，请重试」→ 正确密码解密渲染，兜底回写三项全生效（pageCount -1→3 / needsPassword 1→0 / 缩略图补齐）；⑤横竖屏切换进度保持（configChanges，Activity 未重建）——验收中发现横屏白屏 bug 并修复（`MuPdfReaderView.onSizeChanged → ReaderView.refresh()` 重建子 view 与共享位图），横竖屏双向复测通过

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + Maven fitz | 已完成 |
| M2 | 数据层（Room/Hilt） | 已完成 |
| M3 | 导入与书架 | 已完成 |
| M4 | 阅读器（水平模式） | 已完成 |
| M5 | 目录树与文档管理 | 未开始 |
| M6 | 搜索/排序/最近阅读 | 未开始 |
| M7 | 垂直滚动 + 设置（v1.1） | 未开始 |
| M8 | 备份与导出（v1.1） | 未开始 |
| M9 | 多格式支持（EPUB/CBZ/XPS，v1.2） | 未开始 |

> 状态取值：未开始 / 进行中 / 已完成。标记「已完成」必须附验收证据（命令输出摘要），并同步更新「下一步」。里程碑任务与验收标准详见 docs/process.md。

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

## 常用命令（PowerShell）

```powershell
.\gradlew.bat build                              # 全量构建 + 测试
.\gradlew.bat :core:data:test                    # 数据层单测
.\gradlew.bat :app:assembleDebug                 # 装配 APK
.\gradlew.bat :third-party:mupdf:assembleDebug   # 构建 MuPDF viewer 库（fitz 走官方 Maven 预编译，无 native 编译）
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
| 推进里程碑 / 写测试 / 排风险 / 查文件位置 | [docs/process.md](docs/process.md)（里程碑分解、测试策略、风险对策、关键文件清单） |

> **分册纪律**：每份分册单一职责，同一内容禁止两处维护；状态与验收证据只进本文件，设计规范只进分册。
