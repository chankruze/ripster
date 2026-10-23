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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
        if (!handled) scope.launch { snackbar.showSnackbar("No app on this device can handle MP4 files.") }
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

@Composable
fun QueueScreen(state: QueueUiState, actions: JobActions, modifier: Modifier = Modifier) {
    ScreenScaffold(title = "Queue", modifier = modifier) { contentModifier ->
        if (state.isEmpty) {
            Box(contentModifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet. Inspect a manifest on the Download tab and add it to the queue.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = contentModifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.active.isNotEmpty()) {
                item(key = "active") { SectionHeading("Active") }
                items(state.active, key = { it.id }) { JobCard(it, actions) }
            }
            if (state.finished.isNotEmpty()) {
                item(key = "finished") { SectionHeading("Finished") }
                items(state.finished, key = { it.id }) { JobCard(it, actions) }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
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
    DownloadState.Exporting -> "Exporting MP4"
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
    selection.resolution,
    selection.bandwidth?.let(::formatBitrate),
    "sample".takeIf { isSample },
).joinToString(" · ").ifEmpty { null }

private fun DownloadJob.progressLine(): String {
    val percent = progress?.let { "${(it * 100).toInt()}%" }
    return when (state) {
        DownloadState.Exporting -> percent?.let { "Exporting · $it" } ?: "Exporting…"
        else -> listOfNotNull(percent, formatBytes(bytesDownloaded).takeIf { bytesDownloaded > 0 })
            .joinToString(" · ")
            .ifEmpty { if (waitingForNetwork) "Paused until a connection is available" else "Starting…" }
    }
}
