# UI 分册：Compose 渲染集成 / UI 架构 / 导入流程 / 错误处理边界

> AGENTS.md 文档体系分册之一：改阅读器渲染、路由、目录树、导入流程、错误边界时更新本文档（工作规则 5）；状态与验收证据在根 AGENTS.md。

## MuPDF 在 Compose 中的集成

### Compose 桥接（AndroidView）

```kotlin
// feature/viewer-mupdf/viewermupdf/MuPdfReaderView.kt（M4 已落地，1.28.2 实测：
// 上游 ReaderView 无 setViewListener/ViewListener——页面变化经 protected 钩子 onMoveToChild 暴露）。
// 跳页机制：initialPage 变化（重组传入新值）时在 AndroidView update 块与 getDisplayedViewIndex()
// 差分，不同则 setDisplayedViewIndex——跳页/大纲导航由外部状态驱动，无需命令式引用。
@Composable
fun MuPdfReaderView(
    core: MuPDFCore,
    initialPage: Int,
    onProgressChanged: (ProgressPayload.Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestCallback by rememberUpdatedState(onProgressChanged)   // factory 只执行一次，经 State 转发最新回调
    val adapterRef = remember { mutableStateOf<PageAdapter?>(null) }
    AndroidView(
        factory = { ctx ->
            val adapter = PageAdapter(ctx, core)
            adapterRef.value = adapter
            object : ReaderView(ctx) {
                override fun onMoveToChild(i: Int) {
                    super.onMoveToChild(i)
                    latestCallback(ProgressPayload.Page(i))
                }
                override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                    super.onSizeChanged(w, h, oldw, oldh)
                    // 旋转/分屏等尺寸变化必须 refresh()：复用的 PageView 持旧尺寸位图会白屏
                    // （M4 真机回归发现；refresh 重建子 view + adapter 页尺寸缓存）。
                    // 分屏拖动每帧触发 onSizeChanged，全量重建成本高——去抖 150ms 静止后执行一次。
                    // 去抖窗口内 onLayout2 会以「旧布局值 + 新屏宽」误判自动翻页 → 锁存并恢复
                    // 尺寸变化瞬间的 mCurrent（完整实现见 MuPdfReaderView.kt：lockedCurrent 机制）
                    if (oldw <= 0 || (w == oldw && h == oldh)) return
                    refreshPending?.let(::removeCallbacks)
                    refreshPending = Runnable { /* 恢复锁存页 + refresh() */ }
                        .also { postDelayed(it, SIZE_SETTLE_DELAY_MS) }
                }
            }.apply {
                setAdapter(adapter)
                setDisplayedViewIndex(initialPage)
            }
        },
        update = { v -> if (v.getDisplayedViewIndex() != initialPage) v.setDisplayedViewIndex(initialPage) },
        modifier = modifier,
    )
    DisposableEffect(core) {
        onDispose { adapterRef.value?.releaseBitmaps() }   // 释放共享 HQ 位图（ReaderView 自身无公开 releaseBitmaps）
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

- 跳页：外部状态（GoToPageDialog / OutlineSheet）写入 `uiPage` → 经 `initialProgress` 传入渲染端，`update` 块与 `getDisplayedViewIndex()` 差分后调 `readerView.setDisplayedViewIndex(page)`
- 当前页：覆写 `ReaderView.onMoveToChild(page)` 回调（1.28.2 无 ViewListener 接口）——用来回写进度
- 进度回写：翻页回调 → `ReaderViewModel.onPageChanged` 防抖 600ms 落库（静止期后仅写最新页）；`ON_PAUSE`/`ON_STOP`/离开组合时 `flushProgress()` 立即落库保底（见 docs/data-layer.md「进度记忆实现时机」）
- 大纲导航：`Document.outline()` 返回 `List<OutlineEntry>`（title/pageIndex/children）；顶部栏「目录」按钮弹出 `OutlineSheet`（ModalBottomSheet，打开时懒加载，展平缩进列表，复用目录树的扁平渲染模式），点击条目 `uiPage = entry.pageIndex` 经同一差分机制跳页

## UI 层架构

### 路由

```
nav/MyReaderNavHost.kt
routes:
  library?folderId={folderId}   # 书架/目录浏览（M5：folderId 可选，缺省=根；startDestination 须写完整模式。
                                #   注意 NavType.LongType 不允许 nullable（运行时抛 IllegalArgumentException），
                                #   用哨兵 defaultValue=-1L 表示根（Routes.FOLDER_ID_NONE），VM 中转 null；
                                #   Routes.library(null) 必须返回 "library"——空参数串 LongType 解析也会崩）
  reader/{documentId}           # 阅读器
  settings                      # 阅读模式/主题/书架排序等偏好（DataStore 持久化，M7）
```

> 「移动/导入选目录」用 **FolderPickerDialog**（M5 实现落定，原设计的 folderPicker 独立路由废弃）——弹窗内嵌目录树 + 新建子目录，避免为选目录增加路由栈层级。

### 目录树展示（关键）

**采用展平为带缩进列表**（不递归嵌套 Composable）：
- 递归组件会让 Compose 重组时栈过深，且无法复用 `LazyColumn` 的回收，大目录会卡
- 用 `FolderTreeState` 维护展开状态，`flat` 输出 `List<FolderNode>`（每节点带 `depth`）
- `LazyColumn` 按 `depth` 加左 padding
- 长按弹菜单：重命名/删除/移动/新建子目录
- `moveFolder` 前用 `subtree()` 校验新父目录不在当前子树内（防循环，FK 只约束存在性不防循环）；FolderPickerDialog 移动文件夹场景以 `subtree(id)` 预过滤禁选项（UI 前置）+ 业务层 require 兜底

```kotlin
// M5 落定实现（feature/library/FolderTree.kt）：
class FolderTreeState(
    repo: LibraryRepository,        // 文档给定签名；次构造经 repo.observeAllFolders() 转发
    scope: CoroutineScope,          // snapshotFlow 需 CoroutineScope——由 VM 持有（viewModelScope）
) {
    val expanded: SnapshotStateMap<Long, Boolean>   // mutableStateMapOf，缺失/false=收起
    val flat: StateFlow<List<FolderNode>>           // combine(全表 Flow, snapshotFlow) → 组树展平
    fun toggle(id: Long)
}

@Composable
fun FolderTreeList(
    state: FolderTreeState,
    onFolderClick: (Long?) -> Unit,
    onFolderLongPress: (Long) -> Unit,   // picker 内传 {} 禁用（选目标弹窗单一职责）
    modifier: Modifier = Modifier,
    selectedId: Long? = null,            // picker 高亮选中项
)
```

- **组树方式（M5 落定）**：`FolderDao.observeAll()` 全表 Flow（目录量小全表无压力）→ `repo.observeAllFolders()` → 内存组树展平；比「一次性 subtree() 缓存」更简洁且自动响应增删改（重命名/移动后树自动刷新），同层按 `sortOrder, name` 排序（与 `foldersIn` 一致）；展平逻辑提取为纯函数 `buildFlatTree(folders, expandedIds)` 便于 JVM 单测
- **书架页单层浏览（M5 UX 决策）**：书架显示当前目录的子文件夹卡片 + 文档网格（同一 `LazyVerticalGrid`，文件夹在前），点击文件夹 navigate 到 `library?folderId=xx`、顶栏返回箭头 popBackStack；顶栏标题从全量目录流派生（目录被删时回退「书架」，不崩溃）；FolderTreeList 用于 FolderPickerDialog（移动/导入选目标）

### 搜索、排序与文档操作

- **搜索**：`LibraryScreen` 顶部搜索框 → `DocumentDao.search(q)`（`title LIKE`，**全库匹配不限当前文件夹**），结果复用文档条目 UI，点击直达阅读器
- **排序**：`DocSort` 枚举（`TITLE` / `LAST_READ` / `ADDED` / `SIZE`）放 `core:model`，当前值存 DataStore；书架顶部切换，`LibraryRepository` 按 `DocSort` 分发到 `docsInBy*` 具名查询（Room 无法参数化 `ORDER BY`），默认 `TITLE`
- **文档长按菜单**：重命名（`DocumentDao.rename`，仅改 `title` 不动 `relPath`/物理文件）/ 移动（`FolderPickerDialog`）/ 删除——与目录树操作对称。落点（M5）：文件夹与文档卡片 `combinedClickable` 长按弹出各自 `DropdownMenu`（文件夹：重命名/删除/移动/新建子目录；文档：重命名/移动/删除），删除走 `DeleteConfirmDialog`（目录文案含「文档将移至根目录」，文档文案含「物理文件一并删除」）

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
| 损坏/非 PDF 文件 | 导入时 `engine.open()` 抛 `CorruptDocumentException` → 删半成品文件、不入库，提示「无法读取」；打开时读 `pageCount` 抛错 → 标记 `pageCount = -2`（错误标记，区别于 `-1` 未知），书架条目置灰显示「已损坏」，长按可删除记录（「重新导入」重选源文件替换为后续里程碑，M5 仅提供删除入口），不自动重试、不崩溃 |
| 密码保护文档 | 导入时 `engine.open()` 抛 `PasswordRequiredException` → 照常入库置 `needsPassword = true`；打开时先弹密码对话框，`engine.open(path, fileType, password)` 成功后清除标记、补 `pageCount` 与缩略图；密码不持久化（v1 仅会话内） |
| 重复导入 | 导入时 `contentHash` 命中已有记录 → 弹窗「已存在《title》，是否仍导入」，可跳过（跳过则删刚复制的文件） |
| 存储空间不足 | 复制前 `StatFs` 检查 `filesDir` 可用空间 ≥ 文件大小 + 余量，不足则提示并中止，不写半文件 |
| `countPages` 失败 | 进度页码保持 0，UI 提示「打开失败」并返回书架，标记 `pageCount = -2` 不重复尝试（书架引导删除/重导） |
| 文件丢失（`relPath` 指向不存在） | 打开时 `File.exists()` 为 false → 书架该条目置「缺失」标记，引导删除记录（「重新导入」为后续里程碑，M5 仅提供删除入口） |
| 大文件复制中断 | 导入流程事务 + 删半成品回滚；UI 显示进度 + 可取消 |
