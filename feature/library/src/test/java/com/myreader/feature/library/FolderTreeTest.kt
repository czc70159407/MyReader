package com.myreader.feature.library

import com.myreader.core.model.Folder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * buildFlatTree 纯函数 JVM 测试：排序 / depth / 展开过滤 / hasChildren（目录树展平核心逻辑）。
 */
class FolderTreeTest {

    private fun folder(id: Long, parentId: Long?, name: String, sortOrder: Int = 0) =
        Folder(id = id, parentId = parentId, name = name, sortOrder = sortOrder, createdAt = 1, updatedAt = 1)

    @Test
    fun sortsSiblingsBySortOrderThenName() {
        val flat = buildFlatTree(
            listOf(
                folder(3, null, "b", sortOrder = 1),
                folder(1, null, "z", sortOrder = 0),
                folder(2, null, "a", sortOrder = 0),
            ),
            expandedIds = emptySet(),
        )
        // sortOrder 相同按 name（a 在 z 前），sortOrder 1 的 b 最后
        assertEquals(listOf(2L, 1L, 3L), flat.map { it.id })
        assertTrue(flat.all { it.depth == 0 })
    }

    @Test
    fun expandsOnlySelectedNodes() {
        val flat = buildFlatTree(
            listOf(
                folder(1, null, "a"),
                folder(2, 1, "b"),
                folder(3, 2, "c"),
                folder(4, null, "d"),
            ),
            expandedIds = setOf(1L),   // 只展开 a；b 未展开 → c 不出现
        )
        assertEquals(listOf(1L, 2L, 4L), flat.map { it.id })
        assertEquals(0, flat[0].depth)
        assertEquals(1, flat[1].depth)
        assertEquals(0, flat[2].depth)
    }

    @Test
    fun recursiveExpansion_downToDepth() {
        val flat = buildFlatTree(
            listOf(
                folder(1, null, "a"),
                folder(2, 1, "b"),
                folder(3, 2, "c"),
            ),
            expandedIds = setOf(1L, 2L),
        )
        assertEquals(listOf(1L, 2L, 3L), flat.map { it.id })
        assertEquals(listOf(0, 1, 2), flat.map { it.depth })
    }

    @Test
    fun hasChildren_reflectsData() {
        val flat = buildFlatTree(
            listOf(
                folder(1, null, "a"),
                folder(2, 1, "b"),
                folder(4, null, "d"),
            ),
            expandedIds = setOf(1L),   // 展开a 才能看到子级 b
        )
        assertTrue(flat.first { it.id == 1L }.hasChildren)
        assertFalse(flat.first { it.id == 2L }.hasChildren)   // b 无子
        assertFalse(flat.first { it.id == 4L }.hasChildren)
    }

    @Test
    fun emptyInput_yieldsEmptyList() {
        assertTrue(buildFlatTree(emptyList(), emptySet()).isEmpty())
    }

    // ---- T7：同级重名预检（数据源 = 全量目录快照，与展开状态无关）----

    @Test
    fun isNameTaken_scopedToParent_andCaseSensitive() = runTest {
        val folders = MutableStateFlow(
            listOf(
                folder(1, null, "Docs"),
                folder(2, 1, "Sub"),
            ),
        )
        val state = FolderTreeState(folders, backgroundScope)
        state.all.first { it.isNotEmpty() }   // Eagerly 订阅完成

        assertTrue(state.isNameTaken(null, "Docs"))
        assertFalse(state.isNameTaken(null, "docs"))    // D7：区分大小写
        assertTrue(state.isNameTaken(1L, "Sub"))
        assertFalse(state.isNameTaken(2L, "Sub"))       // 父级不同不算重名
    }
}
