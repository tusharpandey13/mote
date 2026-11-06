package dev.mote.remote.ui

import dev.mote.remote.net.Remote
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mote.remote.ui.theme.Rubber
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
        // fixed: favourites 110, brightness 54 + 24, transport 20 + 112; the control cluster absorbs the rest
        val cluster = (maxHeight - 340.dp).coerceIn(196.dp, 256.dp)
        val slot = cluster - 24.dp
        val dpad = (cluster - 48.dp).coerceAtMost(208.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon("starFill", c.subtle, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("FAVOURITES", style = T.caps, color = c.subtle, modifier = Modifier.weight(1f))
                EditCap(onClick = onEditFavourites)
            }
            Spacer(Modifier.height(6.dp))
            FavouriteStrip(favourites, run, onAdd = onEditFavourites)
            Spacer(Modifier.weight(1f))
            Pair(Modifier.height(54.dp)) {
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(14.dp), haptic = Haptic.TICK, description = "Brightness down",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.BRIGHTNESS_DOWN) }) { Arrowed("sunDown", "arrowDown") }
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(14.dp), haptic = Haptic.TICK, description = "Brightness up",
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
                    DPad(dpad)
                    MachinedSlider(
                        label = "VOLUME", slotHeight = slot,
                        upEnd = { Text("+", style = T.title.copy(fontWeight = FontWeight.Bold), color = c.muted) },
                        downEnd = { Text("−", style = T.title.copy(fontWeight = FontWeight.Bold), color = c.muted) },
                        upDescription = "Volume up", downDescription = "Volume down", thumbDescription = "Volume lever",
                        rates = intArrayOf(0, 3, 5, 8, 12), thumb = { MIcon("speaker", c.metalInk, size = 20.dp) },
                        onNotch = { up -> Remote.consumer(if (up) Consumer.VOL_UP else Consumer.VOL_DOWN) },
                    )
                }
                // round mute cap at the D-pad's lower right, beside the volume slider's minus end
                Cap(Modifier.align(Alignment.BottomEnd).padding(end = 66.dp, bottom = 30.dp).size(48.dp), CircleShape,
                    description = "Mute", onClick = { Remote.consumer(Consumer.MUTE) }) { MIcon("mute", c.ink, size = 22.dp) }
            }
            Spacer(Modifier.height(20.dp))
            // one family: three glass caps in one recessed channel; play is wider and taller
            Row(
                Modifier.fillMaxWidth().well(RoundedCornerShape(30.dp)).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Cap(Modifier.size(72.dp), RoundedCornerShape(22.dp), tone = Tone.GLASS, description = "Previous track",
                    onClick = { Remote.consumer(Consumer.PREV) }) { GlassIcon("prev", 28.dp) }
                Cap(Modifier.weight(1f).height(96.dp), RoundedCornerShape(24.dp), tone = Tone.GLASS, description = "Play or pause",
                    onClick = { Remote.consumer(Consumer.PLAY_PAUSE) }) { GlassIcon("playPause", 34.dp) }
                Cap(Modifier.size(72.dp), RoundedCornerShape(22.dp), tone = Tone.GLASS, description = "Next track",
                    onClick = { Remote.consumer(Consumer.NEXT) }) { GlassIcon("next", 28.dp) }
            }
        }
    }
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

/** A recessed well holding a row of caps (button pair/group). */
@Composable
private fun Pair(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun FavouriteStrip(favs: List<Macro>, run: (Macro) -> Unit, onAdd: () -> Unit) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var used = 0
        favs.forEach { m ->
            val span = if (m.kind == Macro.Kind.SPACES) 2 else 1
            if (used + span > 4) return@forEach
            used += span
            if (m.kind == Macro.Kind.SPACES) SpacesTile(Modifier.weight(span.toFloat()).fillMaxHeight(), compact = true)
            else MacroTile(m, Modifier.weight(span.toFloat()).fillMaxHeight(), compact = true, onRun = run)
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

private enum class Arm(val key: String) { UP("up"), RIGHT("right"), DOWN("down"), LEFT("left"), CENTER("return") }

/** Circular rubber D-pad: arms send arrows (host repeats while held), center tap = Return, hold = Space. */
@Composable
private fun DPad(size: androidx.compose.ui.unit.Dp) {
    val c = LocalMote.current
    val h = haptics()
    val linked = LocalLinked.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf<Arm?>(null) }
    val linkedNow = androidx.compose.runtime.rememberUpdatedState(linked)
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = "Direction pad" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = this.size.width / 2f
                    val dx = down.position.x - r
                    val dy = down.position.y - r
                    val k = r / 104f
                    val d = hypot(dx, dy)
                    if (d > r) return@awaitEachGesture
                    if (!linkedNow.value) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                    val arm = if (d < 42 * k) Arm.CENTER else {
                        val a = Math.toDegrees(atan2(dy, dx).toDouble())
                        when {
                            a >= -135 && a < -45 -> Arm.UP
                            a >= -45 && a < 45 -> Arm.RIGHT
                            a >= 45 && a < 135 -> Arm.DOWN
                            else -> Arm.LEFT
                        }
                    }
                    pressed = arm
                    var job: Job? = null
                    var longFired = false
                    if (arm == Arm.CENTER) {
                        h(Haptic.CLICK)
                        job = scope.launch { delay(500); longFired = true; h(Haptic.HEAVY); Remote.tap("space") }
                    } else {
                        h(Haptic.TICK)
                        Remote.keyDown(arm.key) // macOS applies its own key repeat while held
                    }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    if (arm == Arm.CENTER) { if (up != null && !longFired) Remote.tap("return") } else Remote.keyUp(arm.key)
                    pressed = null
                }
            }
            .drawWithCache {
                val k = this.size.width / 208f
                val ctr = Offset(this.size.width / 2, this.size.height / 2)
                val housing = Path().apply { addOval(Rect(ctr, 104 * k)) }
                val housingFrame = frameAround(housing, 24 * k)
                val housingShade = SoftShadow(c.wellShade, 8.dp.toPx(), dy = 3.dp.toPx())
                val disc = Rubber(c, ctr, 96 * k, density)
                val ring = Path().apply { addOval(Rect(ctr, 42 * k)) }
                val ringFrame = frameAround(ring, 12 * k)
                val ringShade = SoftShadow(c.wellShade, 4.dp.toPx(), dy = 2.dp.toPx())
                val okCap = Rubber(c, ctr, 35 * k, density)
                val okPressed = Rubber(c, ctr, 35 * k, density, pressed = true)
                val armShade = Color.Black.copy(alpha = if (c.dark) 0.22f else 0.07f)
                val divider = if (c.dark) Color.Black.copy(alpha = 0.45f) else c.rubWall.copy(alpha = 0.55f)
                val tri = Path().apply {
                    moveTo(ctr.x, ctr.y - 77 * k); lineTo(ctr.x + 7 * k, ctr.y - 67.5f * k); lineTo(ctr.x - 7 * k, ctr.y - 67.5f * k); close()
                }
                val armRect = Rect(ctr, 96 * k)
                onDrawBehind {
                    drawCircle(c.well, 104 * k, ctr)
                    drawInnerShadow(housing, housingFrame, housingShade)
                    with(disc) { draw() }
                    Arm.entries.filter { it != Arm.CENTER }.forEachIndexed { i, arm ->
                        rotate(i * 90f, ctr) {
                            if (pressed == arm) drawArc(armShade, 225f, 90f, true, armRect.topLeft, armRect.size)
                            drawPath(tri, if (pressed == arm) c.accent else c.muted)
                        }
                    }
                    for (i in 0 until 4) rotate(45f + i * 90f, ctr) {
                        drawLine(divider, Offset(ctr.x, ctr.y - 46 * k), Offset(ctr.x, ctr.y - 80 * k), 1.25f * k)
                    }
                    drawCircle(c.well, 42 * k, ctr)
                    drawInnerShadow(ring, ringFrame, ringShade)
                    with(if (pressed == Arm.CENTER) okPressed else okCap) { draw() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("OK", style = T.label.copy(fontSize = 15.sp), color = if (pressed == Arm.CENTER) c.accent else c.ink)
    }
}
