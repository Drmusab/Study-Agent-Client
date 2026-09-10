package com.studyagent.client.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Study : Screen("study")
    data object Connection : Screen("connection")
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")
}
