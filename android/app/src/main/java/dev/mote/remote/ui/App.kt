package dev.mote.remote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mote.remote.hid.Bt
import dev.mote.remote.hid.BtGate
import dev.mote.remote.data.Macro
import dev.mote.remote.data.Macros
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.ThemeChoice
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.HidState
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.Phase
import dev.mote.remote.service.HidService
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.MoteTheme
import dev.mote.remote.ui.theme.Silver
import dev.mote.remote.ui.theme.Titanium
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.chassis
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.launch

/** Bluetooth permissions plus notifications (link status), asked together from the gate. */
private fun requestedPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) Bt.permissions + Manifest.permission.POST_NOTIFICATIONS else Bt.permissions

private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun requestDiscoverable(c: Context) {
    if (Build.VERSION.SDK_INT >= 31 && !granted(c, Manifest.permission.BLUETOOTH_ADVERTISE)) {
        Toast.makeText(c, "Allow Nearby devices to pair", Toast.LENGTH_SHORT).show(); return
    }
    runCatching {
        c.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
    }
}

@SuppressLint("MissingPermission") // only launched once Bt.permitted() (BLUETOOTH_CONNECT on 31+); legacy BLUETOOTH is a manifest grant
private fun requestEnable(launcher: ActivityResultLauncher<Intent>) {
    runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
}

@Composable
fun MoteApp() {
    val ctx = LocalContext.current
    val choice by Prefs.theme(ctx).collectAsState(initial = ThemeChoice.LIGHT)
    val dark = when (choice) { ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true; ThemeChoice.SYSTEM -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MoteTheme(if (dark) Titanium else Silver) {
        CompositionLocalProvider(LocalHaptics provides rememberHaptics()) { Root(choice) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(theme: ThemeChoice) {
    val ctx = LocalContext.current
    val c = LocalMote.current
    val h = haptics()
    val scope = rememberCoroutineScope()
    val s by Hid.state.collectAsState()
    val macros by Macros.flow(ctx).collectAsState(initial = Macros.defaults)
    var mode by rememberSaveable { mutableStateOf(Mode.MEDIA) }
    var deviceSheet by remember { mutableStateOf(false) }
    var menuSheet by remember { mutableStateOf(false) }
    var pointerBanner by remember { mutableStateOf(false) }
    // Single source of truth for adapter / permissions / bonded hosts: feeds the gate, the pill and the device sheet.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bt by remember(lifecycle) { Bt.observe(ctx, lifecycle) }.collectAsStateWithLifecycle(remember { Bt.snapshot(ctx) })
    LaunchedEffect(bt.gate) { if (bt.gate == BtGate.READY) HidService.start(ctx) }

    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var openSettings by remember { mutableStateOf(false) } // permission permanently denied: only Settings can grant it
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Bt.permitted(ctx)) {
            if (!Bt.snapshot(ctx).enabled) requestEnable(enableLauncher)
        } else {
            val a = ctx as? Activity
            openSettings = a != null && Bt.permissions.none { p -> a.shouldShowRequestPermissionRationale(p) }
        }
    }
    val gateAction: () -> Unit = {
        when {
            bt.gate == BtGate.BT_OFF -> requestEnable(enableLauncher)
            openSettings -> runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }
            else -> permLauncher.launch(requestedPermissions())
        }
    }
    if (bt.gate != BtGate.READY) {
        BtGateScreen(bt.gate, openSettings, gateAction)
        return
    }

    // CONNECT / DISCONNECT haptics on link transitions (not on first composition)
    var wasConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(s.isConnected) {
        val prev = wasConnected
        if (prev != null && prev != s.isConnected) h(if (s.isConnected) Haptic.CONNECT else Haptic.DISCONNECT)
        wasConnected = s.isConnected
    }

    val runMacro: (Macro) -> Unit = { m ->
        scope.launch {
            val skipped = Macros.run(m)
            if (skipped > 0) Toast.makeText(ctx, "$skipped characters skipped (not on US layout)", Toast.LENGTH_SHORT).show()
            if (m.pointer) { mode = Mode.MOUSE; pointerBanner = true }
        }
    }
    val saveMacros: (List<Macro>) -> Unit = { list -> scope.launch { Macros.save(ctx, list) } }

    CompositionLocalProvider(LocalLinked provides s.isConnected) {
        Column(Modifier.fillMaxSize().chassis().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DevicePill(s) { deviceSheet = true }
                Spacer(Modifier.weight(1f))
                WordmarkText()
                Box(
                    Modifier.size(44.dp).clickable(onClickLabel = "Menu") { menuSheet = true },
                    contentAlignment = Alignment.Center,
                ) { MIcon("more", if (c.dark) c.subtle else c.muted, size = 20.dp, description = "Menu") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        // shared X axis: content slides 1/8 of the width toward the new mode and crossfades
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(Motion.spatialDefault()) { it / 8 * dir } + fadeIn(Motion.effectMed())) togetherWith
                            (slideOutHorizontally(Motion.spatialDefault()) { -it / 8 * dir } + fadeOut(Motion.exit()))
                    },
                    label = "mode",
                ) { m -> when (m) {
                    Mode.MEDIA -> MediaScreen(macros.filter { it.star }, runMacro, onEditFavourites = { mode = Mode.MACROS })
                    Mode.MOUSE -> MouseScreen(
                        banner = pointerBanner,
                        onBannerDone = { pointerBanner = false; mode = Mode.MACROS },
                    )
                    Mode.KEYS -> KeysScreen()
                    Mode.MACROS -> MacrosScreen(macros, runMacro, saveMacros)
                } }
            }
            ModeRail(mode) { mode = it }
        }

        if (deviceSheet) MoteSheet(onDismiss = { deviceSheet = false }) { close ->
            DeviceSheet(s, bt.hosts, onPick = { host -> HidService.start(ctx); Hid.select(host); close() })
        }
        if (menuSheet) MoteSheet(onDismiss = { menuSheet = false }) { close ->
            MenuSheet(theme, onTheme = { t -> scope.launch { Prefs.setTheme(ctx, t) } }, onPair = { close(); deviceSheet = true })
        }
    }
}

/** Bottom sheet whose programmatic close plays the exit animation before leaving composition. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoteSheet(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val c = LocalMote.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.surface1) { content(close) }
}

/** First-run (and Bluetooth-off) gate: shown instead of the remote until Bluetooth is usable. */
@Composable
private fun BtGateScreen(gate: BtGate, openSettings: Boolean, onAction: () -> Unit) {
    val c = LocalMote.current
    val (text, action) = when (gate) {
        BtGate.UNSUPPORTED -> "This phone has no Bluetooth adapter Mote can use." to null
        BtGate.NEEDS_PERMISSION -> if (openSettings) {
            "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices in Settings, then come back." to "Open settings"
        } else {
            "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on." to "Allow and turn on Bluetooth"
        }
        else -> "Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue." to "Turn on Bluetooth"
    }
    Column(
        Modifier.fillMaxSize().chassis().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MIcon("bluetooth", c.accent, size = 40.dp)
        Spacer(Modifier.height(16.dp))
        Text("Mote connects over Bluetooth", style = T.title, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = T.body, color = c.muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(action, onClick = onAction)
        }
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(18.dp), tone = Tone.PRIMARY, needsLink = false, description = label, onClick = onClick) {
        Text(label, style = T.label, color = c.onPrim)
    }
}

@Composable
private fun DeviceSheet(s: HidState, hosts: List<Host>, onPick: (Host) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMote.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Devices", style = T.title, color = c.ink)
        Spacer(Modifier.height(4.dp))
        val note = when (s.phase) {
            Phase.UNSUPPORTED -> "This phone does not support Bluetooth HID device mode."
            Phase.BLOCKED -> "Bluetooth HID is unavailable. Another app may be registered as a keyboard; close it and reopen Mote."
            else -> "Mote reconnects to the selected device while the app is open."
        }
        Text(note, style = T.body, color = c.muted)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hosts.isEmpty()) Text("No paired devices yet.", style = T.body, color = c.muted, modifier = Modifier.padding(12.dp))
            hosts.forEach { host ->
                val selected = s.selected?.address == host.address
                val status = when {
                    s.connected?.address == host.address -> "Connected"
                    selected -> "Searching…"
                    else -> "Paired"
                }
                Cap(Modifier.fillMaxWidth().height(60.dp), RoundedCornerShape(14.dp), needsLink = false, description = "Use ${host.name}", onClick = { onPick(host) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MIcon(when (host.kind) { Host.Kind.LAPTOP -> "laptop"; Host.Kind.DESKTOP -> "desktop"; else -> "bluetooth" }, c.muted)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.name, style = T.label, color = c.ink, maxLines = 1)
                            Text(status, style = T.small, color = if (status == "Connected") c.linkOk else c.subtle)
                        }
                        if (selected) MIcon("check", c.accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Pair new device") { requestDiscoverable(ctx) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.",
            style = T.body, color = c.muted,
        )
    }
}

@Composable
private fun MenuSheet(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, onPair: () -> Unit) {
    val c = LocalMote.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Theme", style = T.title, color = c.ink)
        Row(Modifier.fillMaxWidth().well(RoundedCornerShape(18.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeChoice.entries.forEach { t ->
                Cap(
                    Modifier.weight(1f).height(48.dp), RoundedCornerShape(14.dp), needsLink = false, haptic = Haptic.TICK,
                    flat = t != theme, description = t.name, onClick = { onTheme(t) },
                ) {
                    Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, style = T.label, color = if (t == theme) c.accent else c.muted)
                }
            }
        }
        PrimaryButton("Manage devices", onClick = onPair)
        val ctx = LocalContext.current
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
        Text("Mote $version · Bluetooth HID remote", style = T.small, color = c.subtle)
    }
}
