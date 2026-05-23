package com.example.readio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.readio.ui.chapters.ChapterListScreen
import com.example.readio.ui.library.LibraryScreen
import com.example.readio.ui.reader.ReaderScreen
import com.example.readio.ui.settings.SettingsScreen

@Composable
fun ReadioNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.Library.route) {

        composable(Screen.Library.route) {
            LibraryScreen(
                onBookOpen = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) },
                onSettingsOpen = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Reader.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("startChapterId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ReaderScreen(
                onBack = { navController.popBackStack() },
                onChapterList = { bookId ->
                    navController.navigate(Screen.ChapterList.createRoute(bookId))
                }
            )
        }

        composable(
            route = Screen.ChapterList.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            ChapterListScreen(
                onBack = { navController.popBackStack() },
                onOpenChapter = { chapterId ->
                    navController.navigate(Screen.Reader.createRoute(bookId, chapterId)) {
                        popUpTo(Screen.Reader.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
