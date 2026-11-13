package dev.mote.remote.service

import dev.mote.remote.hid.Host
import dev.mote.remote.hid.HidState
import dev.mote.remote.net.WsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkNoteTest {
    private val mac = Host("FC:B2:14:74:79:4B", "MacBook")
    private val ready = WsStatus.Ready("192.168.1.9:8790", paused = false, ax = true)
    private val none = HidState()

    @Test fun idleWithNothingSelected() = assertEquals(LinkNote.Idle, linkNote(none, null, WsStatus.Idle))

    // D-DU-1: a Wi-Fi helper Mac is not "Ready to pair"
    @Test fun helperReadyIsConnected() = assertEquals(LinkNote.Connected("Studio"), linkNote(none, "Studio", ready))
    @Test fun helperPausedOrWithoutAccessibilityStillConnected() {
        assertEquals(LinkNote.Connected("Studio"), linkNote(none, "Studio", ready.copy(paused = true)))
        assertEquals(LinkNote.Connected("Studio"), linkNote(none, "Studio", ready.copy(ax = false)))
    }
    @Test fun helperConnectingIsSearching() {
        assertEquals(LinkNote.Searching("Studio"), linkNote(none, "Studio", WsStatus.Connecting))
        assertEquals(LinkNote.Searching("Studio"), linkNote(none, "Studio", WsStatus.Reading))
        assertEquals(LinkNote.Searching("Studio"), linkNote(none, "Studio", WsStatus.Idle))
    }
    @Test fun helperNeedingTheUser() {
        assertEquals(LinkNote.HelperFailed("Studio"), linkNote(none, "Studio", WsStatus.Failed("auth", "re-pair")))
        assertEquals(LinkNote.HelperFailed("Studio"), linkNote(none, "Studio", WsStatus.Denied("far")))
    }
    @Test fun helperDownButBluetoothBackupUp() =
        assertEquals(LinkNote.Connected("MacBook"), linkNote(HidState(connected = mac, selected = mac), "Studio", WsStatus.Failed("io", "x")))

    @Test fun bluetoothConnectedSearchingAndGivenUp() {
        assertEquals(LinkNote.Connected("MacBook"), linkNote(HidState(connected = mac, selected = mac), null, WsStatus.Idle))
        assertEquals(LinkNote.Searching("MacBook"), linkNote(HidState(selected = mac), null, WsStatus.Idle))
        // D-RC-3
        assertEquals(LinkNote.NotFound("MacBook"), linkNote(HidState(selected = mac, gaveUp = true), null, WsStatus.Idle))
    }
}
