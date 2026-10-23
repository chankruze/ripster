package `in`.geekofia.ripster.navigation

import org.jetbrains.compose.resources.DrawableResource
import ripster.shared.generated.resources.Res
import ripster.shared.generated.resources.ic_download
import ripster.shared.generated.resources.ic_queue

/** Destinations in the bottom navigation bar. Each has a branch in `App`. */
enum class TopLevelDestination(val label: String, val icon: DrawableResource) {
    Download("Download", Res.drawable.ic_download),
    Queue("Queue", Res.drawable.ic_queue),
}
