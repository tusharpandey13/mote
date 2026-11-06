package dev.mote.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.data.Macro
import dev.mote.remote.data.Step
import dev.mote.remote.net.Remote
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Key
import dev.mote.remote.hid.Mod
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.MoteIcons
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MacrosScreen(macros: List<Macro>, run: (Macro) -> Unit, save: (List<Macro>) -> Unit) {
    val c = LocalMote.current
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Macro?>(null) }
    var spacesSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Macros", style = T.headline, color = c.ink, modifier = Modifier.weight(1f))
            EditCap(if (editMode) "Done" else "Edit", active = editMode) { editMode = !editMode }
        }
        Text(
            if (editMode) "Tap a tile to edit it; tap a star to add or remove it from Media."
            else "Tap to run. Spaces opens a desktop carousel. Use Edit to change or star.",
            style = T.body, color = c.muted,
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(macros, key = { it.id }, span = { GridItemSpan(if (it.kind == Macro.Kind.SPACES) 2 else 1) }) { m ->
                Box(Modifier.height(96.dp)) {
                    when {
                        editMode -> Cap(Modifier.fillMaxSize(), RoundedCornerShape(20.dp), needsLink = false, haptic = Haptic.TICK,
                            description = "Edit ${m.name}", onClick = { editing = m }) { TileFace(m, compact = false, editing = true) }
                        m.kind == Macro.Kind.SPACES -> SpacesTile(Modifier.fillMaxSize()) { spacesSheet = true }
                        else -> MacroTile(m, Modifier.fillMaxSize(), compact = false, onRun = run)
                    }
                    run {
                        Box(Modifier.align(Alignment.TopEnd).size(40.dp).let {
                            if (editMode) it.clickable { save(macros.map { x -> if (x.id == m.id) x.copy(star = !x.star) else x }) } else it
                        }, contentAlignment = Alignment.Center) {
                            MIcon(if (m.star) "starFill" else "star", if (m.star) c.accent else c.subtle, size = 16.dp,
                                description = if (m.star) "Favourite" else "Not favourite")
                        }
                    }
                }
            }
            item(key = "+") {
                Cap(Modifier.height(96.dp), RoundedCornerShape(20.dp), needsLink = false, haptic = Haptic.TICK, description = "New macro",
                    flat = true, onClick = { editing = Macro(name = "", icon = "apps") }) {
                    Box(Modifier.fillMaxSize().well(RoundedCornerShape(20.dp), fill = false), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MIcon("plus", c.muted, size = 28.dp)
                            Text("New macro", style = T.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.muted)
                        }
                    }
                }
            }
        }
    }
    if (spacesSheet) MoteSheet(onDismiss = { spacesSheet = false }) { close -> SpacesSheet(onClose = close) }
    editing?.let { target ->
        val isNew = macros.none { it.id == target.id }
        MoteSheet(onDismiss = { editing = null }) { close ->
            MacroEditor(
                target, isNew,
                onSave = { m -> save(if (isNew) macros + m else macros.map { if (it.id == m.id) m else it }); close() },
                onDelete = if (isNew) null else ({ save(macros.filterNot { it.id == target.id }); close() }),
            )
        }
    }
}

@Composable
private fun TileFace(m: Macro, compact: Boolean, editing: Boolean = false, armed: Boolean = false) {
    val c = LocalMote.current
    if (compact) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MIcon(m.icon, if (armed) c.danger else c.ink, size = 22.dp)
            Text(if (armed) "Tap again" else m.name, style = T.small, color = if (armed) c.danger else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            MIcon(if (editing) "edit" else m.icon, if (armed) c.danger else c.ink, size = 24.dp)
            Spacer(Modifier.weight(1f))
            Text(if (armed) "Tap again" else m.name, style = T.label.copy(fontSize = 16.sp, lineHeight = 18.sp), color = if (armed) c.danger else c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.hint, style = T.body.copy(fontSize = 14.sp, lineHeight = 16.sp), color = c.muted, maxLines = 1)
        }
    }
}

/** Macro cap. Confirm-macros arm on the first tap and fire on a second tap within 2 s. */
@Composable
fun MacroTile(m: Macro, modifier: Modifier, compact: Boolean, onRun: (Macro) -> Unit) {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<Job?>(null) }
    Cap(
        modifier, RoundedCornerShape(if (compact) 16.dp else 20.dp),
        tone = if (armed) Tone.DANGER else Tone.NORMAL,
        haptic = if (m.confirm && !armed) Haptic.ARM else Haptic.CLICK,
        description = m.name,
        onClick = {
            if (m.confirm && !armed) {
                armed = true
                reset = scope.launch { delay(2000); armed = false }
            } else {
                reset?.cancel(); armed = false
                onRun(m)
            }
        },
    ) { TileFace(m, compact, armed = armed) }
}

/** Spaces tile in the Macros grid (rev 6 #60): opens the Spaces carousel in a bottom sheet. */
@Composable
fun SpacesTile(modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier, RoundedCornerShape(20.dp), needsLink = false, description = "Spaces: choose a desktop", onClick = onOpen) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            MIcon("spaces", c.ink, size = 24.dp)
            Spacer(Modifier.weight(1f))
            Text("Spaces", style = T.label.copy(fontSize = 16.sp, lineHeight = 18.sp), color = c.ink)
            Text("swipe or tap a desktop", style = T.body.copy(fontSize = 14.sp, lineHeight = 16.sp), color = c.muted, maxLines = 1)
        }
    }
}

private val specialKeys = listOf("return", "esc", "tab", "space", "backspace", "delete", "up", "down", "left", "right",
    "home", "end", "pageup", "pagedown") + (1..12).map { "f$it" }

@Composable
private fun Toggle(label: String, on: Boolean, modifier: Modifier = Modifier, tone: Tone = Tone.NORMAL, onToggle: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier.height(44.dp), RoundedCornerShape(12.dp), tone = if (on) tone else Tone.NORMAL, needsLink = false, haptic = Haptic.TICK,
        flat = !on, forcePressed = on && tone == Tone.NORMAL, description = label, onClick = onToggle) {
        Text(label, style = T.label, color = if (on) (if (tone == Tone.MODIFIER) c.onMod else c.accent) else c.muted, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = LocalMote.current
    Box(Modifier.fillMaxWidth().height(52.dp).well(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = T.body, color = c.subtle)
        BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
    }
}

/** Minimal editor: name, icon, a recorded shortcut chord, favourite and confirm options. */
@Composable
private fun MacroEditor(initial: Macro, isNew: Boolean, onSave: (Macro) -> Unit, onDelete: (() -> Unit)?) {
    val c = LocalMote.current
    val chord0 = initial.steps.singleOrNull() as? Step.Chord
    val click0 = initial.steps.singleOrNull() as? Step.ClickAt
    val chordEditable = initial.kind == Macro.Kind.STEPS && (initial.steps.isEmpty() || chord0 != null || click0 != null)
    val caps by Remote.caps.collectAsState()
    var clickAction by remember { mutableStateOf(click0 != null) } // false = shortcut chord
    var px by remember { mutableFloatStateOf(click0?.x ?: 0.5f) }
    var py by remember { mutableFloatStateOf(click0?.y ?: 0.5f) }
    var name by remember { mutableStateOf(initial.name) }
    var icon by remember { mutableStateOf(initial.icon) }
    var mods by remember { mutableIntStateOf(chord0?.mods ?: 0) }
    var key by remember { mutableStateOf(chord0?.key) }
    var star by remember { mutableStateOf(initial.star) }
    var confirm by remember { mutableStateOf(initial.confirm) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (isNew) "New macro" else "Edit macro", style = T.title, color = c.ink)
        Field(name, "Name") { name = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MoteIcons.macroChoices.forEach { ic ->
                Cap(Modifier.size(44.dp), RoundedCornerShape(12.dp), needsLink = false, haptic = Haptic.TICK, flat = ic != icon,
                    forcePressed = ic == icon, description = "Icon $ic", onClick = { icon = ic }) {
                    MIcon(ic, if (ic == icon) c.accent else c.muted, size = 22.dp)
                }
            }
        }
        if (chordEditable) {
            SectionHead("Action")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("Shortcut", !clickAction, Modifier.weight(1f)) { clickAction = false }
                // Click-at-point needs absolute positioning: only Mote Helper hosts can do it.
                Toggle("Click at point", clickAction, Modifier.weight(1f)) { if (caps.clickAt || clickAction) clickAction = true }
            }
            if (!caps.clickAt) Text("Click at point needs a Mac with Mote Helper (Wi-Fi or Tailscale). On Bluetooth hosts it is skipped.",
                style = T.small, color = c.subtle)
        }
        if (chordEditable && clickAction) {
            PointPicker(px, py) { x, y -> px = x; py = y }
            Text("Tap or drag where to click on the main display: ${(px * 100).toInt()}% across, ${(py * 100).toInt()}% down.",
                style = T.small, color = c.muted)
        } else if (chordEditable) {
            SectionHead("Shortcut")
            Box(Modifier.fillMaxWidth().height(56.dp).well(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(if (key == null) Mod.glyphs(mods) + " + key" else Mod.glyphs(mods) + Key.label(key!!), style = T.title, color = c.ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mod.ALL.forEach { bit ->
                    Toggle("${Mod.glyphs[bit]} ${Mod.names[bit]}", mods and bit != 0, Modifier.weight(1f), Tone.MODIFIER) { mods = mods xor bit }
                }
            }
            Field("", "Type a key (letter, digit, symbol)") { s ->
                s.lastOrNull()?.let(Key::forChar)?.let { (k, m) -> key = k; mods = mods or m }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                specialKeys.forEach { k -> Toggle(Key.label(k), key == k) { key = k } }
            }
        } else if (initial.kind == Macro.Kind.STEPS) {
            Text("This macro has ${initial.steps.size} steps. Only its name and options can be edited here.", style = T.body, color = c.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("★ Favourite", star, Modifier.weight(1f)) { star = !star }
            Toggle("Confirm before run", confirm, Modifier.weight(1f)) { confirm = !confirm }
        }
        val valid = name.isNotBlank() && (!chordEditable || clickAction || key != null)
        PrimaryButton(if (valid) "Save" else "Add a name and a key") {
            if (!valid) return@PrimaryButton
            val steps = when {
                !chordEditable -> initial.steps
                clickAction -> listOf(Step.ClickAt(px, py))
                else -> listOf(Step.Chord(mods, key!!))
            }
            onSave(initial.copy(name = name.trim(), icon = icon, steps = steps, star = star, confirm = confirm))
        }
        if (onDelete != null && initial.kind == Macro.Kind.STEPS) {
            Text("Delete macro", style = T.label, color = c.danger, modifier = Modifier.clickable(onClick = onDelete).padding(8.dp))
        }
        Spacer(Modifier.width(1.dp))
    }
}

/** A 16:10 stand-in for the Mac's main display; tap or drag to place the click point (normalised 0..1). */
@Composable
private fun PointPicker(x: Float, y: Float, onChange: (Float, Float) -> Unit) {
    val c = LocalMote.current
    val update by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).well(RoundedCornerShape(14.dp))
            .semantics { contentDescription = "Click point ${(x * 100).toInt()}% across, ${(y * 100).toInt()}% down" }
            .pointerInput(Unit) {
                fun set(p: Offset) = update((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                awaitEachGesture {
                    val d = awaitFirstDown(); set(d.position)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == d.id } ?: break
                        if (!ch.pressed) break
                        set(ch.position); ch.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = Offset(x * size.width, y * size.height)
            val r = 10.dp.toPx()
            drawLine(c.accent, p.copy(x = p.x - r * 2), p.copy(x = p.x + r * 2), 1.5.dp.toPx())
            drawLine(c.accent, p.copy(y = p.y - r * 2), p.copy(y = p.y + r * 2), 1.5.dp.toPx())
            drawCircle(c.accent, r, p, style = Stroke(1.5.dp.toPx()))
        }
    }
}
