# third-party/patches —— MuPDF submodule 本地改动补丁

## 背景

`third-party/mupdf-android-viewer` 是 git submodule，远端仍指向上游
`https://github.com/ArtifexSoftware/mupdf-android-viewer.git`（无写权限，且 submodule
处于 detached HEAD）。项目在上游 `8407044`（MuPDF 1.28.2）之上有两个**本地提交**：

| Patch | 来源提交 | 内容 |
|---|---|---|
| `0001-feat-M9-MuPDFCore-pageToBookmark-bookmarkToPage.patch` | `315ba6c` | `MuPDFCore` 新增 `pageToBookmark` / `bookmarkToPage` 公开方法（M9：epub CFI 进度锚点经 JNI bookmark 往返，`docs/m9-plan.md` L1） |
| `0002-fix-MuPDFCore-layout-bookmark.patch` | `a5496f1` | `layout` 异常保护（加密文档认证前失败不误判损坏）+ bookmark 失败码分离（`Long.MIN_VALUE` vs 合法 0）+ 整书重排放后台线程 |

这两个 commit 是**构建必需**的：`feature:viewer-mupdf` 的 `EpubLocationResolver` 直接调用
`pageToBookmark` / `bookmarkToPage`。他人 clone 主仓后 `git submodule update` 只会拿到
上游 `8407044`，**不打补丁则编译失败**（`NoSuchMethodError` 路径）。

> 改动均在 MuPDF 边界内（`MuPDFCore.java`，即 `third-party:mupdf` 模块），符合
> AGENTS.md 工作规则 1；本目录是对「子模块提交无法推送」这一限制的持久化解法，
> 待迁移到自有 fork 远端后可整体退役（见下）。

## 应用方式（clone 后）

```powershell
# 1. 正常拉 submodule（注意：勿加 --recursive，原因见 README「构建」节）
git submodule update --init

# 2. 应用两个补丁（在 submodule 目录内执行）
git -C third-party/mupdf-android-viewer am ../../third-party/patches/*.patch

# 3. 校验：submodule HEAD 应为 a5496f1（或其后代）
git -C third-party/mupdf-android-viewer log --oneline -3

# 4. 构建验证
.\gradlew.bat :app:assembleDebug
```

`git am` 会保留原始作者/日期/提交信息；若补丁已应用过（重复执行）可用
`git apply --check` 预检，或 `git am --3way` 处理轻微上下文漂移。

## 重新生成补丁（submodule 有新本地提交时）

```powershell
git -C third-party/mupdf-android-viewer format-patch 8407044..HEAD -o third-party/patches
```

（若上游基线升级，把 `8407044` 换成新基线的 commit。）

## 长期方案（推荐）

把 `mupdf-android-viewer` fork 到有写权限的 GitHub 远端，推送这两个 commit 后改
`.gitmodules` 指向 fork——此后 `git submodule update` 即自动包含本地改动，本目录可删。
在 fork 落地前，**本目录与 submodule 的 detached HEAD 状态必须保持一致**。
