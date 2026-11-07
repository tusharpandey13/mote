package dev.mote.remote.pairing

import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Net
import dev.mote.remote.net.Route
import dev.mote.remote.net.routeOf
import dev.mote.remote.ui.PrimaryButton
import dev.mote.remote.ui.SecondaryButton
import dev.mote.remote.ui.T
import dev.mote.remote.ui.TailscalePrompt
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.chassis
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val INSTALL_CMD = "curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash"
private const val SITE = "tusharpandey13.github.io/mote"

/**
 * First run: opens straight into finding the Mac. No permission is asked here; Bluetooth is the
 * secondary "no helper" path ([FIRST_RUN_ACTIONS]).
 */
@Composable
fun FindMacScreen(onBluetooth: () -> Unit) {
    val c = LocalMote.current
    var address by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().chassis().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Connect to your Mac", style = T.title, color = c.ink)
        Text("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", style = T.body, color = c.muted)
        Text("New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", style = T.small, color = c.subtle)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().well(RoundedCornerShape(24.dp)).padding(vertical = 8.dp)) {
                if (address) AddressConnect(onBack = { address = false }, onDone = {})
                else FindMacPanel(onEnterAddress = { address = true }, onDone = {})
            }
        }
        for (a in FIRST_RUN_ACTIONS) if (a == FirstRunAction.USE_BLUETOOTH) SecondaryButton("Use Bluetooth instead (no helper)", onClick = onBluetooth)
    }
    TailscalePrompt()
}

/**
 * Finds Macs ([Finder]: BLE, LAN sweep, Bonjour, known addresses) and shows the first one as an
 * AirPods-style card with one Connect button. Every state ends in something to do ([findUi]).
 */
@Composable
fun FindMacPanel(onEnterAddress: () -> Unit, onDone: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val finder = InstantPairing.finder
    val found by finder.found.collectAsState()
    val running by finder.running.collectAsState()
    val onWifi by Net.onWifiFlow.collectAsState()
    val saved by Net.saved.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var picked by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { finder.start(); onDispose { finder.stop() } }
    LaunchedEffect(onWifi) { if (onWifi && !finder.running.value) finder.start() } // Wi-Fi came on: look again
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
    val fresh = found.filter { f -> saved.none { it.hostId == f.hostId } }
    val ui = findUi(if (finder.startedAt == 0L) 0 else now - finder.startedAt, onWifi, running, fresh)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (ui) {
            FindUi.Searching -> {
                Glyph("laptop")
                Text("Looking for your Mac…", style = T.label, color = c.ink)
                Text("Searching this Wi-Fi, Bluetooth and Tailscale.", style = T.small, color = c.subtle, textAlign = TextAlign.Center)
            }
            is FindUi.Found -> {
                val m = ui.macs.firstOrNull { it.hostId == picked } ?: ui.macs.first()
                ConnectCard(m, onDone)
                ui.macs.filter { it.hostId != m.hostId }.forEach { o ->
                    Text("Not this one? ${o.name}", style = T.small, color = c.accent, modifier = Modifier.clickable { picked = o.hostId }.padding(4.dp))
                }
            }
            FindUi.WifiOff -> {
                Glyph("wifi")
                Text("Turn on Wi-Fi", style = T.label, color = c.ink)
                Text("Wi-Fi is off. Connect to the same Wi-Fi as your Mac, or turn on Tailscale.", style = T.body, color = c.muted, textAlign = TextAlign.Center)
                PrimaryButton("Turn on Wi-Fi") {
                    val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                    runCatching { ctx.startActivity(i) }
                }
                SmallLink("Enter address", onEnterAddress)
            }
            FindUi.NotFound -> {
                Glyph("laptop")
                Text("Mote Helper not found on this network", style = T.label, color = c.ink, textAlign = TextAlign.Center)
                Text("Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:", style = T.body, color = c.muted, textAlign = TextAlign.Center)
                Text(INSTALL_CMD, style = T.small, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.clickable {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Mote Helper install", INSTALL_CMD))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }.padding(4.dp))
                Text("or download it from $SITE", style = T.small, color = c.subtle)
                PrimaryButton("Retry") { finder.stop(); finder.start() }
                SmallLink("Enter address", onEnterAddress)
            }
        }
    }
}

@Composable
private fun Glyph(icon: String) {
    val c = LocalMote.current
    Box(Modifier.size(88.dp).well(CircleShape), contentAlignment = Alignment.Center) { MIcon(icon, c.ink, size = 44.dp) }
}

@Composable
fun SmallLink(label: String, onClick: () -> Unit) {
    Text(label, style = T.small, color = LocalMote.current.accent, modifier = Modifier.clickable(onClick = onClick).padding(8.dp))
}

/** Typed address (LAN IP, name.local, MagicDNS, 100.x): checks a helper answers, then the same Connect card. */
@Composable
fun AddressConnect(onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mac by remember { mutableStateOf<FoundMac?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        mac?.let { ConnectCard(it, onDone); SmallLink("Back", onBack); return@Column }
        Text("Enter the Mac's address", style = T.label, color = c.ink)
        Text("A LAN IP (192.168.1.2), name.local, or its Tailscale name / 100.x address.", style = T.small, color = c.subtle, textAlign = TextAlign.Center)
        dev.mote.remote.ui.AddressField(text, "192.168.1.2 or mac.tailnet.ts.net") { text = it; error = null }
        error?.let { Text(it, style = T.small, color = c.danger, textAlign = TextAlign.Center) }
        PrimaryButton(if (busy) "Checking…" else "Find") {
            if (busy) return@PrimaryButton
            val (host, port) = dev.mote.remote.net.parseAddress(text) ?: run { error = "Enter an address like 192.168.1.2 or mac.tailnet.ts.net."; return@PrimaryButton }
            if (routeOf(host) == Route.TAILNET && Net.promptForTailscale(host, listOf(Route.TAILNET), seenOnLan = false)) return@PrimaryButton
            busy = true
            scope.launch {
                val p = probeHelper(Net.http, host, port)
                busy = false
                if (p == null) error = "No Mote Helper answered at $host:$port."
                else mac = mergeFound(emptyList(), emptyList(), listOf(p), emptyList()).first()
            }
        }
        SmallLink("Back", onBack)
    }
}

private sealed interface Step {
    data object Ask : Step
    data object Connecting : Step
    /** The Mac's owner turned on "Require approval for new devices". */
    data class Waiting(val timeout: Int) : Step
    /** Proximity needs Bluetooth: one-line rationale, then the system dialog. */
    data object NeedBluetooth : Step
    data class Done(val via: String) : Step
    /** Connected over Wi-Fi / Tailscale; next, the optional Bluetooth HID step. */
    data class OfferBt(val via: String) : Step
    data class Error(val msg: String) : Step
}

/** Plain-language reason for a refused or failed connect (docs/pairing.md deny reasons). */
fun pairFailure(e: Throwable, mac: String): String = when ((e as? HelperError)?.code) {
    "far" -> "$mac couldn't confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account."
    "stale" -> "The nearby check expired. Try again next to the Mac."
    "bad-proximity" -> "The nearby check failed. Try again next to the Mac."
    "blocked" -> "$mac blocked this phone. Unblock it with Revoke all devices in the Mote Helper menu."
    "denied" -> "$mac didn't allow this phone."
    "busy" -> "$mac is answering another phone. Try again in a moment."
    "rate" -> "Too many requests. Try again in ${(e as HelperError).retryAfter ?: 60} s."
    "unauth" -> "Mote Helper on $mac is too old or has instant pairing turned off. Update it, or use Pair with code."
    "helper-key" -> "That isn't the Mac it claims to be (its key doesn't match)."
    "timeout" -> "$mac didn't answer in time."
    "noroute" -> e.message ?: "No way to reach $mac."
    null -> "Couldn't reach $mac. Is Mote Helper running, and the phone on the same Wi-Fi or Tailscale?"
    else -> e.message ?: "Couldn't connect to $mac."
}

/** "Connect to <Mac>": glyph, name, one big Connect; then waiting / done / error, never silent. */
@Composable
fun ConnectCard(m: FoundMac, onDone: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember(m.hostId) { mutableStateOf<Step>(Step.Ask) }
    LaunchedEffect(step) { if (step is Step.Done) { delay(900); onDone() } }
    val btOffer = remember { dev.mote.remote.hid.Bt.snapshot(ctx).supported }

    fun go() {
        // Tailscale-only Mac and the VPN is off: the existing prompt, then connect once it is up.
        val routes = m.addresses.map(::routeOf).distinct()
        if (Net.promptForTailscale(m.name, routes, seenOnLan = Route.WIFI in routes) { go() }) return
        step = Step.Connecting
        scope.launch {
            step = try {
                val done = InstantPairing.connect(m) { st -> if (st is PairState.Pending) step = Step.Waiting(st.timeout) }
                val linked = Net.saved.value.firstOrNull { it.hostId == m.hostId }?.btAddress != null
                if (btOffer && !linked) Step.OfferBt(done.via) else Step.Done(done.via)
            } catch (e: Exception) {
                if ((e as? HelperError)?.code == "far" && !InstantPairing.ble.ready() && Build.VERSION.SDK_INT >= 31) Step.NeedBluetooth
                else Step.Error(pairFailure(e, m.name))
            }
        }
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { go() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!InstantPairing.ble.permitted()) step = Step.Error(pairFailure(HelperError("far", "far"), m.name))
        else if (!InstantPairing.ble.ready()) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { go() }
        else { InstantPairing.ble.start(); go() }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph("laptop")
        Text(m.name, style = T.title, color = c.ink, textAlign = TextAlign.Center)
        when (val s = step) {
            Step.Ask -> PrimaryButton("Connect to ${m.name}") { go() }
            Step.Connecting -> Text("Connecting…", style = T.body, color = c.muted)
            is Step.Waiting -> Text("Click Allow on ${m.name}. It asks before trusting new devices.", style = T.body, color = c.muted, textAlign = TextAlign.Center)
            Step.NeedBluetooth -> {
                Text("Mote uses Bluetooth once to check this phone is next to your Mac.", style = T.body, color = c.muted, textAlign = TextAlign.Center)
                PrimaryButton("Allow and connect") {
                    if (InstantPairing.ble.permitted()) perms.launch(emptyArray()) else perms.launch(BleScan.PERMISSIONS.toTypedArray())
                }
            }
            is Step.Done -> Text(when (s.via) { "tailscale" -> "Connected. Trusted via Tailscale"; else -> "Connected" }, style = T.label, color = c.ink)
            is Step.OfferBt -> {
                Text("Connected over ${if (s.via == "tailscale") "Tailscale" else "Wi-Fi"}", style = T.label, color = c.ink)
                BtStep(m.hostId, m.name, onFinish = onDone)
            }
            is Step.Error -> {
                Text(s.msg, style = T.body, color = c.danger, textAlign = TextAlign.Center)
                SecondaryButton("Try again") { go() }
            }
        }
    }
}

private sealed interface BtUi {
    data object Offer : BtUi
    data class Running(val text: String) : BtUi
    data class Failed(val text: String) : BtUi
    data object Paired : BtUi
}

/**
 * "Also pair Bluetooth for login screen & backup": recommended, pre-checked, skippable. The phone
 * becomes visible (one Android prompt) and registers as a keyboard; the trusted helper pairs from
 * the Mac and accepts the pairing itself; Android asks once to confirm. Then the Mac is linked to
 * its Bluetooth host, so both links are up (pill: Wi-Fi + BT).
 */
@android.annotation.SuppressLint("MissingPermission") // adapter name only after Bt.permitted
@Composable
fun BtStep(hostId: String, mac: String, onFinish: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var want by remember { mutableStateOf(true) }
    var ui by remember { mutableStateOf<BtUi>(BtUi.Offer) }
    LaunchedEffect(ui) { if (ui == BtUi.Paired) { delay(1500); onFinish() } }

    fun run() {
        val name = runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name }.getOrNull()
        if (name.isNullOrBlank()) { ui = BtUi.Failed("Turn on Bluetooth on this phone, then try again."); return }
        dev.mote.remote.service.HidService.start(ctx) // registers the phone as a keyboard / mouse
        dev.mote.remote.hid.Hid.pausePairing(120_000)
        ui = BtUi.Running("Asking $mac to pair…")
        scope.launch {
            ui = try {
                val macBt = BtSetup.pair(hostId, name) { st -> ui = BtUi.Running(btStatusText(st, mac)) }
                Net.linkBt(hostId, macBt)
                dev.mote.remote.hid.Hid.select(dev.mote.remote.hid.Host(macBt, mac))
                BtUi.Paired
            } catch (e: Exception) {
                BtUi.Failed(btStatusText(BtPairStatus("failed", (e as? HelperError)?.code), mac))
            }
        }
    }
    val visible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_CANCELED) ui = BtUi.Failed("The phone has to be visible for a moment so $mac can find it.") else run()
    }
    fun discoverable() = runCatching {
        visible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
    }.onFailure { run() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { discoverable() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!dev.mote.remote.hid.Bt.permitted(ctx)) ui = BtUi.Failed("Mote needs Nearby devices to pair over Bluetooth.")
        else if (!dev.mote.remote.hid.Bt.snapshot(ctx).enabled) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        else discoverable()
    }
    fun start() {
        if (!dev.mote.remote.hid.Bt.permitted(ctx)) perms.launch(dev.mote.remote.hid.Bt.permissions)
        else if (!dev.mote.remote.hid.Bt.snapshot(ctx).enabled) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        else discoverable()
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val u = ui) {
            BtUi.Offer -> {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { want = !want }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = want, onCheckedChange = { want = it })
                    Text("Also pair Bluetooth for login screen & backup (recommended)", style = T.body, color = c.ink)
                }
                Text("Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.", style = T.small, color = c.subtle, textAlign = TextAlign.Center)
                PrimaryButton(if (want) "Continue" else "Done") { if (want) start() else onFinish() }
                if (want) SmallLink("Skip", onFinish)
            }
            is BtUi.Running -> Text(u.text, style = T.body, color = c.muted, textAlign = TextAlign.Center)
            BtUi.Paired -> Text("Wi-Fi and Bluetooth are both set up.", style = T.label, color = c.ink)
            is BtUi.Failed -> {
                Text(u.text, style = T.body, color = c.danger, textAlign = TextAlign.Center)
                SecondaryButton("Try again") { start() }
                SmallLink("Skip for now", onFinish)
            }
        }
    }
}
