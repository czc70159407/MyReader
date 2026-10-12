package com.myreader.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.myreader.feature.library.LibraryScreen
import com.myreader.feature.reader.ReaderScreen

/**
 * 路由表（docs/ui-design.md「UI 层架构」）：
 *   library?folderId={folderId}  书架/目录浏览（M5：folderId 可选参数，缺省=根；
 *                                「移动/导入选目录」用 FolderPickerDialog 弹窗，非独立路由）
 *   reader/{documentId}          阅读器（M4）
 *   settings：M7 预留
 */
object Routes {
    const val LIBRARY = "library?folderId={folderId}"
    const val READER = "reader/{documentId}"

    /** 根目录的哨兵值：NavType.LongType 不允许 nullable（运行时抛 IllegalArgumentException） */
    const val FOLDER_ID_NONE = -1L

    /** null 必须返回 "library"：空参数串会让 LongType 解析崩溃，缺省参数走 defaultValue */
    fun library(folderId: Long?): String = folderId?.let { "library?folderId=$it" } ?: "library"

    fun reader(documentId: Long) = "reader/$documentId"
}

@Composable
fun MyReaderNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY, modifier = modifier) {
        composable(
            route = Routes.LIBRARY,
            arguments = listOf(
                navArgument("folderId") {
                    type = NavType.LongType
                    defaultValue = Routes.FOLDER_ID_NONE   // LongType 不允许 nullable，用哨兵表示根
                },
            ),
        ) {
            LibraryScreen(
                onDocumentClick = { id -> navController.navigate(Routes.reader(id)) },
                onFolderClick = { folderId -> navController.navigate(Routes.library(folderId)) },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) {
            ReaderScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
