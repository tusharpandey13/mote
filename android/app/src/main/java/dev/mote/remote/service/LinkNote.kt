package dev.mote.remote.service

import dev.mote.remote.hid.HidState
import dev.mote.remote.net.WsStatus

/** What the ongoing link notification says. Pure so it is unit-testable (D-DU-1). */
sealed interface LinkNote {
    val name: String?
    data class Connected(override val name: String) : LinkNote
    data class Searching(override val name: String) : LinkNote
    /** Bluetooth auto-reconnect used up its attempts. */
    data class NotFound(override val name: String) : LinkNote
    /** The helper link needs the user (re-pair, update, Accessibility off...). */
    data class HelperFailed(override val name: String) : LinkNote
    data object Idle : LinkNote { override val name: String? = null }
}

/**
 * The active link wins: a Mote Helper host (Wi-Fi / Tailscale) when one is the active transport
 * ([helperName] non-null; Paused / no Accessibility on the Mac still means the link is up), else Bluetooth HID. A Bluetooth backup link that is up still counts as
 * connected while the helper is down.
 */
fun linkNote(hid: HidState, helperName: String?, ws: WsStatus): LinkNote {
    if (helperName != null) return when {
        ws is WsStatus.Ready -> LinkNote.Connected(helperName)
        hid.connected != null -> LinkNote.Connected(hid.connected.name)
        ws is WsStatus.Failed || ws is WsStatus.Denied -> LinkNote.HelperFailed(helperName)
        else -> LinkNote.Searching(helperName)
    }
    return when {
        hid.connected != null -> LinkNote.Connected(hid.connected.name)
        hid.selected != null && hid.gaveUp -> LinkNote.NotFound(hid.selected.name)
        hid.selected != null -> LinkNote.Searching(hid.selected.name)
        else -> LinkNote.Idle
    }
}
