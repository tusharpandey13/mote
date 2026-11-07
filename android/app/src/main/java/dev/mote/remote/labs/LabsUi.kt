package dev.mote.remote.labs

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
import dev.mote.remote.ui.Hairline
import dev.mote.remote.ui.SettingRow
import dev.mote.remote.ui.SettingsCard
import dev.mote.remote.ui.Switch
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import kotlinx.coroutines.launch

/** Settings › Labs: one switch per [Labs.all] entry. */
@Composable
fun LabsSection() {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Text("LABS", style = T.caps, color = c.subtle, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
    SettingsCard {
        Labs.all.forEachIndexed { i, f ->
            if (i > 0) Hairline()
            val on by remember(f) { Labs.flow(ctx, f) }.collectAsState(initial = false)
            SettingRow(f.title, f.summary) { Switch(on, f.title) { v -> scope.launch { Labs.set(ctx, f, v) } } }
        }
    }
}
