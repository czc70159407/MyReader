package com.myreader.feature.library

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DirectoryScanner 单测：纯遍历逻辑（格式过滤 / 规模上限 / 不可访问统计），
 * 以内存 DirectoryTreeSource 替代 SAF（Robolectric 无法伪造 DocumentsContract 查询）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectoryScannerTest {

    /** 内存目录树：map of dirId → children；文件名即 docId 派生，路径断言可读 */
    private class FakeSource(
        private val tree: Map<String, List<DirectoryTreeSource.Entry>>,
        private val inaccessible: Set<String> = emptySet(),
    ) : DirectoryTreeSource {
        override val rootDocId = "root"
        override fun children(docId: String): List<DirectoryTreeSource.Entry>? =
            if (docId in inaccessible) null else tree[docId] ?: emptyList()
        override fun documentUri(docId: String): Uri = Uri.parse("content://test/$docId")
    }

    private fun file(name: String, size: Long? = 10L, mime: String? = null) =
        DirectoryTreeSource.Entry(name, name, mime, size, isDirectory = false)

    private fun dir(name: String) =
        DirectoryTreeSource.Entry(name, name, "vnd.android.document/directory", null, isDirectory = true)

    @Test
    fun scan_mirrorsNestedFolderPaths() {
        val source = FakeSource(
            mapOf(
                "root" to listOf(file("a.pdf"), dir("docs")),
                "docs" to listOf(file("b.pdf"), dir("sub")),
                "sub" to listOf(file("c.epub", mime = "application/epub+zip")),
            ),
        )
        val result = DirectoryScanner.scan(source)

        assertEquals(3, result.files.size)
        assertEquals(emptyList<String>(), result.files.first { it.displayName == "a.pdf" }.folderPath)
        assertEquals(listOf("docs"), result.files.first { it.displayName == "b.pdf" }.folderPath)
        assertEquals(listOf("docs", "sub"), result.files.first { it.displayName == "c.epub" }.folderPath)
        assertEquals(0, result.unsupportedCount)
        assertEquals(0, result.inaccessibleDirCount)
    }

    @Test
    fun scan_filtersUnsupportedFormats() {
        val source = FakeSource(
            mapOf(
                "root" to listOf(
                    file("ok.pdf"),
                    file("notes.log"),          // 有扩展名但未登记 → 拒收
                    file("photo.jpg", mime = "image/jpeg"),
                    file("data", mime = "application/octet-stream"),   // 无扩展名且 mime 未登记 → 拒收
                ),
            ),
        )
        val result = DirectoryScanner.scan(source)

        assertEquals(1, result.files.size)
        assertEquals("ok.pdf", result.files.single().displayName)
        assertEquals(3, result.unsupportedCount)
    }

    @Test
    fun scan_tooLargeFilesSkippedAndCounted() {
        val big = 201L * 1024 * 1024
        val source = FakeSource(
            mapOf("root" to listOf(file("small.pdf", size = 100L), file("big.pdf", size = big))),
        )
        val result = DirectoryScanner.scan(source, DirectoryScanLimits(maxFileBytes = 200L * 1024 * 1024))

        assertEquals(1, result.files.size)
        assertEquals("small.pdf", result.files.single().displayName)
        assertEquals(1, result.tooLargeCount)
    }

    @Test
    fun scan_depthLimitCountsSkippedDirs_butKeepsFilesAtMaxDepth() {
        // depth 1：root/d1 允许下钻一层；root/d1/d2 的目录不展开，但其内文件仍可导入
        val source = FakeSource(
            mapOf(
                "root" to listOf(file("r.pdf"), dir("d1")),
                "d1" to listOf(file("d1.pdf"), dir("d2")),
                "d2" to listOf(file("d2.pdf")),
            ),
        )
        val result = DirectoryScanner.scan(source, DirectoryScanLimits(maxDepth = 1))

        assertEquals(2, result.files.size)
        assertTrue(result.files.any { it.displayName == "r.pdf" })
        assertTrue(result.files.any { it.displayName == "d1.pdf" })
        assertEquals(1, result.deepSkippedCount)
    }

    @Test
    fun scan_fileCapTruncatesAndCounts() {
        val entries = (1..10).map { file("f$it.pdf") }
        val source = FakeSource(mapOf("root" to entries))
        val result = DirectoryScanner.scan(source, DirectoryScanLimits(maxFiles = 4))

        assertEquals(4, result.files.size)
        assertEquals(6, result.truncatedCount)
    }

    @Test
    fun scan_inaccessibleSubdirCounted_othersStillImported() {
        val source = FakeSource(
            mapOf(
                "root" to listOf(file("a.pdf"), dir("locked"), dir("ok")),
                "ok" to listOf(file("b.pdf")),
            ),
            inaccessible = setOf("locked"),
        )
        val result = DirectoryScanner.scan(source)

        assertEquals(2, result.files.size)
        assertEquals(1, result.inaccessibleDirCount)
    }

    @Test
    fun sanitizeFolderName_cleansAndFallsBack() {
        assertEquals("normal", sanitizeFolderName("  normal  "))
        assertEquals("noctrl", sanitizeFolderName("no\tctrl"))   // 控制字符被删除
        assertEquals("未命名目录", sanitizeFolderName("   "))
        assertEquals("未命名目录", sanitizeFolderName(null))
        assertEquals("x".repeat(64), sanitizeFolderName("x".repeat(200)))
    }
}
