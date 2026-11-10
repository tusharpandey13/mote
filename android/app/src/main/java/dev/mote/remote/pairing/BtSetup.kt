package dev.mote.remote.pairing

import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Msg
import dev.mote.remote.net.Net
import dev.mote.remote.net.WsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** `bt.pair.status` from the helper (docs/pairing.md, "Bluetooth HID setup"). */
data class BtPairStatus(val state: String, val reason: String? = null, val address: String? = null) {
    val terminal get() = state == "paired" || state == "failed"

    companion object {
        fun parse(o: JSONObject): BtPairStatus? = if (o.optString("t") != "bt.pair.status") null
        else BtPairStatus(o.optString("state"), o.optString("reason").ifEmpty { null }, o.optString("address").ifEmpty { null })
    }
}

/** Plain words for each step of the Mac-driven Bluetooth pairing. */
fun btStatusText(s: BtPairStatus, mac: String): String = when (s.state) {
    "searching" -> "$mac is looking for this phone over Bluetooth…"
    "pairing" -> "Tap Pair on this phone when Android asks. Nothing to do on the Mac."
    "paired" -> "Bluetooth paired. $mac works at the login screen and as a backup."
    else -> when (s.reason) {
        "notfound" -> "$mac couldn't see this phone. Keep Bluetooth on and the phone visible, then try again."
        "timeout" -> "Bluetooth pairing took too long. Try again."
        "refused" -> "Bluetooth pairing was refused. Try again, or pair from the Mac's Bluetooth settings."
        "unavailable" -> "Bluetooth is off or not allowed for Mote Helper on $mac."
        else -> "Bluetooth pairing failed. Try again."
    }
}

/**
 * Asks the trusted helper to pair this phone over Bluetooth HID: a fresh, silent key handshake on
 * a second WebSocket, `bt.pair {name}` (the Mac finds the discoverable phone by its adapter name and
 * accepts the pairing itself), then `bt.info` for the Mac's Bluetooth address. Returns the Mac's
 * Bluetooth address, or throws [HelperError] with the failure reason.
 */
object BtSetup {
    suspend fun pair(hostId: String, phoneBtName: String, onStatus: (BtPairStatus) -> Unit): String = withContext(Dispatchers.IO) {
        val h = Net.saved.value.firstOrNull { it.hostId == hostId } ?: throw HelperError("noroute", "Unknown Mac")
        val r = InstantPairing.records.value[hostId] ?: throw HelperError("auth", "This Mac isn't trusted")
        val paths = InstantPairing.orderPaths(helperCandidates(selectPaths(r.info, h)))
        var last: Exception = HelperError("noroute", "Couldn't reach ${h.name}")
        for (addr in paths) {
            val s = runCatching { WsSession.open(Net.http, addr, h.port) }.getOrElse { last = it as? Exception ?: last; null } ?: continue
            try {
                labsHandshake(s, Identity.get(InstantPairing.appContext), InstantPairing.deviceName(), r.fp, allowPrompt = false)
                s.send(JSONObject().put("t", "bt.pair").put("name", phoneBtName).toString())
                var st: BtPairStatus? = null
                while (st?.terminal != true) {
                    val m = withTimeoutOrNull(130_000) { s.inbox.receive() } ?: throw HelperError("timeout", "timeout")
                    if (m is Msg.Err) throw HelperError(m.code, m.msg)
                    if (m !is Msg.Other) continue
                    st = BtPairStatus.parse(JSONObject(m.raw)) ?: continue
                    onStatus(st)
                }
                if (st.state != "paired") throw HelperError(st.reason ?: "refused", st.reason ?: "refused")
                s.send(JSONObject().put("t", "bt.info").toString())
                val info = withTimeoutOrNull(5000) {
                    var o: JSONObject? = null
                    while (o == null) { val m = s.inbox.receive(); if (m is Msg.Other) o = JSONObject(m.raw).takeIf { it.optString("t") == "bt.info" } }
                    o
                }
                return@withContext info?.optString("address")?.ifEmpty { null }?.takeIf { it != "null" }
                    ?: throw HelperError("unavailable", "The Mac didn't report its Bluetooth address")
            } catch (e: HelperError) { throw e } catch (e: Exception) { last = e } finally { s.close() }
        }
        throw last
    }
}
