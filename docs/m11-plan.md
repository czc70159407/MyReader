# M11 执行计划（v1.4：epub 锚点稳健性 / 比例锚点）

> 状态：**S0 进行中**（S0-1 已完成 2026-10-30；S0-2 / S0-3 需真机探针，见 §3.2）。
> 前序：M10（v1.3，书签 + 首个 Room Migration）已完成，见 [m10-plan.md](m10-plan.md)。

## 1. 问题陈述

M10-D 真机实证：fitz bookmark 串只在「同一 doc 会话 + 同一排版」内可解析，跨会话 / 跨排版
`MuPDFCore.bookmarkToPage` 恒返回 -2，调用方回落 payload 内 `page`。影响面**不止书签**：

| 受影响链路 | 表现 |
|---|---|
| 书签跳转（`BookmarkEntity.anchor`） | 跨会话失效 → 回落 `page` + `scrollOffsetY`；水平模式 relayout 后 page 属旧排版 → 位置错位 |
| **阅读进度恢复**（`EpubProgressCodec.loc`） | 与书签同一条链路（`EpubLocationResolver`）→ epub 水平模式「退出重进」同样靠 page 回落，跨会话重排后落点漂移 |
| `MuPDFCore.layout()` 旧页 remap | 内部同样用 `makeBookmark`/`findBookmark`，同一失效源 |

即 M9 决策「L1 bookmark 串跨重排稳定」不成立，M9 设计中预留的 **L2「章节索引 + 章内偏移」从未实现**。

> **归因待证**：`MuPDFCore.bookmarkToPage` 的 -2 有两个来源——`findBookmark` / `pageNumberFromLocation`
> 抛异常，或**往返校验**（`makeBookmark(locationFromPageNumber(page)) != mark`）不成立。M10-D 的日志只能
> 证明「走到了 -2」，**不足以断定是 findBookmark 失效**。S0-2 探针（§3.2 B 组）分别打印
> `findBookmark` 结果、其还原页与 `back == mark`，用于区分这两种成因（若是后者，修复成本远低于比例锚点）。

## 2. 目标 / 非目标

- 目标：epub 的进度与书签在任意「会话 × 排版」组合下恢复到同一内容位置，误差 ≤ 1 屏。
- 目标：锚点**自解释**（可日志打印、可人工核对、可判重），不依赖不可拆解的 long。
- 目标：旧数据（v1 payload、十进制 anchor、v1/v2 备份）全部可读，不丢用户进度与书签。
- 非目标：高亮/笔记（M11-B）；PDF/CBZ/XPS（页码式本就稳定）；云同步；epub 字号设置。

## 3. S0 预研（门禁先行）

### 3.1 S0-1 fitz API 边界（已完成 2026-10-30）

取证方式：解包 Gradle 缓存中的 `com.artifex.mupdf:fitz:1.28.2` AAR 读 `classes.jar` 符号，
并用探针用例编译验证（`.\gradlew.bat :feature:viewer-mupdf:compileDebugAndroidTestKotlin` 通过）。

| 事实 | 内容 | 对方案的影响 |
|---|---|---|
| `Location` 字段 | **仅 `chapter` + `page` 两个 int**（无 x / y）；`Location(int chapter, int page)` 公开；`equals`/`toString` 可用 | 页内更细粒度（段落/行）无 API 支撑 → 锚点精度上限为「屏」 |
| `Document.locationFromPageNumber(int)` | `→ Location` | 可把页码拆成 `chapter + 章内页` |
| `Document.pageNumberFromLocation(Location)` | `→ int` | 可由 `chapter + 章内页` 还原当前排版页码 |
| `Document.makeBookmark(Location)` / `findBookmark(long)` | `long ↔ Location` | bookmark 只是 Location 的打包；**不可拆解**，故新格式必须自带 chapter/page |
| `Document.countChapters()` | `→ int` | 章节数可得 |
| `Document.layout(float w, float h, float em)` | `→ void` | 重排后章节页数变化 → 章内页码漂移，**比例需自算** |
| `MuPDFCore` 构造 | 固定 `layout(312, 504, 10)`，`layout()` 仅在参数变化时重排 | 「跨会话同排版」可直接构造，探针可复现 |

**推论**：比例锚点 = `chapter` + `章内页码 / 章页数`。分母不存在现成 API（无 `chapterPageCount`），
需由调用方在抓取锚点时扫描或缓存；扫描成本 O(pageCount) 且仅在「加书签 / 落进度」时发生，可接受。

### 3.2 S0-2 / S0-3 跨会话探针（待真机）

用例：`feature/viewer-mupdf/src/androidTest/.../MuPdfLocationProbeTest.kt`（已就位，仅取证不设断言）

```powershell
.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.myreader.feature.viewermupdf.MuPdfLocationProbeTest
& "D:/develop/Android/Sdk/platform-tools/adb.exe" logcat -d -s M11PROBE
```

三组观测（同一 `epub_test.epub` 夹具，默认 312×504 / em=10）：

| 组 | 场景 | 观测值 | 判据 |
|---|---|---|---|
| A | 单会话自洽 | `page→Location→page`、`page→mark`、各章页数分布 | `pageBack == target` 即 Location 自洽 |
| B | **重开文档，同排版** | `pageNumberFromLocation(Location(ch,cp))`、`findBookmark(mark)→page`、`back == mark` | B 组 `pageFromLoc == target` → 方案 P 可行性成立 |
| C | **重开文档，换排版 420×640** | `pageNumberFromLocation(Location(ch,cp))` 及其回读 Location | 回读 Location 与原始相同 → `chapter+章内页` 跨排版稳定（否则需比例） |

> 若有设备侧证据需留档，按 AGENTS.md 工作规则 10 落 `/sdcard/Download/MyReaderTest/`，本地副本放 `tools/acceptance/verify/`。

### 3.3 决策树

| 分支 | 触发条件（S0-2 结果） | 交付 |
|---|---|---|
| **P（首选）** | B 组 `pageFromLoc` 可用（即使 mark 仍 -2） | 锚点 = `chapter:章内页:章页数`，比例优先；进度 codec v2 + 备份 v3 |
| Q | Location 路径不可用，但 mark 在同排版可解 | 仅修 `bookmarkToPage` 往返校验误判，比例锚点不做，登记限制 |
| R（止损） | 两路均不可用 | 不写代码，只文档化限制 + 进度/书签恢复的 UI 提示；退出本里程碑 |

## 4. 锚点格式（P 分支）

### 4.1 进度 payload（`core:document`）

`EpubProgressCodec.VERSION = 2`，`decode` 接受 `1..2`：

```jsonc
// v2（字段名避开既有 "chapter"（章节标题字符串），改为 chIdx/pageInChap/chapPages）
{ "v": 2, "chIdx": 3, "pageInChap": 7, "chapPages": 23,
  "loc": "-547637665110...", "page": 41, "chapter": "第三章" }
```

回落顺序：**比例（chIdx + pageInChap/chapPages）→ loc（mark 串）→ page**。
`chapPages` 为抓取时刻的章页数快照（重排后仅作分母）。脏值判据：`chIdx < 0`、
`chapPages < 1`、`pageInChap ∉ [0, chapPages)` 一律按「比例不可用」处理（回落 loc / page），不整条丢弃。

### 4.2 书签 anchor（`core:data`）

复用 `bookmarks.anchor` 这**一个 TEXT 列**承载两种格式，无需 Room schema 变更：

| 格式 | 值 | 说明 |
|---|---|---|
| 旧（M9/M10） | `-5476376651105784552` | 十进制 bookmark 串，继续可读 |
| 新（M11） | `cp:3:7:23` | `chapter:章内页:章页数`；解析失败视为旧格式 |

判重：新格式按「同 chapter 且比例差 ≤ 1%」视为同一位置；旧格式维持精确比对。
**实现约束**：`BookmarkDao.existsAtAnchor` 是 SQL 精确相等，容差判重不能复用它——
需在仓储层用 `bookmarksOf(documentId)` 取回列表在内存比对（书签上限 100，成本可忽略），
DAO 侧不新增查询。

### 4.3 备份（`core:data`）

`BackupCodec.SCHEMA_VERSION = 3`，版本门禁 `1..3`，`anchor` 正则放宽：

```
^(-?\d{1,20}|cp:\d{1,5}:\d{1,5}:\d{1,5})$
```

> 正则只做**格式**门禁（防注入/防超长）；`chapPages ≥ 1`、`pageInChap < chapPages` 等语义校验放在
> `BookmarkAnchorCodec` 里做（与 §4.1 进度侧共用同一套判据），备份侧只要求「能解析成合法锚点」。

v1 / v2 包导入路径不变（v2 包内十进制 anchor 按旧格式读）。**升版本的理由**：若沿用 v2 而只放宽正则，
旧版 App 读新包会以「非法书签锚点」整体拒收；升到 3 让旧版明确报「版本不支持」，语义更清晰。

## 5. 落地分解（P 分支）

### A-1 进度链路（`core:document` + `feature:viewer-mupdf`）
- `EpubProgressCodec`：v2 字段与 `decode(1..2)`；`encode(loc, page, chapter, chIdx, pageInChap, chapPages)`（后三者带默认值，旧调用点免改）
- `MuPDFCore` 增（MuPDF 边界内，工作规则 1）：`pageToLocation(page)` → `chapter:page`；
  `pageFromLocation(chapter, pageInChapter)` → page（失败 -2）；`chapterPageCount(chapter)` 扫描实现
- `EpubLocationResolver`：`progressForPage` 产出比例三元组；`pageForProgress` 按比例 → loc → page 回落
- `MuPDFCore.layout()` 内部旧页 remap 目前同样走 `makeBookmark`/`findBookmark`（**与失效点同源**：
  失败时回落 `oldPage`，重排后可能越界）→ 改为 `locationFromPageNumber(oldPage)` → `pageNumberFromLocation` 路径
- 单测：v1 可读、v2 往返、`cp > ct` / 负值判脏、缺字段回落

### A-2 书签链路（`core:data` + `feature:reader`）
- `BookmarkAnchorCodec`（新，`core:data` 或 `core:model` 纯逻辑）：`cp:` 编解码 + 旧格式识别
- 仓储：新增/判重/上限维持 M10 语义，判重分支按格式区分
- `ReaderViewModel.addBookmarkAtCurrent` 流式分支写新格式；跳转时解析新格式 → `Cfi` 比例锚点
- 单测：编解码往返、旧格式兼容、判重容差

### A-3 备份（`core:data`）
- `SCHEMA_VERSION = 3` + 正则放宽 + v3 导出
- 单测：v3 往返、v2 旧包导入、非法新格式拒绝、`cp:0:0:0` 脏值拒绝

## 6. 测试矩阵

| 层 | 用例 |
|---|---|
| JVM | `EpubProgressCodecTest`（v1 兼容 / v2 往返 / 脏值）、`BookmarkAnchorCodecTest`（新格式 / 旧格式 / 越界） |
| JVM | `BackupCodecTest`（v3 往返 / v2 导入 / 新格式非法拒绝）、`LibraryRepositoryImplTest`（比例判重） |
| 插桩 | `MuPdfLocationProbeTest`（S0 取证，结论固化后转为回归断言）、`MuPdfMultiFormatEngineTest` 增跨会话还原 |
| JVM | `ReaderViewModelTest`（流式书签写新格式、跳转解析） |

## 7. 真机验收（一加 PKC110 / Android 16）

1. epub 垂直：第 3 屏加书签 → `am force-stop` → 重进 → 落点 bounds 与创建时一致
2. epub 水平：加书签 → 横竖屏往返 ×2 → 点书签 → 落到同一段落
3. epub 水平：读到第 8 屏 → 退出 → 转屏 → 重进 → 同一段落（不再按旧排版页码）
4. 备份 v3 往返：导出 → 卸载重装 → 恢复 → 书签与进度逐项一致
5. v2 旧包导入：不崩、旧十进制书签可跳
6. R8 release 冒烟：抽验 1~3，logcat 无 FATAL/ANR

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| Location 路径跨会话同样失效 | 转分支 Q（去往返误判）或 R（文档化止损） |
| 章页数扫描开销 | 仅在抓取锚点时扫描，`Doc` 会话内缓存 `Map<chapter, pages>` |
| 比例精度不足以还原「同一屏」 | 误差判据放宽到 1 屏；书签用容差判重避免重复 |
| 旧版 App 读 v3 备份 | 预期明确报「版本不支持」（优于「非法锚点」） |
| M10 旧书签失去精确性 | 旧 anchor 继续可读；仅新加书签享比例精度 |

## 9. 排期

| 阶段 | 内容 | 预估 |
|---|---|---|
| S0-1 | fitz API 边界 | 已完成 |
| S0-2/3 | 真机探针 + 拍板 | 0.2 会话（需设备） |
| A-1 / A-2 / A-3 | 三条链路 + 单测 | 1 会话 |
| 真机验收 | 6 项 | 0.5 会话 |

## 10. 文档口径同步（完成时）

- `data-layer.md`：进度/书签锚点契约（比例优先）、备份 v3
- `design/flows/bookmark-m10.md` §9：已知限制改为「M11 已修（比例锚点）」
- `m9-plan.md` / AGENTS.md：M9「bookmark 串跨重排稳定」表述加 M11 修正指向
- README 路线图：M11 行；`app/build.gradle.kts`：`versionName 1.3.0 → 1.4.0`（M10 收尾时未升，M11 一并补）
