package com.myreader.core.data.backup

import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {

    private fun sampleFolder(id: Long, parentId: Long? = null, name: String = "f$id") = FolderEntity(
        id = id, parentId = parentId, name = name,
        sortOrder = 0, createdAt = 1000L, updatedAt = 1000L,
    )

    private fun sampleDoc(id: Long, folderId: Long? = null, relPath: String = "$id.pdf") = DocumentEntity(
        id = id, folderId = folderId, title = "doc$id", relPath = relPath, fileType = "pdf",
        mimeType = "application/pdf", sourceUri = null, fileSize = 123L, contentHash = "h$id",
        needsPassword = id % 2 == 0L, pageCount = 10 + id.toInt(), progressType = "page",
        currentPage = 3, scrollOffsetY = 250, progressPayload = null,
        thumbnailRelPath = "thumb/$id.png", lastReadAt = 999L, addedAt = 500L,
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val folders = listOf(sampleFolder(1), sampleFolder(2, parentId = 1))
        val docs = listOf(sampleDoc(1, folderId = 1), sampleDoc(2, folderId = 2))
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION, exportedAt = 1L, appVersion = "t",
            folders = folders.map { it.toDto() }, documents = docs.map { it.toDto() },
        )
        val text = BackupCodec.encode(manifest)
        val back = BackupCodec.decode(text)

        assertEquals(folders.map { it.toDto() }, back.folders)
        assertEquals(docs.map { it.toDto() }, back.documents)
        // 逐字段还原（经 dto→entity 还原后与原始 entity 相等）
        assertEquals(folders, back.folders.map { it.toEntity() })
        assertEquals(docs, back.documents.map { it.toEntity() })
    }

    @Test
    fun roundTrip_preservesEpubCfiFields() {
        // M9：epub 记录的 fileType/progressType="cfi"/progressPayload 必须完整往返
        val epub = DocumentEntity(
            id = 7, folderId = null, title = "book.epub", relPath = "7.epub", fileType = "epub",
            mimeType = "application/epub+zip", sourceUri = null, fileSize = 2048L, contentHash = "h7",
            needsPassword = false, pageCount = -1, progressType = "cfi",
            currentPage = 5, scrollOffsetY = 0,
            progressPayload = """{"v":1,"loc":"987654","page":5,"chapter":"Chapter 3"}""",
            thumbnailRelPath = "thumb/7.png", lastReadAt = 1234L, addedAt = 500L,
        )
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION, exportedAt = 1L, appVersion = "t",
            folders = emptyList(), documents = listOf(epub.toDto()),
        )
        val back = BackupCodec.decode(BackupCodec.encode(manifest))

        val restored = back.documents.single().toEntity()
        assertEquals(epub, restored)
        assertEquals("epub", restored.fileType)
        assertEquals("cfi", restored.progressType)
        assertEquals(epub.progressPayload, restored.progressPayload)
        assertEquals(-1, restored.pageCount)
    }

    @Test
    fun decode_rejectsMismatchedSchemaVersion() {
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = 999, exportedAt = 1L, appVersion = "t",
            folders = emptyList(), documents = listOf(sampleDoc(1).toDto()),
        )
        val text = BackupCodec.encode(manifest)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(text) }
    }

    @Test
    fun decode_rejectsIllegalRelPath() {
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION, exportedAt = 1L, appVersion = "t",
            folders = emptyList(),
            documents = listOf(sampleDoc(1).toDto().copy(relPath = "../evil.pdf")),
        )
        val text = BackupCodec.encode(manifest)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(text) }
    }

    @Test
    fun decode_toleratesUnknownFields() {
        // 额外字段 extraUnknown 应被 ignoreUnknownKeys 忽略
        val json = """
        {
          "schemaVersion": 1,
          "exportedAt": 1,
          "appVersion": "t",
          "folders": [],
          "documents": [
            {
              "id": 1, "folderId": null, "title": "d", "relPath": "1.pdf", "fileType": "pdf",
              "mimeType": null, "sourceUri": null, "fileSize": 1, "contentHash": null,
              "needsPassword": false, "pageCount": 1, "progressType": "page",
              "currentPage": 0, "scrollOffsetY": 0, "progressPayload": null,
              "thumbnailRelPath": null, "lastReadAt": null, "addedAt": 1,
              "extraUnknown": "ignored"
            }
          ]
        }
        """.trimIndent()
        val back = BackupCodec.decode(json)
        assertEquals(1, back.documents.size)
        assertEquals("1.pdf", back.documents.first().relPath)
    }

    @Test
    fun decode_rejectsEmptyContent() {
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION, exportedAt = 1L, appVersion = "t",
            folders = emptyList(), documents = emptyList(),
        )
        val text = BackupCodec.encode(manifest)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(text) }
    }

    @Test
    fun decode_rejectsMalformedJson() {
        assertThrows(SerializationException::class.java) { BackupCodec.decode("{not json") }
    }
}
