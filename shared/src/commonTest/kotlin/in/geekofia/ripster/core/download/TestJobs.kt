package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.NetworkProblem

/** Shared fixtures for queue tests. */
internal fun testJob(
    id: String = "job-1",
    state: DownloadState = DownloadState.Queued,
    sourceUrl: String = "https://cdn.example/show/master.m3u8",
    variantUrl: String? = "https://cdn.example/show/v5/prog_index.m3u8",
    createdAt: Long = 1_000,
) = DownloadJob(
    id = id,
    title = "Show 540p",
    sourceUrl = sourceUrl,
    manifestUrl = sourceUrl,
    isSample = false,
    selection = JobSelection(
        variantUrl = variantUrl,
        audioUrl = "https://cdn.example/show/a1/prog_index.m3u8",
        bandwidth = 2_177_116,
        resolution = "960×540",
        codecs = listOf("avc1.640020", "mp4a.40.2"),
    ),
    durationSeconds = 600.0,
    state = state,
    createdAtMillis = createdAt,
    updatedAtMillis = createdAt,
)

internal val testOutput = OutputFile("file:///movies/Show%20540p.mp4", "Show 540p.mp4", 42_000_000, "App storage")

internal val networkError = MediaError.Network(NetworkProblem.Offline)
