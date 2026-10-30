package com.myreader.feature.library

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 无状态组件 Compose UI 测试（Robolectric）：MoreMenu（T2/D2 收纳后顶栏菜单）/ RecentSection / RecentCard。
 * 纯渲染 + 回调，无 Room/VM 依赖；数据链路由 LibraryViewModelTest 与 core:data 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- MoreMenu（T2/D2：动作段 + 分隔线 + 排序段）----

    @Test
    fun moreMenu_showsActionSection_andFourSortOptions_withSingleCheckOnCurrent() {
        composeRule.setContent {
            MoreMenu(importing = false, onNewFolder = {}, onImportDir = {}, current = DocSort.SIZE, onSelect = {})
        }
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()

        // 动作段
        composeRule.onNodeWithText("新建文件夹").assertIsDisplayed()
        composeRule.onNodeWithText("导入目录").assertIsDisplayed()
        // 排序段
        composeRule.onNodeWithText("标题").assertIsDisplayed()
        composeRule.onNodeWithText("最近阅读").assertIsDisplayed()
        composeRule.onNodeWithText("添加时间").assertIsDisplayed()
        composeRule.onNodeWithText("大小").assertIsDisplayed()
        // 仅当前项勾选
        assertEquals(1, composeRule.onAllNodesWithContentDescription("当前排序").fetchSemanticsNodes().size)
    }

    @Test
    fun moreMenu_importing_greysOutImportDir_butKeepsNewFolderAndSort() {
        composeRule.setContent {
            MoreMenu(
                importing = true,
                onNewFolder = {},
                onImportDir = {},
                current = DocSort.TITLE,
                onSelect = {},
            )
        }
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("导入目录").assertIsNotEnabled()
        composeRule.onNodeWithText("新建文件夹").assertIsEnabled()
        composeRule.onNodeWithText("标题").assertIsEnabled()
    }

    @Test
    fun moreMenu_actionAndSortCallbacks_fire_afterClosingMenu() {
        val folders = mutableListOf<Int>()
        val dirs = mutableListOf<Int>()
        val sorts = mutableListOf<DocSort>()
        composeRule.setContent {
            MoreMenu(
                importing = false,
                onNewFolder = { folders.add(1) },
                onImportDir = { dirs.add(1) },
                current = DocSort.TITLE,
                onSelect = { sorts.add(it) },
            )
        }

        // 新建文件夹
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("新建文件夹").performClick()
        composeRule.waitForIdle()

        // 导入目录（菜单关闭后重新展开）
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("导入目录").performClick()
        composeRule.waitForIdle()

        // 排序
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("大小").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1), folders)
        assertEquals(listOf(1), dirs)
        assertEquals(listOf(DocSort.SIZE), sorts)
    }

    // ---- T5 无障碍语义（整卡合并描述 + customActions 长按替代路径）----

    private fun documentCard(item: LibraryItem, onRename: () -> Unit = {}) {
        composeRule.setContent {
            DocumentCard(
                item = item,
                onClick = {},
                onLongClick = {},
                menuExpanded = false,
                onDismissMenu = {},
                onRename = onRename,
                onMove = {},
                onDelete = {},
            )
        }
    }

    @Test
    fun documentCard_mergedDescription_coversTitleAndPages() {
        documentCard(item(1, "a.pdf", 0))
        composeRule.onNodeWithContentDescription("a.pdf，3 页").assertIsDisplayed()
    }

    @Test
    fun documentCard_encrypted_descriptionMentionsPassword() {
        documentCard(item(2, "enc.pdf", 0, needsPassword = true))
        composeRule.onNodeWithContentDescription("enc.pdf，3 页，需要密码").assertIsDisplayed()
    }

    @Test
    fun documentCard_customActions_coverManagementOps() {
        val renamed = mutableListOf<Boolean>()
        documentCard(item(1, "a.pdf", 0), onRename = { renamed.add(true) })
        val actions = composeRule.onNodeWithContentDescription("a.pdf，3 页")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(3, actions.size)
        actions.first { it.label == "重命名" }.action()
        assertEquals(listOf(true), renamed)
    }

    @Test
    fun recentCard_mergedDescription_coversProgressAndStatus() {
        composeRule.setContent { RecentCard(item = item(9, "r.pdf", 2, needsPassword = true), onClick = {}) }
        composeRule.onNodeWithContentDescription("r.pdf，第 3 页，需要密码").assertIsDisplayed()
    }

    // ---- RecentSection / RecentCard ----

    private fun item(id: Long, title: String, currentPage: Int, needsPassword: Boolean = false) = LibraryItem(
        id = id, folderId = null, title = title, relPath = "$id.pdf", fileType = "pdf",
        mimeType = null, sourceUri = null, fileSize = 1, contentHash = null,
        needsPassword = needsPassword, pageCount = 3, progressType = "page",
        currentPage = currentPage, scrollOffsetY = 0, progressPayload = null,
        thumbnailRelPath = null, lastReadAt = id, addedAt = 1,
    )

    @Test
    fun recentSection_rendersTitleAndCards_withProgress() {
        val items = listOf(item(1, "a.pdf", currentPage = 0), item(2, "b.pdf", currentPage = 2))
        composeRule.setContent { RecentSection(recent = items, onDocumentClick = {}) }

        composeRule.onNodeWithText("最近阅读").assertIsDisplayed()
        // T5：卡片整卡由 clearAndSetSemantics 单节点承担描述，文本不再单独暴露
        composeRule.onNodeWithContentDescription("a.pdf，第 1 页").assertIsDisplayed()    // currentPage 0 → 第 1 页
        composeRule.onNodeWithContentDescription("b.pdf，第 3 页").assertIsDisplayed()    // currentPage 2 → 第 3 页
    }

    @Test
    fun recentCard_clickTriggersCallback() {
        val clicked = mutableListOf<Long>()
        composeRule.setContent { RecentSection(recent = listOf(item(7, "a.pdf", 0)), onDocumentClick = { clicked.add(it) }) }
        composeRule.onNodeWithContentDescription("a.pdf，第 1 页").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(7L), clicked)
    }

    @Test
    fun folderCard_customActions_coverFourOps_includingNewSubFolder() {
        var newSubFolder = 0
        composeRule.setContent {
            FolderCard(
                folder = Folder(id = 3, parentId = null, name = "Docs", sortOrder = 0, createdAt = 1, updatedAt = 1),
                onClick = {},
                onLongClick = {},
                menuExpanded = false,
                onDismissMenu = {},
                onRename = {},
                onMove = {},
                onDelete = {},
                onNewSubFolder = { newSubFolder++ },
            )
        }
        val actions = composeRule.onNodeWithContentDescription("Docs，文件夹")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("重命名", "移动", "删除", "新建子目录"), actions.map { it.label })
        actions.first { it.label == "新建子目录" }.action()
        assertEquals(1, newSubFolder)
    }

    @Test
    fun documentCard_damaged_descriptionOmitsFormatButMentionsStatus() {
        composeRule.setContent {
            DocumentCard(
                item = item(1, "bad.pdf", 0).copy(pageCount = -2),
                onClick = {},
                onLongClick = {},
                menuExpanded = false,
                onDismissMenu = {},
                onRename = {},
                onMove = {},
                onDelete = {},
            )
        }
        // T5 review L15：损坏文档不追加格式词，避免「x.pdf，PDF，已损坏」
        // 用 fetchSemanticsNode 断言存在（不依赖 Robolectric 布局尺寸）
        composeRule.onNodeWithContentDescription("bad.pdf，已损坏").fetchSemanticsNode()
    }

    // ---- T7：名称输入期校验（新建文件夹弹窗）----

    @Test
    fun newFolderDialog_tooLong_showsInlineErrorAndDisablesCreate() {
        composeRule.setContent { NewFolderDialog(onConfirm = {}, onDismiss = {}) }

        composeRule.onNode(hasSetTextAction()).performTextInput("x".repeat(MAX_NAME_LEN + 1))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("名称最多 $MAX_NAME_LEN 个字符").assertIsDisplayed()
        composeRule.onNodeWithText("创建").assertIsNotEnabled()
    }

    @Test
    fun newFolderDialog_siblingDuplicate_showsInlineErrorAndDisablesCreate() {
        composeRule.setContent {
            NewFolderDialog(onConfirm = {}, onDismiss = {}, isNameTaken = { it == "Docs" })
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("Docs")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NAME_DUPLICATE_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("创建").assertIsNotEnabled()
    }

    @Test
    fun newFolderDialog_controlChars_filteredOut() {
        composeRule.setContent { NewFolderDialog(onConfirm = {}, onDismiss = {}) }

        composeRule.onNode(hasSetTextAction()).performTextInput("a\tb")
        composeRule.waitForIdle()

        // 节点同时带 label 文本，故只断言输入内容（EditableText）
        val editable = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("ab", editable)
    }

    @Test
    fun renameDialog_lengthCountsLockedExtension() {
        composeRule.setContent {
            RenameDialog(currentName = "old.pdf", onConfirm = {}, onDismiss = {}, lockedExtension = ".pdf")
        }

        // base 64 + ".pdf" 共 68 → 超限（与业务层 requireNameLen 完整名口径一致）
        composeRule.onNode(hasSetTextAction()).performTextReplacement("x".repeat(MAX_NAME_LEN))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("名称最多 $MAX_NAME_LEN 个字符").assertIsDisplayed()
        composeRule.onNodeWithText("确定").assertIsNotEnabled()
    }
}
