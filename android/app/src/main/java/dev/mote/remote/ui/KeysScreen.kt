package dev.mote.remote.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Key
import dev.mote.remote.hid.Mod
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.launch

/** One key: [key] is a HID key name; [mods] are extra modifiers (e.g. shift for symbols). */
private data class K(val label: String, val key: String, val mods: Int = 0, val w: Float = 1f, val sub: String? = null, val icon: String? = null)

private fun ch(c: Char, sub: String? = null): K {
    val (k, m) = Key.forChar(c) ?: error("untypeable $c")
    return K(c.uppercase(), k, m, sub = sub)
}

private val digits = "1234567890".zip("!@#$%^&*()").map { (d, s) -> ch(d, s.toString()) }
private val retKey = K("return", "return", w = 1.5f, icon = "ret")
private val bsKey = K("delete", "backspace", w = 1.5f, icon = "backspace")
private val lettersRows = listOf("qwertyuiop".map { ch(it) }, "asdfghjkl".map { ch(it) } + retKey, "zxcvbnm".map { ch(it) } + bsKey)
private val symbolRows = listOf("-=[]\\;'`~_".map { ch(it) }, "+{}|:\"<>?".map { ch(it) } + retKey, "!@#$%^&".map { ch(it) } + bsKey)

@Composable
fun KeysScreen() {
    val c = LocalMote.current
    var showFn by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        TypeRow()
        SectionHead("Function and navigation", Modifier.padding(horizontal = 12.dp)) {
            Text(if (showFn) "HIDE" else "SHOW", style = T.caps, color = c.subtle, modifier = Modifier.clickable { showFn = !showFn }.padding(4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showFn) {
                KeyRow(listOf(K("esc", "esc")) + (1..6).map { K("F$it", "f$it") }, 46.dp, 10.dp)
                KeyRow((7..12).map { K("F$it", "f$it") } + K("del", "delete"), 46.dp, 10.dp)
                val gap = K("", "")
                KeyRow(listOf(K("home", "home"), K("pg up", "pageup"), gap.copy(w = 0.3f), gap, K("↑", "up"), gap), 46.dp, 10.dp)
                KeyRow(listOf(K("end", "end"), K("pg dn", "pagedown"), gap.copy(w = 0.3f), K("←", "left"), K("↓", "down"), K("→", "right")), 46.dp, 10.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        ChordStrip()
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().well(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyRow(digits)
            (if (symbols) symbolRows else lettersRows).forEachIndexed { i, row ->
                if (i == 2) {
                    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModKey(Mod.SHIFT, "⇧", "shift", Modifier.weight(1.5f))
                        row.forEach { KeyCap(it, Modifier.weight(it.w)) }
                    }
                } else KeyRow(row)
            }
            Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Cap(Modifier.weight(1.5f).fillMaxHeight(), RoundedCornerShape(8.dp), needsLink = false, haptic = Haptic.TICK,
                    description = if (symbols) "Letters" else "Symbols", onClick = { symbols = !symbols }) {
                    Text(if (symbols) "ABC" else "#+=", style = T.keyS, color = c.muted)
                }
                KeyCap(K("tab", "tab"), Modifier.weight(1f))
                KeyCap(ch(','), Modifier.weight(1f))
                KeyCap(K("", "space", w = 5f), Modifier.weight(5f))
                KeyCap(ch('.'), Modifier.weight(1f))
                KeyCap(ch('/', "?"), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("fn", style = T.keyS, color = c.subtle)
                        Text("not sendable", style = T.caps.copy(letterSpacing = T.caps.letterSpacing * 0), color = c.subtle)
                    }
                }
                ModKey(Mod.CTRL, "⌃", "control", Modifier.weight(1f))
                ModKey(Mod.ALT, "⌥", "option", Modifier.weight(1f))
                ModKey(Mod.CMD, "⌘", "command", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TypeRow() {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        val t = text
        if (t.isNotEmpty()) {
            text = ""
            scope.launch {
                val skipped = Hid.typeText(t)
                if (skipped > 0) Toast.makeText(ctx, "$skipped characters skipped (US layout only)", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().well(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Type text to send…", style = T.body, color = c.subtle)
            BasicTextField(
                text, { text = it }, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Text to send" },
            )
        }
        Cap(Modifier.width(72.dp).fillMaxHeight(), RoundedCornerShape(12.dp), description = "Send text", onClick = send) {
            Text("Send", style = T.label, color = c.ink)
        }
    }
}

@Composable
private fun KeyRow(keys: List<K>, height: androidx.compose.ui.unit.Dp = 46.dp, radius: androidx.compose.ui.unit.Dp = 8.dp) {
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEach { k -> if (k.key.isEmpty()) Spacer(Modifier.weight(k.w)) else KeyCap(k, Modifier.weight(k.w), radius) }
    }
}

/** Plain keys go down/up with the finger (the host does key repeat); shifted symbols are tapped. */
@Composable
private fun KeyCap(k: K, modifier: Modifier, radius: androidx.compose.ui.unit.Dp = 8.dp) {
    val c = LocalMote.current
    Cap(
        modifier.fillMaxHeight(), RoundedCornerShape(radius), haptic = Haptic.TICK,
        description = k.label.ifEmpty { k.key },
        onDown = { if (k.mods == 0) Hid.keyDown(k.key) else Hid.tap(k.key, k.mods) },
        onRelease = { if (k.mods == 0) Hid.keyUp(k.key) },
    ) {
        when {
            k.icon != null -> MIcon(k.icon, c.ink, size = 22.dp)
            k.sub != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(k.sub, style = T.caps, color = c.muted)
                Text(k.label, style = T.keyS, color = c.ink)
            }
            else -> Text(k.label, style = if (k.label.length > 1) T.keyS else T.key, color = c.ink, maxLines = 1)
        }
    }
}

/** Sticky modifier: tap = armed (one-shot), double-tap or hold = locked, tap again = clear. */
@Composable
private fun ModKey(bit: Int, glyph: String, name: String, modifier: Modifier) {
    val c = LocalMote.current
    val h = haptics()
    val mods by Hid.mods.collectAsState()
    var lastTap by remember { mutableLongStateOf(0L) }
    val armed = mods.armed and bit != 0
    val locked = mods.locked and bit != 0
    val shape = RoundedCornerShape(8.dp)
    // state transitions: yellow fill and ring fade in on the spatial-fast spring
    val lit by animateFloatAsState(if (armed || locked) 1f else 0f, Motion.spatialFast(), label = "lit")
    val ring by animateFloatAsState(if (locked) 1f else 0f, Motion.spatialFast(), label = "ring")
    fun set(a: Boolean, l: Boolean) = Hid.setMods(
        Hid.Mods(
            armed = if (a) mods.armed or bit else mods.armed and bit.inv(),
            locked = if (l) mods.locked or bit else mods.locked and bit.inv(),
        ),
    )
    Box(
        modifier.fillMaxHeight().border(2.dp, c.modBot.copy(alpha = ring), shape).padding(3.dp * ring),
    ) {
        Cap(
            Modifier.fillMaxSize(), shape,
            fill = lit, forcePressed = locked, haptic = null,
            description = "$name, " + when { locked -> "locked"; armed -> "armed"; else -> "off" },
            onLongPress = { if (!locked) { set(false, true); h(Haptic.LOCK) } },
            onClick = {
                val now = SystemClock.uptimeMillis()
                when {
                    locked -> { set(false, false); h(Haptic.RELEASE) }
                    armed && now - lastTap < 300 -> { set(false, true); h(Haptic.LOCK) }
                    armed -> { set(false, false); h(Haptic.RELEASE) }
                    else -> { set(true, false); h(Haptic.ARM) }
                }
                lastTap = now
            },
        ) {
            val ink = lerp(c.ink, c.onMod, lit)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(glyph, style = T.key, color = ink)
                Text(name, style = T.caps.copy(letterSpacing = T.caps.letterSpacing * 0), color = lerp(c.muted, c.onMod, lit))
            }
            if (locked) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).well(RoundedCornerShape(50)))
        }
    }
}

/** Shows the armed/locked modifiers that will apply to the next key, with Clear. */
@Composable
private fun ChordStrip() {
    val c = LocalMote.current
    val h = haptics()
    val mods by Hid.mods.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp).well(RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Mod.ALL.filter { mods.active and it != 0 }.forEach { bit ->
            val locked = mods.locked and bit != 0
            Box(
                Modifier.size(28.dp).then(if (locked) Modifier.border(1.5.dp, c.modBot, RoundedCornerShape(9.dp)).padding(2.5.dp) else Modifier)
                    .raised(RoundedCornerShape(7.dp), tone = Tone.MODIFIER),
                contentAlignment = Alignment.Center,
            ) { Text(Mod.glyphs.getValue(bit), style = T.keyS, color = c.onMod) }
        }
        Text(if (mods.active == 0) "Modifiers: tap once to arm, twice to lock" else "+ next key", style = T.small, color = c.muted, modifier = Modifier.weight(1f))
        if (mods.active != 0) {
            Cap(Modifier.height(32.dp).width(64.dp), RoundedCornerShape(10.dp), needsLink = false, haptic = null, description = "Clear modifiers",
                onClick = { Hid.setMods(Hid.Mods()); h(Haptic.RELEASE) }) { Text("Clear", style = T.small, color = c.muted) }
        }
    }
}
