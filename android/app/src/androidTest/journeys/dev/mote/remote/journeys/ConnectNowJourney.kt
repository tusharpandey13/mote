package dev.mote.remote.journeys

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/connect-now.md (legacy 6-digit code pairing, as the code does it now). */
@RunWith(AndroidJUnit4::class)
class ConnectNowJourney {
    @get:Rule val j = JourneyRule()

    private val btSeam = "faked: adapter + transport seams (Bluetooth chosen and ready, so the remote and the Devices sheet are reachable)"
    private val addrField = "mac.tailnet.ts.net or 100.x.y.z"
    private val codeField = "6-digit code"
    private val notOpen = "Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu."

    /** P1: Bluetooth path ready (fake adapter + transport, permissions granted), app on the remote. */
    private fun toRemote() {
        j.grant(*B_BT_PERMS)
        if (Build.VERSION.SDK_INT >= 33) j.grant(Manifest.permission.POST_NOTIFICATIONS)
        j.launch(transport = FakeTransport(), adapter = MutableStateFlow(TestSeam.Adapter(supported = true, enabled = true)))
        j.bToBluetoothRemote()
    }

    /** S1-S2 + S4: Devices › More options › Pair with code › Enter address. */
    private fun toAddress(waitNoMacs: Boolean = false) {
        j.step("S1", btSeam) {
            toRemote()
            j.tap("Device: Choose a device. Tap to choose")
            j.expect("Devices"); j.expect("Mote reconnects to the selected device while the app is open.")
            j.expect("Add a Mac"); j.expect("More options")
        }
        j.step("S2") {
            j.tap("More options"); j.tap("Pair with code")
            j.expect("On the Mac, open the Mote Helper menu and choose Pair new device. Then pick the Mac here, or scan its QR with the camera and tap Open in the Mote app.")
            j.expect("Looking for Macs on this Wi-Fi…")
            j.expect("Enter address")
            j.expect("No Mote Helper yet? Get it at tusharpandey13.github.io/mote")
        }
        if (waitNoMacs) j.step("S3") {
            j.expect("No Macs found. Guest and hotel Wi-Fi often block this. Try Enter address, Tailscale or Bluetooth.", timeoutMs = 35_000)
            j.expect("Scan again")
        }
        j.step("S4") {
            j.tap("Enter address")
            j.expect("For Tailscale, use the Mac's MagicDNS name or its 100.x address, and turn on Allow Tailscale connections in the Mote Helper menu. A LAN IP or name.local works too.")
            j.expect(addrField); j.expect("Connect")
        }
    }

    private fun code(mac: Helper): String = Regex("(\\d{6})").find(mac.await("^PAIRING CODE: \\d{6}"))!!.value

    private fun pairCode(c: String) { j.bType(codeField, c); j.tap("Pair") }

    @Test fun main() {
        j.path("connect-now")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        val phone = j.phoneName()
        toAddress(waitNoMacs = true)
        var name = ""
        j.step("S5") {
            j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
            j.expect("Pair with ", substring = true, timeoutMs = 8_000)
            name = j.visibleTexts().first { it.startsWith("Pair with ") && it != "Pair with code" }.removePrefix("Pair with ")
            j.expect("Enter the 6-digit code shown in the Mote Helper pairing window on the Mac.")
            j.expect(codeField); j.expect("Pair")
            j.expectNot(notOpen)
        }
        j.step("S6") { pairCode(code) }
        j.step("S7") {
            mac.await("paired ${bq(phone)} \\(Wi-Fi\\)", 10_000)
            mac.await("authed ${bq(phone)} via Wi-Fi from ", 10_000)
        }
        j.step("S8") {
            j.expectGone(codeField, timeoutMs = 8_000)
            j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
            j.expect("WI-FI")
        }
    }

    @Test fun found() { j.path("connect-now.found") } // NSD can't cross the emulator NAT; needs a Discovery seam: not automated

    @Test fun wrongCode() {
        j.path("connect-now.wrong-code")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        toAddress()
        j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
        j.expect(codeField, timeoutMs = 8_000)
        j.step("B1") {
            pairCode(if (code == "000000") "111111" else "000000")
            mac.await("pairing: wrong code from \\S+", 10_000)
            j.expect("Wrong code. Check the code in the Mote Helper window.", timeoutMs = 8_000)
        }
        j.step("B2") {
            pairCode("12345")
            j.expect("Enter the 6-digit code shown on the Mac.")
        }
    }

    @Test fun rate() {
        j.path("connect-now.rate")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        toAddress()
        j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
        j.expect(codeField, timeoutMs = 8_000)
        val wrong = if (code == "000000") "111111" else "000000"
        j.step("B1") {
            repeat(5) {
                pairCode(wrong)
                mac.await("pairing: wrong code from \\S+", 10_000)
                j.expect("Wrong code. Check the code in the Mote Helper window.", timeoutMs = 8_000)
            }
            pairCode(wrong)
            j.expect("Too many attempts. Try again in ", substring = true, timeoutMs = 8_000)
            val t = j.visibleTexts().first { it.startsWith("Too many attempts. Try again in ") }
            val n = Regex("in (\\d+) s\\.$").find(t)?.groupValues?.get(1)?.toInt() ?: throw AssertionError("bad copy: $t")
            if (n !in 1..60) throw AssertionError("retryAfter $n not in 1..60")
        }
    }

    @Test fun closed() {
        j.path("connect-now.closed")
        val mac = j.helper()
        toAddress()
        j.step("B1") {
            j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
            j.expect(codeField, timeoutMs = 8_000)
            j.expect(notOpen)
        }
        j.step("B2") {
            pairCode("123456")
            j.expect(notOpen, timeoutMs = 8_000)
        }
        var code = ""
        j.step("B3", "faked: stdin pair → stdout PAIRING CODE (window/menu headless-unobservable)") {
            mac.stdin("pair"); code = code(mac)
        }
        j.step("B4") {
            pairCode(code)
            mac.await("paired ${bq(j.phoneName())} \\(Wi-Fi\\)", 10_000)
        }
    }

    @Test fun noAnswer() { j.path("connect-now.no-answer") } // needs the chaos proxy: not automated

    @Test fun unreachable() {
        j.path("connect-now.unreachable")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        toAddress()
        j.step("B1") {
            j.bType(addrField, "10.0.2.3:${mac.port}"); j.tap("Connect")
            j.expect("Couldn't reach 10.0.2.3. Same Wi-Fi as the Mac? Is Mote Helper running?", timeoutMs = 10_000)
        }
        j.step("B3") {
            j.bType(addrField, "bad host!"); j.tap("Connect")
            j.expect("Enter a name like mac.tailnet.ts.net or an address like 100.101.102.103.")
        }
        j.step("B2") {
            j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
            j.expect(codeField, timeoutMs = 8_000)
            mac.stop()
            pairCode(code)
            j.expect("Couldn't reach 10.0.2.2. Same Wi-Fi as the Mac? Is Mote Helper running?", timeoutMs = 10_000)
        }
    }

    @Test fun knownAddress() {
        j.path("connect-now.known-address")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        toAddress()
        j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
        j.expect(codeField, timeoutMs = 8_000)
        pairCode(code)
        j.expectGone(codeField, timeoutMs = 8_000)
        j.step("B1") {
            j.tap("Device: ", substring = true, timeoutMs = 8_000)
            j.tap("More options"); j.tap("Pair with code"); j.tap("Enter address")
            j.bType(addrField, "10.0.2.2:${mac.port}"); j.tap("Connect")
            j.expectGone(addrField, timeoutMs = 8_000)
            j.expectNot(codeField)
        }
    }

    @Test fun tailnetAddress() {
        j.path("connect-now.tailnet-address")
        toAddress()
        j.step("B1", "faked: no Tailscale app → \"Get Tailscale\" variant; VPN-up continuation needs a Tailscale seam") {
            j.bType(addrField, "mac.example.ts.net"); j.tap("Connect")
            j.expect("Tailscale is off", timeoutMs = 5_000)
            j.expect("Turn it on to reach mac.example.ts.net.")
            j.expect("Tailscale isn't installed on this phone.")
            j.expect("Get Tailscale")
        }
    }

    @Test fun deepLink() {
        j.path("connect-now.deep-link")
        val mac = j.helper("--open-pairing")
        val code = code(mac)
        j.launch()
        j.expect("Connect to your Mac", timeoutMs = 8_000)
        j.step("B1") {
            j.shell("am start -a android.intent.action.VIEW -d mote://pair?h=10.0.2.2:${mac.port}&code=$code dev.mote.remote.dev")
            Thread.sleep(3_000)
            j.expect("Connect to your Mac", timeoutMs = 5_000)
            j.expectNot("Pair with 10.0.2.2")
            j.expectNot(codeField)
        }
    }
}
