package com.myreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tab 索引：书签 */
private const val TAB_BOOKMARKS = 0

/** Tab 索引：高亮与笔记 */
private const val TAB_ANNOTATIONS = 1

/**
 * 「标注」面板（M12-C3）：书签 / 高亮双 Tab，把顶栏两个入口合并为一个 —— 5 图标降 4，
 * 标题不再被挤压。Tab 内容复用 [BookmarkSection] / [AnnotationSection]，不重复维护列表逻辑。
 *
 * Tab 选择用 `rememberSaveable`：横竖屏切换/旋转后保持当前 Tab。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationHubSheet(
    bookmarkState: BookmarkPanelState,
    annotationState: AnnotationPanelState,
    bookmarkActions: BookmarkSheetActions,
    annotationActions: AnnotationSheetActions,
    onDismiss: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_BOOKMARKS) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_BOOKMARKS,
                    onClick = { selectedTab = TAB_BOOKMARKS },
                    text = { Text(text = "书签") },
                )
                Tab(
                    selected = selectedTab == TAB_ANNOTATIONS,
                    onClick = { selectedTab = TAB_ANNOTATIONS },
                    text = { Text(text = "高亮与笔记") },
                )
            }
            if (selectedTab == TAB_BOOKMARKS) {
                BookmarkSection(state = bookmarkState, actions = bookmarkActions)
            } else {
                AnnotationSection(state = annotationState, actions = annotationActions)
            }
        }
    }
}
