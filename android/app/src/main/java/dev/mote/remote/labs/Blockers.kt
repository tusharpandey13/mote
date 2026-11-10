package dev.mote.remote.labs

import dev.mote.remote.diag.Events
import dev.mote.remote.labs.context.MacContext
import dev.mote.remote.labs.context.NpState

/** Why a Labs panel cannot work right now: [code] is stable (events, tests), [text] is shown to the user. */
data class Blocker(val code: String, val text: String)

/**
 * Pure rules for "this preview shows nothing / does nothing because ...". Every Labs panel and entry
 * point asks here, so a preview is never silently empty (bug B3: Labs on both sides, YouTube playing,
 * nothing changed on the phone).
 */
object Blockers {
    /** What the app knows about the link to the Mac. */
    data class Link(val overHelper: Boolean, val linked: Boolean, val helperProtoTooOld: Boolean = false)

    /** Common to every lab that talks to the helper. */
    fun link(l: Link): Blocker? = when {
        !l.overHelper && l.linked -> Blocker("bluetooth-only", "Needs Wi-Fi or Tailscale; connected over Bluetooth")
        !l.overHelper -> Blocker("no-helper", "Needs Wi-Fi or Tailscale; not connected to a Mac")
        !l.linked -> Blocker("offline", "Not connected to the Mac right now")
        l.helperProtoTooOld -> Blocker("helper-too-old", "Helper too old for this preview: update Mote Helper on the Mac")
        else -> null
    }

    /** The helper-side switch of a lab is off (`err unsupported` / a disabled `labs.*` state). */
    fun helperSwitchOff(lab: String) = Blocker("helper-lab-off", "Turn on $lab in the Mote Helper menu on the Mac")

    /**
     * B7: the helper answered a `labs.*` request with err `disabled` (its Labs switch is off). [ref] is the
     * request that failed (`labs.tv.open`); without one the caller's own lab is meant ([default]).
     */
    fun helperLabDisabled(ref: String?, default: String = "this lab"): Blocker {
        val lab = when {
            ref == null -> default
            ref.startsWith("labs.tv") -> "TV mode"
            ref.startsWith("labs.open") -> "Open links"
            ref.startsWith("labs.screen") -> "Live screen"
            ref.startsWith("labs.video") -> "Browser extension bridge"
            ref.startsWith("labs.ctx") || ref.startsWith("labs.np") -> "Context & now playing"
            else -> default
        }
        return Blocker("helper-lab-off", "Turn on $lab in Mote Helper › Labs")
    }

    /**
     * Smart remote. Null = nothing blocks it (it may still have nothing to show, which is not an error).
     * [ctx] / [np] are the last `labs.ctx` / `labs.np`; [profileShown] = a profile or now-playing card is displayed.
     */
    fun smartRemote(l: Link, ctx: MacContext, np: NpState, profileShown: Boolean, replied: Boolean = true, waited: Boolean = false): Blocker? {
        link(l)?.let { return it }
        // Subscribed but nothing has come back: wait NO_REPLY_MS ([waited]), then say so (never a silent, empty panel).
        if (!replied) return if (waited) Blocker("no-reply", "The Mac hasn't answered yet. Check that Mote Helper is running and up to date.") else null
        if (!ctx.enabled && !np.enabled) return helperSwitchOff("Labs › Context & now playing")
        if (ctx.browser && ctx.automation == "denied") {
            return Blocker("automation-denied", "Allow Mote Helper to control ${ctx.app} (System Settings › Privacy › Automation) to see the tab")
        }
        if (ctx.browser && ctx.automation == "ask") {
            return Blocker("automation-ask", "Mote Helper hasn't been allowed to read ${ctx.app} tabs yet: in its menu choose Labs › Allow ${ctx.app} tabs")
        }
        if (!profileShown && ctx.browser && np.np == null) {
            val why = if (np.enabled && np.source == "unavailable") "macOS doesn't report what plays" else "nothing is reported as playing"
            return Blocker("no-signal", "Can't tell what ${ctx.app} is showing: $why")
        }
        return null
    }

    /** Browser video control: [bridge] is `labs.video.state.bridge` (`off` / `waiting` / `connected`), null = the helper has not answered. */
    fun videoBridge(l: Link, bridge: String?, active: Boolean): Blocker? {
        link(l)?.let { return it }
        return when {
            bridge == null -> Blocker("no-reply", "The Mac has not answered; Mote Helper may be too old for Browser video control")
            bridge == "off" -> helperSwitchOff("Labs › Browser extension bridge")
            bridge == "waiting" -> Blocker("extension-missing", "Install the Mote Video Bridge extension in the Mac's browser and connect it")
            !active -> Blocker("no-video", "No video found in the Mac's active browser tab")
            else -> null
        }
    }

    /** Open & TV: the Open… sheet's link line when it cannot work. */
    fun openTv(l: Link): Blocker? = link(l)

    /** Logs a blocker once per change (`labs.blocked`), so the event log says why a panel was empty. */
    private val lastByLab = HashMap<String, String?>()
    fun note(lab: String, b: Blocker?) {
        val code = b?.code
        synchronized(lastByLab) {
            if (lastByLab.containsKey(lab) && lastByLab[lab] == code) return
            lastByLab[lab] = code
        }
        if (b != null) Events.log("labs", "blocked", "lab" to lab, "code" to b.code)
    }

    fun resetNotes() = synchronized(lastByLab) { lastByLab.clear() }
}
