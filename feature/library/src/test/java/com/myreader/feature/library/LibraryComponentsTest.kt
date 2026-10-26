package com.myreader.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myreader.core.model.DocSort
import com.myreader.core.model.LibraryItem
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithText("a.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("b.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 页").assertIsDisplayed()    // currentPage 0 → 第 1 页
        composeRule.onNodeWithText("第 3 页").assertIsDisplayed()    // currentPage 2 → 第 3 页
    }

    @Test
    fun recentCard_clickTriggersCallback() {
        val clicked = mutableListOf<Long>()
        composeRule.setContent { RecentSection(recent = listOf(item(7, "a.pdf", 0)), onDocumentClick = { clicked.add(it) }) }
        composeRule.onNodeWithText("a.pdf").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(7L), clicked)
    }

    @Test
    fun recentCard_encrypted_showsLockBadge() {
        composeRule.setContent {
            RecentSection(
                recent = listOf(item(1, "a.pdf", 0), item(2, "enc.pdf", 0, needsPassword = true)),
                onDocumentClick = {},
            )
        }
        // needsPassword 常驻（M6 修正）：加密文档在最近条同样显示锁角标
        composeRule.onNodeWithContentDescription("需要密码").assertIsDisplayed()
    }
}
