package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/instant-pairing.md */
@RunWith(AndroidJUnit4::class)
class InstantPairingJourney {
    @get:Rule val j = JourneyRule()

    private val prox = "faked: TestSeam.fakeProximity + --fake-proximity; card found by the LAN sweep (no BLE seam)"

    @Test fun main() { j.path("instant-pairing") } // S1 needs TestSeam.ble (BLE-found card): not automated

    @Test fun defaultNearby() {
        j.path("instant-pairing.default-nearby")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1", prox) {
            j.tap("Connect to $name")
            mac.await("labs: trusted ${bq(phone)} via nearby", 10_000)
            mac.await("^NEW DEVICE: .*\"via\":\"nearby\"")
            mac.from = 0; mac.assertNo("labs: prompt ", 500)
            j.expect("Connected over Wi-Fi", timeoutMs = 8_000)
        }
    }

    @Test fun tailscaleOwner() {
        j.path("instant-pairing.tailscale-owner")
        val mac = j.helper("--fake-tailscale-owner", "self")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = false)
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B1", "faked: --fake-tailscale-owner self over the LAN") {
            j.tap("Connect to $name")
            mac.await("labs: fake whois \\S+ owner=self", 10_000)
            mac.await("labs: trusted .* via tailscale")
            mac.from = 0; mac.await("^NEW DEVICE: .*\"via\":\"tailscale\"")
            j.expect("Connected over Tailscale", timeoutMs = 8_000)
        }
    }

    @Test fun tailscaleOwnerNoBt() {
        j.path("instant-pairing.tailscale-owner")
        val mac = j.helper("--fake-tailscale-owner", "self")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = false, adapter = MutableStateFlow(TestSeam.Adapter(supported = false)))
        j.expect("Connect to $name", timeoutMs = 6_000)
        j.step("B2", "faked: TestSeam.adapter(supported=false) (no Bluetooth offer)") {
            j.tap("Connect to $name")
            j.expect("Connected. Trusted via Tailscale", timeoutMs = 8_000)
            j.bAdvance(1_000) // ConnectCard waits 900 ms (composition delay) before onDone
            j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 5_000)
        }
    }

    @Test fun reconnectLan() {
        j.path("instant-pairing.reconnect-lan")
        val mac = j.helper("--fake-proximity", "--require-approval", "--fake-approve")
        val name = mac.info().getString("n")
        val phone = j.phoneName()
        j.launch(fakeProximity = true)
        j.tap("Connect to $name", timeoutMs = 6_000)
        j.tap("Skip", timeoutMs = 10_000)
        j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
        j.step("B1", "real (Home + return after the socket's 30 s idle close; the process stays)") {
            mac.mark()
            j.bHomeAndReturn(32_000)
            j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 10_000)
            mac.await("labs: trusted ${bq(phone)} via trusted", 15_000)
            mac.await("authed ${bq(phone)} via Wi-Fi from ")
            mac.assertNo("^NEW DEVICE|labs: prompt ", 1_500)
        }
    }

    @Test fun reconnectNewIp() { j.path("instant-pairing.reconnect-new-ip") } // the Mac's real LAN IPs (info.lan) stay reachable from the emulator: not automated
    @Test fun reconnectTailnet() { j.path("instant-pairing.reconnect-tailnet") } // real tailnet: manual
    @Test fun btFallback() { j.path("instant-pairing.bt-fallback") } // needs the chaos proxy in the controller: not automated

    @Test fun reconnectRefused() {
        j.path("instant-pairing.reconnect-refused")
        val mac = j.helper("--fake-proximity")
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.tap("Connect to $name", timeoutMs = 6_000)
        j.tap("Skip", timeoutMs = 10_000)
        j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
        val note = "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again."
        j.step("B1", "faked: stdin revoke") {
            mac.mark(); mac.stdin("revoke")
            mac.await("all devices revoked")
            j.tap("Device: $name", substring = true)
            j.expect(note, timeoutMs = 8_000)
        }
        j.step("B2") {
            j.device.pressBack()
            j.bHomeAndReturn(1_500)
            mac.await("labs: denied \\(revoked\\) ", 15_000)
            j.tap("Device: $name", substring = true, timeoutMs = 8_000)
            j.expect(note, timeoutMs = 8_000)
            mac.assertNo("labs: prompt ", 500)
        }
    }

    @Test fun sameWindowSecondPhone() { j.path("instant-pairing.same-window-second-phone") } // needs a second phone key in one test: not automated
}
