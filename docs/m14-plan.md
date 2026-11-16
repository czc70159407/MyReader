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
