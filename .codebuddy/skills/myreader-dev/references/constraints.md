# MyReader 硬约束速查

> 与根目录 `AGENTS.md` 同步；冲突以 `AGENTS.md` 为准。本文件仅作速查，不替代事实源。

## 模块边界（不可违反）
- MuPDF 具体类 **仅** 允许在 `feature:viewer-mupdf` 与 `third-party:mupdf`。
- `feature:reader` / `feature:library` 只依赖 `core:document` 接口（`Document` /
  `DocumentEngineRouter` / `DocumentViewerProvider` / `OutlineEntry` 等）。
- 新增文档格式 = 新建 `feature:viewer-xxx` + `@FileTypeKey`，**不改** reader 与 schema。

## Room 迁移纪律
- `core/data/schemas/.../*.json` 随仓库提交。
- 禁用 `fallbackToDestructiveMigration`（阅读进度是核心数据，丢失不可接受）。
- 改 schema（实体/列/索引）必须写 `Migration`，且补跑 `:core:data:test`。

## AGPL 合规
- APP 整体开源（MuPDF AGPL 要求）。
- README + About 页注明 MuPDF 来源与 AGPL 全文链接。
- 不引入 AGPL 不兼容的闭源组件。

## 完成定义（Definition of Done）
- 通过对应里程碑「验收」（docs/process.md）+ `.\gradlew.bat build` 无错。
- 声称完成必须附证据（命令输出摘要）。

## 文档同步
- schema / 模块结构 / 依赖版本 / 接口签名变更 → 同任务内更新对应 docs/ 分册。
- 状态、验收证据、卡点 → 只更新 `AGENTS.md`。

## 不过度工程
- 不引入文档未列出的依赖 / 模块。
- 确需偏离选型 → 先改 `docs/architecture.md`「技术选型」并写理由。

## 测试下沉
- 纯逻辑放 `core:*` 做 JVM 测试（Robolectric + inMemory Room）。
- 改 `core:data` / `core:storage` 必须补跑对应单测。

## 提交门禁（强制执行）
- 任何 commit / push 前必须交由用户决策（「先 review」或「直接提交推送」）。
- 未经用户明确同意，不执行 commit / push。

## 里程碑状态（摘要）
- M0–M7 已完成（数据层 / 导入书架 / 水平阅读 / 目录树管理 / 搜索排序最近 / 垂直滚动+设置）。
- M8 备份与导出（v1.1）未开始；M9 多格式支持（v1.2）未开始。
