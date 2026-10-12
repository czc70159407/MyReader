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
    val needsPassword: Boolean = false,  // 导入验证为加密文档 → true；解密成功后清除
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
- 缩略图：**导入时后台生成**——导入验证的 `open()` 句柄顺带渲染首页（经 `DocumentViewerProvider.thumbnail(doc)`），PNG 存 `filesDir/thumb/<id>.png`；导入时无法生成（如加密文档）则首次打开兜底

## 进度记忆实现时机（防频繁写库）

1. `ReaderViewModel` 持有 `MutableStateFlow<Int?> currentPage`（页码式 M4；流式 Cfi 进度 M9 按同型扩展）
2. `DocumentViewerProvider.Content` 的 `onProgressChanged` 回调更新该状态（引擎无关，统一类型）
3. 防抖写库（按 `ProgressPayload` 类型分发，对应 Repository 的 `savePageProgress` / `saveCfiProgress`；M4 已落地为 Job 版防抖，`PROGRESS_DEBOUNCE_MS = 600`）：
   ```kotlin
   fun onPageChanged(page: Int) {
       currentPage.value = page
       progressJob?.cancel()                    // 静止期后仅写最新页：翻页重置计时
       progressJob = viewModelScope.launch(io) {
           delay(PROGRESS_DEBOUNCE_MS)
           repo.savePageProgress(docId, page, 0)   // 流式文档走 saveCfiProgress
       }
   }
   ```
4. **保底写入**：`ReaderScreen` 的 `LifecycleEventObserver` 在 `ON_PAUSE` / `ON_STOP` 与 `DisposableEffect.onDispose` 时调 `flushProgress()`——取消防抖 Job 立即按当前页写库（幂等，重复 flush 只多写同值），保证离开时进度已落盘
5. `pageCount` 只在首次（`-1`→实际值，页码式）写一次（首开兜底 `backfillAfterOpen`），不进入防抖流；流式文档跳过

```
进度变化 → onPageChanged  ─debounce 600ms─→  savePageProgress / saveCfiProgress（按 payload 类型分发）
退出    → ON_PAUSE/ON_STOP/onDispose ─flush 立即写库─→ finish
```
