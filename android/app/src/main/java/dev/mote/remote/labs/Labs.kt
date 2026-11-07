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
    val INSTANT_PAIRING = LabFlag("instant_pairing", "Instant pairing (preview)", "Find Macs over Bluetooth and connect without a code. Needs Mote Helper with Labs › Instant pairing on.")
    val OPEN_TV = LabFlag("open_tv", "Open & TV mode (preview)", "An Open… sheet that drives the Mac, and a TV remote for YouTube. Needs Mote Helper with Labs › TV mode on.")
    val VIDEO_BRIDGE = LabFlag("labs_video_bridge", "Browser video control (preview)", "Control the video in the Mac's browser tab with the Mote Video Bridge extension. Needs Mote Helper with Labs › Browser extension bridge on.")

    val SMART_REMOTE = LabFlag("smart_remote", "Smart remote (preview)", "Adapts Media to what is on the Mac: now playing with a scrubber, app buttons, keyboard on text fields. Needs Mote Helper with Labs › Context & now playing on.")

    /** Shown in Settings › Labs, in this order. */
    val all: List<LabFlag> = listOf(
        OPEN_TV,
        INSTANT_PAIRING,
        SMART_REMOTE,
        VIDEO_BRIDGE,
    )

    private val Context.store by preferencesDataStore("labs", corruptionHandler = Prefs.corruptionHandler)

    fun flow(c: Context, f: LabFlag): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey(f.key)) ?: false }

    suspend fun set(c: Context, f: LabFlag, on: Boolean) {
        c.store.edit { it[booleanPreferencesKey(f.key)] = on }
    }

    /** Extra per-lab booleans in the same store (a preview's own sub-option). */
    fun bool(c: Context, key: String, default: Boolean): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey(key)) ?: default }
    suspend fun setBool(c: Context, key: String, on: Boolean) { c.store.edit { it[booleanPreferencesKey(key)] = on } }
}
