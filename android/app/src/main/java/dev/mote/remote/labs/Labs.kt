package dev.mote.remote.labs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One preview feature behind a toggle in Settings › Labs. Default off. */
data class LabFlag(val key: String, val title: String, val summary: String)

/** Labs: preview features, each off until turned on in Settings › Labs. Register a preview with one line in [all]. */
object Labs {
    val OPEN_TV = LabFlag("open_tv", "Open & TV mode (preview)", "An Open… sheet that drives the Mac, and a TV remote for YouTube. Needs Mote Helper with Labs › TV mode on.")

    /** Shown in Settings › Labs, in this order. */
    val all: List<LabFlag> = listOf(
        OPEN_TV,
    )

    private val Context.store by preferencesDataStore("labs", corruptionHandler = Prefs.corruptionHandler)

    fun flow(c: Context, f: LabFlag): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey(f.key)) ?: false }

    suspend fun set(c: Context, f: LabFlag, on: Boolean) {
        c.store.edit { it[booleanPreferencesKey(f.key)] = on }
    }
}
