package com.myreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 验收测试专用 ContentProvider（仅 debug 变体打包，release 不含）：
 * 把 app cacheDir 下以 lastPathSegment 命名的文件暴露为 content://com.myreader.testfiles/<name>，
 * 使 DocumentImporter 的 SAF 路径（queryMeta + openInputStream）在插桩中可被真实驱动，
 * 而无需操作系统文件选择器 UI。
 */
class TestFilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: error("missing name segment: $uri")
        return File(context!!.cacheDir, name)
    }
}
