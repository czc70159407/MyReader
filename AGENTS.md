# MyReader 安卓 PDF 阅读器 APP（Agent 开发指南）

> 本文档是本仓库唯一事实源，面向 agent 驱动开发：任何会话先读「当前状态」与「工作规则」，再按需查阅后续参考章节。

## 当前状态（会话开始必读 / 里程碑完成必更新）

- **阶段**：M1/M2 均已完成——数据层就绪（4 个 core 模块 + Room/Hilt + LibraryRepository + 31 单测 + schema 1.json）；M1 真机验收通过（Android 16 真机截图确认 countPages=3 + 首页渲染）
- **下一步**：M3 导入与书架（SAF 导入 + 复制/hash 查重/打开验证/缩略图 + LibraryScreen）
- **环境**：Windows / PowerShell 5 / JDK 21 / Gradle 8.14；Android SDK 位于 `D:\develop\Android\Sdk`（cmdline-tools + platform-tools + platforms android-33/35 + build-tools 35.0.0，`local.properties` 已指向，`ANDROID_HOME` 用户级已设）；git 位于 `D:\develop\Git`（不在 PATH，需全路径或临时加 PATH）；gradle 命令一律 `.\gradlew.bat`
- **已知卡点**：①Java TLS 访问 services.gradle.org 被干扰（已预置 `D:\develop\gradle-repository\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9` 缓存绕过，勿删该目录）；②M1 上游已查证解卡：仓库在 GitHub（`ArtifexSoftware/mupdf-android-viewer`），fitz 官方 Maven `maven.ghostscript.com` 可达且 AAR 含全 4 ABI——NDK 与 native 源码编译不再需要
- **M0 验收证据**：`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 53s`（35 tasks），产物 `app/build/outputs/apk/debug/app-debug.apk`（9.01MB）；真机启动显示 Hello 待用户目视确认（本环境无设备/模拟器）
- **M1 验收证据**：`.\gradlew.bat :third-party:mupdf:assembleDebug` → `BUILD SUCCESSFUL in 21s`（fitz 1.28.2 官方 Maven 构件，免 NDK）；`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 26s`，APK 31.1MB 含 `lib/arm64-v8a/libmupdf_java.so`（10.3MB）+ `lib/x86_64/libmupdf_java.so`（10.7MB），ABI 裁剪生效；**真机验收通过**（Android 16 / SDK 36 真机 `adb install` 成功，截图确认 `countPages() = 3（预期 3）` 且 PDF 首页位图正常渲染）
- **M2 验收证据**：`.\gradlew.bat :core:data:test` → `BUILD SUCCESSFUL`，**31/31 测试通过**（FolderDaoTest 5 / DocumentDaoTest 9 / LibraryRepositoryImplTest 9 / DocumentStorageTest 8，Robolectric+inMemory Room）；`.\gradlew.bat build` → `BUILD SUCCESSFUL in 1m 28s`（315 tasks）；schema 已导出 `core/data/schemas/…/1.json`；经 code-review + security-review 双审查，6 项 minor 问题全部修复（moveFolder 目标校验/路径穿越防护/ID 生成器提取+碰撞重试/LIKE 转义/删死代码/coroutines-core 修正），安全审查零可利用问题

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 脚手架 + git init | 已完成 |
| M1 | MuPDF submodule + Maven fitz | 已完成 |
| M2 | 数据层（Room/Hilt） | 已完成 |
| M3 | 导入与书架 | 未开始 |
| M4 | 阅读器（水平模式） | 未开始 |
| M5 | 目录树与文档管理 | 未开始 |
| M6 | 搜索/排序/最近阅读 | 未开始 |
| M7 | 垂直滚动 + 设置（v1.1） | 未开始 |
| M8 | 备份与导出（v1.1） | 未开始 |
| M9 | 多格式支持（EPUB/CBZ/XPS，v1.2） | 未开始 |

> 状态取值：未开始 / 进行中 / 已完成。标记「已完成」必须附验收证据（命令输出摘要），并同步更新「下一步」。

## 工作规则（硬约束）

1. **MuPDF 边界**：MuPDF 具体类只允许出现在 `feature:viewer-mupdf` 与 `third-party:mupdf`；`feature:reader`/`feature:library` 只依赖 `core:document` 接口。新增格式 = 新建 `feature:viewer-xxx` + `@FileTypeKey`，不改 reader 与 schema
2. **AGPL 合规**：APP 整体开源；README + About 页注明 MuPDF 来源与 AGPL 全文链接；不引入 AGPL 不兼容的闭源组件
3. **Room 迁移纪律**：schema JSON 随仓库提交；禁用 `fallbackToDestructiveMigration`（阅读进度是核心数据）；改 schema 必须写 Migration
4. **完成定义**：代码变更须通过对应里程碑「验收」+ `.\gradlew.bat build` 无错，才算完成；声称完成必须附证据
5. **文档同步**：schema/模块结构/依赖版本/接口签名变更，同一任务内更新本文档对应章节
6. **不过度工程**：不引入本文档未列出的依赖/模块；确需偏离选型，先改「技术选型」章节并写明理由
7. **测试下沉**：纯逻辑放 `core:*` 做 JVM 测试；改 `core:data`/`core:storage` 必须补跑对应单测
8. **命令环境**：Windows PowerShell；clone 后执行 `git submodule update --init`——**勿加 `--recursive`**：viewer 的嵌套 jni 子模块刻意不初始化以走 fitz Maven 预编译分支（见「MuPDF 集成方式」第 4 条）
9. **提交门禁**：所有代码 commit / push 前必须交由用户决策——用户可选择「先 review」或「直接提交推送」；未经用户明确同意，不得执行任何 commit 或 push

## 常用命令（PowerShell）

```powershell
.\gradlew.bat build                              # 全量构建 + 测试
.\gradlew.bat :core:data:test                    # 数据层单测
.\gradlew.bat :app:assembleDebug                 # 装配 APK
.\gradlew.bat :third-party:mupdf:assembleDebug   # 构建 MuPDF viewer 库（fitz 走官方 Maven 预编译，无 native 编译）
```

## Context

项目目录 `D:\develop\code\MyReader` 代码从零开始（当前仅本文档与 `.idea`），需搭建一个安卓 PDF 阅读器 APP。核心需求三点：

1. **本地 PDF 阅读**：从设备存储选取 PDF 导入并阅读
2. **多级目录管理**：树形文件夹结构，可新建/重命名/删除/移动，PDF 可归入任意层级
3. **阅读进度记忆**：每文档记住上次页码，再次打开恢复

环境：JDK 21、Gradle 8.14 已就绪。APP 将开源（MuPDF AGPL 要求）。

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
│   ├── designsystem/                  # :core:designsystem  主题(颜色/字体/形状)+原子组件(AppButton/AppTopBar)
│   ├── model/                        # :core:model         纯领域模型(LibraryItem/Folder data class，无 Room 注解)
│   ├── data/                          # :core:data          Repository 实现 + Room 数据源(实体/DAO/DB)
│   ├── storage/                      # :core:storage       文件复制/删除/缩略图 IO（按 fileType 调度）
│   ├── document/                     # :core:document      DocumentEngine/Document/DocumentViewerProvider 接口(不含 MuPDF 依赖)
│   └── common/                       # :core:common        协程 Dispatcher / 时间工具
├── feature/
│   ├── library/                      # :feature:library    书架+目录树+文件夹增删改移+搜索/排序/文档长按操作(强耦合合并)
│   ├── reader/                       # :feature:reader     阅读器 UI(顶部栏/跳页框/大纲导航/模式切换)，依赖 :feature:viewer-mupdf(经接口)
│   ├── settings/                     # :feature:settings   设置页(阅读模式/主题/书架排序偏好，DataStore 持久化)
│   └── viewer-mupdf/                # :feature:viewer-mupdf  MuPDF 渲染桥接，按引擎拆分以为多格式预留
└── third-party/
    └── mupdf-android-viewer/          # git submodule，其 lib/ 作为 :third-party:mupdf 模块
```

### 模块依赖关系（关键：隔离 MuPDF）

```
app ──► feature:reader ──► core:document (接口：DocumentEngine + Document + DocumentViewerProvider)
   │                          ▲
   ├──► feature:library ──► core:document
   ├──► feature:settings ──► core:designsystem（仅 DataStore 偏好读写，不依赖数据层与 MuPDF）
   │                          │  (app 的 DocumentEngineModule 用 @IntoMap 多绑定，按 fileType 路由)
   └──► feature:viewer-mupdf ──► third-party:mupdf   (MuPDF 边界止于此)
            feature:viewer-mupdf 实现 DocumentEngine/Document/DocumentViewerProvider，@FileTypeKey("pdf") 注册
            feature:reader 不依赖任何 viewer-*，经 core:document 接口 + DI Map 按 doc.fileType 路由到对应引擎
   core:data ──► core:storage   (独立链路)
```

- `feature:reader`、`feature:library` 只依赖 `core:document`（接口），**不传递依赖 MuPDF**；MuPDF 边界止于 `feature:viewer-mupdf`，`reader` 不直接 `implementation(project(":feature:viewer-mupdf"))`
- `core:document` 定义抽象（依赖 Compose，但不依赖 MuPDF）：
  - `DocumentEngine`：工厂，`fun open(path: String, fileType: String, password: String? = null): Document`，纯 Kotlin 接口
  - `Document`：文档句柄，`val pageCount: Int`（页码式有效；流式重排文档返回 -1）/ `fun gotoPage(page: Int)` / `fun drawPage(...)` / `suspend fun outline(): List<OutlineEntry>`（文档大纲/目录，无大纲返回空表）/ `fun close()`，`Closeable`；`ReaderViewModel` 持有此接口而非 native 具体句柄，配置变更时 ViewModel 不销毁、句柄复用，避免 native 资源反复重建
  - `DocumentException`：sealed 异常（`PasswordRequiredException` / `CorruptDocumentException`），`DocumentEngine.open` 抛出，导入验证据此分流「加密→入库标记」与「损坏→拒收」
  - `DocumentViewerProvider`：渲染 Composable 提供者，`@Composable fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit)` + `suspend fun thumbnail(doc: Document): ImageBitmap`
  - `ProgressPayload`：sealed 进度模型，`Page(page: Int, scrollOffsetY: Int)`（页码式：pdf/cbz/xps）/ `Cfi(cfi: String)`（流式：epub）；`ReaderMode` 枚举上移至 `core:document`（避免反向依赖 feature）
- **多绑定路由（核心扩展点）**：自定义 Hilt 限定符 `@FileTypeKey(fileType: String)` + `@IntoMap`，把各引擎的 `DocumentEngine`、`DocumentViewerProvider` 注册成 `Map<String, DocumentEngine>` / `Map<String, DocumentViewerProvider>`；`ReaderViewModel` 注入 `DocumentEngineRouter`（封装两个 Map），按 `doc.fileType` 取对应实现。**加新格式 = 新建 `feature:viewer-xxx` + 注册 `@FileTypeKey("xxx")`，reader/库表/schema 不改。**
- **注入与调用细节**：Hilt 不能直接注入 `@Composable` 函数，故 `DocumentViewerProvider` 是普通接口、方法带 `@Composable` 注解；`ReaderScreen` 经 `router.viewerFor(doc.fileType)` 拿到 provider 后在 Compose 上下文调 `provider.Content(doc, ...)` 即可渲染
- 当前仅 `feature:viewer-mupdf` 一个引擎实现（`MuPdfDocumentEngine` / `MuPdfDocument` / `MuPdfViewerProvider`，`@FileTypeKey("pdf")` 注册；`MuPdfDocumentEngine` 把 MuPDF 的密码/损坏 native 异常映射为 `DocumentException` 子类，`MuPdfDocument` 转发大纲读取；MuPDF 实际也覆盖 epub/cbz/xps，v1 先只开放 pdf 导入）；换引擎或加格式只动 viewer-* 与 `DocumentEngineModule`，`feature:reader` 一行不改
- `core:model` 是纯 Kotlin，被 `core:data`（加 Room 注解的 Entity）和 feature 层共用，避免 UI 层直接碰 Entity

### 为何这样拆

| 拆分 | 收益 | 取舍 |
|---|---|---|
| `core:designsystem` 独立 | feature 只依赖 designsystem 拿统一视觉，主题改动不牵连组件 | — |
| core 按职责拆成 model/data/storage/document/common（不设大杂烩 core:util） | 职责清晰，feature:library 不引入 MuPDF | 模块数增加，构建配置略多 |
| `feature:reader` 拆出 `feature:viewer-mupdf` | 渲染桥接经 `core:document` 接口 + Map 多绑定注入 reader，viewer 对外只暴露接口，加格式/换引擎只动 viewer-* | `core:document` 多一个 `DocumentViewerProvider` Composable 抽象 + `@FileTypeKey` Map 路由，略有复杂度 |
| `feature:foldermgmt` **合并进** `feature:library` | 目录管理是书架一部分，强耦合，拆了反而跨模块传数据 | — |
| 不单建 `domain` 模块 | 纯本地无复杂业务，模型放 `core:model` 即可 | 避免过度工程 |

## MuPDF 集成方式

1. `git submodule add https://github.com/ArtifexSoftware/mupdf-android-viewer.git third-party/mupdf-android-viewer`，锁定固定 commit（当前 `8407044` = 1.28.2a，1.28.x 发布线；上游已从旧 cgit 迁至 GitHub）；更新 submodule = 显式 checkout 新 commit 后提交
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

> MuPDF 核心 API（在 `lib/` 模块，包名 `com.artifex.mupdf.viewer`）：
> - `MuPDFCore`：`countPages()` / `drawPage(Bitmap, sizeX, sizeY, ...)` / `gotoPage(page)` / 大纲读取（返回标题+目标页结构，方法名以 submodule 源码为准）
> - `MuPDFReaderView(context, core)`：已内置瓦片渲染 + BitmapPool，暴露 `setViewListener`（`onPageChanged` 回调）、`setDisplayedViewIndex(page)` 跳页、`getDisplayedViewIndex()` 当前页
>
> 实际类名以 submodule 内 `lib/` 源码为准（MuPDF 版本间有微调），集成时先 `grep "class MuPDFReaderView"` 确认。

## 数据层设计

### Room Schema

```kotlin
// core/data/.../db/entity/FolderEntity.kt
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Long,            // ULID/雪花，避免自增冲突便于移动
    val parentId: Long?,                // 自引用，null = 根
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

// core/data/.../db/entity/DocumentEntity.kt
@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.SET_NULL    // 删目录后文档回到根
    )],
    indices = [Index("folderId"), Index("lastReadAt"), Index("fileType"), Index("contentHash")]
)
data class DocumentEntity(
    @PrimaryKey val id: Long,
    val folderId: Long?,
    val title: String,
    val relPath: String,                 // 相对 filesDir/library/，扁平 "uuid.<ext>"；移动文档不改此字段
    val fileType: String,               // "pdf"/"epub"/"cbz"...，路由引擎 + 书架图标 + 导入过滤
    val mimeType: String?,              // 原始 mime，备查
    val sourceUri: String?,             // 原始 SAF Uri，仅记录来源不依赖
    val fileSize: Long,
    val contentHash: String? = null,     // SHA-256，导入复制时流式计算，重复导入检测用
    val needsPassword: Boolean = false,  // 导入验证为加密文档 → true；解密成功后清除
    val pageCount: Int = -1,             // 导入验证成功时填充；-1 未知（含待解密）；-2 打开损坏标记；流式(EPUB)恒为 -1
    val progressType: String = "page",  // "page"(页码式) / "cfi"(流式)，决定读哪个进度字段
    val currentPage: Int = 0,           // 进度（progressType="page" 时有效）
    val scrollOffsetY: Int = 0,         // 页码式垂直模式额外偏移
    val progressPayload: String? = null, // 流式进度 JSON（progressType="cfi" 时存 CFI 串等）
    val thumbnailRelPath: String?,
    val lastReadAt: Long?,
    val addedAt: Long,
)
```

### DAO 关键查询

```kotlin
@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name")  // 根级传 null，必须 IS（= NULL 恒为假）
    fun children(parentId: Long?): Flow<List<FolderEntity>>

    @Query("""
        WITH RECURSIVE d AS (
          SELECT * FROM folders WHERE id = :rootId
          UNION ALL SELECT f.* FROM folders f JOIN d ON f.parentId = d.id
        )
        SELECT * FROM d
    """)
    suspend fun subtree(rootId: Long): List<FolderEntity>   // 移动/删除/循环校验用

    @Insert suspend fun insert(f: FolderEntity): Long
    @Update suspend fun rename(f: FolderEntity)
    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :ts WHERE id = :id")
    suspend fun move(id: Long, newParentId: Long?, ts: Long)
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DocumentDao {
    // Room 无法参数化 ORDER BY：按 DocSort(TITLE/LAST_READ/ADDED/SIZE) 提供具名查询，Repository 按当前偏好分发
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY title COLLATE NOCASE")
    fun docsInByTitle(parentId: Long?): Flow<List<DocumentEntity>>          // null = 根
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY lastReadAt DESC, title COLLATE NOCASE")
    fun docsInByLastRead(parentId: Long?): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY addedAt DESC, title COLLATE NOCASE")
    fun docsInByAdded(parentId: Long?): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY fileSize DESC, title COLLATE NOCASE")
    fun docsInBySize(parentId: Long?): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE title LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE")
    fun search(q: String): Flow<List<DocumentEntity>>       // 全库标题搜索，不限当前文件夹

    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): DocumentEntity?   // 重复导入检测

    @Query("SELECT * FROM documents WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC LIMIT :limit")
    fun recent(limit: Int = 20): Flow<List<DocumentEntity>>

    @Insert suspend fun insert(d: DocumentEntity): Long
    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)             // 仅改展示名，不动 relPath/物理文件
    @Query("UPDATE documents SET needsPassword = :v WHERE id = :id")
    suspend fun setNeedsPassword(id: Long, v: Boolean)
    @Query("UPDATE documents SET currentPage = :page, scrollOffsetY = :offset, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, offset: Int, ts: Long)   // 页码式（progressType="page"）
    @Query("UPDATE documents SET progressPayload = :payload, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgressPayload(id: Long, payload: String, ts: Long)  // 流式（progressType="cfi"）
    @Query("UPDATE documents SET pageCount = :n WHERE id = :id")
    suspend fun setPageCount(id: Long, n: Int)
    @Query("UPDATE documents SET folderId = :newFolderId WHERE id = :id")
    suspend fun move(id: Long, newFolderId: Long?)
    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}
```

- `@Database(..., version = 1, exportSchema = true)`，schema JSON 提交进仓库 `core/data/schemas/` 便于后续迁移
- 进度表不独立：单文档单进度、无历史回溯需求，单独表只带来 JOIN 与冗余更新

### 删除目录策略（级联）

`folders` 表的 `parentId` **无自引用外键**（SQLite 对自引用 FK 处理复杂且仅约束存在性、不防循环），故删除由业务层 `LibraryRepository` 编排，遵循「禁止裸删非空目录」：

1. 删文件夹 `F` 时，先 `FolderDao.subtree(F.id)` 取 `F` 及全部后代目录
2. 一个 DB 事务内：
   - 将「`F` 及所有后代目录下」的文档 `folderId` 批量置 `null`（文档回根级，**保留文档**；物理 PDF 因扁平存储无需移动）
   - 按「子目录优先、父目录后删」顺序删除后代目录及 `F` 本身记录（避免 `parentId` 悬空成孤儿）
3. 事务提交成功即完成（无物理文件操作）；`sourceUri` 仅备查不依赖
4. 移动文件夹同理：校验循环后只改 `parentId`，不动物理文件

> **与 `DocumentEntity` 外键 `onDelete = SET_NULL` 的关系**：该 FK 仅作兜底安全网（万一业务层漏处理直接删 folder，DB 自动把直接子文档 `folderId` 置 null，不致崩溃）。正常路径走业务层显式批量置 null，便于在单事务内统一编排「后代文档回根 + 子目录先于父删」，不依赖 FK 副作用的隐式顺序。`folders.parentId` 无自引用 FK，故子目录删除顺序必须业务层保证（子优先于父），否则 `parentId` 悬空成孤儿。

### PDF 文件存储策略（取舍结论）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 复制到 `filesDir/library/` | 无权限、稳定、删除可控 | 占双倍空间；卸载/换机即丢，跨设备迁移需导出/备份（M8） | **采用** |
| 持久化 SAF Uri | 省空间 | 权限易丢失、Uri 跨重启偶发失败、缩略图需反复打开远端 | 不采用 |

实现：
- 导入时 `contentResolver.openInputStream(uri)` 复制到 `File(filesDir, "library/${uuid}.pdf")`，DB 存 `relPath`
- **`relPath` 扁平化**：物理文件只按文档 UUID 平铺到 `library/`，**不按文件夹建子目录**。文件夹层级仅由 DB 的 `folderId` 表达，与物理路径解耦——这样移动文档只改 `folderId`，不动文件、不改 `relPath`；删除文件夹时也不会残留孤儿物理文件
- 文件名用 UUID 避免重名；`title` 存用户友好名
- 删文档同时删本地 PDF + 缩略图文件：**先在 DB 事务内删记录，事务提交成功后再删物理文件**（DB 失败则文件不动，避免数据丢失；文件删除失败留为孤儿，由后续清理扫描兜底）
- 缩略图：**导入时后台生成**——导入验证的 `open()` 句柄顺带渲染首页（经 `DocumentViewerProvider.thumbnail(doc)`），PNG 存 `filesDir/thumb/<id>.png`；导入时无法生成（如加密文档）则首次打开兜底

## MuPDF 在 Compose 中的集成

### Compose 桥接（AndroidView）

```kotlin
// feature/viewer-mupdf/viewermupdf/MuPdfReaderView.kt（签名示意；viewer 内部可自由使用 MuPDF 具体类，
// 对外只经 MuPdfViewerProvider.Content(doc: Document) 暴露，内部 cast 为 MuPdfDocument 取出 MuPDFCore）
@Composable
fun MuPdfReaderView(
    core: MuPDFCore,
    initialProgress: ProgressPayload.Page,
    mode: ReaderMode,                          // HORIZONTAL / VERTICAL
    onProgressChanged: (ProgressPayload.Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<MuPDFReaderView?>(null) }
    AndroidView(
        factory = { ctx ->
            MuPDFReaderView(ctx, core).apply {
                setDisplayedViewIndex(initialProgress.page)
                setViewListener(object : ViewListener {
                    override fun onPageChanged(page: Int, offset: Int) {
                        onProgressChanged(ProgressPayload.Page(page, offset))
                    }
                })
            }.also { viewRef.value = it }
        },
        update = { v -> /* 模式切换、跳页差分 */ },
        modifier = modifier,
    )
    DisposableEffect(core) {
        onDispose { viewRef.value?.releaseBitmaps() }
    }
}
```

### 渲染与内存策略

- **优先用 MuPDF 内置瓦片渲染**：`MuPDFReaderView` 内部基于 `TileView` + `BitmapPool`，按视口预渲染相邻页、远页降级。直接用，不要自己 draw Bitmap
- 限制最大 Bitmap 内存：`BitmapPool` 约 24MB，超出回收远页
- 超大 PDF（>200 页/300MB）：`MuPDFCore` 默认只加载页数索引，本身就 OOM 友好；水平模式预渲染窗口收到 ±1 页
- 垂直连续滚动模式（v1.1）：`LazyColumn` 渲染每页一个 `Image`，每页 Bitmap 由 `MuPDFCore.drawPage(...)` 在 `Dispatchers.Default` 异步生成 + `LruCache` 缓存（按页号，限制 8 页），`onScrolled` 滚出可视区时 `bitmap.recycle()`
- **配置变更（横竖屏/键盘）**：阅读页 Activity 设 `android:configChanges="orientation|screenSize|keyboardHidden"`，自行处理配置变更，**避免 Activity 重建导致文档句柄（底层 `MuPDFCore` 的 JNI native 句柄）销毁重建**；句柄以 `Document` 接口形式由 `ReaderViewModel` 持有（reader 只见接口，底层是 `MuPdfDocument` 包装的 `MuPDFCore`），配置变更时 ViewModel 不销毁、句柄复用，`MuPdfReaderView` 在 `update` 块按需重测布局
- **native 资源释放**：`Document.close()` 由 `MuPdfDocument` 转发到底层 `MuPDFCore` 的 native 释放（`destroy()`/`onDestroy`，方法名以 submodule 源码为准）并 `releaseBitmaps()`；`DisposableEffect` 与 `ReaderViewModel.onCleared()` 都触发 `close()`，防止跨多次进出阅读器累积 native 内存泄漏

### 阅读模式选择

| 模式 | 适合 | 进度记忆 | 性能 | 默认 |
|---|---|---|---|---|
| 水平分页 | 单页/双页书籍式 | 页码即可，简单 | MuPDF 内置最优 | **默认** |
| 垂直连续滚动 | 长文档/论文/扫描件 | 页码+像素偏移 | 需自管理 Bitmap | 设置可选（v1.1） |

### 跳页与进度获取

- 跳页：`readerView.setDisplayedViewIndex(page)` —— 由 ViewModel 传入上次 `currentPage`
- 当前页：`onPageChanged(page, offset)` 回调 —— 用来回写进度
- 大纲导航：`Document.outline()` 返回 `List<OutlineEntry>`（title/pageIndex/children）；顶部栏「目录」按钮弹出 `OutlineSheet`（ModalBottomSheet，展平缩进列表，复用目录树的扁平渲染模式），点击条目 `setDisplayedViewIndex(entry.pageIndex)`

## UI 层架构

### 路由

```
nav/MyReaderNavHost.kt
routes:
  library/{folderId?}           # 书架/目录树
  reader/{documentId}           # 阅读器
  folderPicker/{documentId?}    # 选/建目录用于移动或导入
  settings                      # 阅读模式/主题/书架排序等偏好（DataStore 持久化）
```

### 目录树展示（关键）

**采用展平为带缩进列表**（不递归嵌套 Composable）：
- 递归组件会让 Compose 重组时栈过深，且无法复用 `LazyColumn` 的回收，大目录会卡
- 用 `FolderTreeState` 维护展开状态，`flat` 输出 `List<FolderNode>`（每节点带 `depth`）
- `LazyColumn` 按 `depth` 加左 padding
- 长按弹菜单：重命名/删除/移动/新建子目录
- `moveFolder` 前用 `subtree()` 校验新父目录不在当前子树内（防循环，FK 只约束存在性不防循环）

```kotlin
class FolderTreeState(private val repo: LibraryRepository) {   // 仓库统一为 LibraryRepository（目录+文档编排一体，不单设 FolderRepository）
    val expanded: SnapshotStateMap<Long, Boolean> = mutableStateMapOf()
    val flat: StateFlow<List<FolderNode>> = …   // combine(rootFlow, expanded)
    fun toggle(id: Long)
}

@Composable
fun FolderTreeList(
    state: FolderTreeState,
    onFolderClick: (Long?) -> Unit,
    onFolderLongPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
)
```

### 搜索、排序与文档操作

- **搜索**：`LibraryScreen` 顶部搜索框 → `DocumentDao.search(q)`（`title LIKE`，**全库匹配不限当前文件夹**），结果复用文档条目 UI，点击直达阅读器
- **排序**：`DocSort` 枚举（`TITLE` / `LAST_READ` / `ADDED` / `SIZE`）放 `core:model`，当前值存 DataStore；书架顶部切换，`LibraryRepository` 按 `DocSort` 分发到 `docsInBy*` 具名查询（Room 无法参数化 `ORDER BY`），默认 `TITLE`
- **文档长按菜单**：重命名（`DocumentDao.rename`，仅改 `title` 不动 `relPath`/物理文件）/ 移动（复用 `folderPicker` 路由）/ 删除——与目录树操作对称

### 导入流程

1. `LibraryScreen` 工具栏"+"按钮 → `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`，mime 数组按已注册引擎的 `fileType` 聚合（如 `application/pdf`, `application/epub+zip`, `image/vnd.comicbook`），支持多选
2. 选中后弹 `FolderPickerDialog`（默认当前目录，可选新建子目录）
3. `LibraryViewModel.import(uris, targetFolderId)` 在 `Dispatchers.IO` 逐个处理：
   - 复制到 `filesDir/library/<uuid>.<ext>`（扁平，不建文件夹子目录；扩展名取自 Uri 或 mime 推断），复制时**流式计算 SHA-256 `contentHash`**
   - **重复检测**：`DocumentDao.findByHash` 命中 → 弹窗「已存在《title》，是否仍导入？」；用户跳过则删刚复制的文件，继续下一个
   - **打开验证**（异常分流见 `DocumentException`）：
     - `engine.open()` 成功 → 句柄顺带读 `pageCount`、渲染首页生成缩略图，然后 `close()`
     - 抛 `PasswordRequiredException` → 照常入库，置 `needsPassword = true`（暂无 pageCount/缩略图）
     - 抛 `CorruptDocumentException` 等 → 删半成品文件、不入库，提示「无法读取」
   - 由 mime/扩展名解析 `fileType`，插入 `DocumentEntity`（`pageCount` 已知则填否则 -1，`progressType` 默认按类型：页码式 "page"、流式 "cfi"）
   - 复制/插入失败的 Uri 回滚该条（事务 + 删半成品文件）
4. 完成后刷新当前目录列表
5. 首次打开兜底：`pageCount == -1` 或缺缩略图时（如加密文档解密成功后），`ReaderViewModel` 经 `DocumentEngineRouter.engineFor(doc.fileType).open(...)` 补齐 `pageCount`、清 `needsPassword` 并触发缩略图生成

## 进度记忆实现时机（防频繁写库）

1. `ReaderViewModel` 持有 `MutableStateFlow<ProgressPayload>`（`Page(page, offset)` 或 `Cfi(cfi)`）
2. `DocumentViewerProvider.Content` 的 `onProgressChanged` 回调更新该 StateFlow（引擎无关，统一类型）
3. 防抖写库（按 `progressType` 分发）：
   ```kotlin
   progressFlow
       .debounce(1500)                     // 进度变化防抖 1.5s
       .drop(1)
       .onEach { p -> repo.saveProgress(docId, p) }  // Page→updateProgress，Cfi→updateProgressPayload
       .launchIn(viewModelScope)
   ```
4. **保底写入**：`Lifecycle.Event.ON_PAUSE` 和 `BackHandler` 触发时立即 `repo.saveProgress(docId, current)` 并 `await()`，保证离开时进度已落盘
5. `pageCount` 只在首次（`-1`→实际值，页码式）写一次，不进入防抖流；流式文档跳过

```
进度变化 → StateFlow.update  ─debounce 1.5s─→  saveProgress（按 progressType 分发）
退出    → onPause 回调       ─立即写库─→       await → finish
```

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
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
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
```

> Kotlin 2.0 起用 compose-compiler 插件，不再手写 compose-compiler 版本。`activity-compose` 不在 Compose BOM 内，需单独声明版本。`accompanist-permissions` 不引入：SAF `OpenDocument` 与内部存储均无需运行时权限。MuPDF 官方不发布到 Maven Central，社区镜像版本陈旧且可能违反 AGPL 通告要求，源码子模块是最稳妥路径。以上为撰写时稳定档，M0 落地前统一刷新到当时最新稳定版并核对与 Gradle 8.14/JDK 21 兼容。

## 关键风险与对策

| 风险 | 对策 |
|---|---|
| 大文件 OOM | 依赖 MuPDF BitmapPool；水平模式预渲染窗口 ±1 页；垂直模式自管理 LRU(8页)+recycle；Application `onTrimMemory(MODERATE)` 调 `releaseBitmaps()` |
| MuPDF so 体积 | fitz AAR 官方已 strip；App 打包 `ndk.abiFilters` 裁剪——debug 保留 x86_64（模拟器调试），release 限 arm64-v8a + armeabi-v7a；Play Bundle 自动 per-ABI |
| SAF 权限丢失 | 采用"复制到内部存储"方案，导入后即与 Uri 解耦；DB 仅存 `sourceUri` 备查不依赖 |
| Compose 中 AndroidView 重组性能 | `AndroidView` 的 `factory` 只创建一次；模式/页码变化走 `update` 块做差分；`core` 生命周期用 `DisposableEffect` 管理，与 Document 绑定 |
| 树形目录循环引用 | 业务层 `moveFolder` 前用 `subtree()` 检查新父目录不是当前子树成员；FK 仅约束存在性不防循环 |
| 删除非空目录 | 见「删除目录策略」小节：事务内后代文档 `folderId` 置 null 回根 + 子目录先于父删，禁止裸删致 `parentId` 悬空（自引用无 FK 约束） |
| Room 递归 CTE 性能 | 深度通常 <5，CTE 走索引足够；全树列表用一次性 `subtree()` 缓存到内存树 |
| MuPDF API 版本漂移 | submodule 锁定到特定 commit；`:feature:viewer-mupdf` 实现 `core:document` 的 `DocumentEngine` + `Document` + `DocumentViewerProvider` 接口（`MuPdfDocumentEngine.kt` / `MuPdfDocument.kt` / `MuPdfViewerProvider.kt`），隔离 MuPDF 具体类，便于后续替换 |
| AGPL 合规 | README + About 页注明 MuPDF + AGPL 全文链接；APP 整体开源仓库 |
| 后台导入被杀 | v1 同步复制 + loading dialog；大文件 v1.1 用 WorkManager + ForegroundService |

## 错误处理与边界

| 场景 | 处理 |
|---|---|
| 损坏/非 PDF 文件 | 导入时 `engine.open()` 抛 `CorruptDocumentException` → 删半成品文件、不入库，提示「无法读取」；打开时读 `pageCount` 抛错 → 标记 `pageCount = -2`（错误标记，区别于 `-1` 未知），书架条目置灰显示「已损坏」，长按可删除记录或重新导入，不自动重试、不崩溃 |
| 密码保护文档 | 导入时 `engine.open()` 抛 `PasswordRequiredException` → 照常入库置 `needsPassword = true`；打开时先弹密码对话框，`engine.open(path, fileType, password)` 成功后清除标记、补 `pageCount` 与缩略图；密码不持久化（v1 仅会话内） |
| 重复导入 | 导入时 `contentHash` 命中已有记录 → 弹窗「已存在《title》，是否仍导入」，可跳过（跳过则删刚复制的文件） |
| 存储空间不足 | 复制前 `StatFs` 检查 `filesDir` 可用空间 ≥ 文件大小 + 余量，不足则提示并中止，不写半文件 |
| `countPages` 失败 | 进度页码保持 0，UI 提示「打开失败」并返回书架，标记 `pageCount = -2` 不重复尝试（书架引导删除/重导） |
| 文件丢失（`relPath` 指向不存在） | 打开时 `File.exists()` 为 false → 书架该条目置「缺失」标记，引导删除记录或重新导入 |
| 大文件复制中断 | 导入流程事务 + 删半成品回滚；UI 显示进度 + 可取消 |

## 测试策略

| 层 | 方式 | 范围 |
|---|---|---|
| `core:data` | JVM 单元测试（Robolectric） | `Room.inMemoryDatabaseBuilder` 跑 DAO：插/查/移动/进度更新/删非空目录级联回根/递归 CTE `subtree()`/搜索与排序查询/hash 查重 |
| `core:storage` | JVM 单元测试（临时目录） | PDF 复制/删除/缩略图生成、半成品回滚、空间检查 |
| `core:document` 接口 | 仅契约测试 | 接口存在性，实现在 viewer-mupdf |
| `feature:viewer-mupdf` | Android 插桩测试（`androidTest`，需真机/.so） | `MuPdfDocumentEngine.open`（含密码/损坏异常映射）+ `countPages` + `drawPage` + `outline` 对 assets 内样本 PDF；native 句柄 `close()` 无泄漏 |
| `feature:library` / `reader` | Compose UI 测试（`createAndroidComposeRule`） | 导入流程、目录树展开/移动/循环校验拦截、跳页+进度回写、大纲跳页、搜索/排序切换 |
| MuPDF 渲染正确性 | 手工真机回归 | 无参考图难自动化；真机验证首页渲染、翻页、大文件不 OOM、横竖屏切换进度保持 |
| `Repository` | 单元测试（fake DAO） | 业务编排：循环校验、删除编排顺序、导入编排（hash 去重、加密/损坏分流） |

原则：纯逻辑尽量下沉到 `core:*` 做 JVM 测试（快、无需设备）；MuPDF 相关只能真机验证，集中到 `feature:viewer-mupdf` 的 `androidTest`。

## 里程碑与任务分解

> 每个里程碑 = 一个任务单元：完成即更新「当前状态」表并附验收证据。真机项需设备；命令均为 PowerShell。

1. **M0 脚手架**
   - 任务：`git init` + `.gitignore`（`.gradle/`、`build/`、`local.properties`、`keystore.properties`、`.idea/`）+ 首次提交；根 `build.gradle.kts` + `settings.gradle.kts` + `libs.versions.toml` + `:app` 空 MainActivity 显示 Hello；预留 release `signingConfigs` 占位（密钥从本地 `keystore.properties` 读取，不入仓）
   - 验收：`.\gradlew.bat :app:assembleDebug` 成功，app 启动显示 Hello
2. **M1 MuPDF 子模块**
   - 任务：① submodule 引入并锁定 1.28.x commit（上游 GitHub，URL 与锁定值见「MuPDF 集成方式」）；② `:third-party:mupdf` 模块挂载 + fitz 官方 Maven 预编译依赖（免 NDK/native 编译）；③ demo pdf 验证 `MuPDFCore.countPages()` 与首页渲染
   - 验收：`.\gradlew.bat :third-party:mupdf:assembleDebug` 构建通过（fitz AAR 含全 ABI `libmupdf_java.so`）；demo Activity 打开 assets 内测试 PDF，`countPages()` 返回正确页数，能渲染首页
3. **M2 数据层**
   - 任务：Room 实体+DAO+Hilt 注入；写 `LibraryRepository` 与单元测试（`Room.inMemoryDatabaseBuilder`）
   - 验收：`.\gradlew.bat :core:data:test` 通过（插/查/移动/进度更新/删非空目录级联回根/搜索与排序/hash 查重）
4. **M3 导入与书架**
   - 任务：SAF 导入 + 复制 + hash 重复检测 + 打开验证（损坏拒收/加密标记）+ 导入时缩略图 + 插库；LibraryScreen 文档网格/列表展示
   - 验收：真机选 PDF → 出现在书架且有缩略图；`filesDir/library/` 下有副本；同一 PDF 二次导入提示重复；损坏文件拒收；加密 PDF 入库标记后打开弹密码框
5. **M4 阅读器水平模式**
   - 任务：AndroidView 桥接 + 水平分页 + 跳页 + 大纲导航（OutlineSheet）+ 进度回写（含防抖+onPause 保底）
   - 验收：打开 PDF 可翻页；跳到第 N 页退出再进，恢复到第 N 页（核心需求）；带大纲的 PDF「目录」跳页正确
6. **M5 目录树与文档管理**
   - 任务：FolderTreeList + 新建/重命名/删除/移动（含循环校验）；文档长按重命名/移动/删除
   - 验收：新建多级文件夹 → 移动文档进子目录 → 删父目录后文档回根级；移动到自身子树被拦截；文档可重命名
7. **M6 搜索、排序与最近阅读**
   - 任务：全库搜索；`DocSort` 排序切换（DataStore 记忆）；最近阅读列表
   - 验收：关键词命中全库文档（不限当前文件夹）；切换排序立即生效且重启保持；最近阅读按时间排序
8. **M7 垂直滚动模式 + 设置**（v1.1，可选）
   - 任务：`LazyColumn` + `drawPage` + LRU（注意 `MuPDFCore` 非线程安全，并发绘制需串行化）；`feature:settings` 设置页（阅读模式/主题/书架排序偏好，DataStore 持久化）
   - 验收：长文档连续滚动流畅、滚动偏移可恢复；设置页偏好重启后保持
9. **M8 备份与导出**（v1.1，可选）
   - 任务：导出 `library/` 目录 + 数据库 JSON 到用户指定 SAF 目录；导入恢复，解决 `filesDir` 换机/卸载即丢问题
   - 验收：导出 → 卸载重装/换机 → 导入恢复，目录树、文档、进度完整
10. **M9 多格式支持**（v1.2，可选）
   - 任务：`:feature:viewer-mupdf` 内为 epub/cbz/xps 注册 `@FileTypeKey`（同一 MuPDF 引擎多格式复用，MuPDF 核心本就解析这三类）；导入 mime 聚合开放对应类型（`application/epub+zip` / `image/vnd.comicbook` / `application/oxps`）；每格式样本回归——epub 进度为章节+页码（序列化至 `progressPayload`，`progressType="cfi"`），cbz/xps 沿用页码式；书架图标按 `fileType` 区分
   - 验收：三种格式均可导入（缩略图正常）→ 打开 → 退出重进进度恢复；加第四种自研格式（如 TXT）仅新增 `feature:viewer-txt` 模块即可跑通（验证扩展点，条目可选做）
11. **真机回归**（M4 起每个里程碑后执行）：大 PDF（>200 页）不 OOM；横竖屏切换进度保持

## 关键文件清单（落地优先级）

**构建配置（最优先）：**
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

**领域模型（纯 Kotlin，无注解；避免与 `core:document` 的句柄接口 `Document` 同名）：**
- `core/model/.../model/LibraryItem.kt` ← 书架条目元数据，映射 DocumentEntity
- `core/model/.../model/Folder.kt`
- `core/model/.../model/DocSort.kt` ← 书架排序枚举（TITLE/LAST_READ/ADDED/SIZE），DataStore 记忆当前值

**数据层（Repository 实现 + Room）：**
- `core/data/.../db/entity/FolderEntity.kt`
- `core/data/.../db/entity/DocumentEntity.kt`
- `core/data/.../db/dao/FolderDao.kt`
- `core/data/.../db/dao/DocumentDao.kt`
- `core/data/.../db/MyReaderDatabase.kt`
- `core/data/.../repo/LibraryRepository.kt` ← 接口（与 Impl 同模块，feature 依赖此接口而非实现）
- `core/data/.../repo/LibraryRepositoryImpl.kt`

**隔离接口 / 文件 IO：**
- `core/document/.../document/DocumentEngine.kt` ← 抽象工厂接口，feature 依赖此而非 MuPDF
- `core/document/.../document/Document.kt` ← 文档句柄接口（pageCount/gotoPage/drawPage/outline/close），ReaderViewModel 持有此而非 native 具体句柄
- `core/document/.../document/DocumentException.kt` ← sealed：PasswordRequired / CorruptDocument（导入验证分流）
- `core/document/.../document/OutlineEntry.kt` ← 大纲条目（title/pageIndex/children）
- `core/document/.../document/DocumentViewerProvider.kt` ← 渲染 Composable 提供者抽象（@Composable Content + thumbnail）
- `core/document/.../document/ProgressPayload.kt` ← sealed 进度模型（Page / Cfi）
- `core/document/.../document/ReaderMode.kt` ← 枚举上移至此（供 DocumentViewerProvider 接口引用，避免 core 反向依赖 feature）
- `core/document/.../document/FileTypeKey.kt` ← Hilt 多绑定限定符 + DocumentEngineRouter（按 fileType 路由 Map）
- `core/storage/.../storage/DocumentStorage.kt` ← 复制/删除/缩略图 IO（按 fileType 调度）
- `core/common/.../common/DispatcherProvider.kt`

**设计系统：**
- `core/designsystem/.../theme/Color.kt` / `Type.kt` / `Shape.kt` ← 含 light/dark 两套配色；Android 12+ 支持 `dynamicColor`（Material You 取壁纸取色），12 以下回落静态品牌色；深色模式随系统 `NightMode` 切换
- `core/designsystem/.../component/AppButton.kt` / `AppTopBar.kt`

**书架 + 目录管理（合并模块）：**
- `feature/library/.../ui/LibraryScreen.kt` ← 文档网格/列表 + 长按菜单(重命名/移动/删除) + 搜索框 + 排序切换
- `feature/library/.../ui/LibraryViewModel.kt`
- `feature/library/.../ui/FolderTreeList.kt`
- `feature/library/.../ui/FolderTreeState.kt`
- `feature/library/.../ui/FolderPickerDialog.kt`
- `feature/library/.../ui/NewFolderDialog.kt`

**阅读器 UI（不含渲染）：**
- `feature/reader/.../ui/ReaderScreen.kt`
- `feature/reader/.../ui/ReaderViewModel.kt`
- `feature/reader/.../ui/GoToPageDialog.kt`
- `feature/reader/.../ui/OutlineSheet.kt` ← 大纲导航（ModalBottomSheet，展平缩进列表，点击跳页）

**设置（独立 feature，偏好持久化）：**
- `feature/settings/.../ui/SettingsScreen.kt` ← 阅读模式/主题/书架排序偏好
- `feature/settings/.../ui/SettingsViewModel.kt` ← DataStore Preferences 读写

**渲染桥接（按引擎拆分，当前仅 MuPDF；唯一依赖 MuPDF 的 feature）：**
- `feature/viewer-mupdf/.../viewermupdf/MuPdfReaderView.kt` ← AndroidView 桥接（DocumentViewerProvider.Content 的实现体）
- `feature/viewer-mupdf/.../viewermupdf/MuPdfDocumentEngine.kt` ← DocumentEngine 实现，`@FileTypeKey("pdf")`
- `feature/viewer-mupdf/.../viewermupdf/MuPdfDocument.kt` ← Document 实现（包装 MuPDFCore native 句柄，含大纲转发）
- `feature/viewer-mupdf/.../viewermupdf/MuPdfViewerProvider.kt` ← DocumentViewerProvider 实现，`@FileTypeKey("pdf")`

**DI/入口：**
- `app/.../MyReaderApp.kt`（@HiltAndroidApp）
- `app/.../MainActivity.kt`
- `app/.../nav/MyReaderNavHost.kt`
- `app/.../di/DatabaseModule.kt`
- `app/.../di/RepositoryModule.kt`
- `app/.../di/DocumentEngineModule.kt` ← `@IntoMap` 多绑定 DocumentEngine/DocumentViewerProvider by `@FileTypeKey`

**MuPDF（git submodule 引入，无需手写）：**
- `third-party/mupdf-android-viewer/` ← submodule 根，`lib/` 作为 `:third-party:mupdf` 模块

