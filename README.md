# MyReader

安卓本地文档阅读器：PDF 优先，已支持 EPUB / CBZ / XPS / TXT 多格式。导入、多级目录管理、阅读进度记忆、备份与恢复，全程离线。

## 为什么做这个应用

市面上的本地文档阅读软件大多充斥开屏广告、插页广告与各种诱导推广，安静地读一个本地 PDF 反而成了奢望。MyReader 因此而生——一个纯粹、干净、完全离线的本地文档阅读器：无广告、无追踪、不联网、不收集任何数据，文档只留在你自己的设备上，阅读体验回归它本来的样子。

## 功能

- **本地文档阅读**：从设备存储选取文档导入（复制到应用内部存储，无需存储权限），支持水平分页滑动与垂直连续滚动
- **目录导入**：选择整个目录即可批量导入，自动按原目录结构在书架创建镜像文件夹；内容重复自动跳过、同名自动重命名，结束后展示成功/跳过/失败汇总
- **多格式**：PDF / EPUB / CBZ / XPS / TXT，引擎按格式路由，新增格式不改阅读器与数据库（见下节「多格式文档支持」）
- **多级目录管理**：树形文件夹，可新建 / 重命名 / 删除 / 移动，文档可归入任意层级；删除目录时文档自动回到根级，永不丢文档
- **阅读进度记忆**：每文档记住上次位置（页码 + 垂直偏移；EPUB 用 CFI），再次打开原地恢复（防抖写库 + 退出保底落盘）
- **书架体验**：导入时自动生成首页缩略图、全库标题搜索、多维度排序（标题 / 最近阅读 / 添加时间 / 大小）、最近阅读列表
- **大纲导航**：带目录的文档可通过大纲条目直接跳转
- **书签**：当前页一键收藏（PDF「第 N 页」/ EPUB「第 N 屏」），书签面板可跳转与删除，每文档上限 100 条，随备份一并导出恢复
- **异常处理**：加密 PDF 入库标记、打开时输密码解锁（密码不持久化）；损坏 / 重复 / 不支持的文件导入自动识别并拒收
- **备份与恢复**：导出物理文档 + 缩略图 + 元数据清单为单个 `.zip`，可整体恢复目录树 / 进度 / 元数据（卸载重装后还原）
- **设置**：阅读模式（水平 / 垂直）、主题（浅色 / 深色 / 跟随系统）、书架排序偏好，全部持久化

## 多格式文档支持

阅读器按「一格式一引擎」设计：`feature:reader` / `feature:library` 只依赖 `core:document` 接口（`DocumentEngine` / `Document` / `DocumentViewerProvider`），各引擎经 Hilt `@FileTypeKey` 多绑定注册，运行时按 `doc.fileType` 路由到对应实现。

| 格式 | 状态 | 引擎 | 进度模型 |
|---|---|---|---|
| PDF | 已支持（v1） | MuPDF（`feature:viewer-mupdf`） | 页码 + 垂直偏移 |
| EPUB | 已支持（v1.2 / M9） | MuPDF（同引擎复用） | 重排位置 + 页码（CFI 序列化至 `progressPayload`） |
| CBZ | 已支持（v1.2 / M9） | MuPDF（同引擎复用） | 页码式 |
| XPS / OXPS | 已支持（v1.2 / M9） | MuPDF（同引擎复用） | 页码式 |
| TXT 等纯文本 | 已支持（v1.2 / M9-F） | 自研轻量引擎（`feature:viewer-txt`，同时作为扩展点验证） | 页码式（字符集分页） |

> XPS 的引擎路由与打开链路已真机验证；正向渲染因手写 OPC 夹具不满足 fitz 解析器，仍待真实样本复验（详见 [AGENTS.md](AGENTS.md)「M9 真机验收」R3）。

**扩展一个新格式 = 新建 `feature:viewer-xxx` 模块**，实现三个接口并注册 `@FileTypeKey("xxx")` 即可；书架、阅读器 UI、Room schema 均零改动。数据库自 v1 起预留 `fileType` / `progressType` / `progressPayload` 字段，导入器按已注册引擎自动聚合可接受的 mime 类型。

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0 + Jetpack Compose（Material 3），单 Activity + Navigation |
| PDF 引擎 | [MuPDF](https://mupdf.com)（AGPL）：viewer 层 git submodule 源码集成 + native 层官方 Maven 预编译构件 |
| 数据库 | Room（schema 迁移纪律：禁用破坏性迁移） |
| DI | Hilt（`@FileTypeKey` 多绑定按格式路由文档引擎） |
| 偏好 / 序列化 | DataStore Preferences、kotlinx.serialization-json（备份清单、EPUB 进度 CFI） |
| 最低 Android | API 26（Android 8.0），targetSdk 35 |
| 构建 / 发布 | Gradle 8.14 + Kotlin DSL + 版本目录；release 启用 R8 代码收缩与资源裁剪 |

## 构建

环境要求：JDK 21、Android SDK（Platform 33/35 + Build-Tools 35.0.0）。无需 NDK——MuPDF native 层使用官方预编译构件。

```powershell
# 克隆后拉起 submodule（注意：勿加 --recursive，原因见下）
# submodule 指向自有 fork 的 myreader-patches 分支，epub 进度锚点 + layout 保护补丁已含在内，一步取全
git clone <repository-url>
cd MyReader
git submodule update --init

# 装配 Debug APK
.\gradlew.bat :app:assembleDebug

# 全量构建 + 测试
.\gradlew.bat build
```

> **为什么不能用 `--recursive`**：MuPDF viewer 的嵌套子模块 `jni/`（mupdf-android-fitz）刻意保持未初始化——native 层（`libmupdf_java.so`）直接使用官方 Maven 预编译构件（`com.artifex.mupdf:fitz`，全 ABI），无需 NDK 工具链与源码编译；递归初始化会使构建切换到源码编译分支并失败。

Release 构建（R8 混淆 + 资源裁剪）：

```powershell
# 产出 release APK；密钥由仓库根 keystore.properties 提供（不入仓）
.\gradlew.bat :app:assembleRelease

# CI 场景：缺少签名配置时直接失败，防误发未签名包
.\gradlew.bat :app:assembleRelease -Pmyreader.requireReleaseSigning=true
```

> 本地缺少 `keystore.properties` 时不会构建失败，但会打印告警并产出**未签名** APK，仅供验证使用。

## 项目结构

```
app/                    # 主壳：Application + MainActivity + NavHost + Hilt DI
core/
  ├── model/            # 纯领域模型（无 Room 注解）
  ├── data/             # Repository + Room（实体/DAO/数据库）
  ├── storage/          # 文件复制/删除/缩略图 IO
  ├── document/         # DocumentEngine/Document/ViewerProvider 抽象接口
  └── common/           # 协程 Dispatcher / 时间工具
feature/
  ├── library/          # 书架 + 目录树 + 搜索/排序 + 文档操作
  ├── reader/           # 阅读器 UI（跳页/大纲/模式切换）
  ├── settings/         # 设置页（阅读模式 / 主题 / 排序）+ 备份与恢复
  ├── viewer-mupdf/     # MuPDF 渲染桥接（MuPDF 边界止于此）
  └── viewer-txt/       # TXT 渲染（扩展点验证，不依赖 MuPDF）
third-party/
  └── mupdf-android-viewer/   # MuPDF viewer（git submodule → 自有 fork czc70159407/mupdf-android-viewer，本地补丁在 myreader-patches 分支）
docs/
  ├── design/             # UI 交互方案（设计意图事实源：tokens / copy / screens / flows / accessibility）
  └── *.md                # 架构、数据层、UI 实现、里程碑计划等分册
.github/
  └── workflows/build.yml     # CI：全量构建 + 单测（push/PR 触发）
LICENSE                 # AGPL-3.0 全文
NOTICE                  # 第三方组件与许可来源
```

架构要点：`feature:reader` / `feature:library` 只依赖 `core:document` 接口，不直接依赖 MuPDF；各渲染引擎经 Hilt `@FileTypeKey` 多绑定注册，新增文档格式（epub / cbz 等）只需新增 `feature:viewer-xxx` 模块，不改阅读器与数据库 schema。

## 隐私与安全

- **无网络权限**：APP 不声明 `INTERNET`，不含网络库、无明文流量，文档不上传任何服务端
- **数据留在本地**：文档与数据库仅存于应用私有目录，并已关闭 Android 自动备份（`android:allowBackup="false"`）
- **最小对外攻击面**：唯一导出组件为启动 Activity；第三方 MuPDF viewer 自带的导出 `DocumentActivity` 与 `READ_EXTERNAL_STORAGE` 权限已在 manifest 合并阶段移除
- **备份按不可信输入处理**：恢复时对 zip 条目做白名单与路径穿越校验，并设资源上限（条目数 / 单条目 / 累计大小 / 清单大小 / 压缩比），`library.json` 另有版本与格式门禁
- **release 已混淆**：启用 R8 代码收缩与资源裁剪，保留 fitz JNI 与序列化所需 keep 规则

## 许可证与开源声明（AGPL 合规）

- 本项目**整体以 [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 开源**（含网络传播条款的义务），仓库根目录附全文：[LICENSE](LICENSE)
- 第三方组件与许可来源：[NOTICE](NOTICE)
- PDF 渲染引擎为 Artifex Software 的 **MuPDF**，以 git submodule 形式集成自其[官方仓库](https://github.com/ArtifexSoftware/mupdf-android-viewer)，同样遵循 AGPL-3.0
- AGPL-3.0 全文：<https://www.gnu.org/licenses/agpl-3.0.html>
- 应用内 About 页（设置页入口）同步展示以上声明

## 路线图

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + Maven fitz 预编译 | 已完成 |
| M2 | 数据层（Room/Hilt） | 已完成 |
| M3 | 导入与书架 | 已完成 |
| M4 | 阅读器（水平模式 + 进度记忆） | 已完成 |
| M5 | 目录树与文档管理 | 已完成 |
| M6 | 搜索 / 排序 / 最近阅读 | 已完成 |
| M7 | 垂直滚动 + 设置（v1.1） | 已完成 |
| M8 | 备份与恢复（v1.1） | 已完成 |
| M9 | 多格式支持（EPUB / CBZ / XPS + TXT 扩展点，v1.2） | 已完成 |
| 安全加固 | 导出面收窄、备份解压资源上限、release 混淆、LICENSE / NOTICE | 已完成 |
| M10 | 书签（章节级）+ 首个 Room Migration（v1.3） | 已完成（2026-10-30，A/B/C/D 全过：schema v2 + `MIGRATION_1_2`（真机 MigrationTest 2/2）、书签仓储双轨去重与上限 100、备份 v2 与 v1 兼容、阅读器书签入口/面板/跳转；D 真机五项通过并修 2 缺陷——垂直视图跳页丢页内偏移、备份负锚点致恢复整体失败。epub 跨排版/跨会话锚点失效登记为已知限制 → M11 候选改比例锚点，见 [docs/m10-plan.md](docs/m10-plan.md)） |
| M11 | epub 锚点稳健性 / 比例锚点（v1.4） | 已完成（2026-10-31：`cp:<chapter>:<章内页>:<章页数>` 比例锚点 + `EpubProgressCodec` v2 + 备份 `schemaVersion=3` + `MuPDFCore` Location API；真机探针判定「Location 跨会话可用、bookmark 串失效」；六项真机验收全过并修 2 缺陷——精确路径未校验章页数、旋转 remap 未按比例；见 [docs/m11-plan.md](docs/m11-plan.md)） |

> 开发指南与任务分解见 [AGENTS.md](AGENTS.md)。
