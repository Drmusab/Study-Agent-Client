package com.studyagent.client.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Study : Screen("study")

    /** Study Control Center: how the PC Study Agent studies (§45). */
    data object Control : Screen("control")

    data object Connection : Screen("connection")
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")

    companion object {
        /** Primary product destinations shown in the bottom navigation (§47). */
        val primaryRoutes: List<String> = listOf(Home.route, Study.route, Control.route)
    }
}
