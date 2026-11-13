package dev.mote.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mote.remote.diag.Events
import dev.mote.remote.net.Net
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTransport
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay

/** U18: a helper link that has been Connecting this long stops being a quiet pulse and says what to check. */
const val SEARCHING_AFTER_MS = 15_000L

/** Pure: the "still looking" card shows once the link has been connecting (and not failed or ready) for [SEARCHING_AFTER_MS]. */
fun searchingCardVisible(connecting: Boolean, connectingForMs: Long) = connecting && connectingForMs >= SEARCHING_AFTER_MS

val SEARCHING_CAUSES = listOf(
    "The Mac is asleep or away: wake it (open the lid or press a key).",
    "Mote Helper isn't running: open it from ~/Applications on the Mac.",
    "This phone is on another Wi-Fi, or Tailscale is off: join the Mac's network.",
)

/** Shown on the remote screen under the header while the selected Mac won't answer (Wi-Fi / Tailscale helper only). */
@Composable
fun SearchingCard(onDevices: () -> Unit) {
    val c = LocalMote.current
    val active by Net.active.collectAsState()
    val ws by Net.wsStatus.collectAsState()
    val saved by Net.saved.collectAsState()
    val connecting = active is WsTransport && ws is WsStatus.Connecting
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(connecting, (active as? WsTransport)?.hostId) {
        shown = false
        if (connecting) { delay(SEARCHING_AFTER_MS); shown = searchingCardVisible(true, SEARCHING_AFTER_MS) }
    }
    if (!shown || !connecting) return
    val name = saved.firstOrNull { it.hostId == (active as WsTransport).hostId }?.name ?: "your Mac"
    LaunchedEffect(Unit) { Events.log("ui", "banner", "kind" to "searching", "host" to name) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).well(RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Still looking for $name", style = T.label, color = c.ink)
        SEARCHING_CAUSES.forEach { Text("• $it", style = T.small, color = c.muted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Try again", Modifier.weight(1f)) { (Net.active.value as? WsTransport)?.kick() }
            SecondaryButton("Devices", Modifier.weight(1f)) { onDevices() }
        }
    }
}
