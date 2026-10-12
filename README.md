# MyReader

安卓本地 PDF 阅读器：导入、多级目录管理、阅读进度记忆。

## 功能

- **本地 PDF 阅读**：从设备存储选取 PDF 导入（复制到应用内部存储，无需存储权限），MuPDF 渲染，水平分页滑动（v1.1 计划垂直连续滚动）
- **多级目录管理**：树形文件夹，可新建 / 重命名 / 删除 / 移动，PDF 可归入任意层级；删除目录时文档自动回到根级，永不丢文档
- **阅读进度记忆**：每文档记住上次页码与偏移，再次打开原地恢复（防抖写库 + 退出保底落盘）
- **书架体验**：导入时自动生成首页缩略图、全库标题搜索、多维度排序（标题 / 最近阅读 / 添加时间 / 大小）、最近阅读列表
- **大纲导航**：带目录的 PDF 可通过大纲条目直接跳页
- **异常处理**：加密 PDF 入库标记、打开时密码解锁；损坏 / 重复文件导入自动识别
- v1.1 计划：垂直连续滚动模式、设置页（阅读模式 / 主题 / 排序偏好）、备份与跨设备导出

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0 + Jetpack Compose（Material 3），单 Activity + Navigation |
| PDF 引擎 | [MuPDF](https://mupdf.com)（AGPL，git submodule 源码集成） |
| 数据库 | Room（schema 迁移纪律：禁用破坏性迁移） |
| DI | Hilt（`@FileTypeKey` 多绑定按格式路由文档引擎） |
| 最低 Android | API 26（Android 8.0），targetSdk 35 |
| 构建 | Gradle 8.14 + Kotlin DSL + 版本目录 |

## 构建

环境要求：JDK 21、Android SDK（Platform 35 + Build-Tools 35.0.0）、NDK（编译 MuPDF native 库）。

```powershell
# 克隆（含 MuPDF submodule）
git clone --recursive <repository-url>
cd MyReader

# 首次拉起 submodule（clone 未加 --recursive 时）
git submodule update --init --recursive

# 装配 Debug APK（首次会触发 MuPDF native 编译，耗时较长）
.\gradlew.bat :app:assembleDebug

# 全量构建 + 测试
.\gradlew.bat build
```

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
| M0 | 脚手架 + git init | 进行中 |
| M1 | MuPDF submodule + native 编译 | 未开始 |
| M2 | 数据层（Room/Hilt） | 未开始 |
| M3 | 导入与书架 | 未开始 |
| M4 | 阅读器（水平模式 + 进度记忆） | 未开始 |
| M5 | 目录树与文档管理 | 未开始 |
| M6 | 搜索 / 排序 / 最近阅读 | 未开始 |
| M7 | 垂直滚动 + 设置（v1.1） | 未开始 |
| M8 | 备份与导出（v1.1） | 未开始 |

> 开发指南与任务分解见 [AGENTS.md](AGENTS.md)。
