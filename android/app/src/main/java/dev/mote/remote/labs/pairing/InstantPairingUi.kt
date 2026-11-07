package dev.mote.remote.labs.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Net
import dev.mote.remote.ui.MoteSheet
import dev.mote.remote.ui.PrimaryButton
import dev.mote.remote.ui.SecondaryButton
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val dismissed = HashSet<String>() // hostIds whose auto sheet was closed this process

/** Live candidates: BLE + Bonjour, minus Macs already saved on this phone. */
@Composable
private fun untrusted(): List<Candidate> {
    val ble by InstantPairing.ble.found.collectAsState()
    val bonjour by Net.discovery.found.collectAsState()
    val saved by Net.saved.collectAsState()
    return candidates(ble, bonjour).filter { c -> saved.none { it.hostId == c.hostId } }
}

/**
 * Root hook: with the Labs flag on, scans BLE for 20 s while the remote is on screen and pops an
 * AirPods-style sheet for the first nearby Mac this phone doesn't know yet.
 */
@Composable
fun InstantPairingHost() {
    val on by InstantPairing.enabled.collectAsState()
    if (!on) return
    DisposableEffect(Unit) { InstantPairing.ble.start(); onDispose { InstantPairing.ble.stop() } }
    val list = untrusted()
    var shown by remember { mutableStateOf<Candidate?>(null) }
    LaunchedEffect(list) { if (shown == null) shown = list.firstOrNull { it.hostId !in dismissed && it.info != null } }
    val c = shown ?: return
    MoteSheet(onDismiss = { dismissed += c.hostId; shown = null }) { close -> ConnectCard(c, close) }
}

/** HostSheet hook: nearby Macs with a Connect button, and Tailscale-trusted notes. */
@Composable
fun InstantPairingRows(close: () -> Unit) {
    val on by InstantPairing.enabled.collectAsState()
    if (!on) return
    val c = LocalMote.current
    DisposableEffect(Unit) { InstantPairing.ble.start(); onDispose { InstantPairing.ble.stop() } }
    val list = untrusted()
    val records by InstantPairing.records.collectAsState()
    val saved by Net.saved.collectAsState()
    var picked by remember { mutableStateOf<Candidate?>(null) }
    picked?.let { p -> Column(Modifier.fillMaxWidth().well(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(12.dp)) { ConnectCard(p, close) }; return }
    if (list.isNotEmpty()) {
        Text("Nearby (Instant pairing)", style = T.label, color = c.ink)
        list.forEach { m -> SecondaryButton("Connect to ${m.name}") { picked = m } }
    }
    records.values.filter { it.via == "tailscale" }.forEach { r ->
        val name = saved.firstOrNull { it.hostId == r.hostId }?.name ?: return@forEach
        Text("$name: Trusted via Tailscale", style = T.small, color = c.subtle)
    }
}

private sealed interface Step {
    data object Ask : Step
    data object Connecting : Step
    data class Waiting(val timeout: Int) : Step
    data class Done(val via: String) : Step
    data class Error(val msg: String) : Step
}

private fun friendly(e: Throwable, mac: String): String = when ((e as? HelperError)?.code) {
    "denied" -> "$mac didn't allow this phone."
    "rate" -> "Too many requests. Try again in ${(e as HelperError).retryAfter ?: 60} s."
    "unauth" -> "Turn on Labs › Instant pairing in the Mote Helper menu on $mac."
    "helper-key" -> "That isn't the Mac it claims to be (its key doesn't match)."
    "timeout" -> "$mac didn't answer in time."
    else -> e.message ?: "Couldn't connect to $mac."
}

/** "Connect to <Mac>?": glyph, name, fingerprint, Connect; then waiting / done / error. */
@Composable
fun ConnectCard(m: Candidate, close: () -> Unit) {
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Ask) }
    LaunchedEffect(step) { if (step is Step.Done) { delay(if ((step as Step.Done).via == "tailscale") 2000 else 900); close() } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(88.dp).well(CircleShape), contentAlignment = Alignment.Center) { MIcon("laptop", c.ink, size = 44.dp) }
        Text(m.name, style = T.title, color = c.ink, textAlign = TextAlign.Center)
        m.fp?.let { Text("Key ${PairProto.groupFp(it).take(19)}…", style = T.small, color = c.subtle) }
        when (val s = step) {
            Step.Ask -> {
                Text("Connect to ${m.name}?", style = T.body, color = c.muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Connect") {
                    step = Step.Connecting
                    scope.launch {
                        step = try {
                            val done = InstantPairing.connect(m) { st -> if (st is PairState.Pending) step = Step.Waiting(st.timeout) }
                            Step.Done(done.via)
                        } catch (e: Exception) { Step.Error(friendly(e, m.name)) }
                    }
                }
                SecondaryButton("Not now", onClick = close)
            }
            Step.Connecting -> Text("Connecting…", style = T.body, color = c.muted)
            is Step.Waiting -> Text("Waiting for ${m.name} to allow this phone. Click Allow on the Mac.", style = T.body, color = c.muted, textAlign = TextAlign.Center)
            is Step.Done -> Text(if (s.via == "tailscale") "Connected. Trusted via Tailscale" else "Connected", style = T.label, color = c.ink)
            is Step.Error -> {
                Text(s.msg, style = T.body, color = c.danger, textAlign = TextAlign.Center)
                SecondaryButton("Try again") { step = Step.Ask }
            }
        }
    }
}
