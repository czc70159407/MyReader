# MyReader

安卓本地文档阅读器：PDF 优先，已支持 EPUB / CBZ / XPS / TXT 多格式。导入、多级目录管理、阅读进度记忆、备份与恢复，全程离线。

## 功能

- **本地文档阅读**：从设备存储选取文档导入（复制到应用内部存储，无需存储权限），支持水平分页滑动与垂直连续滚动
- **多格式**：PDF / EPUB / CBZ / XPS / TXT，引擎按格式路由，新增格式不改阅读器与数据库（见下节「多格式文档支持」）
- **多级目录管理**：树形文件夹，可新建 / 重命名 / 删除 / 移动，文档可归入任意层级；删除目录时文档自动回到根级，永不丢文档
- **阅读进度记忆**：每文档记住上次位置（页码 + 垂直偏移；EPUB 用 CFI），再次打开原地恢复（防抖写库 + 退出保底落盘）
- **书架体验**：导入时自动生成首页缩略图、全库标题搜索、多维度排序（标题 / 最近阅读 / 添加时间 / 大小）、最近阅读列表
- **大纲导航**：带目录的文档可通过大纲条目直接跳转
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
  └── mupdf-android-viewer/   # MuPDF 官方 viewer（git submodule）
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

> 开发指南与任务分解见 [AGENTS.md](AGENTS.md)。
