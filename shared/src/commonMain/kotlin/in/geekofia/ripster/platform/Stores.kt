package `in`.geekofia.ripster.platform

/**
 * Durable storage for one text document (the download queue). Writes are atomic: after a crash
 * either the old or the new text is read back, never a mix. Called off the main thread.
 */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

/** Small persistent preferences (`SharedPreferences` on Android). Null for keys never written. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** A non-persistent [TextStore] for tests and previews. */
class InMemoryTextStore(private var text: String? = null) : TextStore {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/** A non-persistent [KeyValueStore] for tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
