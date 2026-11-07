package dev.mote.remote.labs

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import dev.mote.remote.ui.Hairline
import dev.mote.remote.ui.SettingRow
import dev.mote.remote.ui.SettingsCard
import dev.mote.remote.ui.Switch
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One preview feature behind a switch in Settings › Labs. Default off. */
data class LabFlag(val key: String, val title: String, val summary: String)

/** Labs flags (DataStore "labs"). Add a feature by appending one line to [flags]. */
object Labs {
    const val INSTANT_PAIRING = "instant_pairing"

    val flags = listOf(
        LabFlag(INSTANT_PAIRING, "Instant pairing (preview)", "Find Macs over Bluetooth and connect without a code. Needs Instant pairing on in the Mote Helper menu too."),
    )

    private val Context.store by preferencesDataStore("labs", corruptionHandler = Prefs.corruptionHandler)
    private fun pref(key: String) = booleanPreferencesKey("lab_$key")

    fun flow(c: Context, key: String): Flow<Boolean> = c.applicationContext.store.data.map { it.typed(pref(key)) ?: false }
    suspend fun isOn(c: Context, key: String): Boolean = flow(c, key).first()
    suspend fun set(c: Context, key: String, on: Boolean) { c.applicationContext.store.edit { it[pref(key)] = on } }
}

/** Settings › Labs: one switch per [Labs.flags] entry. */
@Composable
fun LabsSection() {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Text("LABS", style = T.caps, color = c.subtle, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
    SettingsCard {
        Labs.flags.forEachIndexed { i, f ->
            if (i > 0) Hairline()
            val on by Labs.flow(ctx, f.key).collectAsState(initial = false)
            SettingRow(f.title, f.summary) { Switch(on, f.title) { v -> scope.launch { Labs.set(ctx, f.key, v) } } }
        }
    }
}
