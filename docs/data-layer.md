# 数据层分册：Room Schema / DAO / 删除目录策略 / 存储策略 / 进度记忆

> AGENTS.md 文档体系分册之一：改 schema、DAO、Repository、存储策略、进度写库逻辑时更新本文档（工作规则 3/5/7）；状态与验收证据在根 AGENTS.md。

## Room Schema

```kotlin
// core/data/.../db/entity/FolderEntity.kt
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Long,            // ULID/雪花，避免自增冲突便于移动
    val parentId: Long?,                // 自引用，null = 根
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

// core/data/.../db/entity/DocumentEntity.kt
@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.SET_NULL    // 删目录后文档回到根
    )],
    indices = [Index("folderId"), Index("lastReadAt"), Index("fileType"), Index("contentHash")]
)
data class DocumentEntity(
    @PrimaryKey val id: Long,
    val folderId: Long?,
    val title: String,
    val relPath: String,                 // 相对 filesDir/library/，扁平 "uuid.<ext>"；移动文档不改此字段
    val fileType: String,               // "pdf"/"epub"/"cbz"...，路由引擎 + 书架图标 + 导入过滤
    val mimeType: String?,              // 原始 mime，备查
    val sourceUri: String?,             // 原始 SAF Uri，仅记录来源不依赖
    val fileSize: Long,
    val contentHash: String? = null,     // SHA-256，导入复制时流式计算，重复导入检测用
    val needsPassword: Boolean = false,  // 导入验证为加密文档 → true；常驻不清（文件始终加密，角标依赖此标记，M6 修正）
    val pageCount: Int = -1,             // 导入验证成功时填充；-1 未知（含待解密）；-2 打开损坏标记；流式(EPUB)恒为 -1
    val progressType: String = "page",  // "page"(页码式) / "cfi"(流式)，决定读哪个进度字段
    val currentPage: Int = 0,           // 进度（progressType="page" 时有效）
    val scrollOffsetY: Int = 0,         // 页码式垂直模式额外偏移
    val progressPayload: String? = null, // 流式进度 JSON（progressType="cfi" 时存 CFI 串等）
    val thumbnailRelPath: String?,
    val lastReadAt: Long?,
    val addedAt: Long,
)
```

### DAO 关键查询

```kotlin
@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name")  // 根级传 null，必须 IS（= NULL 恒为假）
    fun children(parentId: Long?): Flow<List<FolderEntity>>

    @Query("""
        WITH RECURSIVE d AS (
          SELECT * FROM folders WHERE id = :rootId
          UNION ALL SELECT f.* FROM folders f JOIN d ON f.parentId = d.id
        )
        SELECT * FROM d
    """)
    suspend fun subtree(rootId: Long): List<FolderEntity>   // 移动/删除/循环校验用

    @Insert suspend fun insert(f: FolderEntity): Long
    @Update suspend fun rename(f: FolderEntity)
    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :ts WHERE id = :id")
    suspend fun move(id: Long, newParentId: Long?, ts: Long)
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: Long): FolderEntity?   // moveFolder 目标存在性校验 / renameFolder 读原文
}

@Dao
interface DocumentDao {
    // Room 无法参数化 ORDER BY：按 DocSort(TITLE/LAST_READ/ADDED/SIZE) 提供具名查询，Repository 按当前偏好分发
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY title COLLATE NOCASE")
    fun docsInByTitle(parentId: Long?): Flow<List<DocumentEntity>>          // null = 根
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY lastReadAt DESC, title COLLATE NOCASE")
    fun docsInByLastRead(parentId: Long?): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY addedAt DESC, title COLLATE NOCASE")
    fun docsInByAdded(parentId: Long?): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY fileSize DESC, title COLLATE NOCASE")
    fun docsInBySize(parentId: Long?): Flow<List<DocumentEntity>>

    // ESCAPE '\' + Repository 层 escapeLikePattern 转义，使 %/_ 按字面匹配
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY title COLLATE NOCASE")
    fun search(q: String): Flow<List<DocumentEntity>>       // 全库标题搜索，不限当前文件夹

    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): DocumentEntity?   // 重复导入检测

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC LIMIT :limit")
    fun recent(limit: Int = 20): Flow<List<DocumentEntity>>

    @Insert suspend fun insert(d: DocumentEntity): Long
    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)             // 仅改展示名，不动 relPath/物理文件
    @Query("UPDATE documents SET needsPassword = :v WHERE id = :id")
    suspend fun setNeedsPassword(id: Long, v: Boolean)
    @Query("UPDATE documents SET currentPage = :page, scrollOffsetY = :offset, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, offset: Int, ts: Long)   // 页码式（progressType="page"）
    @Query("UPDATE documents SET progressPayload = :payload, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgressPayload(id: Long, payload: String, ts: Long)  // 流式（progressType="cfi"）
    @Query("UPDATE documents SET pageCount = :n WHERE id = :id")
    suspend fun setPageCount(id: Long, n: Int)
    @Query("UPDATE documents SET folderId = :newFolderId WHERE id = :id")
    suspend fun move(id: Long, newFolderId: Long?)
    @Query("UPDATE documents SET folderId = NULL WHERE folderId IN (:folderIds)")
    suspend fun moveDocsInFoldersToRoot(folderIds: List<Long>)   // 删目录编排：后代文档批量回根（业务层事务内调用）
    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}
```

- `@Database(..., version = 1, exportSchema = true)`，schema JSON 提交进仓库 `core/data/schemas/` 便于后续迁移
- 进度表不独立：单文档单进度、无历史回溯需求，单独表只带来 JOIN 与冗余更新

## 删除目录策略（级联）

`folders` 表的 `parentId` **无自引用外键**（SQLite 对自引用 FK 处理复杂且仅约束存在性、不防循环），故删除由业务层 `LibraryRepository` 编排，遵循「禁止裸删非空目录」：

1. 删文件夹 `F` 时，先 `FolderDao.subtree(F.id)` 取 `F` 及全部后代目录
2. 一个 DB 事务内：
   - 将「`F` 及所有后代目录下」的文档 `folderId` 批量置 `null`（文档回根级，**保留文档**；物理 PDF 因扁平存储无需移动）
   - 按「子目录优先、父目录后删」顺序删除后代目录及 `F` 本身记录（避免 `parentId` 悬空成孤儿）
3. 事务提交成功即完成（无物理文件操作）；`sourceUri` 仅备查不依赖
4. 移动文件夹同理：校验循环后只改 `parentId`，不动物理文件

> **与 `DocumentEntity` 外键 `onDelete = SET_NULL` 的关系**：该 FK 仅作兜底安全网（万一业务层漏处理直接删 folder，DB 自动把直接子文档 `folderId` 置 null，不致崩溃）。正常路径走业务层显式批量置 null，便于在单事务内统一编排「后代文档回根 + 子目录先于父删」，不依赖 FK 副作用的隐式顺序。`folders.parentId` 无自引用 FK，故子目录删除顺序必须业务层保证（子优先于父），否则 `parentId` 悬空成孤儿。

## PDF 文件存储策略（取舍结论）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 复制到 `filesDir/library/` | 无权限、稳定、删除可控 | 占双倍空间；卸载/换机即丢，跨设备迁移需导出/备份（M8） | **采用** |
| 持久化 SAF Uri | 省空间 | 权限易丢失、Uri 跨重启偶发失败、缩略图需反复打开远端 | 不采用 |

实现（`core:storage` 的 `DocumentStorage`，M2 已落地）：
- 导入时 `contentResolver.openInputStream(uri)` 复制到 `File(filesDir, "library/<docId>.<ext>")`，DB 存 `relPath`
- **ID 与文件名**：`docId` 由 `newEntityId()` 生成（毫秒时间戳左移 20 位 + nanoTime 低 20 位，碰撞由 `retryOnIdCollision` 捕获 PRIMARY KEY 冲突重试兜底）；`title` 存用户友好名
- **路径安全（双闸）**：`copyIn` 对 ext 做字母数字白名单（1-10 位）；`resolve()` 对 relPath 做 canonical 包含校验（防 `../` 越出 libraryDir），`deleteDocument`/`deleteThumbnail`/`documentFile` 同受保护
- **`relPath` 扁平化**：物理文件只按文档 ID 平铺到 `library/`，**不按文件夹建子目录**。文件夹层级仅由 DB 的 `folderId` 表达，与物理路径解耦——这样移动文档只改 `folderId`，不动文件、不改 `relPath`；删除文件夹时也不会残留孤儿物理文件
- 删文档同时删本地 PDF + 缩略图文件：**先在 DB 事务内删记录，事务提交成功后再删物理文件**（DB 失败则文件不动，避免数据丢失；文件删除失败留为孤儿，由后续清理扫描兜底）
- 缩略图：**导入时后台生成**——导入验证的 `open()` 句柄顺带渲染首页（经 `DocumentViewerProvider.thumbnail(doc)`），PNG 存 `filesDir/thumb/<id>.png`；导入时无法生成（如加密文档）**则保持无缩略图——加密文档永不补生成，且打开时清除历史遗留（内容预览与加密语义冲突，M6 修正）**

## 备份与恢复存储 / DAO（M8）

> 设计见 process.md M8 分解；完整代码为事实源，此处仅记关键新增与约束。

- **DAO 新增（无 schema 变更，故无 Migration，遵守工作规则 3）**：`FolderDao`/`DocumentDao` 各增 `@Query("SELECT * FROM ...") all()`（一次性全量快照，供导出）与 `@Upsert upsert(...)` / `upsertAll(...)`（按原 ID 合并写回，冲突以备份为准 → 幂等，重复恢复不产生重复记录）。
- **`DocumentStorage` 备份方法**：`openRead(relPath)` / `openThumbnailRead(...)` 流式读取（导出用，不存在或 `thumbnailRelPath` 非 `thumb/<id>.png` 白名单返回 null 计入缺失）；`deleteThumbnail` 同样先白名单后 canonical（安全审计 L3）；`totalBytesOf(relPaths)` 恢复前空间预检；`restoreFile` / `restoreThumbnail`（保留原 `relPath` 的流式写回，先白名单后 canonical 双重防穿越）；`newBackupTempDir()` 在 `filesDir` 下创建与 `library/` 同分区临时目录；`moveIntoLibrary` / `moveIntoThumb`（同分区 `renameTo` 瞬时，跨设备回退复制）。
- **`BackupCodec`（纯逻辑，JVM 可测，`core:data` 内）**：`library.json` 快照编解码（`BackupManifest { schemaVersion, exportedAt, appVersion, folders[], documents[] }`）；不可信边界处对 `relPath` / `thumbnailRelPath` 做白名单校验、对 `schemaVersion` 做版本门禁（当前 `=1`）。
- **`BackupManager`**：导出 = 读全量 → 流式写 zip（`files/` 物理文档 + `thumbs/` 缩略图 + `library.json`）；恢复 = 整体解压到临时目录（`entry.name` 严格白名单，拒绝 `../` 与未知条目，防 zip slip；资源上限 `RestoreLimits`——条目数 ≤512 / 单条目解压 ≤512MB / 累计解压 ≤1GB / `library.json` ≤4M 字符 / 单条目压缩比 ≤100x（小包按 8MB 下限豁免），超限即中止，防 zip bomb 与磁盘耗尽，安全审计 M2）→ 校验清单 → 空间预检 → 事务内目录按拓扑序 upsert 再文档 upsert（满足 `folderId` 外键）→ 提交后把临时文件 move 进 `library/`/`thumb/`；失败临时目录由调用方 `deleteRecursively`，DB 因事务原子性保持未变。
- **序列化选型**：`kotlinx-serialization-json` 1.7.3（M8 唯一偏离选型的依赖，见 architecture.md「技术选型」+「依赖项清单」），类型安全、跨 schema 版本可演进；零运行时闭源风险、AGPL 兼容。

## 进度记忆实现时机（防频繁写库）

1. `ReaderViewModel` 持有 `MutableStateFlow<ProgressPayload?> currentProgress`（M9 统一为 sealed：`ProgressPayload.Page(page, scrollOffsetY)` 页码式 / `ProgressPayload.Cfi(cfi)` 流式；null = 尚未回调）
2. `DocumentViewerProvider.Content` 的 `onProgressChanged` 回调更新该状态（引擎无关，统一类型；页码式水平 offset 恒 0、垂直含像素偏移；流式 epub 由 viewer 回调 `Cfi(bookmark 串)`）
3. 防抖写库（按 `ProgressPayload` 类型分发到 Repository 的 `savePageProgress` / `saveCfiProgress`；M4 落地 Job 版防抖，`PROGRESS_DEBOUNCE_MS = 600`）：
   ```kotlin
   fun onProgress(payload: ProgressPayload) {
       if (currentProgress.value == payload) return
       currentProgress.value = payload
       progressJob?.cancel()                    // 静止期后仅写最新值：翻页/滚动重置计时
       progressJob = viewModelScope.launch(io) {
           delay(PROGRESS_DEBOUNCE_MS)
           when (payload) {                     // 按类型分发（M9）
               is ProgressPayload.Page -> repo.savePageProgress(docId, payload.page, payload.scrollOffsetY)
               is ProgressPayload.Cfi  -> repo.saveCfiProgress(docId, payload.cfi)
           }
       }
   }
   ```
4. **保底写入**：`ReaderScreen` 的 `LifecycleEventObserver` 在 `ON_PAUSE` / `ON_STOP` 与 `DisposableEffect.onDispose` 时调 `flushProgress()`——取消防抖 Job 立即按当前进度整体写库（幂等，重复 flush 只多写同值），保证离开时进度已落盘
5. `pageCount` 只在首次（`-1`→实际值，页码式）写一次（首开兜底 `backfillAfterOpen`），不进入防抖流；**流式文档（`doc.reflowable`）跳过**（页数随排版变化，恒 -1）
6. `scrollOffsetY` 语义（M7 启用）：垂直模式由渲染端回写实测像素偏移，水平模式恒 0；恢复时 `Ready.initialProgress` 透传（页码式取 `item.scrollOffsetY.coerceAtLeast(0)`，流式取 `item.progressPayload` 组 `Cfi`），垂直实现 `scrollToItem(page, offset)` 一次性锚定（跨宽度恢复有近似误差，见 ui-design.md）

```
进度变化 → onProgress(payload)  ─debounce 600ms─→  savePageProgress / saveCfiProgress（按 payload 类型分发）
退出    → ON_PAUSE/ON_STOP/onDispose ─flush 立即写库─→ finish
```

## 流式（EPUB）CFI 进度锚点（M9）

- `progressType="cfi"` 时进度写 `progressPayload`（JSON），由 `core:document` 的 `EpubProgressCodec`（纯逻辑）编解码：`{"v":1,"loc":"<bookmark>","page":N,"chapter":"..."}`，带 `version` 门禁与脏输入拒绝（解码失败返回 null，调用方回落安全默认）。
- **锚点** `loc` = fitz `makeBookmark` 返回的 long bookmark 十进制串，经 `MuPDFCore.pageToBookmark/bookmarkToPage` 往返——**跨重排稳定**（epub 排版随视口变化、页码漂移，故以 bookmark 为权威锚）。恢复时 `bookmarkToPage(loc)` 得当前排版页码再跳转；无 `loc` 时回落 payload 内 `page`（L3）。
- `pageCount` 对流式恒 -1（不落库）；书架卡片显示格式标签（EPUB）而非页数，最近阅读显示「继续阅读」而非「第 N 页」。

## 轻量偏好持久化（DataStore，M6 排序 / M7 设置）

- **存储**：`PreferenceDataStoreFactory` 创建 `DataStore<Preferences>`，**双偏好文件**（M7 起两个）：
  - `library_prefs.preferences_pb` → `LibraryPrefs`（书架排序，M6）
  - `settings_prefs.preferences_pb` → `SettingsPrefs`（默认阅读模式 + 主题，M7）
  - 实例由 app 层 `RepositoryModule` 以自定义限定符 `@LibraryPrefsStore` / `@SettingsPrefsStore` 分别 `@Provides @Singleton` 提供（**单文件必须单实例**，1.1.x 会对同文件第二实例抛 `IllegalStateException`；限定符定义在 `core/data/prefs/PrefsStoreQualifiers.kt`），`core:data` 不自建 Hilt module
- **读写**：`intPreferencesKey` 存 **ordinal**——`LibraryPrefs.docSort: Flow<DocSort>`、`SettingsPrefs.defaultReaderMode: Flow<ReaderMode>` / `themeMode: Flow<ThemeMode>`（枚举均在 core:model）+ 各自 `suspend fun setXxx`；读流 `.catch { emit(emptyPreferences()) }`（IO 失败回落默认不崩调用方 stateIn）、`null`/越界 ordinal 回退默认值（TITLE / HORIZONTAL / SYSTEM）；写方法不吞异常，VM 层 try/catch 静默降级分工（M6 code-review 惯例）
- **约束：`DocSort` / `ReaderMode` / `ThemeMode` 枚举均不可重排/在中间插入新值**（按 ordinal 持久化；违反仅导致回落默认值，可接受但应避免，仅允许尾部追加）
- **接线**：`LibraryViewModel.documents = prefs.docSort.flatMapLatest { repository.docsIn(folderId, it) }`（M6）；`ReaderViewModel.openDocument` 入口 `settingsPrefs.defaultReaderMode.first()` 取会话初值（M7，`modeInitialized` 守卫防密码重试重置）；设置页三组偏好同源读写（排序与书架 SortMenu 双向同步零代码）；MainActivity 消费 `themeMode`（见 ui-design.md「主题」）
- **测试**：`LibraryPrefsTest` / `SettingsPrefsTest`（Robolectric + TemporaryFolder 真实文件；DataStore scope 须共享 `testScheduler` 的 `UnconfinedTestDispatcher`，否则虚拟时间推不动其内部协程；每用例独立文件）；feature 层纯 JVM 测试亦可直接 `PreferenceDataStoreFactory.create(scope = ...) { File }` 构造真实例（ReaderViewModelTest / SettingsViewModelTest 先例，无需 Robolectric）
