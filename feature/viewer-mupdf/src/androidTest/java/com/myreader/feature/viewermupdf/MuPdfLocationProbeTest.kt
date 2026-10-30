package com.myreader.feature.viewermupdf

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Location
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11 S0 预研探针（androidTest，仅取证不设断言）。
 *
 * 目的：回答「跨 doc 会话 / 跨排版时，哪一级锚点仍然指向同一内容」——
 * 这是 docs/m11-plan.md 分支 P/Q/R 的判据。
 *
 * 观测三组：
 * - A：单会话自洽性（`page → Location(chapter,page) → page`、`page → mark`）+ 各章页数分布
 * - B：**重开文档（跨会话）、同一排版**：Location 还原页 vs mark 还原页 vs mark 往返一致性
 * - C：**重开文档、换排版**（420×640，模拟水平模式 relayout）：Location 还原页，
 *   并回读 `locationFromPageNumber` 判断是否落到同一章内页（即 chapter+page 是否跨排版稳定）
 *
 * 输出：`adb logcat -d -s M11PROBE`（一次性取证，结论固化后本文件可删除或转为回归用例）。
 */
@RunWith(AndroidJUnit4::class)
class MuPdfLocationProbeTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = File(context.cacheDir, "m11_probe").apply { mkdirs() }
        file = File(dir, "epub_test.epub").apply {
            context.assets.open("epub_test.epub").use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    /** 与 [com.artifex.mupdf.viewer.MuPDFCore] 构造函数一致的默认排版（312×504pt，em=10）。 */
    private fun openFixedLayout(): Document = Document.openDocument(file.absolutePath, "epub").apply {
        layout(312f, 504f, 10f)
    }

    @Test
    fun probe_crossSessionAndRelayout() {
        // ---------- A：单会话自洽 ----------
        val a = openFixedLayout()
        val countA = a.countPages()
        val chaptersA = a.countChapters()
        val target = (countA / 2).coerceIn(0, countA - 1)
        val locA = a.locationFromPageNumber(target)
        val markA = a.makeBookmark(locA)
        val pageBackA = a.pageNumberFromLocation(Location(locA.chapter, locA.page))
        val locBackA = a.locationFromPageNumber(pageBackA)
        val chapterOfPage = IntArray(countA) { a.locationFromPageNumber(it).chapter }
        val chapterCounts = chapterOfPage.toList().groupingBy { it }.eachCount().toSortedMap()
        a.destroy()
        Log.i(
            TAG,
            "A(session) count=$countA chapters=$chaptersA target=$target loc=$locA " +
                "pageBack=$pageBackA locBack=$locBackA mark=$markA chapterCounts=$chapterCounts",
        )

        // ---------- B：跨会话，同排版 ----------
        // 全部包 runCatching：探针的职责是「无论成败都把证据打出来」，任一调用抛异常不能中断后续分组
        val targetLoc = Location(locA.chapter, locA.page)
        val b = openFixedLayout()
        val countB = b.countPages()
        val pageFromLocB = runCatching { b.pageNumberFromLocation(targetLoc) }.getOrNull()
        val foundB = runCatching { b.findBookmark(markA) }.getOrNull()
        val pageFromMarkB = foundB?.let { runCatching { b.pageNumberFromLocation(it) }.getOrNull() }
        val backB = pageFromMarkB?.let { runCatching { b.makeBookmark(b.locationFromPageNumber(it)) }.getOrNull() }
        b.destroy()
        Log.i(
            TAG,
            "B(reopen, same layout) count=$countB pageFromLoc=$pageFromLocB " +
                "pageFromLocEqualsTarget=${pageFromLocB == target} " +
                "found=$foundB pageFromMark=$pageFromMarkB backEqualsMark=${backB == markA} " +
                "mark=${markA} back=$backB",
        )

        // ---------- C：跨会话，换排版（relayout） ----------
        val c = openFixedLayout()
        c.layout(420f, 640f, 10f) // fitz 侧 layout 返回 void；旧页 remap 属 MuPDFCore 封装职责，探针不需要
        val countC = c.countPages()
        val pageFromLocC = runCatching { c.pageNumberFromLocation(targetLoc) }.getOrNull()
        val locFromPageC = pageFromLocC?.let { p -> runCatching { c.locationFromPageNumber(p) }.getOrNull() }
        val markC = runCatching { c.makeBookmark(targetLoc) }.getOrNull()
        val pageFromMarkC = markC?.let { m -> runCatching { c.pageNumberFromLocation(c.findBookmark(m)) }.getOrNull() }
        c.destroy()
        Log.i(
            TAG,
            "C(reopen, relayout 420x640) count=$countC pageFromLoc=$pageFromLocC locFromPage=$locFromPageC " +
                "locStable=${locFromPageC == targetLoc} markFromLoc=$markC " +
                "pageFromMark=$pageFromMarkC (A mark=$markA)",
        )
    }

    private companion object {
        const val TAG = "M11PROBE"
    }
}
