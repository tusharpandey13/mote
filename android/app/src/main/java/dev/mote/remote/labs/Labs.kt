package dev.mote.remote.labs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A preview feature behind a Settings › Labs switch. [key] is its DataStore key; off by default. */
data class LabFlag(val key: String, val title: String, val summary: String)

/** Preview features (docs/labs/). Add a flag: one line in [all]; Settings lists them in order. */
object Labs {
    val VIDEO_BRIDGE = LabFlag(
        "labs_video_bridge", "Browser video control (preview)",
        "Control the video in the Mac's browser tab with the Mote Video Bridge extension. Needs Mote Helper with Labs › Browser extension bridge on.",
    )

    val all = listOf(
        VIDEO_BRIDGE,
    )

    private val Context.store by preferencesDataStore("mote_labs", corruptionHandler = Prefs.corruptionHandler)

    fun flow(c: Context, flag: LabFlag): Flow<Boolean> =
        c.store.data.map { it.typed(booleanPreferencesKey(flag.key)) ?: false }.distinctUntilChanged()

    suspend fun set(c: Context, flag: LabFlag, on: Boolean) { c.store.edit { it[booleanPreferencesKey(flag.key)] = on } }
}
