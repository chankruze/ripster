package `in`.geekofia.ripster

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.geekofia.ripster.feature.home.HomeRoute
import `in`.geekofia.ripster.feature.queue.QueueRoute
import `in`.geekofia.ripster.navigation.TopLevelDestination
import `in`.geekofia.ripster.ui.theme.RipsterTheme
import org.jetbrains.compose.resources.painterResource

/**
 * Shared entry point, hosted by `MainActivity` on Android. Two peer tabs, so a saveable selection
 * is all the navigation needed; each screen keeps its own ViewModel across tab switches.
 */
@Composable
fun App(container: AppContainer) {
    val services = container.platformServices
    RipsterTheme {
        var tab by rememberSaveable { mutableStateOf(TopLevelDestination.Download) }
        val jobs by container.downloads.jobs.collectAsStateWithLifecycle()
        val activeCount = jobs.count { it.state.isActive }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = { tab = item },
                            icon = {
                                BadgedBox(badge = {
                                    if (item == TopLevelDestination.Queue && activeCount > 0) Badge { Text("$activeCount") }
                                }) {
                                    Icon(painterResource(item.icon), contentDescription = null)
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)
            when (tab) {
                TopLevelDestination.Download -> HomeRoute(
                    resolver = container.resolver,
                    downloads = container.downloads,
                    clipboard = services.clipboard,
                    notificationPermission = services.notificationPermission,
                    outputSettings = container.outputSettings,
                    folderPicker = services.folderPicker,
                    showSamples = services.appInfo.isDebugBuild,
                    onOpenQueue = { tab = TopLevelDestination.Queue },
                    modifier = modifier,
                )
                TopLevelDestination.Queue -> QueueRoute(
                    downloads = container.downloads,
                    fileActions = services.fileActions,
                    modifier = modifier,
                )
            }
        }
    }
}
