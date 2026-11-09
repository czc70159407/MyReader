# 页面 · 关于（AGPL 合规）

- 状态：**已实现（2026-11-09，编译级验收通过；真机验收待做）**
- 优先级：P1（链接可点击）
- 路由：`about`
- 代码落点：`feature/settings/AboutScreen.kt`
- 合规依据：工作规则 2（APP 整体开源；README + 应用内 About 页注明 MuPDF 来源与 AGPL 全文链接）

## 1. 目标

让用户在**应用内**可查：本项目以 AGPL-3.0 开源、PDF 引擎为 MuPDF 且同样 AGPL、可访问许可全文、本项目源码、MuPDF 上游与实际集成的 viewer fork。链接地址与声明语义与 `README.md`「许可证与开源声明」章节保持一致（两处需同步修改；正文措辞因展示形式不同可不同，见 §9 场景 8）。

## 2. 布局

```
┌────────────────────────────────┐
│ [←] 关于                        │
├────────────────────────────────┤
│        ( 64dp 应用标识 )         │  圆形 primaryContainer 底 + 文档图标
│ MyReader            titleLarge  │
│ 安卓本地文档阅读器     bodyMedium  │
│ 版本 1.5.0             bodySmall  │
│                                │
│ 开源许可            labelMedium/primary │
│ 本项目整体以 AGPL-3.0 开源…  bodyMedium │
│ AGPL-3.0 全文：{URL}   bodySmall/primary │
│ 本项目源码：{URL}      bodySmall/primary │
│                                │
│ PDF 渲染引擎                    │
│ PDF 渲染引擎为 Artifex…  bodyMedium │
│ MuPDF 主页：{URL}      bodySmall/primary │
│ MuPDF 上游：{URL}      bodySmall/primary │
│ viewer 分支：{URL}      bodySmall/primary │
└────────────────────────────────┘
```

- 整页 `verticalScroll`，横向/纵向 16dp。
- 应用标识：`Icons.Filled.Description` 于 64dp 圆形 `primaryContainer` 底，tint `onPrimaryContainer`；**装饰性，`contentDescription = null`**。不引用品牌资产色（D11：品牌色仅用于 launcher / splash 资源层），避免跨模块依赖 `app` 的资源。
- 版本号运行时从 `PackageManager` 读取（`versionName`），不硬编码、不依赖 `BuildConfig`（`feature:settings` 无 `buildConfig`）。
- 五个 URL 以 `bodySmall` + `primary` + 下划线展示，整行 `heightIn(min = 48.dp)`。

## 3. 状态矩阵

| 状态 | 展示 |
|---|---|
| 正常 | 静态文案（无网络依赖、无加载态、无错误态） |
| 离线 | 完全一致（文案内展示 URL，不请求网络） |
| 字体 200% | 整页滚动，URL 可换行不裁切 |

本页无页面级加载态 / 空态 / 错误态；操作反馈（链接已复制、无浏览器兜底）走 Snackbar，不改变页面内容。

## 4. 交互细则

1. `[已实现]` URL 整行 `combinedClickable`：点击用 `context.startActivity(Intent(ACTION_VIEW, Uri.parse(url)))` 打开系统浏览器；长按复制到剪贴板 + Snackbar `ok_link_copied`。
2. `[已实现]` 无浏览器设备：精确捕获 `ActivityNotFoundException`，Snackbar `err_link_no_browser`，不崩溃。**不复用 `LocalUriHandler`**：Compose 1.7.4 的 `AndroidUriHandler` 会把它包装成 `IllegalArgumentException` 重新抛出，捕获不到会崩溃（反编译 `ui-android:1.7.4` 实证）。
3. 返回：回到设置页（不重建设置状态）。
4. 实现差异：`LinkAnnotation.Url` 只能挂单击、无法同时承载长按复制与自定义朗读，改用 `combinedClickable` + `semantics` 手写 `Role.Button` / `onClick` / `onLongClick`，语义等价且可控。

## 5. 文案

| 键 | 文案 |
|---|---|
| `about_title` | 关于 |
| `about_app_desc` | 安卓本地文档阅读器 |
| `about_version` | 版本 {version}（运行时读 `PackageManager`） |
| `about_link_agpl` | AGPL-3.0 全文：{url} |
| `about_link_project` | 本项目源码：{url} |
| `about_link_mupdf_home` | MuPDF 主页：{url} |
| `about_link_mupdf_upstream` | MuPDF 上游：{url} |
| `about_link_viewer_fork` | viewer 分支：{url} |
| `ok_link_copied` | 链接已复制 |
| `err_link_no_browser` | 未找到可打开链接的应用，请长按复制链接 |
| `a11y_link` | {label}，链接。双击打开，长按复制 |
| `about_license_section` | 开源许可 |
| `about_license_body` | 本项目整体以 AGPL-3.0 开源（含服务端条款的网络传播义务）。 |
| `about_mupdf_section` | PDF 渲染引擎 |
| `about_mupdf_body` | PDF 渲染引擎为 Artifex Software 的 MuPDF，同样遵循 AGPL-3.0，以 git submodule 形式集成自其官方仓库的自有分支。 |

固定链接（与代码常量一致，P2 已闭环）：

| 键 | 链接 |
|---|---|
| `AGPL_URL` | `https://www.gnu.org/licenses/agpl-3.0.html` |
| `PROJECT_REPO_URL` | `https://github.com/czc70159407/MyReader` |
| `MUPDF_HOME_URL` | `https://mupdf.com` |
| `MUPDF_UPSTREAM_URL` | `https://github.com/ArtifexSoftware/mupdf-android-viewer` |
| `VIEWER_FORK_URL` | `https://github.com/czc70159407/mupdf-android-viewer/tree/myreader-patches` |

> **P2 决策（2026-11-09）**：原「源码仓库」标签指向 `gitlab.com/artifexsoftware/...`——**与当前上游口径（GitHub）不符**（判断依据为 `git remote -v`、README 与 `.gitmodules`；旧 GitLab 链接的可达性未实测），且标签含糊（实际 submodule 指向自有 fork）。改为分列三个明确对象：本项目主仓（AGPL 网络传播条款所需「本项目源码」）、MuPDF 上游仓库、实际集成的 viewer fork；后者链接直达 `myreader-patches` 分支，避免落到跟随上游 1.28.5 的默认分支。口径与 `README.md` 合规章节、`.gitmodules`、`docs/architecture.md` 一致。
> 不再保留 `[建议 P2]`；链接地址变更需四处同步（本表 / 代码常量 / README 合规章节 / `.gitmodules` 或架构分册）。

## 6. 表单校验

无输入表单。

## 7. 无障碍

| 项 | 要求 | 现状 |
|---|---|---|
| 链接语义 | 可点击 URL 需作为链接角色暴露（`Role.Button`/`Link`） | `[已实现]` `Role.Button` + `onClick` / `onLongClick` 语义 |
| 朗读 | 朗读「AGPL-3.0 全文，链接」而非裸 URL | `[已实现]` `a11y_link` 模板，CD 用标签不拼接裸 URL |
| 触达 | 链接行高 ≥ 48dp（可点击时） | `[已实现]` `heightIn(min = 48.dp)` |
| 文本对比度 | 正文 `onSurface`；URL 若是链接用 `primary`（≥ 4.5:1） | `[已实现]` `primary` + 下划线 |
| 图标语义 | 应用标识为装饰，不朗读 | `[已实现]` `contentDescription = null` |
| 长文本 | 支持字体缩放与换行 | `[已实现]` |

## 8. 响应式

| 场景 | 变化 |
|---|---|
| 竖屏 | 单列 |
| 横屏/平板 | `[建议 P2]` 限宽 600dp |
| 字体 200% | 滚动；URL 换行 |

## 9. 验收场景

1. 设置 → 关于：应用标识、应用名、版本号、两段说明与五个链接全部可见；
2. 点击 AGPL 链接：系统浏览器打开许可页；
3. 长按链接：复制到剪贴板 + Snackbar「链接已复制」；
4. 无浏览器设备：点击后 Snackbar「未找到可打开链接的应用，请长按复制链接」，不崩溃；
5. 字体 200%：无裁切，可滚动到底部，URL 可换行；
6. TalkBack：应用标识静默，链接朗读「AGPL-3.0 全文，链接。双击打开，长按复制」；
7. 深浅主题：应用标识与链接色随 `colorScheme` 变化，对比度达标；
8. **链接与声明语义一致**：五个链接地址与 `README.md`「许可证与开源声明」章节相同；正文措辞允许不同（README 用 markdown 链接、应用内用「标签：URL」纯文本），但开源主体、许可证、MuPDF 来源与集成方式的语义必须一致（评审时对照）。

> 版本号 `versionName` 随发版变化，验收时以 `app/build.gradle.kts` 当前值比对。
