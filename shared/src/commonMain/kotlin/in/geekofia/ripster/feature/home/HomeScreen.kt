package `in`.geekofia.ripster.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.download.EnqueueResult
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.hls.HlsEncryption
import `in`.geekofia.ripster.core.settings.OutputSettingsRepository
import `in`.geekofia.ripster.core.source.ErrorCategory
import `in`.geekofia.ripster.core.source.ManifestKind
import `in`.geekofia.ripster.core.source.MediaSourceResolver
import `in`.geekofia.ripster.core.source.SampleSource
import `in`.geekofia.ripster.core.source.SampleSources
import `in`.geekofia.ripster.core.source.SourceUrl
import `in`.geekofia.ripster.core.source.message
import `in`.geekofia.ripster.platform.ClipboardReader
import `in`.geekofia.ripster.platform.NotificationPermission
import `in`.geekofia.ripster.platform.OutputFolderPicker
import `in`.geekofia.ripster.ui.components.ScreenScaffold
import `in`.geekofia.ripster.ui.components.SectionCard
import org.jetbrains.compose.resources.painterResource
import ripster.shared.generated.resources.Res
import ripster.shared.generated.resources.ic_close

/** Wires [HomeViewModel] and the output settings to the stateless [HomeScreen]. */
@Composable
fun HomeRoute(
    resolver: MediaSourceResolver,
    downloads: DownloadCoordinator,
    clipboard: ClipboardReader,
    notificationPermission: NotificationPermission,
    outputSettings: OutputSettingsRepository,
    folderPicker: OutputFolderPicker,
    showSamples: Boolean,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel { HomeViewModel(resolver, downloads, clipboard, notificationPermission) },
) {
    val folder by outputSettings.folder.collectAsStateWithLifecycle()
    HomeScreen(
        state = viewModel.uiState,
        samples = if (showSamples) SampleSources.all else emptyList(),
        outputFolder = folder,
        onUrlChange = viewModel::onUrlChange,
        onPaste = viewModel::paste,
        onClear = viewModel::clear,
        onInspect = viewModel::inspect,
        onPickSample = viewModel::pickSample,
        onSelectVariant = viewModel::selectVariant,
        onDownload = viewModel::download,
        onChooseFolder = { folderPicker.pick(outputSettings::setFolder) },
        onUseAppStorage = { outputSettings.setFolder(null) },
        onOpenQueue = {
            viewModel.dismissResult()
            onOpenQueue()
        },
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    samples: List<SampleSource>,
    outputFolder: OutputFolder?,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onInspect: () -> Unit,
    onPickSample: (SampleSource) -> Unit,
    onSelectVariant: (Int) -> Unit,
    onDownload: () -> Unit,
    onChooseFolder: () -> Unit,
    onUseAppStorage: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = "Ripster", modifier = modifier) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceInput(state, onUrlChange, onPaste, onClear, onInspect)
            if (samples.isNotEmpty()) SamplePicker(samples, state.sample, onPickSample)
            when (val inspection = state.inspection) {
                Inspection.Idle -> Unit
                is Inspection.Inspecting -> InspectingCard()
                is Inspection.Failed -> ErrorCard(inspection)
                is Inspection.Ready -> {
                    SourceCard(inspection)
                    if (inspection.source.variants.isNotEmpty()) VariantList(inspection, onSelectVariant)
                    OutputCard(inspection, outputFolder, onChooseFolder, onUseAppStorage)
                    DownloadAction(state, onDownload, onOpenQueue)
                }
            }
        }
    }
}

@Composable
private fun SourceInput(
    state: HomeUiState,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onInspect: () -> Unit,
) {
    SectionCard(title = "HLS manifest URL") {
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…/playlist.m3u8") },
            singleLine = true,
            supportingText = {
                Text(
                    if (state.sample != null) "Sample stream: ${state.sample.title}" else "A direct link to an .m3u8 manifest you're allowed to download",
                )
            },
            trailingIcon = if (state.url.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(painterResource(Res.drawable.ic_close), contentDescription = "Clear URL")
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onInspect() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaste) { Text("Paste") }
            Button(onClick = onInspect, enabled = state.canInspect) { Text("Inspect") }
        }
    }
}

@Composable
private fun SamplePicker(samples: List<SampleSource>, current: SampleSource?, onPick: (SampleSource) -> Unit) {
    SectionCard(title = "Sample streams (development)") {
        Text(
            "Public test streams. They may change or go offline at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        samples.forEach { sample ->
            AssistChip(
                onClick = { onPick(sample) },
                label = {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(sample.title + if (sample == current) "  ✓" else "", style = MaterialTheme.typography.labelLarge)
                        Text(sample.purpose, style = MaterialTheme.typography.bodySmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InspectingCard() {
    SectionCard(title = "Inspecting") {
        Text("Fetching and checking the manifest…")
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorCard(failed: Inspection.Failed) {
    val message = failed.error.message()
    val heading = when (failed.error.category) {
        ErrorCategory.NeedsUserAction -> "Action needed"
        ErrorCategory.Unsupported -> "Unsupported source"
        ErrorCategory.Expired -> "Expired"
        ErrorCategory.Unauthorized -> "Not authorized"
        ErrorCategory.Unavailable -> "Unavailable"
        ErrorCategory.Network -> "Network problem"
        else -> "Can't use this source"
    }
    SectionCard(title = heading) {
        Text(message.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(message.detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SourceCard(ready: Inspection.Ready) {
    val source = ready.source
    val details = source.details
    SectionCard(title = "Source") {
        Text(SourceUrl.displayForm(source.manifestUrl), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val kind = when (source.kind) {
            ManifestKind.Multivariant -> "Adaptive playlist · ${source.variants.size} variants"
            ManifestKind.Media -> "Single media playlist"
        }
        Text(kind, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${formatDuration(details.durationSeconds)} · ${details.segmentCount} segments · ${details.container.label()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (details.encryption == HlsEncryption.Aes128) {
            Text("AES-128 encrypted segments (clear key)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VariantList(ready: Inspection.Ready, onSelect: (Int) -> Unit) {
    SectionCard(title = "Quality") {
        Column(Modifier.selectableGroup()) {
            ready.source.variants.forEachIndexed { index, variant ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = index == ready.selectedIndex, onClick = { onSelect(index) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = index == ready.selectedIndex, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(variant.qualityLabel(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            variant.codecSummary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputCard(
    ready: Inspection.Ready,
    folder: OutputFolder?,
    onChooseFolder: () -> Unit,
    onUseAppStorage: () -> Unit,
) {
    val selection = ready.selection
    SectionCard(title = "Output") {
        Text("Format: MP4", style = MaterialTheme.typography.bodyLarge)
        Text(selection.outlook.description(), style = MaterialTheme.typography.bodySmall)
        selection.audio?.let { audio ->
            Text("Audio: ${audio.name}${audio.channels?.let { " · $it ch" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Save to: ${folder?.name ?: "App storage (Ripster)"}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChooseFolder) { Text("Choose folder") }
            if (folder != null) TextButton(onClick = onUseAppStorage) { Text("Use app storage") }
        }
    }
}

@Composable
private fun DownloadAction(state: HomeUiState, onDownload: () -> Unit, onOpenQueue: () -> Unit) {
    Button(onClick = onDownload, enabled = state.canDownload, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.isEnqueueing) "Adding…" else "Download")
    }
    state.lastResult?.let { result ->
        val text = when (result) {
            is EnqueueResult.Added -> "Added \"${result.job.title}\" to the queue."
            is EnqueueResult.AlreadyQueued -> "\"${result.job.title}\" is already in the queue."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onOpenQueue) { Text("View queue") }
        }
    }
}
