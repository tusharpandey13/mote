package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.Host
import dev.mote.remote.net.Net
import dev.mote.remote.net.Selection
import dev.mote.remote.net.WsStatus
import dev.mote.remote.ui.DENIED_TITLE
import dev.mote.remote.ui.denialText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/recovery.md */
@RunWith(AndroidJUnit4::class)
class RecoveryJourney {
    @get:Rule val j = JourneyRule()

    private val revokedCopy = "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again."
    private val fp = "[0-9a-f]{32}"

    private fun paired(vararg flags: String): Triple<Helper, String, String> {
        val mac = j.helper("--fake-proximity", *flags)
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.dPair(mac, name)
        j.dWs(10_000, "Ready") { it is WsStatus.Ready }
        return Triple(mac, name, j.phoneName())
    }

    /** Pill › long-press › Forget, then "Add a Mac" and Connect (sheet stays open on the card). */
    private fun forgetAndConnect(name: String) {
        if (!j.shown("Devices")) j.tap(dPill(name, Ctl.link))
        j.dLongPress("Use $name")
        j.tap("Forget")
        j.dWait("forgotten") { Net.saved.value.isEmpty() }
        j.expect("Connect to your Mac", timeoutMs = 5_000) // no Mac left: FindMacScreen
        j.expect("Connect to $name", timeoutMs = 10_000)
        dFreshProximityWindow()
        j.tap("Connect to $name")
    }

    @Test fun main() {
        j.path("recovery")
        val (mac, name, phone) = paired()
        mac.mark()
        j.step("S1", "faked: the silent reconnect right after pairing stands in for a cold launch (the app process can't be restarted under instrumentation)") {
            mac.from = 0
            mac.await("labs: trusted \\Q$phone\\E via trusted", 15_000)
            mac.await("authed \\Q$phone\\E via ${Ctl.link} from ", 10_000)
            j.expect(dPill(name, Ctl.link), timeoutMs = 10_000)
        }
        j.step("S2", "faked: stdin-control `revoke` stands in for the menu alert") {
            mac.stdin("revoke")
            mac.await("all devices revoked")
        }
        j.step("S3") {
            j.dWs(10_000, "Failed(revoked)") { it is WsStatus.Failed && it.code == "revoked" }
            j.expect(dPill(name, Ctl.link))
            j.expectNot("Not paired: pair again")
            // ~4 s after the refusal `rediscover` re-selects the Mac once; the refused handshake lands as Denied (B6, recovery-D5)
            mac.await("labs: denied \\(revoked\\)", 20_000)
            j.dWs(10_000, "Denied(revoked)") { it is WsStatus.Denied && it.reason == "revoked" }
            j.expect(DENIED_TITLE)
        }
        j.step("S4") {
            j.tap(dPill(name, Ctl.link))
            j.expect(denialText("revoked"))
            j.expect("Use $name")
            j.expect("Add a Mac")
        }
        j.step("S5") {
            j.dLongPress("Use $name")
            j.tap("Forget")
            j.dWait("forgotten") { Net.saved.value.isEmpty() }
            j.expect("Connect to your Mac", timeoutMs = 5_000)
            j.expect("New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.")
        }
        j.step("S6", "real (sweep on sweepPort)") {
            j.expectAny("Looking for your Mac…", "Connect to $name", timeoutMs = 5_000)
            j.expect("Connect to $name", timeoutMs = 10_000)
        }
        j.step("S7", D_PROX) {
            mac.mark()
            dFreshProximityWindow()
            j.tap("Connect to $name")
            mac.await("labs: trusted \\Q$phone\\E via nearby", 15_000)
            mac.await("labs: new device \\Q$phone\\E via nearby \\(k:$fp\\)")
            mac.from = 0
            mac.await("^NEW DEVICE: ") // the first pairing (precondition)
            mac.await("^NEW DEVICE: \\{\"id\":\"k:$fp\",\"name\":\"\\Q$phone\\E\",\"via\":\"nearby\"\\}")
            j.expectAny("Connected over ${Ctl.link}", "Connected", timeoutMs = 8_000)
            j.dSkipBt(name)
        }
        j.step("S8") { j.expect(dPill(name, Ctl.link), timeoutMs = 10_000) }
    }

    @Test fun revokedRelaunch() {
        j.path("recovery.revoked-relaunch")
        val (mac, name, _) = paired()
        mac.stdin("revoke"); mac.await("all devices revoked")
        mac.event("session", "revoked", ".*\"scope\":\"all\".*")   // RC-10 Detected by, helper side
        j.dWs(10_000, "Failed") { it is WsStatus.Failed }
        mac.mark()
        j.step("B1") {
            j.dRelaunch()
            mac.await("labs: denied \\(revoked\\)", 15_000)
            j.dWs(10_000, "Denied(revoked)") { it is WsStatus.Denied && it.reason == "revoked" }
            j.expect(DENIED_TITLE)
            j.tap(dPill(name, Ctl.link), timeoutMs = 10_000)
            j.expect(denialText("revoked"))
        }
        j.step("B2") {
            j.tap("Add a Mac")
            j.dExpect("Mote Helper not found on this network", timeoutMs = 12_000)
            j.expect("Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:")
        }
    }

    @Test fun revokedTailscale() {
        j.path("recovery.revoked-tailscale")
        val (mac, name, phone) = paired("--fake-tailscale-owner", "self")
        mac.stdin("revoke"); mac.await("all devices revoked")
        j.dWs(10_000, "Failed") { it is WsStatus.Failed }
        mac.mark()
        j.step("B1", "faked: --fake-tailscale-owner self (no tailnet on the emulator)") {
            j.dRelaunch()
            mac.await("labs: fake whois .* owner=self", 15_000)
            mac.await("labs: trusted \\Q$phone\\E via tailscale")
            mac.await("^NEW DEVICE: .*\"via\":\"tailscale\"")
            j.dWs(10_000, "Ready") { it is WsStatus.Ready }
            j.expect(dPill(name, Ctl.link))
        }
    }

    @Test fun revokedFallback() {
        j.path("recovery.revoked-fallback")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val bt = Host("AA:00:00:00:00:01", name, Host.Kind.LAPTOP)
        j.dGrantBt()
        j.launch(fakeProximity = true, transport = dFakeHid(false), adapter = dAdapter(bt))
        j.dPair(mac, name)
        j.dWait("btAddress linked") { Net.saved.value.firstOrNull()?.btAddress == bt.address }
        j.dWs(10_000, "Ready") { it is WsStatus.Ready }
        val hostId = Net.saved.value.first().hostId
        mac.stdin("revoke"); mac.await("all devices revoked")
        j.step("B1", "faked: adapter + FakeTransport") {
            // B6: Denied backs off 30 s, no silent loop; the Denied status is not "unreachable" for the fallback timer
            mac.mark()
            Thread.sleep(25_000)
            val denied = mac.lines(mac.from).count { Regex("labs: denied \\(revoked\\)").containsMatchIn(it) }
            if (dSelectedBt(bt.address)) throw AssertionError("fell back to Bluetooth after the refusal")
            if (denied > 1) throw AssertionError("expected at most 1 further refused handshake in 25 s (no silent loop), saw $denied")
            j.dWs(5_000, "Denied(revoked)") { it is WsStatus.Denied && it.reason == "revoked" }
            j.expect(DENIED_TITLE)
            j.expect(dPill(name, Ctl.link))
        }
        j.step("B2", "faked: same") {
            if (Net.selection.value != Selection.Helper(hostId)) throw AssertionError("selection ${Net.selection.value}")
            j.dWs(10_000, "Denied") { it is WsStatus.Denied }
        }
    }

    @Test fun block() {
        j.path("recovery.block")
        val (mac, name, phone) = paired()
        mac.mark()
        j.step("B1", "faked: stdin-control `block` stands in for the notice's Block button") {
            mac.stdin("block")
            mac.await("labs: blocked k:$fp")
        }
        j.step("B2") {
            j.dWs(10_000, "Failed(revoked)") { it is WsStatus.Failed && it.code == "revoked" }
            j.tap(dPill(name, Ctl.link))
            j.expect(revokedCopy)
        }
        j.step("B3", D_PROX) {
            mac.mark()
            forgetAndConnect(name)
            mac.await("labs: denied \\(blocked\\)", 15_000)
            j.expect("$name blocked this phone. Unblock it with Revoke all devices in the Mote Helper menu.", timeoutMs = 8_000)
            j.expect("Try again")
        }
        j.step("B4", "faked: stdin-control `unblock`") {
            mac.stdin("unblock")
            mac.await("labs: unblocked k:$fp")
        }
        j.step("B5", D_PROX) {
            dFreshProximityWindow()
            j.tap("Try again")
            mac.await("labs: trusted \\Q$phone\\E via nearby", 15_000)
            j.dSkipBt(name)
        }
    }

    @Test fun revokeUnblocks() {
        j.path("recovery.revoke-unblocks")
        val (mac, name, phone) = paired()
        mac.stdin("block"); mac.await("labs: blocked k:$fp")
        j.step("B1", "faked: stdin-control `revoke`, `unblock`") {
            mac.stdin("revoke"); mac.await("all devices revoked")
            mac.stdin("unblock"); mac.await("labs: unblock: nothing blocked")
        }
        j.step("B2", D_PROX) {
            mac.mark()
            j.dWs(10_000, "Failed or Denied") { it is WsStatus.Failed || it is WsStatus.Denied }
            forgetAndConnect(name)
            mac.await("labs: trusted \\Q$phone\\E via nearby", 15_000)
            mac.from = 0
            mac.await("^NEW DEVICE: .*\"via\":\"nearby\"")
            mac.await("^NEW DEVICE: .*\"via\":\"nearby\"") // the second one: the blocked phone is back
        }
    }

    @Test fun pause() {
        j.path("recovery.pause")
        val (mac, name, _) = paired()
        mac.mark()
        j.step("B1", "faked: stdin-control `pause`") {
            mac.stdin("pause"); mac.await("input paused")
            j.dWs(5_000, "Ready(paused)") { it is WsStatus.Ready && it.paused }
            j.tap(dPill(name, Ctl.link))
            j.expect("Paused on the Mac")
            j.device.pressBack()
            j.expectGone("Paused on the Mac")
        }
        j.step("B2") {
            mac.mark()
            j.dPress("Play or pause")
            mac.assertNo("DRY consumer", 1_500)
        }
        j.step("B3", "faked: stdin-control `resume`") {
            mac.stdin("resume"); mac.await("input resumed")
            j.dWs(5_000, "Ready(not paused)") { it is WsStatus.Ready && !it.paused }
            j.tap(dPill(name, Ctl.link))
            j.expect("Connected · ${Ctl.link}")
        }
    }

    @Test fun reinstall() {
        j.path("recovery.reinstall")
        j.step("B1", "real (app data cleared by the orchestrator = pm clear)") {
            j.launch()
            j.expect("Connect to your Mac", timeoutMs = 10_000)
            j.expect("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.")
            j.expect("New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.")
            j.expect("Use Bluetooth instead (no helper)")
            j.expectNot("How do you want to connect?")
        }
    }

    @Test fun nearbyRevoked() {
        j.path("recovery.nearby-revoked")
        val (_, name, _) = paired()
        j.step("B2") {
            j.expect(dPill(name, Ctl.link))
            j.tap(dPill(name, Ctl.link))
            j.expect("Set up Bluetooth")
            if (Net.wsStatus.value !is WsStatus.Ready) throw AssertionError("helper link affected: ${Net.wsStatus.value}")
        }
    }

    @Test fun btOff() {
        j.path("recovery.bt-off")
        val host = Host("AA:00:00:00:00:01", "MacBook Pro", Host.Kind.LAPTOP)
        j.dGrantBt()
        val a = dAdapter(host)
        j.launch(transport = dFakeHid(false), adapter = a)
        j.tap(dPill("Choose a device", null), timeoutMs = 10_000)
        j.tap("Use MacBook Pro")
        j.dWait("selection bt") { dSelectedBt(host.address) }
        j.step("B1", "faked: TestSeam.adapter enabled = false") {
            a.value = a.value.copy(enabled = false)
            j.expect("How do you want to connect?", timeoutMs = 5_000)
            j.expect("Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue.")
            j.expect("Turn on Bluetooth")
            j.expect("Connect to your Mac instead")
            j.expect("Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point.")
        }
    }
}
