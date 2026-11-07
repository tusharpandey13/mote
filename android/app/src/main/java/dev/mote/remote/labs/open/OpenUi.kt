package dev.mote.remote.labs.open

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.hid.Consumer
import dev.mote.remote.labs.Labs
import dev.mote.remote.net.Caps
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.Cap
import dev.mote.remote.ui.EditCap
import dev.mote.remote.ui.GlassIcon
import dev.mote.remote.ui.Haptic
import dev.mote.remote.ui.MoteSheet
import dev.mote.remote.ui.Repeat
import dev.mote.remote.ui.SectionHead
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.launch

/** What App.kt needs from the Open & TV preview: the flag, whether TV mode is up, the sheet. */
@Stable
class OpenTvHandle {
    var enabled by mutableStateOf(false)
    var tvOn by mutableStateOf(false)
    var tvUrl by mutableStateOf<String?>(null)
    var sheet by mutableStateOf(false)
}

/** Collects the Labs flag and the helper's TV state; keeps the share target in step with the flag. */
@Composable
fun rememberOpenTv(): OpenTvHandle {
    val ctx = LocalContext.current
    val h = remember { OpenTvHandle() }
    val on by remember { Labs.flow(ctx, Labs.OPEN_TV) }.collectAsState(initial = false)
    val tv by OpenTv.state.collectAsState()
    val linked by Remote.linked.collectAsState()
    val share by OpenTv.pendingShare.collectAsState()
    LaunchedEffect(Unit) { OpenTv.start() }
    LaunchedEffect(on) { OpenTv.syncShareTarget(ctx, on) }
    LaunchedEffect(on, linked) { if (on && linked) OpenTv.refresh() }
    LaunchedEffect(on, share) { if (on && share != null) h.sheet = true }
    h.enabled = on
    h.tvOn = on && tv?.on == true
    h.tvUrl = tv?.url
    return h
}

/** Top-bar entry point ("Open…"); nothing when the flag is off. */
@Composable
fun OpenEntry(h: OpenTvHandle) {
    if (!h.enabled) return
    val c = LocalMote.current
    Box(Modifier.size(44.dp).clickable(onClickLabel = "Open on Mac") { h.sheet = true }, contentAlignment = Alignment.Center) {
        MIcon("search", if (c.dark) c.subtle else c.muted, size = 20.dp, description = "Open on Mac")
    }
}

/** The Open… bottom sheet, when requested. */
@Composable
fun OpenTvSheet(h: OpenTvHandle) {
    if (!h.enabled || !h.sheet) return
    MoteSheet(onDismiss = { h.sheet = false; OpenTv.pendingShare.value = null }) { close -> OpenSheet(close) }
}

@Composable
private fun PreviewBadge() {
    val c = LocalMote.current
    Text(
        "PREVIEW", style = T.caps.copy(fontSize = 10.sp), color = c.accent,
        modifier = Modifier.border(1.dp, c.accent, RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun clipboardText(c: Context): String? =
    runCatching { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() }.getOrNull()

@Composable
private fun OpenSheet(close: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins by remember { OpenStore.pins(ctx) }.collectAsState(initial = Sites.defaultPins)
    val recents by remember { OpenStore.recents(ctx) }.collectAsState(initial = emptyList())
    val err by OpenTv.error.collectAsState()
    val caps by Remote.caps.collectAsState()
    val helper = caps == Caps.HELPER
    var text by remember { mutableStateOf(OpenTv.pendingShare.value ?: "") }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { OpenTv.pendingShare.value = null; OpenTv.error.value = null }

    fun openLink(title: String, url: String) {
        val mode = OpenUrl.modeFor(url)
        if (OpenTv.send(OpenTvProto.openUrl(url, mode, fullscreen = mode == "browser"))) {
            scope.launch { OpenStore.addRecent(ctx, Site(title, url)) }
            close()
        }
    }
    fun submit() {
        when (val i = OpenUrl.classify(text)) {
            is OpenUrl.Input.Link -> openLink(OpenUrl.host(i.url), i.url)
            is OpenUrl.Input.Search -> {
                val url = OpenUrl.youTubeSearch(i.query)
                if (OpenTv.send(OpenTvProto.openUrl(url, "tv"))) {
                    scope.launch { OpenStore.addRecent(ctx, Site("YouTube: ${i.query}", url)) }
                    close()
                }
            }
            null -> if (text.isNotBlank()) OpenTv.error.value = "Only http and https links can be opened."
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open on Mac", style = T.headline, color = c.ink)
            Spacer(Modifier.width(10.dp))
            PreviewBadge()
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp).clickable(onClickLabel = "Close", onClick = close), contentAlignment = Alignment.Center) {
                MIcon("close", c.ink, description = "Close")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(52.dp).well(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Search YouTube or paste a link", style = T.body, color = c.subtle, maxLines = 1)
                // Text keyboard (not Uri) so the keyboard's mic is offered for voice search.
                BasicTextField(
                    text, { text = it.take(OpenUrl.MAX) }, singleLine = true, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search YouTube or paste a link" },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (text.isBlank()) {
                Cap(Modifier.height(52.dp), RoundedCornerShape(14.dp), needsLink = false, haptic = Haptic.TICK, description = "Paste",
                    onClick = { clipboardText(ctx)?.let { s -> text = (OpenUrl.fromShare(s) ?: s).take(OpenUrl.MAX) } }) {
                    Text("Paste", style = T.label, color = c.ink, modifier = Modifier.padding(horizontal = 14.dp))
                }
            } else {
                Cap(Modifier.height(52.dp), RoundedCornerShape(14.dp), tone = Tone.PRIMARY, description = "Open", onClick = { submit() }) {
                    Text("Open", style = T.label, color = c.onPrim, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        Text(
            if (helper) "Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak."
            else "Opening links needs Mote Helper over Wi-Fi or Tailscale. Quick actions work over Bluetooth too.",
            style = T.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        err?.let { Text(it, style = T.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }

        Spacer(Modifier.height(18.dp))
        SectionHead("Quick actions")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickCap("close", "Close this tab", Modifier.weight(1f)) { OpenTv.quick("closeTab") }
            QuickCap("escape", "Exit fullscreen", Modifier.weight(1f)) { OpenTv.quick("exitFullscreen") }
            QuickCap("playPause", "Pause everything", Modifier.weight(1f)) { OpenTv.quick("pauseAll") }
        }
        if (helper) {
            Spacer(Modifier.height(8.dp))
            Cap(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(16.dp), description = "Open TV mode",
                onClick = { if (OpenTv.send(OpenTvProto.tvOpen(pins))) close() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon("desktop", c.ink, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Open TV mode on the Mac", style = T.label, color = c.ink)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionHead("Pinned") { EditCap(if (editing) "Done" else "Edit", active = editing, compact = true) { editing = !editing } }
        Spacer(Modifier.height(8.dp))
        val cells = pins.map { it as Site? } + if (editing && pins.size < Sites.PINS_MAX) listOf(null) else emptyList()
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    if (s == null) {
                        val link = OpenUrl.normalize(text)
                        Cap(Modifier.weight(1f).height(72.dp), RoundedCornerShape(16.dp), needsLink = false, flat = true,
                            description = "Pin the link in the field", onClick = {
                                if (link == null) OpenTv.error.value = "Type or paste a link above, then tap + to pin it."
                                else scope.launch { OpenStore.editPins(ctx) { Sites.addPin(it, Site(OpenUrl.host(link), link)) }; text = "" }
                            }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MIcon("plus", c.muted, size = 20.dp)
                                Text(if (link == null) "Pin a link" else "Pin ${OpenUrl.host(link)}", style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Cap(Modifier.weight(1f).height(72.dp), RoundedCornerShape(16.dp), needsLink = !editing,
                            description = if (editing) "Remove ${s.title}" else "Open ${s.title}",
                            onClick = { if (editing) scope.launch { OpenStore.editPins(ctx) { Sites.removePin(it, s.url) } } else openLink(s.title, s.url) }) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalAlignment = Alignment.Start) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.title, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (editing) MIcon("close", c.danger, size = 16.dp)
                                }
                                Text(OpenUrl.host(s.url), style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionHead("Recent") {
                Text("Clear", style = T.label, color = c.accent, modifier = Modifier.clickable(onClickLabel = "Clear recents") {
                    scope.launch { OpenStore.clearRecents(ctx) }
                }.padding(8.dp))
            }
            recents.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Open ${r.title}") { openLink(r.title, r.url) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MIcon(if (OpenUrl.isYouTube(r.url)) "playPause" else "laptop", c.muted, size = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(OpenUrl.host(r.url), style = T.small, color = c.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCap(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier.height(76.dp), RoundedCornerShape(16.dp), description = label, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MIcon(icon, c.ink, size = 22.dp)
            Spacer(Modifier.height(6.dp))
            Text(label, style = T.small, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Media while the helper's TV mode is up: big D-pad, back, home, play/pause, search, volume. */
@Composable
fun TvRemotePanel(h: OpenTvHandle) {
    val c = LocalMote.current
    fun key(k: String) { OpenTv.send(OpenTvProto.tvKey(k)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV MODE", style = T.caps, color = c.subtle)
            Spacer(Modifier.width(8.dp))
            PreviewBadge()
            Spacer(Modifier.weight(1f))
            EditCap("Exit", compact = true) { OpenTv.send(OpenTvProto.tvExit()) }
        }
        Text(
            h.tvUrl?.let { if (OpenUrl.isYouTube(it)) "YouTube" else OpenUrl.host(it) } ?: "Home",
            style = T.body, color = c.muted, modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
        )
        val err by OpenTv.error.collectAsState()
        err?.let { Text(it, style = T.body.copy(fontSize = 13.sp), color = c.danger, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.weight(1f))
        // Big D-pad: four arms around a primary OK cap, in one round well.
        Box(Modifier.size(272.dp).well(CircleShape, bezel = true).semantics { contentDescription = "TV direction pad" }) {
            val arm = Modifier.size(84.dp)
            val r = Repeat(delayMs = 400, slowMs = 160, fastMs = 90)
            Cap(arm.align(Alignment.TopCenter).padding(top = 8.dp), RoundedCornerShape(24.dp), haptic = Haptic.TICK, description = "Up", repeat = r, onDown = { key("up") }) { MIcon("chevUp", c.ink, size = 30.dp) }
            Cap(arm.align(Alignment.BottomCenter).padding(bottom = 8.dp), RoundedCornerShape(24.dp), haptic = Haptic.TICK, description = "Down", repeat = r, onDown = { key("down") }) { MIcon("chevDown", c.ink, size = 30.dp) }
            Cap(arm.align(Alignment.CenterStart).padding(start = 8.dp), RoundedCornerShape(24.dp), haptic = Haptic.TICK, description = "Left", repeat = r, onDown = { key("left") }) { MIcon("chevLeft", c.ink, size = 30.dp) }
            Cap(arm.align(Alignment.CenterEnd).padding(end = 8.dp), RoundedCornerShape(24.dp), haptic = Haptic.TICK, description = "Right", repeat = r, onDown = { key("right") }) { MIcon("chevRight", c.ink, size = 30.dp) }
            Cap(Modifier.size(92.dp).align(Alignment.Center), CircleShape, tone = Tone.PRIMARY, description = "OK", onClick = { key("ok") }) {
                Text("OK", style = T.title, color = c.onPrim)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvCap("chevLeft", "Back", Modifier.weight(1f)) { key("back") }
            TvCap("apps", "Home", Modifier.weight(1f)) { key("home") }
            // Search opens the Open… sheet: phone keyboard and voice beat the on-screen TV keyboard.
            TvCap("search", "Search", Modifier.weight(1f)) { h.sheet = true }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().well(RoundedCornerShape(30.dp), bezel = true).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Cap(Modifier.width(76.dp).height(88.dp), RoundedCornerShape(22.dp), haptic = Haptic.TICK, description = "Volume down", repeat = Repeat(),
                onDown = { Remote.consumer(Consumer.VOL_DOWN) }) { MIcon("volDown", c.ink, size = 26.dp) }
            Cap(Modifier.weight(1f).height(88.dp), RoundedCornerShape(24.dp), tone = Tone.GLASS, description = "Play or pause",
                onClick = { key("playpause") }) { GlassIcon("playPause", 32.dp) }
            Cap(Modifier.width(76.dp).height(88.dp), RoundedCornerShape(22.dp), haptic = Haptic.TICK, description = "Volume up", repeat = Repeat(),
                onDown = { Remote.consumer(Consumer.VOL_UP) }) { MIcon("volUp", c.ink, size = 26.dp) }
        }
    }
}

@Composable
private fun TvCap(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalMote.current
    Cap(modifier.height(64.dp), RoundedCornerShape(18.dp), description = label, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(icon, c.ink, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = T.label, color = c.ink)
        }
    }
}
