package `in`.geekofia.ripster

import `in`.geekofia.ripster.core.download.DownloadCoordinator
import `in`.geekofia.ripster.core.download.DownloadRepository
import `in`.geekofia.ripster.core.source.DefaultSourceResolver
import `in`.geekofia.ripster.core.settings.OutputSettingsRepository
import `in`.geekofia.ripster.core.source.HlsSourceResolver
import `in`.geekofia.ripster.core.source.MediaSourceResolver
import `in`.geekofia.ripster.platform.PlatformServices
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-scoped objects, created once per process by the platform entry point
 * (`RipsterApplication` on Android) and passed to [App]. Manual dependency injection, as in
 * MorseKit: anything shared across screens, or that must outlive them (the queue), lives here.
 *
 * Creating the container starts the queue: persisted jobs are recovered right away, even when
 * the process was started in the background by a download or export service.
 */
class AppContainer(val platformServices: PlatformServices) {
    /** Outlives every screen; work here continues while the app is in the background. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val outputSettings = OutputSettingsRepository(platformServices.settingsStore)

    /** Direct files, HLS manifests, and web pages that declare either (see [DefaultSourceResolver]). */
    val resolver: MediaSourceResolver = DefaultSourceResolver(platformServices.httpClient, HlsSourceResolver(platformServices.httpClient))

    val downloads = DownloadCoordinator(
        repository = DownloadRepository(platformServices.queueStore, appScope, platformServices.ioDispatcher),
        resolver = resolver,
        engine = platformServices.downloadEngine,
        exporter = platformServices.exporter,
        storage = platformServices.storage,
        outputFolder = { outputSettings.folder.value },
        scope = appScope,
        nowMillis = ::currentTimeMillis,
        newId = ::newJobId,
    ).also { it.start() }
}

/** Wall-clock time in epoch milliseconds. */
private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
private fun newJobId(): String = Uuid.random().toString()
