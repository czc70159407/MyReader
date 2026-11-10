package com.myreader.feature.library

import com.myreader.core.model.BatchDeleteResult
import com.myreader.core.model.BatchMoveResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** M13 批量选择纯逻辑单测：toggle 扩展、结果文案、删除确认文案。 */
class SelectionTest {

    @Test
    fun toggle_addsAndRemovesMember() {
        val added = emptySet<Long>().toggle(1L)
        assertEquals(setOf(1L), added)
        assertEquals(emptySet<Long>(), added.toggle(1L))
    }

    @Test
    fun selection_total_sumsFoldersAndDocuments() {
        assertEquals(3, Selection(folderIds = setOf(1L, 2L), documentIds = setOf(3L)).total)
        assertEquals(0, Selection().total)
    }

    @Test
    fun batchMoveMessage_reportsMovedAndSkipped() {
        assertEquals("已移动 2 个文档、1 个文件夹", batchMoveMessage(BatchMoveResult(1, 2, 0)))
        assertEquals("已移动 1 个文档（2 个已在目标目录）", batchMoveMessage(BatchMoveResult(0, 1, 2)))
        assertEquals("已在目标目录", batchMoveMessage(BatchMoveResult(0, 0, 3)))
    }

    @Test
    fun batchDeleteMessage_reportsDeletedAndOrphan() {
        assertEquals("已删除 2 个文档、1 个文件夹", batchDeleteMessage(BatchDeleteResult(1, 2, 0)))
        assertEquals("已删除 1 个文档；2 个文件删除失败，请重试", batchDeleteMessage(BatchDeleteResult(0, 1, 2)))
        assertEquals("未删除任何内容", batchDeleteMessage(BatchDeleteResult(0, 0, 0)))
    }

    @Test
    fun batchDeleteConfirmMessage_composesBothConsequences() {
        assertEquals(
            "将删除 2 个文档，物理文件一并删除。将删除 1 个文件夹，其中的文档将移至根目录。是否继续？",
            batchDeleteConfirmMessage(Selection(folderIds = setOf(1L), documentIds = setOf(2L, 3L))),
        )
        assertEquals(
            "将删除 1 个文档，物理文件一并删除。是否继续？",
            batchDeleteConfirmMessage(Selection(documentIds = setOf(2L))),
        )
    }
}
