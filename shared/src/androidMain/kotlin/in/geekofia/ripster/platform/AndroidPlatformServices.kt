package `in`.geekofia.ripster.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import `in`.geekofia.ripster.core.download.OutputFile
import `in`.geekofia.ripster.core.download.StorageInfo
import `in`.geekofia.ripster.download.AndroidDownloads
import java.io.File

/**
 * Builds the Android [PlatformServices]. Uses the application context, so everything here can
 * outlive an Activity; [bridge] covers the few features that need one.
 */
fun androidPlatformServices(context: Context, downloads: AndroidDownloads, bridge: ActivityBridge): PlatformServices {
    val appContext = context.applicationContext
    return PlatformServices(
        httpClient = downloads.httpClient,
        queueStore = AtomicFileTextStore(File(appContext.filesDir, "download-queue.txt")),
        settingsStore = SharedPreferencesStore(appContext, "ripster_settings"),
        downloadEngine = downloads.engine,
        exporter = downloads.exporter,
        storage = StorageInfo { runCatching { StatFs(appContext.noBackupFilesDir.path).availableBytes }.getOrNull() },
        clipboard = AndroidClipboardReader(appContext),
        fileActions = AndroidFileActions(appContext),
        folderPicker = bridge,
        notificationPermission = bridge,
        appInfo = AppInfo(isDebugBuild = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0),
        ioDispatcher = downloads.ioDispatcher,
    )
}

private class AndroidClipboardReader(private val context: Context) : ClipboardReader {
    override fun readText(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }
}

/**
 * Opens and shares outputs with read-only, per-intent grants: app-storage files through a
 * FileProvider, SAF documents by their own content URI.
 */
private class AndroidFileActions(private val context: Context) : FileActions {
    override fun open(output: OutputFile): Boolean {
        val uri = contentUri(output) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, output.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent)
    }

    override fun share(output: OutputFile): Boolean {
        val uri = contentUri(output) ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(output.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(output.displayName, uri)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(chooser)
    }

    /** Null when an app-storage file no longer exists (e.g. deleted from a file manager). */
    private fun contentUri(output: OutputFile): Uri? {
        val uri = Uri.parse(output.uri)
        if (uri.scheme != "file") return uri
        val file = uri.path?.let(::File)?.takeIf { it.exists() } ?: return null
        return FileProvider.getUriForFile(context, "${context.packageName}.ripster.files", file)
    }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
