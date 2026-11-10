package dev.mote.remote.diag

import org.json.JSONObject

/** One line of the Diagnostics screen. [bad] = something is blocked or missing. */
data class DiagRow(val title: String, val value: String, val bad: Boolean = false)

/** Pure: turns the helper's `diag` snapshot (docs/protocol.md 2.1) into screen rows. Unknown keys are ignored. */
object DiagRows {
    const val UPDATE_HELPER = "Update the helper to see diagnostics"

    /** Words for `blockedBy` codes. */
    fun blockedBy(code: String?): String = when (code) {
        "accessibility-not-granted" -> "Accessibility is not granted on the Mac"
        "bluetooth-not-granted", "bluetooth-denied" -> "Bluetooth is not allowed for Mote Helper"
        "bluetooth-off" -> "Bluetooth is off on the Mac"
        "local-network-denied" -> "Local Network access is denied"
        "screen-recording-not-granted" -> "Screen Recording is not granted"
        "automation-not-granted" -> "Browser control (Automation) is not granted"
        "input-paused" -> "Input is paused"
        "keychain-unreadable", "keychain-corrupt" -> "The Mac's keychain item cannot be read"
        "listener-waiting", "listener-failed" -> "The helper is not listening on its port"
        "bonjour-failed" -> "Bonjour registration failed"
        null -> "blocked"
        else -> code
    }

    fun helper(status: HelperDiag.Status, snap: JSONObject?): List<DiagRow> = when {
        status == HelperDiag.Status.UNSUPPORTED -> listOf(DiagRow("Mote Helper", UPDATE_HELPER, bad = true))
        status == HelperDiag.Status.NO_LINK -> listOf(DiagRow("Mote Helper", "Not connected over Wi-Fi or Tailscale, so the Mac cannot be asked", bad = true))
        snap == null -> listOf(DiagRow("Mote Helper", if (status == HelperDiag.Status.LOADING) "Asking the Mac…" else "No answer yet"))
        else -> rows(snap)
    }

    private fun rows(s: JSONObject): List<DiagRow> {
        val out = ArrayList<DiagRow>()
        val h = s.optJSONObject("helper")
        if (h != null) {
            out += DiagRow("Version", "${h.optString("version")} (protocol ${h.optString("protocol")}, ${h.optString("arch")})")
            if (h.optBoolean("dryRun")) out += DiagRow("Mode", "dry run (no input is injected)")
        }
        s.optJSONObject("features")?.let { f ->
            for (k in f.keys().asSequence().sorted()) {
                val o = f.optJSONObject(k) ?: continue
                val state = o.optString("state")
                val bad = state == "blocked"
                val text = when (state) {
                    "blocked" -> blockedBy(o.optString("blockedBy").ifEmpty { null })
                    "off" -> o.optString("detail").ifEmpty { "off" }
                    else -> state
                }
                out += DiagRow(k, text, bad)
            }
        }
        s.optJSONObject("permissions")?.let { p ->
            for (k in p.keys().asSequence().sorted()) {
                val o = p.optJSONObject(k) ?: continue
                val st = o.optString("state")
                // "unknown" means macOS does not say; never suggest a fix for it
                out += DiagRow("Permission $k", if (st == "unknown") "can't check: macOS doesn't report this" else st, st == "blocked")
            }
        }
        s.optJSONArray("sessions")?.let { a ->
            out += DiagRow("Sessions", "${a.length()} connected")
        }
        return out
    }
}
