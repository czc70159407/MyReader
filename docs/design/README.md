# 设计分册索引（docs/design）

> **单一职责纪律**：本目录只写「设计意图（what / why）」；实现现状写 [`../ui-design.md`](../ui-design.md)；状态与验收证据只进根 [`AGENTS.md`](../../AGENTS.md)。同一内容禁止两处维护。

- 方案版本：v1.0（2026-10-26 评审通过，按方案 A 落盘为文档骨架）
- 适用产品：MyReader（Android 本地文档阅读器，Kotlin + Jetpack Compose Material3，单用户离线）
- 评审基线：本轮按实际代码逐页核对（`LibraryScreen` / `ReaderScreen` / `SettingsScreen` / `AboutScreen` / 各 Dialog）
- 标记约定：`[已实现]` 与当前代码一致；`[建议]` 待排期实现；`[缺口]` 已知不一致或风险

## 目录导航

| 文件 | 内容 | 何时读 |
|---|---|---|
| [tokens.md](tokens.md) | 色板、字体映射、间距、圆角、触达、栅格 | 写任何 UI 前 |
| [copy.md](copy.md) | 按钮 / 空态 / 错误 / 成功文案总表与标点规范 | 新增或修改任何用户可见文字 |
| [accessibility.md](accessibility.md) | 响应式矩阵 + 无障碍逐项清单与验收脚本 | 设计与验收阶段 |
| [components/_template.md](components/_template.md) | 条目模板（状态矩阵 / 文案 / 无障碍 / 代码落点 / 验收） | 新增设计条目时 |
| [components/top-bar.md](components/top-bar.md) | 书架顶栏、阅读器顶栏（含窄屏收纳与沉浸模式） | 改导航与动作区 |
| [components/cards.md](components/cards.md) | 文件夹卡 / 文档卡 / 最近阅读卡 / 角标 | 改书架卡片 |
| [components/dialogs.md](components/dialogs.md) | 8 类弹窗规格与通用规则 | 改/加弹窗 |
| [components/states.md](components/states.md) | 加载 / 空 / 错误全量矩阵 | 任一页面状态设计 |
| [screens/library.md](screens/library.md) | 书架页与搜索态 | 改书架 |
| [screens/reader.md](screens/reader.md) | 阅读器（状态机、顶栏、跳页、大纲、失败页） | 改阅读器 |
| [screens/settings.md](screens/settings.md) | 设置页（四分区 + 备份恢复） | 改设置 |
| [screens/about.md](screens/about.md) | 关于页（AGPL 合规展示） | 改合规文案 |
| [flows/import-single.md](flows/import-single.md) | 单文件（多选）导入 | 改导入编排 |
| [flows/import-directory.md](flows/import-directory.md) | 整目录批量导入（含取消） | 改批量导入 |
| [flows/move-and-delete.md](flows/move-and-delete.md) | 移动防环、删除后果 | 改目录操作 |
| [flows/backup-restore.md](flows/backup-restore.md) | 备份导出 / 恢复（含二次确认） | 改备份 |
| [flows/bookmark-m10.md](flows/bookmark-m10.md) | M10 章节级书签（设计预留） | 执行 M10 前 |

## 决策日志

| ID | 决策点 | 结论 | 落点 |
|---|---|---|---|
| D1 | 恢复备份是否二次确认 | **需要**。对话框说明「合并式恢复、同名记录以备份为准、其他文档不受影响」；取消则不读取流 | `screens/settings.md`、`flows/backup-restore.md` |
| D2 | 360dp 窄屏顶栏溢出 | **收纳**：顶栏保留 `[+ 导入][搜索][⋮][设置]`，「新建文件夹」「导入目录」移入 `⋮` 菜单顶部，与排序项之间加分隔线 | `components/top-bar.md` |
| D3 | 批量导入是否可取消 | **支持**。底部胶囊尾部加「取消」；取消后保留已导入项并弹部分汇总 | `flows/import-directory.md` |
| D4 | 阅读器沉浸模式 | **独立预研任务**（验证与渲染端点按/长按手势是否冲突），通过后再排期；不阻塞其他 P0 | `screens/reader.md` |
| D5 | 单文件多选失败反馈 | **改用汇总对话框**：抽统一 `ImportReport` 模型，与目录导入共用对话框 | `flows/import-single.md` |
| D6 | 组件库边界 | 维持「不设 `core:designsystem`」：仅 `MaterialTheme` + 模块内共享组件；抽模块需先改 `docs/architecture.md` 技术选型 | `tokens.md` |
| D7 | 重名判定 | 保持**区分大小写**（与文件系统解耦），在文档中写明 | `copy.md`、`flows/move-and-delete.md` |
| D8 | 空态/次要文本颜色 | `outline` → `onSurfaceVariant`（`outline` 与背景对比度不足 4.5:1） | `tokens.md`、`components/states.md` |

## 交付状态

| 任务 | 优先级 | 范围 | 状态 | 关联条目 |
|---|---|---|---|---|
| T1 | P0 | 恢复备份二次确认（D1） | 已验收（真机 2026-10-28） | `flows/backup-restore.md` |
| T2 | P0 | 书架顶栏窄屏收纳（D2） | 已验收（真机 2026-10-28） | `components/top-bar.md` |
| T3 | P0 | 批量导入取消（D3） | 已验收（真机 2026-10-28） | `flows/import-directory.md` |
| T4 | P0 | 单文件导入汇总对话框（D5） | 已验收（真机 2026-10-28） | `flows/import-single.md` |
| T5 | P1 | 无障碍修订（卡片语义 / 长按替代 / 焦点 / 进度环语义） | 设备语义验收通过（2026-10-29，6 项）；整卡朗读与「操作」菜单人工复核待做 | `accessibility.md` |
| T6 | P1 | 文案对齐与对比度（D8） | 待实现 | `copy.md` |
| T7 | P2 | 文件夹名长度与控制字符校验统一 | 待实现 | `components/dialogs.md` |
| T8 | P0（独立） | 沉浸模式手势预研（D4） | 预研完成（可行，待排期） | `screens/reader.md` |

> 状态只在本表与 `AGENTS.md`「当前状态」同步更新；实现完成后由验收证据驱动，不提前改。

## 阅读顺序建议

- **设计评审**：`README` → `screens/*` → `flows/*`（先看目标与场景，再看细节）
- **开发实现**：`components/*` → `screens/*` → `flows/*`（先定组件参数，再接页面与编排）
- **验收**：`components/states.md` + `accessibility.md`（逐行打勾，避免只测 happy path）

## 图与资产约定

- **Figma：未接入**。当前以 Markdown 线框 + Compose 实现作为唯一可运行原型；如后续接入，只在此登记「链接 + 最后同步日期 + 对应条目」，不嵌入二进制。
- 可入库：SVG 线框图（单个 < 100KB，可 diff）。
- 不入库：Figma / Sketch / PSD / 录屏 / 验收截图（后者已由 `.gitignore` 排除 `tools/acceptance/verify/`）。

## 命名约定

- 页面文件用路由名或模块名（`library` / `reader` / `settings` / `about`）。
- 组件文件用 Compose 组件概念名（`top-bar` / `cards` / `dialogs`），不照抄类名（避免改名后文档失效）。
- 状态名统一：`加载` / `空` / `错误` / `成功` / `禁用` / `进行中`。
- 文案键名统一 `err_*` / `act_*` / `empty_*` / `ok_*`，与 `copy.md` 一一对应。

## 变更流程

1. 先改本目录（设计意图）→ 2. 再改代码与测试 → 3. 验收通过后更新 `AGENTS.md` 状态；
2. 一个提交只动一个条目（或一组同源条目），提交信息写明条目名（如 `design(reader): 顶栏动作顺序`）；
3. 废弃设计保留「已废弃 + 替代条目」标注，不直接删除（对齐 `../ui-design.md` 对 `folderPicker` 独立路由的废弃记录方式）。
