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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mote.remote.hid.Bt
import dev.mote.remote.hid.BtGate
import dev.mote.remote.data.Macro
import dev.mote.remote.data.Macros
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.ThemeChoice
import dev.mote.remote.data.UiPrefs
import androidx.compose.runtime.DisposableEffect
import dev.mote.remote.data.UiSettings
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.HidState
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.Phase
import dev.mote.remote.net.Net
import dev.mote.remote.net.Remote
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

@SuppressLint("MissingPermission") // only launched once Bt.permitted() (BLUETOOTH_CONNECT on 31+); legacy BLUETOOTH is a manifest grant
private fun requestEnable(launcher: ActivityResultLauncher<Intent>) {
    runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
}

@Composable
fun MoteApp() {
    val ctx = LocalContext.current
    val choice by Prefs.theme(ctx).collectAsState(initial = ThemeChoice.LIGHT)
    val dark = when (choice) { ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true; ThemeChoice.SYSTEM -> isSystemInDarkTheme() }
        // debug screenshots: `--ez mote.demo.dark true` with mote.demo forces Titanium
        || remember { isDemo(ctx) && (ctx as? Activity)?.intent?.getBooleanExtra("mote.demo.dark", false) == true }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val settings by UiPrefs.flow(ctx).collectAsState(initial = UiSettings())
    MoteTheme(if (dark) Titanium else Silver) {
        val haptics = rememberHaptics()
        SideEffect { haptics.cfg = settings }
        CompositionLocalProvider(LocalHaptics provides haptics, LocalSettings provides settings) { Root(choice) }
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
    // keeps each mode's rememberSaveable state (typed text, toggles) while another mode is shown.
    // Keyed by name: an enum key is Java-serialized and is dropped when restoring after process death.
    val modeState = rememberSaveableStateHolder()
    var deviceSheet by remember { mutableStateOf(false) }
    var addHost by remember { mutableStateOf(false) } // open the host sheet on "Add Mac over Wi-Fi"
    val wifiChosen by Net.wifiChosen.collectAsState()
    val link by Net.pendingLink.collectAsState()
    LaunchedEffect(link) { if (link != null) deviceSheet = true }
    val linked by Remote.linked.collectAsState()
    var menuSheet by remember { mutableStateOf(false) }
    var pointerBanner by remember { mutableStateOf(false) }
    // Single source of truth for adapter / permissions / bonded hosts: feeds the gate, the pill and the device sheet.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bt by remember(lifecycle) { Bt.observe(ctx, lifecycle) }.collectAsStateWithLifecycle(remember { Bt.snapshot(ctx) })
    // Debug builds only: `am start ... --ez mote.demo true` skips the first-run gate so every screen
    // can be screenshotted on an emulator (input stays inert without a host).
    val demo = remember { isDemo(ctx) }
    LaunchedEffect(demo) { if (demo && (ctx as? Activity)?.intent?.getBooleanExtra("mote.demo.displays", false) == true) DemoSpaces.enable() }
    LaunchedEffect(bt.gate) { if (bt.gate == BtGate.READY && !demo) HidService.start(ctx) }
    LaunchedEffect(bt) { Hid.envChanged() } // bond/ACL/adapter change or resume: fresh reconnect budget
    val settings = LocalSettings.current
    var page by rememberSaveable { mutableStateOf<MenuPage?>(null) }
    val openTv = dev.mote.remote.labs.open.rememberOpenTv() // Labs: Open & TV mode (preview)
    val updateSettings: ((UiSettings) -> UiSettings) -> Unit = { f -> scope.launch { UiPrefs.update(ctx, f) } }

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
    if (bt.gate != BtGate.READY && !wifiChosen && link == null && !demo) {
        BtGateScreen(bt.gate, openSettings, gateAction, onWifi = { Net.chooseWifi(); addHost = true; deviceSheet = true })
        return
    }

    // CONNECT / DISCONNECT haptics on link transitions (not on first composition)
    var wasConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(linked) {
        val prev = wasConnected
        if (prev != null && prev != linked) h(if (linked) Haptic.CONNECT else Haptic.DISCONNECT)
        wasConnected = linked
    }

    val runMacro: (Macro) -> Unit = { m ->
        scope.launch {
            val skipped = Macros.run(m)
            if (skipped > 0) Toast.makeText(ctx, "$skipped characters skipped (not on US layout)", Toast.LENGTH_SHORT).show()
            if (m.pointer) { mode = Mode.MOUSE; pointerBanner = true }
        }
    }
    val saveMacros: (List<Macro>) -> Unit = { list -> scope.launch { Macros.save(ctx, list) } }

    // Settings: clear sticky modifiers on mode switch; release them after 30 s without a change
    LaunchedEffect(mode) { if (settings.clearModsOnSwitch) Remote.setMods(Hid.Mods()) }
    val mods by Remote.mods.collectAsState()
    LaunchedEffect(mods, settings.releaseModsIdle) {
        if (settings.releaseModsIdle && mods.active != 0) { kotlinx.coroutines.delay(30_000); Remote.setMods(Hid.Mods()) }
    }
    // Settings: keep the screen on while connected; extra dim lowers this window's brightness
    val view = LocalView.current
    val linkedUi = linked || (demo && !isDemoOffline(ctx))
    DisposableEffect(linkedUi, settings.keepScreenOn) {
        view.keepScreenOn = linkedUi && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(settings.extraDim) {
        val w = (ctx as? Activity)?.window
        w?.attributes = w?.attributes?.apply { screenBrightness = if (settings.extraDim) 0.02f else -1f }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = -1f } }
    }

    CompositionLocalProvider(LocalLinked provides linkedUi) {
        Column(Modifier.fillMaxSize().chassis().systemBarsPadding().imePadding()) {
          val p = page
          if (p != null) {
            when (p) {
                MenuPage.SETTINGS -> SettingsPage(onBack = { page = null }, update = updateSettings)
                MenuPage.HAPTICS -> HapticsPage(onBack = { page = null }, update = updateSettings)
                MenuPage.ABOUT -> AboutPage(onBack = { page = null })
            }
          } else {
            CappedFontScale { Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HostPill { deviceSheet = true }
                Spacer(Modifier.weight(1f))
                WordmarkText()
                dev.mote.remote.labs.open.OpenEntry(openTv)
                Box(
                    Modifier.size(44.dp).clickable(onClickLabel = "Menu") { menuSheet = true },
                    contentAlignment = Alignment.Center,
                ) { MIcon("more", if (c.dark) c.subtle else c.muted, size = 20.dp, description = "Menu") }
            } }
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
                ) { m -> modeState.SaveableStateProvider(m.name) { when (m) {
                    Mode.MEDIA -> if (openTv.tvOn) dev.mote.remote.labs.open.TvRemotePanel(openTv) else MediaScreen(macros.filter { it.star }, runMacro, onEditFavourites = { mode = Mode.MACROS })
                    Mode.MOUSE -> MouseScreen(
                        banner = pointerBanner,
                        onBannerDone = { pointerBanner = false; mode = Mode.MACROS },
                    )
                    Mode.KEYS -> KeysScreen()
                    Mode.MACROS -> MacrosScreen(macros, runMacro, saveMacros)
                } } }
            }
            ModeRail(mode) { mode = it }
          }
        }

        if (deviceSheet) MoteSheet(onDismiss = { deviceSheet = false; addHost = false }) { close ->
            HostSheet(s, bt, addHost, onBtSetup = gateAction, onPickBt = { host -> HidService.start(ctx); Hid.select(host) }, close = close)
        }
        if (menuSheet) MoteSheet(onDismiss = { menuSheet = false }) { close ->
            val name = s.connected?.name ?: s.selected?.name
            val others = bt.hosts.count { it.name != name }
            val summary = when {
                name == null -> if (bt.hosts.isEmpty()) "No paired devices" else "${bt.hosts.size} paired"
                others == 0 -> name
                else -> "$name and $others other${if (others == 1) "" else "s"}"
            }
            MenuSheet(
                theme, summary,
                onTheme = { t -> scope.launch { Prefs.setTheme(ctx, t) } },
                onDevices = { close(); deviceSheet = true },
                onPage = { pg -> close(); page = pg },
                onClose = close,
            )
        }
        TailscalePrompt()
        dev.mote.remote.labs.open.OpenTvSheet(openTv)
        dev.mote.remote.labs.pairing.InstantPairingHost() // Labs: "Connect to <Mac>?" sheet (flag off: nothing)
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
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = c.surface1,
        // rev 4: the handle is a small recessed slot, not a printed bar
        // labelled like M3's default handle: the sheet attaches expand / dismiss actions to it
        dragHandle = { Box(Modifier.semantics { contentDescription = "Drag handle" }.padding(top = 10.dp, bottom = 8.dp).size(36.dp, 5.dp).well(RoundedCornerShape(3.dp))) },
    ) { content(close) }
}

/** First-run (and Bluetooth-off) gate: shown instead of the remote until Bluetooth is usable. */
@Composable
private fun BtGateScreen(gate: BtGate, openSettings: Boolean, onAction: () -> Unit, onWifi: () -> Unit) {
    val c = LocalMote.current
    val (text, action) = when (gate) {
        BtGate.UNSUPPORTED -> "This phone has no Bluetooth adapter Mote can use." to null
        BtGate.NEEDS_PERMISSION -> if (openSettings) {
            "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices in Settings, then come back." to "Open settings"
        } else {
            "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on." to "Connect with Bluetooth"
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
        Text("How do you want to connect?", style = T.title, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = T.body, color = c.muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(action, onClick = onAction)
        }
        WifiGateOption(primary = action == null, onWifi)
    }
}

private fun isDemo(c: Context): Boolean {
    if (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
    return (c as? Activity)?.intent?.getBooleanExtra("mote.demo", false) == true
}

/** Debug builds: `--ez mote.demo.offline true` with mote.demo shows the app disconnected (unlit wordmark). */
private fun isDemoOffline(c: Context): Boolean = (c as? Activity)?.intent?.getBooleanExtra("mote.demo.offline", false) == true

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(18.dp), tone = Tone.PRIMARY, needsLink = false, description = label, onClick = onClick) {
        Text(label, style = T.label, color = c.onPrim)
    }
}
