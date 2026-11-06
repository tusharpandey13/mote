package dev.mote.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.lerp
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
import dev.mote.remote.data.Macro
import dev.mote.remote.data.Step
import dev.mote.remote.net.Remote
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
import kotlin.math.abs

@Composable
fun MacrosScreen(macros: List<Macro>, run: (Macro) -> Unit, save: (List<Macro>) -> Unit) {
    val c = LocalMote.current
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Macro?>(null) }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        SectionHead("Macros") {
            Text(if (editMode) "DONE" else "EDIT", style = T.caps, color = if (editMode) c.accent else c.subtle,
                modifier = Modifier.clickable { editMode = !editMode }.padding(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(macros, key = { it.id }, span = { GridItemSpan(if (it.kind == Macro.Kind.SPACES) 2 else 1) }) { m ->
                Box(Modifier.height(88.dp)) {
                    when {
                        editMode -> Cap(Modifier.fillMaxSize(), RoundedCornerShape(20.dp), needsLink = false, haptic = Haptic.TICK,
                            description = "Edit ${m.name}", onClick = { editing = m }) { TileFace(m, compact = false, editing = true) }
                        m.kind == Macro.Kind.SPACES -> SpacesTile(Modifier.fillMaxSize(), compact = false)
                        else -> MacroTile(m, Modifier.fillMaxSize(), compact = false, onRun = run)
                    }
                    if (m.star || editMode) {
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
                Cap(Modifier.height(88.dp), RoundedCornerShape(20.dp), needsLink = false, haptic = Haptic.TICK, description = "New macro",
                    onClick = { editing = Macro(name = "", icon = "apps") }) {
                    MIcon("plus", c.muted, size = 28.dp)
                }
            }
        }
    }
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
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            MIcon(if (editing) "edit" else m.icon, if (armed) c.danger else c.ink, size = 24.dp)
            Spacer(Modifier.weight(1f))
            Text(if (armed) "Tap again" else m.name, style = T.label, color = if (armed) c.danger else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.hint, style = T.keyS, color = c.muted, maxLines = 1)
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

/** Spaces gesture tile: hold and slide sends ⌃← / ⌃→ (one step per 32dp), tap sends ⌃↑. */
@Composable
fun SpacesTile(modifier: Modifier, compact: Boolean) {
    val c = LocalMote.current
    val h = haptics()
    val linked by rememberUpdatedState(LocalLinked.current)
    var pressed by remember { mutableStateOf(false) }
    var dir by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) } // content follows the finger a little, springs back
    val dirTint by animateFloatAsState(if (dir != 0) 1f else 0f, Motion.effectFast(), label = "dir")
    val step = with(LocalDensity.current) { 32.dp.toPx() }
    val slop = with(LocalDensity.current) { 8.dp.toPx() }
    Box(
        modifier
            .raised(RoundedCornerShape(if (compact) 16.dp else 20.dp), pressed)
            .semantics { contentDescription = "Spaces: slide to switch, tap for Mission Control" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!linked) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                    pressed = true; h(Haptic.CLICK)
                    var anchor = down.position.x
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (abs(ch.position.x - down.position.x) > slop) moved = true
                        val dx = ch.position.x - anchor
                        if (abs(dx) >= step) {
                            val right = dx > 0
                            Remote.tap(if (right) "right" else "left", Mod.CTRL)
                            h(Haptic.TICK); dir = if (right) 1 else -1
                            anchor += if (right) step else -step
                        }
                        val follow = ((ch.position.x - anchor) * 0.35f).coerceIn(-step * 0.35f, step * 0.35f)
                        scope.launch { nudge.snapTo(follow) }
                        ch.consume()
                    }
                    if (!moved) Remote.tap("up", Mod.CTRL)
                    pressed = false; dir = 0
                    scope.launch { nudge.animateTo(0f, Motion.spatialFast()) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).offset { IntOffset(nudge.value.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MIcon("chevLeft", if (dir < 0) lerp(c.muted, c.accent, dirTint) else c.muted, size = 20.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon("spaces", c.ink, size = if (compact) 20.dp else 24.dp)
                Text("Spaces", style = T.small, color = c.ink)
                Text("hold & slide", style = T.caps, color = c.subtle)
            }
            MIcon("chevRight", if (dir > 0) lerp(c.muted, c.accent, dirTint) else c.muted, size = 20.dp)
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
        BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

/** Minimal editor: name, icon, a recorded shortcut chord, favourite and confirm options. */
@Composable
private fun MacroEditor(initial: Macro, isNew: Boolean, onSave: (Macro) -> Unit, onDelete: (() -> Unit)?) {
    val c = LocalMote.current
    val chord0 = initial.steps.singleOrNull() as? Step.Chord
    val chordEditable = initial.kind == Macro.Kind.STEPS && (initial.steps.isEmpty() || chord0 != null)
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
        val valid = name.isNotBlank() && (!chordEditable || key != null)
        PrimaryButton(if (valid) "Save" else "Add a name and a key") {
            if (!valid) return@PrimaryButton
            val steps = if (chordEditable) listOf(Step.Chord(mods, key!!)) else initial.steps
            onSave(initial.copy(name = name.trim(), icon = icon, steps = steps, star = star, confirm = confirm))
        }
        if (onDelete != null && initial.kind == Macro.Kind.STEPS) {
            Text("Delete macro", style = T.label, color = c.danger, modifier = Modifier.clickable(onClick = onDelete).padding(8.dp))
        }
        Spacer(Modifier.width(1.dp))
    }
}
