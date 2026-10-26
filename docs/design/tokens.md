# 设计基线（tokens）

> 本文件是**唯一**的视觉基线事实源。代码实现约束：所有颜色、字号、间距必须来自 `MaterialTheme` 或本表，禁止在 Composable 内硬编码色值与魔数（detekt 已对 `@Composable` 魔数豁免，故靠本文档约束）。

## 1. 颜色

| 语义 | 取值 | 用途 |
|---|---|---|
| 主色 / 容器 | `MaterialTheme.colorScheme.primary` / `primaryContainer` | 分组标题、文件夹图标、主按钮 |
| 背景 / 表面 | `background` / `surface` | 页面底色、卡片底色 |
| 次级表面 | `surfaceVariant` | 缩略图缺图底、底部导入胶囊 |
| 主文本 | `onSurface` | 标题、正文 |
| 次要文本 | `onSurfaceVariant` | 面包屑根级、页码、元信息、空态说明（**替代 `outline`**，见 D8） |
| 分隔/边框 | `outlineVariant` | 分隔线 |
| 错误 | `error` / `errorContainer` | 内联错误、删除按钮文字、失败页文案 |
| 选中态 | `secondaryContainer` | 目录树/picker 选中行、当前项背景 |
| 角标底 | `Color.Black.copy(alpha = 0.55f)` | 缩略图上的格式/状态角标（底图不可控，需固定半透明黑） |
| 角标文字 | `Color.White` | 角标内文字/图标 |

规则：

1. **不使用 dynamicColor**（与「跟随系统 / 浅色 / 深色」三选一语义冲突），见 `MainActivity.kt`。
2. **不引入品牌色**：主题仅 `lightColorScheme()` / `darkColorScheme()` 默认色板。
3. 状态不得只靠颜色表达：加密/损坏必须同时有图标 + `contentDescription`。
4. 对比度：正文 ≥ 4.5:1，次要文本 ≥ 3:1；`outline` 仅用于纯装饰。

## 2. 字体（Material3 默认 typography）

| 场景 | 样式 | 位置 |
|---|---|---|
| 页面/对话框标题 | `titleLarge`（关于页）/ `TopAppBar` 默认标题样式 | `AboutScreen`、各 `TopAppBar` |
| 分组标题 | `labelMedium` + `primary` | 设置分区、关于分区 |
| 列表主文本 / 按钮 | `bodyLarge` | 单选行、picker 行、按钮 |
| 正文说明 | `bodyMedium` | 设置分组内容、对话框正文、大纲条目 |
| 卡片标题 | `bodySmall`（最多 2 行，`Ellipsis`） | 文件夹卡 / 文档卡 |
| 元信息 | `labelSmall` | 页码、`N 页`、格式角标、最近卡标题 |
| 大段说明 | `bodySmall` | 关于页 URL、汇总明细 |

规则：

1. 顶栏标题双行结构（文档名 + 页码）必须 `maxLines = 1` + `Ellipsis`，防字体放大后撑破 `TopAppBar`。
2. 不使用 `sp` 之外的单位描述字号；不重写 `TextStyle` 的 `fontSize`，只用语义样式。

## 3. 间距与布局

| 名称 | 值 | 用途 |
|---|---|---|
| `space-1` | 4dp | 图标与文字紧邻、大纲缩进基准 |
| `space-2` | 8dp | 卡片内边距、汇总行间距 |
| `space-3` | 12dp | 网格间距、单选行内水平间距 |
| `space-4` | 16dp | 页面横向边距、胶囊内边距 |
| `space-5` | 20dp | 分组标题上间距 |
| `space-6` | 24dp | 对话框内边距、失败页文案与按钮间距、大纲底部 |
| `space-8` | 32dp | 空态容器内边距、失败页容器内边距 |

其它定值：

- 目录树单级缩进 16dp；大纲单级缩进 20dp；详情树展开箭头槽位 48dp。
- 目录树弹窗限高 360dp（`TREE_MAX_HEIGHT`）。
- 空态图标 64dp；卡片内占位图标 36dp / 文件夹 48dp / 树内 20dp。
- 顶部导入胶囊圆角 24dp；状态角标 24dp 圆形；格式角标圆角 8dp。

## 4. 形状与层级

| 元素 | 值 |
|---|---|
| 卡片 | Material3 `Card` 默认形状，`elevation = 2.dp` |
| 对话框 | Material3 `AlertDialog` 默认 28dp 圆角 |
| 底部面板 | `ModalBottomSheet` 默认形状 |
| 状态角标 | `RoundedCornerShape(12.dp)`（24dp 见方 → 圆形） |
| 格式角标 | `RoundedCornerShape(8.dp)` |

不自定义阴影、渐变、投影。

## 5. 栅格与尺寸

| 元素 | 规则 |
|---|---|
| 书架网格 | `LazyVerticalGrid(GridCells.Adaptive(minSize = 108.dp))`，间距 12dp，内容边距 12dp |
| 文档卡 / 文件夹卡 | 缩略图区 `aspectRatio(1f)`；文本区 padding 8dp |
| 最近阅读卡 | 宽 100dp；缩略图区 `aspectRatio(0.75f)`（3:4） |
| 最近阅读条 | `LazyRow`，间距 12dp，标题与条间距 4dp |
| 阅读器渲染区 | 占满 `Scaffold` 内容区，不额外加内边距 |
| 设置/关于页 | 横向 16dp；内容纵向滚动 |

列数参考（只作验证预期，不写死）：360dp ≈ 3 列；600dp ≈ 5 列；840dp ≈ 7 列。

## 6. 触达与输入

- 所有可点元素最小 48×48dp；视觉图标 20~24dp 时用 padding 撑满（`IconButton` 默认 48dp，树展开箭头显式 48dp）。
- 数字输入用 `KeyboardType.Number`；搜索用 `ImeAction.Search`；所有单行输入 `singleLine = true`。
- 密码输入 `PasswordVisualTransformation()`。
- 输入框错误态用 `isError = true`，禁用态用 `enabled = false`，二者不可互换。

## 7. 动效

- 不自定义动画曲线与时长；依赖 Material3 默认（对话框淡入、BottomSheet 滑入）。
- 进度类动效仅用 `CircularProgressIndicator` / `LinearProgressIndicator`，不自建闪烁骨架屏。
- 已知的"防跳变"行为：最近阅读条首项变化时横向滚动复位到 0（`LaunchedEffect(recent.firstOrNull()?.id)`），属有意动效。

## 8. 禁止清单

1. 硬编码色值（如 `Color(0xFF...)`），除缩略图角标半透明黑/白。
2. 新增第三方 UI 库或图标库（需先改 `docs/architecture.md` 技术选型）。
3. 新建 `core:designsystem` 模块（D6）。
4. 使用 `outline` 承载需要阅读的文本（D8）。
5. 用 emoji 充当图标。
