package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.export.MediaExporter
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.hls.HlsAudioRendition
import `in`.geekofia.ripster.core.hls.HlsEncryption
import `in`.geekofia.ripster.core.hls.HlsVariant
import `in`.geekofia.ripster.core.hls.Resolution
import `in`.geekofia.ripster.core.hls.SegmentContainer
import `in`.geekofia.ripster.core.source.ManifestKind
import `in`.geekofia.ripster.core.source.MediaDetails
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaSourceResolver
import `in`.geekofia.ripster.core.source.ResolvedSource
import `in`.geekofia.ripster.core.source.SourceResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout

/** A Media3-like engine whose state the test controls. */
internal class FakeEngine(loaded: Boolean = true) : DownloadEngine {
    private val lock = Any()
    val state = MutableStateFlow(EngineSnapshot(loaded, emptyMap()))
    override val snapshot = state
    val enqueued: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val removed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    fun set(id: String, status: EngineStatus) = synchronized(lock) {
        state.value = state.value.next { it + (id to status) }
    }

    override suspend fun enqueue(job: DownloadJob) = synchronized(lock) {
        enqueued += job.id
        if (state.value.downloads[job.id] !is EngineStatus.Downloading) set(job.id, EngineStatus.Queued(waitingForNetwork = false))
    }

    override suspend fun remove(jobId: String) = synchronized(lock) {
        removed += jobId
        state.value = state.value.next { it - jobId }
    }
}

internal class FakeExporter : MediaExporter {
    val exported: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val discarded: MutableList<OutputFile> = java.util.Collections.synchronizedList(mutableListOf())
    val destinations: MutableList<OutputFolder?> = java.util.Collections.synchronizedList(mutableListOf())

    /** Replace to fail, block or report progress. */
    var behaviour: suspend (DownloadJob, (Float?) -> Unit) -> OutputFile = { _, progress ->
        progress(0.5f)
        testOutput
    }

    override suspend fun export(job: DownloadJob, destination: OutputFolder?, onProgress: (Float?) -> Unit): OutputFile {
        exported += job.id
        destinations += destination
        return behaviour(job, onProgress)
    }

    override suspend fun discard(output: OutputFile) {
        discarded += output
    }
}

internal class FakeResolver(var result: SourceResolution) : MediaSourceResolver {
    val resolved: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun resolve(input: String): SourceResolution {
        resolved += input
        gate?.await()
        return result
    }
}

internal val variant540 = HlsVariant(
    url = "https://cdn.example/show/v5/prog_index.m3u8",
    bandwidth = 2_177_116,
    resolution = Resolution(960, 540),
    codecs = listOf("avc1.640020", "mp4a.40.2"),
    audioGroupId = "aud1",
)

internal val testSource = ResolvedSource(
    inputUrl = "https://cdn.example/show/master.m3u8",
    manifestUrl = "https://cdn.example/show/master.m3u8",
    kind = ManifestKind.Multivariant,
    variants = listOf(variant540),
    audioRenditions = listOf(HlsAudioRendition("aud1", "English", url = "https://cdn.example/show/a1/prog_index.m3u8", isDefault = true)),
    defaultVariantIndex = 0,
    details = MediaDetails(600.0, 100, SegmentContainer.FragmentedMp4, HlsEncryption.None),
)

internal val unavailable = MediaError.HttpStatus(404)

/** Polls until [predicate] holds; these tests run on real dispatchers. */
internal suspend fun <T> eventually(timeoutMs: Long = 5_000, value: () -> T, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) {
        var current = value()
        while (!predicate(current)) {
            delay(5)
            current = value()
        }
        current
    }
