package `in`.geekofia.ripster.feature.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.download.DownloadState
import `in`.geekofia.ripster.core.source.formatBytes
import `in`.geekofia.ripster.core.source.message
import `in`.geekofia.ripster.feature.home.formatBitrate
import `in`.geekofia.ripster.platform.FileActions
import `in`.geekofia.ripster.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

/** Wires [QueueViewModel] to the stateless [QueueScreen]. */
@Composable
fun QueueRoute(
    downloads: DownloadCoordinator,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = viewModel { QueueViewModel(downloads, fileActions) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noApp: (Boolean) -> Unit = { handled ->
        if (!handled) scope.launch { snackbar.showSnackbar("No app on this device can open this file.") }
    }
    Box(modifier) {
        QueueScreen(
            state = state,
            actions = JobActions(
                cancel = viewModel::cancel,
                retry = viewModel::retry,
                remove = viewModel::remove,
                open = { noApp(viewModel.open(it)) },
                share = { noApp(viewModel.share(it)) },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

class JobActions(
    val cancel: (DownloadJob) -> Unit,
    val retry: (DownloadJob) -> Unit,
    val remove: (DownloadJob) -> Unit,
    val open: (DownloadJob) -> Unit,
    val share: (DownloadJob) -> Unit,
)

/** The queue's two views. Active is the default; finished jobs stay until removed. */
enum class QueueTab(val label: String) { Active("Active"), Finished("Finished") }

@Composable
fun QueueScreen(state: QueueUiState, actions: JobActions, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { QueueTab.entries.size }
    val scope = rememberCoroutineScope()
    ScreenScaffold(title = "Queue", modifier = modifier) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                QueueTab.entries.forEach { item ->
                    val count = if (item == QueueTab.Active) state.active.size else state.finished.size
                    Tab(
                        selected = item.ordinal == pager.currentPage,
                        onClick = { scope.launch { pager.animateScrollToPage(item.ordinal) } },
                        text = { Text(if (count > 0) "${item.label} ($count)" else item.label) },
                    )
                }
            }
            // Swipe between the tabs; the pager's state is saveable, so the tab survives rotation.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { QueueTab.entries[it].name }) { page ->
                val tab = QueueTab.entries[page]
                val jobs = if (tab == QueueTab.Active) state.active else state.finished
                if (jobs.isEmpty()) {
                    EmptyTab(tab)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(jobs, key = { it.id }) { JobCard(it, actions) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(tab: QueueTab) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            when (tab) {
                QueueTab.Active -> "Nothing downloading. Inspect a manifest on the Download tab and add it to the queue."
                QueueTab.Finished -> "Completed, failed and cancelled downloads appear here."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JobCard(job: DownloadJob, actions: JobActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(job.stateLabel(), style = MaterialTheme.typography.labelMedium, color = job.stateColor())
            }
            job.qualityLine()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Progress(job)
            job.error?.takeIf { job.state == DownloadState.Failed }?.message()?.let { message ->
                Text(message.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text(message.detail, style = MaterialTheme.typography.bodySmall)
            }
            job.output?.takeIf { job.state == DownloadState.Completed }?.let { output ->
                Text(
                    "${output.displayName} · ${formatBytes(output.sizeBytes)} · ${output.location}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Actions(job, actions)
        }
    }
}

@Composable
private fun Progress(job: DownloadJob) {
    when (job.state) {
        DownloadState.Resolving -> LinearProgressIndicator(Modifier.fillMaxWidth())
        DownloadState.Downloading, DownloadState.Exporting -> {
            val progress = job.progress
            // Determinate only with a real value from the engine or exporter; never estimated.
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(job.progressLine(), style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
}

@Composable
private fun Actions(job: DownloadJob, actions: JobActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (job.state) {
            DownloadState.Completed -> {
                FilledTonalButton(onClick = { actions.open(job) }) { Text("Open") }
                OutlinedButton(onClick = { actions.share(job) }) { Text("Share") }
                TextButton(onClick = { actions.remove(job) }) { Text("Remove") }
            }
            DownloadState.Failed, DownloadState.Cancelled -> {
                if (job.error?.isRetryable != false) {
                    FilledTonalButton(onClick = { actions.retry(job) }) { Text("Retry") }
                }
                TextButton(onClick = { actions.remove(job) }) { Text("Remove") }
            }
            else -> OutlinedButton(onClick = { actions.cancel(job) }) { Text("Cancel") }
        }
    }
}

private fun DownloadJob.stateLabel(): String = when (state) {
    DownloadState.Queued -> "Queued"
    DownloadState.Resolving -> "Checking source"
    DownloadState.Downloading -> if (waitingForNetwork) "Waiting for network" else "Downloading"
    DownloadState.Exporting -> if (file != null) "Saving file" else "Exporting MP4"
    DownloadState.Completed -> "Completed"
    DownloadState.Failed -> "Failed"
    DownloadState.Cancelled -> "Cancelled"
}

@Composable
private fun DownloadJob.stateColor() = when (state) {
    DownloadState.Failed -> MaterialTheme.colorScheme.error
    DownloadState.Completed -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun DownloadJob.qualityLine(): String? = listOfNotNull(
    file?.let { "${it.label} file" },
    selection.resolution,
    selection.bandwidth?.let(::formatBitrate),
    "sample".takeIf { isSample },
).joinToString(" · ").ifEmpty { null }

private fun DownloadJob.progressLine(): String {
    val percent = progress?.let { "${(it * 100).toInt()}%" }
    return when (state) {
        DownloadState.Exporting -> {
            val verb = if (file != null) "Saving" else "Exporting"
            percent?.let { "$verb · $it" } ?: "$verb…"
        }
        else -> listOfNotNull(percent, formatBytes(bytesDownloaded).takeIf { bytesDownloaded > 0 })
            .joinToString(" · ")
            .ifEmpty { if (waitingForNetwork) "Paused until a connection is available" else "Starting…" }
    }
}
