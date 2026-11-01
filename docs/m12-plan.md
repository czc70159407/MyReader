# M12 执行计划（v1.5：沉浸模式 + 高亮/笔记）

> 状态：**Part A 已实现并真机验收通过（2026-11-01）；Part B 未立项**。
> 排期：先 **A 沉浸模式（T8）**——预研已完、结论可行，可直接实现；再 **B 高亮/笔记**——需 S0 预研立项。
> 前序：M11（v1.4，epub 比例锚点）已完成，见 [m11-plan.md](m11-plan.md)。
> 关联设计：沉浸模式预研与交互契约见 [design/screens/reader.md](design/screens/reader.md) §6（D4 决策产物）。

## 1. 排期总览

| 阶段 | 内容 | 就绪度 | 预估 |
|---|---|---|---|
| A | 沉浸模式（T8） | ✅ 预研完成、契约已写 | 1 会话（含真机） |
| B | 高亮/笔记（M12 主体） | ⚠️ 需 S0 立项 | S0 0.5–1 会话 + 实现 2–3 会话 |

理由：A 是唯一「就绪待做」项、成本低风险小；B 是产品主线下一站但须完整立项，故先 A 后 B。

---

## 2. Part A：沉浸模式（T8）

> 目标：垂直长文档阅读时释放顶栏 64dp。预研结论与交互契约以 `reader.md` §6 为准，本节只写执行分解与门禁。
> **状态：已实现并真机验收通过（2026-11-01）；`build` 全绿（detekt 全绿、单测通过）；§2.6 七条通过（TalkBack 双击/双指手势受 adb 限制，已用隐藏态 `clickable=true` 语义节点验证）。**

### 2.1 A-0 前置纠偏（执行前必读）

| 来源 | 表述 |
|---|---|
| `design/screens/reader.md` §6.4 | 「`Content` 追加两个**带默认值**的回调」 |
| `core/document/.../DocumentViewerProvider.kt` 注释 | 「**接口方法参数不带默认值**——Compose 接口默认参数 + 实现会生成冲突重载桥」 |

**结论**：以代码注释为准——追加回调**不带默认值**，所有 `Content` 实现点（生产 2 个 + 测试/preview fake）**必须同步改签名**，不能靠默认值免改。执行时同步把 `reader.md` §6.4 的「带默认值」修正为「不带默认值、全实现点同步」。

### 2.2 A-1 接口扩展（`core:document`）

文件：`core/document/src/main/java/com/myreader/core/document/DocumentViewerProvider.kt`

在 `Content(...)` 的 `onPageCountChanged` 之后、`modifier` 之前追加两个参数（不带默认值）：

```kotlin
onTapContent: () -> Unit,   // 内容区点按（水平中间区 / 垂直静止点按）→ 切换顶栏
onUserScroll: () -> Unit,   // 用户翻页/滚动/缩放 → 隐藏顶栏
```

语义约定：水平模式边缘 1 英寸仍翻页、不触发 `onTapContent`；垂直模式滚动取消 tap，无手势冲突（`reader.md` §6.1 实证）。

### 2.3 A-2 实现侧（两个 viewer 模块）

| 模块 | 文件 | 做法 |
|---|---|---|
| `feature:viewer-mupdf` | `MuPdfReaderView`（水平） | 覆写上游 protected 钩子 `onTapMainDocArea()` → `onTapContent`；`onDocMotion()` → `onUserScroll`；经 `rememberUpdatedState` 转发防回调过期 |
| `feature:viewer-mupdf` | `MuPdfVerticalView`（垂直） | `pointerInput { detectTapGestures(onTap = …) }` → `onTapContent`；监听滚动状态变化 → `onUserScroll` |
| `feature:viewer-txt` | `TxtViewerProvider` | 同 `MuPdfVerticalView`（裸 `LazyColumn` + `pointerInput` + `semantics`） |

### 2.4 A-3 状态侧（`feature:reader`）

- `ReaderScreen` 持 `topBarVisible`（`rememberSaveable`，旋转保持）。
- 规则：点按内容区 → toggle；滚动/拖动/缩放 → `false`；大纲/跳页操作结束 → `true`（用户刚操作需页码反馈）。
- 仅隐藏应用内 `TopAppBar`，不动系统状态栏/导航栏；隐藏不改变返回语义。

**无障碍强制约束（不满足则不实现，`reader.md` §6.3）**：
1. `AccessibilityManager.isTouchExplorationEnabled == true` 时**禁用自动隐藏**。
2. 隐藏态必须暴露 `Modifier.semantics { onClick(label = "显示工具栏") { …; true } }`——纯 `pointerInput` 不产生 click 语义。
3. 隐藏后动作全部不可达时必须保留可读的恢复路径。

### 2.5 A-4 测试

- 所有 `DocumentViewerProvider.Content` 实现点 + 测试 fake 同步新签名（执行时 `search_content` 定位全部实现点）。
- `ReaderViewModelTest` **无需改**（`topBarVisible` 在 Screen 层，不在 VM）。
- `feature:reader` 无 Compose UI 测试基建（已登记），状态逻辑交由真机验收覆盖。

### 2.6 A-5 真机验收（`reader.md` §6.5，7 条）

1. 水平 PDF 点中间 → 顶栏隐藏；再点 → 显示；点左右边缘 → 翻页且顶栏状态不变；
2. 水平拖动翻页 → 顶栏隐藏；
3. 垂直滚动 → 隐藏；静止点按 → 显示；
4. 大纲跳页 → 顶栏可见（页码反馈可读）；
5. 开 TalkBack → 滚动不自动隐藏；双击内容区可切换顶栏；
6. 旋转/分屏 → 显隐状态保持，渲染不白屏；
7. 字体 200% / 深色 → 顶栏淡入淡出、内容区尺寸恒定（显隐前后渲染区 bounds 完全一致，不 relayout 不闪）。

### 2.7 A 门禁

`build` 全绿 + detekt 全绿 + 真机 7 条通过 + 回填 `reader.md` §6 状态为「已实现」+ 更新根 `AGENTS.md`。产物落 `/sdcard/Download/MyReaderTest/`（工作规则 10 红线）。

---

## 3. Part B：M12 高亮/笔记（立项）

> 立项走 `m9/m10/m11-plan.md` 同一模板；本节为立项骨架与 S0 预研问题清单，**拍板前不写实现代码**。

### 3.1 问题陈述

M10/M11 的书签解决的是「**位置**记忆」，高亮/笔记解决的是「**内容片段**」的记忆与标注——是从「翻阅」到「研读」的关键能力。当前阅读器只有位置锚点，无法标记一段文字、无法给内容留注。

### 3.2 目标 / 非目标

- 目标：PDF 文本高亮（单色黄色起步）+ 高亮处加笔记；持久化、随备份导出恢复、跨会话稳定。
- 非目标（本阶段外）：epub 高亮（S0-1 若证实 fitz 不支持 reflowable 文本级则砍掉、顺延）；手写涂鸦；高亮导出到外部文件；云同步。

### 3.3 S0 预研（门禁，约 0.5–1 会话，需设备）

> 门禁先行：解包 fitz AAR 读符号 + 真机探针取证，**拍板前不写业务代码**。

**S0-1 fitz annotation API 边界**（解包 `com.artifex.mupdf:fitz:1.28.2` AAR 读 `classes.jar` 符号，同 M11 S0-1 手法）：

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | 解包 AAR，读 `classes.jar` 符号 | 可用类/方法清单 |
| 2 | 查 PDF annotation：`PDFAnnotation` 类型（Highlight/Ink/Text）、`PDFDocument.createAnnotation`/`deleteAnnotation`/`updateAnnotation` | PDF 高亮原生渲染可行性 |
| 3 | 查文本选择：`PDFDocument`/`Page` 是否有 `TextSpan`/选中文本 + 归一化矩形 | 「选中→高亮」交互可行性 |
| 4 | 查 epub（reflowable）文本选择与锚点：重排后矩形漂移，是否有文本级锚点 | epub 高亮是否降级/砍掉 |

**S0-2 真机探针**（`MuPdfAnnotationProbeTest`，先取证后转回归）：
- PDF：选中一段文字 → 拿选中文本 + 矩形 → 创建 highlight → 退出重进验证持久化；
- epub：垂直/水平选中 → 重排后矩形是否稳定。

**S0-3 决策拍板**（据 S0-1/2 结论）：
- 锚点格式：PDF = 页码 + 归一化矩形；epub = 复用 `cp:` 比例锚点 或 降级「页级/屏级」。
- 数据模型：`annotations` 单表（type 区分 HIGHLIGHT/NOTE）还是分表。
- 颜色范围：已拍板单色黄色（§3.8）。
- 交互边界：文本选择手势 vs 水平边缘翻页/垂直滚动的冲突与优先级。
- 现状对齐：Room `version=2`、`entities=[Folder,Document,Bookmark]`（已复核）；是否与 `Bookmark` 共用 `BookmarkAnchorCodec`。

### 3.4 阶段分解（详细步骤）

| 阶段 | 步骤 | 内容 | 验证 |
|---|---|---|---|
| **A 数据层**（0.5 会话） | A-1 | `AnnotationEntity`（`annotations` 表：`documentId` FK CASCADE、`type`、`anchor` 复用双轨、`color`、`text`、`createdAt`）+ `AnnotationDao` | JVM |
| | A-2 | Room **schema v3** + 手写 `MIGRATION_2_3`（SQL 逐字对齐 `3.json`，同 M10 纪律）+ `3.json` 入库 | `Migration2To3Test` |
| | A-3 | 仓储 CRUD + 上限常量（类比 `MAX_BOOKMARKS_PER_DOCUMENT`）+ 判重 | JVM |
| **B 渲染层**（1 会话） | B-1 | PDF 文本选择：`MuPdfReaderView` 覆写选择手势（同沉浸模式钩子法），拿选中文本 + 矩形 | 插桩 |
| | B-2 | 高亮绘制：原生 annotation（若 S0-1 支持）或自绘 overlay | 真机 |
| | B-3 | 锚点抓取：选中区域 → 页码 + 矩形（PDF）/ 比例（epub） | 插桩 |
| | B-4 | epub 高亮（据 S0 结论：支持则文本级，不支持则砍掉顺延） | 真机 |
| **C UI**（0.5–1 会话） | C-1 | 选择工具栏（高亮颜色 / 加笔记 / 删除） | — |
| | C-2 | 高亮/笔记列表面板（复用书签 BottomSheet 模式）+ 跳转定位 | — |
| | C-3 | 接线 `ReaderViewModel` + 文案入 `copy.md` | JVM |
| **D 备份+验收**（0.5 会话） | D-1 | `BackupCodec.SCHEMA_VERSION = 4` + 高亮/笔记导出/恢复 + v3 兼容 | JVM |
| | D-2 | 真机验收（备份往返 / 升级安装 v2→v3 / R8 冒烟） | 真机 |

> **A 数据层已落地（2026-11-01）**：`AnnotationEntity`/`AnnotationDao` + `MyReaderDatabase` **v3**（entities 加 `AnnotationEntity`）+ **`MIGRATION_2_3`**（CREATE TABLE / CREATE INDEX 与 Room 生成的 `3.json` 逐字一致，外键 CASCADE）+ `LibraryRepository.addAnnotation`（**同页同 quad 去重 + 上限 `MAX_ANNOTATIONS_PER_DOCUMENT=200`**）+ `EntityMappers` 映射（未知 type 回落 HIGHLIGHT）。JVM 单测（`AnnotationDaoTest` 5 例 / `EntityMappersTest` 补 2 例）通过，`build` BUILD SUCCESSFUL（detekt 全绿）。**`Migration2To3Test` 已写、待真机执行**（验收时设备断开）。

### 3.5 测试矩阵

| 层 | 用例 |
|---|---|
| JVM | `Migration2To3Test`（迁移保数据 + FK 级联）、`AnnotationCodec`/仓储去重与上限、`BackupCodec` v4 往返与 v3 兼容 |
| 插桩 | fitz 文本选择/高亮探针（先取证后转回归）、跨会话/跨排版锚点还原 |
| JVM | `ReaderViewModel` 高亮/笔记增删改 |

### 3.6 真机验收场景

1. PDF 选中一段文字 → 高亮 → 退出重进 → 高亮位置/颜色一致；
2. 高亮处加笔记 → 笔记列表可见 → 点击跳回对应位置；
3. epub 高亮 → 横竖屏/重排后高亮仍落在原内容片段（**此条是否可测取决于 S0-1 结论**）；
4. 备份往返：高亮/笔记随备份导出 → 卸载重装恢复 → 逐字段一致；
5. 升级安装（v2→v3）：旧书签/进度/文档保留，迁移不崩；
6. R8 release 冒烟：logcat 无 FATAL/ANR。

### 3.7 B 门禁

`m12-plan.md` §3 拍板评审通过 + S0 预研结论固化（探针日志留档）。

### 3.8 决策记录（2026-11-01 已拍板）

1. **epub 降级预案**：接受——若 S0-1 证实 fitz 不支持 reflowable 文本级高亮，M12 砍成「仅 PDF 高亮/笔记」，epub 高亮顺延。
2. **颜色范围**：单色（黄色）起步，多色留待后续里程碑。
3. **备份导出**：高亮/笔记随备份导出（`BackupCodec.SCHEMA_VERSION = 4`）。

### 3.9 S0 预研结论（2026-11-01）

**S0-1 fitz 1.28.2 API 边界**（解包 AAR 读 `classes.jar` 符号 + `javap`）：
- PDF annotation：`PDFPage.createAnnotation(TYPE_HIGHLIGHT)` / `deleteAnnotation` / `getAnnotations`；`PDFAnnotation` 有 `setQuadPoints`/`setColor`/`setContents`/`update`/`getType`。
- 文本选择：`Page.toStructuredText()` → `StructuredText.highlight(Point,Point)→Quad[]`、`copy(Point,Point)→String`、`search(String)→Quad[][]`、`asText()`。
- **`search(String)` 真机 JNI 崩溃**（`jobject invalid local reference: 0x1`，SIGABRT）——不可用，但不影响（高亮/笔记用两点选择 `highlight`/`copy`）。

**S0-2 真机探针（PKC110 / Android 16）**：

| 能力 | PDF | EPUB |
|---|---|---|
| 文本获取（`toStructuredText`+`asText`） | ✅ "Section 1 Introduction" | ✅ "Chapter 1…" |
| 文本选择（`highlight`→Quad[]） | ✅ 1 quad | ✅ 3 quads |
| 选中文本（`copy`→String） | ✅ | ✅ |
| 高亮创建（`createAnnotation`+`getAnnotations`） | ✅ type=8，读回 1 | 未测（同 API） |

**结论**：PDF 高亮/笔记**完全可行**（原生 annotation + 两点选择）；EPUB reflowable 文本选择**可用**（fitz 支持，§3.8 决策 1 的「砍掉」前提不触发），但 quad 坐标跨重排漂移，锚点需按比例。

**S0-3 决策（据 S0-1/2 拍板）**：
- 锚点格式：PDF = 页码 + 归一化矩形（quad 各顶点 / 页面宽高，固定排版稳定）；epub = 比例锚点（quad / 页面宽高 + `cp:` 章节比例，跨重排按比例换算）。
- 数据模型：`annotations` 单表（`type` 区分 HIGHLIGHT/NOTE），字段同 §3.4 A-1。
- 渲染：PDF 高亮用原生 annotation（fitz 自动渲染）；epub 高亮自绘 overlay（reflowable 无 annotation）。
- 交互：长按进入文本选择（区别于单击切换顶栏 / 水平边缘翻页 / 垂直滚动）。

---

## 4. 依赖与时间预估

| 阶段 | 预估 | 备注 |
|---|---|---|
| A（沉浸模式） | 1 会话（含真机） | 预研已完，契约已写 |
| B-S0（立项预研） | 0.5–1 会话 | 依赖真机 + fitz AAR 解包 |
| B 实现（A~D） | 2–3 会话 | 含第二个 Room Migration |

## 5. 风险与待拍板

| 风险/待定 | 说明 |
|---|---|
| **epub 高亮降级预案** | 若 S0-1 证实 fitz 对 reflowable 文本选择/锚点不足，M12 可能砍成「仅 PDF 高亮/笔记」，或 epub 降级为「页级/屏级」而非「文本级」。**需用户拍板是否接受**。 |
| 接口契约矛盾（A-0） | `reader.md` §6.4「带默认值」与代码注释「不带默认值」冲突，执行 A 时按代码为准并回改文档。 |
