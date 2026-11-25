# M15 显示偏好：阅读字号（EPUB）+ 默认缩放（PDF/CBZ/XPS）

- 状态：代码完成（`build` 全绿），**待真机验收**
- 优先级：P0（阅读体验）

## 1. 目标与范围

1. **EPUB 字号**：5 档（EM 8/9/10/12/14，默认 10）——水平/垂直均按字号整书重排，页码漂移经 `core.layout(pagenum,…)` remap 保持位置，页数经 `onPageCountChanged` 上抛。
2. **固定版式默认缩放**（PDF/CBZ/XPS）：5 档（1.0×/1.25×/1.5×/2.0×/3.0×，相对「适合宽度」），仅水平模式；fork `ReaderView.setDefaultScale` 立即生效，refresh 后回落该档。
3. **双入口同源**：设置页「显示」分区 + 阅读器顶栏「Aa」`DisplaySheet`（按 `reflowable` 分派），写同一 DataStore 全局偏好（跨会话/跨文档/跨重启）。

**非目标（P2 后置）**：TXT 字号；垂直模式 PDF 缩放（路线 A）；边距裁切（§8）；字距/段距/页边距（MuPDF reflow 不暴露）。

## 2. 决策记录（用户拍板）

| # | 决策点 | 定稿 | 理由 |
|---|---|---|---|
| 1 | 作用域 | EPUB 字号 + PDF 缩放替代 | PDF 固定版式无「正文字号」；缩放 =「默认看到更大字」 |
| 2 | 字号档位 | 5 档 EM 8/9/10/12/14 | 离散档位避免滑杆海量重排；EM=10 与 MuPDF 默认对齐零回归 |
| 3 | 缩放档位 | 5 档 1.0~3.0 | FIT_WIDTH(1.0) 与上游 `mScale=1.0` 等价零回归 |
| 4 | 垂直 EPUB 重排 | 接受，本期实现 | EPUB 默认垂直模式，不做则字号垂直无效 |
| 5 | 垂直 PDF 缩放 | 路线 A：后置 | 需横向滚动容器，控范围 |
| 6 | 持久化语义 | 全局偏好（非会话态） | 阅读舒适度偏好（同主题），区别于「阅读模式」会话态 |
| 7 | TXT | P2 | 验证模块 |
| 8 | 边距裁切 | P2 独立里程碑 | 正交能力，需 S0 预研（MuPDF 内容边界 API 未暴露） |

## 3. 实现落点

| 模块 | 文件 | 改动 |
|---|---|---|
| core:model | `ReaderFontScale.kt`、`ReaderZoomScale.kt`（新增） | 两枚举（`em`/`factor`+`label`）+ `FALLBACK` |
| core:data | `SettingsPrefs.kt` | `readerFontScale`/`readerZoomScale` 流 + setter（name 存储 + legacy int key 兼容，同 ThemeMode 纪律） |
| core:document | `DocumentViewerProvider.kt` | `Content` 加 `fontScale`/`zoomScale`（无默认值） |
| viewer-mupdf | `MuPdfReaderView.kt` | `REFLOW_EM` → `appliedFontEm` 差分；提取 `scheduleRelayout`/`relayoutToViewport`（字号与尺寸变化同路径）；update 块缩放差分 → `setDefaultScale`（仅固定版式） |
| viewer-mupdf | `MuPdfVerticalView.kt` | 加 `fontScale`+`onPageCountChanged`；恢复 effect 重构：reflowable 先 `core.layout(currentPage,…)` remap 再锚定；`evictAll(invalidatePageSizes=true)` |
| viewer-mupdf | `MuPdfViewerProvider.kt`、`VerticalPageLoader.kt` | 加参转发；`evictAll` 支持页尺寸失效 |
| third-party（fork） | `ReaderView.java` | `mDefaultScale` + `setDefaultScale()`（立即生效）+ `refresh()` 回落默认缩放（原硬编码 1.0） |
| feature:reader | `ReaderViewModel.kt` | 两偏好独立 StateFlow（不并入 Ready，避免 key(s) 重置会话态）+ setter（写失败静默降级） |
| feature:reader | `ReaderScreen.kt` + `DisplaySheet.kt`（新增） | 顶栏第 4 动作「Aa」（`FormatSize`）；`Content` 传参；`ModalBottomSheet` + `FilterChip` 档位行 |
| feature:settings | `SettingsScreen.kt`/`SettingsViewModel.kt` | 「显示」分区（字号/缩放两组 RadioRow）+ 两流 + setter |
| 测试 | `SettingsPrefsTest` +3 例；4 个 fake 签名同步 | `ReaderViewModelTest`、`LibraryViewModelTest`、`DocumentImporterTest`、`DirectoryImporterTest` |

## 4. 测试与门禁（2026-11-25）

- `.\gradlew.bat build` 全绿（全部模块单测 + detekt；修复 1 处 `LongParameterList` suppress——`DisplaySheet` 6 参，同 `Content` 先例）
- `SettingsPrefsTest` 新增 3 例全绿

## 5. 真机验收方案（待执行）

产物按红线落盘 `/sdcard/Download/MyReaderTest/`（工作规则 10），本地留档 `tools/acceptance/verify/m15/`。

| 场景 | 要点 |
|---|---|
| S1 EPUB 字号调大 | 垂直 EPUB「Aa」→「大」→ 页数变化、当前位置 remap 保持 |
| S2 EPUB 字号跨会话 | 退出重进仍「大」（持久化） |
| S3 EPUB 旋转 | 「大」下旋转 → 重排到新视口、位置保持、分母随动 |
| S4 PDF 缩放 | A4 PDF →「150%」→ 页面放大、超宽可横向滑动 |
| S5 PDF 旋转回落 | 150% → 旋转 → 回落 150%（非 1.0） |
| S6 设置页同源 | 设置页改 → 打开即生效；阅读器改 → 设置页选中态跟随 |
| S7 格式分派 | 同一面板：PDF 显缩放档、EPUB 显字号档 |
| S8 360dp | 顶栏 5 图标不溢出、标题可点（reader.md §11-10） |

### 5.1 验收结果（待补）

## 6. 已知限制

- 缩放超出「适合宽度」后需横向滚动（固定版式物理限制）；2×~3× 明显。
- 垂直模式 PDF 缩放不生效（路线 A 后置）；TXT 不受字号影响（P2）。
- 垂直 EPUB relayout 后页内像素偏移不保留（对齐页顶；M7 跨宽度近似同口径）。

## 7. 风险与应对

| 风险 | 应对 |
|---|---|
| fork gitlink 不对齐 → CI 红 | commit 后 `git ls-tree HEAD third-party/...` 比对 fork 顶端（工作规则 11） |
| GitHub 443 阻断 push | `Test-NetConnection` 预判 + 分步重试 |
| 垂直 relayout 位置漂移 | `core.layout(pagenum)` remap + pageCount clamp |
| 大字号 ANR | 重排在 `Dispatchers.Default`（水平/垂直同口径） |

## 8. 方案 P2：边距裁切（规划，暂不实施）

- 预研：MuPDF 页内容 bbox（`StructuredText` / 页内对象并集 + 安全内缩）；当前 `MuPDFCore` 未暴露，需 fork 补 API。
- 建议全书统一裁切（各页 bbox 并集），避免翻页视觉跳动；与缩放正交可组合；高亮/选区归一化 quad 锚点不受影响（仅渲染 offset 变化）。
