# 真机验收文件布局（设备侧）

> 红线来源：[AGENTS.md](../AGENTS.md) 工作规则第 10 条。本册是设备侧验收产物的**内部结构、命名与落盘流程**的唯一事实源与当前快照；验收结论与证据状态仍只写 AGENTS.md。

## 1. 唯一根目录

`/sdcard/Download/MyReaderTest/`——夹具与产出同目录。

根目录本身及其禁止范围由 [AGENTS.md](../AGENTS.md) 工作规则第 10 条规定；本册只定义**其内部结构、命名与落盘流程**，不复述红线本体。历史遗留已于 2026-10-29 全部归位（见 §4）。

## 2. 目录结构（2026-10-29 归位后快照）

| 路径 | 内容 | 数量 |
|---|---|---|
| `xml/` | `uiautomator dump` 产物（T1~T5 全流程 + M6/M9 历史 + 本轮 `t5_*`） | 135 |
| `png/` | 验收截图（`s1~s26`、`t0~t4`、`m9_*`、`t5_*` 等） | 76 |
| `db/` | 应用 DB 快照：`dump.db`、`dump.db-wal`、`dump2.db`、`dump2.db-shm`、`dump2.db-wal` | 5 |
| `backup/` | 备份导出包：`myreader-backup.zip`、`_1`、`_2` | 3 |
| `m6/` | M6 验收产物（`demo_test.pdf` + `m6_01~m6_09.png`，原在 `/data/local/tmp`） | 10 |
| `fixtures/` | 全量测试夹具（`epub_test.epub`、`comic_test.cbz`、`xps_test.xps`、`utf8_test.txt`、`txt_test.txt` 等） | 12 |
| `ImportTest/` | 目录导入夹具树：`a1.pdf`、`outline_test.pdf`、`A/a2.txt`、`A/a3.pdf`、`C/epub_test.epub`、`C/note.docx`（`B/` 为刻意空目录） | 6 |
| 根目录 | 常用单文件夹具：`corrupt_test.pdf`、`demo_test.pdf`、`encrypted_test.pdf`、`epub_test.epub`、`gbk_test.txt`、`long_test.pdf`、`outline_test.pdf` | 7 |

合计约 20 MB（`du -sk` = 20,307 KB）；`xml/` 3.3 MB、`png/` 15.1 MB 为主要占用。

> 原 `_dup/`（9 个历史重复批次）已于 2026-10-29 核对后删除，详见 §4 末段。
>
> 另：`fixtures/` 与根目录存在**同名不同批次**的夹具（如 `demo_test.pdf` 共三份，MD5 各不相同，仅生成时间戳差异），不影响验收，取舍规则见 §4；如无特殊需要，新增产物直接放对应分类目录，不必再制造副本。

## 3. 命名与归属约定

1. **落盘路径由内容类型决定**：dump → `xml/`；截图 → `png/`；DB 快照 → `db/`；备份包 → `backup/`；夹具 → `fixtures/`（批量导入夹具树另建专用目录如 `ImportTest/`）。
2. **产出文件名带任务前缀**：`<任务号>_<场景>.<ext>`，任务号小写、对应 [design/README.md](design/README.md) 交付表中的 T5 / M10（如 `t5_rename.png`、`m10_bookmark.png`）。
3. **不按任务建目录**：任务靠文件名前缀区分，避免目录爆炸。
4. **临时 vs 留档**：中间 dump、失败截图、一次性探针脚本属临时，收尾即删；分类目录中的定量产物属留档，不随收尾清理。
5. **本地留档**：需要长期保留的证据复制到 `tools/acceptance/verify/`（已被 `.gitignore` 排除，不入库）。
6. **持久性**：`/sdcard/Download/...` 与 `/data/local/tmp` 都**不会因重启自动清理**（后者同样位于 `/data` 分区），验收产物必须显式删除才消失——收尾清理要主动执行，不能指望重启兜底。

## 4. 本次归位记录（2026-10-29）

| 来源 | 去向 | 处理 |
|---|---|---|
| `/sdcard` 根 108 个 `*.xml` | `MyReaderTest/xml/` | 直接迁移 |
| `/sdcard` 根 51 个 `*.png` | `MyReaderTest/png/` | 直接迁移 |
| `/sdcard` 根 5 个 `*.db*` | `MyReaderTest/db/` | 直接迁移 |
| `/sdcard` 根 3 个 `myreader-backup*.zip` | `MyReaderTest/backup/` | 直接迁移 |
| `/data/local/tmp` 10 项（`demo_test.pdf` + `m6_01~09.png`） | `MyReaderTest/m6/` | 直接迁移（该路径位于 `/data` 分区，重启不会自动清理；但属系统临时目录，不作为长期存档位） |
| `Download/` 根 `corrupt_test.pdf`、`epub_test.epub`、`gbk_test.txt` | `MyReaderTest/` 根 | 无同名冲突，直接迁移 |
| `Download/` 根 4 个同名 PDF + `myreader-backup.zip` | `MyReaderTest/_dup/`（已删，见下） | 同名同尺寸但 MD5 不同（不同生成批次），保留目录内现有版本 |
| `Download/fixtures/` 5 个 | `txt_test.txt` → `fixtures/`；其余 4 个 → `_dup/`（已删，见下） | 同上，`unsupported_test.docx` 本就字节一致 |
| `Download/ImportTest/` | `MyReaderTest/ImportTest/` | 整目录迁移 |
| `Download/MyReader演示.`（1125 B 普通文件） | — | 判定为脚本误建，已删除 |

判定规则：**同名 + 同尺寸 = 同一夹具的不同生成批次**，保留 `MyReaderTest` 内现有版本，来源先入 `_dup/` 待核（该目录已于同日核对后清理，见下）。

### `_dup/` 核对与清理（2026-10-29）

逐对比对（拉取后离线比较，忽略容器时间戳）：

| 文件 | 差异 | 处置 |
|---|---|---|
| `encrypted_test.pdf`、`unsupported_test.docx`、`myreader-backup.zip` | MD5 完全相同（备份包与 `backup/myreader-backup.zip` 同 36,804 B） | 删除 |
| `comic_test.cbz`、`epub_test.epub` | 条目内容一致，仅 zip 条目时间戳不同 | 删除 |
| `demo_test.pdf`、`long_test.pdf`、`outline_test.pdf` | 尺寸一致，差异仅在 `/CreationDate`、`/ModDate`、`/ID` 等元数据 | 删除 |
| `xps_test.xps` | 8 个条目中仅 `[Content_Types].xml` 声明不同（483 vs 496 B，旧版生成器），页面 XAML 一致 | 删除 |

理由：夹具可由 `tools/test_pdfs.py` / `tools/test_docs.py` 重新生成（脚本自带 verify 属性断言），验收判据是页数 / 格式 / 加密 / 大纲层级等属性而非字节。`_dup/` 整目录已删除。

## 5. 后续约定

1. 新增验收脚本一律按 §3 的分类落盘（`tools/acceptance/m9_drive.ps1` 已改为写入 `MyReaderTest/xml/`）。
2. `xml/`、`png/`、`m6/` 中的历史产物属一次性证据；如需腾空间，按任务前缀整批删除，不动 `fixtures/`、`ImportTest/` 与根目录夹具。

## 6. 校验命令（PowerShell）

```powershell
$adb = "D:\develop\Android\Sdk\platform-tools\adb.exe"

# 根目录不应有任何测试文件残留（期望 0）
& $adb shell "ls /sdcard | grep -E '\.(xml|png|db|db-wal|db-shm|zip|pdf|epub|cbz|xps|docx)$' | wc -l"

# 各分类目录数量
& $adb shell "cd /sdcard/Download/MyReaderTest; for d in xml png db backup m6 fixtures; do echo -n `"`$d: `"; ls `$d | wc -l; done"
```
