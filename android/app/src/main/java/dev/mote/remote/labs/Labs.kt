package dev.mote.remote.labs

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A preview feature behind a switch in Settings › Labs. All default off. */
data class LabFlag(val key: String, val title: String, val summary: String)

/** Labs (preview features). Each lab registers one line in [flags]. */
object Labs {
    val SMART_REMOTE = LabFlag(
        "smart_remote", "Smart remote (preview)",
        "Adapts the Media screen to what is on the Mac: now playing with a scrubber, app-specific buttons, keyboard on text fields. Needs the Mote Helper over Wi-Fi with Labs › Context & now playing on.",
    )

    val flags: List<LabFlag> = listOf(
        SMART_REMOTE,
    )

    private val Context.store by preferencesDataStore("mote_labs", corruptionHandler = Prefs.corruptionHandler)

    fun enabled(c: Context, f: LabFlag): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey(f.key)) ?: false }
    suspend fun set(c: Context, f: LabFlag, on: Boolean) { c.store.edit { it[booleanPreferencesKey(f.key)] = on } }

    /** Extra per-lab booleans (same store), e.g. a lab's sub-option. */
    fun bool(c: Context, key: String, default: Boolean): Flow<Boolean> = c.store.data.map { it.typed(booleanPreferencesKey(key)) ?: default }
    suspend fun setBool(c: Context, key: String, on: Boolean) { c.store.edit { it[booleanPreferencesKey(key)] = on } }
}

/** Settings › Labs: one switch per [Labs.flags] entry. */
@Composable
fun LabsSettingsSection() {
    val ctx = LocalContext.current
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    Text("LABS", style = T.caps, color = c.subtle, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
    SettingsCard {
        Labs.flags.forEachIndexed { i, f ->
            if (i > 0) Hairline()
            val on by remember(f) { Labs.enabled(ctx, f) }.collectAsState(initial = false)
            SettingRow(f.title, f.summary) { Switch(on, f.title) { v -> scope.launch { Labs.set(ctx, f, v) } } }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Previews can change or go away. They are off until you turn them on.",
        style = T.body.copy(fontSize = 13.sp), color = c.muted, modifier = Modifier.padding(horizontal = 4.dp),
    )
}
