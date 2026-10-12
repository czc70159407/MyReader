# UI 分册：Compose 渲染集成 / UI 架构 / 导入流程 / 错误处理边界

> AGENTS.md 文档体系分册之一：改阅读器渲染、路由、目录树、导入流程、错误边界时更新本文档（工作规则 5）；状态与验收证据在根 AGENTS.md。

## MuPDF 在 Compose 中的集成

### Compose 桥接（AndroidView）

```kotlin
// feature/viewer-mupdf/viewermupdf/MuPdfReaderView.kt（签名示意；viewer 内部可自由使用 MuPDF 具体类，
// 对外只经 MuPdfViewerProvider.Content(doc: Document) 暴露，内部 cast 为 MuPdfDocument 取出 MuPDFCore。
// 1.28.2 实测：MuPDF 底层类为 ReaderView(Context)，core 经 setAdapter(PageAdapter) 注入，见下方 API 备注）
@Composable
fun MuPdfReaderView(
    core: MuPDFCore,
    initialProgress: ProgressPayload.Page,
    mode: ReaderMode,                          // HORIZONTAL / VERTICAL
    onProgressChanged: (ProgressPayload.Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<ReaderView?>(null) }
    AndroidView(
        factory = { ctx ->
            ReaderView(ctx).apply {
                // setAdapter(PageAdapter 包装 core)；构造签名以 submodule 源码为准，M4 集成时最终确认
                setDisplayedViewIndex(initialProgress.page)
                setViewListener(object : ViewListener {
                    override fun onPageChanged(page: Int, offset: Int) {
                        onProgressChanged(ProgressPayload.Page(page, offset))
                    }
                })
            }.also { viewRef.value = it }
        },
        update = { v -> /* 模式切换、跳页差分 */ },
        modifier = modifier,
    )
    DisposableEffect(core) {
        onDispose { viewRef.value?.releaseBitmaps() }
    }
}
```

### 渲染与内存策略

- **优先用 MuPDF 内置瓦片渲染**：`ReaderView` 内部基于 `TileView` + `BitmapPool`，按视口预渲染相邻页、远页降级。直接用，不要自己 draw Bitmap
- 限制最大 Bitmap 内存：`BitmapPool` 约 24MB，超出回收远页
- 超大 PDF（>200 页/300MB）：`MuPDFCore` 默认只加载页数索引，本身就 OOM 友好；水平模式预渲染窗口收到 ±1 页
- 垂直连续滚动模式（v1.1）：`LazyColumn` 渲染每页一个 `Image`，每页 Bitmap 由 `MuPDFCore.drawPage(...)` 在 `Dispatchers.Default` 异步生成 + `LruCache` 缓存（按页号，限制 8 页），`onScrolled` 滚出可视区时 `bitmap.recycle()`（注意 `MuPDFCore` 非线程安全，并发绘制需串行化）
- **配置变更（横竖屏/键盘）**：阅读页 Activity 设 `android:configChanges="orientation|screenSize|keyboardHidden"`，自行处理配置变更，**避免 Activity 重建导致文档句柄（底层 `MuPDFCore` 的 JNI native 句柄）销毁重建**；句柄以 `Document` 接口形式由 `ReaderViewModel` 持有（reader 只见接口，底层是 `MuPdfDocument` 包装的 `MuPDFCore`），配置变更时 ViewModel 不销毁、句柄复用，`MuPdfReaderView`（我们的 Composable 包装）在 `update` 块按需重测布局
- **native 资源释放**：`Document.close()` 由 `MuPdfDocument` 转发到底层 `MuPDFCore` 的 native 释放（实测为 `onDestroy()`）并 `releaseBitmaps()`；`DisposableEffect` 与 `ReaderViewModel.onCleared()` 都触发 `close()`，防止跨多次进出阅读器累积 native 内存泄漏

> **MuPDF 核心 API（1.28.2 submodule `lib/` 实测，包名 `com.artifex.mupdf.viewer`，Java 源码）**：
> - `MuPDFCore`：构造器 `MuPDFCore(byte[] buffer, String magic)` / `MuPDFCore(SeekableInputStream stm, String magic)`（**无 Context+path 版本**，magic 如 "pdf"）；`countPages()` / `getPageSize(pageNum): PointF` / `drawPage(Bitmap, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, Cookie)`（全页渲染：patch 取整页，Bitmap 用 `ARGB_8888`，参照 PageView 用法）/ `onDestroy()`（native 释放）
> - `ReaderView(Context)`：瓦片渲染 + BitmapPool；core 经 `setAdapter(PageAdapter)` 注入（PageAdapter 构造签名以源码为准，M4 集成确认）；`setDisplayedViewIndex(page)` 跳页 / `getDisplayedViewIndex()` 当前页

### 阅读模式选择

| 模式 | 适合 | 进度记忆 | 性能 | 默认 |
|---|---|---|---|---|
| 水平分页 | 单页/双页书籍式 | 页码即可，简单 | MuPDF 内置最优 | **默认** |
| 垂直连续滚动 | 长文档/论文/扫描件 | 页码+像素偏移 | 需自管理 Bitmap | 设置可选（v1.1） |

### 跳页与进度获取

- 跳页：`readerView.setDisplayedViewIndex(page)` —— 由 ViewModel 传入上次 `currentPage`
- 当前页：`onPageChanged(page, offset)` 回调 —— 用来回写进度
- 大纲导航：`Document.outline()` 返回 `List<OutlineEntry>`（title/pageIndex/children）；顶部栏「目录」按钮弹出 `OutlineSheet`（ModalBottomSheet，展平缩进列表，复用目录树的扁平渲染模式），点击条目 `setDisplayedViewIndex(entry.pageIndex)`

## UI 层架构

### 路由

```
nav/MyReaderNavHost.kt
routes:
  library/{folderId?}           # 书架/目录树
  reader/{documentId}           # 阅读器
  folderPicker/{documentId?}    # 选/建目录用于移动或导入
  settings                      # 阅读模式/主题/书架排序等偏好（DataStore 持久化）
```

### 目录树展示（关键）

**采用展平为带缩进列表**（不递归嵌套 Composable）：
- 递归组件会让 Compose 重组时栈过深，且无法复用 `LazyColumn` 的回收，大目录会卡
- 用 `FolderTreeState` 维护展开状态，`flat` 输出 `List<FolderNode>`（每节点带 `depth`）
- `LazyColumn` 按 `depth` 加左 padding
- 长按弹菜单：重命名/删除/移动/新建子目录
- `moveFolder` 前用 `subtree()` 校验新父目录不在当前子树内（防循环，FK 只约束存在性不防循环）

```kotlin
class FolderTreeState(private val repo: LibraryRepository) {   // 仓库统一为 LibraryRepository（目录+文档编排一体，不单设 FolderRepository）
    val expanded: SnapshotStateMap<Long, Boolean> = mutableStateMapOf()
    val flat: StateFlow<List<FolderNode>> = …   // combine(rootFlow, expanded)
    fun toggle(id: Long)
}

@Composable
fun FolderTreeList(
    state: FolderTreeState,
    onFolderClick: (Long?) -> Unit,
    onFolderLongPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
)
```

### 搜索、排序与文档操作

- **搜索**：`LibraryScreen` 顶部搜索框 → `DocumentDao.search(q)`（`title LIKE`，**全库匹配不限当前文件夹**），结果复用文档条目 UI，点击直达阅读器
- **排序**：`DocSort` 枚举（`TITLE` / `LAST_READ` / `ADDED` / `SIZE`）放 `core:model`，当前值存 DataStore；书架顶部切换，`LibraryRepository` 按 `DocSort` 分发到 `docsInBy*` 具名查询（Room 无法参数化 `ORDER BY`），默认 `TITLE`
- **文档长按菜单**：重命名（`DocumentDao.rename`，仅改 `title` 不动 `relPath`/物理文件）/ 移动（复用 `folderPicker` 路由）/ 删除——与目录树操作对称

### 导入流程

1. `LibraryScreen` 工具栏"+"按钮 → `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`，mime 数组按已注册引擎的 `fileType` 聚合（如 `application/pdf`, `application/epub+zip`, `image/vnd.comicbook`），支持多选
2. 选中后弹 `FolderPickerDialog`（默认当前目录，可选新建子目录）
3. `LibraryViewModel.import(uris, targetFolderId)` 在 `Dispatchers.IO` 逐个处理：
   - 复制到 `filesDir/library/<docId>.<ext>`（扁平，不建文件夹子目录；docId 为生成 ID，扩展名取自 Uri 或 mime 推断、经 `DocumentStorage` 白名单校验），复制时**流式计算 SHA-256 `contentHash`**
   - **重复检测**：`DocumentDao.findByHash` 命中 → 弹窗「已存在《title》，是否仍导入？」；用户跳过则删刚复制的文件，继续下一个
   - **打开验证**（异常分流见 `DocumentException`）：
     - `engine.open()` 成功 → 句柄顺带读 `pageCount`、渲染首页生成缩略图，然后 `close()`
     - 抛 `PasswordRequiredException` → 照常入库，置 `needsPassword = true`（暂无 pageCount/缩略图）
     - 抛 `CorruptDocumentException` 等 → 删半成品文件、不入库，提示「无法读取」
   - 由 mime/扩展名解析 `fileType`，插入 `DocumentEntity`（`pageCount` 已知则填否则 -1，`progressType` 默认按类型：页码式 "page"、流式 "cfi"）
   - 复制/插入失败的 Uri 回滚该条（事务 + 删半成品文件）
4. 完成后刷新当前目录列表
5. 首次打开兜底：`pageCount == -1` 或缺缩略图时（如加密文档解密成功后），`ReaderViewModel` 经 `DocumentEngineRouter.engineFor(doc.fileType).open(...)` 补齐 `pageCount`、清 `needsPassword` 并触发缩略图生成

## 错误处理与边界

| 场景 | 处理 |
|---|---|
| 损坏/非 PDF 文件 | 导入时 `engine.open()` 抛 `CorruptDocumentException` → 删半成品文件、不入库，提示「无法读取」；打开时读 `pageCount` 抛错 → 标记 `pageCount = -2`（错误标记，区别于 `-1` 未知），书架条目置灰显示「已损坏」，长按可删除记录或重新导入，不自动重试、不崩溃 |
| 密码保护文档 | 导入时 `engine.open()` 抛 `PasswordRequiredException` → 照常入库置 `needsPassword = true`；打开时先弹密码对话框，`engine.open(path, fileType, password)` 成功后清除标记、补 `pageCount` 与缩略图；密码不持久化（v1 仅会话内） |
| 重复导入 | 导入时 `contentHash` 命中已有记录 → 弹窗「已存在《title》，是否仍导入」，可跳过（跳过则删刚复制的文件） |
| 存储空间不足 | 复制前 `StatFs` 检查 `filesDir` 可用空间 ≥ 文件大小 + 余量，不足则提示并中止，不写半文件 |
| `countPages` 失败 | 进度页码保持 0，UI 提示「打开失败」并返回书架，标记 `pageCount = -2` 不重复尝试（书架引导删除/重导） |
| 文件丢失（`relPath` 指向不存在） | 打开时 `File.exists()` 为 false → 书架该条目置「缺失」标记，引导删除记录或重新导入 |
| 大文件复制中断 | 导入流程事务 + 删半成品回滚；UI 显示进度 + 可取消 |
