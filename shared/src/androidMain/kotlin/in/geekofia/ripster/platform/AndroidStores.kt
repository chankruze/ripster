package `in`.geekofia.ripster.platform

import android.content.Context
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException

/** [TextStore] on an [AtomicFile]: a crash mid-write leaves the previous version readable. */
internal class AtomicFileTextStore(file: File) : TextStore {
    private val atomicFile = AtomicFile(file)

    override fun read(): String? = try {
        atomicFile.readFully().decodeToString()
    } catch (e: IOException) {
        null // Never written yet (or unreadable): start with an empty queue.
    }

    override fun write(text: String) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(text.encodeToByteArray())
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            atomicFile.failWrite(stream)
            throw e
        }
    }
}

internal class SharedPreferencesStore(context: Context, name: String) : KeyValueStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit { if (value == null) remove(key) else putString(key, value) }
    }
}
