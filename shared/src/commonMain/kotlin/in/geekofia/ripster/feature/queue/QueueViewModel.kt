package `in`.geekofia.ripster.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.download.DownloadJob
import `in`.geekofia.ripster.core.download.DownloadState
import `in`.geekofia.ripster.platform.FileActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class QueueUiState(
    val active: List<DownloadJob> = emptyList(),
    val finished: List<DownloadJob> = emptyList(),
) {
    val isEmpty: Boolean get() = active.isEmpty() && finished.isEmpty()

    companion object {
        /** Active jobs oldest first (queue order); finished ones newest first. */
        fun from(jobs: List<DownloadJob>) = QueueUiState(
            active = jobs.filter { it.state.isActive }.sortedBy { it.createdAtMillis },
            finished = jobs.filter { it.state.isTerminal }.sortedByDescending { it.updatedAtMillis },
        )
    }
}

/** The queue's lifecycle-aware view; all actions are forwarded to [DownloadCoordinator]. */
class QueueViewModel(
    private val downloads: DownloadCoordinator,
    private val fileActions: FileActions,
) : ViewModel() {

    val state: StateFlow<QueueUiState> = downloads.jobs
        .map(QueueUiState::from)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState.from(downloads.jobs.value))

    fun cancel(job: DownloadJob) = downloads.cancel(job.id)

    fun retry(job: DownloadJob) = downloads.retry(job.id)

    fun remove(job: DownloadJob) = downloads.remove(job.id)

    /** Returns false when no installed app can open the file. */
    fun open(job: DownloadJob): Boolean = job.output?.takeIf { job.state == DownloadState.Completed }
        ?.let(fileActions::open) ?: false

    fun share(job: DownloadJob): Boolean = job.output?.takeIf { job.state == DownloadState.Completed }
        ?.let(fileActions::share) ?: false
}
