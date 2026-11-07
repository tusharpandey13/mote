package dev.mote.remote.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.data.UiSettings
import dev.mote.remote.hid.Mod
import dev.mote.remote.net.DisplaySpaces
import dev.mote.remote.net.Remote
import dev.mote.remote.net.SpacesState
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

// Helper Spaces come from [Remote.spaces] / [Remote.spacesGoto] (net/, protocol 1.3; 1.4 adds per-display
// lists: the state's count/current/thumbs are the active display's and follow it live); null state =
// the transport can't see Spaces (Bluetooth, pre-1.3 helper, helper count unknown): use [BtSpaces].

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

/** Debug builds only: a fake two-display helper state for screenshots (`--ez mote.demo.displays true`). */
object DemoSpaces {
    val state = MutableStateFlow<SpacesState?>(null)

    fun enable() {
        state.value = SpacesState(
            count = 6, current = 2, capture = "on", versions = List(6) { 0 }, thumbs = emptyMap(),
            displays = listOf(DisplaySpaces("builtin", "Built-in", 6, 2), DisplaySpaces("lg", "LG UltraFine", 3, 1)),
            activeDisplay = "builtin",
        )
    }
}

/** Helper Spaces for the UI (or the debug demo); null over Bluetooth / unknown. */
@Composable
fun helperSpaces(): SpacesState? {
    val live = Remote.spaces.collectAsState().value
    val demo = DemoSpaces.state.collectAsState().value
    return (live ?: demo)?.takeIf { it.count > 0 }
}

/**
 * Which display the carousel shows: the Mac's active display, unless the user picked another in the
 * display menu since the active one last changed. Shared by the chip and the carousel.
 */
object DisplayPick {
    val picked = MutableStateFlow<String?>(null)
    /** The active display the pick was made against; a new active display clears the pick. */
    var pickedAgainst: String? = null
}

/** The display the carousel should show for [s], and its Spaces (null when the helper doesn't list displays). */
@Composable
fun shownDisplay(s: SpacesState?): DisplaySpaces? {
    val picked by DisplayPick.picked.collectAsState()
    if (s == null || s.displays.isEmpty()) return null
    val uuid = if (picked != null && DisplayPick.pickedAgainst == s.activeDisplay) picked else s.activeDisplay
    return s.displays.firstOrNull { it.uuid == uuid } ?: s.displays.firstOrNull { it.uuid == s.activeDisplay } ?: s.displays.first()
}

/**
 * Carousel header display indicator (rev 7 #69, tokens.json components.spacesCarousel.displayIndicator):
 * one dot per display (active lit) and a plastic chip with the display's icon, name and ▾, which opens
 * a list of displays. Helper only, and only when the Mac has two or more displays.
 */
@Composable
fun DisplayIndicator(s: SpacesState?) {
    val c = LocalMote.current
    val shown = shownDisplay(s) ?: return
    if (s == null || s.shared || s.displays.size <= 1) return
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Display ${s.displays.indexOf(shown) + 1} of ${s.displays.size}"
        }) {
            s.displays.forEach { d ->
                val on = d.uuid == shown.uuid
                Box(Modifier.size(6.dp).drawBehind {
                    if (on) drawCircle(c.accentGlow, radius = size.minDimension / 2 + 2.5.dp.toPx())
                    drawCircle(if (on) c.accent else c.subtle.copy(alpha = 0.5f))
                })
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Cap(Modifier.height(26.dp), RoundedCornerShape(9.dp), needsLink = false, haptic = Haptic.TICK,
                description = "Display: ${shown.name}. Choose display", onClick = { open = true }) {
                Row(Modifier.padding(start = 7.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MIcon(displayIcon(shown.name), c.ink, size = 16.dp)
                    Text(shown.name, style = T.label.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1)
                    MIcon("chevDown", c.ink, size = 16.dp)
                }
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                s.displays.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.name + if (d.uuid == s.activeDisplay) " (active)" else "", style = T.label) },
                        leadingIcon = { MIcon(displayIcon(d.name), c.ink, size = 20.dp) },
                        trailingIcon = { if (d.uuid == shown.uuid) MIcon("check", c.accent, size = 20.dp) },
                        onClick = {
                            DisplayPick.pickedAgainst = s.activeDisplay
                            DisplayPick.picked.value = d.uuid
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun displayIcon(name: String) = if (name.contains("Built-in", ignoreCase = true) || name.contains("Retina", ignoreCase = true)) "laptop" else "desktop"

/**
 * The Spaces carousel (rev 6 #60): an 84dp well holding desktop cards that snap start-aligned, with a
 * 24dp fade at the trailing edge. Tap a card to jump. Over Bluetooth cards are numbered; with the
 * helper they show cached thumbnails or a dashed "Not visited yet" placeholder. Rev 7 #69: when the
 * shown display changes the cards fade out (120ms), the row jumps to the current card, and the new
 * cards fade in (200ms).
 */
@Composable
fun SpacesCarousel(cardWidth: Dp = 104.dp, modifier: Modifier = Modifier) {
    val c = LocalMote.current
    val cfg = LocalSettings.current
    val reduced = reducedMotion()
    val helper = helperSpaces()
    val target = shownDisplay(helper)
    val btCurrent by BtSpaces.current.collectAsState()
    // the display whose cards are on screen; lags [target] through the fade-out
    var shownUuid by remember { mutableStateOf(target?.uuid) }
    val fade = remember { Animatable(1f) }
    val shown = helper?.displays?.firstOrNull { it.uuid == shownUuid }
    val active = shown == null || shown.uuid == helper.activeDisplay
    val count = shown?.count ?: helper?.count ?: cfg.desktopCount
    val current = shown?.current ?: helper?.current ?: btCurrent
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((current - 2).coerceAtLeast(0)) }
    // the shown display changed (the Mac's active display, or a pick): fade out, jump, fade in
    LaunchedEffect(target?.uuid) {
        val next = target?.uuid
        if (next == shownUuid) return@LaunchedEffect
        if (!reduced && shownUuid != null) fade.animateTo(0f, Motion.exit())
        shownUuid = next
        list.scrollToItem(((target?.current ?: 1) - 2).coerceAtLeast(0))
        if (reduced) fade.snapTo(1f) else fade.animateTo(1f, Motion.effectMed())
    }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().height(84.dp).well(shape)) {
        LazyRow(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Start),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
        ) {
            items(count) { i ->
                val n = i + 1
                SpaceCard(n, n == current, cardWidth, helper != null, if (active) helper?.thumbs?.get(n)?.image else null) {
                    if (helper != null) Remote.spacesGoto(n, shown?.uuid ?: helper.activeDisplay) else BtSpaces.goto(n, cfg)
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

/** Header for a carousel: left caption, right hint, 13sp; [trailing] (the display indicator) replaces the hint. */
@Composable
fun CarouselCaption(left: String, right: String, caps: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val st = if (caps) T.caps else T.body.copy(fontSize = 13.sp, lineHeight = 18.sp)
        Text(if (caps) left.uppercase() else left, style = st, color = c.muted, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else Text(if (caps) right.uppercase() else right, style = st, color = c.muted)
    }
}

/** The bottom sheet opened from the Macros Spaces tile. */
@Composable
fun SpacesSheet(onClose: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val cfg = LocalSettings.current
    val helper = helperSpaces()
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
            CarouselCaption("With the Mote helper", "Thumbnails", caps = true, trailing = if (!helper.shared && helper.displays.size > 1) {
                { DisplayIndicator(helper) }
            } else null)
            Spacer(Modifier.height(8.dp))
            SpacesCarousel(112.dp)
            Spacer(Modifier.height(12.dp))
            val perDisplay = !helper.shared && helper.displays.size > 1
            Text((if (perDisplay) "Each display has its own desktops. The row follows the display you're using; tap the chip to pick another. " else "") +
                "The helper saves a picture of each desktop when you visit it" +
                if (perDisplay) " (needs Screen Recording permission)." else ". It needs Screen Recording permission on the Mac.",
                style = T.body.copy(fontSize = 16.sp, lineHeight = 22.sp), color = c.muted)
        } else {
            CarouselCaption("Over Bluetooth", "Numbered", caps = true)
            Spacer(Modifier.height(8.dp))
            SpacesCarousel(112.dp)
            if (!cfg.ctrlNumberShortcuts && !cfg.spacesHintDone && !later) {
                Spacer(Modifier.height(12.dp))
                SpacesHint(
                    onDone = { scope.launch { UiPrefs.update(ctx) { it.copy(spacesHintDone = true) } } },
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
