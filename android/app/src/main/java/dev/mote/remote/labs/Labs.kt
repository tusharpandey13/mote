package dev.mote.remote.labs

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import dev.mote.remote.ui.Hairline
import dev.mote.remote.ui.SettingRow
import dev.mote.remote.ui.SettingsCard
import dev.mote.remote.ui.Switch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A preview feature behind a toggle in Settings › Labs. Off by default. */
data class LabFlag(val key: String, val title: String, val summary: String)

/** Labs flags (DataStore "labs"). Register a preview with one line in [flags]. */
object Labs {
    const val LIVE_SCREEN = "live_screen"

    val flags = listOf(
        LabFlag(LIVE_SCREEN, "Live screen (preview)", "See the Mac's screen and tap to click. Needs Mote Helper over Wi-Fi or Tailscale."),
    )

    private val Context.store by preferencesDataStore("labs", corruptionHandler = Prefs.corruptionHandler)
    private fun pref(key: String) = booleanPreferencesKey("lab_$key")

    /** Whether the flag [key] is on (false until set). */
    fun on(c: Context, key: String): Flow<Boolean> = c.store.data.map { it.typed(pref(key)) ?: false }
    suspend fun set(c: Context, key: String, on: Boolean) { c.store.edit { it[pref(key)] = on } }

    /** Per-lab one-time state (e.g. a warning that was acknowledged), kept in the same file. */
    fun seen(c: Context, key: String): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey("seen_$key")) ?: false }
    suspend fun markSeen(c: Context, key: String) { c.store.edit { it[booleanPreferencesKey("seen_$key")] = true } }
}

/** `remember`ed flag state for composables. */
@Composable
fun labOn(key: String): Boolean {
    val ctx = LocalContext.current
    val on by remember(key) { Labs.on(ctx, key) }.collectAsState(initial = false)
    return on
}

/** Settings › Labs: one switch per [Labs.flags] entry. */
@Composable
fun LabsSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsCard {
        Labs.flags.forEachIndexed { i, f ->
            if (i > 0) Hairline()
            val on = labOn(f.key)
            SettingRow(f.title, f.summary) { Switch(on, f.title) { v -> scope.launch { Labs.set(ctx, f.key, v) } } }
        }
    }
}
