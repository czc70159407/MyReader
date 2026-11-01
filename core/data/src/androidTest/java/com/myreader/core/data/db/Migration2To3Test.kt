package com.myreader.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M12 第二个 Room Migration 门禁：以 `2.json` 建 v2 库 → 插入种子 → `runMigrationsAndValidate(3)`
 * 校验目标 schema 与手写 `MIGRATION_2_3` 完全一致，且既有数据（文档/书签）零丢失。
 *
 * schema JSON 由 Room Gradle Plugin 导出，`androidTest` assets 已指向 `schemas/`。
 * 运行：`.\gradlew.bat :core:data:connectedDebugAndroidTest`（真机；会安装测试 APK）。
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MyReaderDatabase::class.java,
    )

    @Test
    fun migrate2To3_preservesSeedData_andCreatesAnnotationsWithCascade() {
        helper.createDatabase(DB_SEEDED, 2).apply {
            execSQL(
                "INSERT INTO folders (id, parentId, name, sortOrder, createdAt, updatedAt) " +
                    "VALUES (1, NULL, 'F', 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO documents (id, folderId, title, relPath, fileType, mimeType, sourceUri, " +
                    "fileSize, contentHash, needsPassword, pageCount, progressType, currentPage, " +
                    "scrollOffsetY, progressPayload, thumbnailRelPath, lastReadAt, addedAt) " +
                    "VALUES (1, 1, 'a.pdf', '1.pdf', 'pdf', NULL, NULL, 10, NULL, 0, 3, 'page', 1, 0, " +
                    "NULL, NULL, NULL, 1)",
            )
            execSQL(
                "INSERT INTO bookmarks (documentId, page, scrollOffsetY, anchor, label, createdAt) " +
                    "VALUES (1, 2, 0, NULL, '第 3 页', 100)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_SEEDED, 3, true, MyReaderDatabase.MIGRATION_2_3)
        db.execSQL("PRAGMA foreign_keys = ON")

        // 既有数据完整保留（迁移不得动老表）
        db.query("SELECT title FROM documents WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a.pdf", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM bookmarks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // 新表可写且初始为空
        db.query("SELECT COUNT(*) FROM annotations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.execSQL(
            "INSERT INTO annotations (documentId, type, page, anchor, color, text, note, createdAt) " +
                "VALUES (1, 'HIGHLIGHT', 0, '[[0,0,1,0,0,1,1,1]]', -256, '选中文本', NULL, 200)",
        )
        db.query("SELECT COUNT(*) FROM annotations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // 外键 CASCADE：删文档即删高亮
        db.execSQL("DELETE FROM documents WHERE id = 1")
        db.query("SELECT COUNT(*) FROM annotations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_emptyDatabase() {
        helper.createDatabase(DB_EMPTY, 2).close()
        helper.runMigrationsAndValidate(DB_EMPTY, 3, true, MyReaderDatabase.MIGRATION_2_3).close()
    }

    private companion object {
        const val DB_SEEDED = "migration-2-3-seeded.db"
        const val DB_EMPTY = "migration-2-3-empty.db"
    }
}
