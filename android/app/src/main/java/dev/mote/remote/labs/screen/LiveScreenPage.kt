package dev.mote.remote.labs.screen

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import dev.mote.remote.labs.Blockers
import dev.mote.remote.labs.Labs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mote.remote.labs.LabsBus
import dev.mote.remote.net.Remote
import dev.mote.remote.net.Net
import dev.mote.remote.net.Route
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTransport
import dev.mote.remote.net.routeOf
import dev.mote.remote.ui.Haptic
import dev.mote.remote.ui.PrimaryButton
import dev.mote.remote.ui.T
import dev.mote.remote.ui.WordmarkText
import dev.mote.remote.ui.haptics
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen state shown by the viewer; one per open page. */
private class View {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var frame by mutableStateOf<ScreenProto.Frame?>(null)
    var message by mutableStateOf<String?>(null)
    var fps by mutableFloatStateOf(0f)
    var kib by mutableFloatStateOf(0f)
    var tap by mutableStateOf<Pair<Offset, Long>?>(null)
}

/**
 * Labs › Live screen (docs/labs/live-screen.md): the Mac's active display, low rate. Tap = click,
 * long-press = right click, a second tap = double click, two fingers zoom and pan the picture (not the
 * Mac). Streams only while this page is on screen; leaving it or backgrounding the app stops it.
 */
@Composable
fun LiveScreenPage(onBack: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val active by Net.active.collectAsState()
    val ws = active as? WsTransport
    val status by (ws?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow<WsStatus>(WsStatus.Idle) }).collectAsState()
    val ready = status as? WsStatus.Ready
    val btLinked by Remote.linked.collectAsState()
    LaunchedEffect(ws == null) { Blockers.note("live_screen", if (ws == null) Blockers.link(Blockers.Link(false, btLinked)) else null) }
    val plainLan = ready != null && routeOf(ready.address) != Route.TAILNET
    // null until DataStore has answered: never start (or flash the notice) on a guess; the stream is unencrypted.
    val warnedState by remember { ScreenPrefs.warned(ctx) }.collectAsState(initial = null)
    val warned = warnedState == true
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val visible = lstate.isAtLeast(Lifecycle.State.STARTED)
    val debug = remember { ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    val v = remember { View() }

    // The stream: runs while linked, on screen, not paused and past the LAN warning. Cancelling it
    // (back, background, pause, link drop) sends `stop`; the helper also stops on its 2 s lease.
    val streaming = ws != null && ready != null && visible && !paused && warnedState != null && (!plainLan || warned)
    LaunchedEffect(ws, streaming, ready?.address) {
        if (!streaming || ws == null) return@LaunchedEffect
        v.message = null
        var lastSeq = 0
        val heartbeat = launch { while (true) { delay(500); ws.sendLabs(ScreenProto.ack(lastSeq)) } }
        val times = ArrayDeque<Long>()
        try {
            // Start once subscribed, so an immediate `err` (lab off on the Mac, no permission) isn't missed.
            LabsBus.incoming.onSubscription { ws.sendLabs(ScreenProto.start(w = 960, fps = 12, credits = 2)) }.collect { o ->
                    when (val e = ScreenProto.parse(o)) {
                        is ScreenProto.FrameIn -> {
                            val f = e.f
                            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }
                            lastSeq = f.seq
                            ws.sendLabs(ScreenProto.ack(f.seq))   // after decoding: the credit paces the stream to this phone
                            if (bmp != null) { v.bitmap = bmp; v.frame = f }
                            val now = SystemClock.elapsedRealtime()
                            times.addLast(now); while (times.isNotEmpty() && now - times.first() > 2000) times.removeFirst()
                            v.fps = times.size / 2f
                            v.kib = f.jpeg.size / 1024f
                        }
                        is ScreenProto.StartedIn -> v.message = null
                        is ScreenProto.StoppedIn -> v.message = ScreenProto.explain(e.reason)
                        is ScreenProto.ErrIn -> v.message = ScreenProto.explain(e.code, e.msg)
                        null -> Unit
                    }
            }
        } finally {
            heartbeat.cancel()
            ws.sendLabs(ScreenProto.stop())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
                MIcon("chevLeft", c.ink, description = "Back")
            }
            Text("Screen", style = T.title, color = c.ink, modifier = Modifier.weight(1f))
            if (debug && v.frame != null) {
                Text("%.0f fps · %d ms · %.0f KiB".format(v.fps, v.frame?.rttMs ?: 0, v.kib), style = T.small, color = c.muted,
                    modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Stream stats" })
            }
            if (ws != null) {
                Box(
                    Modifier.size(44.dp).clickable(onClickLabel = if (paused) "Resume sharing" else "Pause sharing") { paused = !paused },
                    contentAlignment = Alignment.Center,
                ) { MIcon(if (paused) "playPause" else "hold", c.ink, description = if (paused) "Resume sharing" else "Pause sharing") }
            } else {
                WordmarkText()
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            when {
                ws == null -> Notice("Live screen needs Mote Helper", "${Blockers.link(Blockers.Link(false, btLinked))?.text}. Bluetooth can't carry the picture.")
                plainLan && warnedState == false -> Notice(
                    "Your screen travels unencrypted",
                    "On plain Wi-Fi, anyone on this network who can capture traffic could see the Mac's screen. Tailscale encrypts it. Frames are never saved.",
                    action = "Show the screen",
                ) { scope.launch { ScreenPrefs.markWarned(ctx) } }
                ready == null -> Notice("Connecting to the Mac…", "The screen shows once Mote Helper answers.")
                else -> Viewer(v, ws, paused)
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalMote.current
    LaunchedEffect(title) { dev.mote.remote.diag.Events.log("ui", "banner", "banner" to "live-screen", "title" to title) }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MIcon("laptop", c.accent, size = 40.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = T.title, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = T.body, color = c.muted, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(24.dp)); PrimaryButton(action, onClick = onAction) }
    }
}

@Composable
private fun Viewer(v: View, ws: WsTransport, paused: Boolean) {
    val c = LocalMote.current
    val h = haptics()
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val taps = remember { TapCounter() }
    val frame by rememberUpdatedState(v.frame)
    val imgW = (frame?.w ?: 16).toFloat(); val imgH = (frame?.h ?: 10).toFloat()
    val iw by rememberUpdatedState(imgW); val ih by rememberUpdatedState(imgH)

    fun norm(p: Offset) = ViewMath.toNorm(p.x, p.y, box.width.toFloat(), box.height.toFloat(), iw, ih, scale, ox, oy)
    fun click(p: Offset, button: String) {
        val f = frame ?: return
        val n = norm(p) ?: return
        val (count, x, y) = if (button == "left") taps.tap(n.first, n.second, SystemClock.uptimeMillis()) else Triple(1, n.first, n.second)
        ws.sendLabs(ScreenProto.click(x.toDouble(), y.toDouble(), button, count, f.display))
        v.tap = p to SystemClock.uptimeMillis()
        h(if (button == "right") Haptic.HEAVY else Haptic.CLICK)
    }

    Box(
        Modifier.fillMaxSize().well(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .onSizeChanged { box = it }
            .semantics { contentDescription = "Mac screen. Tap to click, hold to right-click, pinch to zoom." }
            .pointerInput(paused) {
                if (paused) return@pointerInput
                detectTapGestures(onTap = { click(it, "left") }, onLongPress = { click(it, "right") })
            }
            // Two fingers: zoom and pan the picture around the centroid. Consumed, so they never click.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            val z = ev.calculateZoom(); val pan = ev.calculatePan(); val p = ev.calculateCentroid()
                            val s2 = (scale * z).coerceIn(1f, ViewMath.MAX_ZOOM)
                            val k = s2 / scale
                            val cx = box.width / 2f; val cy = box.height / 2f
                            val nx = p.x + pan.x - cx - k * (p.x - ox - cx)
                            val ny = p.y + pan.y - cy - k * (p.y - oy - cy)
                            val (px, py) = ViewMath.clampPan(nx, ny, s2, box.width.toFloat(), box.height.toFloat(), iw, ih)
                            scale = s2; ox = px; oy = py
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = v.bitmap
        if (bmp != null) {
            Image(
                bmp, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = ox; translationY = oy
                    alpha = if (paused) 0.4f else 1f
                },
            )
            val accent = c.accent
            Canvas(Modifier.fillMaxSize()) {
                val f = frame
                val cxN = f?.cx; val cyN = f?.cy
                if (cxN != null && cyN != null) {
                    val (x, y) = ViewMath.fromNorm(cxN, cyN, size.width, size.height, iw, ih, scale, ox, oy)
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(4.dp.toPx()))
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
                }
                v.tap?.let { (p, at) ->
                    if (SystemClock.uptimeMillis() - at < 400) drawCircle(accent.copy(alpha = 0.5f), radius = 18.dp.toPx(), center = p)
                }
            }
        } else if (v.message == null) {
            Text("Waiting for the first frame…", style = T.body, color = Color.White.copy(alpha = 0.7f))
        }
        v.message?.let { msg ->
            Text(msg, style = T.body, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(16.dp).width(280.dp))
        }
        if (paused) Text("Sharing paused", style = T.label, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
    }
}

/** The live screen's own one-time state (DataStore "labs_screen"): the plain-LAN warning was acknowledged. */
private object ScreenPrefs {
    private val Context.store by preferencesDataStore("labs_screen", corruptionHandler = Prefs.corruptionHandler)
    private val WARNED = booleanPreferencesKey("lan_warning_seen")
    fun warned(c: Context): Flow<Boolean> = c.store.data.map { it.typed(WARNED) ?: false }
    suspend fun markWarned(c: Context) { c.store.edit { it[WARNED] = true } }
}

/** Settings › Labs › Live screen is on (gates the menu entry). */
@Composable
fun liveScreenOn(): Boolean {
    val ctx = LocalContext.current
    val on by remember { Labs.flow(ctx, Labs.LIVE_SCREEN) }.collectAsState(initial = false)
    return on
}
