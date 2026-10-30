# M10 执行计划（v1.3：书签 + 首个 Room Migration）

> 分册纪律：本册是 M10 唯一执行计划；状态与验收证据只进 AGENTS.md，设计规范随落地同步对应分册（data-layer.md / ui-design.md）。

## 目标与范围

1. **书签（章节级）**：阅读器内对当前页添加/移除书签；书签列表跳转
2. **首个 Room Migration（v1→v2）**：作为本里程碑强制门禁——实战验证迁移纪律（工作规则 3：禁 `fallbackToDestructiveMigration`、schema JSON 随仓库提交）与 `room-testing` 基建
3. **备份兼容**：`BackupCodec.schemaVersion` 1→2，v1 旧备份可导入（书签为空，不崩）

**不做**（本里程碑外）：高亮/笔记（M11 候选）、云同步、书签导出分享。

## S0 预研（门禁）

- **锚点设计**（复用既有思路，S0 定稿）：页码式文档（pdf/cbz/xps/txt）存 `page + scrollOffsetY`；流式文档（epub）存 bookmark 十进制串（同 `EpubProgressCodec.loc` 思路，经 `MuPDFCore.pageToBookmark` 生成、跨 relayout 稳定；失效回落 page——与进度锚点同一套防御）
- **表结构草案**：

```sql
CREATE TABLE IF NOT EXISTS bookmarks (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  documentId INTEGER NOT NULL,
  page INTEGER NOT NULL,
  scrollOffsetY INTEGER NOT NULL DEFAULT 0,
  anchor TEXT,
  label TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
)
CREATE INDEX IF NOT EXISTS index_bookmarks_documentId ON bookmarks(documentId)
```

- **决策点（S0 拍板，2026-10-30）**：
  1. **书签上限**：每文档 **≤100**（`BookmarkDao.countByDocument` 预检，超限拒绝并提示「书签已达上限（100 个）」，不静默丢弃）；
  2. **label 规则**：页码式「第 N 页」、流式「第 N 屏」；label 为**创建时快照**，重排后不回溯改写（列表排序用 `page`，跳转用 `anchor`）；
  3. **anchor 规则**：页码式 `page + scrollOffsetY`（anchor null），流式 `anchor = bookmark 十进制串`、`page = 创建时解析页码`（失效回落）；
  4. **SQL 口径**：手写 `MIGRATION_1_2` 必须逐字对齐 `2.json` 的 `createSql`——Kotlin 字段默认值不生成 SQL `DEFAULT`（草案中的 `scrollOffsetY ... DEFAULT 0` 已按实际 schema 去掉）。

## M10-A schema v2 + Migration（强制门禁）

> 状态：**已完成（2026-10-30）**——`BookmarkEntity/BookmarkDao`、`MIGRATION_1_2`、`2.json`、DI 注册、`Migration1To2Test`；真机 2/2、build 全绿。证据见根 `AGENTS.md`。

- `MyReaderDatabase` version=2，注册 `BookmarkEntity` / `BookmarkDao`
- 手写 `MIGRATION_1_2`（仅 CREATE TABLE + CREATE INDEX；documents 表零变更）
- `2.json` schema 导出随仓库提交（`room.schemaDirectory` 已配置）
- **MigrationTest 先行**（`core/data` androidTest，补 `libs.room.testing` 依赖）：`MigrationTestHelper` 以 `1.json` 建库 → 插入 documents 种子 → `runMigrationsAndValidate(2)` → 断言书签表结构 + 种子数据完整保留
- **验收**：MigrationTest 通过 + `.\gradlew.bat build` 全绿（352+ 用例不回归）

## M10-B DAO / Repository / 备份兼容

> 状态：**已完成（2026-10-30）**——`Bookmark` 模型与映射、仓储书签 CRUD（双轨去重 + 上限 100）、备份 `SCHEMA_VERSION=2` 与 v1 兼容、`BackupManager` 事务写书签；测试 +10、build 全绿。证据见根 `AGENTS.md`。

- `BookmarkDao`：`insert` / `delete` / `observeByDocument(documentId)`（Robolectric inMemory 单测，含 CASCADE 级联删验证）
- `LibraryRepository` 扩展书签 CRUD（并入现有仓储，不建独立 Repository——维持「feature 依赖单一仓储接口」惯例）
- `BackupCodec`：`schemaVersion=2` + bookmarks 数组 DTO；**恢复接受 v1**（书签空）+ 拒绝超版本；导出含书签
- `BackupManager`：恢复事务扩展书签 upsert（同事务原子性）
- 测试：`BackupCodecTest` / `BackupManagerTest` 扩展（v1 兼容导入 / v2 全字段往返 / 恢复幂等）
- **验收**：全部单测绿 + build 绿

## M10-C 阅读器 UI

> 状态：**已完成（2026-10-30）**——顶栏书签入口（实心/描边）+ `BookmarkSheet`（加载/空/失败重试/列表）+ 添加/跳转/删除编排 + Snackbar；VM 测试 5 例、Sheet Compose UI 测试 5 例。证据见根 `AGENTS.md`。

- 顶栏书签入口：当前页添加/移除（图标状态区分已收藏）
- 书签列表 Sheet：跳转 + 删除（复用 `OutlineSheet` 的 ModalSheet 模式）
- 跳转对 epub 走锚点解析（bookmark→page，复用 `EpubLocationResolver` 往返校验，失效回落 page）
- 测试：Sheet Compose UI 测试（列表渲染/删除）+ VM 书签编排测试
- **验收**：build 全绿 + UI/VM 测试绿

## M10-D 真机验收（一加 PKC110）

1. pdf：添加书签 → 退出 → 重进 → 列表跳转精确到页+偏移
2. epub：添加书签 → relayout（旋转）→ 跳转锚定正确
3. 备份往返：v2 导出 → 卸载重装 → 恢复 → 书签逐项一致（DB 级比对）
4. v1 旧备份导入：书签空、不崩
5. R8 release 冒烟搭车（书签链路混淆后正常）

## 风险与对策

| 风险 | 对策 |
|---|---|
| 首个 Migration 无实战经验 | MigrationTest 门禁先行 + 真机「升级安装」（不卸载，覆盖装）验证数据保留 |
| 备份双版本兼容 | BackupCodec 版本门禁既有；v1 导入路径单独单测 |
| epub 锚点失效（重排后） | 复用 bookmark→page→bookmark 往返校验，失效回落 page（与进度锚点同防御） |
| 书签表无上限滥用 | 每文档上限 + 拒绝提示（S0 拍板） |

## 排期

| 阶段 | 内容 | 预估 |
|---|---|---|
| S0 | 锚点与表结构定稿 | 0.5 会话 |
| M10-A | schema v2 + Migration + MigrationTest | 1 会话 |
| M10-B | DAO/Repository/备份兼容 | 1 会话 |
| M10-C | 阅读器 UI + 测试 | 1 会话 |
| M10-D | 真机验收 | 0.5 会话 |

> 与 detekt baseline 削减专项并行互不干扰（detekt 存量修复不触碰 M10 变更文件即可）。
