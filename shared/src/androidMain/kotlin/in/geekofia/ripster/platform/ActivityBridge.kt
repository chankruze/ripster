package `in`.geekofia.ripster.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import `in`.geekofia.ripster.core.export.OutputFolder

/**
 * The few features that need an Activity: the SAF folder picker and the notification permission
 * prompt. App-scoped; the current Activity attaches in `onCreate`. The pending callback survives
 * Activity recreation (the result is delivered to the new Activity's launcher), and it only
 * writes to app-scoped settings.
 */
class ActivityBridge : OutputFolderPicker, NotificationPermission {
    private var activity: ComponentActivity? = null
    private var folderLauncher: ActivityResultLauncher<Uri?>? = null
    private var permissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingFolder: ((OutputFolder) -> Unit)? = null
    private var askedForNotifications = false

    /** Call from `onCreate`, before the Activity is started (a launcher requirement). */
    fun attach(activity: ComponentActivity) {
        this.activity = activity
        folderLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val callback = pendingFolder
            pendingFolder = null
            if (uri != null && callback != null) callback(persist(activity, uri))
        }
        permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Downloads work either way; without it the progress notification is just hidden.
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (this@ActivityBridge.activity === activity) {
                    this@ActivityBridge.activity = null
                    folderLauncher = null
                    permissionLauncher = null
                }
            }
        })
    }

    override fun pick(onPicked: (OutputFolder) -> Unit) {
        val launcher = folderLauncher ?: return
        pendingFolder = onPicked
        launcher.launch(null)
    }

    override fun requestIfNeeded() {
        val activity = activity ?: return
        if (Build.VERSION.SDK_INT < 33 || askedForNotifications) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Keeps write access across restarts and reads the folder's display name. */
    private fun persist(activity: ComponentActivity, tree: Uri): OutputFolder {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(tree, flags)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = activity.contentResolver
            .query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return OutputFolder(tree.toString(), name ?: "Chosen folder")
    }
}
