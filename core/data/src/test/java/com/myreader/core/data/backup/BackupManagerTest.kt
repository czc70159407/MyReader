package com.myreader.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MyReaderDatabase
    private lateinit var storage: DocumentStorage
    private lateinit var manager: BackupManager

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        storage = DocumentStorage(tmp.newFolder("library"))
        manager = BackupManager(db, db.folderDao(), db.documentDao(), storage, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private fun writePdf(docId: Long, content: String = "PDFDATA-$docId"): String {
        val cr = storage.copyIn(ByteArrayInputStream(content.toByteArray()), docId, "pdf")
        return cr.relPath
    }

    private fun writeThumb(docId: Long, content: String = "PNG-$docId") {
        File(storage.thumbDir(), "$docId.png").apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    @Test
    fun exportThenRestore_preservesFoldersDocumentsProgressAndFiles() = runTest {
        // 结构：root(1) / a(2)，文档在 a；含真实物理文件与缩略图
        db.folderDao().insert(FolderEntity(1, null, "root", 0, 100, 100))
        db.folderDao().insert(FolderEntity(2, 1, "a", 0, 100, 100))
        val rel = writePdf(10)
        writeThumb(10)
        db.documentDao().insert(
            DocumentEntity(
                id = 10, folderId = 2, title = "doc", relPath = rel, fileType = "pdf",
                mimeType = "application/pdf", sourceUri = "uri", fileSize = 10,
                contentHash = "h10", needsPassword = false, pageCount = 7,
                progressType = "page", currentPage = 3, scrollOffsetY = 250,
                progressPayload = null, thumbnailRelPath = "thumb/10.png",
                lastReadAt = 999, addedAt = 500,
            )
        )

        val baos = ByteArrayOutputStream()
        val exp = manager.export(baos, "test")
        assertEquals(2, exp.folders)
        assertEquals(1, exp.documents)
        assertEquals(1, exp.filesWritten)
        assertEquals(1, exp.thumbnailsWritten)
        assertEquals(0, exp.missingFiles)

        // 清空本机数据：删 DB 记录 + 物理文件 + 缩略图
        db.documentDao().delete(10)
        db.folderDao().delete(2)
        db.folderDao().delete(1)
        storage.deleteDocument(rel)
        storage.deleteThumbnail("thumb/10.png")
        assertEquals(0, db.documentDao().all().size)
        assertEquals(0, db.folderDao().all().size)

        // 恢复
        val res = manager.restore(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(2, res.folders)
        assertEquals(1, res.documents)
        assertEquals(1, res.filesWritten)
        assertEquals(1, res.thumbnailsWritten)
        assertEquals(0, res.missingFiles)

        // DB 还原：目录链 + 文档全字段
        val folders = db.folderDao().all().sortedBy { it.id }
        assertEquals(listOf(1L, 2L), folders.map { it.id })
        assertEquals(null, folders[0].parentId)
        assertEquals(1L, folders[1].parentId)
        val doc = db.documentDao().all().first()
        assertEquals(2L, doc.folderId)
        assertEquals("doc", doc.title)
        assertEquals(7, doc.pageCount)
        assertEquals(3, doc.currentPage)
        assertEquals(250, doc.scrollOffsetY)
        assertEquals(999L, doc.lastReadAt)
        assertEquals("h10", doc.contentHash)
        assertEquals(false, doc.needsPassword)

        // 物理文件还原且内容一致
        assertTrue(storage.documentExists(rel))
        assertEquals("PDFDATA-10", File(storage.thumbDir().parentFile, "library/$rel").readText())
        assertTrue(File(storage.thumbDir(), "10.png").exists())
    }

    @Test
    fun exportThenRestore_preservesEpubCfiProgressAndFile() = runTest {
        // M9：epub 记录（fileType/progressType=cfi/progressPayload）经导出→恢复全字段一致
        db.documentDao().insert(
            DocumentEntity(
                id = 40, folderId = null, title = "book.epub", relPath = "40.epub", fileType = "epub",
                mimeType = "application/epub+zip", sourceUri = null, fileSize = 9, contentHash = "h40",
                needsPassword = false, pageCount = -1, progressType = "cfi",
                currentPage = 5, scrollOffsetY = 0,
                progressPayload = """{"v":1,"loc":"987654","page":5,"chapter":"Chapter 3"}""",
                thumbnailRelPath = null, lastReadAt = 777, addedAt = 500,
            )
        )
        storage.copyIn(ByteArrayInputStream("EPUBDATA-40".toByteArray()), 40, "epub")

        val baos = ByteArrayOutputStream()
        val exp = manager.export(baos, "test")
        assertEquals(1, exp.documents)
        assertEquals(1, exp.filesWritten)

        // 清空后恢复
        db.documentDao().delete(40)
        storage.deleteDocument("40.epub")
        val res = manager.restore(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, res.documents)
        assertEquals(1, res.filesWritten)

        val doc = db.documentDao().all().first()
        assertEquals("epub", doc.fileType)
        assertEquals("cfi", doc.progressType)
        assertEquals("""{"v":1,"loc":"987654","page":5,"chapter":"Chapter 3"}""", doc.progressPayload)
        assertEquals(-1, doc.pageCount)
        assertEquals(5, doc.currentPage)
        // 物理 epub 文件还原且内容一致
        assertTrue(storage.documentExists("40.epub"))
        assertEquals("EPUBDATA-40", File(storage.thumbDir().parentFile, "library/40.epub").readText())
    }

    @Test
    fun export_countsMissingFilesWhenPhysicalAbsent() = runTest {
        db.documentDao().insert(
            DocumentEntity(
                id = 20, folderId = null, title = "no-file", relPath = "20.pdf", fileType = "pdf",
                mimeType = null, sourceUri = null, fileSize = 0, contentHash = null,
                needsPassword = true, pageCount = -1, progressType = "page",
                currentPage = 0, scrollOffsetY = 0, progressPayload = null,
                thumbnailRelPath = null, lastReadAt = null, addedAt = 1,
            )
        )
        val baos = ByteArrayOutputStream()
        val exp = manager.export(baos, "test")
        assertEquals(1, exp.documents)
        assertEquals(0, exp.filesWritten)
        assertEquals(1, exp.missingFiles)

        // 恢复：zip 中本就未含该物理文件（导出时已计入缺失），元数据仍恢复，恢复侧 missingFiles=0
        val res = manager.restore(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, res.documents)
        assertEquals(0, res.filesWritten)
        assertEquals(0, res.missingFiles)
        assertEquals(1, db.documentDao().all().size) // 加密文档元数据完整恢复
        assertFalse(storage.documentExists("20.pdf"))
    }

    @Test
    fun restore_isIdempotent_sameBackupTwiceDoesNotDuplicate() = runTest {
        db.folderDao().insert(FolderEntity(1, null, "r", 0, 1, 1))
        val rel = writePdf(30)
        db.documentDao().insert(
            DocumentEntity(
                id = 30, folderId = 1, title = "d", relPath = rel, fileType = "pdf",
                mimeType = null, sourceUri = null, fileSize = 3, contentHash = "h",
                needsPassword = false, pageCount = 1, progressType = "page",
                currentPage = 0, scrollOffsetY = 0, progressPayload = null,
                thumbnailRelPath = null, lastReadAt = null, addedAt = 1,
            )
        )
        val baos = ByteArrayOutputStream()
        manager.export(baos, "test")
        manager.restore(ByteArrayInputStream(baos.toByteArray()))
        manager.restore(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, db.folderDao().all().size)
        assertEquals(1, db.documentDao().all().size)
    }

    @Test
    fun restore_rejectsZipSlipEntry() = runTest {
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION, exportedAt = 1, appVersion = "t",
            folders = emptyList(),
            documents = listOf(
                BackupCodec.DocumentDto(
                    1, null, "d", "1.pdf", "pdf", null, null, 1, null, false,
                    1, "page", 0, 0, null, null, null, 1,
                )
            ),
        )
        val json = BackupCodec.encode(manifest)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("library.json"))
            z.write(json.toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("files/../evil.pdf")) // zip slip 尝试
            z.write("x".toByteArray())
            z.closeEntry()
        }
        val ex = runCatching { manager.restore(ByteArrayInputStream(baos.toByteArray())) }.exceptionOrNull()
        assertTrue("应拒绝 zip slip: $ex", ex is IllegalArgumentException)
    }
}
