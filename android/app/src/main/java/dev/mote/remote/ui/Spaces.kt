package dev.mote.remote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.data.UiSettings
import dev.mote.remote.hid.Mod
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.SoftShadow
import dev.mote.remote.ui.theme.drawShadow
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// data seam

/**
 * What the helper reports about Spaces (1-based [current]). Mirrors the `net/` API being added in
 * parallel (`Remote.spaces: StateFlow<SpacesState?>`, `Remote.spacesGoto(i)`); reconcile at merge by
 * pointing [SpacesSource] at Remote. Null state = the transport can't see Spaces (Bluetooth).
 */
data class SpacesState(val count: Int, val current: Int, val thumbnails: Map<Int, ImageBitmap> = emptyMap())

interface SpacesApi {
    val spaces: StateFlow<SpacesState?>
    fun spacesGoto(i: Int)
}

/** Until the net/ API lands no transport reports Spaces, so the UI runs the Bluetooth path. */
object SpacesSource : SpacesApi {
    override val spaces: StateFlow<SpacesState?> = MutableStateFlow<SpacesState?>(null).asStateFlow()
    override fun spacesGoto(i: Int) {}
}

/**
 * Bluetooth Spaces: the Mac can't tell us the current desktop, so we track the last one we jumped to
 * (default 1). A jump sends ⌃N when the user enabled "Switch to Desktop N" (N ≤ 9), otherwise steps
 * ⌃← / ⌃→ |current − target| times, spaced so the macOS slide animation keeps up.
 */
object BtSpaces {
    private val _current = MutableStateFlow(1)
    val current: StateFlow<Int> = _current.asStateFlow()

    /** One chord: key name + modifiers. */
    data class Chord(val key: String, val mods: Int = Mod.CTRL)

    fun plan(from: Int, to: Int, ctrlNumbers: Boolean): List<Chord> = when {
        from == to -> emptyList()
        ctrlNumbers && to in 1..9 -> listOf(Chord(to.toString()))
        else -> List(abs(to - from)) { Chord(if (to > from) "right" else "left") }
    }

    // lazy: the planner is unit-tested on the JVM, where Dispatchers.Main doesn't exist
    private val queue by lazy {
        Channel<Chord>(Channel.UNLIMITED).also { ch ->
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { for (c in ch) { Remote.tap(c.key, c.mods); delay(STEP_MS) } }
        }
    }

    fun goto(to: Int, cfg: UiSettings) {
        val from = _current.value
        _current.value = to.coerceIn(1, cfg.desktopCount)
        plan(from, _current.value, cfg.ctrlNumberShortcuts).forEach { queue.trySend(it) }
    }

    /** A step every 300 ms: macOS queues the slides without skipping a desktop. */
    const val STEP_MS = 300L
}

// ---------------------------------------------------------------------------------------------
// UI

/**
 * The Spaces carousel (rev 6 #60): an 84dp well holding desktop cards that snap start-aligned, with a
 * 24dp fade at the trailing edge. Tap a card to jump. Over Bluetooth cards are numbered; with the
 * helper they show cached thumbnails or a dashed "Not visited yet" placeholder.
 */
@Composable
fun SpacesCarousel(cardWidth: Dp = 104.dp, modifier: Modifier = Modifier) {
    val c = LocalMote.current
    val cfg = LocalSettings.current
    val helper by SpacesSource.spaces.collectAsState()
    val btCurrent by BtSpaces.current.collectAsState()
    val count = helper?.count ?: cfg.desktopCount
    val current = helper?.current ?: btCurrent
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((current - 2).coerceAtLeast(0)) }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().height(84.dp).well(shape)) {
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Start),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count) { i ->
                val n = i + 1
                SpaceCard(n, n == current, cardWidth, helper != null, helper?.thumbnails?.get(n)) {
                    if (helper != null) SpacesSource.spacesGoto(n) else BtSpaces.goto(n, cfg)
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp)
                .background(Brush.horizontalGradient(listOf(c.well.copy(alpha = 0f), c.well))),
        )
    }
}

@Composable
private fun SpaceCard(n: Int, current: Boolean, width: Dp, helper: Boolean, thumb: ImageBitmap?, onGo: () -> Unit) {
    val c = LocalMote.current
    val shape = RoundedCornerShape(14.dp)
    val label = "Desktop $n"
    Box(
        Modifier.size(width, 68.dp).then(
            if (current) Modifier.drawWithCache {
                val p = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))) }
                val glow = SoftShadow(c.accentGlow, 12.dp.toPx(), dy = 0f)
                onDrawBehind { drawShadow(p, glow) }
            } else Modifier,
        ),
    ) {
        if (helper && thumb == null) {
            // never visited: dashed placeholder, no fill
            Box(Modifier.fillMaxSize().dashedSlot(c.keyEdge, 14.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = T.small.copy(fontSize = 11.sp), color = c.subtle)
                    Text("Not visited yet", style = T.small.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal), color = c.subtle)
                }
            }
            return@Box
        }
        Cap(Modifier.fillMaxSize(), shape, description = label + if (current) ", current" else "", onClick = onGo) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(20.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000)))),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(label, style = T.small.copy(fontSize = 11.sp), color = Color.White, modifier = Modifier.padding(start = 9.dp)) }
            } else {
                Box(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 7.dp)) {
                    Text("$n", style = T.title.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight(650)),
                        color = if (current) c.accent else c.ink, modifier = Modifier.align(Alignment.TopStart))
                    Text(label, style = T.small.copy(fontSize = 11.sp), color = if (current) c.accent else c.muted, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
            if (current) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(7.dp).drawBehind {
                    drawCircle(c.accentGlow, radius = size.minDimension / 2 + 3.dp.toPx())
                    drawCircle(c.accent)
                })
            }
        }
        if (current) Box(Modifier.fillMaxSize().border(2.dp, c.accent, shape))
    }
}

/** Header for a carousel: left caption, right hint, 13sp. */
@Composable
fun CarouselCaption(left: String, right: String, caps: Boolean = false) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val st = if (caps) T.caps else T.body.copy(fontSize = 13.sp, lineHeight = 18.sp)
        Text(if (caps) left.uppercase() else left, style = st, color = c.muted, modifier = Modifier.weight(1f))
        Text(if (caps) right.uppercase() else right, style = st, color = c.muted)
    }
}

/** The bottom sheet opened from the Macros Spaces tile. */
@Composable
fun SpacesSheet(onClose: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val cfg = LocalSettings.current
    val helper by SpacesSource.spaces.collectAsState()
    val scope = rememberCoroutineScope()
    var later by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Spaces", style = T.headline, color = c.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) { MIcon("close", c.ink, description = "Close") }
        }
        Text("Swipe the row or tap a desktop to jump to it. The lit card is the desktop you are on.", style = T.body, color = c.muted)
        Spacer(Modifier.height(16.dp))
        if (helper != null) {
            CarouselCaption("With the Mote helper", "Thumbnails", caps = true)
            Spacer(Modifier.height(8.dp))
            SpacesCarousel(112.dp)
            Spacer(Modifier.height(12.dp))
            Text("The helper saves a picture of each desktop when you visit it. It needs Screen Recording permission on the Mac.",
                style = T.body.copy(fontSize = 16.sp, lineHeight = 22.sp), color = c.muted)
        } else {
            CarouselCaption("Over Bluetooth", "Numbered", caps = true)
            Spacer(Modifier.height(8.dp))
            SpacesCarousel(112.dp)
            if (!cfg.ctrlNumberShortcuts && !cfg.spacesHintDone && !later) {
                Spacer(Modifier.height(12.dp))
                SpacesHint(
                    onDone = { scope.launch { UiPrefs.save(ctx, cfg.copy(spacesHintDone = true)) } },
                    onLater = { later = true },
                )
            }
        }
    }
}

/** One-time hint: turn on "Switch to Desktop N" so cards jump straight there. */
@Composable
fun SpacesHint(onDone: () -> Unit, onLater: () -> Unit) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().well(RoundedCornerShape(16.dp)).padding(16.dp)) {
        MIcon("info", c.muted, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)); append("Jump straight to a desktop. "); pop()
                    append("On the Mac, turn on Switch to Desktop 1-9 in System Settings, Keyboard Shortcuts, Mission Control. Until then Mote steps with ⌃← and ⌃→.")
                },
                style = T.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cap(Modifier.height(36.dp).width(64.dp), RoundedCornerShape(11.dp), needsLink = false, haptic = Haptic.TICK, description = "Done", onClick = onDone) {
                    Text("Done", style = T.label, color = c.ink)
                }
                Spacer(Modifier.width(16.dp))
                Text("Remind me later", style = T.label, color = c.ink, modifier = Modifier.clickable(onClick = onLater).padding(vertical = 8.dp))
            }
        }
    }
}
