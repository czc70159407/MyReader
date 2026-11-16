# M14 文本朗读（选中气泡：朗读 / 高亮 / 复制）

> 状态：代码实现 + `.\gradlew.bat build` 全绿（2026-11-16）；真机验收待执行。

## 1. 目标与范围

PDF 内长按拖动选中文本 → 松手弹操作气泡（朗读 / 高亮 / 复制）。朗读用系统 `TextToSpeech`（中英自动检测），高亮复用 M12 落库链路，复制写剪贴板。**仅 PDF**，零新增依赖。

### 非目标（明确不做）

- 单击自动吸附整句（需 `selectAt` + 朗读模式开关，留后续）。
- EPUB / TXT 朗读。
- 语速 / 音色设置、朗读跟随滚动、逐句语言精确分派。

## 2. 决策记录

| 维度 | 结论 |
|---|---|
| 触发 | 长按拖动选中后弹气泡菜单 |
| 菜单形态 | `Popup` 气泡（对白框，20dp 圆角 + 三角尾巴 + 上下翻转定位） |
| 图标 | 朗读 `RecordVoiceOver` / 高亮 `Brush` / 复制 `ContentCopy` |
| 语言 | `detectLanguage`：含汉字（Han 脚本）→ `zh-CN`，否则 `en-US` |
| 视觉 | 轻量活泼化，不越 `tokens.md` 禁止清单、不引新依赖 |

## 3. 实现落点

- `core:document/DocumentViewerProvider.kt`：新增 `TextSelectionEvent(draft, anchorX, anchorY)`（纯 Float 坐标，不耦合 Compose 几何类型）；`onTextSelected` 签名 `(TextSelectionEvent) -> Unit`（`AnnotationDraft` 持久化语义不变）。
- `feature/reader/Tts.kt`：`Tts` 封装（init/speak/stop/shutdown）+ `detectLanguage` 纯函数（下沉供 JVM 单测）。
- `feature/reader/SelectionMenu.kt`：气泡菜单 + 三动作（`PopupPositionProvider` clamp 窗口内）。
- `feature/reader/ReaderScreen.kt`：选中暂存 `selectionMenuTarget` + Tts 生命周期（`DisposableEffect.shutdown`）+ 气泡渲染 + `LocalClipboardManager` 复制。
- `feature/viewer-mupdf/MuPdfReaderView.kt`：`finishSelection` 用 `scaleOf()` + `getDisplayedView()` 换算选区首 quad 屏幕锚点。
- `feature/viewer-mupdf/MuPdfVerticalView.kt`：`onGloballyPositioned` 捕获页 Box 窗口原点，`origin + 选区起点` 得屏幕锚点。
- `feature/viewer-txt/TxtViewerProvider.kt`：签名适配（TXT 无文本选择）。

## 4. 测试与门禁

- JVM 单测 `DetectLanguageTest`：纯英文 / 含中文 / 空白兜底 / 混排含汉字（4 例）。
- `.\gradlew.bat build` 全绿；`DocumentViewerProvider.Content` 签名变更已同步 4 个测试 `FakeViewer`/`ThrowingViewer`。

## 5. 真机验收方案

设备侧证据落 `/sdcard/Download/MyReaderTest/`，本地留档 `tools/acceptance/verify/`（红线）。

1. 长按选中英文句 → 松手弹气泡（非直接落库）；
2. 点「朗读」→ 设备英文音色朗读选中句；
3. 选中中文段 → 中文音色朗读；
4. 点「高亮」→ Snackbar「已添加高亮」+ 列表/回放可见；
5. 点「复制」→ Snackbar「已复制」+ 粘贴内容正确；
6. 重复位置高亮 → Snackbar「该位置已有高亮」不重复落库；
7. 朗读中退出阅读器 → 停止、无残留/崩溃；
8. 深浅主题 → 气泡配色随 `colorScheme`；
9. TalkBack → 三动作可聚焦、`contentDescription` 正确；
10. epub 回归 → 打开 epub 选中「高亮」仍正常（签名变更回归）。

## 5.1 验收结果（PKC110 / Android 17 / API 37，2026-11-16）

**10 项全部通过**：

| # | 项 | 结果 |
|---|---|---|
| A1 | 长按选中 → 气泡 | 通过（选中弹气泡，不再直接落库） |
| A2 | 英文朗读 | 通过（`AudioTrack state:started, CONTENT_TYPE_SPEECH`） |
| A3 | 中文朗读 | 通过（中文段选中朗读，引擎中文音色） |
| A4 | 高亮 | 通过（Snackbar「已添加高亮」+ 黄色 annotation 渲染） |
| A5 | 复制 | 通过（Snackbar「已复制」） |
| A6 | 重复高亮 | 通过（Snackbar「该位置已有高亮」） |
| A7 | 朗读中退出 | 通过（音频停止 + 回书架，无残留） |
| A8 | 深色主题 | 通过（气泡配色随 `colorScheme` 变深紫） |
| A9 | 无障碍 | 通过（三动作 `content-desc` + 可聚焦，UI dump 验证；TalkBack 完整手势交互未自动化） |
| A10 | EPUB 回归 | 通过（长按选中弹气泡 + 高亮落库，签名变更无回归） |

### 验收发现并修复的缺陷

1. **`not bound to TTS engine`（致命）**：`AndroidManifest.xml` 缺 `<queries>` TTS_SERVICE 声明，Android 11+ 包可见性导致 `TextToSpeech` 无法发现系统引擎，朗读静默失败。加 `<queries>` 声明修复。
2. **初始化未就绪静默失败**：`Tts.speak` 在 `onInit` 完成前调用返回 true 但无声音。改为 PENDING/READY/FAILED 三态 + 待播队列（`onInit` 成功后自动播放；FAILED 返回 false 提示）。
3. **气泡定位偏上约 220px**：水平模式用 `pv.left/top`（父容器坐标）当窗口坐标，缺状态栏/内容区偏移。改用 `getLocationInWindow` 换算（真机复验气泡已紧贴选中文字）。
4. **选中后看不到选中内容（用户反馈）**：渲染端松手即清空选区预览（M12 靠「松手即落库高亮」接管显示，M14 改为弹气泡不落库，导致无任何视觉反馈）。修复：`ReaderScreen` 把当前选中草稿附加到 `annotations` 参数作为「临时高亮」回放，气泡关闭（动作执行/取消）即移除。真机复验：水平 PDF（原生 annotation 回放）与垂直 EPUB（overlay 回放）选中均显示黄色高亮；点「朗读」后气泡关闭、高亮同步清除（未落库位置无残留）。

证据：`/sdcard/Download/MyReaderTest/m14/{png,xml}/`（9 张截图 + 31 个 UI dump），本地副本 `tools/acceptance/verify/m14/`；测试夹具 `m14_tts_test.pdf`（生成脚本 `tools/acceptance/gen_m14_test_pdf.py`）。

## 5.2 v3 微信读书式定稿（2026-11-16）

- **菜单**：`SelectionMenu` 深色横条（`#2C2C2C` + 白色图标/文字，三动作：朗读/高亮/复制），底部**弧形鱼尾指针**（两侧 quadratic 内凹）指向选区上沿；居中于选区（锚点改为「选区中心 X + 选区上沿 Y」）、出屏 clamp、上方空间不足翻转下方。（「更多」扩展位因暂无实际功能已移除，避免死按钮；后续接入查询/分享等功能时再加回。）
- **手柄**：泪滴形（尖朝上贴选区下沿，主体半径 10dp、尖点-圆心 13dp），水平 `drawTeardrops`（Canvas Path：quadratic + arcTo 225°/-270°）+ 垂直 `SelectionHandle`（Compose Path 同几何）；拖动回报**尖点**页面坐标（顺带修复垂直手柄拖动未做坐标换算的隐藏 bug）。
- **选区**：`primary` 25% 半透明，不遮字。
- **拖手柄收起菜单**：`Content` 新增 `onSelectionAdjusting(Boolean)` 回调，S2a 拖动期间隐藏菜单、松手在新位置重弹（真机验证：拖动中/松手后菜单深色像素 136 vs 19050）。
- **深色主题对比度验证**：PDF 内容不反色（白底），菜单条 `#2C2C2C` 其上实测 6.15:1（WCAG 非文本 3:1 达标）；页面外深色背景 2.65:1（边缘情况，不处理）。

## 6. 已知限制

- 朗读内容 = 手动选中文本（非自动句子吸附）。
- 语言检测按「是否含汉字」二分（混排整段走中文）。
- 依赖设备语音包；缺失时 `speak` 返回 false + Snackbar「未找到语音包，请在系统设置中下载」。
- 垂直模式气泡锚点为选区起点近似（页 padding 2dp 内偏差，不影响定位）。

## 7. 风险与应对（摘要）

| 风险 | 应对 |
|---|---|
| 语音包缺失静默失败 | `speak` 返回 false → Screen 弹 Snackbar |
| 签名变更波及 TXT / 测试 Fake | 已同步适配 + epub 回归验收 |
| TTS 生命周期泄漏 | `DisposableEffect` + `shutdown`（内含 stop） |
| 气泡坐标失效（旋转/分屏） | `PopupPositionProvider` 按窗口 size clamp |

## 8. 方案 B：拖柄词级精确选择（规划，2026-11-16 修订版）

> 背景（真机反馈）：拖动手柄时手柄偶尔乱窜、忽然到行末。根因：拖柄重算用 `selectText` → `StructuredText.highlight(矩形)`，矩形选择在手指 y 微抖跨行时会把选区末尾扩展到相邻行行末；泪滴主体位于「选区下沿 + 13dp」的下一行区域，`y - drop` 反解稍有偏差即触发跨行。

### 8.1 目标与范围

- **目标**：拖柄时选区吸附词边界、平滑跨行、不乱窜。
- **范围**：水平 PDF（`MuPdfReaderView`）+ 垂直 EPUB（`MuPdfVerticalView`）；`MuPDFCore` 新增词级选择 API。
- **非目标**：不改长按框选 `selectText`；不改选区视觉（泪滴/菜单）；不解决「垂直手柄 vs LazyColumn 滚动抢占」（既有风险，另议）。

### 8.2 核心思路

拖柄重算从「矩形 `highlight`」改为「词级 `highlightSelection`」：fitz 的 `highlightSelection(a, b)`（若可用）按阅读顺序返回「a 到 b 的连续文本」quads，天然跨行且不含无关行。

### 8.3 S0 可行性门禁（4 项，任一不过 → 回退方案 A）

| # | 验证项 | 方式 | 不过的处置 |
|---|---|---|---|
| V1 | `StructuredText.highlightSelection`/`copySelection` **存在** | 编译探针（引用方法编译）或 `javap` 反编译 aar | 方案 B 重构（`snapSelection`×2 + 手动拼 quads）或降级方案 A |
| V2 | JNI **不崩溃**（`search` 曾 `jobject invalid local reference` 崩溃，同源风险） | 真机调用 | 降级方案 A |
| V3 | **反向端点**语义（touch < fixed） | 真机拖动验证 | 实现端点交换 |
| V4 | **空选区**（空白处 `snapSelection` 返回 null） | 真机拖到空白区 | 松手保持原选区（不清除） |

### 8.4 吸附粒度决策

- 英文：`SELECT_WORDS`（词吸附）。
- 中文/CJK：fitz 无中文分词器，`SELECT_WORDS` 对无空格中文可能退化为整段 → S0 验证；若退化严重，中文改 `SELECT_CHARS`（字符级）。
- 决策点：S0 真机验证后定，写入实现注释。

### 8.5 边界与降级

- 拖到空白处/无效位置：`selectTextByWords` 返回 null → 预览清空、松手**保持原选区**（不提交空）。
- 反向拖动（越过固定端）：端点交换后调 `highlightSelection`。
- 拖柄跨页：不跨页（拖动中手势不翻页），明确为范围约束。

### 8.6 阶段与交付物

| 阶段 | 内容 | 交付物 | 判据 |
|---|---|---|---|
| S0 | 4 项门禁验证 | 探针结论（V1~V4） | 4/4 通过或转方案 A |
| S1 | `MuPDFCore.selectTextByWords` | API + 边界注释 | 编译 + 真机选区正确 |
| S2 | 水平接入 `adjustSelection` | 改造 + 证据 | 拖柄不乱窜、平滑跨行 |
| S3 | 垂直接入 `selectQuads` | 改造 + 证据 | EPUB 同上 |
| S4 | 测试验收 | 单测/build/真机清单 | 全绿 |
| S5 | 文档 + 提交 | `docs` 更新 + dev 推送 | 推送成功 |

### 8.7 测试策略（修正）

- `MuPDFCore` 依赖 native，**无法 JVM 单测**；「端点选择/方向判断」等纯逻辑可下沉 `core:*` 单测，JNI 部分全部真机覆盖。
- **回归**：①长按框选 `selectText` 行为不变；②高亮落库 `replayAnnotations` 不变。

### 8.8 风险与应对

| 风险 | 应对 |
|---|---|
| V1 方法不存在 / V2 JNI 崩溃 | 降级方案 A（拖柄锁行、只水平调整，1 行改动） |
| CJK 分词退化 | S0 验证；退化严重则中文改 `SELECT_CHARS` |
| 性能（每 MOVE 多次 JNI 遍历） | 复用同一 `StructuredText` 实例；真机实测卡顿 |
| 改 third-party 的 AGPL/边界 | 仅在 `selectText` 旁加方法，不改底层 C |
| 回退门槛 | S0 4 项任一不过 → 转方案 A，不硬上 |

### 8.9 已知限制（预期）

- 中文词吸附精度依赖 fitz 分词（可能字符级）。
- 拖柄不跨页。
- 空白处拖动不改变选区。

### 8.10 S0 结论（2026-11-16 执行）

| 项 | 结果 |
|---|---|
| V1 | ❌ **`StructuredText.highlightSelection`/`copySelection` 在 fitz 1.28.2 不存在**（编译报「找不到符号」）。改走 **`walk(StructuredTextWalker)`**：`onChar` 回调提供每字符精确 Quad，实现 `selectTextByChars`（字符级截取）——编译通过 |
| V2 | ✅ 无 JNI 崩溃（历史 FATAL 均为 `com.heytap.cloud`，非本应用） |
| V3 | ✅ 反向拖动生效（选区 100-780 → 100-238，`min/max` 自动处理） |
| V4 | ✅ 拖到空白不崩溃（吸附最近字符，选区 y 721-1055） |
| 核心 | ✅ 微抖不乱窜（选区 y 721-765 保持）；✅ 平滑跨行（y 721-837 扩展到第 2 行） |

**实现落点**：`MuPDFCore.selectTextByChars`（walk 逐字）+ 水平 `adjustSelection` / 垂直 `selectQuads` 已切换。证据：`m14_62~66`（基线/微抖/跨行/反向/空白）。

**性能优化（S0 后追加）**：页文本结构（字符 Quad + unicode）按页缓存（`getTextStructure`，`TextStructure` 内部类），拖柄高频 MOVE 只做内存索引查找，**不再每次 `toStructuredText` + walk 全页**；翻页时按 `textStructurePage` 自动重建。验证：同页拖动选区正确（`m14_67`）、翻页后重建正常（`m14_68`）。
