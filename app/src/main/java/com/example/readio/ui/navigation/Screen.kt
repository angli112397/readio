package com.example.readio.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Reader : Screen("reader/{bookId}?startChapterId={startChapterId}") {
        fun createRoute(bookId: String, startChapterId: String? = null) =
            if (startChapterId != null) "reader/$bookId?startChapterId=$startChapterId"
            else "reader/$bookId"
    }
    data object ChapterList : Screen("chapters/{bookId}") {
        fun createRoute(bookId: String) = "chapters/$bookId"
    }
}
