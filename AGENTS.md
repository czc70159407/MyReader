# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源入口，面向 agent 驱动开发：任何会话先读「当前状态」「工作规则」「文档导航」，再**按任务按需**查阅 docs/ 分册，避免一次性加载全部设计细节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

- **阶段**：M0~M7 均已完成——数据层（Room/Hilt）+ MuPDF viewer 模块 + 导入与书架 + 阅读器水平模式 + 目录树与文档管理 + 搜索/排序/最近阅读 + 垂直滚动与设置（v1.1：垂直连续滚动 + 阅读器顶栏实时切换 / 设置页阅读模式·主题·书架排序 / 深浅色主题全 APP），M7 真机验收全场景通过；M8 备份与恢复真机验收通过（导出→卸载重装→恢复，目录树/文档/进度/加密/页数 DB 级逐项一致 + 物理文件 SHA-256 一致）
- **M7 后补齐（合规 / 测试 / 文档）**：①**About 页**（路由 `about`，入口=设置页「关于」）——补齐 AGPL 合规缺口：此前仅 README 有声明却写着「应用内 About 页同步展示」，应用内实无此页，现于应用内展示 MuPDF 来源 + AGPL-3.0 全文链接 + 本项目整体开源声明（工作规则 2）；②`feature:viewer-mupdf` 补 **`androidTest` 插桩测试** 5 例（`MuPdfDocumentEngineTest`：正常/加密/损坏 open、`pageCount`、`outline`、`drawPage`、句柄释放），样本 PDF 由 `tools/test_pdfs.py generate` 生成并置于 `src/androidTest/assets/`；③**文档修正**：移除文档中不存在的 `core:designsystem` 模块（主题实落在 `app`）、README 路线图 M3~M7 由「未开始」更正为「已完成」、M1 描述更正为「Maven fitz 预编译」；`docs/ui-design.md` 路由表增 `about`
  - 验收证据：`.\gradlew.bat :app:assembleDebug :feature:viewer-mupdf:assembleDebugAndroidTest` → `BUILD SUCCESSFUL in 13s`（224 tasks）；`.\gradlew.bat test` → `BUILD SUCCESSFUL`，全量单测 **0 失败**（27 个套件、含 debug/release 双变体共 222 次执行）。插桩测试**仅验证编译**，运行需真机：`.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest`
- **M8 进展（v1.1）**：代码/单测/build 已落地——`kotlinx-serialization-json` 引入（architecture.md 技术选型+依赖清单已同步）；`core:data` 新增 `BackupCodec`（DTO 编解码 + `schemaVersion` 门禁 + `relPath` 白名单）与 `BackupManager`（导出 `files/`+`thumbs/`+`library.json` 的 `.zip`，恢复事务 + 目录拓扑序 upsert + 解压 entry 白名单防 zip slip + 失败回滚）；`DocumentStorage` 新增导出读取/空间预检/恢复写回（白名单 + canonical 双闸、同分区 move）+ `newBackupTempDir`；`DocumentDao`/`FolderDao` 新增 `all()` 全量查询与 `@Upsert`（**无 schema 变更，无 Migration**，符合工作规则 3）；`feature:settings` 新增「备份与恢复」分区（SAF `CreateDocument`/`OpenDocument`，进度环 + snackbar）。单测 `BackupCodecTest`/`BackupManagerTest` 全绿（含往返全字段一致/缺文件计数/幂等/zip slip 拒绝），`.\gradlew.bat build` BUILD SUCCESSFUL（1m36s）。**真机验收已通过**（一加 PKC110 / Android 16）：造数据（根→A→B→C 目录树 + 4 文档含加密 `encrypted_test.pdf` 密码 1234 + `outline` 第 8 页 / `long` 第 18 页 + 垂直偏移 831 进度）→ 设置页 SAF 导出 `myreader-backup.zip`（36,804 字节，10 条目 = 5 PDF + 4 缩略图 + `library.json`；5 文件 SHA-256 与基线 DB `contentHash` 全一致）→ `adb uninstall com.myreader` 重装 → 从 `.zip` 恢复 → 提取恢复后 DB 与基线逐项比对：**目录树 `parentId`/文档/`currentPage`/`scrollOffsetY`/`lastReadAt`/`pageCount`/`needsPassword`/`thumbnailRelPath` 全部一致（`tools/acceptance/verify_db.py` PASS）**，恢复后 `library/` 5 文件 SHA-256 与 `contentHash` 全一致、`thumb/` 4 张缩略图正确恢复（加密文档无缩略图）。**验收中发现 3 个真机 bug 并已修复**：①导出 zip 0 字节——`SettingsScreen` 用 `?.use{}` 在 `viewModelScope` 异步协程写完前就关闭 SAF 流（`ZipOutputStream` 链又叠加多余 `BufferedOutputStream` 使中央目录滞留缓冲层）；改为协程内 `finally` 关闭流、移除 `BufferedOutputStream`；②恢复报「存储空间不足」——`DocumentStorage.hasSpaceFor` 用 `libraryDir.usableSpace`，全新安装时 `library/` 子目录尚不存在导致 `File.usableSpace` 返回 0L 误判，改为不存在时回退父目录评估；③（①②叠加）均**无 schema 变更、无 Migration**（符合工作规则 3），`.\gradlew.bat build` 修复后 BUILD SUCCESSFUL（847 tasks，全模块单测绿），真机复验导出 36,804 字节 + 恢复 DB/文件全一致通过
- **M8 复验（本会话 2026-10-13）**：重新 SAF 导出 `myreader-backup.zip` = 36,804 字节、10 条目（5 PDF + 4 缩略图 + `library.json`）、`schemaVersion=1`/4 目录/5 文档，与 M8 验收值一致——确认导出修复无回归；此前观测到的 0 字节系后台协程未刷盘时过早 `ls` 所致（等待约 9s 后文件正常落盘为 36,804 字节），非缺陷。
- **下一步**：M8 代码 + `.gitignore`（忽略验收产物与 `.trae/`）已**本地提交**（commit `020f46a` 备份与恢复、`c50865a` .gitignore），**未推送**；是否推送交用户决策
- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要
- **M0 验收证据**：`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 53s`（35 tasks），产物 `app/build/outputs/apk/debug/app-debug.apk`（9.01MB）；Hello 界面目视已由 M1 真机验收覆盖闭环（同一 MainActivity 启动链路，M1 截图确认应用正常启动与渲染）
- **M1 验收证据**：`.\gradlew.bat :third-party:mupdf:assembleDebug` → `BUILD SUCCESSFUL in 21s`（fitz 1.28.2 官方 Maven 构件，免 NDK）；`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 26s`，APK 31.1MB 含 `lib/arm64-v8a/libmupdf_java.so`（10.3MB）+ `lib/x86_64/libmupdf_java.so`（10.7MB），ABI 裁剪生效；**真机验收通过**（Android 16 / SDK 36 真机 `adb install` 成功，截图确认 `countPages() = 3（预期 3）` 且 PDF 首页位图正常渲染）
- **M2 验收证据**：`.\gradlew.bat :core:data:test` → `BUILD SUCCESSFUL`，**31/31 测试通过**（FolderDaoTest 5 / DocumentDaoTest 9 / LibraryRepositoryImplTest 9 / DocumentStorageTest 8，Robolectric+inMemory Room）；`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 28s`（315 tasks）；schema 已导出 `core/data/schemas/…/1.json`；经 code-review + security-review 双审查，6 项 minor 问题全部修复（moveFolder 目标校验/路径穿越防护/ID 生成器提取+碰撞重试/LIKE 转义/删死代码/coroutines-core 修正），安全审查零可利用问题
- **M3 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 17s`（615 tasks，含 35/35 单测）；真机验收（Android 16 / SDK 36）全场景通过：①SAF 导入 demo_test.pdf → 书架条目 + 缩略图 + 「3 页」标注（displayName/mime/size 解析正确）；②重复导入 → 「文档已存在」弹窗，跳过/仍导入（force 重导）双路径均验证，查重按内容 hash 而非文件名；③corrupt_test.pdf → 「1 个损坏被拒收」+ 书架无新增 + 半成品副本已清理（library/ 仅保留成功导入副本）；④加密 PDF（pikepdf R=6 生成，密码 1234）→ 入库带锁角标、pageCount=-1、无缩略图，snackbar「1 个已加密（打开时需密码）」；⑤验收后移除 debug 直通与 M3Debug 临时日志，清理包复装冒烟通过（Room 数据无损保留）
- **M4 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL`（717 tasks；单测 feature:reader 9 例 + core:data 11 例）；code-review 双验证员交叉审查 3 项问题全修复（OutlineSheet 重复 key 崩溃 / 跳页进度帧窗口 / 取消异常吞没）；真机验收（一加 PKC110 / Android 16）五场景通过：①打开 outline_test.pdf（12 页带两级大纲）渲染 + 滑动翻页 + 页码联动，DB `currentPage` 防抖落库精确对应；②跳页第 8 页 → BACK 回书架 → 重进恢复第 8 页（核心需求，截图 + DB `currentPage=7` 双向佐证）；③OutlineSheet 展平缩进层级正确，点「Section 1.2 Usage」精确跳至第 4 页；④加密 PDF 密码框（打开键初始禁用）→ 错密码「密码错误，请重试」→ 正确密码解密渲染，兜底回写三项全生效（pageCount -1→3 / needsPassword 1→0 / 缩略图补齐）；⑤横竖屏切换进度保持（configChanges，Activity 未重建）——验收中发现横屏白屏 bug 并修复（`MuPdfReaderView.onSizeChanged → ReaderView.refresh()` 重建子 view 与共享位图），横竖屏双向复测通过
- **M5 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL in 47s`（单测 core:data 33 例含 observeAll/observeAllFolders 2 例新增 + feature:library 19 例含 buildFlatTree 纯函数 5 例 / FolderTreeList Compose UI 3 例 / LibraryViewModel 编排 7 例新增）；真机验收（一加 PKC110 / Android 16）5 条标准 + 回归全通过（UI 截图 + Room DB 双向佐证）：①新建多级文件夹 根→A→B→C 逐层创建，DB `parentId` 链精确（A=null / B→A.id / C→B.id），子目录空态文案「此文件夹为空」与根态区分；②移动文档进子目录：长按 demo_test.pdf → FolderPickerDialog 选 A → DB `folderId=A.id` 且 `relPath` 不变（不动物理文件），根网格同步移除；③删父目录文档回根：DeleteConfirmDialog 文案含「文档将移至根目录」→ 删 A 后 DB folders 清空（A/B/C 级联删除）、renamed_test.pdf `folderId=null` 回根；④移动到自身子树拦截：picker 选 A 自身与子树 B 均显示「不能移动到自身或其子目录」+ 确定按钮禁用（`subtree()` 预过滤 UI 前置 + `moveFolder` require 兜底双保险）；⑤文档重命名：demo_test.pdf→renamed_test.pdf，DB `title` 更新且 `relPath` 不变；⑥回归：SAF 导入选目标目录（ImportTarget）+ 重复逐个确认（仍导入后 force 新记录精确落 ImportTarget，原记录保留）、横竖屏进度保持（横屏渲染无白屏 6/12 保持、BACK 重进恢复第 6 页、DB `currentPage=5` 精确落库）；**验收中发现启动即崩 bug 并修复**：`NavType.LongType` 不允许 nullable（`IllegalArgumentException: long does not allow nullable values`，编译期不报）→ 改哨兵 `defaultValue=-1L`（`Routes.FOLDER_ID_NONE`）+ VM 中转 null，docs/ui-design.md 已同步
- **M6 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 18s`（单测 core:data 31 例含 LibraryPrefsTest 3 例新增 + feature:library 30 例含 LibraryViewModelTest 4 例新增 / LibraryComponentsTest 4 例新增）；真机验收（一加 PKC110 / Android 16）三标准 + 回归全通过（UI dump + Room DB 双向佐证）：①全库搜索：ImportTarget 子目录内搜索 "outline" → 命中 2 个（当前目录 1 + **根目录 1，不限当前文件夹**），输入 300ms 防抖实时出结果，空关键词维持浏览态（null/empty 语义），`BackHandler` 系统返回收起搜索不 pop 导航栈；②排序记忆：菜单 4 项默认勾选「标题」→ 选「大小」网格即时重排（6107→1674→1125→1125B，同大小按 title NOCASE 次序）→ `am force-stop` 杀进程重启后网格仍大小序且菜单勾选保持「大小」（DataStore `library_prefs.preferences_pb` 持久化）；③最近阅读：初始最近条 outline(第6页)→encrypted(第2页) 与 DB lastReadAt 降序一致（02:50:34 > 00:59:56），打开 encrypted（密码 1234）读到 3/3 返回后动态重排为 encrypted(**第3页**)→outline(第6页)，DB `currentPage=2` / `lastReadAt=04:21:48` 双向佐证；④回归：子目录进出面包屑正确、子目录不显示最近条（VM 层 gate）、文档长按菜单（重命名/移动/删除）正常。实现细节：无 schema 变更（DAO/Repository M2 已就绪，仅 UI/VM 接线 + LibraryPrefs 新建）；`searchResults` null=非搜索态与 emptyList=无命中严格区分。**验收后用户试用反馈 4 项问题并全部修复复验**（`.\gradlew.bat build` → `BUILD SUCCESSFUL in 54s`，reader 10 例 / feature:library 31 例）：①加密角标密码打开成功后永久消失（M4 `backfillAfterOpen` 清 `needsPassword` 所致——文件始终加密、密码不持久化，标记即角标依据）→ 标记改为常驻不清 + `PasswordRequiredException` 路径自愈置 true（兜住存量误清数据），真机复验角标恢复且重启保持；②密码弹窗不输入直接返回时弹窗残影悬于书架转场之上（AlertDialog 独立窗口与 NavHost 转场不同步）→ 取消先使弹窗离开组合再触发导航；③阅读返回后最近条重排而横向偏移保留 → 卡片原地跳变 → 首项变化时横向滚动复位，真机复验返回后首位即刚读文档；④加密文档卡片能显示第一页内容（M4 兜底给解密成功的加密文档补生成缩略图所致——内容预览与加密语义冲突）→ 加密文档不补生成缩略图且打开时清除历史遗留（`storage.deleteThumbnail` + 置 null），DB 复验 `needsPassword=1 thumbnail=None`，卡片恢复占位图标 + 锁角标。**code-review（TRAE-code-review 双验证员交叉）3 项问题全部修复复验**（`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 9s`）：①DataStore 异常路径——`docSort` 读取流 `.catch` 回落空偏好（读失败不沿 stateIn 崩溃书架主列表）、`setSort` 写入 try/catch 静默降级；②`PasswordRequiredException` 自愈加 `!item.needsPassword` 守卫（错密码重试路径不再冗余写库）；③搜索框展开态 `searchOpen` 上提至 VM（原 Compose 本地态跨导航复位而 VM 查询存活 →「结果网格 + 浏览态顶栏」失配且无退出入口）——真机复验搜索→阅读→返回后顶栏/结果/退出入口完全一致，BACK 正常收起搜索
- **M7 验收证据**：`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 6s`（847 tasks，新增 feature:settings 模块；单测 91 例全绿：core:data 34 含 SettingsPrefsTest 3 例新增 / viewer-mupdf 6 例 VerticalPageLoaderTest 新增 / reader 18 含 mode 状态·offset 落库·密码重试守卫 7 例新增 / settings 2 例新增 / library 31 例）；真机验收（一加 PKC110 / Android 16）六场景 + 回归全通过（UI 截图 + Room DB + DataStore preferences_pb 字节级三方佐证）：①垂直滚动 + 偏移落库：设置默认模式「垂直」→ 打开 outline_test.pdf 连滚至 8/12（页码联动、多页同屏连续渲染），DB `currentPage=7 / scrollOffsetY=1262`；长文档 long_test.pdf（pikepdf 生成 40 页 + SAF 导入「40 页」标注）快速连滑跨 17 页流畅，DB `17/831` 精确；②偏移恢复：BACK→重进恢复第 8 页（UI 8/12 + DB `7/1229`，恢复锚定仅像素级舍入）；**验收中发现关键 bug 并修复**：`uiPage` 初始 0 先组合 Content（`LaunchedEffect(s)` 帧后才补正值）→ 垂直视图恢复到第 1 页且 snapshotFlow 回流 `(0,0)` 覆盖库中进度 → 改 `key(s)` + `remember { mutableStateOf(s.initialPage/initialScrollOffsetY) }` 使初始进度组合期即就位（密码重试新 Ready 实例 → key 变化全套重置），修复复测恢复精确且进度不再被毁；③模式实时切换：顶栏按钮 H↔V 往返当前页稳定 8/12（H 阶段 offset 归 0 符合「水平只记页码」语义，无落库抖动）；④设置持久化：垂直滚动/深色/大小 → `am force-stop` 重启三项全保持（`settings_prefs.preferences_pb` 字节级 `default_reader_mode=1 / theme_mode=2`、`library_prefs` `doc_sort=3`）；⑤主题全 APP：深色遍历 书架/设置/阅读器（水平+垂直）/大纲 Sheet/密码弹窗/跳页弹窗 无浅色残留、无首帧闪白（runBlocking 300ms 首读 + themes.xml DayNight 双保险）；「跟随系统」+ `cmd uimode` 切换浅/深即时生效（uiMode 重建路径进度不丢，重进恢复 8/12）；⑥排序双向同步：设置页改「大小」→ 书架即时重排（long_test 10955B > outline 6107B > encrypted 1674B），书架 SortMenu 与设置页同源（LibraryPrefs）天然同步。回归：水平模式（页保持 + 横屏居中渲染无白屏 + 竖屏往返 8/12 保持）、垂直横竖屏（LazyList 锚定）、加密文档（深色密码弹窗 → 解密 → 垂直可读）。**验收排疑似记录**：encrypted_test.pdf 首页显示「M3 Demo Page 3」与横屏「白屏」经拉文件解析内容流（`page 0 text ops: [b'M3 Demo Page 3']`，页面点尺寸 300×400pt）确认为文档自身页序与内容布局，渲染全链路正确，非缺陷

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
