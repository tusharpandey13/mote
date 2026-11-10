package dev.mote.remote.journeys

import android.bluetooth.BluetoothManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/first-run-bt.md */
@RunWith(AndroidJUnit4::class)
class FirstRunBtJourney {
    @get:Rule val j = JourneyRule()

    private val proxSeam = "faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity)"
    private val sweepSeam = "faked: discovery = LAN sweep of 10.0.2.0/24 on the helper port (Bonjour off)"
    private lateinit var mac: Helper
    private var name = ""
    private lateinit var adapter: MutableStateFlow<TestSeam.Adapter>

    /** Helper with [flags] (+ --fake-proximity), app launched with the seams of P1. */
    private fun start(vararg flags: String, enabled: Boolean = true) {
        mac = j.helper("--fake-proximity", *flags)
        name = mac.info().getString("n")
        adapter = aAdapter(enabled = enabled)
        j.launch(fakeProximity = true, transport = aHidFake(), adapter = adapter)
    }

    /** Precondition for the branches: connected over Wi-Fi, Bluetooth offer on screen. */
    private fun toOffer(vararg flags: String, enabled: Boolean = true) {
        start(*flags, enabled = enabled)
        j.aConnect(name)
        j.expect(A_BT_OFFER, timeoutMs = 10_000)
    }

    /** Continue → allow Nearby devices → allow visibility. */
    private fun continueAndAllow() {
        j.tap("Continue")
        if (j.aAllowAll().isEmpty()) throw AssertionError("no Nearby devices dialog")
        j.aDiscoverable(allow = true)
    }

    private fun btName(): String = j.ctx.getSystemService(BluetoothManager::class.java).adapter.name

    private fun failPath(path: String, reason: String, text: (String) -> String) {
        j.path(path)
        toOffer("--fake-bt-pair-fail", reason)
        continueAndAllow()
        j.step("B1", "faked: --fake-bt-pair-fail $reason") {
            mac.await("bt: failed \\($reason\\)", 15_000)
            j.expect(text(name), timeoutMs = 8_000)
            j.expect("Try again"); j.expect("Skip for now")
        }
    }

    @Test fun main() {
        j.path("first-run-bt")
        mac = j.helper("--fake-proximity", "--fake-bt-pair")
        name = mac.info().getString("n")
        val phone = j.phoneName()
        j.step("S1") {
            j.launch(fakeProximity = true, transport = aHidFake(), adapter = aAdapter())
            j.expect("Connect to your Mac", timeoutMs = 8_000)
            j.expect("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.")
            j.expect("Use Bluetooth instead (no helper)")
            if (j.aSystemShown("Allow", 800)) throw AssertionError("a permission dialog was shown at launch")
        }
        j.step("S2", sweepSeam) { j.expect("Connect to $name", timeoutMs = 8_000); j.expect(name) }
        j.step("S3", proxSeam) {
            j.tap("Connect to $name")
            mac.await("labs: trusted \\Q$phone\\E via nearby")
            mac.await("labs: new device \\Q$phone\\E via nearby \\(k:[0-9a-f]{32}\\)")
            mac.from = 0
            mac.await("^NEW DEVICE: \\{\"id\":\"k:[0-9a-f]{32}\",\"name\":\"\\Q$phone\\E\",\"via\":\"nearby\"\\}")
        }
        j.step("S4") {
            j.expect("Connected over Wi-Fi", timeoutMs = 10_000)
            j.expect(A_BT_OFFER)
            j.expect("Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.")
            j.expect("Continue"); j.expect("Skip")
        }
        j.step("S5") {
            j.tap("Continue")
            if (!j.aSystemShown(".*nearby devices.*", 6_000)) throw AssertionError("no Nearby devices permission dialog")
        }
        j.step("S6") {
            val asked = j.aAllowAll()
            if (asked.any(::aIsNotificationDialog)) throw AssertionError("BtStep asked for notifications: $asked")
            if (!j.aSystemShown(".*(visible|discoverable).*", 8_000)) throw AssertionError("no visibility dialog")
        }
        mac.mark()
        j.step("S7", "faked: --fake-bt-pair (no IOBluetooth, no Android pairing dialog)") {
            j.aDiscoverable(allow = true, timeoutMs = 2_000)
            val bt = btName()
            mac.await("labs: trusted \\Q$phone\\E via trusted", 10_000)
            mac.await("bt: pair '\\Q$bt\\E' for \\Q$phone\\E", 10_000)
            mac.await("bt: paired 02:00:00:00:00:01", 10_000)
        }
        j.step("S8", "faked: --fake-bt-pair (bt.info address 02:00:00:00:00:00)") { j.expect("Wi-Fi and Bluetooth are both set up.", timeoutMs = 8_000) }
        j.step("S9") { j.aRemote(8_000); j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000) }
        j.step("S10") { mac.assertNo("^NEW DEVICE", 1_000) }
    }

    @Test fun skipBt() {
        j.path("first-run-bt.skip-bt")
        toOffer("--fake-bt-pair")
        mac.mark()
        j.step("B1") {
            j.tap("Skip")
            j.aRemote(); j.expect("Device: $name via Wi-Fi. Tap to choose", timeoutMs = 8_000)
            if (j.aSystemShown("Allow", 800)) throw AssertionError("a permission dialog was shown")
            mac.assertNo("bt: pair", 1_000)
        }
    }

    @Test fun untick() {
        j.path("first-run-bt.untick")
        toOffer("--fake-bt-pair")
        mac.mark()
        j.step("B1") { j.tap(A_BT_OFFER); j.expect("Done"); j.expectGone("Skip") }
        j.step("B2") { j.tap("Done"); j.aRemote(); mac.assertNo("bt: pair", 1_000) }
    }

    @Test fun later() {
        j.path("first-run-bt.later")
        toOffer("--fake-bt-pair")
        j.tap("Skip"); j.aRemote()
        j.step("B1") {
            j.tap("Device: $name via Wi-Fi. Tap to choose")
            j.expect("Devices"); j.expect("Set up Bluetooth")
            j.expectNot(A_BT_OFFER); j.expectNot("Pair Bluetooth")
        }
        j.step("B2", "faked: sweep port") {
            j.tap("Add a Mac")
            j.aExpect("Mote Helper not found on this network", timeoutMs = 12_000)
            j.expectNot("Connect to $name")
        }
    }

    @Test fun btVisibleDenied() {
        j.path("first-run-bt.bt-visible-denied")
        toOffer("--fake-bt-pair")
        j.tap("Continue"); j.aAllowAll()
        mac.mark()
        j.step("B1") {
            j.aDiscoverable(allow = false)
            j.expect("The phone has to be visible for a moment so $name can find it.", timeoutMs = 6_000)
            j.expect("Try again"); j.expect("Skip for now")
            mac.assertNo("bt: pair", 1_000)
        }
    }

    @Test fun btPermDenied() {
        j.path("first-run-bt.bt-perm-denied")
        toOffer("--fake-bt-pair")
        j.step("B1") {
            j.tap("Continue")
            if (j.aDenyAll().isEmpty()) throw AssertionError("no permission dialog")
            j.expect("Mote needs Nearby devices to pair over Bluetooth.", timeoutMs = 6_000)
            j.expect("Try again"); j.expect("Skip for now")
        }
    }

    @Test fun btOff() {
        j.path("first-run-bt.bt-off")
        toOffer("--fake-bt-pair", enabled = false)
        j.step("B1", "faked: adapter off (the emulator's real adapter stays on)") {
            j.tap("Continue"); j.aAllowAll()
            j.aDismissEnableDialog()
            if (!j.aSystemShown(".*(visible|discoverable).*", 8_000)) throw AssertionError("no visibility dialog after the enable request")
        }
    }

    @Test fun btFailNotfound() = failPath("first-run-bt.bt-fail-notfound", "notfound") { "$it couldn't see this phone. Keep Bluetooth on and the phone visible, then try again." }
    @Test fun btFailTimeout() = failPath("first-run-bt.bt-fail-timeout", "timeout") { "Bluetooth pairing took too long. Try again." }
    @Test fun btFailRefused() = failPath("first-run-bt.bt-fail-refused", "refused") { "Bluetooth pairing was refused. Try again, or pair from the Mac's Bluetooth settings." }
    @Test fun btFailOther() = failPath("first-run-bt.bt-fail-other", "weird") { "Bluetooth pairing failed. Try again." }

    @Test fun btFailUnavailable() {
        failPath("first-run-bt.bt-fail-unavailable", "unavailable") { "Bluetooth is off or not allowed for Mote Helper on $it." }
        j.step("B2") { j.tap("Skip for now"); j.aRemote() }
    }

    @Test fun btNofake() {
        j.path("first-run-bt.bt-nofake")
        toOffer()
        continueAndAllow()
        j.step("B1") {
            mac.await("bt: failed \\(unavailable\\)", 15_000)
            j.expect("Bluetooth is off or not allowed for Mote Helper on $name.", timeoutMs = 8_000)
        }
    }

    @Test fun btRetry() {
        j.path("first-run-bt.bt-retry")
        toOffer("--fake-bt-pair-fail", "refused")
        continueAndAllow()
        mac.await("bt: failed \\(refused\\)", 15_000)
        j.expect("Try again", timeoutMs = 8_000)
        j.step("B1", "faked: --fake-bt-pair-fail refused") {
            j.tap("Try again")
            j.aDiscoverable(allow = true)
            mac.await("bt: pair '", 10_000)
        }
    }

    @Test fun btOnly() {
        j.path("first-run-bt.bt-only")
        start("--fake-bt-pair")
        j.step("B1") {
            j.tap("Use Bluetooth instead (no helper)")
            j.expect("How do you want to connect?"); j.expect("Connect with Bluetooth"); j.expect("Connect to your Mac instead")
        }
        j.step("B2", "faked: adapter on, FakeTransport") {
            j.tap("Connect with Bluetooth"); j.aAllowAll()
            j.aRemote(); j.expect("Device: Choose a device. Tap to choose")
        }
        j.step("B3") {
            j.tap("Device: Choose a device. Tap to choose")
            j.expect("Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.")
            j.tap("Pair new Bluetooth device")
            if (!j.aSystemShown(".*(visible|discoverable).*", 8_000)) throw AssertionError("no visibility dialog")
            j.aDiscoverable(allow = false, timeoutMs = 1_000)
        }
        j.manual("B4", "real Bluetooth HID bond from the Mac's Bluetooth settings")
        j.step("B5", "faked: sweep port + proximity") {
            j.tap("Add a Mac")
            j.aConnect(name)
            j.expect("Connected over Wi-Fi", timeoutMs = 10_000)
            j.expect(A_BT_OFFER)
        }
    }
}
