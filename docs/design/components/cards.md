# 组件 · 书架卡片

- 状态：评审通过
- 优先级：P1（无障碍语义 T5）
- 代码落点：`feature/library/LibraryCards.kt`、`feature/library/LibraryThumbnails.kt`

## 1. 文件夹卡（`FolderCard`）

```
┌──────────────┐
│              │
│      📁      │  1:1 区域，图标 48dp，primary 色，居中
│              │
├──────────────┤
│ 文件夹名称      │  bodySmall，最多 2 行，Ellipsis，padding 8dp
└──────────────┘
```

- 整卡 `combinedClickable`：单击进入子目录，长按弹菜单。
- 长按菜单（`DropdownMenu`）：重命名 / 移动 / 删除 / 新建子目录。
- `elevation = 2.dp`。

## 2. 文档卡（`DocumentCard`）

```
┌──────────────┐
│ EPUB      🔒 │  左上格式角标（非 PDF）/ 右上状态角标
│   [缩略图]     │  1:1，ContentScale.Crop
│              │
├──────────────┤
│ 文档标题        │  bodySmall，2 行省略；损坏时用 onSurfaceVariant 弱化
│ 12 页 / EPUB   │  labelSmall，onSurfaceVariant 色
└──────────────┘
```

规则：

1. **格式角标**：仅非 PDF 显示（EPUB / CBZ / XPS / TXT），圆角 8dp，半透明黑底白字。
2. **状态角标**：`needsPassword` → 锁图标；`pageCount == -2` → 警告图标；二者互斥优先加密。24dp 圆形半透明黑底白图标，`contentDescription` 分别为「需要密码」「已损坏」。
3. **元信息文案**：`pageCount > 0` → `{n} 页`；否则显示格式标签（流式/加密未知页数）。
4. **缺图占位**：`surfaceVariant` 底 + 按格式取图标（EPUB=Book / CBZ=Image / TXT=Article / 其它=Description），图标 36dp，`onSurfaceVariant` 色（T6/D8）。
5. **解码中**：仅 `surfaceVariant` 底，不出骨架闪烁（避免网格滚动时频闪）。
6. 单击打开阅读器；长按菜单：重命名 / 移动 / 删除。

## 3. 最近阅读卡（`RecentCard`，仅根目录）

```
┌────────┐   宽 100dp
│  缩略图  │   aspectRatio(0.75f)
├────────┤
│ 标题     │   labelSmall，1 行省略
│ 第 5 页  │   或「继续阅读」（progressType == "cfi"）
└────────┘
```

- 区块标题「最近阅读」`titleSmall` + `onSurfaceVariant`，条间距 4dp，卡片间距 12dp。
- 仅根目录、有记录时渲染；搜索态不渲染。
- 首项变化时横向滚动复位到 0（有意行为，避免"原地换卡"错觉）。

## 4. 无障碍要求

| 项 | 要求 | 现状 |
|---|---|---|
| 整卡朗读 | 一次读出「文档名，12 页，需要密码」 | `[已实现 T5]` 点击容器 `clearAndSetSemantics` 单节点（描述 + `role` + `onClick`/`onLongClick` + `customActions`）；状态角标 CD 置 null 防重复播报（模板见 `copy.md` §9.1）。**勿改回 `Card` 上的 `mergeDescendants`**：平台会拆成两个节点导致重复播报，门禁 `LibraryA11yTreeTest` |
| 菜单替代 | 非长按路径触发重命名/移动/删除 | `[已实现 T5]` `customActions`（文件夹 4 项 / 文档 3 项，先复位菜单再执行）；不新增可见按钮以免改变布局 |
| 装饰静默 | 缩略图、占位图标 `contentDescription = null` | `[已实现]` |
| 状态语义 | 锁/警告图标有独立 CD | `[已实现]` |
| 触达 | 整卡可点，尺寸远大于 48dp | `[已实现]` |
| 弱化但可读 | 损坏文档标题用 `onSurfaceVariant` | `[已实现 T6]`（原 `outline` 对比度不足） |

## 5. 状态矩阵

| 状态 | 触发 | 展示 |
|---|---|---|
| 正常 | 有缩略图、可解码 | 缩略图 + 元信息 |
| 缺图 | `thumbnailRelPath == null` | 格式占位图标 |
| 解码失败 | 文件丢失/损坏图 | `surfaceVariant` 底（无图标） |
| 加密 | `needsPassword` | 右上锁角标 |
| 损坏 | `pageCount == -2` | 右上警告角标 + 标题弱化 |
| 流式 | `fileType == epub` | 元信息显示「EPUB」而非页数 |
| 搜索态 | 搜索结果 | 与浏览态同款卡片，key 前缀 `d_` |
| 最近条 | 根目录 + 有记录 | 独立 3:4 卡，1 行标题 |

## 6. 验收场景

1. 混合格式库（pdf + epub + cbz + txt）：各卡角标与占位图标正确，PDF 无格式角标；
2. 加密 PDF：锁角标常驻（首开成功后仍保留），TalkBack 读出「需要密码」；
3. 损坏文档：警告角标 + 标题弱化 + 打开后进入失败页（文案 `err_corrupt_open`）；
4. epub：元信息显示「EPUB」，最近条显示「继续阅读」；
5. 长按文档卡 → 菜单三项，操作后 Snackbar 文案与 `copy.md` 一致；
6. TalkBack 聚焦文档卡：一次朗读完整描述（T5 后）；
7. TalkBack「操作」菜单可触发重命名/移动/删除（T5 后）。
