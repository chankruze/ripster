package `in`.geekofia.ripster.platform

import `in`.geekofia.ripster.core.download.DownloadEngine
import `in`.geekofia.ripster.core.download.OutputFile
import `in`.geekofia.ripster.core.download.StorageInfo
import `in`.geekofia.ripster.core.export.MediaExporter
import `in`.geekofia.ripster.core.export.OutputFolder
import kotlinx.coroutines.CoroutineDispatcher

/** Reads plain text from the system clipboard (user-initiated Paste only). */
fun interface ClipboardReader {
    fun readText(): String?
}

/** Opens or shares an exported file with other apps. Returns false if no app can handle it. */
interface FileActions {
    fun open(output: OutputFile): Boolean
    fun share(output: OutputFile): Boolean
}

/** Lets the user choose an output folder (Android: Storage Access Framework). */
fun interface OutputFolderPicker {
    fun pick(onPicked: (OutputFolder) -> Unit)
}

/** Asks for permission to show download progress notifications, where the platform requires it. */
fun interface NotificationPermission {
    fun requestIfNeeded()
}

class AppInfo(
    /** Debug builds show the sample-stream picker. */
    val isDebugBuild: Boolean,
)

/**
 * Platform capabilities the shared code depends on, built by the platform entry point and passed
 * down explicitly: no DI framework or globals (the same approach as MorseKit).
 */
class PlatformServices(
    val httpClient: HttpClient,
    val queueStore: TextStore,
    val settingsStore: KeyValueStore,
    val downloadEngine: DownloadEngine,
    val exporter: MediaExporter,
    val storage: StorageInfo,
    val clipboard: ClipboardReader,
    val fileActions: FileActions,
    val folderPicker: OutputFolderPicker,
    val notificationPermission: NotificationPermission,
    val appInfo: AppInfo,
    val ioDispatcher: CoroutineDispatcher,
)
