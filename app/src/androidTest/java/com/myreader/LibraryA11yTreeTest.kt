package com.myreader

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myreader.core.data.db.entity.DocumentEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T5 无障碍回归门禁（平台层）：
 * 书架卡片必须是**单个**同时具备描述与点击动作的 AccessibilityNodeInfo，
 * 且其下不再挂文本子节点——否则 TalkBack 会拆成两个焦点位，重复播报（2026-10-29 真机实测缺陷）。
 *
 * 为什么不用 Robolectric 的 Compose 测试：`SemanticsNode` 只反映 Compose 语义树，
 * 平台 `AccessibilityNodeInfo` 的父子拆分在该层看不到，本次缺陷正是这样漏过的。
 *
 * 数据自播种：仪器测试跑完 AGP 会卸载应用（数据清空），因此每个用例前直接向 DB 插入一条
 * `a11y_test.pdf` 夹具行，用例后删除——不依赖设备既有书架内容。
 *
 * 运行（需真机/模拟器）：
 * `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.myreader.LibraryA11yTreeTest`
 */
@RunWith(AndroidJUnit4::class)
class LibraryA11yTreeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var entry: AcceptanceEntryPoint

    @Before
    fun seedShelf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        entry = EntryPointAccessors.fromApplication(ctx, AcceptanceEntryPoint::class.java)
        runBlocking {
            entry.database().documentDao().upsert(SEED_DOC)
        }
    }

    @After
    fun clearShelf() {
        runBlocking {
            entry.database().documentDao().delete(SEED_DOC.id)
        }
    }

    @Test
    fun documentCards_exposeSingleAccessibilityNode() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val cards = waitFor { root -> collect(root) { node -> node.isCardNode() } }
            assertTrue(
                "书架上应至少有一张「同时带描述与点击动作」的卡片（夹具已播种）",
                cards.isNotEmpty(),
            )
            cards.forEach { node ->
                val textChildren = descendants(node).filter { !it.text.isNullOrBlank() }
                assertEquals(
                    "卡片应是单一焦点位，其下不应再有文本子节点（会重复播报）：${node.contentDescription} -> ${textChildren.map { it.text }}",
                    0,
                    textChildren.size,
                )
            }
        }
    }

    @Test
    fun documentCard_exposesCustomActionsForLongPressOps() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val card = waitFor { root -> collect(root) { node -> node.isCardNode() } }
                .firstOrNull { node -> node.contentDescription?.toString()?.contains("页") == true }
            assertNotNull("书架应至少有一张文档卡（含「页」描述）", card)

            val labels = card!!.actionList.mapNotNull { it.label?.toString() }
            assertTrue(
                "整卡应暴露读屏长按替代路径（customActions），实际: $labels",
                labels.containsAll(listOf("重命名", "移动", "删除")),
            )
        }
    }

    private fun AccessibilityNodeInfo.isCardNode(): Boolean =
        isClickable && !contentDescription.isNullOrBlank()

    /** 轮询根节点直到满足 `select` 返回非空（书架首帧加载 / LazyGrid 布局需要时间） */
    private fun waitFor(
        timeoutMs: Long = 10_000,
        select: (AccessibilityNodeInfo) -> List<AccessibilityNodeInfo>,
    ): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                val hit = select(root)
                if (hit.isNotEmpty()) return hit
            }
            SystemClock.sleep(300)
        }
        return emptyList()
    }

    private fun collect(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> = descendants(root).filter(predicate)

    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node !== root && node.packageName == root.packageName) out += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return out
    }

    private companion object {
        const val SEED_ID = 900_001L

        val SEED_DOC = DocumentEntity(
            id = SEED_ID,
            folderId = null,
            title = "a11y_test.pdf",
            relPath = "$SEED_ID.pdf",
            fileType = "pdf",
            mimeType = "application/pdf",
            sourceUri = null,
            fileSize = 1L,
            pageCount = 3,
            thumbnailRelPath = null,
            lastReadAt = null,
            addedAt = 0L,
        )
    }
}
