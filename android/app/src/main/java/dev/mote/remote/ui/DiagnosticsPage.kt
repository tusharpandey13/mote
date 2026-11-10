package dev.mote.remote.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.mote.remote.diag.DiagRow
import dev.mote.remote.diag.DiagRows
import dev.mote.remote.diag.Events
import dev.mote.remote.diag.HelperDiag
import dev.mote.remote.diag.Perms
import dev.mote.remote.labs.Labs
import dev.mote.remote.labs.context.SmartRemote
import dev.mote.remote.net.Net
import dev.mote.remote.net.Remote
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTransport
import dev.mote.remote.ui.theme.LocalMote
import java.io.File

/** Writes the event log as JSONL and opens the share sheet. */
fun shareLog(ctx: android.content.Context) {
    Events.log("ui", "tap", "button" to "share-log")
    runCatching {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val f = File(dir, "mote-log-${System.currentTimeMillis() / 1000}.jsonl").apply { writeText(Events.toJsonl()) }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(3)?.forEach { it.delete() }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Mote log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share Mote log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Events.log("ui", "tap", "button" to "share-log", "error" to it.javaClass.simpleName) }
}

@Composable
private fun RowLine(r: DiagRow) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(r.title, style = T.label.copy(fontSize = 14.sp), color = c.ink, modifier = Modifier.weight(0.4f))
        Text(r.value, style = T.body.copy(fontSize = 14.sp, lineHeight = 19.sp), color = if (r.bad) c.danger else c.muted, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun Rows(rows: List<DiagRow>) {
    SettingsCard { rows.forEachIndexed { i, r -> if (i > 0) Hairline(); RowLine(r) } }
}

/** Settings › Diagnostics: this phone's own state next to the Mac's `diag` snapshot, live. */
@Composable
fun DiagnosticsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Events.log("ui", "screen", "screen" to "diagnostics") }
    DisposableEffect(Unit) {
        HelperDiag.open()
        onDispose { HelperDiag.close() }
    }
    val diag by HelperDiag.state.collectAsState()
    val overHelper by Remote.overHelper.collectAsState()
    val linked by Remote.linked.collectAsState()
    val ws by Net.wsStatus.collectAsState()
    val helperUp by SmartRemote.helperUp.collectAsState()
    val labs = Labs.all.map { f -> f to remember(f) { Labs.flow(ctx, f) }.collectAsState(initial = false).value }
    val npPlan by dev.mote.remote.labs.context.NpNotifier.plan.collectAsState()
    val perms = remember(diag) { Perms.state(ctx) }
    val active by Net.active.collectAsState()
    val protoHelper = diag.snapshot?.optJSONObject("helper")?.optString("protocol")?.ifEmpty { null }

    val transport = when {
        overHelper -> "Wi-Fi / Tailscale" + ((active as? WsTransport)?.let { " (${wsText(ws)})" } ?: "")
        else -> "Bluetooth" + if (linked) " (connected)" else " (not connected)"
    }
    val app = listOf(
        DiagRow("Transport", transport, bad = !linked),
        DiagRow("Helper protocol", protoHelper ?: if (overHelper) "unknown (older helper or not asked yet)" else "n/a over Bluetooth"),
        DiagRow("This app speaks", "protocol ${dev.mote.remote.net.Proto.CLIENT_PROTO}"),
    ) + labs.map { (f, on) -> DiagRow(f.title.substringBefore(" ("), if (on) "on" else "off") } + listOf(
        DiagRow("Subscriptions", listOfNotNull(
            if (labs.any { it.first == Labs.SMART_REMOTE && it.second } && helperUp) "smart remote" else null,
            if (diag.subscribed) "helper diagnostics" else null,
        ).ifEmpty { listOf("none") }.joinToString(", ")),
    ) + (dev.mote.remote.labs.context.NpNotifPolicy.describe(npPlan).let { (t, bad) -> listOf(DiagRow("Now-playing notification", t, bad)) }) + perms.map { (k, v) -> DiagRow("Permission $k", if (v) "granted" else "not granted", bad = !v && k != "notifications") }

    PageScaffold("Diagnostics", onBack) {
        Text("Why isn't this working", style = T.body, color = LocalMote.current.muted, modifier = Modifier.padding(top = 4.dp))
        Overline("This phone")
        Column(Modifier.semantics { contentDescription = "Phone state" }) { Rows(app) }
        Overline("The Mac")
        Column(Modifier.semantics { contentDescription = "Mac state" }) { Rows(DiagRows.helper(diag.status, diag.snapshot)) }
        if (diag.events.isNotEmpty()) {
            Overline("Live from the Mac")
            Rows(diag.events.takeLast(8).reversed().map { e ->
                DiagRow(e.optString("area"), e.optString("event") + (e.optString("corr").takeIf { it != "null" && it.isNotEmpty() }?.let { "  [$it]" } ?: ""))
            })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            SecondaryButton("Refresh", Modifier.weight(1f)) { Events.log("ui", "tap", "button" to "diag-refresh"); HelperDiag.refresh() }
            Spacer(Modifier.width(12.dp))
            PrimaryButton("Share log", Modifier.weight(1f)) { shareLog(ctx) }
        }
    }
}

private fun wsText(s: WsStatus): String = when (s) {
    WsStatus.Idle -> "idle"
    WsStatus.Connecting -> "connecting"
    is WsStatus.Ready -> "connected"
    is WsStatus.Failed -> "failed: ${s.code}"
}
