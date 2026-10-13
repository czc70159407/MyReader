---
name: myreader-dev
description: "MyReader 安卓 PDF 阅读器（Kotlin/Jetpack Compose/多模块 Gradle/Room/Hilt/MuPDF）的项目开发约束与验收流程。当在本仓库修改代码、新增模块或依赖、改动 Room schema、执行构建测试或提交、生成验收测试 PDF 时触发，确保遵循模块边界、迁移纪律、AGPL 合规与提交门禁。本技能是 AGENTS.md 的可触发摘要，详细事实源以 AGENTS.md 与 docs 分册为准。"
---

# MyReader Dev

## Overview

本技能把 MyReader 仓库的硬约束与开发/验收流程固化为可触发的操作指引，避免偏离既有架构与纪律。
MyReader 是多模块安卓 PDF 阅读器：核心能力在 `core:*`（model/common/storage/data/document）、
功能在 `feature:*`（library/reader/settings/viewer-mupdf）、第三方 MuPDF 在 `third-party:mupdf`。

## When to Use

在以下任一场景触发：修改本仓库代码、新增/调整 Gradle 模块或依赖、改动 Room 实体或 DAO、
执行 `gradlew.bat` 构建/测试、准备提交（commit/push）、生成或校验验收测试 PDF。

## Hard Constraints（硬约束，违反即视为缺陷）

1. **MuPDF 边界**：MuPDF 具体类只允许出现在 `feature:viewer-mupdf` 与 `third-party:mupdf`；
   `feature:reader`/`feature:library` 只依赖 `core:document` 接口。新增格式 = 新建
   `feature:viewer-xxx` + `@FileTypeKey`，不改 reader 与 schema。
2. **AGPL 合规**：APP 整体开源；README + About 页注明 MuPDF 来源与 AGPL 全文链接；
   不引入 AGPL 不兼容的闭源组件。
3. **Room 迁移纪律**：schema JSON 随仓库提交；禁用 `fallbackToDestructiveMigration`
   （阅读进度是核心数据）；改 schema 必须写 `Migration`。
4. **完成定义**：代码变更须通过对应里程碑「验收」（见 docs/process.md）+ `.\gradlew.bat build`
   无错，才算完成；声称完成必须附证据（命令输出摘要）。
5. **文档同步**：schema/模块结构/依赖版本/接口签名变更，同一任务内更新对应分册；
   状态、验收证据、卡点只更新 AGENTS.md。
6. **不过度工程**：不引入文档未列出的依赖/模块；确需偏离选型，先改 docs/architecture.md
   「技术选型」章节并写明理由。
7. **测试下沉**：纯逻辑放 `core:*` 做 JVM 测试；改 `core:data`/`core:storage` 必须补跑对应单测。
8. **提交门禁**：所有代码 commit / push 前必须交由用户决策——用户可选择「先 review」或
   「直接提交推送」；未经用户明确同意，不得执行任何 commit 或 push。

## Command Environment（命令环境）

- 操作系统 Windows / PowerShell；克隆后执行 `git submodule update --init`（**勿加 `--recursive`**：
  viewer 的嵌套 jni 子模块刻意不初始化，以走 fitz Maven 预编译分支）。
- 一律 `.\gradlew.bat`（非 `gradle`）。常用命令见 `references/commands.md`。
- Java 访问 services.gradle.org 被干扰已预置 gradle 缓存绕过，**勿删**
  `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\...\` 目录。
- Android SDK 位于 `D:\develop\Android\Sdk`；`local.properties` 已指向；`ANDROID_HOME` 用户级已设。
- git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）。

## Acceptance Test PDFs（验收夹具）

M3–M7 真机验收依赖五类测试 PDF，由 `tools/test_pdfs.py` 生成与离线校验，避免每次手工造 PDF：

- `python tools/test_pdfs.py generate` —— 生成到 `tools/fixtures/`（已 gitignore）。
- `python tools/test_pdfs.py verify` —— 断言页数/大纲/加密/损坏属性，全绿方可用于验收。
- `python tools/test_pdfs.py selftest` —— 生成到临时目录并立即校验。

夹具清单：`demo_test.pdf`(3 页)、`outline_test.pdf`(12 页+两级大纲)、`corrupt_test.pdf`(损坏)、
`encrypted_test.pdf`(密码 1234)、`long_test.pdf`(40 页)。依赖 `pypdf` + `reportlab`
（`python -m pip install pypdf reportlab`）。

## Resources

- `references/constraints.md` —— 硬约束与里程碑状态的精简速查（与 AGENTS.md 同步，冲突以 AGENTS.md 为准）。
- `references/commands.md` —— 构建/测试/运行/提交命令与已知卡点的完整清单。

> 详细事实源始终为仓库根 `AGENTS.md` 与 `docs/`（architecture.md / data-layer.md /
> ui-design.md / process.md）。本技能仅作触发与摘要，不复制其全部内容。
