package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/wifi-helper.md */
@RunWith(AndroidJUnit4::class)
class WifiHelperJourney {
    @get:Rule val j = JourneyRule()

    private val proxSeam = "faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity) instead of BLE"

    @Test fun main() {
        j.path("wifi-helper")
        j.manual("S1", "install.sh installs and opens the real app (TCC, Keychain); stand-in: the dry-run helper started in S2")
        lateinit var mac: Helper
        var name = ""
        j.step("S2", "faked: dry-run headless helper stands in for the installed app") {
            mac = j.helper("--fake-proximity")
            mac.await("instant pairing: on")
            name = mac.info().getString("n")
        }
        j.step("S3") {
            j.launch(fakeProximity = true)
            j.expect("Connect to your Mac")
            j.expect("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.")
            j.expect("New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.")
            j.expect("Use Bluetooth instead (no helper)")
            if (j.systemShown("Allow", 500)) throw AssertionError("a permission dialog was shown at launch")
        }
        j.step("S4", "faked: discovery = LAN sweep of 10.0.2.0/24 on the helper port; Bonjour off (TestSeam.noBonjour)") {
            // Searching may be skipped when the sweep answers first: accept either, but never Not found.
            j.expectAny("Looking for your Mac…", "Connect to $name", timeoutMs = 5_000)
        }
        j.step("S5", "faked: discovery = LAN sweep on the helper port") {
            j.expect("Connect to $name", timeoutMs = 5_000)
            j.expect(name)
            mac.assertNo("authed ", 500)
        }
        j.step("S6") { j.tap("Connect to $name") }
        j.step("S7", proxSeam) {
            val phone = j.phoneName()
            // in this order (each await continues after the previous line)
            mac.await("labs: trusted \\Q$phone\\E via nearby")
            mac.await("authed \\Q$phone\\E via ${Ctl.link} from 127\\.0\\.0\\.1")
            mac.await("labs: new device \\Q$phone\\E via nearby \\(k:[0-9a-f]{32}\\)")
            mac.await("^NEW DEVICE: \\{\"id\":\"k:[0-9a-f]{32}\",\"name\":\"\\Q$phone\\E\",\"via\":\"nearby\"\\}")
        }
        j.step("S8") {
            // next-0.6: setup stays on screen for the optional Bluetooth step (App.kt `setup`), the emulator has an adapter
            j.expect("Connected over ${Ctl.link}", timeoutMs = 8_000)
            j.expect("Also pair Bluetooth for login screen & backup (recommended)")
            j.expect("Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.")
            j.expect("Continue"); j.expect("Skip")
        }
        j.step("S9") {
            mac.await("labs: trusted .* via trusted", 10_000)
            mac.assertNo("^NEW DEVICE", 1_000)
        }
        j.step("S10") {
            j.tap("Skip")
            j.expect("Device: $name via ${Ctl.link}. Tap to choose", timeoutMs = 8_000)
            j.expect(Ctl.link.uppercase())
        }
        j.step("S11", "faked: headless helper has no floating notice; stdout NEW DEVICE (S7) stands in") {
            mac.from = 0; mac.await("^NEW DEVICE: ")
        }
    }

    @Test fun tailscaleOwner() {
        j.path("wifi-helper.tailscale-owner")
        val mac = j.helper("--fake-tailscale-owner", "self")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = false)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1") { j.tap("Connect to $name"); j.expectAny("Connecting…", "Connected over Tailscale") }
        j.step("B2", "faked: tailnet owner match by --fake-tailscale-owner self over the LAN (no tailnet on the emulator)") {
            mac.await("labs: trusted .* via tailscale")
            mac.await("authed .* via ${Ctl.link} from 127\\.0\\.0\\.1")
            mac.from = 0; mac.await("^NEW DEVICE: .*\"via\":\"tailscale\"")
            mac.event("session", "paired", ".*\"via\":\"tailscale\".*")   // SP-9 / FC-7: the same-owner trust decision
        }
        j.step("B3") {
            // code: the label comes from labs.pair.ok `via`, not the route (connect-next-D4)
            j.expect("Connected over Tailscale", timeoutMs = 8_000)
            j.expect("Also pair Bluetooth for login screen & backup (recommended)")
        }
        j.step("B4") {
            j.tap("Skip")
            j.expect("Device: $name via ${Ctl.link}. Tap to choose", timeoutMs = 8_000)
        }
        j.step("B5", "faked: stdout NEW DEVICE stands in for the Mac's notice") { mac.from = 0; mac.await("^NEW DEVICE: .*tailscale") }
    }

    @Test fun tailscaleReal() {
        j.path("wifi-helper.tailscale-real")
        j.manual("B1", "needs a real tailnet (no Tailscale on the emulator)")
        j.manual("B2", "needs a real tailnet")
    }

    @Test fun installNextSteps() {
        j.path("wifi-helper.install-next-steps")
        j.static("B1", "site/install.sh final text: Setup window, then open Mote on the phone (wifi-helper-D1 FIXED in next-0.6)")
        j.static("B2", "main.swift: no pairing window unless --open-pairing or instant pairing off")
    }
}
