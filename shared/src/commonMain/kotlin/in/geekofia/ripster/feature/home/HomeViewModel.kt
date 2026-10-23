package `in`.geekofia.ripster.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.source.MediaSourceResolver
import `in`.geekofia.ripster.core.source.SampleSource
import `in`.geekofia.ripster.core.source.SampleSources
import `in`.geekofia.ripster.core.source.SourceResolution
import `in`.geekofia.ripster.platform.ClipboardReader
import `in`.geekofia.ripster.platform.NotificationPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * URL input, inspection and enqueueing. State is Compose snapshot state, as in MorseKit's
 * translator, because it backs a TextField. Network work happens in [resolver] and
 * [downloads], off the main thread; nothing here blocks.
 */
class HomeViewModel(
    private val resolver: MediaSourceResolver,
    private val downloads: DownloadCoordinator,
    private val clipboard: ClipboardReader,
    private val notificationPermission: NotificationPermission,
) : ViewModel() {

    var uiState by mutableStateOf(HomeUiState())
        private set

    private var inspectJob: Job? = null

    fun onUrlChange(url: String) {
        if (url == uiState.url) return
        inspectJob?.cancel()
        // A result belongs to the URL it was made for; editing the URL invalidates it.
        uiState = uiState.copy(url = url, sample = SampleSources.find(url), inspection = Inspection.Idle, lastResult = null)
    }

    fun paste() {
        clipboard.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let(::onUrlChange)
    }

    fun clear() = onUrlChange("")

    fun pickSample(sample: SampleSource) {
        onUrlChange(sample.url)
        inspect()
    }

    fun inspect() {
        if (!uiState.canInspect) return
        val url = uiState.url.trim()
        uiState = uiState.copy(inspection = Inspection.Inspecting(url), lastResult = null)
        inspectJob = viewModelScope.launch {
            val inspection = when (val result = resolver.resolve(url)) {
                is SourceResolution.Supported -> Inspection.Ready(result.source, result.source.defaultVariantIndex)
                is SourceResolution.Rejected -> Inspection.Failed(result.error)
            }
            // Ignore a result for a URL the user has since changed.
            if (uiState.inspection == Inspection.Inspecting(url)) uiState = uiState.copy(inspection = inspection)
        }
    }

    fun selectVariant(index: Int) {
        val ready = uiState.inspection as? Inspection.Ready ?: return
        if (index !in ready.source.variants.indices) return
        uiState = uiState.copy(inspection = ready.copy(selectedIndex = index), lastResult = null)
    }

    /** Adds the selected quality to the queue. Repeated taps don't add duplicates. */
    fun download() {
        val ready = uiState.inspection as? Inspection.Ready ?: return
        if (uiState.isEnqueueing) return
        notificationPermission.requestIfNeeded()
        uiState = uiState.copy(isEnqueueing = true)
        viewModelScope.launch {
            val result = downloads.enqueue(ready.source, ready.selection)
            uiState = uiState.copy(isEnqueueing = false, lastResult = result)
        }
    }

    fun dismissResult() {
        uiState = uiState.copy(lastResult = null)
    }
}
