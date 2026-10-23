package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.platform.TextStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The queue, newest first. Memory is the source of truth for the UI; the [store] gets a copy
 * whenever a durable field changes. Writes go through one conflated writer on [ioDispatcher], so
 * they're ordered, never block the caller, and a burst of changes costs one write.
 *
 * Progress, bytes and "waiting for network" of active jobs aren't persisted: after a restart the
 * engine reports them again from its own index, so progress ticks never touch the disk.
 */
class DownloadRepository(
    private val store: TextStore,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val state = MutableStateFlow<List<DownloadJob>>(emptyList())
    private val loadedState = MutableStateFlow(false)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var lastWritten: String? = null

    val jobs: StateFlow<List<DownloadJob>> = state.asStateFlow()

    /** False until [load] has read the store; until then [jobs] is empty but not authoritative. */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            for (request in writes) {
                // Never overwrite the stored queue with a partial one; load() requests a write.
                if (!loadedState.value) continue
                val text = DownloadJobCodec.encode(state.value.map { it.durable() })
                if (text != lastWritten) {
                    store.write(text)
                    lastWritten = text
                }
            }
        }
    }

    suspend fun load() {
        if (loadedState.value) return
        val text = withContext(ioDispatcher) { store.read() }
        lastWritten = text
        val stored = DownloadJobCodec.decode(text)
        // Atomic: a job added while the store was being read must not be lost to this merge.
        state.update { current ->
            (current + stored.filter { s -> current.none { it.id == s.id } }).sortedByDescending { it.createdAtMillis }
        }
        loadedState.value = true
        writes.trySend(Unit)
    }

    fun get(id: String): DownloadJob? = state.value.firstOrNull { it.id == id }

    fun add(job: DownloadJob) {
        mutate { jobs -> listOf(job) + jobs.filter { it.id != job.id } }
    }

    fun remove(id: String) {
        mutate { jobs -> jobs.filter { it.id != id } }
    }

    /**
     * Atomically replaces the job with [transform]'s result. Returns the new job, or null when the
     * job doesn't exist or [transform] returned null (an event that doesn't apply).
     */
    fun update(id: String, transform: (DownloadJob) -> DownloadJob?): DownloadJob? {
        while (true) {
            val current = state.value
            val index = current.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
            val updated = transform(current[index]) ?: return null
            if (updated == current[index]) return updated
            val next = current.toMutableList().also { it[index] = updated }
            if (state.compareAndSet(current, next)) {
                if (updated.durable() != current[index].durable()) writes.trySend(Unit)
                return updated
            }
        }
    }

    private fun mutate(transform: (List<DownloadJob>) -> List<DownloadJob>) {
        while (true) {
            val current = state.value
            if (state.compareAndSet(current, transform(current))) break
        }
        writes.trySend(Unit)
    }

    private fun DownloadJob.durable(): DownloadJob =
        if (state.isActive) copy(progress = null, bytesDownloaded = 0, waitingForNetwork = false) else this
}
