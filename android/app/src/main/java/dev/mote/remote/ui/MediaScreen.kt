package dev.mote.remote.ui

import dev.mote.remote.net.Remote
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mote.remote.ui.theme.Rubber
import dev.mote.remote.ui.theme.MetalRing
import dev.mote.remote.ui.theme.SoftShadow
import dev.mote.remote.ui.theme.drawInnerShadow
import dev.mote.remote.ui.theme.frameAround
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mote.remote.data.Macro
import dev.mote.remote.hid.Consumer
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun MediaScreen(favourites: List<Macro>, run: (Macro) -> Unit, onEditFavourites: () -> Unit) {
    val c = LocalMote.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 12.dp)) {
        // fixed: favourites ~200, brightness 74 + 24, transport 20 + 112; the control cluster absorbs the rest
        val showSpaces = favourites.any { it.kind == Macro.Kind.SPACES }
        val cluster = (maxHeight - (if (showSpaces) 448 else 336).dp).coerceIn(196.dp, 256.dp)
        val slot = cluster - 24.dp
        val dpad = cluster.coerceAtMost(236.dp)
        Column(Modifier.fillMaxSize()) {
            CappedFontScale { // fixed-height header + carousel
            // the Edit chip's 48dp touch target overhangs this 32dp row
            Row(Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon("starFill", c.subtle, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("FAVOURITES", style = T.caps, color = c.subtle, modifier = Modifier.weight(1f))
                EditCap(compact = true, onClick = onEditFavourites)
            }
            Spacer(Modifier.height(8.dp))
            if (showSpaces) {
                // rev 6 #60: Spaces is a carousel row of its own at the top of Favourites
                val hs = helperSpaces()
                CarouselCaption("Spaces", "swipe or tap a desktop", trailing = if (hs != null && !hs.shared && hs.displays.size > 1) {
                    { DisplayIndicator(hs) }
                } else null)
                Spacer(Modifier.height(4.dp))
                SpacesCarousel()
                Spacer(Modifier.height(8.dp))
            }
            }
            FavouriteStrip(favourites.filter { it.kind != Macro.Kind.SPACES }, run, onAdd = onEditFavourites)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().height(74.dp).well(RoundedCornerShape(22.dp)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(17.dp), haptic = Haptic.TICK, description = "Brightness down",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.BRIGHTNESS_DOWN) }) { Arrowed("sunDown", "arrowDown") }
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(17.dp), haptic = Haptic.TICK, description = "Brightness up",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.BRIGHTNESS_UP) }) { Arrowed("sunUp", "arrowUp") }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(cluster)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    MachinedSlider(
                        label = "SCROLL", slotHeight = slot,
                        upEnd = { MIcon("triUp", c.muted, size = 14.dp) }, downEnd = { MIcon("triDown", c.muted, size = 14.dp) },
                        upDescription = "Scroll up one notch", downDescription = "Scroll down one notch", thumbDescription = "Scroll lever",
                        rates = intArrayOf(0, 4, 8, 16, 30), thumb = { GripLines() },
                        // up = toward the top of the document with macOS natural scrolling (the default)
                        onNotch = { up -> Remote.scroll(if (up) -1 else 1, 0) },
                    )
                    JoystickPad(dpad)
                    MachinedSlider(
                        label = "VOLUME", slotHeight = slot,
                        upEnd = { Text("+", style = T.title.copy(fontWeight = FontWeight.Bold), color = c.muted) },
                        downEnd = { Text("−", style = T.title.copy(fontWeight = FontWeight.Bold), color = c.muted) },
                        upDescription = "Volume up", downDescription = "Volume down", thumbDescription = "Volume lever",
                        rates = intArrayOf(0, 3, 5, 8, 12), thumb = { MIcon("speaker", c.metalInk, size = 20.dp) },
                        onNotch = { up -> Remote.consumer(if (up) Consumer.VOL_UP else Consumer.VOL_DOWN) },
                        // rev 6 #62: mute is a 44dp cap in the base of the volume well
                        base = {
                            Cap(Modifier.size(44.dp), CircleShape, description = "Mute", onClick = { Remote.consumer(Consumer.MUTE) }) {
                                MIcon("mute", c.ink, size = 20.dp)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // rev 6 #67 / rev 7 #71: three metal caps on one 96dp baseline; prev/next 76dp wide, play (spun metal) fills the rest
            Row(
                Modifier.fillMaxWidth().well(RoundedCornerShape(30.dp), bezel = true).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SeekCap("prev", "Previous track, hold to rewind", Consumer.PREV, Consumer.REWIND)
                Cap(Modifier.weight(1f).height(96.dp), RoundedCornerShape(24.dp), tone = Tone.METAL_PLAY, description = "Play or pause",
                    onClick = { Remote.consumer(Consumer.PLAY_PAUSE) }) { EmbossIcon("playPause", 32.dp) }
                SeekCap("next", "Next track, hold to fast-forward", Consumer.NEXT, Consumer.FAST_FORWARD)
            }
        }
    }
}

/**
 * Prev/next brushed-metal cap: tap = [skip]; hold past 400 ms = [seek] held down until lift (fast-forward or
 * rewind), with a tick when seeking starts and a pressed, lit cap while it lasts. See [SeekHold].
 */
@Composable
private fun SeekCap(icon: String, description: String, skip: Int, seek: Int) {
    val h = haptics()
    val scope = rememberCoroutineScope()
    val hold = remember { SeekHold() }
    var seeking by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf<Job?>(null) }
    fun now() = android.os.SystemClock.uptimeMillis()
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (hold.cancel() == SeekHold.Out.SEEK_STOP) Remote.consumerHold(seek, false) }
    }
    Cap(
        Modifier.size(76.dp, 96.dp), RoundedCornerShape(24.dp), tone = Tone.METAL, description = description, forcePressed = seeking,
        onDown = {
            hold.down(now())
            timer = scope.launch {
                delay(SeekHold.THRESHOLD_MS)
                if (hold.tick(now()) == SeekHold.Out.SEEK_START) { seeking = true; h(Haptic.TICK); Remote.consumerHold(seek, true) }
            }
        },
        onUp = { lifted ->
            timer?.cancel()
            when (if (lifted) hold.up(now()) else hold.cancel()) {
                SeekHold.Out.SKIP -> Remote.consumer(skip)
                SeekHold.Out.SEEK_STOP -> { seeking = false; Remote.consumerHold(seek, false) }
                else -> {}
            }
        },
        a11yClick = { Remote.consumer(skip) },
    ) { EmbossIcon(icon, 28.dp, lit = seeking) }
}

/** Brightness legend: sun 24dp + arrow 20dp at ink colour, one centred row. */
@Composable
private fun Arrowed(sun: String, arrow: String) {
    val c = LocalMote.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MIcon(sun, c.ink, size = 24.dp)
        MIcon(arrow, c.ink, size = 20.dp)
    }
}

@Composable
private fun FavouriteStrip(favs: List<Macro>, run: (Macro) -> Unit, onAdd: () -> Unit) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var used = 0
        favs.take(4).forEach { m ->
            used += 1
            MacroTile(m, Modifier.weight(1f).fillMaxHeight(), compact = true, onRun = run)
        }
        // the strip never looks broken: the free space is one dashed "+ Add favourite" slot
        if (used < 4) {
            Box(
                Modifier.weight((4 - used).toFloat()).fillMaxHeight().dashedSlot(c.keyEdge, 16.dp)
                    .clickable(onClickLabel = "Add favourite", onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon("plus", c.subtle, size = 20.dp)
                    if (4 - used >= 2) { Spacer(Modifier.width(6.dp)); Text("Add favourite", style = T.label.copy(fontWeight = FontWeight.Medium), color = c.subtle) }
                }
            }
        }
    }
}
