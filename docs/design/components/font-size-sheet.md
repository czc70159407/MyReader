# 组件 · 显示设置面板（字号 / 缩放）

- 状态：评审通过（`[已实现 M15]`；排版联动控件为 `[规划 P2]`，见 §8）
- 优先级：P0
- 关联决策：D12（见 `design/README.md` 决策日志）；M15 决策见 `docs/m15-plan.md` §2
- 里程碑：M15
- 代码落点：`feature/reader/DisplaySheet.kt`（面板）、`ReaderScreen.kt`（Aa 入口）、`SettingsScreen.kt`（设置页同源分区）
- 相关测试：`ReaderViewModelTest`、`SettingsPrefsTest`

> 本文档是「显示设置面板」的**视觉与交互规格**（what/how）。功能需求、边界与验收的 what/why 见 [`../flows/font-size.md`](../flows/font-size.md)，本文只引用不复制。

---

## 1. 目标与入口

用户从阅读器顶栏「Aa」或设置页「显示」分区进入，选择一个阅读字号档位（EPUB）或缩放档位（PDF/CBZ/XPS），点选即全局生效。

- 入口：阅读器顶栏第 4 动作「Aa」（`FormatSize`，CD「显示设置」）；设置页「显示」分区（内嵌 `RadioRow`）。
- 关闭：下滑 / 点面板外；点选档位**不关闭**面板（可连续试档）。
- 分派：`doc.reflowable == true` 显「字号」5 档，否则显「缩放」5 档。

## 2. 布局（面板 `DisplaySheet`）

```
┌─────────────────────────────────────────────┐
│                  （拖拽把手）                  │
│                                              │
│   字号                              ← 分组标题 │
│   ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
│   │ 小 │ │较小 │ │标准 │ │较大 │ │ 大 │         │
│   └────┘ └────┘ └────┘ └────┘ └────┘         │
│        （FilterChip 单选行，可横向滚动）        │
│                                              │
│   [规划] 字体 / 行距 / 段距 / 页边距           │
│                                              │
└─────────────────────────────────────────────┘
```

- 容器：Material3 `ModalBottomSheet` 默认形状/圆角（`tokens.md` §4）。
- 分组标题：`labelMedium` + `primary`（`tokens.md` §2 分组标题样式）。
- 面板内边距：横向 `space-4`(16dp)；分组标题上间距 `space-5`(20dp)。
- 档位行：`LazyRow`（档位超出屏宽时横向滚动，300% 档真机已验证可达），行内间距 `space-2`(8dp)。

## 3. 调节控件（档位 Chip）

| 属性 | 规格 |
|---|---|
| 组件 | Material3 `FilterChip`，单选（同组互斥） |
| 未选中 | 默认 Chip 外观，标签 `bodyLarge`（`tokens.md` §2） |
| 选中 | `secondaryContainer` 背景 + `onSecondaryContainer` 文字（选中态语义色，`tokens.md` §1「选中态」）+ `LeadingIcon` 勾选 |
| 触达 | 每档 ≥ 48×48dp（`FilterChip` 默认高度 32dp，须以 `heightIn(min=48.dp)` 或等价方式撑满触达，`tokens.md` §6） |
| 档位内容 | 字号：`小 / 较小 / 标准 / 较大 / 大`（`ReaderFontScale.label`）；缩放：`适合宽度 / 125% / 150% / 200% / 300%`（`ReaderZoomScale.label`） |
| 选中回显 | 进入面板即高亮当前偏好档（从 DataStore 读），非每次都回默认 |

**字号档位与缩放档位的取值表**见 `core/model/ReaderFontScale.kt` / `ReaderZoomScale.kt`，本文不复制数值。

## 4. 状态矩阵（业务状态）

| 状态 | 触发条件 | 展示 | 可执行动作 |
|---|---|---|---|
| 加载 | DataStore 首读未完成 | 面板内档位回显「默认档」占位，不阻塞交互 | 点选即写偏好 |
| 空 | 不适用（偏好必有值，回落 `FALLBACK`） | — | — |
| 错误 | 偏好读失败 | 回显回落档（`标准` / `适合宽度`），不弹错误 | 正常点选 |
| 成功 | 点选档位 → 写入成功 | 选中态切换 + 正文重排即时可见 | 继续试档 / 关闭 |
| 禁用 | 无（档位恒可选） | — | — |
| 进行中 | 重排中（整书 relayout） | 正文重排，面板可继续点选；无全局遮挡 | 连续点选（去抖合并） |

> 交互六态（默认/悬停/按下/禁用/加载/错误）核对见 `interaction.md` §2：Chip 走「默认 + 按下涟漪 + 选中」，无 hover 信息承载、无 loading 替换（重排不阻塞面板）。

## 5. 交互细则

- **点选**：点某档 Chip → 选中态即时切换 → 触发整书重排（后台），面板不关闭。
- **连续点选**：快速点多个档位 → 以最终档为准，水平经 `scheduleRelayout` 去抖合并，垂直 `LaunchedEffect(doc, fontScale)` 只保留最新。
- **返回键 / 点外部 / 下滑**：关闭面板；已选档位**已持久化**，不随面板关闭回退。
- **与滚动/翻页手势冲突**：面板为 `ModalBottomSheet`，展开时拦截内容区触摸；面板关闭后内容区手势恢复（字号重排已完成）。
- **重复点击保护**：同档位重复点选幂等（`distinctUntilChanged` / `appliedFontEm != fontScale.em` 差分），不重复重排。
- **数据复位**：进入面板即读当前偏好回显，不重置用户已设档位。

## 6. 文案

> 正文只在 `copy.md` 维护，此处只列键名（`design/README.md` 命名约定）。

| 场景 | 文案键 | 备注 |
|---|---|---|
| Aa 入口 CD | `act_display_settings` | 「显示设置」 |
| 分组标题「字号」 | `label_font_scale` | 仅 EPUB 面板 |
| 分组标题「缩放」 | `label_zoom_scale` | 仅 PDF/CBZ/XPS 面板 |
| 档位标签 | 复用 `ReaderFontScale.label` / `ReaderZoomScale.label` | 枚举内聚，不另设 copy 键 |

> 注：若 `copy.md` 尚无 `act_display_settings` / `label_font_scale` / `label_zoom_scale`，需在实现时登记（当前档位标签已在枚举 `label` 内聚，无需重复）。

## 7. 无障碍

| 项 | 要求 | 现状 |
|---|---|---|
| 触达 | 每档 Chip ≥ 48×48dp，相邻间隔 ≥ 8dp | 见 §3（须 `heightIn` 保证） |
| 读屏语义 | 档位朗读「标准，已选中」而非仅「标准」；面板标题「显示设置」可达 | `FilterChip` + `role` 自带选中语义；分组标题 `labelMedium` |
| 选中不依赖颜色 | 选中态有「勾选图标 + 背景」，非仅颜色 | `secondaryContainer` + `LeadingIcon` |
| 焦点顺序 | 顶栏 Aa → 面板分组标题 → 档位行，与视觉一致 | Compose 默认顺序 |
| 对比度 | 正文 ≥ 4.5:1、次要 ≥ 3:1（`tokens.md` §1 规则 4） | 选中态 `onSecondaryContainer` 于 `secondaryContainer` 上达标 |
| 手势非唯一 | 调字号可点按完成，不依赖滑动手势 | 档位点按即完成 |

## 8. 响应式与多端适配

| 宽度/场景 | 变化 |
|---|---|
| 手机竖屏 360dp | 档位行 `LazyRow` 可横向滚动；分组标题 + 档位行单列 |
| 手机横屏 / 平板 | 面板限宽居中（`ModalBottomSheet` 默认），档位全可见时禁用滚动 |
| 系统字号 200% | Chip 标签不裁切（`bodyLarge` 自适应），面板可滚动 |
| 深色主题 | 面板 `surface` 背景、选中态 `secondaryContainer` 随 `colorScheme` 自动切换 |
| 分屏 | 面板在窗口内展开，档位行宽度随窗口，`LazyRow` 兜底 |

## 9. 设计规范说明（术语与 token 统一）

- **色**：一律取自 `MaterialTheme.colorScheme`（选中 `secondaryContainer`、分组标题 `primary`、面板 `surface`），**禁止硬编码色值**（`tokens.md` §8 禁止清单 1）。
- **间距**：只引用 `space-*`（§2 已列），禁止魔数。
- **形状**：`ModalBottomSheet` / `FilterChip` 均用 Material3 默认，不自定义圆角、阴影、渐变（`tokens.md` §4）。
- **动效**：依赖 Material3 默认（BottomSheet 滑入 / Chip 涟漪），不自定义曲线（`tokens.md` §7）。
- **图标**：`FormatSize`（Aa）来自 material-icons-extended，禁用 emoji（`tokens.md` §8 禁止清单 5）。
- **术语**：统一「阅读字号 / 缩放」「水平分页 / 垂直滚动」「全局偏好 / 会话态」，与 `flows/font-size.md`、`interaction.md` 一致；不出现「字体大小设置」「翻页模式」等混用表述。

## 10. 验收场景（可执行）

1. EPUB 打开「Aa」→ 显「字号」5 档，当前偏好档高亮；PDF 打开「Aa」→ 显「缩放」5 档；
2. 点「大」→ Chip 勾选态切换 + 正文即时重排、顶栏分母变化，面板不关闭；
3. 连续点「大」→「较小」→ 最终生效「较小」，无重复重排闪烁；
4. 关闭面板 → 重进 → 选中档位保持高亮；
5. 设置页「显示」分区改档 → 阅读器立即生效；反向亦然；
6. 深色主题下开面板 → 选中态对比度达标、无浅色残留；
7. 系统字号 200% → 档位标签不裁切、面板可滚动；
8. 360dp → 顶栏 5 动作含「Aa」不溢出；300% 档横向滚动可达。

## 11. 未决问题

- 排版联动控件（字体 / 行距 / 段距 / 页边距）为 `[规划 P2]`，落地前须先过 `m15-plan.md` §8 的 S0 预研（MuPDF reflow 参数暴露情况）与字体体积/AGPL 评估；本文档 §2 仅预留布局占位，不定义控件规格。
