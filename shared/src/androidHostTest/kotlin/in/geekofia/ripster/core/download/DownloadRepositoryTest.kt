package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.platform.InMemoryTextStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DownloadRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun repository(store: InMemoryTextStore) = DownloadRepository(store, scope, Dispatchers.IO)

    @Test
    fun jobsSurviveARestart() = runBlocking<Unit> {
        val store = InMemoryTextStore()
        val first = repository(store).apply { load() }
        first.add(testJob(id = "a", createdAt = 1))
        first.add(testJob(id = "b", createdAt = 2))
        first.update("a") { it.apply(JobEvent.Cancel, 9) }
        eventually<Int>(value = { DownloadJobCodec.decode(store.read()).size }) { it == 2 }

        val second = repository(store).apply { load() }
        assertEquals(listOf("b", "a"), second.jobs.value.map { it.id })
        assertEquals(DownloadState.Cancelled, second.get("a")?.state)
    }

    @Test
    fun nothingIsWrittenBeforeLoading() = runBlocking<Unit> {
        val existing = DownloadJobCodec.encode(listOf(testJob(id = "old")))
        val store = InMemoryTextStore(existing)
        val repository = repository(store)
        repository.add(testJob(id = "new", createdAt = 5_000))
        delay(100)
        assertEquals(existing, store.read(), "a partial queue must never overwrite the stored one")

        repository.load()
        assertEquals(listOf("new", "old"), repository.jobs.value.map { it.id })
        eventually<Int>(value = { DownloadJobCodec.decode(store.read()).size }) { it == 2 }
    }

    @Test
    fun progressTicksAreNotWritten() = runBlocking<Unit> {
        val store = InMemoryTextStore()
        val repository = repository(store).apply { load() }
        repository.add(testJob(state = DownloadState.Downloading))
        val written = eventually(value = { store.read() }) { it != null && DownloadJobCodec.decode(it).isNotEmpty() }
        repository.update("job-1") { it.apply(JobEvent.DownloadProgress(0.3f, 30, false), 2) }
        delay(100)
        assertEquals(written, store.read())
        assertEquals(0.3f, repository.get("job-1")?.progress)
    }

    @Test
    fun updateReportsWhetherTheEventApplied() {
        val repository = repository(InMemoryTextStore())
        repository.add(testJob(state = DownloadState.Completed))
        assertNull(repository.update("job-1") { it.apply(JobEvent.Cancel, 2) })
        assertNull(repository.update("missing") { it })
        assertTrue(repository.update("job-1") { it.apply(JobEvent.Fail(networkError), 2) } == null)
        repository.remove("job-1")
        assertEquals(emptyList(), repository.jobs.value)
    }
}
