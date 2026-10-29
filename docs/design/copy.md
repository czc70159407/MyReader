# 文案规范与总表（copy）

> 本文件是**唯一**的用户可见文案事实源。代码中的字符串必须与本节逐字一致；改文案先改本表。实现现状见 [`../ui-design.md`](../ui-design.md)。

## 1. 通用规则

1. 结构：**做了什么 / 为什么 / 下一步**。缺"下一步"的错误提示视为不合格。
2. 禁止技术信息进用户文案：异常类名、堆栈、`Uri`、绝对路径、`fileType` 原始值。
3. 标点：中文全角「：」「，」「。」；按钮文字不加标点；范围用 ` - `（如 `页码（1 - 12）`）。
4. 省略号统一 `…`（U+2026），不用 `...`。
5. 数值与单位间加空格：`12 页`、`3 / 30`（阅读器页码两侧空格为现状，保持一致）。
6. 数量词：文档/目录用「个」，页码用「页」。
7. 书名/目录名包裹：文档用《》，文件夹用「」。
8. 按钮用动词，不用「确定」以外的模糊词；破坏性动作直接写动作名（删除 / 仍导入）。
9. 重名判定**区分大小写**（D7），文案不承诺"不区分大小写"。

## 2. 动作文案（act_*）

| 键 | 文案 | 位置 |
|---|---|---|
| `act_import_doc` | 导入文档（图标 `contentDescription`） | 书架顶栏 |
| `act_import_dir` | 导入目录 | 书架顶栏 |
| `act_new_folder` | 新建文件夹 | 书架顶栏 |
| `act_new_subfolder` | 新建子目录 | 文件夹卡菜单 / picker |
| `act_search` | 搜索 | 书架顶栏 |
| `act_exit_search` | 退出搜索 | 搜索态返回图标 |
| `act_clear_query` | 清空关键词 | 搜索框尾部 |
| `act_sort` | 排序 | 书架顶栏 |
| `act_more` | 更多 | 书架顶栏（D2 收纳菜单入口） |
| `act_current_sort` | 当前排序 | 排序菜单当前项 trailing |
| `act_settings` | 设置 | 书架顶栏 |
| `act_back` | 返回 | 设置/关于/阅读器 |
| `act_back_parent` | 返回上级 | 子目录书架 |
| `act_outline` | 目录 | 阅读器顶栏 |
| `act_goto` | 跳页 | 阅读器顶栏 |
| `act_toggle_mode` | 切换阅读模式 | 阅读器顶栏 |
| `act_bookmark` | 书签（M10 预留） | 阅读器顶栏 |
| `act_rename` | 重命名 | 卡片菜单 |
| `act_move` | 移动 | 卡片菜单 |
| `act_delete` | 删除 | 卡片菜单 / 确认按钮 |
| `act_create` | 创建 | 新建文件夹 |
| `act_confirm` | 确定 | 重命名 / 选择目录 |
| `act_cancel` | 取消 | 全部弹窗 |
| `act_open` | 打开 | 密码框 |
| `act_jump` | 跳转 | 跳页框 |
| `act_import_anyway` | 仍导入 | 重复确认 |
| `act_skip` | 跳过 | 重复确认 |
| `act_got_it` | 知道了 | 汇总/失败提示 |
| `act_back_library` | 返回书架 | 阅读器失败页 |
| `act_retry` | 重试 | 大纲失败（`[已实现 T6]`）；阅读器失败页 `[建议]` |
| `act_cancel_import` | 取消（读屏 CD「取消导入」） | 导入胶囊（T3 已实现） |
| `act_restore_confirm` | 继续恢复 | 恢复二次确认框（T1 已实现） |

## 3. 空态文案（empty_*）

| 键 | 文案 | 场景 |
|---|---|---|
| `empty_library_root` | 书架还是空的\n点右上角 + 导入文档 | 根目录无内容 |
| `empty_folder` | 此文件夹为空 | 子目录无内容 |
| `empty_search` | 未找到匹配文档 | 搜索无结果 |
| `empty_outline` | 本文档没有目录 | 大纲为空 |
| `empty_bookmarks` | 暂无书签（M10 预留） | 书签面板为空 |

规则：空态必须配「下一步」，根空态已含入口指引；其余空态由顶栏动作提供出口（评审时确认可发现性）。

## 4. 加载文案（loading_*）

| 键 | 文案 |
|---|---|
| `loading_import` | 正在导入… |
| `loading_scan_dir` | 正在扫描目录… |
| `loading_import_progress` | 正在导入 {current}/{total}… |
| `loading_outline` | 正在加载目录… |
| `loading_backup` | 处理中…（无阶段文案时） |
| `loading_generic` | 正在加载 | 无可见文案的加载环（书架首帧 / 阅读器打开）读屏说明 |

规则：加载文案只在**可预期等待 > 1s** 的场景出现；进度可量化时必须给 `x/y`。

## 5. 成功反馈（ok_*）

| 键 | 文案 | 级别 |
|---|---|---|
| `ok_folder_created` | 已创建文件夹「{name}」 | Snackbar |
| `ok_folder_renamed` | 已重命名文件夹 | Snackbar |
| `ok_folder_moved` | 已移动文件夹 | Snackbar |
| `ok_folder_deleted` | 已删除文件夹（其中文档已移至根目录） | Snackbar |
| `ok_doc_renamed` | 已重命名文档 | Snackbar |
| `ok_doc_moved` | 已移动文档 | Snackbar |
| `ok_doc_deleted` | 已删除文档 | Snackbar |
| `ok_import_summary` | 导入 {n} 个；{其他项}…（分号连接） | Snackbar |
| `ok_backup_done` | 备份完成 | Snackbar |
| `ok_restore_done` | 已恢复 {documents} 个文档 / {folders} 个目录 | Snackbar |
| `ok_import_summary_title` | 目录导入完成 | 对话框标题 |
| `ok_import_summary_title_multi` | 导入完成（T4 已实现：单文件多选复用） | 对话框标题 |

## 6. 错误文案（err_*）

| 键 | 文案 | 级别 | 现状 |
|---|---|---|---|
| `err_folder_duplicate` | 该目录下已存在同名文件夹 | L3 → `[建议]` L1 | `[已实现]` |
| `err_doc_duplicate` | 该目录下已存在同名文档 | L3 → `[建议]` L1 | `[已实现]` |
| `err_storage_low` | 存储空间不足 | L3 | `[已实现]` |
| `err_unreadable` | 无法读取所选文件 | L3 | `[已实现]` |
| `err_unsupported` | 暂不支持的文件类型 | L3 | `[已实现]` |
| `err_corrupt_open` | 无法读取此文档（可能已损坏） | L2 页面 | `[已实现]` |
| `err_missing_record` | 文档记录不存在，可能已被删除 | L2 页面 | `[已实现]` |
| `err_missing_file` | 文档文件已丢失，可删除记录后重新导入 | L2 页面 | `[已实现]` |
| `err_open_generic` | 打开失败，请返回书架后重试 | L2 页面 | `[已实现 T6]`（异常消息只进日志，不进文案） |
| `err_unsupported_type` | 暂不支持的文件类型 | L2 页面 | `[已实现 T6]`（去掉 `{fileType}` 技术值） |
| `err_pick_dir_permission` | 无法访问所选目录（权限可能已被撤销） | L3 | `[已实现]` |
| `err_move_cycle` | 不能移动到自身或其子目录 | L1 | `[已实现]` |
| `err_subtree_load` | 无法移动 / 读取目录结构失败，请重试 | L2 | `[已实现]` |
| `err_folder_missing` | 目录不存在，请刷新后重试 | L3 | `[已实现 T6]`（去掉内部 id） |
| `err_doc_missing` | 文档不存在，请刷新后重试 | L3 | `[已实现 T6]`（去掉内部 id） |
| `err_create_folder` | 创建失败，请重试 | L3 | `[已实现 T6]`（异常消息不进文案） |
| `err_outline_load` | 目录加载失败 | L1 面板内 | `[已实现 T6]`（含「重试」出口） |
| `err_backup_op` | 备份操作失败，请重试 | L3 | T1 review M12 已固定文案（去路径/类名，细节仅 debug 日志） |
| `err_import_interrupted` | 导入失败，请重试 | L3 | 单文件导入遭未预见异常中断（T4 review H2 兜底） |
| `err_backup_invalid` | 备份文件无法识别或已损坏 | L3 | `[已实现]`（`SettingsViewModel`） |
| `err_generic` | 操作失败，请重试 | L3 | `[已实现 T6]`（异常消息不进文案） |
| `err_pick_dir_empty` | 该目录中没有可导入的文档 | L3 | `[已实现]`；目录内全部为不支持类型时也只报本句（total=0 不弹汇总，T6 已删伪路径明细条目） |
| `err_import_dir` | 目录导入失败，请重试 | L3 | `[已实现 T6]`（异常消息不进文案） |
| `err_nothing_imported` | 未导入任何文档 | L3 | `[已实现]` |
| `err_viewer_empty` | 无可显示的页面 | L2 视图内 | `[已实现]`（MuPDF 视图无页可绘兜底） |
| `err_txt_view` | 无法显示此文档 | L2 视图内 | `[已实现]`（TXT 视图兜底） |
| `err_page_render` | 第 {N} 页渲染失败 | L1 页内 | `[已实现]`（垂直模式单页失败占位，不整篇失败） |

## 7. 表单校验文案（inline_*）

| 键 | 文案 | 触发 |
|---|---|---|
| `inline_search_placeholder` | 搜索全部文档 | 书架搜索框占位（review L20 补登记） |
| `inline_goto_range` | 页码（1 - {pageCount}） | 跳页框 label，常驻显示范围 |
| `inline_goto_invalid` | 请输入 1 - {pageCount} 的页码 | `[已实现 T6]` 非法输入时的 `supportingText` |
| `inline_password_wrong` | 密码错误，请重试 | 提交失败后内联，保留已输入内容 |
| `inline_name_empty` | 名称不能为空 | `[建议]` 失焦或提交时 |
| `inline_name_too_long` | 名称最多 64 个字符 | `[建议]` 输入期 |
| `inline_name_duplicate` | 该目录下已存在同名项目 | `[建议]` 输入期替代 L3 |
| `inline_move_invalid` | 不能移动到自身或其子目录 | picker 选中禁选集内条目时 |

## 8. 对话框正文模板

| 键 | 文案 |
|---|---|
| `dlg_delete_folder` | 将删除文件夹「{name}」及其所有子目录，其中的文档将移至根目录。是否继续？ |
| `dlg_delete_doc` | 将删除《{title}》，物理文件一并删除。是否继续？ |
| `dlg_duplicate_import` | 已存在《{existingTitle}》，是否仍导入同一文件？ |
| `dlg_password_title` | 文档已加密 |
| `dlg_goto_title` | 跳转到页面 |
| `dlg_pick_dir_title` | 选择目录 |
| `dlg_new_folder_title` | 新建文件夹 |
| `dlg_rename_title` | 重命名 |
| `dlg_delete_folder_title` | 删除文件夹 |
| `dlg_delete_doc_title` | 删除文档 |
| `dlg_duplicate_title` | 文档已存在 |
| `dlg_move_failed_title` | 无法移动 |
| `dlg_restore_confirm_title` | 从备份恢复（`[已实现 T1]`） |
| `dlg_restore_confirm_body` | 将合并备份中的目录与文档记录：同名记录以备份为准，备份之外的数据不受影响。是否继续？（不预测数量——为避免显示数字而先解析整包） |

## 9. 导入汇总字段（summary_*）

| 键 | 文案 | 显示条件 |
|---|---|---|
| `summary_total` | 共扫描 {total} 个可导入文档 | 恒显示 |
| `summary_imported` | 导入成功：{n} | n > 0 |
| `summary_password` | 已加密（打开时需密码）：{n} | n > 0 |
| `summary_duplicate` | 已跳过（库中已存在）：{n} | n > 0 |
| `summary_corrupt` | 损坏被拒收：{n} | n > 0 |
| `summary_unsupported` | 暂不支持的文件类型：{n} | n > 0 |
| `summary_too_large` | 超过单文件上限：{n} | n > 0 |
| `summary_failed` | 导入失败：{n} | n > 0 |
| `summary_folders` | 新建目录 / 复用已有目录：{created} / {reused} | 恒显示 |
| `summary_renamed` | 同名自动重命名：{n} | n > 0 |
| `summary_deep` | 超出深度上限未处理：{n} | n > 0 |
| `summary_truncated` | 超出数量上限未处理：{n} | n > 0 |
| `summary_inaccessible` | 无法访问的子目录：{n} | n > 0 |
| `summary_issues_title` | 明细： | issues 非空 |
| `summary_issue_line` | · {path} —— {reason} | issues 非空，≤ 50 条；**{reason} 走白名单**（`[已实现 T6]`：`文件损坏` / `文本过大（>16MB），请使用分卷或压缩版` / `导入失败` / `暂不支持的文件类型` / `库中已存在相同内容，已跳过`；异常消息只进 debug 日志，真机验收曾发现 `无法打开文档: 188029…pdf`） |
| `summary_cancelled` | 已取消，已导入 {n} 个（T3 已实现） | 取消后 |

## 9.1 读屏文案（a11y_*，T5）

整卡朗读模板（点击容器 `clearAndSetSemantics` 单节点组合描述；状态角标 CD 置 null，状态只由本描述承担）：

| 键 | 模板 | 场景 |
|---|---|---|
| `a11y_doc_card` | {title}，{N 页 / 格式}，{需要密码 / 已损坏} | 文档卡；损坏时不追加格式词；加密与损坏同时成立只读加密 |
| `a11y_folder_card` | {name}，文件夹 | 文件夹卡 |
| `a11y_recent_card` | {title}，{第 N 页 / 继续阅读 / 未读}，{需要密码} | 最近阅读卡 |
| `a11y_unread` | 未读 | 最近卡 progressType 未知时的进度位 |

规则：模板只描述用户可感知信息，不得含 uri、内部 relPath、异常类名；状态词与角标优先级保持一致（加密优先）。

## 10. 设置与关于

| 键 | 文案 |
|---|---|
| `settings_title` | 设置 |
| `settings_section_mode` | 默认阅读模式 |
| `settings_mode_horizontal` | 水平分页 |
| `settings_mode_vertical` | 垂直滚动 |
| `settings_section_theme` | 主题 |
| `settings_theme_system` | 跟随系统 |
| `settings_theme_light` | 浅色 |
| `settings_theme_dark` | 深色 |
| `settings_section_sort` | 书架排序 |
| `settings_sort_title` | 标题 |
| `settings_sort_last_read` | 最近阅读 |
| `settings_sort_added` | 添加时间 |
| `settings_sort_size` | 大小 |
| `settings_section_backup` | 备份与恢复 |
| `settings_export` | 导出备份 |
| `settings_restore` | 从备份恢复 |
| `settings_section_about` | 关于 |
| `settings_about_entry` | 开源许可与第三方声明 |
| `about_title` | 关于 |
| `about_app_desc` | 安卓本地文档阅读器 |
| `about_license_section` | 开源许可 |
| `about_license_body` | 本项目整体以 AGPL-3.0 开源（含服务端条款的网络传播义务）。 |
| `about_mupdf_section` | PDF 渲染引擎 |
| `about_mupdf_body` | PDF 渲染引擎为 Artifex Software 的 MuPDF，同样遵循 AGPL-3.0，以 git submodule 形式集成自其官方仓库。 |
