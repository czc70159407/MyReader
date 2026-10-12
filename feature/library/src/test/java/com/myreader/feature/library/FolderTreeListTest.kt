package com.myreader.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.myreader.core.model.Folder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FolderTreeList Compose UI 测试（Robolectric）：渲染 / 点击 / 长按 / 展开收起。
 * 经 internal 构造直接注入 MutableStateFlow 数据（确定性，无 Room 异步竞态）；
 * 数据链路（Room→repo→组树）由 FolderTreeTest（组树纯函数）与 core:data 测试覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderTreeListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clicked = mutableListOf<Long?>()
    private val longPressed = mutableListOf<Long>()

    private val booksId = 1L
    private val docsId = 2L
    private val childId = 3L

    private fun folder(id: Long, parentId: Long?, name: String) =
        Folder(id = id, parentId = parentId, name = name, sortOrder = 0, createdAt = 1, updatedAt = 1)

    private fun createState(): FolderTreeState {
        val foldersFlow = MutableStateFlow(
            listOf(
                folder(booksId, null, "Books"),
                folder(docsId, null, "Docs"),
                folder(childId, docsId, "DocsChild"),
            ),
        )
        return FolderTreeState(foldersFlow, CoroutineScope(UnconfinedTestDispatcher()))
    }

    private fun setContent(state: FolderTreeState) {
        composeRule.setContent {
            FolderTreeList(
                state = state,
                onFolderClick = { clicked.add(it) },
                onFolderLongPress = { longPressed.add(it) },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rendersRootFolders_clickTriggersCallback() {
        clicked.clear()
        longPressed.clear()
        setContent(createState())
        composeRule.onNodeWithText("Books").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf<Long?>(booksId), clicked)
    }

    @Test
    fun toggle_expandsChildren_thenCollapses() {
        clicked.clear()
        longPressed.clear()
        val state = createState()
        setContent(state)
        // 初始收起：子级 DocsChild 不可见（根级列表含 Books/Docs）
        composeRule.onNodeWithText("Books").assertIsDisplayed()
        composeRule.onNodeWithText("DocsChild").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("展开").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DocsChild").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("收起").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DocsChild").assertDoesNotExist()
    }

    @Test
    fun longPress_triggersCallback() {
        clicked.clear()
        longPressed.clear()
        setContent(createState())
        composeRule.onNodeWithText("Books").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(listOf(booksId), longPressed)
    }
}
