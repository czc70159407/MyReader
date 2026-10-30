package com.myreader.feature.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.myreader.core.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M10-C 书签面板 Compose UI 测试：状态矩阵（加载/空/有数据/失败）+ 跳转与删除回调。
 * 与 `LibraryComponentsTest` 同型（Robolectric + createComposeRule）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookmarkSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun bookmark(id: Long, label: String) = Bookmark(
        id = id,
        documentId = 1,
        page = 0,
        scrollOffsetY = 0,
        anchor = null,
        label = label,
        createdAt = 1_700_000_000_000,
    )

    private fun setSheet(
        state: BookmarkPanelState,
        onAdd: () -> Unit = {},
        onJump: (Bookmark) -> Unit = {},
        onDelete: (Long) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            BookmarkSheet(
                state = state,
                actions = BookmarkSheetActions(
                    onAdd = onAdd,
                    onJump = onJump,
                    onDelete = onDelete,
                    onRetry = onRetry,
                    onDismiss = {},
                ),
            )
        }
    }

    @Test
    fun ready_rendersItems_andDeleteFiresCallback() {
        val deleted = mutableListOf<Long>()
        setSheet(
            BookmarkPanelState.Ready(listOf(bookmark(1, "第 3 页"), bookmark(2, "第 5 屏"))),
            onDelete = { deleted += it },
        )

        composeRule.onNodeWithText("第 3 页").assertIsDisplayed()
        composeRule.onNodeWithText("第 5 屏").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("删除书签")[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(listOf(1L), deleted)
    }

    @Test
    fun empty_showsPlaceholder_andAddFiresCallback() {
        var added = 0
        setSheet(BookmarkPanelState.Ready(emptyList()), onAdd = { added++ })

        composeRule.onNodeWithText("暂无书签").assertIsDisplayed()
        composeRule.onNodeWithText("添加当前位置书签").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(1, added)
    }

    @Test
    fun failed_showsRetry() {
        var retried = 0
        setSheet(BookmarkPanelState.Failed, onRetry = { retried++ })

        composeRule.onNodeWithText("书签加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(1, retried)
    }

    @Test
    fun loading_showsProgressText() {
        setSheet(BookmarkPanelState.Loading)

        composeRule.onNodeWithText("正在加载书签…").assertIsDisplayed()
    }

    @Test
    fun rowClick_jumpsToBookmark() {
        val jumped = mutableListOf<Long>()
        setSheet(
            BookmarkPanelState.Ready(listOf(bookmark(9, "第 2 页"))),
            onJump = { jumped += it.id },
        )

        composeRule.onNodeWithText("第 2 页").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(listOf(9L), jumped)
    }
}
