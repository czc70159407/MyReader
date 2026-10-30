package com.myreader.feature.library

/**
 * T7：名称输入规则（文件夹名 / 文档展示名共用）。
 *
 * - 长度上限 64：与目录导入的 `sanitizeFolderName` 截断口径一致；
 * - 控制字符（`\p{Cntrl}`，如制表/退格）在输入期过滤，粘贴也不接收——库里与界面都不该出现不可见字符；
 * - 空/全空格**不报错**，由弹窗禁用主按钮表达（`docs/design/components/dialogs.md` §2.2）。
 */
internal const val MAX_NAME_LEN = 64

/** 重名行内文案（`copy.md` `inline_name_duplicate`） */
internal const val NAME_DUPLICATE_MESSAGE = "该目录下已存在同名项目"

private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")

/** 输入期过滤：去掉控制字符，保留其余原文（含首尾空格，提交时再 trim）。 */
internal fun filterNameInput(raw: String): String = raw.replace(CONTROL_CHARS, "")

/** 输入期校验：超长返回行内文案（`copy.md` `inline_name_too_long`），合法返回 null。 */
internal fun nameInputError(raw: String): String? =
    if (raw.trim().length > MAX_NAME_LEN) "名称最多 $MAX_NAME_LEN 个字符" else null
