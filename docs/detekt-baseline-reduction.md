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
