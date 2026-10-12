package com.myreader.feature.viewermupdf

import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 本地文件的 fitz [SeekableInputStream] 适配（对应上游 ContentInputStream 的 ContentResolver 版）。
 * RandomAccessFile 天然支持随机寻址，seek 语义与上游一致：
 * SEEK_SET/CUR/END（常量定义于 SeekableStream），返回新位置。
 * 用于 >8MB 的大文件流式打开，避免整文件读入内存。
 * 注意：接口不要求 close——流生命周期由 fitz 原生层管理（对齐上游 ContentInputStream）。
 */
class SeekableFileInputStream(file: File) : SeekableInputStream {

    private val raf = RandomAccessFile(file, "r")

    @Throws(IOException::class)
    override fun seek(offset: Long, whence: Int): Long {
        when (whence) {
            SeekableStream.SEEK_SET -> raf.seek(offset)
            SeekableStream.SEEK_CUR -> raf.seek(raf.filePointer + offset)
            SeekableStream.SEEK_END -> raf.seek(raf.length() + offset)
            else -> throw IllegalArgumentException("未知 whence: $whence")
        }
        return raf.filePointer
    }

    @Throws(IOException::class)
    override fun position(): Long = raf.filePointer

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int = raf.read(b)
}
