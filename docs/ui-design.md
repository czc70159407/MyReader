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
- 垂直连续滚动模式（M7 已落地）：`MuPdfVerticalView` = `LazyColumn` 每页一项 + `VerticalPageLoader` 渲染编排（feature:viewer-mupdf 内，Compose 无关可 Robolectric 测试）。要点：
  - **线程模型（缺一不可）**：MuPDFCore 非线程安全 → 所有 `pageSize`/`drawPage` 收进 `Mutex` 串行化；`Bitmap.createBitmap` + `drawPage` 在 Mutex 临界区内 `withContext(Dispatchers.Default)` 执行（调用方是 Main 协程，native 渲染主线程执行必卡帧）。`Mutex.withLock` 为可取消挂起点：快速滚动时 item 离场、排队加载自动终止；取锁后 `ensureActive()` 再确认减少完成即弃的浪费渲染
  - **内存**：`LruCache<Pair<页号,目标宽>, Bitmap>` 按**字节预算** `maxMemory/8`（clamp 16~48MB，典型设备等效 4~8 页；不按固定页数——2K 屏横屏单页可达 30MB）；key 含宽度，旋转/分屏后旧位图自然失配 + `LaunchedEffect(widthPx)` 立即 `evictAll()` 清引用。**不手动 `recycle()`**（原设计已修订）：minSdk 26 位图像素数据在 native heap、由 GC 经 NativeAllocationRegistry 释放，recycle 一个仍被 Image 绘制/状态持有的位图必崩；淘汰仅移除引用
  - **布局**：页点尺寸经 `Document.pageSize()`（`getPageSize` 兼做页面预热）异步获取后定 item 高度；未就绪占位 4:3 + 加载圈；单页位图高度上限 4096（超长页等比降分辨率，防低端 GPU 纹理溢出）；点尺寸缓存于 loader 跨重组/跨宽度复用，防滚动中高度抖动
  - **进度**：`snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }` 每帧发射 → `Page(index, offset)` 回写，防抖 600ms 落库由 VM 承担；恢复 = `scrollToItem(initialPage, initialOffsetY)` 一次性锚定（LazyList 锚「item 顶部 + 像素偏移」，占位高度收敛不影响锚点）；**跨宽度恢复的旧偏移有近似误差**（同宽度精确，已知限制）
  - **外部跳页差分（防回环关键）**：`lastEmittedPage` 守卫区分「自身滚动回流」（page 相等 no-op）与「外部跳页」（`initialPage` 变化 → `scrollToItem(page, 0)` 顶部对齐）；跳页/大纲写 `uiPage` 时同步置 `uiOffsetY = 0`
- **配置变更（横竖屏/键盘）**：阅读页 Activity 设 `android:configChanges="orientation|screenSize|keyboardHidden"`，自行处理配置变更，**避免 Activity 重建导致文档句柄（底层 `MuPDFCore` 的 JNI native 句柄）销毁重建**；句柄以 `Document` 接口形式由 `ReaderViewModel` 持有（reader 只见接口，底层是 `MuPdfDocument` 包装的 `MuPDFCore`），配置变更时 ViewModel 不销毁、句柄复用，`MuPdfReaderView`（我们的 Composable 包装）在 `update` 块按需重测布局
- **native 资源释放**：`Document.close()` 由 `MuPdfDocument` 转发到底层 `MuPDFCore` 的 native 释放（实测为 `onDestroy()`）并 `releaseBitmaps()`；`DisposableEffect` 与 `ReaderViewModel.onCleared()` 都触发 `close()`，防止跨多次进出阅读器累积 native 内存泄漏

> **MuPDF 核心 API（1.28.2 submodule `lib/` 实测，包名 `com.artifex.mupdf.viewer`，Java 源码）**：
> - `MuPDFCore`：构造器 `MuPDFCore(byte[] buffer, String magic)` / `MuPDFCore(SeekableInputStream stm, String magic)`（**无 Context+path 版本**，magic 如 "pdf"）；`countPages()` / `getPageSize(pageNum): PointF` / `drawPage(Bitmap, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, Cookie)`（全页渲染：patch 取整页，Bitmap 用 `ARGB_8888`，参照 PageView 用法）/ `onDestroy()`（native 释放）
> - `ReaderView(Context)`：瓦片渲染 + BitmapPool；core 经 `setAdapter(PageAdapter)` 注入（PageAdapter 构造签名以源码为准，M4 集成确认）；`setDisplayedViewIndex(page)` 跳页 / `getDisplayedViewIndex()` 当前页

### 阅读模式选择

| 模式 | 适合 | 进度记忆 | 性能 | 现状（M7） |
|---|---|---|---|---|
| 水平分页 | 单页/双页书籍式 | 页码（offset 恒 0） | MuPDF 内置最优 | **默认**（设置页可改默认值） |
| 垂直连续滚动 | 长文档/论文/扫描件 | 页码+像素偏移 | 自管理 Bitmap（字节预算 LruCache） | **已实现**（设置页默认 + 阅读器顶栏实时切换） |

- 默认值来自设置页「默认阅读模式」偏好（`SettingsPrefs`，DataStore 持久化）；**阅读器内切换是会话态**（`ReaderViewModel.mode`，`toggleMode()` 顶栏图标），不回写偏好——避免「打开 A 文档切一下垂直，所有新文档都变垂直」；`modeInitialized` 守卫防密码重试/重入把用户已切换的模式打回默认（初值在 `openDocument` 入口取，密码弹窗阶段即就位）
- 切换保持当前页：两实现是不同 Composable，`when(mode)` 切换即旧实现离场（水平 `releaseBitmaps` / 垂直 `evictAll`）、新实现以当前 `(uiPage, uiOffsetY)` 全新组合；H→V offset 恒 0 停当前页顶部，V→H 丢弃 offset 定位当前页；顶栏页码/跳页框/大纲在两模式共用同一 `uiPage`

### 跳页与进度获取

- 跳页（水平）：外部状态（GoToPageDialog / OutlineSheet）写入 `uiPage` → 经 `initialProgress` 传入渲染端，`update` 块与 `getDisplayedViewIndex()` 差分后调 `readerView.setDisplayedViewIndex(page)`
- 跳页（垂直）：同一外部状态写入 `uiPage`（并置 `uiOffsetY = 0` 顶部对齐）→ `MuPdfVerticalView` 的 `LaunchedEffect(initialPage)` 经 `lastEmittedPage` 守卫差分后 `scrollToItem(page, 0)`（自身滚动回流 page 相等 no-op，防回环）
- 当前页（水平）：覆写 `ReaderView.onMoveToChild(page)` 回调（1.28.2 无 ViewListener 接口）；当前页（垂直）：`snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }`
- 进度回写：渲染回调 → `ReaderViewModel.onPageChanged(page, scrollOffsetY)` 防抖 600ms 落库（静止期后仅写最新值；水平路径 offset 恒 0，垂直含像素偏移）；`ON_PAUSE`/`ON_STOP`/离开组合时 `flushProgress()` 立即落库保底（见 docs/data-layer.md「进度记忆实现时机」）
- 大纲导航：`Document.outline()` 返回 `List<OutlineEntry>`（title/pageIndex/children）；顶部栏「目录」按钮弹出 `OutlineSheet`（ModalBottomSheet，打开时懒加载，展平缩进列表，复用目录树的扁平渲染模式），点击条目 `uiPage = entry.pageIndex` 经同一差分机制跳页（两模式通用）

## UI 层架构

### 路由

```
nav/MyReaderNavHost.kt
routes:
  library?folderId={folderId}   # 书架/目录浏览（M5：folderId 可选，缺省=根；startDestination 须写完整模式。
                                #   注意 NavType.LongType 不允许 nullable（运行时抛 IllegalArgumentException），
                                #   用哨兵 defaultValue=-1L 表示根（Routes.FOLDER_ID_NONE），VM 中转 null；
                                #   Routes.library(null) 必须返回 "library"——空参数串 LongType 解析也会崩）
  reader/{documentId}           # 阅读器（M4 水平；M7 增垂直滚动 + 顶栏实时切换）
  settings                      # 设置页（M7 已落地）：默认阅读模式/主题/书架排序三组单选，DataStore 持久化；
                                #   入口 = 书架顶栏 SortMenu 后的设置图标（LibraryScreen onOpenSettings）
  about                         # 关于页（AGPL 合规）：应用内展示开源许可 + MuPDF 来源 + AGPL-3.0 全文链接；
                                #   入口 = 设置页「关于 → 开源许可与第三方声明」（SettingsScreen onOpenAbout）
```

> 「移动/导入选目录」用 **FolderPickerDialog**（M5 实现落定，原设计的 folderPicker 独立路由废弃）——弹窗内嵌目录树 + 新建子目录，避免为选目录增加路由栈层级。

### 主题（M7）

- `ThemeMode { SYSTEM, LIGHT, DARK }`（core:model，`resolve(systemDark)` 纯函数）经 `SettingsPrefs` 持久化；设置页三选一
- 消费点在 `MainActivity`：`runBlocking(Dispatchers.IO) { withTimeoutOrNull(300) { themeMode.first() } } ?: SYSTEM` 首读（本地小文件毫秒级；超时兜底防磁盘异常 ANR）→ `MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme())`（Material3 默认色板，不接 dynamicColor——与三选一语义冲突）；`LaunchedEffect` 持续 collect 同一流，设置页改动实时生效
- 首帧双保险：`values/themes.xml` parent 用 `android:Theme.Material.NoActionBar`（DayNight，窗口背景随系统深色），防 XML 窗口层闪白
- 系统深色快捷开关（uiMode 变更，configChanges 未含）触发 Activity 重建：VM 存活 + rememberSaveable + `LaunchedEffect(s)` 回填链路与 M4 横竖屏同型（回归验收场景）

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

- **搜索（M6）**：顶栏搜索图标展开为输入框（navigationIcon 变「退出搜索」、trailing 清空键）→ `DocumentDao.search(q)`（`title LIKE`，**全库匹配不限当前文件夹**，结果固定按标题排序）；输入经 **300ms 防抖**实时出结果。内容切换**仅以 `searchResults != null` 为准**：`null` = 非搜索态（关键词为空/防抖未到，维持浏览内容），空列表 = 无命中（显示「未找到匹配文档」），两者语义严格区分。搜索态隐藏文件夹区块与最近阅读条；`BackHandler(enabled = searchActive)` 使系统返回先收起搜索而不 pop 导航栈；结果复用 `DocumentCard`（含长按菜单），点击直达阅读器
- **排序（M6）**：顶栏三点溢出菜单（`SortMenu`），`DocSort`（`TITLE`/`LAST_READ`/`ADDED`/`SIZE`）四项中文标签（标题/最近阅读/添加时间/大小）+ 当前项 trailing Check；选中 `setSort` 写 DataStore（`LibraryPrefs`，持久化设计见数据分册）即时生效并重启保持；`documents = prefs.docSort.flatMapLatest { docsIn(folderId, it) }`。搜索结果固定按标题排序，故搜索态不展示排序菜单
- **最近阅读条（M6）**：根书架网格首部全宽 `item(span = maxLineSpan)` 横向 `LazyRow`（`RecentSection`），卡片 100dp 宽 = 缩略图（含加密锁角标）+ 标题 + 「第 N 页」（仅 `progressType=="page"` 显示）；数据源 `recent(20)` 按 `lastReadAt DESC`；仅根目录显示、无阅读记录隐藏、搜索态不渲染；点击复用 `onDocumentClick` 直达阅读器（key 前缀 `r_` 与网格 `f_`/`d_` 无冲突）。**首项变化时横向滚动复位到最新项**：阅读返回后重排会在保留的横向偏移下原地换卡，视觉上像卡片跳变（M6 用户反馈），复位后跳变变为「展示刚读文档」的有意行为
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
5. 首次打开兜底：`pageCount == -1` 或缺缩略图时（如加密文档解密成功后），`ReaderViewModel` 经 `DocumentEngineRouter.engineFor(doc.fileType).open(...)` 补齐 `pageCount` 并触发缩略图生成；**`needsPassword` 保留不清**（文件始终加密、密码不持久化，每次打开仍需输入——该标记即加密常驻标识，清掉会让书架/最近条锁角标永久消失；M6 验收用户反馈修正，另在 `PasswordRequiredException` 路径自愈置 true 兜住存量数据）；**加密文档不保留内容预览**：不补生成缩略图，且清除历史遗留缩略图（锁角标卡片却展示第一页内容违背加密语义，M6 用户反馈修正）

## 错误处理与边界

| 场景 | 处理 |
|---|---|
| 损坏/非 PDF 文件 | 导入时 `engine.open()` 抛 `CorruptDocumentException` → 删半成品文件、不入库，提示「无法读取」；打开时读 `pageCount` 抛错 → 标记 `pageCount = -2`（错误标记，区别于 `-1` 未知），书架条目置灰显示「已损坏」，长按可删除记录（「重新导入」重选源文件替换为后续里程碑，M5 仅提供删除入口），不自动重试、不崩溃 |
| 密码保护文档 | 导入时 `engine.open()` 抛 `PasswordRequiredException` → 照常入库置 `needsPassword = true`；打开时先弹密码对话框，`engine.open(path, fileType, password)` 成功后补 `pageCount`、**保留 `needsPassword`（加密标识常驻）**、**清除并不再生成缩略图（加密文档不保留内容预览）**；密码不持久化（v1 仅会话内），每次打开均重新弹密码框；取消密码弹窗先让弹窗离开组合再返回书架（避免弹窗残影悬于转场之上） |
| 重复导入 | 导入时 `contentHash` 命中已有记录 → 弹窗「已存在《title》，是否仍导入」，可跳过（跳过则删刚复制的文件） |
| 存储空间不足 | 复制前 `StatFs` 检查 `filesDir` 可用空间 ≥ 文件大小 + 余量，不足则提示并中止，不写半文件 |
| `countPages` 失败 | 进度页码保持 0，UI 提示「打开失败」并返回书架，标记 `pageCount = -2` 不重复尝试（书架引导删除/重导） |
| 文件丢失（`relPath` 指向不存在） | 打开时 `File.exists()` 为 false → 书架该条目置「缺失」标记，引导删除记录（「重新导入」为后续里程碑，M5 仅提供删除入口） |
| 大文件复制中断 | 导入流程事务 + 删半成品回滚；UI 显示进度 + 可取消 |
