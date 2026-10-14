# M9 执行计划与验收方案：多格式支持（EPUB / CBZ / XPS，v1.2）

> 本文件是 M9 的**执行/验收单一来源**（任务拆解、技术决策、夹具、测试矩阵、真机验收清单）。
> 设计规范类变更（模块结构/接口签名/进度语义/UI 流程）仍按工作规则 5 同步进 architecture.md / data-layer.md / ui-design.md / process.md；
> **状态与验收证据只写 AGENTS.md**（本文件不记状态）。

## 0. 总纲

- **范围**：在同一 MuPDF 引擎上为 `epub` / `cbz` / `xps` 注册 `@FileTypeKey`，开放导入、渲染、进度、图标；可选验证自研格式扩展点（TXT）。
- **三条硬结论**：
  1. **不改 Room schema、不写 Migration**（`fileType` / `progressType` / `progressPayload` / `pageCount=-1` 已在 schema v1 预留）。
  2. **不新增 Gradle 依赖与模块**（可选 TXT 扩展点除外，process.md M9 已列）。
  3. **MuPDF 边界不变**：新增的 MuPDF/fitz 相关代码只允许落在 `feature:viewer-mupdf`。
- **执行顺序**：S0 预研（go/no-go 门禁）→ M9-A ~ M9-G，每阶段有出口标准；全部完成才由用户决策提交（工作规则 9）。

---

## 1. 现状差距（精确到文件:行）

### 1.1 已就绪（无需改动）

| 能力 | 位置 |
|---|---|
| 多引擎路由扩展点 | `core/document/FileTypeKey.kt`（`@MapKey` + `DocumentEngineRouter` + `SimpleDocumentEngineRouter`） |
| 进度双模型 | `core/document/ProgressPayload.kt`：`Page(page, scrollOffsetY)` / `Cfi(cfi)`，注释已写明「pdf/cbz/xps」与「epub 流式」语义 |
| DB 字段 | `DocumentEntity.fileType` / `progressType` / `progressPayload` / `pageCount=-1` |
| 流式进度落库 | `LibraryRepository.saveCfiProgress` → `DocumentDao.updateProgressPayload`（已实现，仅未接线） |
| 引擎 | `MuPdfDocumentEngine.open` 以 `FileTypes.magicOf(fileType)`（规范 MIME）作 fitz magic，天然支持 epub/cbz/xps（S0-4 用规范 MIME 而非裸扩展名，规避识别不确定性） |
| 渲染 | `PageAdapter` / `ReaderView` 与格式无关；`MuPdfVerticalView` 仅依赖 `Document` 接口 |
| 备份 DTO | `BackupCodec.DocumentDto` 已含 `fileType` / `progressType` / `progressPayload` |
| 扩展名白名单 | `DocumentStorage.EXT_PATTERN` 是字符形状校验（`^[a-zA-Z0-9]{1,10}$`），epub/cbz/xps 直接通过 |

### 1.2 必须改动（缺口清单）

| # | 缺口 | 位置 |
|---|---|---|
| G1 | 导入 mime 写死 `arrayOf("application/pdf")` | `feature/library/.../LibraryScreen.kt:219` |
| G2 | `extOf` 为 private 且**任意扩展名都接受**；`application/oxps → "oxps"` 与注册 key 可能不一致 | `feature/library/.../DocumentImporter.kt:172` |
| G3 | 未注册格式流入 router → `IllegalArgumentException("未注册的文档格式")` 崩溃 | `SimpleDocumentEngineRouter` |
| G4 | 导入时 `progressType = "page"` 硬编码 | `DocumentImporter.kt:152` |
| G5 | `currentProgress` 类型写死 `ProgressPayload.Page?`，Cfi 分支完全缺失 | `feature/reader/.../ReaderViewModel.kt:97/105/117`、`ReaderScreen.kt:160` |
| G6 | epub 重排未处理：尺寸变化只 `refresh()`，无 `core.layout()` | `feature/viewer-mupdf/.../MuPdfReaderView.kt` |
| G7 | provider 把 Cfi 静默降级为第 0 页（`initialProgress as? Page ?: Page(0)`） | `feature/viewer-mupdf/.../MuPdfViewerProvider.kt` |
| G8 | 卡片图标/页数文案不按 `fileType` 分支（epub `pageCount=-1` 时完全无文案） | `LibraryScreen.kt:583+`（`DocumentCard`） |
| G9 | `backfillAfterOpen` 对 epub 会反复写 `setPageCount(-1)` | `ReaderViewModel.kt:214` |
| G10 | `MuPDFCore` 不暴露 location/CFI API（内部 `private Document doc;`） | `third-party/mupdf-android-viewer/lib/.../MuPDFCore.java:31` |

### 1.3 上游可借鉴（同为 1.28.2）

- `MuPDFCore.layout(oldPage, w, h, em)` 公开且 `synchronized`，返回「重排后对应原页的新页码」；`isReflowable()` 公开。
- `DocumentActivity.java`：`onSizeChanged` 中对 reflowable 重算 `mLayoutW/H = w|h * 72 / mDisplayDPI` 后 `relayoutDocument()`；仅 reflowable 显示排版菜单（6pt~16pt）。
- 进度保存上游是**页码式**（无 CFI）：`prefs.putInt("page"+key, getDisplayedViewIndex())`。
- 内部真实用法：`long mark = doc.makeBookmark(doc.locationFromPageNumber(oldPage)); ... doc.pageNumberFromLocation(doc.findBookmark(mark));`
- 上游 mime 白名单（`app/.../LibraryActivity.java:31-42`）可作 launcher 参考：
  `application/pdf`、`application/vnd.ms-xpsdocument`、`application/oxps`、`application/x-cbz`、`application/vnd.comicbook+zip`、`application/epub+zip`。
- `third-party/mupdf-android-viewer/lib/build.gradle:12` 以 **`api 'com.artifex.mupdf:fitz:1.28.2'`** 暴露 → `feature:viewer-mupdf` 编译期可见 `com.artifex.mupdf.fitz.*`。

---

## 2. 关键技术决策（先定死，避免返工）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 模块 / 依赖 | 不加模块、不加依赖；同一引擎注册 `"pdf"/"epub"/"cbz"/"xps"` 四个 key | 工作规则 6；fitz 本身解析这三类；reader/library 零改动 |
| Room schema | 无变更、无 Migration | 字段已在 v1 |
| epub 进度锚点 | **三级回落**：L1 MuPDF bookmark 串（首选，跨重排稳定）→ L2 章节索引 + 章内偏移 → L3 纯页码 | G10：`MuPDFCore` 不暴露 location；上游内部用法证明 fitz 有该能力 |
| L1 取 location 手段（S0 已定，偏离原反射方案） | 在 `third-party:mupdf` 的 `MuPDFCore.java` 增公开方法 `pageToBookmark(page)→long` / `bookmarkToPage(long)→int`（fitz `makeBookmark(locationFromPageNumber)` / `pageNumberFromLocation(findBookmark)` 往返），锚点用 **bookmark long 十进制串**（非 location 串，跨重排更稳） | S0 javap 证实 fitz 1.28.2 有这些 native 方法；`MuPDFCore.java` 在 MuPDF 边界内（工作规则 1 允许），比反射更稳、无降级分支；已同步 architecture.md |
| epub 排版参数 | 打开时按视口 `layout(w*72/dpi, h*72/dpi, em=10)`；尺寸变化 relayout（同上游） | 排版随视口 → 页码漂移 → 必须以 L1 location 为锚 |
| epub `pageCount` | **DB 恒 -1（不落库）**，UI 页数取运行时 `doc.pageCount` | 页数随排版参数变化；符合 data-layer.md「流式(EPUB)恒为 -1」 |
| epub 默认模式 | 会话初值强制 `VERTICAL`（顶栏仍可切 HORIZONTAL） | 重排文档水平分页体验差 |
| `Document` 接口 | 新增 `val reflowable: Boolean get() = false`（默认实现，不破坏既有实现），`MuPdfDocument` 覆写 `core.isReflowable()` | provider / 渲染层需判断是否重排；同步更新 architecture.md 接口签名 |
| 未知格式 | 导入期集中归一化 → 未命中即提示「暂不支持的文件类型」并跳过（不复制、不入库） | 修 G3 崩溃面，也是未来 TXT 扩展点前置 |
| 导入 mime | 7 项白名单（见 §5.4；M9 修复：text/plain 仅「无扩展名」兜底，有扩展名经 `fromExtension` 拒收）；**暂不加 `application/octet-stream`**，真机若选不到某格式再按记录追加 | 保持选择器干净；权衡记录进 AGENTS.md |
| 字号 / 字体设置 | **不做**（排版参数固定 em=10 + 视口自适应） | 避免进度漂移与范围膨胀；留 v1.3 |

---

## 3. S0 预研（半天，go/no-go 门禁）

| 项 | 动作 | 判据 / 出口 |
|---|---|---|
| S0-1 location API | `javap` 查 fitz AAR 中 `Document` 的 `makeBookmark` / `findBookmark` / `locationFromPageNumber` / `pageNumberFromLocation` 签名（AAR 在 Gradle 缓存 `modules-2/files-2.1/com.artifex.mupdf/fitz/1.28.2/...`），并写临时插桩用例验证 `page → location → page` 往返 | 往返稳定 → **L1**；否则降级 L2 / L3 |
| S0-2 反射可行性 | 反射 `MuPDFCore::class.java.getDeclaredField("doc")` 取值后调 location API | 成功 → 单句柄；失败 → 双句柄方案 |
| S0-3 渲染实测 | 插桩用例：`MuPdfDocumentEngine.open(epub/cbz/xps)` → `pageCount` / `drawPage` / `outline()` | 任一格式打不开 → 换 magic 或该格式降级 |
| S0-4 magic 归一 | 验证 `.xps` / `.oxps` / `.cbz` / `.epub` 四种 magic 接受度 | 定 `FileTypes` 的 ext→fileType→magic 映射 |
| S0-5 夹具可生成性 | `python tools/test_docs.py selftest`（纯 stdlib） | XPS 若被 MuPDF 拒收 → 走 §9 R3 降级 |

**出口**：结论写进本文件 §2 决策表 + AGENTS.md「M9 进展」；未过不进 M9-A。

---

## 4. 阶段分解

### M9-A 类型契约与路由注册
- 新增 `core/document/FileTypes.kt`：`ALL = {pdf, epub, cbz, xps}`、`normalize(ext, mime): String?`、`mimeFilters(): Array<String>`、`magicOf(fileType): String`
- `app/.../di/DocumentEngineModule.kt` 追加 `@IntoMap @FileTypeKey("epub"/"cbz"/"xps")` 的 engine + viewer 绑定（复用 `MuPdfDocumentEngine` / `MuPdfViewerProvider`）
- `DocumentImporter` 用 `FileTypes.normalize` 替换 private `extOf`（G2/G3）
- `LibraryScreen.kt:219` mime 数组改为 `FileTypes.mimeFilters()`（G1）
- 导入 `progressType` 按格式：`epub → "cfi"`，其余 `"page"`（G4）
- **出口**：`FileTypesTest` 全绿；导入未注册格式不再崩溃

### M9-B 阅读器渲染适配（重排）
- `Document` 增 `reflowable`；`MuPdfDocument` 覆写 `core.isReflowable()`
- `MuPdfReaderView.onSizeChanged` 去抖块内加重排分支：`loc = core.layout(current, w*72/dpi, h*72/dpi, em)` → `refresh()` → `setDisplayedViewIndex(loc)`（复用既有 `lockedCurrent` 防误翻页）
- `MuPdfVerticalView`：重排文档沿用 `MuPDFCore` 默认固定排版（312×504pt）**不 relayout**（故 `Document.pageCount` 稳定、LazyColumn 项数不变）；宽度变化仍 `evictAll()` 失效位图缓存。**偏离/简化**：epub 的视口拟合 relayout 只落在水平 `MuPdfReaderView`（对齐上游、低风险）；垂直 epub 以固定分页连续滚动（进度由 bookmark 锚点保证准确），记为已知简化（页数不随宽度自适应）
- **出口**：PDF 行为零变化；epub 横竖屏不白屏、页数自适应

### M9-C 进度模型接线（核心）
- `EpubLocationResolver`（viewer-mupdf）：`page → location`、`location → page`，失败按 L2/L3 回落
- `MuPdfViewerProvider`：reflowable 时回调 `ProgressPayload.Cfi(loc)`，否则 `Page(page, offset)`；去掉静默降级（G7）
- `ReaderViewModel`：`currentProgress: MutableStateFlow<ProgressPayload?>`；防抖（600ms）/ flush 按类型分发 `savePageProgress` / `saveCfiProgress`；`Ready.initialProgress: ProgressPayload` 取代 `initialPage + initialScrollOffsetY`
- 恢复：Cfi → `pageNumberFromLocation` → 跳页；`backfillAfterOpen` 对 reflowable 豁免（G9）
- `EpubProgressCodec`（`core:document` 纯逻辑）：payload JSON `{"v":1,"loc":"...","page":N,"chapter":"..."}`；版本门禁 + 脏输入拒绝
- **出口**：`EpubProgressCodecTest` + `ReaderViewModelTest` 新增 cfi 用例全绿

### M9-D UI 与元数据
- 卡片按 `fileType` 分支图标；`pageCount>0` 显示「N 页」，流式文档显示格式标签（EPUB / CBZ / XPS）（G8）
- 最近阅读条：`progressType=="cfi"` 不显示「第 N 页」，改「继续阅读」
- 搜索 / 排序 / 移动 / 重命名对混合格式无感（回归验证）
- **出口**：`.\gradlew.bat build` 通过 + 书架截图

### M9-E 备份兼容
- 核对 `BackupCodec` 往返已含 `fileType` / `progressType` / `progressPayload`（字段已存在，仅补测试）
- 恢复侧防御：`fileType ∉ FileTypes.ALL` 时打开降级为「不支持的格式」提示，不崩溃
- `tools/acceptance/verify_db.py` 比对增 `fileType` / `progressType` / `progressPayload`
- **出口**：`BackupCodecTest` / `BackupManagerTest` 增 epub 记录往返用例

### M9-F 扩展点验证（可选，go/no-go）
- 新增 `feature:viewer-txt`（纯 Compose 文本分页，不依赖 MuPDF）+ `@FileTypeKey("txt")`
- **判据**：仅新增模块 + DI 绑定即可跑通导入 / 阅读 / 进度（页码式），验证「reader / 库表 / schema 一行不改」承诺
- 不做时 M9-A 的未知格式拒收仍必须落地

### M9-G 文档同步 + 真机验收 + 回归
- `docs/architecture.md`：`Document` 接口签名（reflowable）、多格式注册说明、无新依赖
- `docs/data-layer.md`：cfi 进度语义与分发、epub `pageCount=-1`
- `docs/ui-design.md`：导入 mime 聚合、卡片图标、阅读器重排与进度、错误边界（不支持格式）
- `docs/process.md`：M9 条目细化（保持指向本文件）
- `AGENTS.md`：状态 + 验收证据（唯一证据落点）

---

## 5. 夹具方案

### 5.1 脚本
新增 `tools/test_docs.py`（与 `tools/test_pdfs.py` 同 CLI 契约：`generate` / `verify` / `selftest`，共用 `check()` 助手，扩展 `EXPECT` 表）。**零新 Python 依赖**（纯 stdlib `zipfile` / `zlib` / `struct`）。

### 5.2 夹具清单

| 夹具 | 生成方式 | 断言 |
|---|---|---|
| `epub_test.epub`（3 章 ≈12 页） | `zipfile`：`mimetype`（STORED 首条目）+ `META-INF/container.xml` + OPF + NCX + 3 个 XHTML | zip 结构、OPF 存在、页数 >0、大纲 3 条 |
| `comic_test.cbz`（5 页） | `zipfile` + 手写最小 PNG（zlib + struct） | 5 个 image 条目、按名自然序 |
| `xps_test.xps`（2 页） | 手写最小 OPC：`FixedDocumentSequence.fdseq` + `.rels` + 2 个 `FixedPage` XAML（文本 Glyphs） | zip 结构、fdseq 存在；能否打开由 S0-3 判定 |
| `unsupported_test.docx` | 伪造扩展名 | 导入期被拒（M9-A 用例） |

### 5.3 产物去向
- `tools/fixtures/`（已 gitignore，不入库）
- 拷入 `feature/viewer-mupdf/src/androidTest/assets/`（**入库**，供插桩测试使用）

### 5.4 导入 mime 白名单（M9-A 落地值）
```
application/pdf
application/epub+zip
application/x-cbz
application/vnd.comicbook+zip
application/oxps
application/vnd.ms-xpsdocument
text/plain
```
> 注（M9 修复）：`text/plain` 仅对「无扩展名、mime=text/plain」的 SAF 条目兜底为 txt；带扩展名（如 `.log`/`.csv`/`.md`）经 `FileTypes.fromExtension` 未命中即拒收，不误导入。

---

## 6. 测试矩阵

| 层 | 用例 | 类型 |
|---|---|---|
| `core:document` | `FileTypesTest`（ext/mime 归一化、`oxps→xps`、未知返回 null、mime 数组内容）、`EpubProgressCodecTest`（往返 / 版本门禁 / 脏输入） | JVM（纯逻辑，无需 Robolectric） |
| `core:data` | `BackupCodecTest` / `BackupManagerTest` 增 epub 记录往返（含 `progressPayload`） | JVM Robolectric |
| `feature:library` | `DocumentImporterTest`：epub/cbz/xps 分流、`progressType` 正确、未知格式拒收且不复制不入库 | JVM |
| `feature:reader` | `ReaderViewModelTest`：cfi 防抖落库、flush、密码重试不重置模式/进度、epub 豁免 `setPageCount`、Cfi→页码恢复 | JVM |
| `feature:viewer-mupdf` | `VerticalPageLoaderTest`（已有，格式无关） | JVM Robolectric |
| `feature:viewer-mupdf` | **`MuPdfMultiFormatEngineTest`**（新增 androidTest）：epub/cbz/xps 的 open + pageCount + drawPage + outline + close；epub location 往返一致 | 插桩（真机） |

命令：
```powershell
.\gradlew.bat build                                        # 全量 + 单测，必须 BUILD SUCCESSFUL
.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest
python tools/test_docs.py selftest
```

---

## 7. 真机验收方案（一加 PKC110 / Android 16）

| # | 场景 | PASS 判据 |
|---|---|---|
| A1 | 导入 epub / cbz / xps 各一 | 书架 3 条 + 缩略图 + 格式角标/标签；DB `fileType` 正确 |
| A2 | 导入不支持格式（.docx） | 提示「暂不支持的文件类型」；`library/` 无残留副本；DB 无新增；不崩溃 |
| B1 | epub 阅读：滚动/翻页 → BACK → 重进 | 恢复到原位置（同章节，误差 ≤1 屏）；DB `progressType="cfi"` 且 `progressPayload` 非空 |
| B2 | epub 横竖屏切换 | 触发 relayout 无白屏、进度锚定不回首页 |
| B3 | epub 大纲 | 章节列表正确，点击跳到对应位置 |
| C1 | cbz 连续滚动 + 重进恢复 | 页数 = 图片数；`progressType="page"`，`currentPage` 精确 |
| D1 | xps 同上 | 同上 |
| E1 | 混合库目录管理 | 移动 / 重命名 / 删除 / 搜索 / 排序对三种格式均生效（DB + UI 双向佐证） |
| F1 | 备份恢复含 epub | 导出 zip → `adb uninstall` → 重装 → 恢复：物理文件 SHA-256 一致 + `fileType`/`progressPayload` 一致 + 打开进度正确；`tools/acceptance/verify_db.py` PASS |
| G1 | PDF 全链路回归 | 加密密码框（错/对）、水平↔垂直切换、深色主题、最近阅读、损坏标记 |
| H1 | 性能 | epub ~300 章 / cbz ~100 图：无 OOM、无 ANR、滚动流畅 |

**证据要求**（写进 AGENTS.md）：`.\gradlew.bat build` 输出摘要（task 数 + 单测数）、插桩测试输出、Room DB dump 关键字段、UI 截图、`verify_db.py` PASS 输出。

---

## 8. 回归清单（每阶段末必跑）
1. PDF 导入（重复 / 损坏 / 加密三分支）不变
2. 水平模式下进度、跳页、大纲、横竖屏保持
3. 垂直模式滚动偏移落库 / 恢复、横竖屏锚定
4. 设置页默认模式 / 主题 / 排序持久化
5. 备份导出 / 恢复（含 PDF 库）大小与字段一致
6. 深色主题遍历各页面无浅色残留

---

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| R1 location API 不可得 | L2（章节 + 章内偏移）→ L3（纯页码）；L3 下横竖屏切换进度有漂移，记为已知限制 |
| R2 反射取 `doc` 失败 | 双句柄（另开 fitz `Document`，同步 layout 参数）；或放弃 relayout（固定 312×504pt @10pt，页码稳定） |
| R3 XPS 夹具不被 MuPDF 识别 | 降级为「注册 + 路由 + 导入分流」代码级验证 + 真机手工样本；AGENTS.md 记录 |
| R4 epub 页数漂移导致进度错乱 | 进度以 location 为准；`pageCount` 不落库；`backfillAfterOpen` 对流式豁免 |
| R5 未知格式崩溃（G3） | M9-A 集中归一化 + 导入期拒收 |
| R6 cbz 大图内存 | 复用垂直视图单页位图 4096 上限 + 字节预算 LruCache（已有） |
| R7 备份跨版本 | `schemaVersion` 保持 1（字段本就在 v1）；未注册 `fileType` 打开时友好降级 |
| R8 AGPL | 无新依赖、无新第三方组件；合规项不变 |

---

## 10. 明确不做（Out of scope）
字号 / 字体 / 行距设置；PDF 之外的全文搜索；EPUB 高级 CSS / 媒体；DRM（LCP / Adobe）；FB2 / MOBI 注册；PDF 侧任何行为变更。TXT 扩展点为可选验证项（M9-F），由用户 go/no-go。

---

## 11. 流程约束
- 所有 commit / push 由用户决策（工作规则 9），执行过程中不自行提交。
- 每阶段出口附证据；状态与验收证据只更新 `AGENTS.md`。
- 偏离本文件的选型变化，必须先改 `docs/architecture.md`「技术选型」并写明理由，再改本文件。
