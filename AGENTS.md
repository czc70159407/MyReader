# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源入口，面向 agent 驱动开发：任何会话先读「当前状态」「工作规则」「文档导航」，再**按任务按需**查阅 docs/ 分册，避免一次性加载全部设计细节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

### 最近状态

- **M16 高亮锚点文本化（2026-11-25，真机验收 4/4 通过）**：EPUB 高亮锚点从「归一化页面比例 quad（v1）」升级为「文本指纹 v2」（章节 Location 分量 + 选中文本 + 前后文，JSON 存 `annotations.anchor`，**无 Room schema 变更**）。渲染端对当前页遍历全部 v2 锚点按页文本匹配重建 quad（**不限原页码**，重排后内容换页自动命中）；旧 v1 锚点降级不绘制（无法迁移）。fork 新增 `findTextQuads`/`pageText`（空白不敏感 squash 匹配——真机实证 fitz 字符流空白输出随排版变化），`layout()` 清页字符缓存。修复 3 缺陷：①字符缓存不随重排失效；②v2 仍按原页码过滤；③重建早于异步 relayout（引入 `layoutEpoch` 代次）。`build` 全绿。真机 4/4：旧 v1 降级、新建贴合、EM10→EM6 换页仍精确（32 quad）、EM6→EM13 再精确（29 quad）。证据 `tools/acceptance/verify/m15/`（png 61/65/71/76）。详见 [docs/m16-plan.md](docs/m16-plan.md) §5.1/§5.2。
- **M15 显示偏好（2026-11-25，真机验收通过 8/8）**：EPUB 阅读字号 5 档（EM 8/9/10/12/14；水平/垂直均整书重排，`core.layout(pagenum,…)` remap 保持位置）+ 固定版式默认缩放 5 档（fork `ReaderView.setDefaultScale`：立即生效 + refresh 回落该档；仅水平模式，路线 A 拍板）。双入口同源：设置页「显示」分区 + 阅读器顶栏「Aa」`DisplaySheet`（按 `reflowable` 分派，`FilterChip` 档位行）；偏好走 `SettingsPrefs.readerFontScale/readerZoomScale`（DataStore name 存储 + legacy key 兼容）。`.\gradlew.bat build` 全绿（detekt 修复 1 处 LongParameterList suppress；`SettingsPrefsTest` +3 例全绿）。P2 后置：TXT 字号、垂直 PDF 缩放、边距裁切（见 docs/m15-plan.md §8）。**真机验收通过（PKC110 / Android 17 / API 37，S1~S8 8/8，验收修复 0 缺陷）**：EPUB「大」档 27→75 页 remap 位置等义保持；杀进程重启持久化生效；水平模式横屏 84→竖屏 75 页分母随动；PDF 150% 放大 + 旋转回落精确 1.48≈1.5；设置页双向同源；309dp 顶栏 5 动作无溢出。**确认行为变更（非缺陷）**：垂直 EPUB 排版基准从固定 312×504pt 变为「视口宽 + 字号」（EM10：27→48 页），垂直旋转不重排。证据 `/sdcard/Download/MyReaderTest/m15/{png,xml}/`（46 份），本地 `tools/acceptance/verify/m15/`。详见 [docs/m15-plan.md](docs/m15-plan.md) §5.1。**验收后 P0 修复（2026-11-25）**：改字号后文档宽度/缩放漂移——根因 `MuPDFCore.getPageSize()` 返回 `page.getBounds()`（内容 bbox，宽度随字号 em 重排变化），被 PageView「整页适配」缩放（`mSourceScale = min(视口/bbox)`）与垂直页高比消费；**方案 A** 在 fork `MuPDFCore` 让 reflowable 的 `getPageSize`（返回固定 layout 画布 w×h）与 `drawPage`（以 layout 画布为缩放基准，内容等比不变形）双双改用 layout 尺寸，bbox 仅保留给内容坐标换算（选区/高亮/书签）；固定版式（PDF/CBZ/XPS）不受影响，上层零改动。`.\gradlew.bat build` 全绿（1056 tasks；顺带补 `MuPdfVerticalView` 的 `CyclomaticComplexMethod` suppress——M16 既有改动引入）。**真机验收（PKC110 / Android 17 / API 37，2026-11-25）**：页面级 6/6 通过（垂直/水平页数正确 45→93 / 63→84、横屏 `left/em` 23.5~23.6、选区+高亮精准、PDF 零回归）。**二次修复（用户反馈「行宽仍随字号变化」）**：根因 = MuPDF 默认 CSS 页面边距为 em 单位（`left = 3em × zoom`，即内容宽 = 页宽 − 6em）；在 fork `MuPDFCore.layout()` 前置 `doc.style(false, "@page { margin: 16pt; } body { padding: 0; margin: 0; }")` 固定物理边距。**验收**：竖屏 em 6/10/13 左边距 `126/125/126`、横屏 em 13/10 `128/125`（恒定 ≈ 16pt×560/72），行宽不再随字号变化；`em=6` 的 12 页为正确排版（页数 ∝ em²，非未重排）。证据 `/sdcard/Download/MyReaderTest/m15_p0/`（38 份），本地 `tools/acceptance/verify/m15_p0/`。详见 [docs/m15-plan.md](docs/m15-plan.md) §9/§9.1。
- **M14 文本朗读（2026-11-16，真机验收通过 10/10）**：PDF 内长按拖动选中文本 → 松手弹操作气泡（朗读 / 高亮 / 复制）。新增 `TextSelectionEvent(draft, anchorX, anchorY)` 携带屏幕锚点（纯 Float 坐标，不耦合 Compose 几何类型）（`core:document`，`onTextSelected` 签名 `(TextSelectionEvent) -> Unit`，`AnnotationDraft` 持久化语义不变）；`feature/reader/Tts.kt`（`Tts` + `detectLanguage`：含汉字 → zh-CN，否则 en-US）；`SelectionMenu.kt`（`Popup` 气泡对白框，20dp 圆角 + 三角尾巴 + 上下翻转，图标 `RecordVoiceOver`/`Brush`/`ContentCopy`）；水平 `finishSelection` / 垂直 `onGloballyPositioned` 换算选区屏幕锚点；复制走 `LocalClipboardManager`。**仅 PDF，零新增依赖**；`DetectLanguageTest`（4 例）+ 4 个测试 Fake 签名同步；`.\gradlew.bat build` 全绿。**真机验收通过（PKC110 / Android 17 / API 37）**：长按选中弹气泡；英文/中文朗读均验证 `AudioTrack state:started, CONTENT_TYPE_SPEECH`；高亮 Snackbar「已添加高亮」+ 黄色渲染；重复高亮「该位置已有高亮」；复制「已复制」；朗读中退出音频停止；深色主题气泡配色随 `colorScheme`；EPUB 选中/高亮回归正常；三动作 a11y `content-desc` 验证。**验收修复 4 缺陷**：①`AndroidManifest` 缺 `<queries>` TTS_SERVICE → 引擎 not bound 静默无声（加声明）；②`Tts` 初始化未就绪静默失败 → PENDING/READY/FAILED 三态 + 待播队列；③气泡定位缺窗口偏移偏上 220px → `getLocationInWindow`；④选中后看不到选中内容（松手清空选区预览又不落库）→ 选中草稿作为「临时高亮」附加 `annotations` 回放，气泡关闭即移除。证据 `/sdcard/Download/MyReaderTest/m14/{png,xml}/`（9 截图 + 31 dump），本地 `tools/acceptance/verify/m14/`。详见 [docs/m14-plan.md](docs/m14-plan.md) §5.1。

- **M13 批量操作（2026-11-10）**：书架「多选 → 批量移动 / 批量删除」。入口：浏览态长按卡片菜单「多选」（预选该卡）/ ⋮ 菜单「多选」（搜索态不提供）；多选顶栏（✕ /「已选 {n} 项」/ 全选 / 移动 / 删除）+ 卡片选择态（`Role.Checkbox` + `contentDescription` + 勾选覆盖层）；批量移动复用 `FolderPickerDialog`（防环 = 选中文件夹子树并集，红字 + 确定禁用）；批量删除混合后果文案 + 单事务删记录 + 物理文件后置删。核心：`core:model/Batch.kt`（`BatchMoveResult`/`BatchDeleteResult`/`MAX_BATCH_SELECTION=500`）+ `LibraryRepository.moveItems/deleteItems` + DAO 批量查询（**无 schema 变更**）。`.\gradlew.bat build` 全绿；`LibraryRepositoryBatchTest`（8 例）+ `SelectionTest`（5 例）+ `LibraryComponentsTest` 多选态 a11y 断言全绿。**真机验收通过（PKC110 / Android 17 / API 37，2026-11-10）**：长按菜单 + ⋮ 菜单两入口进多选；顶栏「已选 n 项」+ 全选/移动/删除；卡片 dump 为 `CheckBox` + `content-desc`（文件名/文件夹名）+ `checked` 状态；多选态隐藏最近阅读条；全选 2 项 + 取消全选；批量移动（选文件夹自身红字「不能移动到自身或其子目录」+ 确定禁用；移文档到文件夹 Snackbar「已移动 1 个文档」）；批量删除（文件夹确认框「将删除 1 个文件夹，其中的文档将移至根目录」→ 删除后内部文档回根 + Snackbar「已删除 1 个文件夹」；文档确认框「物理文件一并删除」→ Snackbar「已删除 1 个文档」）；操作后自动退出多选。证据：`/sdcard/Download/MyReaderTest/{xml,png}/m13_*`（16 项），本地副本 `tools/acceptance/verify/m13_*`。
- **应用图标与启动画面（D11，2026-11-10 观感升级）**：图标由「描边折角文档 + 深绿 `#1B5E20`」改为**实心「打开的书」+ 深墨绿 `#103D2C` / 奶油白 `#F5F1E4`**（原 4dp 描边在 48dp 下过细、且与系统文件图标语汇雷同）；SplashScreen 背景同步换色。`.\gradlew.bat build` 全绿、aapt 验证 APK 各密度均指向 adaptive icon。**真机验收通过（PKC110 / Android 17 / API 37；设备近期 OTA，历史验收记录为 Android 16）**：桌面图标实心书清晰、48dp 辨识度好；冷启动 SplashScreen 中心色实测 `#103D2C`。注：ColorOS launcher 覆盖安装后缓存旧图标，需 `am force-stop com.android.launcher` 才刷新。路线 C 不变（图标资源色独立于 UI 主题），色值见 `docs/design/tokens.md` §1.1，实现落点见 `docs/ui-design.md`「应用图标与启动画面」。证据：`/sdcard/Download/MyReaderTest/m13/png/{launcher_final2,boot_v2_1,boot_v2_2}.png`，本地副本 `tools/acceptance/verify/m13/`。
- **关于页（2026-11-09）**：应用标识 + 运行读取 `versionName` + 五个链接可点击/长按复制/链接语义已落地，`.\gradlew.bat build` 全绿。**真机验收通过（PKC110 / Android 17 / API 37，2026-11-09；设备近期 OTA，历史验收记录为 Android 16）**：版本号显示 `1.5.0`；五链接 UI dump 均为 `Button` + `focusable/clickable/long-clickable`，CD 为「标签，链接。双击打开，长按复制」；长按出 Snackbar「链接已复制」；点击打开系统浏览器且 URL 正确；字体 200% 下 URL 换行无裁切、可滚至末条；深浅主题配色随 `colorScheme` 变化。`[建议 P2]` 已闭环——修正与上游口径不符的 GitLab 链接，分列本项目主仓 / MuPDF 上游 / 实际集成的 viewer fork（直达 `myreader-patches` 分支）。**N/A 一项**：无浏览器兜底无法真机构造（系统浏览器 `pm disable-user` 返回 enabled、`pm hide` 需 `MANAGE_USERS`、suspend 后 intent 仍可解析），代码层已有反编译 `ui-android:1.7.4` 实证 + build 门禁覆盖。证据：`/sdcard/Download/MyReaderTest/m13/{png,xml}/`（21 项），本地副本 `tools/acceptance/verify/m13/`。规格 `docs/design/screens/about.md`，文案 `docs/design/copy.md` §10。

- **M12（v1.5）已完成并真机验收通过（2026-11-01~03）**：沉浸模式（顶栏 overlay + fade 显隐，7 条真机通过）+ 高亮/笔记（Room schema **v3** + `MIGRATION_2_3`、备份 **v4**，含 5 色板 / 笔记编辑 / 顶栏收纳「标注」双 Tab）。**已知限制（已拍板不做）**：①epub 水平模式自绘 overlay；②归一化 quad 跨重排页级近似提升；③epub 未端到端真机验证（SAF「最近」不索引 `.epub`，以 PDF 垂直同代码路径覆盖）。详见 [docs/m12-plan.md](docs/m12-plan.md)。

- **M11（v1.4）已完成并真机验收通过（2026-10-31）**：epub 比例锚点 `cp:<chapter>:<章内页>:<章页数>` + `EpubProgressCodec` v2 + 备份 `schemaVersion=3`；六项真机验收通过并修 2 缺陷。详见 [docs/m11-plan.md](docs/m11-plan.md)。

- **P0 detekt 存量清零（2026-10-31）**：173 条 baseline 全部清零，9 个 `detekt-baseline.xml` 删除，detekt 回归纯 config 门禁。详见 [docs/detekt-baseline-reduction.md](docs/detekt-baseline-reduction.md)。

### 里程碑状态表

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + Maven fitz | 已完成 |
| M2 | 数据层（Room/Hilt） | 已完成 |
| M3 | 导入与书架 | 已完成 |
| M4 | 阅读器（水平模式） | 已完成 |
| M5 | 目录树与文档管理 | 已完成 |
| M6 | 搜索/排序/最近阅读 | 已完成 |
| M7 | 垂直滚动 + 设置（v1.1） | 已完成 |
| M8 | 备份与恢复（v1.1） | 已完成 |
| M9 | 多格式支持（EPUB/CBZ/XPS + TXT 扩展点，v1.2） | 已完成（插桩 9/9；修 2 bug；XPS 正向渲染按 R3 待真实样本） |
| 安全审计与加固 | 全仓 code 安全审查 + 按清单修复 | 已完成（build SUCCESSFUL、双变体单测 322 用例 0 失败；R8 runtime 冒烟已关闭） |
| M10 | 书签（章节级）+ 首个 Room Migration（v1.3） | 已完成（schema v2 + `MIGRATION_1_2`；D 真机五项通过并修 2 缺陷） |
| M11 | epub 锚点稳健性 / 比例锚点（v1.4） | 已完成（`cp:` 比例锚点 + 备份 v3；六项真机验收通过并修 2 缺陷） |
| M12 | 沉浸模式 + 高亮/笔记（v1.5） | 已完成（A 沉浸模式 7 条真机通过；B 高亮/笔记 Room v3 + 备份 v4，两项低价值项拍板不做） |
| M13 | 批量操作（多选/批量移动/批量删除） | 已完成（真机验收通过 + `build` 全绿 + 单测/a11y 全绿） |
| M14 | 文本朗读（选中气泡：朗读/高亮/复制） | 已完成（真机验收 10/10 通过 + `build` 全绿 + 单测全绿；验收修复 3 缺陷） |
| M15 | 显示偏好（EPUB 字号 + 固定版式默认缩放） | 已完成（真机验收 8/8 通过 + `build` 全绿 + 单测全绿；验收修复 0 缺陷；垂直 EPUB 排版基准变更为视口宽+字号；字号档位后续调整为 6/8/10/13/16——原 8/9/10 间距过小导致「小↔较小」视觉不可辨） |
| M16 | 高亮锚点文本化（v2 跨字号/跨排版精确复原） | 已完成（真机验收 4/4 通过 + `build` 全绿；修复 3 缺陷；旧 v1 锚点降级不绘制） |

> 状态取值：未开始 / 进行中 / 已完成。标记「已完成」必须附验收证据（命令输出摘要），并同步更新「下一步」。里程碑任务与验收标准详见 docs/process.md。

### 环境与卡点

- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要；③**GitHub 443 间歇阻断**（本机 DNS 正常，TCP 握手时通时断，报 `443 after 21s` / `Empty reply from server` / `Connection was reset`）——`git push/pull` 会失败但**本地提交不受影响**；应对：先 `Test-NetConnection github.com -Port 443 -InformationLevel Quiet` 判可达，再**按分支分步推送**、间隔 15~30s 重试（一次推一条比连推两条成功率高），仍不行则需开代理。判据是链路层，不要误判为仓库或权限问题

### 历史归档

> M0~M12 逐会话验收证据、踩坑记录、决策与已知限制已归档至 [docs/history.md](docs/history.md)（历史快照，只增不改，非开发事实源）。需追溯历史细节时按需查阅。

## 工作规则（硬约束）

1. **MuPDF 边界**：MuPDF 具体类只允许出现在 `feature:viewer-mupdf` 与 `third-party:mupdf`；`feature:reader`/`feature:library` 只依赖 `core:document` 接口。新增格式 = 新建 `feature:viewer-xxx` + `@FileTypeKey`，不改 reader 与 schema
2. **AGPL 合规**：APP 整体开源；README + About 页注明 MuPDF 来源与 AGPL 全文链接；不引入 AGPL 不兼容的闭源组件
3. **Room 迁移纪律**：schema JSON 随仓库提交；禁用 `fallbackToDestructiveMigration`（阅读进度是核心数据）；改 schema 必须写 Migration
4. **完成定义**：代码变更须通过对应里程碑「验收」（见 docs/process.md）+ `.\gradlew.bat build` 无错，才算完成；声称完成必须附证据
5. **文档同步**：schema/模块结构/依赖版本/接口签名变更，同一任务内更新对应分册（导航见下方「文档导航」）；状态、验收证据、卡点只更新本文件
6. **不过度工程**：不引入文档未列出的依赖/模块；确需偏离选型，先改 docs/architecture.md「技术选型」章节并写明理由
7. **测试下沉**：纯逻辑放 `core:*` 做 JVM 测试；改 `core:data`/`core:storage` 必须补跑对应单测
8. **命令环境**：Windows PowerShell；clone 后执行 `git submodule update --init`——**勿加 `--recursive`**：viewer 的嵌套 jni 子模块刻意不初始化以走 fitz Maven 预编译分支（见 docs/architecture.md「MuPDF 集成方式」第 4 条）
9. **提交门禁**：所有代码 commit / push 前必须交由用户决策——用户可选择「先 review」或「直接提交推送」；未经用户明确同意，不得执行任何 commit 或 push
10. **真机验收文件落盘（红线）**：真机验收产生的所有测试文件（截图 / `uiautomator` dump / 日志 / 拉取产物）在设备上一律写入 `/sdcard/Download/MyReaderTest/`（与测试夹具同目录），**禁止散落到 `/sdcard` 根目录或任何其它目录**；收尾只清理本轮临时文件，留档产物按分类子目录保留。本地留档放 `tools/acceptance/verify/`（已被 `.gitignore` 排除），仓库不提交验收截图。内部结构、命名与归位记录见 [docs/acceptance-files.md](docs/acceptance-files.md)；两者冲突时以本条为准
11. **子模块提交门禁**：改 `third-party/mupdf-android-viewer` 后，子模块新 commit **必须** `git -C third-party/mupdf-android-viewer push fork myreader-patches`（推自己的 fork `czc70159407/mupdf-android-viewer` 的 `myreader-patches` 分支，**不是** `origin` 上游 ArtifexSoftware），并确认主仓 gitlink（`git ls-tree HEAD third-party/mupdf-android-viewer`）与 fork 分支顶端一致——否则 CI 的 `git submodule update --init` 拉不到 gitlink 指向的 commit，编译失败（M12 B/C 阶段曾踩坑：本地 commit 后未 push fork，主仓 gitlink `6785e32` 超前 fork 顶端 `9df85e4`，CI 红，修复即 push fork）
12. **分支主干与版本 tag**：主干固定为 `main`（仓库默认分支），日常开发切 `dev` 或按里程碑开 `feature/xxx`，完成后合并回 `main`；版本发布须打附注 tag——每次 `versionName`/`versionCode` 升级执行 `git tag -a vX.Y.Z <sha> -m "..."` 并 `git push origin --tags`（tag 名与 `app/build.gradle.kts` 的 `versionName` 一致，如 `v1.5.0`）

13. **真机验收设备状态还原（红线，M15 教训）**：验收涉及修改系统设置（`wm density`、`settings put system user_rotation` / `accelerometer_rotation` / `font_scale` 等）时，**必须在修改前先 `settings get` / `wm density` 记录原值**，验收收尾逐项还原并**复读核对**；涉及显示密度 / 字体大小的改动（直接影响用户全系统字体与 UI 观感），还原后需经用户确认方可断开设备。**禁止在未记录原值的情况下修改任何系统设置**（M15 验收曾改 `wm density` 未记录原值，用户系统字体观感被改动）。**特别警惕：`wm density` 在 ColorOS 上会按密度比例联动改写 `system.font_scale`（实测 560→480 时 1.0 被自动提至 1.15），且 `wm density reset` 不回滚该联动**——还原密度后必须核对 `font_scale` 与 `device_font_scale` 一致并手动还原（M15 实际踩坑：用户字体被联动改至 1.15 未察觉，用户反馈后才 `settings put system font_scale 1.0` 修复）。

14. **功能开发双文档门禁（红线）**：任何新功能进入开发（写代码）前，必须**同时**产出并通过评审两样文档——**产品设计文档**与 **UI 设计文档**，二者缺一不可；任一缺失即禁止开发。

    - **适用范围**：新增里程碑（M 系列）、对既有功能的可见行为变更（新增交互 / 新增界面 / 新增或改动文案与视觉规范 / 新增无障碍行为）。纯缺陷修复、纯重构（行为与界面零变化）、纯工程基建（依赖升级 / 构建脚本 / CI）**不在此列**；但若缺陷修复本身改变了交互或界面，仍须先补文档。
    - **触发时机**：里程碑「S0 预研」完成、进入编码实现前；两份文档均评审通过，是「开发」与「上线」的双重前置条件，**文档未通过评审前不得写任何功能代码**。
    - **文档核心内容**：
      - **产品设计文档**（本仓落点 `docs/mXX-plan.md` + 对应 `docs/design/flows/*.md` 的 PRD 段）：**功能目标**、用户场景与需求边界、非目标（明确「不做」）、成功度量与**验收标准**（含真机验收场景清单）。
      - **UI 设计文档**（本仓落点 `docs/design/`：`screens/*` / `flows/*` / `components/*` + `tokens.md` / `copy.md` / `accessibility.md`）：**交互流程**（状态机、分支、失败/空/加载态）、**界面规范**（组件规格、文案、色板/间距/圆角 token、无障碍清单）。
    - **责任角色**：功能负责人（Agent / 开发者）负责起草两份文档；评审结论与变更同步登记 `docs/design/README.md`「决策日志」与 `docs/process.md`「里程碑与任务分解」。
    - **评审与确认流程**：文档先于代码提交评审；评审通过须在 `docs/design/README.md` 决策日志（及对应 `mXX-plan.md` / AGENTS.md「当前状态」）留下记录，方可开始编码。未评审通过即编码的产出视为无效，不得合并、不得上线。
    - **未满足处理措施**：缺任一文档 → **禁止开发**（不得写功能代码）；文档未评审通过 → **禁止合并 / 上线**；事后补文档不改变「先评审后开发」的顺序，已开发代码须按评审结论回退或返工。
    - **例外申请与审批**：仅紧急缺陷修复、无可感知行为变化的小改动等确需免文档的场景可申请例外。功能负责人须书面说明（改动范围、为何无需文档、风险）并交项目维护者（用户）**明确书面批准**，批准记录写入对应 `mXX-plan.md` 或 AGENTS.md「当前状态」；**未获批准一律按默认门禁执行**。

## 常用命令（PowerShell）

```powershell
.\gradlew.bat build                              # 全量构建 + 测试
.\gradlew.bat :core:data:test                    # 数据层单测
.\gradlew.bat :app:assembleDebug                 # 装配 APK
.\gradlew.bat :third-party:mupdf:assembleDebug   # 构建 MuPDF viewer 库（fitz 走官方 Maven 预编译，无 native 编译）
.\gradlew.bat detekt koverHtmlReport             # T7 质量报告（detekt 静态检查 + 覆盖率，第一轮 ignoreFailures 仅报告）
```

## Context

项目目录 `D:\develop\code\MyReader`，安卓 PDF 阅读器 APP。核心需求三点：

1. **本地 PDF 阅读**：从设备存储选取 PDF 导入并阅读
2. **多级目录管理**：树形文件夹结构，可新建/重命名/删除/移动，PDF 可归入任意层级
3. **阅读进度记忆**：每文档记住上次页码，再次打开恢复

APP 整体开源（MuPDF AGPL 要求）。

## 文档导航（按需加载）

| 任务场景 | 分册 |
|---|---|
| 改模块结构 / 依赖版本 / MuPDF 集成 / 偏离选型 | [docs/architecture.md](docs/architecture.md)（技术选型、项目结构、模块依赖、MuPDF 集成方式、依赖项清单） |
| 改 Room schema / DAO / Repository / 存储策略 / 进度写库 | [docs/data-layer.md](docs/data-layer.md)（Room Schema、DAO、删除目录策略、存储策略、进度记忆） |
| 改阅读器渲染 / 路由 / 目录树 / 导入流程 / 错误边界 | [docs/ui-design.md](docs/ui-design.md)（Compose 集成、渲染与内存策略、UI 架构、导入流程、错误处理） |
| 改 UI 交互 / 文案 / 视觉基线 / 无障碍 / 新建或评审设计条目 | [docs/design/README.md](docs/design/README.md)（设计意图事实源：索引与决策日志、tokens、copy、screens、flows、accessibility） |
| 推进里程碑 / 写测试 / 排风险 / 查文件位置 | [docs/process.md](docs/process.md)（里程碑分解、测试策略、风险对策、关键文件清单） |
| 执行 / 追溯里程碑计划 | 对应 `docs/mXX-plan.md`（按里程碑号，含执行计划、S0 预研、验收方案）；现有里程碑清单与状态以上方「里程碑状态表」为准 |
| detekt 存量削减（已完成） | [docs/detekt-baseline-reduction.md](docs/detekt-baseline-reduction.md)（P0 已 100% 完成 173→0；削减方法学供 baseline 复发时参考） |
| 真机验收产物落盘 / 查历史 dump 与截图 | [docs/acceptance-files.md](docs/acceptance-files.md)（设备侧唯一目录 `/sdcard/Download/MyReaderTest`：分类子目录、命名约定、2026-10-29 归位记录） |
| 追溯历史会话日志 / 踩坑 / 决策 | [docs/history.md](docs/history.md)（M0~M12 逐会话验收证据与决策归档，历史快照只增不改；**设计决策以 `mXX-plan.md` 为准**） |

> **分册纪律**：每份分册单一职责，同一内容禁止两处维护；状态与验收证据只进本文件，设计规范只进分册。
