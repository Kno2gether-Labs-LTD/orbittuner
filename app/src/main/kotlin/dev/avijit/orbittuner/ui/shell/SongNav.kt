package dev.avijit.orbittuner.ui.shell

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable

/** The song view hides the phone's bottom bar (the sheet gets the full height); the tablet rail stays. */
@Composable
fun shellLayoutType(songOpen: Boolean): NavigationSuiteType {
    val type = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    return if (songOpen && type == NavigationSuiteType.NavigationBar) NavigationSuiteType.None else type
}
