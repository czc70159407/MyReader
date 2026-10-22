# 架构分册：技术选型 / 模块结构 / MuPDF 集成 / 依赖清单

> AGENTS.md 文档体系分册之一：改模块结构、依赖版本、MuPDF 集成方式，或偏离选型时更新本文档（工作规则 5/6）；状态与验收证据在根 AGENTS.md。

## 技术选型

| 决策点 | 选择 | 理由 |
|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose，单 Activity + Navigation | Google 官方推荐，声明式 UI，开发效率高 |
| PDF 引擎 | MuPDF 开源版（AGPL）：viewer 层源码 git submodule + fitz 层官方 Maven 预编译构件 | 渲染质量最好；AGPL 要求 APP 开源（已计划）。原「官方不发布 Maven、须源码编译」前提已过时：官方 `maven.ghostscript.com` 提供 fitz AAR（全 ABI，免 NDK 工具链与数十分钟首次 native 编译）；viewer 源码仍可读可定制 |
| 最低 Android | API 26 (Android 8.0) | MuPDF native 库兼容 API 21+；26 覆盖当前 99%+ 设备，且自适应图标、Java 8 desugaring 默认开启，免去额外兼容配置 |
| 构建工具 | Gradle + Kotlin DSL + 版本目录 (libs.versions.toml) | 统一版本管理 |
| DI | Hilt | 官方推荐，与 Compose/Room 集成好 |
| 数据库 | Room | 官方 ORM，支持 Flow 响应式查询 |
| PDF 文件存储 | **复制到 app 内部存储** `filesDir/library/` | SAF 持久化权限不可靠（用户撤销/换机即丢失），内部存储零权限烦恼；注意 `filesDir` 卸载/换机即丢，跨设备迁移需另提供导出/备份（见里程碑 M8） |
| 进度记忆 | 合并到 Document 表（不单建 ReadProgress 表） | 单文档单进度，无历史需求，避免冗余 JOIN |
| 默认阅读模式 | 水平分页滑动 | 单页清晰，页码即进度，记忆简单；垂直连续滚动作为 v1.1 可选 |
| 备份与恢复（M8） | **单文件 `.zip` 备份包**（`library.json` 快照 + `files/` 物理文档 + `thumbs/` 缩略图）+ **`kotlinx-serialization-json`** 序列化；恢复按原 ID 合并 upsert（幂等） | 跨设备搬运只需一个文件、导入只选一个 Uri；JSON 快照跨 schema 版本可演进（优于直接复制 Room DB 文件，后者绑定 identity hash）；序列化库是 M8 唯一偏离选型的依赖，零运行时闭源风险、AGPL 兼容；纯逻辑下沉 `core:data` 做 JVM 单测，UI 仅提供 SAF 流 |
| Compose 集成 | **AndroidView 桥接** `MuPDFReaderView` | MuPDF 内部已做瓦片分块 + Bitmap 池，重写代价高且易 OOM |

## 项目结构

多模块 + 版本目录。core 按职责拆分以隔离依赖（feature:library/reader 完全不依赖 MuPDF），feature 合并强耦合模块、拆出渲染桥接层：

```
MyReader/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # 统一版本
├── app/                                # :app 主壳（Application + MainActivity + NavHost + DI）
│   └── src/main/java/com/myreader/
│       ├── MyReaderApp.kt             # @HiltAndroidApp
│       ├── MainActivity.kt            # 单 Activity，setContent { MyReaderRoot() }
│       ├── nav/MyReaderNavHost.kt     # 路由表
│       └── di/
│           ├── DatabaseModule.kt
│           ├── RepositoryModule.kt
│           └── DocumentEngineModule.kt  # 多绑定：@IntoMap DocumentEngine/DocumentViewerProvider，按 fileType 路由
├── core/
│   ├── model/                        # :core:model         纯领域模型(LibraryItem/Folder data class，无 Room 注解)
│   ├── data/                          # :core:data          Repository 实现 + Room 数据源(实体/DAO/DB)
│   ├── storage/                      # :core:storage       文件复制/删除/缩略图 IO（按 fileType 调度）
│   ├── document/                     # :core:document      DocumentEngine/Document/DocumentViewerProvider 接口(不含 MuPDF 依赖)
│   └── common/                       # :core:common        协程 Dispatcher / 时间工具
├── feature/
│   ├── library/                      # :feature:library    书架+目录树+文件夹增删改移+搜索/排序/文档长按操作(强耦合合并)
│   ├── reader/                       # :feature:reader     阅读器 UI(顶部栏/跳页框/大纲导航/模式切换)，依赖 :feature:viewer-mupdf(经接口)
│   ├── settings/                     # :feature:settings   设置页(阅读模式/主题/书架排序偏好，DataStore 持久化)
│   ├── viewer-mupdf/                # :feature:viewer-mupdf  MuPDF 渲染桥接，按引擎拆分以为多格式预留
│   └── viewer-txt/                  # :feature:viewer-txt    纯 Compose 文本阅读器（M9-F 扩展点验证，不依赖 MuPDF）
└── third-party/
    └── mupdf-android-viewer/          # git submodule，其 lib/ 作为 :third-party:mupdf 模块
```

### 模块依赖关系（关键：隔离 MuPDF）

```
app ──► feature:reader ──► core:document (接口：DocumentEngine + Document + DocumentViewerProvider)
   │                          ▲
   ├──► feature:library ──► core:document
   ├──► feature:settings ──► core:data + core:model（DataStore 偏好读写；不依赖 core:document 与 MuPDF）
   │                          │  (app 的 DocumentEngineModule 用 @IntoMap 多绑定，按 fileType 路由)
   └──► feature:viewer-mupdf ──► third-party:mupdf   (MuPDF 边界止于此)
            feature:viewer-mupdf 实现 DocumentEngine/Document/DocumentViewerProvider，@FileTypeKey("pdf"/"epub"/"cbz"/"xps") 注册（M9）
   └──► feature:viewer-txt ──► core:document   (M9-F：纯 Compose 文本引擎，@FileTypeKey("txt") 注册，不依赖 MuPDF——验证扩展点)
            feature:reader 不依赖任何 viewer-*，经 core:document 接口 + DI Map 按 doc.fileType 路由到对应引擎
   core:data ──► core:storage + datastore-preferences   (独立链路；双偏好文件：library_prefs 书架排序 M6 / settings_prefs 阅读模式+主题 M7，
                                                          实例由 app DI 以 @LibraryPrefsStore/@SettingsPrefsStore 限定符分别提供，单文件单实例)
```

- `feature:reader`、`feature:library` 只依赖 `core:document`（接口），**不传递依赖 MuPDF**；MuPDF 边界止于 `feature:viewer-mupdf`，`reader` 不直接 `implementation(project(":feature:viewer-mupdf"))`
- `feature:settings` 依赖 `core:data`（而非原计划的 core:designsystem）：书架排序偏好 M6 已落 `core:data` 的 `LibraryPrefs`，设置页与书架 SortMenu 同源读写同一 DataStore 流，天然双向同步、避免双存储不一致与同步代码（M7 落地时的实际选型修订；MuPDF 边界不受影响）
- `core:document` 定义抽象（依赖 Compose，但不依赖 MuPDF）：
  - `DocumentEngine`：工厂，`fun open(path: String, fileType: String, password: String? = null): Document`，纯 Kotlin 接口
  - `Document`：文档句柄，`val pageCount: Int`（页码式有效；流式重排文档返回 -1）/ `val reflowable: Boolean get() = false`（M9：流式重排格式如 epub 返回 true，provider/渲染层据此选重排+CFI 路径、VM 据此豁免 pageCount 回写）/ `fun gotoPage(page: Int)` / `suspend fun pageSize(page: Int): Size`（页点尺寸，M7 垂直模式布局占位与等比渲染用；实现内部可兼做页面预热）/ `fun drawPage(...)` / `suspend fun outline(): List<OutlineEntry>`（文档大纲/目录，无大纲返回空表）/ `fun close()`，`Closeable`；`ReaderViewModel` 持有此接口而非 native 具体句柄，配置变更时 ViewModel 不销毁、句柄复用，避免 native 资源反复重建
  - `DocumentException`：sealed 异常（`PasswordRequiredException` / `CorruptDocumentException`），`DocumentEngine.open` 抛出，导入验证据此分流「加密→入库标记」与「损坏→拒收」
  - `DocumentViewerProvider`：渲染 Composable 提供者，`@Composable fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit)` + `suspend fun thumbnail(doc: Document): ImageBitmap`
  - `ProgressPayload`：sealed 进度模型，`Page(page: Int, scrollOffsetY: Int)`（页码式：pdf/cbz/xps）/ `Cfi(cfi: String)`（流式：epub）；`ReaderMode` 枚举归属 `core:model`（M7 迁移：偏好层 core:data 需类型化暴露 `Flow<ReaderMode>` 而不依赖 core:document 的 Compose 传递依赖；`core:document` 以 `api(project(":core:model"))` 引用，与 `DocSort` 同处惯例）
- **多绑定路由（核心扩展点）**：自定义 Hilt 限定符 `@FileTypeKey(fileType: String)` + `@IntoMap`，把各引擎的 `DocumentEngine`、`DocumentViewerProvider` 注册成 `Map<String, DocumentEngine>` / `Map<String, DocumentViewerProvider>`；`ReaderViewModel` 注入 `DocumentEngineRouter`（封装两个 Map），按 `doc.fileType` 取对应实现。**加新格式 = 新建 `feature:viewer-xxx` + 注册 `@FileTypeKey("xxx")`，reader/库表/schema 不改。**
- **注入与调用细节**：Hilt 不能直接注入 `@Composable` 函数，故 `DocumentViewerProvider` 是普通接口、方法带 `@Composable` 注解；`ReaderScreen` 经 `router.viewerFor(doc.fileType)` 拿到 provider 后在 Compose 上下文调 `provider.Content(doc, ...)` 即可渲染
- 当前有两个引擎实现：`feature:viewer-mupdf`（`MuPdfDocumentEngine` / `MuPdfDocument` / `MuPdfViewerProvider`，以四个 `@FileTypeKey` 复用同一实现注册 `"pdf"`/`"epub"`/`"cbz"`/`"xps"`；fitz 原生解析这四类，`MuPdfDocumentEngine.open` 以 `FileTypes.magicOf(fileType)`（规范 MIME，如 `application/epub+zip`）作 fitz 打开 magic（规避裸扩展名的识别不确定性，S0-4）；epub 进度锚点经 `MuPDFCore.pageToBookmark/bookmarkToPage`，M9 加在 third-party:mupdf 的 MuPDFCore.java 内，属 MuPDF 边界内）与 `feature:viewer-txt`（`TxtDocumentEngine` / `TxtDocument` / `TxtViewerProvider`，`@FileTypeKey("txt")`，**纯 Compose 文本分页、不依赖 MuPDF**——M9-F 用它验证「加新格式 = 新模块 + 两个 @IntoMap 绑定，reader/library/schema 一行不改」的扩展点承诺）。格式契约（扩展名/mime/magic 归一、导入白名单、默认进度类型）集中在 `core:document` 的 `FileTypes`；换引擎或加格式只动 viewer-* 与 `DocumentEngineModule`
- `core:model` 是纯 Kotlin，被 `core:data`（加 Room 注解的 Entity）和 feature 层共用，避免 UI 层直接碰 Entity

### 为何这样拆

| 拆分 | 收益 | 取舍 |
|---|---|---|
| 不设 `core:designsystem` | 主题实际落在 `app`（`MainActivity` 动态 colorScheme + `values/themes.xml` DayNight，见 docs/ui-design.md「主题（M7）」），避免无收益的模块膨胀 | — |
| core 按职责拆成 model/data/storage/document/common（不设大杂烩 core:util） | 职责清晰，feature:library 不引入 MuPDF | 模块数增加，构建配置略多 |
| `feature:reader` 拆出 `feature:viewer-mupdf` | 渲染桥接经 `core:document` 接口 + Map 多绑定注入 reader，viewer 对外只暴露接口，加格式/换引擎只动 viewer-* | `core:document` 多一个 `DocumentViewerProvider` Composable 抽象 + `@FileTypeKey` Map 路由，略有复杂度 |
| `feature:foldermgmt` **合并进** `feature:library` | 目录管理是书架一部分，强耦合，拆了反而跨模块传数据 | — |
| 不单建 `domain` 模块 | 纯本地无复杂业务，模型放 `core:model` 即可 | 避免过度工程 |

## MuPDF 集成方式

1. `git submodule add https://github.com/czc70159407/mupdf-android-viewer.git third-party/mupdf-android-viewer`——**自有 fork**（2026-10-20 起替换上游直连 + patches 机制）：`master` 跟随上游 `ArtifexSoftware/mupdf-android-viewer`，本地两个补丁 commit（`315ba6c` epub 进度锚点 pageToBookmark/bookmarkToPage、`a5496f1` layout 异常保护）维护在 `myreader-patches` 分支，gitlink 锁定 `a5496f1`（补丁基线 `8407044` = 1.28.2a，**与 fitz AAR 1.28.2 匹配**；fork `master` 已在上游 1.28.5 `f2f7563`，勿单方面 rebase——升 viewer 必须同步升 fitz AAR，属独立引擎升级任务）。同步官方更新：fetch upstream → rebase `myreader-patches` → push fork（`--force-with-lease`）→ 主仓 gitlink 前移
2. `settings.gradle.kts`：
   ```kotlin
   include(":third-party:mupdf")
   project(":third-party:mupdf").projectDir =
       file("third-party/mupdf-android-viewer/lib")
   ```
3. `:feature:viewer-mupdf` 中 `implementation(project(":third-party:mupdf"))`（唯一依赖 MuPDF 的模块；按引擎拆分命名，未来 `feature:viewer-txt` 等并列）
4. **fitz（JNI/native 层）走官方 Maven 预编译构件** `com.artifex.mupdf:fitz:1.28.2`（仓库 `https://maven.ghostscript.com/`，已在 settings.gradle.kts 注册并按 `com.artifex.mupdf` group 过滤）。lib 的 build.gradle 自带双路径：`jni/` 子模块存在→源码编译（需 NDK），不存在→Maven 分支——**因此 viewer 的嵌套子模块 `jni/`（mupdf-android-fitz）刻意保持未初始化**；clone 后只执行 `git submodule update --init`（**勿加 `--recursive`**：递归初始化 jni 会使 lib 走源码分支，且 `:jni` 未挂载而构建失败）
5. fitz AAR 含全 4 ABI（arm64-v8a / armeabi-v7a / x86 / x86_64，约 20.6MB）；App 打包按 ABI 策略裁剪：**debug 保留 `x86_64`（模拟器调试），release 限 `arm64-v8a` + `armeabi-v7a`**
6. **AGPL 合规**：README + About 页注明 MuPDF 来源 + AGPL 全文链接；APP 整体开源仓库；fitz 官方构件 pom 本身声明 AGPL，满足许可通告
7. 若未来需改 MuPDF native 层（v1 无此需求）：初始化 `jni/` 递归子模块 + 安装 NDK + `include(":jni")` 挂载 `third-party/mupdf-android-viewer/jni`，lib 自动切源码分支

> MuPDF 核心 API（1.28.2 实测：构造器、渲染方法、类名）见 docs/ui-design.md「MuPDF 在 Compose 中的集成」末尾备注——API 细节归属 UI 分册，此处不重复维护。

## 依赖项清单（gradle/libs.versions.toml）

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
composeBom = "2024.10.00"
activityCompose = "1.9.3"        # 不在 Compose BOM 内，须单独声明（setContent 必需）
hilt = "2.52"
room = "2.6.1"
navCompose = "2.8.3"
coroutines = "1.9.0"
lifecycle = "2.8.6"
documentfile = "1.0.1"
datastore = "1.1.1"
junit = "4.13.2"
androidxTestExt = "1.2.1"
androidxTestCore = "1.6.1"
robolectric = "4.13"
turbine = "1.1.0"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navCompose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }   # 纯 JVM 模块（core:common）用
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
javax-inject = { group = "javax.inject", name = "javax.inject", version = "1" }   # @FileTypeKey 限定符契约（Hilt 多绑定要求，微小纯规范包）
dagger = { group = "com.google.dagger", name = "dagger", version.ref = "hilt" }   # @MapKey（@FileTypeKey 作为 IntoMap 自定义 key 的元注解契约）
documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }   # 书架排序偏好（M6）：core:data + app DI + feature:library 测试
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.7.3" }   # M8 备份快照 + M9 EpubProgressCodec（cfi 进度 payload）序列化；core:data 与 core:document 均引入（类型安全；Kotlin 2.0.21 兼容；AGPL 兼容，无闭源风险）
# ---- 测试（对应「测试策略」各层）----
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }        # core:data 在 JVM 跑 Room inMemory
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }                   # Flow 断言
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }                   # 版本随 BOM
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.27" }
room = { id = "androidx.room", version.ref = "room" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }   # M8 备份 JSON 序列化（与 kotlin-android 同版本对齐）
detekt = { id = "io.gitlab.arturbosch.detekt", version = "1.23.8" }     # T7 静态检查（构建期工具，非运行时依赖；第一轮 ignoreFailures 报告不阻断）
kover = { id = "org.jetbrains.kotlinx.kover", version = "0.9.11" }     # T7 覆盖率报告（构建期工具）
```

> Kotlin 2.0 起用 compose-compiler 插件，不再手写 compose-compiler 版本。`activity-compose` 不在 Compose BOM 内，需单独声明版本。`accompanist-permissions` 不引入：SAF `OpenDocument` 与内部存储均无需运行时权限。MuPDF 官方不发布到 Maven Central，社区镜像版本陈旧且可能违反 AGPL 通告要求，源码子模块是最稳妥路径。以上为撰写时稳定档，M0 落地前统一刷新到当时最新稳定版并核对与 Gradle 8.14/JDK 21 兼容。
