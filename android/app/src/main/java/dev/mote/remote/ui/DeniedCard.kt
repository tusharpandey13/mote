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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mote.remote.diag.Events
import dev.mote.remote.net.Net
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTransport
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.well

const val DENIED_TITLE = "This Mac doesn't recognise this phone"
const val KEYCHAIN_READING_TEXT = "The Mac is reading its Keychain; trying again…"

/** Pure (B6): the helper's `labs.pair.denied` reason in plain words. */
fun denialText(reason: String): String = when (reason) {
    "far" -> "Bring the phone close to the Mac, with Bluetooth on."
    "stale" -> "The nearby check expired. Bring the phone close to the Mac and try again."
    "bad-proximity" -> "The nearby check failed. Bring the phone close to the Mac and try again."
    "blocked" -> "This Mac blocked this phone; unblock it in Mote Helper."
    "denied" -> "Someone at the Mac said no, or the Mac forgot this phone. Pair again, or ask for approval."
    "busy" -> "The Mac is answering another phone. Try again in a moment."
    else -> "The Mac refused this phone ($reason)."
}

/** B6: shown instead of a silent retry loop. The link retries on its own only every 30 s or slower. */
@Composable
fun DeniedCard(onDevices: () -> Unit) {
    val c = LocalMote.current
    val active by Net.active.collectAsState()
    val ws by Net.wsStatus.collectAsState()
    val saved by Net.saved.collectAsState()
    val t = active as? WsTransport ?: return
    when (val st = ws) {
        is WsStatus.Reading -> Text(KEYCHAIN_READING_TEXT, style = T.small, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        is WsStatus.Denied -> {
            val name = saved.firstOrNull { it.hostId == t.hostId }?.name ?: "your Mac"
            LaunchedEffect(st.reason) { Events.log("ui", "banner", "kind" to "denied", "reason" to st.reason) }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).well(RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(DENIED_TITLE, style = T.label, color = c.ink)
                Text("$name: ${denialText(st.reason)}", style = T.small, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Try again", Modifier.weight(1f)) { t.kick() }
                    SecondaryButton("Pair with code", Modifier.weight(1f)) { onDevices() }
                }
                SecondaryButton("Remove this Mac", Modifier.fillMaxWidth()) { Net.forget(t.hostId) }
            }
        }
        else -> Unit
    }
}
