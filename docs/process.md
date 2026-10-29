# 过程分册：里程碑分解 / 测试策略 / 风险对策 / 关键文件清单

> AGENTS.md 文档体系分册之一：推进里程碑、写测试、评估风险、查文件位置时查阅本文档；**里程碑状态表与验收证据只更新根 AGENTS.md「当前状态」**。

## 关键风险与对策

| 风险 | 对策 |
|---|---|
| 大文件 OOM | 依赖 MuPDF BitmapPool；水平模式预渲染窗口 ±1 页；垂直模式（M7 落地）自管理字节预算 LruCache（`maxMemory/8` clamp 16~48MB，不按固定页数——2K 屏横屏单页可达 30MB）+ **不手动 recycle**（minSdk 26 位图 native heap 由 GC 释放，recycle 在绘位图必崩）；Application `onTrimMemory(MODERATE)` 调 `releaseBitmaps()` |
| MuPDF so 体积 | fitz AAR 官方已 strip；App 打包 `ndk.abiFilters` 裁剪——debug 保留 x86_64（模拟器调试），release 限 arm64-v8a + armeabi-v7a；Play Bundle 自动 per-ABI |
| SAF 权限丢失 | 采用"复制到内部存储"方案，导入后即与 Uri 解耦；DB 仅存 `sourceUri` 备查不依赖 |
| Compose 中 AndroidView 重组性能 | `AndroidView` 的 `factory` 只创建一次；模式/页码变化走 `update` 块做差分；`core` 生命周期用 `DisposableEffect` 管理，与 Document 绑定 |
| 树形目录循环引用 | 业务层 `moveFolder` 前用 `subtree()` 检查新父目录不是当前子树成员；FK 仅约束存在性不防循环 |
| 删除非空目录 | 见 docs/data-layer.md「删除目录策略」：事务内后代文档 `folderId` 置 null 回根 + 子目录先于父删，禁止裸删致 `parentId` 悬空（自引用无 FK 约束） |
| Room 递归 CTE 性能 | 深度通常 <5，CTE 走索引足够；全树列表用一次性 `subtree()` 缓存到内存树 |
| MuPDF API 版本漂移 | submodule 锁定到特定 commit；`:feature:viewer-mupdf` 实现 `core:document` 的 `DocumentEngine` + `Document` + `DocumentViewerProvider` 接口（`MuPdfDocumentEngine.kt` / `MuPdfDocument.kt` / `MuPdfViewerProvider.kt`），隔离 MuPDF 具体类，便于后续替换 |
| AGPL 合规 | README + About 页注明 MuPDF + AGPL 全文链接；APP 整体开源仓库 |
| 后台导入被杀 | v1 同步复制 + loading dialog；大文件 v1.1 用 WorkManager + ForegroundService |

## 测试策略

| 层 | 方式 | 范围 |
|---|---|---|
| `core:data` | JVM 单元测试（Robolectric） | `Room.inMemoryDatabaseBuilder` 跑 DAO：插/查/移动/进度更新/删非空目录级联回根/递归 CTE `subtree()`/搜索与排序查询/hash 查重 |
| `core:storage` | JVM 单元测试（临时目录） | PDF 复制/删除/缩略图生成、半成品回滚、空间检查 |
| `core:document` 接口 | 仅契约测试 | 接口存在性，实现在 viewer-mupdf |
| `feature:viewer-mupdf` | Android 插桩测试（`androidTest`，需真机/.so） | `MuPdfDocumentEngine.open`（含密码/损坏异常映射）+ `countPages` + `drawPage` + `outline` 对 assets 内样本 PDF；native 句柄 `close()` 无泄漏 |
| `feature:library` / `reader` | Compose UI 测试（`createAndroidComposeRule`） | 导入流程、目录树展开/移动/循环校验拦截、跳页+进度回写、大纲跳页、搜索/排序切换 |
| `app`（无障碍门禁） | Android 插桩测试（`androidTest`，需真机） | `LibraryA11yTreeTest`：读**平台** `AccessibilityNodeInfo`，断言书架卡片是单一 `clickable && contentDescription` 节点、其下无文本子节点、`actionList` 含 `重命名/移动/删除`——Compose 语义被平台拆成两个焦点位（重复播报）只有这一层能看到。测试自播种 `a11y_test.pdf` 后删除，不依赖设备数据；注意 `connectedAndroidTest` 跑完会卸载应用（数据清空） |
| MuPDF 渲染正确性 | 手工真机回归 | 无参考图难自动化；真机验证首页渲染、翻页、大文件不 OOM、横竖屏切换进度保持 |
| `Repository` | JVM 单元测试（Robolectric + inMemory Room，真 DAO；M2 已落地 9 用例） | 业务编排：循环校验、移动目标存在性、删除编排顺序（后代回根/子先父删）、删文档联动物理文件、进度分发、hash 查重 |

原则：纯逻辑尽量下沉到 `core:*` 做 JVM 测试（快、无需设备）；MuPDF 相关只能真机验证，集中到 `feature:viewer-mupdf` 的 `androidTest`。

## 里程碑与任务分解

> 每个里程碑 = 一个任务单元：完成即更新根 AGENTS.md「当前状态」表并附验收证据。真机项需设备；命令均为 PowerShell。

1. **M0 脚手架**
   - 任务：`git init` + `.gitignore`（`.gradle/`、`build/`、`local.properties`、`keystore.properties`、`.idea/`）+ 首次提交；根 `build.gradle.kts` + `settings.gradle.kts` + `libs.versions.toml` + `:app` 空 MainActivity 显示 Hello；预留 release `signingConfigs` 占位（密钥从本地 `keystore.properties` 读取，不入仓）
   - 验收：`.\gradlew.bat :app:assembleDebug` 成功，app 启动显示 Hello
2. **M1 MuPDF 子模块**
   - 任务：① submodule 引入并锁定 1.28.x commit（上游 GitHub，URL 与锁定值见 docs/architecture.md「MuPDF 集成方式」）；② `:third-party:mupdf` 模块挂载 + fitz 官方 Maven 预编译依赖（免 NDK/native 编译）；③ demo pdf 验证 `MuPDFCore.countPages()` 与首页渲染
   - 验收：`.\gradlew.bat :third-party:mupdf:assembleDebug` 构建通过（fitz AAR 含全 ABI `libmupdf_java.so`）；demo Activity 打开 assets 内测试 PDF，`countPages()` 返回正确页数，能渲染首页
3. **M2 数据层**
   - 任务：Room 实体+DAO+Hilt 注入；写 `LibraryRepository` 与单元测试（`Room.inMemoryDatabaseBuilder`）
   - 验收：`.\gradlew.bat :core:data:test` 通过（插/查/移动/进度更新/删非空目录级联回根/搜索与排序/hash 查重）
4. **M3 导入与书架**
   - 任务：SAF 导入 + 复制 + hash 重复检测 + 打开验证（损坏拒收/加密标记）+ 导入时缩略图 + 插库；LibraryScreen 文档网格/列表展示
   - 验收：真机选 PDF → 出现在书架且有缩略图；`filesDir/library/` 下有副本；同一 PDF 二次导入提示重复；损坏文件拒收；加密 PDF 入库标记后打开弹密码框
5. **M4 阅读器水平模式**
   - 任务：AndroidView 桥接 + 水平分页 + 跳页 + 大纲导航（OutlineSheet）+ 进度回写（含防抖+onPause 保底）
   - 验收：打开 PDF 可翻页；跳到第 N 页退出再进，恢复到第 N 页（核心需求）；带大纲的 PDF「目录」跳页正确
6. **M5 目录树与文档管理**
   - 任务：FolderTreeList + 新建/重命名/删除/移动（含循环校验）；文档长按重命名/移动/删除
   - 验收：新建多级文件夹 → 移动文档进子目录 → 删父目录后文档回根级；移动到自身子树被拦截；文档可重命名
7. **M6 搜索、排序与最近阅读**
   - 任务：全库搜索；`DocSort` 排序切换（DataStore 记忆）；最近阅读列表
   - 验收：关键词命中全库文档（不限当前文件夹）；切换排序立即生效且重启保持；最近阅读按时间排序
8. **M7 垂直滚动模式 + 设置**（v1.1）
   - 任务（M7 落地分解）：①`ReaderMode`/`ThemeMode` 枚举迁入/新建于 core:model（偏好层类型化暴露）；②`SettingsPrefs`（settings_prefs 文件，默认阅读模式+主题，双限定符 DI 与 LibraryPrefs 并列）；③`Document.pageSize` 接口（垂直布局定高 + 页面预热）；④垂直渲染 `VerticalPageLoader`（Mutex 串行 + Dispatchers.Default + 字节预算 LruCache）+ `MuPdfVerticalView`（LazyColumn，lastEmittedPage 防回环守卫）+ Provider 按 mode 分支；⑤阅读器接线（VM mode 会话态 + 顶栏切换 + scrollOffsetY 落库/恢复）；⑥`feature:settings` 模块（三组单选，排序与书架 SortMenu 同源双向同步）+ settings 路由 + 书架入口；⑦主题（MainActivity 动态 colorScheme + themes.xml DayNight 防首帧闪白）
   - 验收：长文档连续滚动流畅、滚动偏移可恢复；设置页偏好重启后保持（细化场景见根 AGENTS.md M7 验收证据）
9. **M8 备份与恢复**（v1.1）
   - 任务：导出 `library/` 物理文档 + `thumb/` 缩略图 + `library.json` 全量快照到用户指定 SAF `.zip`；从备份 `.zip` 合并式恢复，解决 `filesDir` 换机/卸载即丢问题
   - 分解：
     ① `kotlinx-serialization-json` 引入（M8 唯一偏离选型的依赖，已更新 architecture.md「技术选型」+「依赖项清单」）；
     ② `DocumentStorage` 新增导出读取/空间预检/恢复写回（白名单 + canonical 双闸、同分区 `renameTo` move）+ `newBackupTempDir`（见 data-layer.md「备份与恢复存储/DAO」）；
     ③ `DocumentDao`/`FolderDao` 新增 `all()` 全量查询与 `@Upsert`（无 schema 变更，无 Migration）；`core:data` 新增 `BackupCodec`（DTO 编解码 + `schemaVersion` 门禁 + `relPath` 白名单）与 `BackupManager`（导出 zip / 恢复事务 + 目录拓扑序 upsert + 解压 entry 白名单防 zip slip + 失败回滚），纯逻辑下沉 `core:data` 做 JVM 单测；
     ④ `feature:settings` 新增「备份与恢复」分区（`CreateDocument` 导出 / `OpenDocument` 恢复，进度环 + snackbar，依赖 `activity-compose`）
   - 验收：单测 `BackupCodecTest`/`BackupManagerTest`（往返全字段一致、缺文件计数、幂等、zip slip 拒绝）全绿 + `.\gradlew.bat build` 通过；真机：造数据（父子目录 + 加密文档 + 阅读进度）→ 导出 `.zip` → `adb uninstall` 重装 → 从 `.zip` 恢复，目录树 `parentId`/文档/进度（`currentPage`/`scrollOffsetY`/`lastReadAt`/`pageCount`/`needsPassword`）逐字段一致，物理文件 SHA-256 一致
10. **M9 多格式支持**（v1.2，可选）
   - 任务：`:feature:viewer-mupdf` 内为 epub/cbz/xps 注册 `@FileTypeKey`（同一 MuPDF 引擎多格式复用，fitz 原生解析这三类；格式契约集中 `core:document` 的 `FileTypes`：扩展名/mime/magic 归一 + 导入 mime 白名单 + 未知格式导入期拒收）；epub 进度以 fitz bookmark 串为锚（`EpubProgressCodec` 序列化至 `progressPayload`，`progressType="cfi"`，跨重排稳定），cbz/xps 沿用页码式；epub 重排 relayout + `Document.reflowable` 分支；书架卡片按 `fileType` 区分图标/格式角标/页数文案
   - 验收：三种格式均可导入（缩略图正常）→ 打开 → 退出重进进度恢复；加第四种自研格式（TXT）已由 `feature:viewer-txt` 落地验证扩展点（M9-F 已做）：仅新增该模块 + 两个 `@FileTypeKey("txt")` 绑定 + `FileTypes` 登记 `txt`/`text/plain`，reader/library/schema 零改动即跑通导入/阅读/页码进度
   - **执行计划与验收方案（缺口清单 / 技术决策 / S0 预研门禁 / 阶段 A~G / 夹具与测试矩阵 / 真机场景）：[docs/m9-plan.md](m9-plan.md)**
11. **真机回归**（M4 起每个里程碑后执行）：大 PDF（>200 页）不 OOM；横竖屏切换进度保持

## 关键文件清单（落地优先级）

**构建配置（最优先）：**
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

**领域模型（纯 Kotlin，无注解；避免与 `core:document` 的句柄接口 `Document` 同名）：**
- `core/model/.../model/LibraryItem.kt` ← 书架条目元数据，映射 DocumentEntity
- `core/model/.../model/Folder.kt`
- `core/model/.../model/DocSort.kt` ← 书架排序枚举（TITLE/LAST_READ/ADDED/SIZE），DataStore 记忆当前值

**数据层（Repository 实现 + Room）：**
- `core/data/.../db/entity/FolderEntity.kt`
- `core/data/.../db/entity/DocumentEntity.kt`
- `core/data/.../db/dao/FolderDao.kt`
- `core/data/.../db/dao/DocumentDao.kt`
- `core/data/.../db/MyReaderDatabase.kt`
- `core/data/schemas/` ← Room schema JSON 随仓库提交（工作规则 3，改 schema 必须写 Migration）
- `core/data/.../repo/LibraryRepository.kt` ← 接口（与 Impl 同模块，feature 依赖此接口而非实现）
- `core/data/.../repo/LibraryRepositoryImpl.kt`

**隔离接口 / 文件 IO：**
- `core/document/.../document/DocumentEngine.kt` ← 抽象工厂接口，feature 依赖此而非 MuPDF
- `core/document/.../document/Document.kt` ← 文档句柄接口（pageCount/gotoPage/drawPage/outline/close），ReaderViewModel 持有此而非 native 具体句柄
- `core/document/.../document/DocumentException.kt` ← sealed：PasswordRequired / CorruptDocument（导入验证分流）
- `core/document/.../document/OutlineEntry.kt` ← 大纲条目（title/pageIndex/children）
- `core/document/.../document/DocumentViewerProvider.kt` ← 渲染 Composable 提供者抽象（@Composable Content + thumbnail）
- `core/document/.../document/ProgressPayload.kt` ← sealed 进度模型（Page / Cfi）
- `core/document/.../document/ReaderMode.kt` ← 枚举上移至此（供 DocumentViewerProvider 接口引用，避免 core 反向依赖 feature）
- `core/document/.../document/FileTypeKey.kt` ← Hilt 多绑定限定符 + DocumentEngineRouter（按 fileType 路由 Map）
- `core/storage/.../storage/DocumentStorage.kt` ← 复制/删除/缩略图 IO（按 fileType 调度）
- `core/common/.../common/DispatcherProvider.kt`

**主题（不设 `core:designsystem` 模块）：**
- 主题落在 `app`：`MainActivity` 动态 `colorScheme`（Material3 默认色板，不接 dynamicColor）+ `values/themes.xml` DayNight 防首帧闪白；细节见 docs/ui-design.md「主题（M7）」

**书架 + 目录管理（合并模块）：**
- `feature/library/.../ui/LibraryScreen.kt` ← 文档网格/列表 + 长按菜单(重命名/移动/删除) + 搜索框 + 排序切换
- `feature/library/.../ui/LibraryViewModel.kt`
- `feature/library/.../ui/FolderTreeList.kt`
- `feature/library/.../ui/FolderTreeState.kt`
- `feature/library/.../ui/FolderPickerDialog.kt`
- `feature/library/.../ui/NewFolderDialog.kt`

**阅读器 UI（不含渲染）：**
- `feature/reader/.../ui/ReaderScreen.kt`
- `feature/reader/.../ui/ReaderViewModel.kt`
- `feature/reader/.../ui/GoToPageDialog.kt`
- `feature/reader/.../ui/OutlineSheet.kt` ← 大纲导航（ModalBottomSheet，展平缩进列表，点击跳页）

**设置（独立 feature，偏好持久化）：**
- `feature/settings/.../ui/SettingsScreen.kt` ← 阅读模式/主题/书架排序偏好
- `feature/settings/.../ui/SettingsViewModel.kt` ← DataStore Preferences 读写

**渲染桥接（按引擎拆分，当前仅 MuPDF；唯一依赖 MuPDF 的 feature）：**
- `feature/viewer-mupdf/.../viewermupdf/MuPdfReaderView.kt` ← AndroidView 桥接（DocumentViewerProvider.Content 的实现体）
- `feature/viewer-mupdf/.../viewermupdf/MuPdfDocumentEngine.kt` ← DocumentEngine 实现，`@FileTypeKey("pdf")`
- `feature/viewer-mupdf/.../viewermupdf/MuPdfDocument.kt` ← Document 实现（包装 MuPDFCore native 句柄，含大纲转发）
- `feature/viewer-mupdf/.../viewermupdf/MuPdfViewerProvider.kt` ← DocumentViewerProvider 实现，`@FileTypeKey("pdf")`

**DI/入口：**
- `app/.../MyReaderApp.kt`（@HiltAndroidApp）
- `app/.../MainActivity.kt`
- `app/.../nav/MyReaderNavHost.kt`
- `app/.../di/DatabaseModule.kt`
- `app/.../di/RepositoryModule.kt`
- `app/.../di/DocumentEngineModule.kt` ← `@IntoMap` 多绑定 DocumentEngine/DocumentViewerProvider by `@FileTypeKey`

**MuPDF（git submodule 引入，无需手写）：**
- `third-party/mupdf-android-viewer/` ← submodule 根，`lib/` 作为 `:third-party:mupdf` 模块
