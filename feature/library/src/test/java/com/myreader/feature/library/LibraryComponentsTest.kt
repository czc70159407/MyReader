package com.myreader.feature.library

import androidx.compose.ui.test.assertIsDisplayed
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
 * M6 无状态组件 Compose UI 测试（Robolectric）：SortMenu / RecentSection / RecentCard。
 * 纯渲染 + 回调，无 Room/VM 依赖；数据链路由 LibraryViewModelTest 与 core:data 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- SortMenu ----

    @Test
    fun sortMenu_showsFourOptions_withSingleCheckOnCurrent() {
        composeRule.setContent { SortMenu(current = DocSort.SIZE, onSelect = {}) }
        composeRule.onNodeWithContentDescription("排序").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("标题").assertIsDisplayed()
        composeRule.onNodeWithText("最近阅读").assertIsDisplayed()
        composeRule.onNodeWithText("添加时间").assertIsDisplayed()
        composeRule.onNodeWithText("大小").assertIsDisplayed()
        // 仅当前项勾选
        assertEquals(1, composeRule.onAllNodesWithContentDescription("当前排序").fetchSemanticsNodes().size)
    }

    @Test
    fun sortMenu_selectTriggersCallback() {
        val selected = mutableListOf<DocSort>()
        composeRule.setContent { SortMenu(current = DocSort.TITLE, onSelect = { selected.add(it) }) }
        composeRule.onNodeWithContentDescription("排序").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("大小").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(DocSort.SIZE), selected)
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
