package `in`.geekofia.ripster.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import `in`.geekofia.ripster.core.download.OutputFile
import `in`.geekofia.ripster.core.export.FileNames
import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import java.io.File
import java.io.IOException

/**
 * Where exported MP4s go. Exports are written to a partial file first and only published once
 * validated, so a half-written file never appears under its final name.
 *
 * - No folder chosen: the app's external files `Movies` directory (no permission needed on any
 *   API level; shared with other apps through a FileProvider). Names are made unique, so an
 *   existing file is never overwritten.
 * - A folder chosen with the Storage Access Framework: a new document is created there (the
 *   provider de-duplicates the name), and the partial file is copied into it.
 */
internal class OutputPublisher(private val context: Context) {
    private val appDirectory: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "exports"))
            .also { it.mkdirs() }

    /** On the same volume as [appDirectory], so publishing there is an atomic rename, not a copy. */
    private val partialDirectory: File
        get() = File(appDirectory, ".partial").also { it.mkdirs() }

    /** A fresh partial file for [jobId]. Exports run one at a time, so any other partial is stale. */
    fun newPartialFile(jobId: String): File {
        partialDirectory.listFiles()?.forEach { it.delete() }
        return File(partialDirectory, "$jobId.mp4")
    }

    fun publish(partial: File, title: String, destination: OutputFolder?): OutputFile {
        val baseName = FileNames.sanitize(title)
        return if (destination == null) publishToAppStorage(partial, baseName) else publishToFolder(partial, baseName, destination)
    }

    fun discard(output: OutputFile) {
        val uri = Uri.parse(output.uri)
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).delete() }
            "content" -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }

    private fun publishToAppStorage(partial: File, baseName: String): OutputFile {
        val directory = appDirectory
        val name = FileNames.unique(baseName, "mp4") { File(directory, it).exists() }
        val target = File(directory, name)
        try {
            // Same directory tree, so this is normally a rename; copy if the platform refuses.
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = false)
                partial.delete()
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return OutputFile(Uri.fromFile(target).toString(), name, target.length(), APP_STORAGE_LABEL)
    }

    private fun publishToFolder(partial: File, baseName: String, folder: OutputFolder): OutputFile {
        val resolver = context.contentResolver
        val tree = Uri.parse(folder.uri)
        val document = try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, MIME_TYPE, "$baseName.mp4")
        } catch (e: SecurityException) {
            throw MediaException(MediaError.OutputUnavailable("Ripster no longer has access to \"${folder.name}\"."), e)
        } catch (e: Exception) {
            throw MediaException(MediaError.OutputUnavailable("\"${folder.name}\" can't be written."), e)
        } ?: throw MediaException(MediaError.OutputUnavailable("\"${folder.name}\" can't be written."))

        try {
            val output = resolver.openOutputStream(document, "w") ?: throw IOException("No output stream")
            output.use { out -> partial.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        } finally {
            partial.delete()
        }
        val name = displayName(document) ?: "$baseName.mp4"
        return OutputFile(document.toString(), name, sizeOf(document), folder.name)
    }

    private fun displayName(document: Uri): String? =
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun sizeOf(document: Uri): Long =
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    companion object {
        const val MIME_TYPE = "video/mp4"
        const val APP_STORAGE_LABEL = "App storage"
    }
}
