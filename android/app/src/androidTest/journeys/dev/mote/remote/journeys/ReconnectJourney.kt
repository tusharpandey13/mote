package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Host
import dev.mote.remote.net.Net
import dev.mote.remote.net.Selection
import dev.mote.remote.net.WsStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/reconnect.md */
@RunWith(AndroidJUnit4::class)
class ReconnectJourney {
    @get:Rule val j = JourneyRule()

    private val mbp = Host("AA:00:00:00:00:01", "MacBook Pro", Host.Kind.LAPTOP)
    private val mini = Host("AA:00:00:00:00:02", "Mac mini", Host.Kind.DESKTOP)
    private val btSeam = "faked: TestSeam.adapter (bonded MacBook Pro, Mac mini) + FakeTransport(Caps.HID, linked = false); no real HID link"

    @Test fun main() {
        j.path("reconnect")
        j.dGrantBt()
        val a = dAdapter(mbp, mini)
        val t = dFakeHid(linked = false)
        // precondition: Bluetooth host MacBook Pro selected
        j.launch(transport = t, adapter = a)
        j.tap(dPill("Choose a device", null), timeoutMs = 10_000)
        j.tap("Use MacBook Pro")
        j.dWait("selection bt:MacBook Pro") { dSelectedBt(mbp.address) }
        j.dRelaunch(fakeProximity = false, transport = t, adapter = a)

        j.step("S1", btSeam) {
            j.expect(dPill("MacBook Pro", "BT"), timeoutMs = 10_000)
            j.expectNot("Connect to your Mac")
            val s = Hid.state.value
            if (s.selected?.address != mbp.address || s.connected != null || s.unpaired) throw AssertionError("Hid state not searching: $s")
        }
        j.step("S2") { j.dNotification("Searching for MacBook Pro…") }
        j.step("S3", "faked: FakeTransport linked = false") {
            t.clear()
            j.dPress("Play or pause")
            Thread.sleep(600)
            if (t.calls.isNotEmpty()) throw AssertionError("sent while not linked: ${t.calls}")
        }
        j.step("S4") {
            j.tap(dPill("MacBook Pro", "BT"))
            j.expect("Devices")
            j.expect("Mote reconnects to the selected device while the app is open.")
            j.expect("Use MacBook Pro"); j.expect("Searching…")
            j.expect("Use Mac mini"); j.expect("Paired")
            j.expect("Add a Mac"); j.expect("More options")
            j.expect("Pair new Bluetooth device")
            j.expect("Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.")
            j.expect("Connected over Bluetooth but buttons do nothing?")
        }
        j.step("S5", btSeam) {
            j.appMark()
            j.tap("Use Mac mini")
            j.expect(dPill("Mac mini", "BT"), timeoutMs = 5_000)
            j.dWait("selection bt:Mac mini") { dSelectedBt(mini.address) }
            j.dNotification("Searching for Mac mini…")
            j.appEvent("transport", "select", "kind" to "bluetooth")   // DU-7: switching Macs from Devices
        }
    }

    @Test fun helperNetwork() {
        j.path("reconnect.helper-network")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        j.dPair(mac, name)
        mac.mark()
        j.step("B1", "faked: the silent reconnect right after pairing stands in for a cold launch (the app process can't be restarted under instrumentation)") {
            mac.from = 0
            mac.await("labs: trusted \\Q$phone\\E via trusted", 15_000)
            mac.await("authed \\Q$phone\\E via Wi-Fi from ", 10_000)
            j.expect(dPill(name, "Wi-Fi"), timeoutMs = 10_000)
        }
        // Offline: the controller (10.0.2.2) is unreachable, so observations are taken now and the
        // B2/B3 steps assert them after the network is back (screenshots are taken then).
        var sawConnecting = false
        var row = false
        var note: String? = null
        val notes = arrayOf(
            "Mote Helper isn't running on $name. Open it from Applications on the Mac.",
            "$name seems asleep or away. Wake it (open the lid or press a key) and keep it on this Wi-Fi or Tailscale.",
        )
        try {
            j.shell("svc data disable")
            j.wifi(false)
            sawConnecting = runCatching { j.dWs(45_000, "Connecting") { it is WsStatus.Connecting } }.isSuccess
            if (sawConnecting) {
                j.tap(dPill(name, "Wi-Fi"))
                row = runCatching { j.expect("Connecting…", 3_000) }.isSuccess
                note = runCatching { j.dExpectAny(*notes, timeoutMs = 8_000) }.getOrNull()
            }
        } finally {
            j.shell("svc data enable")
            j.wifi(true)
        }
        j.step("B2", "real; observed offline, asserted after the network is back") {
            if (!sawConnecting) throw AssertionError("WsStatus never became Connecting within 45 s of Wi-Fi + data off")
        }
        j.step("B3", "real; observed offline, asserted after the network is back") {
            if (!row) throw AssertionError("row status \"Connecting…\" not shown while offline")
            if (note == null) throw AssertionError("no reachNote line while offline")
            if (note == notes[0]) throw AssertionError("offline phone was told \"${notes[0]}\" (reconnect-D5)")
        }
        j.step("B4") {
            mac.await("authed \\Q$phone\\E via Wi-Fi from ", 20_000)
            j.dWs(10_000, "Ready") { it is WsStatus.Ready }
            j.expect("Connected · Wi-Fi", timeoutMs = 5_000)
        }
    }

    @Test fun background() {
        j.path("reconnect.background")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        j.dPair(mac, name)
        j.dWs(10_000, "Ready") { it is WsStatus.Ready }
        mac.mark()
        j.step("B1") {
            j.appMark(); mac.mark()
            j.device.pressHome()
            Thread.sleep(29_000)
            if (Net.wsStatus.value !is WsStatus.Ready) throw AssertionError("socket closed before 30 s: ${Net.wsStatus.value}")
            j.dWs(6_000, "Idle 30 s after leaving the screen") { it is WsStatus.Idle }
            j.appEvent("lifecycle", "foreground", "on" to false)   // RC-11
            mac.event("session", "closed")
        }
        j.step("B2") {
            // JourneyRule.backgroundAndReturn can't resume after Home (ActivityScenario stays STOPPED): bring the task back with am start
            j.shell("am start -n ${j.ctx.packageName}/dev.mote.remote.MainActivity")
            mac.await("authed \\Q$phone\\E via Wi-Fi from ", 15_000)
            j.expect(dPill(name, "Wi-Fi"), timeoutMs = 10_000)
        }
    }

    @Test fun fallbackBt() {
        j.path("reconnect.fallback-bt")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        val bt = Host("AA:00:00:00:00:01", name, Host.Kind.LAPTOP)
        j.dGrantBt()
        val a = dAdapter(bt)
        val t = dFakeHid(linked = false)
        j.launch(fakeProximity = true, transport = t, adapter = a)
        j.dPair(mac, name)
        j.dWait("btAddress linked at Connect") { Net.saved.value.firstOrNull()?.btAddress == bt.address }
        j.dWs(10_000, "Ready") { it is WsStatus.Ready }
        j.step("B1", "faked: SIGSTOP on the dry-run helper; adapter seam + FakeTransport for the Bluetooth side") {
            j.appMark()
            mac.sigstop()
            j.dWait("fallback to Bluetooth", 100_000) { dSelectedBt(bt.address) }
            j.expect(dPill(name, "BT"), timeoutMs = 5_000)
            if (Hid.state.value.selected?.address != bt.address) throw AssertionError("Hid not on the linked host: ${Hid.state.value}")
            j.appEvent("transport", "connect.backoff")                       // RC-5: the helper never answered
            j.appEvent("transport", "select", "kind" to "bluetooth")
        }
        j.step("B2", "faked: SIGCONT") {
            mac.mark()
            mac.sigcont()
            val hostId = Net.saved.value.first().hostId
            j.dWait("probe back to the helper", 45_000) { Net.selection.value == Selection.Helper(hostId) }
            mac.await("authed \\Q$phone\\E via Wi-Fi from ", 20_000)
            j.expect(dPill(name, "Wi-Fi"), timeoutMs = 10_000)
            j.appEvent("transport", "select", "kind" to "helper")             // RC-5: probed back
        }
    }

    @Test fun forgetHelper() {
        j.path("reconnect.forget-helper")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.dPair(mac, name)
        j.step("B1") {
            j.tap(dPill(name, "Wi-Fi"))
            j.dLongPress("Use $name")
            j.expect("Forget $name on this phone?")
            j.expect("Forget"); j.expect("Cancel")
        }
        j.step("B2") {
            j.tap("Cancel")
            j.expectGone("Forget $name on this phone?")
            if (Net.saved.value.size != 1) throw AssertionError("Cancel changed saved hosts: ${Net.saved.value}")
        }
        j.step("B3") {
            j.appMark()
            j.dLongPress("Use $name")
            j.tap("Forget")
            j.dWait("saved hosts empty") { Net.saved.value.isEmpty() && Net.selection.value == null }
            if (dev.mote.remote.pairing.InstantPairing.records.value.isEmpty()) throw AssertionError("trust record was removed (spec: it stays)")
            // No Mac left and Wi-Fi was chosen: startScreen → FIND_MAC at once (reconnect-D7)
            j.expect("Connect to your Mac", timeoutMs = 5_000)
            j.expect("Connect to $name", timeoutMs = 10_000)
            j.appEvent("ui", "screen", "screen" to "find_mac")   // RC-14
        }
    }
}
