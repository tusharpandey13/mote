package dev.mote.remote.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mote.remote.hid.BtEnv
import dev.mote.remote.hid.BtGate
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.HidState
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.Phase
import dev.mote.remote.net.Found
import dev.mote.remote.net.HelperError
import dev.mote.remote.net.HostRow
import dev.mote.remote.net.Msg
import dev.mote.remote.net.Net
import dev.mote.remote.net.Route
import dev.mote.remote.net.Selection
import dev.mote.remote.net.Tailscale
import dev.mote.remote.net.Transport
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTransport
import dev.mote.remote.net.hostRows
import dev.mote.remote.net.parseAddress
import dev.mote.remote.net.routeOf
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HELPER_URL = "tusharpandey13.github.io/mote"

private fun routeIcon(r: Route) = when (r) { Route.BT -> "bluetooth"; Route.WIFI -> "wifi"; Route.TAILNET -> "globe" }

/** Transport badge: caps text in a hairline capsule. */
@Composable
fun RouteBadge(r: Route, active: Boolean = false) {
    val c = LocalMote.current
    Box(
        Modifier.border(1.dp, if (active) c.accent else c.outline, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text(r.badge.uppercase(), style = T.caps, color = if (active) c.accent else c.subtle, maxLines = 1) }
}

/** Device selector: LED, host name, transport badge. Replaces DevicePill for mixed BT / helper hosts. */
@Composable
fun HostPill(onClick: () -> Unit) {
    val c = LocalMote.current
    val s by Hid.state.collectAsState()
    val active by Net.active.collectAsState()
    val ws by Net.wsStatus.collectAsState()
    val saved by Net.saved.collectAsState()
    val onWifiNow by Net.onWifiFlow.collectAsState()
    val name: String; val led: androidx.compose.ui.graphics.Color; val pulse: Boolean; val route: Route?
    if (active is WsTransport) {
        val h = saved.firstOrNull { it.hostId == (active as WsTransport).hostId }
        name = h?.name ?: "Mac"
        // Not connected: show the route it will use from here (off Wi-Fi, a tailnet host goes via Tailscale).
        route = (ws as? WsStatus.Ready)?.address?.let(::routeOf)
            ?: h?.routes?.let { r -> if (!onWifiNow && Route.TAILNET in r) Route.TAILNET else r.firstOrNull() }
        val st = ws
        led = when {
            st is WsStatus.Ready && (st.paused || !st.ax) -> c.linkWait
            st is WsStatus.Ready -> c.linkOk
            st is WsStatus.Failed -> c.linkBad
            else -> c.linkWait
        }
        pulse = st is WsStatus.Connecting
    } else {
        val searching = !s.isConnected && s.selected != null && !s.unpaired && s.phase == Phase.READY
        led = when {
            s.isConnected -> c.linkOk
            searching || s.phase == Phase.REGISTERING -> c.linkWait
            else -> c.linkBad
        }
        pulse = searching
        name = s.connected?.name ?: s.selected?.let { if (s.unpaired) "Not paired: pair again" else it.name } ?: "Choose a device"
        route = if (s.connected != null || s.selected != null) Route.BT else null
    }
    Cap(
        Modifier.height(40.dp).widthIn(max = 260.dp), shape = RoundedCornerShape(50), needsLink = false,
        description = "Device: $name${route?.let { " via ${it.badge}" } ?: ""}. Tap to choose", onClick = onClick,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Led(led, pulse)
            Spacer(Modifier.size(10.dp))
            Text(name, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            route?.let { Spacer(Modifier.size(6.dp)); RouteBadge(it) }
            Spacer(Modifier.size(4.dp))
            MIcon("chevDown", c.muted, size = 20.dp)
        }
    }
}

/** Secondary action: flat cap with a hairline (design: flat-outline = not the main path). */
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalMote.current
    val shape = RoundedCornerShape(18.dp)
    Cap(modifier.fillMaxWidth().height(56.dp).border(1.dp, c.outline, shape), shape, flat = true, needsLink = false, description = label, onClick = onClick) {
        Text(label, style = T.label, color = c.ink)
    }
}

/** First-run gate's second path: no Bluetooth permission needed. */
@Composable
fun WifiGateOption(primary: Boolean, onWifi: () -> Unit) {
    val c = LocalMote.current
    Spacer(Modifier.height(if (primary) 24.dp else 12.dp))
    if (primary) PrimaryButton("Use Wi-Fi with Mote Helper", onClick = onWifi) else SecondaryButton("Use Wi-Fi with Mote Helper", onClick = onWifi)
    Spacer(Modifier.height(8.dp))
    Text("Needs the free Mote Helper app on the Mac ($HELPER_URL). Adds Unicode typing, precise brightness and click-at-point.",
        style = T.small, color = c.subtle)
}

private enum class Page { LIST, NEARBY, ADDRESS, CODE }

/**
 * The host picker: one list mixing Bluetooth HID hosts and Mote Helper hosts (grouped by hostId /
 * name), plus the add flows (nearby, enter address, pairing code / link). Scans the LAN while open.
 */
@Composable
fun HostSheet(
    s: HidState, bt: BtEnv, startAdding: Boolean,
    onBtSetup: () -> Unit, onPickBt: (Host) -> Unit, close: () -> Unit,
) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Net.saved.collectAsState()
    val found by Net.discovery.found.collectAsState()
    val scanning by Net.discovery.scanning.collectAsState()
    val selection by Net.selection.collectAsState()
    val active by Net.active.collectAsState()
    val ws by Net.wsStatus.collectAsState()
    val link by Net.pendingLink.collectAsState()

    var page by remember { mutableStateOf(if (startAdding) Page.NEARBY else Page.LIST) }
    var target by remember { mutableStateOf<String?>(null) }  // address being paired
    var targetPort by remember { mutableStateOf(8790) }
    var targetName by remember { mutableStateOf("") }
    var tailnetHint by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { Net.discovery.start(); onDispose { Net.discovery.stop() } }
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        Net.pendingLink.value = null
        Net.chooseWifi() // a pairing link is a Wi-Fi choice: keep the first-run gate out of the way
        target = l.host; targetPort = l.port; tailnetHint = l.tailnet; code = l.code
        targetName = saved.firstOrNull { it.hostId == l.hostId }?.name ?: l.host
        error = null; page = Page.CODE
    }

    fun goCode(addr: String, port: Int, name: String) {
        target = addr; targetPort = port; targetName = name; code = ""; error = null; page = Page.CODE
    }

    fun doPair() {
        val a = target ?: return
        if (code.length != 6) { error = "Enter the 6-digit code shown on the Mac."; return }
        busy = true; error = null
        scope.launch {
            val ts = tailnetHint
            try {
                try {
                    Net.pair(a, targetPort, code, ts)
                } catch (e: Exception) {
                    // Link from the Mac's QR (LAN address) opened away from that LAN: pair over the tailnet.
                    if (e is HelperError || ts == null || ts == a) throw e
                    if (Net.promptForTailscale(targetName, listOf(Route.TAILNET), seenOnLan = false) { doPair() }) return@launch
                    Net.pair(ts, targetPort, code, a)
                }
                close()
            } catch (e: Exception) {
                error = friendly(e, ts ?: a)
            } finally { busy = false }
        }
    }

    fun doAddress() {
        val (host, port) = parseAddress(address) ?: run { error = "Enter a name like mac.tailnet.ts.net or an address like 100.101.102.103."; return }
        val knownName = saved.firstOrNull { host in it.candidates() }?.name ?: host
        if (routeOf(host) == Route.TAILNET && Net.promptForTailscale(knownName, listOf(Route.TAILNET), seenOnLan = false) { doAddress() }) return
        busy = true; error = null
        scope.launch {
            try {
                val hello: Msg.Hello = Net.probe(host, port)
                val known = saved.firstOrNull { it.hostId == hello.hostId }
                if (known != null) { Net.addAddress(known.hostId, host); close() }
                else {
                    goCode(host, port, hello.host.ifBlank { host })
                    if (!hello.pairing) error = "Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu."
                }
            } catch (e: Exception) {
                error = friendly(e, host)
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (page) {
            Page.LIST -> {
                Text("Devices", style = T.title, color = c.ink)
                val note = when {
                    ws is WsStatus.Failed -> failedText(ws as WsStatus.Failed)
                    s.phase == Phase.UNSUPPORTED -> "This phone can't be a Bluetooth keyboard. Use Wi-Fi with Mote Helper."
                    s.phase == Phase.BLOCKED -> "Bluetooth HID is unavailable. Another app may be registered as a keyboard; close it and reopen Mote."
                    else -> "Mote reconnects to the selected device while the app is open."
                }
                Text(note, style = T.body, color = c.muted)
                // A saved host that was unpaired is no longer in the bonded list; keep it visible so its "Not paired" state shows.
                val btHosts = s.selected?.takeIf { sel -> s.unpaired && bt.hosts.none { it.address == sel.address } }?.let { bt.hosts + it } ?: bt.hosts
                val rows = hostRows(btHosts, saved, found)
                Column(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty()) Text(if (scanning) "Looking for Macs…" else "No devices yet.", style = T.body, color = c.muted, modifier = Modifier.padding(12.dp))
                    rows.forEach { r ->
                        HostRowCap(r, selection, active, ws, s,
                            onPick = { sel ->
                                when (sel) {
                                    is Selection.Bt -> {
                                        val host = r.bt ?: return@HostRowCap
                                        Net.select(sel); onPickBt(host)
                                        r.saved?.let { Net.linkBt(it.hostId, host.address) }
                                        close()
                                    }
                                    is Selection.Helper -> {
                                        Net.select(sel); r.bt?.let { Net.linkBt(sel.hostId, it.address) }
                                        // Tailnet-only right now and Tailscale off: ask to turn it on; the socket connects once the VPN is up.
                                        r.saved?.let { Net.promptForTailscale(r.name, it.routes, seenOnLan = r.found != null) }
                                        close()
                                    }
                                    null -> r.found?.let { goCode(it.address, it.port, it.service) }
                                }
                            },
                            onLong = { r.saved?.let { forgetting = it.hostId } })
                        if (forgetting != null && forgetting == r.saved?.hostId) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Forget ${r.name} on this phone?", style = T.small, color = c.muted, modifier = Modifier.weight(1f))
                                Text("Forget", style = T.label, color = c.danger, modifier = Modifier.clickable { Net.forget(forgetting!!); forgetting = null }.padding(8.dp))
                                Text("Cancel", style = T.label, color = c.muted, modifier = Modifier.clickable { forgetting = null }.padding(8.dp))
                            }
                        }
                    }
                }
                SecondaryButton("Add Mac over Wi-Fi") { error = null; page = Page.NEARBY }
                SecondaryButton("Enter address") { error = null; page = Page.ADDRESS }
                if (bt.gate == BtGate.READY) {
                    SecondaryButton("Pair new Bluetooth device") { requestDiscoverable(ctx) }
                    Text("Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.",
                        style = T.small, color = c.subtle)
                } else if (bt.gate != BtGate.UNSUPPORTED) {
                    SecondaryButton(if (bt.gate == BtGate.BT_OFF) "Turn on Bluetooth" else "Set up Bluetooth", onClick = onBtSetup)
                }
            }

            Page.NEARBY -> {
                Header("Add Mac over Wi-Fi") { page = Page.LIST }
                Text("On the Mac, open the Mote Helper menu and choose Pair new device. Then pick the Mac here, or scan its QR with the camera and tap Open in the Mote app.",
                    style = T.body, color = c.muted)
                val unpaired = found.filter { f -> saved.none { it.hostId == f.hostId } }
                Column(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unpaired.isEmpty()) Text(
                        if (scanning) "Looking for Macs on this Wi-Fi…"
                        else "No Macs found. Guest and hotel Wi-Fi often block this. Try Enter address, Tailscale or Bluetooth.",
                        style = T.body, color = c.muted, modifier = Modifier.padding(12.dp),
                    )
                    unpaired.forEach { f -> FoundCap(f) { goCode(f.address, f.port, f.service) } }
                }
                if (!scanning) SecondaryButton("Scan again") { Net.discovery.start() }
                SecondaryButton("Enter address") { error = null; page = Page.ADDRESS }
                Text("No Mote Helper yet? Get it at $HELPER_URL", style = T.small, color = c.subtle)
            }

            Page.ADDRESS -> {
                Header("Enter address") { page = Page.LIST }
                Text("For Tailscale, use the Mac's MagicDNS name or its 100.x address, and turn on Allow Tailscale connections in the Mote Helper menu. A LAN IP or name.local works too.",
                    style = T.body, color = c.muted)
                InputField(address, "mac.tailnet.ts.net or 100.x.y.z", KeyboardType.Uri) { address = it; error = null }
                ErrorText(error)
                PrimaryButton(if (busy) "Connecting…" else "Connect") { if (!busy) doAddress() }
            }

            Page.CODE -> {
                Header("Pair with $targetName") { page = Page.LIST }
                Text("Enter the 6-digit code shown in the Mote Helper pairing window on the Mac.", style = T.body, color = c.muted)
                InputField(code, "6-digit code", KeyboardType.NumberPassword) { v -> code = v.filter(Char::isDigit).take(6); error = null }
                ErrorText(error)
                PrimaryButton(if (busy) "Pairing…" else "Pair") { if (!busy) doPair() }
                tailnetHint?.let { Text("Tailscale address from the pairing link: $it", style = T.small, color = c.subtle) }
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val c = LocalMote.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
            MIcon("chevLeft", c.muted, description = "Back")
        }
        Text(title, style = T.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ErrorText(e: String?) {
    if (e != null) Text(e, style = T.small, color = LocalMote.current.danger)
}

@Composable
private fun InputField(value: String, placeholder: String, type: KeyboardType, onChange: (String) -> Unit) {
    val c = LocalMote.current
    Box(Modifier.fillMaxWidth().height(52.dp).well(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = T.body, color = c.subtle)
        BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth())
    }
}

/** A picker row. Selected = raised cap; unselected = flat with a hairline (design-language revision 3). */
@Composable
private fun HostRowCap(
    r: HostRow, selection: Selection?, active: Transport, ws: WsStatus, s: HidState,
    onPick: (Selection?) -> Unit, onLong: () -> Unit,
) {
    val c = LocalMote.current
    // Bluetooth selection lives in Hid (it also changes when the host connects to us).
    val selected = if (active is WsTransport) r.matches(selection) else r.bt != null && s.selected?.address == r.bt.address
    val viaHelper = selected && active is WsTransport
    val status = when {
        viaHelper && ws is WsStatus.Ready -> when {
            ws.paused -> "Paused on the Mac"
            !ws.ax -> "Mote Helper needs Accessibility on the Mac"
            else -> "Connected · ${routeOf(ws.address).badge}"
        }
        viaHelper && ws is WsStatus.Failed -> failedText(ws)
        viaHelper -> "Connecting…"
        selected && r.bt != null && s.connected?.address == r.bt.address -> "Connected · BT"
        selected && r.bt != null && s.unpaired -> "Not paired: pair again"
        selected && r.bt != null -> "Searching…"
        r.saved == null && r.bt == null -> "Nearby · tap to pair"
        r.saved == null && r.found != null -> "Paired over Bluetooth · Mote Helper found"
        r.found != null -> "Nearby"
        else -> "Paired"
    }
    val shape = RoundedCornerShape(14.dp)
    Cap(
        Modifier.fillMaxWidth().height(64.dp).then(if (selected) Modifier else Modifier.border(1.dp, c.outline, shape)),
        shape, flat = !selected, needsLink = false, description = "Use ${r.name}",
        onLongPress = if (r.saved != null) onLong else null, onClick = { onPick(r.defaultSelection) },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon(when (r.bt?.kind) { Host.Kind.DESKTOP -> "desktop"; Host.Kind.OTHER -> "bluetooth"; else -> "laptop" }, c.muted)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = T.small, color = if (status.startsWith("Connected")) c.linkOk else c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // One badge per known route. On a grouped row, tapping BT pins Bluetooth for this Mac.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                r.routes.forEach { route ->
                    val on = selected && ((route == Route.BT) == (active !is WsTransport))
                    val pin = if (route == Route.BT && r.saved != null && r.bt != null) Modifier.clickable(onClickLabel = "Use Bluetooth") {
                        onPick(Selection.Bt(r.bt.address))
                    } else Modifier
                    Box(pin) { RouteBadge(route, on) }
                }
            }
        }
    }
}

@Composable
private fun FoundCap(f: Found, onClick: () -> Unit) {
    val c = LocalMote.current
    val shape = RoundedCornerShape(14.dp)
    Cap(Modifier.fillMaxWidth().height(60.dp).border(1.dp, c.outline, shape), shape, flat = true, needsLink = false,
        description = "Pair ${f.service}", onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon(routeIcon(Route.WIFI), c.muted)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.service, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.address, style = T.small, color = c.subtle)
            }
            RouteBadge(Route.WIFI)
        }
    }
}

private fun failedText(f: WsStatus.Failed) = when (f.code) {
    "auth", "revoked" -> "The Mac no longer knows this phone. Long-press it to forget, then pair again."
    "version" -> f.msg
    "unpaired" -> "Pairing data is missing. Long-press the Mac to forget it, then pair again."
    else -> f.msg
}

/** Plain-language errors for pairing / connecting (docs/protocol.md error codes). */
private fun friendly(e: Throwable, address: String): String = when (e) {
    is HelperError -> when (e.code) {
        "code" -> "Wrong code. Check the code in the Mote Helper window."
        "pairing-closed" -> "Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu."
        "rate" -> "Too many attempts. Try again in ${e.retryAfter ?: 60} s."
        "timeout" -> "The Mac didn't answer. Is Mote Helper running?"
        else -> e.message ?: "Mote Helper refused the connection."
    }
    else -> buildString {
        append("Couldn't reach $address.")
        if (routeOf(address) == Route.TAILNET) {
            append(if (Net.tailnetUpFlow.value) " Check Allow Tailscale connections in the Mote Helper menu." else " Tailscale looks off on this phone.")
        } else append(" Same Wi-Fi as the Mac? Is Mote Helper running?")
    }
}

private const val DISCOVERABLE_S = 120

/** Makes the phone discoverable for Bluetooth pairing; auto-connect pauses meanwhile (Reconnect policy). */
private fun requestDiscoverable(c: Context) {
    if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(c, "Allow Nearby devices to pair", Toast.LENGTH_SHORT).show(); return
    }
    Hid.pausePairing(DISCOVERABLE_S * 1000L)
    runCatching {
        c.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_S))
    }
}

/** How long to wait for the VPN after CONNECT_VPN before opening the Tailscale app (it was up in ~1 s on a Nothing Phone 2). */
private const val TAILSCALE_WAIT_MS = 6000L

/**
 * "Tailscale is off" sheet. Turn on: broadcast CONNECT_VPN to Tailscale's IPNReceiver, then wait;
 * Net dismisses this sheet and continues as soon as a tailnet address appears. If the VPN isn't up
 * after [TAILSCALE_WAIT_MS] (no VPN permission yet, signed out), open the Tailscale app.
 * Not installed: the Play Store page.
 */
@Composable
fun TailscalePrompt() {
    val p by Net.tsPrompt.collectAsState()
    val prompt = p ?: return
    MoteSheet(onDismiss = { Net.tsPrompt.value = null }) { close -> TailscaleSheet(prompt.host, close) }
}

@Composable
private fun TailscaleSheet(host: String, close: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Tailscale.installed(ctx) }
    var waiting by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon("globe", c.accent, size = 28.dp)
            Spacer(Modifier.size(12.dp))
            Text("Tailscale is off", style = T.title, color = c.ink)
        }
        Text("Turn it on to reach $host.", style = T.body, color = c.muted)
        when {
            opened -> Text("Finish in the Tailscale app, then come back. Mote connects as soon as Tailscale is on.", style = T.small, color = c.subtle)
            waiting -> Text("Turning on Tailscale…", style = T.small, color = c.subtle)
            !installed -> Text("Tailscale isn't installed on this phone.", style = T.small, color = c.subtle)
        }
        PrimaryButton(if (installed) "Turn on Tailscale" else "Get Tailscale") {
            if (!installed) { Tailscale.openStore(ctx); return@PrimaryButton }
            if (waiting) return@PrimaryButton
            waiting = true
            Tailscale.requestConnect(ctx)
            scope.launch {
                delay(TAILSCALE_WAIT_MS)
                if (Net.tsPrompt.value != null) { opened = Tailscale.open(ctx); waiting = false }
            }
        }
        SecondaryButton("Not now", onClick = close)
    }
}

