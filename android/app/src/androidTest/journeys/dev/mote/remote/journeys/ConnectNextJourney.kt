package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.TestSeam
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/connect-next.md */
@RunWith(AndroidJUnit4::class)
class ConnectNextJourney {
    @get:Rule val j = JourneyRule()

    private val sweep = "faked: discovery = LAN sweep of 10.0.2.0/24 on the helper port; Bonjour off (TestSeam.noBonjour)"
    private val prox = "faked: TestSeam.fakeProximity + --fake-proximity"
    private val offer = "Also pair Bluetooth for login screen & backup (recommended)"

    /** Main path up to the Connect card (S1-S2). */
    private fun toCard(name: String, timeoutMs: Long = 6_000) {
        j.step("S1") { j.expect("Connect to your Mac") }
        j.step("S2", sweep) { j.expect("Connect to $name", timeoutMs = timeoutMs); j.expect(name) }
    }

    @Test fun main() {
        j.path("connect-next")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        toCard(name)
        j.step("S3") { j.tap("Connect to $name") }
        j.step("S4", prox) {
            mac.await("labs: trusted ${bq(phone)} via nearby")
            mac.await("authed ${bq(phone)} via Wi-Fi from ")
            mac.await("labs: new device ${bq(phone)} via nearby \\(k:[0-9a-f]{32}\\)")
            mac.await("^NEW DEVICE: \\{\"id\":\"k:[0-9a-f]{32}\",\"name\":\"${bq(phone)}\",\"via\":\"nearby\"\\}")
        }
        j.step("S5") {
            j.expect("Connected over Wi-Fi", timeoutMs = 8_000)
            j.expect(offer); j.expect("Continue"); j.expect("Skip")
        }
        j.step("S6") {
            j.tap("Skip")
            j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
            j.expect("WI-FI")
        }
        j.step("S7", "faked: stdout NEW DEVICE (headless)") { mac.from = 0; mac.await("^NEW DEVICE: .*\"via\":\"nearby\"") }
    }

    @Test fun wifiOff() {
        j.path("connect-next.wifi-off")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        j.wifi(false)
        j.launch(fakeProximity = true)
        j.step("B1") {
            j.expect("Turn on Wi-Fi", timeoutMs = 6_000)
            j.expect("Wi-Fi is off. Connect to the same Wi-Fi as your Mac, or turn on Tailscale.")
            j.expect("Enter address")
        }
        j.step("B2") {
            j.tap("Turn on Wi-Fi")
            j.compose.waitForIdle()
            if (!j.systemShown("Wi.?Fi", 5_000)) throw AssertionError("system Wi-Fi panel not shown")
        }
        j.step("B3") {
            j.wifi(true)
            j.device.pressBack()
            j.expect("Connect to $name", timeoutMs = 15_000)
        }
    }

    @Test fun notFound() {
        j.path("connect-next.not-found")
        val port = bFreePort()
        j.launch(sweepPort = port, fakeProximity = true)
        j.step("B1") {
            j.bExpectTicking("Mote Helper not found on this network", timeoutMs = 12_000)
            j.expect("Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:")
            j.expect("curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash")
            j.expect("or download it from tusharpandey13.github.io/mote")
            j.expect("Retry"); j.expect("Enter address")
        }
        j.step("B2") {
            j.tap("curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash")
            val clip = j.bClipboard()
            if (clip != "curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash") throw AssertionError("clipboard = $clip")
        }
        lateinit var mac: Helper
        j.step("B3") {
            mac = j.helper("--fake-proximity", port = port)
            val name = mac.info().getString("n")
            j.tap("Retry")
            j.expect("Connect to $name", timeoutMs = 8_000)
        }
    }

    @Test fun enterAddress() {
        j.path("connect-next.enter-address")
        val port = bFreePort()
        // U4: first-run setup shows its own "Not now" (notifications); grant it so "Not now" below is the Tailscale sheet's.
        if (android.os.Build.VERSION.SDK_INT >= 33) j.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        j.launch(sweepPort = port, fakeProximity = true)
        j.bExpectTicking("Mote Helper not found on this network", timeoutMs = 12_000)
        val field = "192.168.1.2 or mac.tailnet.ts.net"
        j.step("B1") {
            j.tap("Enter address")
            j.expect("Enter the Mac's address")
            j.expect("A LAN IP (192.168.1.2), name.local, or its Tailscale name / 100.x address.")
            j.expect(field); j.expect("Find"); j.expect("Back")
        }
        j.step("B2") {
            j.tap("Find")
            j.expect("Enter an address like 192.168.1.2 or mac.tailnet.ts.net.")
            j.bType(field, "bad host!"); j.tap("Find")
            j.expect("Enter an address like 192.168.1.2 or mac.tailnet.ts.net.")
        }
        j.step("B3") {
            j.bType(field, "10.0.2.3:$port"); j.tap("Find")
            j.expect("No Mote Helper answered at 10.0.2.3:$port.", timeoutMs = 10_000)
        }
        // B5 before B4: "Back" from the B4 card returns to the discovery card (AddressConnect onBack), which then
        // shows the found Mac and no longer offers "Enter address".
        j.step("B5", "faked: no Tailscale app → \"Get Tailscale\" variant") {
            j.bType(field, "mac.example.ts.net"); j.tap("Find")
            j.expect("Tailscale is off", timeoutMs = 5_000)
            j.expect("Turn it on to reach mac.example.ts.net.")
            j.expect("Tailscale isn't installed on this phone.")
            j.expect("Get Tailscale"); j.expect("Not now")
            j.tap("Not now"); j.expectGone("Tailscale is off")
        }
        j.step("B4") {
            val mac = j.helper("--fake-proximity", port = port)
            val name = mac.info().getString("n")
            j.bType(field, "10.0.2.2:$port"); j.tap("Find")
            j.expect("Connect to $name", timeoutMs = 8_000)
            j.expect("Back")
        }
    }

    @Test fun needBluetooth() {
        j.path("connect-next.need-bluetooth")
        val mac = j.helper()
        val name = mac.info().getString("n")
        j.launch(fakeProximity = false)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1") {
            j.tap("Connect to $name")
            mac.await("labs: denied \\(far\\) ", 10_000)
            j.expect("Mote uses Bluetooth once to check this phone is next to your Mac.", timeoutMs = 15_000)
            j.expect("Allow and connect")
        }
        j.step("B2") {
            j.tap("Allow and connect")
            if (!j.systemShown("Allow|Don.t allow", 8_000)) throw AssertionError("no Nearby devices permission dialog")
        }
        j.step("B3a") {
            j.systemTap("Don.t allow")
            j.expect("$name couldn't confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account.", timeoutMs = 8_000)
            j.expect("Try again")
        }
    }

    @Test fun far() {
        j.path("connect-next.far")
        val mac = j.helper()
        val name = mac.info().getString("n")
        j.grant(*B_BLE_PERMS)
        j.launch(fakeProximity = false)
        j.expect("Connect to $name", timeoutMs = 6_000)
        val far = "$name couldn't confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account."
        j.step("B1", "real (needs the emulator's Bluetooth adapter on)") {
            j.tap("Connect to $name")
            mac.await("labs: denied \\(far\\) ", 15_000)
            j.expect(far, timeoutMs = 20_000)
            j.expect("Try again")
        }
        j.step("B2") {
            mac.mark()
            j.tap("Try again")
            mac.await("labs: denied \\(far\\) ", 15_000)
            j.expect(far, timeoutMs = 20_000)
        }
    }

    @Test fun stale() {
        j.path("connect-next.stale")
        // TestSeam.fakeProximity can't ask for an old window (no dw): not automated.
    }

    @Test fun badProximity() {
        j.path("connect-next.bad-proximity")
        // needs a forged-token seam: not automated.
    }

    @Test fun requireApprovalAllow() {
        j.path("connect-next.require-approval-allow")
        val mac = j.helper("--fake-proximity", "--require-approval", "--fake-approve")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.step("B1", "faked: --require-approval") { mac.from = 0; mac.await("instant pairing: on, require approval \\(key [0-9a-f]{8}\\)") }
        j.launch(fakeProximity = true)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B2") {
            j.tap("Connect to $name")
            // with --fake-approve the prompt is answered after 100 ms: the waiting copy may be gone already
            j.expectAny("Click Allow on $name. It asks before trusting new devices.", "Connected over Wi-Fi", timeoutMs = 8_000)
        }
        j.step("B3", "faked: --fake-approve answers after 100 ms; alert not shown headless") {
            mac.await("labs: prompt ${bq(phone)} \\(Wi-Fi \\S+\\)")
            mac.await("labs: trusted ${bq(phone)} via approved")
            mac.await("authed ${bq(phone)} via Wi-Fi from ")
            mac.await("labs: new device ${bq(phone)} via approved \\(k:[0-9a-f]{32}\\)")
            mac.await("^NEW DEVICE: .*\"via\":\"approved\"")
        }
        j.step("B4") { j.expect("Connected over Wi-Fi", timeoutMs = 8_000); j.expect(offer) }
    }

    @Test fun requireApprovalDeny() {
        j.path("connect-next.require-approval-deny")
        val mac = j.helper("--fake-proximity", "--require-approval", "--fake-approve-deny")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1", "faked: --fake-approve-deny") {
            j.tap("Connect to $name")
            mac.await("labs: denied \\(denied\\) ")
            j.expect("$name didn't allow this phone.", timeoutMs = 8_000)
            j.expect("Try again")
        }
        j.step("B2", "faked: --fake-approve-deny") {
            mac.mark()
            j.tap("Try again")
            mac.await("labs: denied \\(denied\\) ")
            mac.from = 0
            val prompts = mac.lines().count { it.contains("labs: prompt ") }
            if (prompts != 1) throw AssertionError("expected 1 prompt (cool-down), saw $prompts")
            j.expect("$name didn't allow this phone.")
        }
    }

    @Test fun requireApprovalTimeout() {
        j.path("connect-next.require-approval-timeout")
        val mac = j.helper("--fake-proximity", "--require-approval")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1", "real (60 s wait)") {
            j.tap("Connect to $name")
            j.expect("Click Allow on $name. It asks before trusting new devices.", timeoutMs = 8_000)
            mac.await("labs: prompt ", 10_000)
            // the controller caps one wait at 60 s: wait in two halves for the 60 s prompt timeout
            runCatching { mac.await("labs: denied \\(timeout\\) ", 40_000) }.getOrElse { mac.await("labs: denied \\(timeout\\) ", 40_000) }
            j.expect("$name didn't answer in time.", timeoutMs = 8_000)
        }
    }

    @Test fun blocked() {
        j.path("connect-next.blocked")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        j.tap("Connect to $name", timeoutMs = 6_000)
        j.tap("Skip", timeoutMs = 10_000)
        j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
        j.step("B1", "faked: stdin block stands in for the notice button") {
            mac.mark()
            mac.stdin("block")
            mac.await("labs: blocked k:[0-9a-f]{32}")
            j.tap("Device: $name", substring = true)
            j.expect("The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again.", timeoutMs = 8_000)
        }
        j.step("B2") {
            j.bLongPress("Use $name")
            j.expect("Forget $name on this phone?")
            j.tap("Forget")
            // the only Mac is gone: startScreen → FIND_MAC, the app shows the first-run screen again
            j.expect("Connect to your Mac", timeoutMs = 8_000)
            j.tap("Connect to $name", timeoutMs = 10_000)
            mac.await("labs: denied \\(blocked\\) ", 10_000)
            j.expect("$name blocked this phone. Unblock it with Revoke all devices in the Mote Helper menu.", timeoutMs = 8_000)
        }
        j.step("B3", "faked: stdin unblock") {
            mac.stdin("unblock")
            mac.await("labs: unblocked k:[0-9a-f]{32}")
            // the first trust used this 30 s window's token: retrying in it gets "stale" (instant-pairing-D3)
            bNextProxWindow()
            j.tap("Try again")
            mac.await("labs: trusted ${bq(phone)} via nearby", 10_000)
            mac.await("^NEW DEVICE: ")
        }
    }

    @Test fun instantOff() {
        j.path("connect-next.instant-off")
        lateinit var mac: Helper
        var name = ""
        j.step("B1") {
            mac = j.helper("--no-instant-pairing")
            mac.await("instant pairing: off")
            // no LABS PAIR INFO with instant pairing off: the name comes from the card
            j.launch(fakeProximity = false)
            val end = System.currentTimeMillis() + 6_000
            while (name.isEmpty() && System.currentTimeMillis() < end) {
                name = j.visibleTexts().firstOrNull { it.startsWith("Connect to ") && it != "Connect to your Mac" }?.removePrefix("Connect to ") ?: ""
                if (name.isEmpty()) Thread.sleep(200)
            }
            if (name.isEmpty()) throw AssertionError("no Connect card for a helper with instant pairing off")
        }
        j.step("B2") {
            j.tap("Connect to $name")
            j.expect("Mote Helper on $name is too old or has instant pairing turned off. Update it, or use Pair with code.", timeoutMs = 10_000)
        }
    }

    @Test fun unreachable() {
        j.path("connect-next.unreachable")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1") {
            mac.stop()
            j.tap("Connect to $name")
            j.expect("Couldn't connect to $name (IOException: ", substring = true, timeoutMs = 20_000)
            j.expect("). Is Mote Helper running?", substring = true)
            j.expect("Try again")
        }
    }

    @Test fun timeout() { j.path("connect-next.timeout") }      // needs the chaos proxy in the controller: not automated
    @Test fun helperKey() { j.path("connect-next.helper-key") } // needs a stable --host-id with a new key: not automated
    @Test fun twoMacs() { j.path("connect-next.two-macs") }     // sweep probes one port: not automated

    @Test fun btOffer() {
        j.path("connect-next.bt-offer")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.tap("Connect to $name", timeoutMs = 6_000)
        j.tap("Skip", timeoutMs = 10_000)
        j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
        val mac2 = j.helper("--fake-proximity", name = "mac2")
        val name2 = mac2.info().getString("n")
        j.step("B1", "faked: adapter = the emulator's; second helper on another port (TestSeam.sweepPort switched)") {
            TestSeam.sweepPort = mac2.port
            j.tap("Device: $name via Wi-Fi. Tap to choose")
            j.tap("Add a Mac")
            j.tap("Connect to $name2", timeoutMs = 10_000)
            mac2.await("labs: trusted .* via nearby", 10_000)
            j.expect("Connected over Wi-Fi", timeoutMs = 8_000)
            j.expect(offer)
            j.expect("Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.")
            j.expect("Continue"); j.expect("Skip")
        }
        j.step("B2") {
            j.tap("Skip")
            j.expectGone(offer, timeoutMs = 5_000)
        }
    }
}
