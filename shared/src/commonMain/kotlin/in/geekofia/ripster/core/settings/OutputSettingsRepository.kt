package `in`.geekofia.ripster.core.settings

import `in`.geekofia.ripster.core.export.OutputFolder
import `in`.geekofia.ripster.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where exports are saved: a user-chosen folder, or null for the app's own storage. */
class OutputSettingsRepository(private val store: KeyValueStore) {
    private val state = MutableStateFlow(load())

    val folder: StateFlow<OutputFolder?> = state.asStateFlow()

    fun setFolder(folder: OutputFolder?) {
        store.putString(KEY_URI, folder?.uri)
        store.putString(KEY_NAME, folder?.name)
        state.value = folder
    }

    private fun load(): OutputFolder? {
        val uri = store.getString(KEY_URI)?.takeIf { it.isNotEmpty() } ?: return null
        return OutputFolder(uri, store.getString(KEY_NAME)?.takeIf { it.isNotEmpty() } ?: "Chosen folder")
    }

    internal companion object {
        const val KEY_URI = "output.folder.uri"
        const val KEY_NAME = "output.folder.name"
    }
}
