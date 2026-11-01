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

- 目标：PDF 文本高亮（多色）+ 高亮处加笔记；持久化、随备份导出恢复、跨会话稳定。
- 非目标（本阶段外）：epub 文本级高亮（若 S0 证实 fitz 不支持则降级为页级或砍掉）；手写涂鸦；高亮导出到外部文件；云同步。

### 3.3 S0 预研（门禁先行，先于任何代码）

**S0-1 fitz annotation API 边界**（解包 `com.artifex.mupdf:fitz:1.28.2` AAR 读 `classes.jar` 符号，同 M11 S0-1 手法）：

| 问题 | 对方案的影响 |
|---|---|
| PDF 是否支持 highlight/underline annotation（`PDFAnnotation` / `createAnnotation`）？ | 决定 PDF 高亮是原生渲染还是自绘 overlay |
| 能否做**文本选择**并拿到选中文本 + 归一化矩形？ | 决定「选中 → 高亮/笔记」交互是否可做 |
| epub（reflowable）文本选择与高亮：矩形随重排漂移，fitz 是否提供文本级锚点？ | **最大技术风险**，决定 epub 高亮是否降级或砍掉 |

**S0-2 数据模型决策**：
- 是否新建 `annotations` 表 → Room **schema v3** + 手写 `MIGRATION_2_3`（SQL 逐字对齐 `3.json`，同 M10 纪律）。
- 字段草案：`id` / `documentId`(FK CASCADE) / `type`(HIGHLIGHT\|NOTE) / `anchor`(复用双轨) / `color` / `text`(笔记正文或选中文本) / `createdAt`；上限常量类比 `MAX_BOOKMARKS_PER_DOCUMENT`。

**S0-3 锚点决策**：
- PDF：页码 + 归一化矩形（固定排版稳定）；
- EPUB：复用 M11 `cp:` 比例锚点（精度屏级）还是引入文本级 CFI（M9 曾设想未落地）——据 S0-1 结论定夺。

**S0-4 渲染层手势**：文本选择手势 vs 现有水平边缘翻页/垂直滚动的冲突与优先级。

**S0-5 现状对齐**：当前 Room `version = 2`、`entities = [Folder, Document, Bookmark]`（已复核）；高亮/笔记是否与 `Bookmark` 共用 `BookmarkAnchorCodec` 锚点编码，而非另起炉灶。

### 3.4 阶段分解草案（类比 M10 四段）

| 阶段 | 内容 |
|---|---|
| S0 | 预研拍板（锚点/模型/交互边界） |
| A | 数据层：schema v3 + `MIGRATION_2_3` + `Annotation` 实体/DAO/仓储 + 备份 `SCHEMA_VERSION=4` |
| B | 渲染层：文本选择 + 高亮绘制 + 锚点抓取 |
| C | UI：选择工具栏（颜色/笔记/删除）+ 高亮/笔记列表面板（复用书签 BottomSheet 模式） |
| D | 真机验收（含备份往返 + 升级安装 + R8 冒烟） |

### 3.5 测试矩阵草案

| 层 | 用例 |
|---|---|
| JVM | `Migration2To3Test`（迁移保数据 + FK 级联）、`AnnotationCodec`/仓储去重与上限、`BackupCodec` v4 往返与 v3 兼容 |
| 插桩 | fitz 文本选择/高亮探针（先取证后转回归）、跨会话/跨排版锚点还原 |
| JVM | `ReaderViewModel` 高亮/笔记增删改 |

### 3.6 真机验收场景草案

1. PDF 选中一段文字 → 高亮 → 退出重进 → 高亮位置/颜色一致；
2. 高亮处加笔记 → 笔记列表可见 → 点击跳回对应位置；
3. epub 高亮 → 横竖屏/重排后高亮仍落在原内容片段（**此条是否可测取决于 S0-1 结论**）；
4. 备份往返：高亮/笔记随备份导出 → 卸载重装恢复 → 逐字段一致；
5. 升级安装（v2→v3）：旧书签/进度/文档保留，迁移不崩；
6. R8 release 冒烟：logcat 无 FATAL/ANR。

### 3.7 B 门禁

`m12-plan.md` §3 拍板评审通过 + S0 预研结论固化（探针日志留档）。

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
