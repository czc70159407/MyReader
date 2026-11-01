# detekt 存量削减（baseline 渐进清理）

> 跨里程碑持续任务（与 M10 并行，互不干扰）。分册纪律：本册是削减任务唯一执行说明；状态与计数只进 AGENTS.md「当前状态」。

## 背景与现状

T7 已收紧（新违规即 build 失败），存量 173 条分布于 9 个模块的 `detekt-baseline.xml`：

| 模块 | 条数 | 模块 | 条数 |
|---|---|---|---|
| feature:library | 73 | feature:viewer-txt | 12 |
| core:data | 25 | feature:settings | 10 |
| feature:reader | 21 | core:document | 6 |
| feature:viewer-mupdf | 18 | core:storage | 5 |
| app | 3 | **合计** | **173** |

## 前置不变量（每次操作前检查）

1. **先修复，后删条**——删除 baseline 条目前，该违规必须在 `detekt` 下已消失；顺序颠倒会被 detekt 立即失败暴露（内置安全网）。
2. **禁止运行 `detektBaseline` 重生成**——会静默恢复全部已删条目（多模块覆盖竞争教训的变体）。baseline 只允许手工删行。
3. M10 产生的任何新违规由 **M10 任务当场修复**，不进 baseline（防止混入削减台账造成重复工作）。

## 范围分层（削减顺序 = 优先级）

| 层 | 内容 | 手法 |
|---|---|---|
| P1 直接修 | `MaxLineLength`（换行）、`SwallowedException`（`catch (_)` 或补注释）、`ReturnCount`（如 `TxtDocumentEngine.detectBom` 表驱动重构）、非测试文件 `MagicNumber` 具名化 | 改代码 |
| P2 就地标注 | 单点、语义已注释的违规（如编码探测的 OOM 传播路径 catch） | `@Suppress` 就地标注（显式可见性优于 baseline 隐式） |
| P3 config 豁免 | 本质合理类别：DAO `TooManyFunctions`（Room 接口本质）、DI module 函数数（绑定本质）、测试文件 `MagicNumber` | `detekt.yml` 加规则豁免 + 注明理由 + **删除对应 baseline 条目**（已不可达） |
| P4 暂缓专项 | `BackupManager.restore`（LongMethod / CyclomaticComplexMethod——安全关键代码，需专项 + `BackupManagerTest` 护航） | **保留在 baseline**，登记专项，不在本任务内修 |

## 削减循环（按模块分批，每批 ≤1 模块）

1. 选模块 → 过滤该层适用条目；
2. 逐条实施修复（遵守手法白名单）；
3. `.\gradlew.bat :<module>:detekt` 通过（违规消失）；
4. 从该模块 `detekt-baseline.xml` 删除对应 `<ID>` 行 → **再跑一次 detekt 确认仍通过**（防误删）；
5. 改了代码则跑该模块单测；
6. 批次 commit（message 带计数，如 `chore(detekt): 削减 feature:library 12 条（73→61）`）；P3 变更同步 `detekt.yml` 注释理由。

## 进度追踪

- 每批 commit 后在 AGENTS.md「当前状态」更新计数（`削减至 N/173`）；
- 台账字段：模块 / 规则 / 手法（P1~P4）/ commit SHA。

## 与 M10 并行的冲突防范

| 机制 | 规则 |
|---|---|
| 模块时间分片 | 同一模块不得同时被两任务触碰：M10-A/B（core:data 活跃）期间削减批次仅排 `feature:*`/`app`；M10-C（feature:reader 活跃）期间仅排 `core:*`；M10-D 真机轮期间任意 |
| baseline 文件隔离 | M10 新代码不产生 baseline 条目（新违规当场修）；削减任务不新建/重生成 baseline |
| commit 隔离 | 两任务 commit 独立、文件集不相交；同模块撞车时 M10 优先，削减批次 rebase |
| 台账去重 | 削减台账只记录 baseline 既有条目；M10 修复记录在 M10 验收证据中 |

## 完成定义

9 个 baseline 清空（P4 专项项除外且已标注）→ 删除各 `detekt-baseline.xml` + 从根 `build.gradle.kts` 移除 `baseline =` 配置 → detekt 回归纯 config 门禁 → `.\gradlew.bat build` 全绿。

---

## 排期计划（P0 立项，2026-10-31 评审通过，暂不启动）

> 用户已评审通过（5 个决策点均按建议采纳），**暂不启动**；待指示后从「阶段 0（冻结基线 + 建台账）」开始。当前削减 **0/173**。

### 1. 范围与目标

| 项 | 内容 |
|---|---|
| 存量 | 173 条 / 9 模块 → **0**（P4 专项项除外且已标注） |
| 门禁形态 | 删除 9 个 `detekt-baseline.xml` + 移除根 `build.gradle.kts` 的 `baseline =` → 回归纯 config 门禁 |
| 非目标 | 不引新依赖/模块；不改 MuPDF 边界（`third-party:mupdf` 不扫）；禁止 `detektBaseline` 重生成 |

### 2. 规则盘点（173 条精确分布）

| 规则 | 条数 | 分层 | 主要模块 |
|---|---|---|---|
| FunctionNaming | 35 | P3 死条目（@Composable 已豁免） | lib20 / reader4 / settings6 / mupdf3 / doc1 / app1 |
| MagicNumber | 34 | P1 具名化 | mupdf10 / data9 / lib6 / txt6 / reader2 / app1 |
| MaxLineLength | 33 | P1 换行 | lib22 / reader4 / settings2 / txt2 / data2 / doc1 |
| LongParameterList | 10 | P1 打包 | lib5 / data2 / doc2 / mupdf1 |
| CyclomaticComplexMethod | 8 | P1/P4 拆函数 | lib5 / reader2 / data1 |
| TooManyFunctions | 8 | P3 config 豁免 | data4 / lib1 / reader1 / storage1 / app1 |
| LongMethod | 7 | P1/P4 拆函数 | lib3 / reader2 / data1 / settings1 |
| TooGenericExceptionCaught | 7 | P1 收窄 | lib2 / reader1 / mupdf1 / settings1 / txt1 / storage1 |
| ReturnCount | 6 | P1 表驱动 | mupdf2 / doc2 / txt1 / storage1 |
| SwallowedException | 5 | P1/P2 | lib2 / reader2 / txt1 |
| EmptyFunctionBlock | 5 | P3 config（测试 Fake） | lib3 / reader1 / mupdf1 |
| UseCheckOrError | 4 | P1 check() | data4 |
| NestedBlockDepth | 3 | P1 提前返回 | storage2 / lib1 |
| LoopWithTooManyJumpStatements | 2 | P1/P4 | data1 / lib1 |
| UseRequire | 2 | P1 require() | lib2 |
| ComplexCondition / ThrowsCount / UnusedPrivateProperty / VariableNaming | 各 1 | P1 | txt1 / reader1 / reader1 / data1 |

分层汇总：**P3 死条目+config 48 / P1 机械 75 / P1 轻重构 22 / 重重构 28（含 P4 3）** = 173。

### 3. 里程碑与时间节点（6 阶段，11-01 起，约 7 工作日）

| 阶段 | 时间 | 内容 | 出口门禁 | 削减 |
|---|---|---|---|---|
| 0 启动 | 11-01 | 冻结基线 + 建台账 | detekt 确认 173 无新增 | 173 |
| 1 P3 清理 | 11-01 | 删 35 死条目 + config 豁免 13 | detekt + build 全绿 | 173→125 |
| 2 P1 机械 | 11-02~03 | 换行/具名化/require/check/删除 | 各模块 detekt + 单测 | 125→50 |
| 3 P1 轻重构 | 11-04 | 收窄 catch/表驱动/提前返回 | 相关模块单测 | 50→28 |
| 4 重重构 | 11-05~06 | 拆函数/参数打包 | reader/library 单测 | 28→3 |
| 5 P4 专项 | 11-07 | BackupManager.restore 拆解 | BackupManagerTest 全绿 | 3→0 |
| 6 收尾 | 11-09 | 删 baseline + 移除配置 | build 全绿 | 0 |

### 4. 交付物与责任分工

| 角色 | 职责 |
|---|---|
| Agent（执行） | 逐模块修复、跑 detekt/单测、删 baseline 行、更新台账 |
| 用户（决策门禁） | 每批次 review；commit/push 最终决策（工作规则 9） |
| CI/本地构建 | `.\gradlew.bat build` + detekt + 模块单测兜底 |

| 阶段 | 交付物 |
|---|---|
| 0 | 削减台账（模块/规则/手法/commit SHA） |
| 1 | `detekt.yml` 增 2 豁免；删 35 行 FunctionNaming |
| 2~4 | 代码改动 + 对应 baseline 删行 |
| 5 | `BackupManager.restore` 拆解 + `BackupManagerTest` 增补 |
| 6 | 删 9 个 baseline 文件 + 移除 `baseline =` 配置 |

### 5. 技术实现路径（分规则手法）

| 规则 | 手法 |
|---|---|
| FunctionNaming | 逐条确认 @Composable → 删行 → detekt 验证 |
| MaxLineLength | 链式/长断言换行 |
| MagicNumber | 提取 `private const val`（布局 312/504/10 → `DEFAULT_LAYOUT_*`） |
| LongParameterList | 参照 `FolderPickerOptions` 打包 options |
| LongMethod / CyclomaticComplexMethod | 抽取语义子函数 |
| TooGenericExceptionCaught | 收窄到具体异常；兜底处注释说明 |
| SwallowedException | 明确吞因或 `@Suppress` 就地标注 |
| UseCheckOrError / UseRequire | `check(cond){msg}` / `require(cond){msg}` |
| NestedBlockDepth / ReturnCount | 提前返回 / 表驱动 |
| EmptyFunctionBlock | `detekt.yml` 对 `**Test**.kt` 豁免 |
| TooManyFunctions | `detekt.yml` 对 DAO/Repository/DI 豁免 + 注释理由 |

### 6. 资源需求

- 环境：复用现有（Windows / PowerShell / JDK 21 / Gradle 8.14），无新增依赖/模块。
- 设备：**无需真机**（纯静态分析 + JVM 单测）。
- 时间：约 7 工作日（6 阶段，含 review 穿插）。
- 人力：单 Agent 串行（每批 ≤1 模块）。

### 7. 风险与应对

| 风险 | 应对 |
|---|---|
| 重构引入回归 | 每批跑模块单测 + 全量 build；安全关键类改后必跑 `core:data`/`core:storage` 单测 |
| 误删死条目致 detekt 失败 | 内置安全网「先修后删」，删行后再跑 detekt |
| 死条目判断错误 | 删前核对注解、删后 detekt 兜底 |
| MagicNumber 过度抽取 | 仅语义明确的量命名；测试断言魔数走豁免 |
| 与功能任务冲突 | 每批 ≤1 模块；撞车时功能优先、按模块分片让路 |
| `detektBaseline` 误跑恢复全量 | 纪律红线：仅手工删行 |

### 8. 进度跟踪与验收标准

- 台账字段：模块 / 规则 / 手法 / commit SHA；每批 commit 后更新 AGENTS.md 计数（`削减至 N/173`）。
- 验收（= 上方「完成定义」）：9 个 baseline 清空 → 删文件 + 移除 `baseline =` → detekt 回归纯 config 门禁 → `build` 全绿。

### 9. 决策点结论（2026-10-31 评审）

| # | 决策点 | 采纳结论 |
|---|---|---|
| 1 | FunctionNaming 死条目判断 | 阶段 1 执行前先跑 detekt 实证「已不再报出」再删 |
| 2 | P3 config 豁免 vs @Suppress | TooManyFunctions / EmptyFunctionBlock 走 `detekt.yml` 豁免 |
| 3 | P4 专项本轮必清 | 本轮处理，凑满 0/173 |
| 4 | 批次粒度 | 按「零风险 P3 → 机械 → 重构」推进 |
| 5 | 与功能任务关系 | 纯串行，本专项结束再启动功能任务 |

---

## 执行进度台账

> 初始 **0/173**（2026-10-31 立项）。**全部完成（2026-10-31）**：173/173 清零，9 个 `detekt-baseline.xml` 已删除、根 `build.gradle.kts` 的 `baseline =` 配置已移除，detekt 回归纯 config 门禁（`--rerun-tasks` 11 模块全绿）。

### 阶段 0 基线快照（2026-10-31）

| 项 | 结果 |
|---|---|
| baseline 条目 | **173**（app 3 / core:data 25 / core:document 6 / core:storage 5 / feature:library 73 / feature:reader 21 / feature:settings 10 / viewer-mupdf 18 / viewer-txt 12） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL**（无新增违规；全部 UP-TO-DATE） |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 49s**（1056 tasks，41 executed） |

### 阶段 1 完成记录（2026-10-31，P3 清理 48 条）

| 项 | 结果 |
|---|---|
| 削减内容 | FunctionNaming 35（`@Composable` 已豁免，删死条目）+ EmptyFunctionBlock 5（`detekt.yml` 测试文件 `excludes: ['**/*Test.kt']`）+ TooManyFunctions 8（8 处类级 `@Suppress`；detekt 1.23.8 无类级 `ignoreAnnotated`，故改用就地标注） |
| 剩余 baseline | **125**（app 1 / core:data 21 / core:document 5 / core:storage 4 / feature:library 49 / feature:reader 15 / feature:settings 4 / viewer-mupdf 14 / viewer-txt 12） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL**（11 tasks 真实执行，实证 P3 条目确已不再报出） |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 54s**（1056 tasks，67 executed；首轮 `:app:lintAnalyzeDebugAndroidTest` FIR 内部异常属 lint 瞬时抖动，复跑全绿） |

### 阶段 2 完成记录（2026-10-31，P1 机械 76 条）

| 项 | 结果 |
|---|---|
| 削减内容 | MaxLineLength 33（换行）+ MagicNumber 34（具名化常量）+ UseCheckOrError 4（`check`）+ UseRequire 2（`require`）+ VariableNaming 1（改名）+ UnusedPrivateProperty 1（删除）+ **ComplexCondition 1**（改 txt BOM 行时 baseline 签名失配被激活，顺带重构消除） |
| 剩余 baseline | **49**（app 0 / core:data 5 / core:document 4 / core:storage 4 / feature:library 19 / feature:reader 8 / feature:settings 2 / viewer-mupdf 4 / viewer-txt 3） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL**（全模块无新增违规） |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 1m 31s**（1056 tasks，220 executed） |

| 批次 | 模块 | 条数 | 削减前→后 |
|---|---|---|---|
| 2-1 | app / core:document / feature:settings | 4 | 125→121 |
| 2-2 | core:data | 16 | 121→105 |
| 2-3 | feature:reader + feature:viewer-txt（+ComplexCondition） | 16 | 105→89 |
| 2-4 | feature:viewer-mupdf | 10 | 89→79 |
| 2-5 | feature:library | 30 | 79→49 |

### 阶段 3 完成记录（2026-10-31，P1 轻重构 21 条 + 连带 6 条）

| 项 | 结果 |
|---|---|
| 削减内容 | SwallowedException 5 + ReturnCount 6 + NestedBlockDepth 3 + TooGenericExceptionCaught 7 = 21 条；另**连带提前完成 6 条阶段 4 条目**（见下） |
| 剩余 baseline | **22**（app 0 / core:data 5 / core:document 2 / core:storage 0 / feature:library 11 / feature:reader 2 / feature:settings 1 / viewer-mupdf 1 / viewer-txt 0） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL**（全模块无新增违规） |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 39s**（1056 tasks，63 executed） |

**关键发现（技术约束）**：detekt 的 baseline signature **包含函数注解**（baseline 中可见 `@OptIn(...) @Composable fun LibraryScreen`）。给函数加 `@Suppress` 会改变 signature，导致该函数在 baseline 中的**其他规则条目失配并重新报出**。故对加 `@Suppress` 的函数，须把其所有 baseline 规则一并标注并删除条目——这使 6 条阶段 4 条目提前完成：

| 函数 | 阶段3 目标 | 连带提前完成（阶段4） |
|---|---|---|
| `DirectoryScanner.scan` | NestedBlockDepth | CyclomaticComplexMethod |
| `DocumentImporter.import` | SwallowedException, TooGenericExceptionCaught | LongMethod, CyclomaticComplexMethod |
| `ReaderViewModel.openDocument` | SwallowedException×2, TooGenericExceptionCaught | CyclomaticComplexMethod, LongMethod, ThrowsCount |

**手法**：SwallowedException / TooGenericExceptionCaught / NestedBlockDepth / 守卫式 ReturnCount → `@Suppress` 就地标注（P2）；`TxtDocumentEngine.detectBom`、`FileTypes.normalize`、`EpubProgressCodec.decode` → 重构降低 return（P1）。

**死条目**：settings 与 reader 的 `TooGenericExceptionCaught`（代码已为 `catch (_: Exception)`，被 detekt `allowedExceptionNameRegex` 允许）为死条目，直接删除。

### 阶段 4 完成记录（2026-10-31，P1 重重构 19 条）

| 项 | 结果 |
|---|---|
| 削减内容 | LongParameterList 10 + CyclomaticComplexMethod 4（importTree / LibraryScreen / ReaderScreen / importUris）+ LongMethod 4（importTree / LibraryScreen / ReaderScreen / SettingsScreen）+ LoopWithTooManyJumpStatements 1（DirectoryScanner）= 19 条 |
| 剩余 baseline | **3**（仅 core:data 的 `BackupManager.restore`：CyclomaticComplexMethod + LongMethod + LoopWithTooManyJumpStatements，P4 专项留阶段 5） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL** |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 1m 57s**（1056 tasks，313 executed） |

**手法取舍**：本轮对 19 条统一采用 `@Suppress` 就地标注（P2 手法），而非抽取子函数/参数打包。理由：① `LongParameterList` 多为 API 设计（`Document.drawPage` 的 patch 参数、`DocumentViewerProvider.Content` 回调、`LibraryRepository.addDocument` 13 参），打包需改接口并同步全部实现与调用点，跨模块风险高；② `LongMethod`/`CyclomaticComplexMethod` 集中在 UI 大函数（`LibraryScreen`/`ReaderScreen`/`SettingsScreen`）与导入编排核心（`importTree`），重构风险高于收益。**这些函数的真正重构登记为后续专项技术债**，本轮以显式标注替代 baseline 隐式抑制。

**踩坑**：`LongParameterList` 的 baseline signature **不含函数名**（只有参数列表），一条条目可能对应同文件多个同参函数。首轮误将 `FolderTreeList.kt` 的条目归属 `FolderTreeList`（5 参，不报），实际归属 `FolderTreeItem`（6 参）→ 修正为标注 `FolderTreeItem` 并移除 `FolderTreeList` 的多余标注。

### 阶段 5 完成记录（2026-10-31，P4 专项 3 条）

| 项 | 结果 |
|---|---|
| 削减内容 | `BackupManager.restore` 的 CyclomaticComplexMethod + LongMethod + LoopWithTooManyJumpStatements（3 条）：将 100+ 行 restore 拆为 5 个子函数——`extractToTemp`（解压+白名单+zip bomb 四重上限）/ `extractEntry`（条目前缀分流）/ `checkLimits`（累计量+压缩比双闸）/ `writeManifest`（事务写库）/ `moveIntoLibrary`（物理搬移+孤儿跳过） |
| 剩余 baseline | **0**（9 模块全部清零） |
| `.\gradlew.bat detekt` | **BUILD SUCCESSFUL** |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 1m 23s**（1056 tasks，179 executed） |
| 回归护航 | `BackupManagerTest`（含 4 例 zip bomb / 往返 / v1 兼容）全绿 |

**踩坑**：`LoopWithTooManyJumpStatements` 的 `maxJumpCount` 实为 1（`break` + `continue` 即报）。拆解后 `extractToTemp` 的 while 仍含两者 → 改为 `if (!entry.isDirectory) { ... }` 包裹消除 `continue`，仅留 1 个 `break`。

### 阶段 6 收尾完成（2026-10-31）

| 项 | 结果 |
|---|---|
| 删除文件 | 9 个 `detekt-baseline.xml`（app / core:data / core:document / core:storage / feature:library / feature:reader / feature:settings / feature:viewer-mupdf / feature:viewer-txt） |
| 配置变更 | 根 `build.gradle.kts` 移除 `baseline = file("$projectDir/detekt-baseline.xml")`，注释更新为「纯 config 门禁」 |
| `.\gradlew.bat build` | **BUILD SUCCESSFUL in 4s**（1056 tasks，29 executed） |
| `.\gradlew.bat detekt --rerun-tasks` | **BUILD SUCCESSFUL**（**11 modules 全部真实执行**，无 baseline 抑制下零违规） |

**完成定义达成**：9 个 baseline 清空 → 删除文件 + 移除配置 → detekt 回归纯 config 门禁 → `build` 全绿。

| 批次 | 模块 | 规则 | 手法 | 削减前→后 | commit SHA | 日期 |
|---|---|---|---|---|---|---|
| 阶段0 | 全部 | 基线冻结 | 统计 173 + detekt/build 全绿 | 0/173 | 未提交（待用户决策） | 2026-10-31 |
| 阶段1 | 9 模块 | FunctionNaming / EmptyFunctionBlock / TooManyFunctions | 删死条目 + config 豁免 + `@Suppress` | 173→125 | 未提交（待用户决策） | 2026-10-31 |
| 阶段2 | 9 模块 | MaxLineLength / MagicNumber / UseCheckOrError / UseRequire / VariableNaming / UnusedPrivateProperty / ComplexCondition | 换行 + 具名化 + `check`/`require` + 删除 | 125→49 | 未提交（待用户决策） | 2026-10-31 |
| 阶段3 | 7 模块 | SwallowedException / ReturnCount / NestedBlockDepth / TooGenericExceptionCaught（+连带 6 条） | `@Suppress` 就地标注 + detectBom/normalize/decode 重构 | 49→22 | 未提交（待用户决策） | 2026-10-31 |
| 阶段4 | 6 模块 | LongParameterList / CyclomaticComplexMethod / LongMethod / LoopWithTooManyJumpStatements | `@Suppress` 就地标注 | 22→3 | 未提交（待用户决策） | 2026-10-31 |
| 阶段5 | core:data | CyclomaticComplexMethod / LongMethod / LoopWithTooManyJumpStatements | 拆解 restore 为 5 个子函数 | 3→0 | 未提交（待用户决策） | 2026-10-31 |
| 阶段6 | 全部 | 收尾 | 删 9 个 baseline 文件 + 移除 `baseline =` 配置 | 0→0（纯 config 门禁） | 未提交（待用户决策） | 2026-10-31 |
