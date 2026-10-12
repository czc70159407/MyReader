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
 *   library/{folderId?}  书架/目录树（M3 为根目录，M5 加参数）
 *   reader/{documentId}  阅读器（M4）
 *   folderPicker/{documentId?}、settings：M5/M7
 */
object Routes {
    const val LIBRARY = "library"
    const val READER = "reader/{documentId}"

    fun reader(documentId: Long) = "reader/$documentId"
}

@Composable
fun MyReaderNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY, modifier = modifier) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onDocumentClick = { id -> navController.navigate(Routes.reader(id)) },
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
