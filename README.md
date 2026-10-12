# MyReader

安卓本地文档阅读器：PDF 优先，架构原生支持多格式扩展（EPUB / CBZ / XPS 已在路线图）。导入、多级目录管理、阅读进度记忆。

## 功能

- **本地 PDF 阅读**：从设备存储选取 PDF 导入（复制到应用内部存储，无需存储权限），MuPDF 渲染，水平分页滑动（v1.1 计划垂直连续滚动）
- **多格式扩展架构**：文档引擎按格式路由（见下节「多格式文档支持」），新增格式不改动阅读器与数据库
- **多级目录管理**：树形文件夹，可新建 / 重命名 / 删除 / 移动，PDF 可归入任意层级；删除目录时文档自动回到根级，永不丢文档
- **阅读进度记忆**：每文档记住上次页码与偏移，再次打开原地恢复（防抖写库 + 退出保底落盘）
- **书架体验**：导入时自动生成首页缩略图、全库标题搜索、多维度排序（标题 / 最近阅读 / 添加时间 / 大小）、最近阅读列表
- **大纲导航**：带目录的 PDF 可通过大纲条目直接跳页
- **异常处理**：加密 PDF 入库标记、打开时密码解锁；损坏 / 重复文件导入自动识别
- v1.1 计划：垂直连续滚动模式、设置页（阅读模式 / 主题 / 排序偏好）、备份与跨设备导出
- v1.2 计划：开放 EPUB / CBZ / XPS 格式（MuPDF 引擎原生解析，见路线图 M9）

## 多格式文档支持

阅读器按「一格式一引擎」设计：`feature:reader` / `feature:library` 只依赖 `core:document` 接口（`DocumentEngine` / `Document` / `DocumentViewerProvider`），各引擎经 Hilt `@FileTypeKey` 多绑定注册，运行时按 `doc.fileType` 路由到对应实现。

| 格式 | 状态 | 引擎 | 进度模型 |
|---|---|---|---|
| PDF | v1 支持 | MuPDF（`feature:viewer-mupdf`） | 页码 + 垂直偏移 |
| EPUB | 规划（M9 / v1.2） | MuPDF（同引擎复用） | 章节 + 页码（序列化至 `progressPayload`，字段已预留） |
| CBZ | 规划（M9 / v1.2） | MuPDF（同引擎复用） | 页码式 |
| XPS / OXPS | 规划（M9 / v1.2） | MuPDF（同引擎复用） | 页码式 |
| TXT 等纯文本 | 远期 | 自研轻量引擎（`feature:viewer-txt`，验证扩展点） | 字符偏移 |

**扩展一个新格式 = 新建 `feature:viewer-xxx` 模块**，实现三个接口并注册 `@FileTypeKey("xxx")` 即可；书架、阅读器 UI、Room schema 均零改动。数据库自 v1 起预留 `fileType` / `progressType` / `progressPayload` 字段，导入器按已注册引擎自动聚合可接受的 mime 类型。

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0 + Jetpack Compose（Material 3），单 Activity + Navigation |
| PDF 引擎 | [MuPDF](https://mupdf.com)（AGPL）：viewer 层 git submodule 源码集成 + native 层官方 Maven 预编译构件 |
| 数据库 | Room（schema 迁移纪律：禁用破坏性迁移） |
| DI | Hilt（`@FileTypeKey` 多绑定按格式路由文档引擎） |
| 最低 Android | API 26（Android 8.0），targetSdk 35 |
| 构建 | Gradle 8.14 + Kotlin DSL + 版本目录 |

## 构建

环境要求：JDK 21、Android SDK（Platform 33/35 + Build-Tools 35.0.0）。无需 NDK——MuPDF native 层使用官方预编译构件。

```powershell
# 克隆后拉起 submodule（注意：勿加 --recursive，原因见下）
git clone <repository-url>
cd MyReader
git submodule update --init

# 装配 Debug APK
.\gradlew.bat :app:assembleDebug

# 全量构建 + 测试
.\gradlew.bat build
```

> **为什么不能用 `--recursive`**：MuPDF viewer 的嵌套子模块 `jni/`（mupdf-android-fitz）刻意保持未初始化——native 层（`libmupdf_java.so`）直接使用官方 Maven 预编译构件（`com.artifex.mupdf:fitz`，全 ABI），无需 NDK 工具链与源码编译；递归初始化会使构建切换到源码编译分支并失败。

## 项目结构

```
app/                    # 主壳：Application + MainActivity + NavHost + Hilt DI
core/
  ├── designsystem/     # 主题（颜色/字体/形状）+ 原子组件
  ├── model/            # 纯领域模型（无 Room 注解）
  ├── data/             # Repository + Room（实体/DAO/数据库）
  ├── storage/          # 文件复制/删除/缩略图 IO
  ├── document/         # DocumentEngine/Document/ViewerProvider 抽象接口
  └── common/           # 协程 Dispatcher / 时间工具
feature/
  ├── library/          # 书架 + 目录树 + 搜索/排序 + 文档操作
  ├── reader/           # 阅读器 UI（跳页/大纲/模式切换）
  ├── settings/         # 设置页（DataStore 偏好）
  └── viewer-mupdf/     # MuPDF 渲染桥接（MuPDF 边界止于此）
third-party/
  └── mupdf-android-viewer/   # MuPDF 官方 viewer（git submodule）
```

架构要点：`feature:reader` / `feature:library` 只依赖 `core:document` 接口，不直接依赖 MuPDF；各渲染引擎经 Hilt `@FileTypeKey` 多绑定注册，新增文档格式（epub / cbz 等）只需新增 `feature:viewer-xxx` 模块，不改阅读器与数据库 schema。

## 许可证与开源声明（AGPL 合规）

- 本项目**整体以 [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 开源**（含服务端条款的网络传播义务）
- PDF 渲染引擎为 Artifex Software 的 **MuPDF**，以 git submodule 形式集成自其[官方仓库](https://gitlab.com/artifexsoftware/mupdf-android-viewer)，同样遵循 AGPL-3.0
- AGPL-3.0 全文：<https://www.gnu.org/licenses/agpl-3.0.html>
- 应用内 About 页同步展示以上声明

## 路线图

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + native 编译 | 未开始 |
| M2 | 数据层（Room/Hilt） | 未开始 |
| M3 | 导入与书架 | 未开始 |
| M4 | 阅读器（水平模式 + 进度记忆） | 未开始 |
| M5 | 目录树与文档管理 | 未开始 |
| M6 | 搜索 / 排序 / 最近阅读 | 未开始 |
| M7 | 垂直滚动 + 设置（v1.1） | 未开始 |
| M8 | 备份与导出（v1.1） | 未开始 |
| M9 | 多格式支持（EPUB / CBZ / XPS，v1.2） | 未开始 |

> 开发指南与任务分解见 [AGENTS.md](AGENTS.md)。
