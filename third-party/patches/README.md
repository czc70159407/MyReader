# third-party/patches —— MuPDF submodule 本地改动补丁

## 背景

`third-party/mupdf-android-viewer` 是 git submodule，远端仍指向上游
`https://github.com/ArtifexSoftware/mupdf-android-viewer.git`（无写权限）。项目基于上游
`8407044`（MuPDF 1.28.2）叠加了两个**本地提交**（内容以本目录 patch 持久化）：

| Patch | 来源提交 | 内容 |
|---|---|---|
| `0001-feat-M9-MuPDFCore-pageToBookmark-bookmarkToPage.patch` | `315ba6c` | `MuPDFCore` 新增 `pageToBookmark` / `bookmarkToPage` 公开方法（M9：epub CFI 进度锚点经 JNI bookmark 往返，`docs/m9-plan.md` L1） |
| `0002-fix-MuPDFCore-layout-bookmark.patch` | `a5496f1` | `layout` 异常保护（加密文档认证前失败不误判损坏）+ bookmark 失败码分离（`Long.MIN_VALUE` vs 合法 0）+ 整书重排放后台线程 |

这两个 commit 是**构建必需**的：`feature:viewer-mupdf` 的 `EpubLocationResolver` 直接调用
`pageToBookmark` / `bookmarkToPage`。

**gitlink 契约（重要，2026-10-16 CI 首跑失败后确立）**：主仓记录的 submodule 指针是
**上游基线 `8407044`**，而非本地提交 `a5496f1`——后者只存在于开发机，写进 gitlink 会让
所有 `git submodule update`（含 CI）报 `not our ref` 拒取。正确的复现方式一律是
「`submodule update` 取基线 → `git am` 本目录补丁」，补丁应用后的内容与 `a5496f1`
**逐字节一致**（SHA-256 已验证）。因此：

- **全新环境**（CI / 他人 clone）：照下节步骤执行即可。
- **原开发机**：submodule 停在 `a5496f1`，`git status` 会长期显示
  `modified: third-party/mupdf-android-viewer (new commits)`——这是**预期状态**，
  勿"修复"它（跑 `git submodule update` 会把工作区退回 8407044 丢掉补丁，需重新 am）。
  构建不受任何影响。

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

（若上游基线升级：先在主仓 `git update-index --cacheinfo 160000,<新基线SHA>,third-party/mupdf-android-viewer`
更新 gitlink，再以新基线为起点重新生成补丁。）

## 长期方案（推荐）

把 `mupdf-android-viewer` fork 到有写权限的 GitHub 远端，推送这两个 commit 后改
`.gitmodules` 指向 fork——此后 `git submodule update` 即自动包含本地改动，本目录可删。
在 fork 落地前，**本目录与 submodule 的 detached HEAD 状态必须保持一致**。
