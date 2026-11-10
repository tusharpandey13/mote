package dev.mote.remote.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mote.remote.diag.Events
import dev.mote.remote.ui.PrimaryButton
import dev.mote.remote.ui.SecondaryButton
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * U12: a Mac we trust now presents another helper key (reinstall, new Mac with the same name, or an impostor).
 * The phone never trusts it silently: it asks "This Mac has a new key. Trust it?" and, on yes, re-pairs through
 * [InstantPairing.trustNewKey], which needs the BLE proximity check again (the Mac accepts a new key only when this
 * phone is next to it, or on the same Tailscale account).
 *
 * Today the trigger is the existing key-mismatch error ([InstantPairing] `reconnect`: `helper-key` "This Mac's key
 * changed"). HOOK for the helper's explicit message (protocol.md, branch fix-vault): call [onHelperNewKey] from the
 * Labs/WS message handler when that message arrives; nothing else in the UI changes.
 */
object HostKeyChange {
    data class Pending(val hostId: String, val name: String, val ephemeral: Boolean = false)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Message the key-mismatch error carries (Handshake.kt); the hook matches on it until the helper has its own message. */
    const val MISMATCH = "This Mac's key changed"

    /** Shown when the Mac is in ephemeral mode (its Keychain is unreadable): per-launch key, approve on the Mac, nothing stored. */
    const val EPHEMERAL_TEXT = "This Mac can't save pairings right now (Keychain unavailable). Approve this phone on the Mac. Your existing pairing is kept and works again once the Mac's Keychain is repaired."

    /** Classic token `auth` error code of an ephemeral helper: keep the stored token, show [EPHEMERAL_TEXT], never delete anything. */
    const val KEYCHAIN_UNAVAILABLE = "keychain-unavailable"

    fun isKeyMismatch(code: String, msg: String?) = code == "helper-key" && msg == MISMATCH

    fun report(hostId: String, name: String) {
        if (_pending.value?.hostId == hostId) return
        _pending.value = Pending(hostId, name)
        Events.log("ui", "banner", "kind" to "new-key", "host" to name)
    }

    fun reportEphemeral(hostId: String, name: String) {
        if (_pending.value?.hostId == hostId) return
        _pending.value = Pending(hostId, name, ephemeral = true)
        Events.log("ui", "banner", "kind" to "mac-ephemeral", "host" to name)
    }

    /** HOOK: the helper told us (or the handshake showed) that [hostId] has a new key. */
    fun onHelperNewKey(hostId: String, name: String) = report(hostId, name)

    fun dismiss() { _pending.value = null }
}

/** The "Trust it?" card, shown under the header on the remote screen. */
@Composable
fun KeyChangeCard() {
    val c = LocalMote.current
    val p by HostKeyChange.pending.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = p ?: return
    LaunchedEffect(pending.hostId) { busy = false; error = null }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).well(RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (pending.ephemeral) "This Mac can't save pairings" else "This Mac has a new key. Trust it?", style = T.label, color = c.ink)
        Text(
            if (pending.ephemeral) HostKeyChange.EPHEMERAL_TEXT else error ?: "${pending.name} is using a different Mote Helper key than the one this phone trusted. Only trust it if you reinstalled Mote Helper or set the Mac up again. Keep the phone next to the Mac: Mote checks that over Bluetooth.",
            style = T.small, color = if (error != null) c.linkBad else c.muted,
        )
        if (pending.ephemeral) { SecondaryButton("OK") { HostKeyChange.dismiss() }; return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (busy) "Checking…" else "Trust", Modifier.weight(1f)) {
                if (busy) return@PrimaryButton
                busy = true; error = null
                scope.launch {
                    try {
                        InstantPairing.trustNewKey(pending.hostId)
                        Events.log("ui", "tap", "target" to "new-key-trust", "ok" to true)
                        HostKeyChange.dismiss()
                    } catch (e: Exception) {
                        Events.log("ui", "tap", "target" to "new-key-trust", "ok" to false)
                        error = pairFailure(e, pending.name)
                    } finally { busy = false }
                }
            }
            SecondaryButton("Not now", Modifier.weight(1f)) { Events.log("ui", "tap", "target" to "new-key-dismiss"); HostKeyChange.dismiss() }
        }
    }
}
