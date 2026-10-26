# 页面 · 关于（AGPL 合规）

- 状态：评审通过
- 优先级：P1（链接可点击）
- 路由：`about`
- 代码落点：`feature/settings/AboutScreen.kt`
- 合规依据：工作规则 2（APP 整体开源；README + 应用内 About 页注明 MuPDF 来源与 AGPL 全文链接）

## 1. 目标

让用户在**应用内**可查：本项目以 AGPL-3.0 开源、PDF 引擎为 MuPDF 且同样 AGPL、可访问许可全文与源码仓库。文案与 `README.md`「许可证与开源声明」章节保持一致（两处需同步修改）。

## 2. 布局

```
┌────────────────────────────────┐
│ [←] 关于                        │
├────────────────────────────────┤
│ MyReader            titleLarge  │
│ 安卓本地文档阅读器     bodyMedium  │
│                                │
│ 开源许可            labelMedium/primary │
│ 本项目整体以 AGPL-3.0 开源…  bodyMedium │
│ AGPL-3.0 全文：{URL}   bodySmall   │
│                                │
│ PDF 渲染引擎                    │
│ PDF 渲染引擎为 Artifex…  bodyMedium │
│ MuPDF 主页：{URL}      bodySmall   │
│ 源码仓库：{URL}        bodySmall   │
└────────────────────────────────┘
```

- 整页 `verticalScroll`，横向/纵向 16dp。
- 三个 URL 以 `bodySmall` 展示。

## 3. 状态矩阵

| 状态 | 展示 |
|---|---|
| 正常 | 静态文案（无网络依赖、无加载态、无错误态） |
| 离线 | 完全一致（文案内展示 URL，不请求网络） |
| 字体 200% | 整页滚动，URL 可换行不裁切 |

本页无数据加载、无空态、无错误态（纯静态内容）。

## 4. 交互细则

1. **现状**：URL 为纯文本，不可点击、不可复制——用户需手抄，属可用性缺口。
2. `[建议 P1]` 修订：
   - URL 改为可点击（`LinkAnnotation.Url` 或 `ClickableText`），点击用系统浏览器打开；
   - 长按复制 + Snackbar「链接已复制」；
   - 若平台无法打开浏览器（无浏览器设备），不崩溃并提示「未找到可打开链接的应用」。
3. 返回：回到设置页（不重建设置状态）。

## 5. 文案

| 键 | 文案 |
|---|---|
| `about_title` | 关于 |
| `about_app_desc` | 安卓本地文档阅读器 |
| `about_license_section` | 开源许可 |
| `about_license_body` | 本项目整体以 AGPL-3.0 开源（含服务端条款的网络传播义务）。 |
| `about_mupdf_section` | PDF 渲染引擎 |
| `about_mupdf_body` | PDF 渲染引擎为 Artifex Software 的 MuPDF，同样遵循 AGPL-3.0，以 git submodule 形式集成自其官方仓库。 |

固定链接（与代码常量一致）：AGPL 全文 `https://www.gnu.org/licenses/agpl-3.0.html`；MuPDF 主页 `https://mupdf.com`；源码仓库 `https://gitlab.com/artifexsoftware/mupdf-android-viewer`。

> 注意：源码仓库链接指向上游 Artifex 仓库（现状），而本项目实际 submodule 指向自有 fork。`[建议 P2]` 评审确认是否补充本项目仓库地址，避免"源码仓库"指向引起误解。

## 6. 表单校验

无输入表单。

## 7. 无障碍

| 项 | 要求 | 现状 |
|---|---|---|
| 链接语义 | 可点击 URL 需作为链接角色暴露（`Role.Button`/`Link`） | `[建议 P1]` |
| 朗读 | 朗读「AGPL-3.0 全文，链接」而非裸 URL | `[建议 P1]` |
| 触达 | 链接行高 ≥ 48dp（可点击时） | `[建议 P1]` |
| 文本对比度 | 正文 `onSurface`；URL 若是链接用 `primary`（≥ 4.5:1） | `[建议]` |
| 长文本 | 支持字体缩放与换行 | `[已实现]` |

## 8. 响应式

| 场景 | 变化 |
|---|---|
| 竖屏 | 单列 |
| 横屏/平板 | `[建议 P2]` 限宽 600dp |
| 字体 200% | 滚动；URL 换行 |

## 9. 验收场景

1. 设置 → 关于：标题、两段说明与三个链接全部可见；
2. 点击 AGPL 链接（P1 后）：系统浏览器打开许可页；
3. 长按链接（P1 后）：复制并提示；
4. 字体 200%：无裁切，可滚动到底部；
5. TalkBack：标题与两段正文可读，链接角色明确（P1 后）；
6. 文案与 `README.md` 合规章节逐字一致（评审时对照）。
