package dev.mote.remote.journeys

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/setup-phone.md */
@RunWith(AndroidJUnit4::class)
class SetupPhoneJourney {
    @get:Rule val j = JourneyRule()

    private val adapterOn = "faked: adapter on (TestSeam.adapter), FakeTransport for HID"
    private val gateAsk = "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on."
    private val gateOff = "Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue."
    private val gateSettings = "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices in Settings, then come back."
    private val wifiNote = "Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point."
    private val btPerms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)

    private fun launch(supported: Boolean = true, enabled: Boolean = true) =
        aAdapter(supported, enabled).also { j.launch(sweepPort = A_NO_HELPER_PORT, transport = aHidFake(), adapter = it) }

    private fun findMac() {
        j.expect("Connect to your Mac", timeoutMs = 8_000)
        j.expect("Use Bluetooth instead (no helper)")
    }

    private fun toGate() {
        findMac()
        j.tap("Use Bluetooth instead (no helper)")
        j.expect("How do you want to connect?")
    }

    @Test fun main() {
        j.path("setup-phone")
        j.step("S1", "faked: the harness installs the debug APK (gradle connectedDevAndroidTest) instead of Chrome + package installer") {
            if (j.ctx.packageName != "dev.mote.remote.dev") throw AssertionError("installed package is ${j.ctx.packageName}")
        }
        launch()
        j.step("S2") {
            findMac()
            j.expect("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.")
            j.expect("New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.")
            if (j.aSystemShown("Allow", 800)) throw AssertionError("a permission dialog was shown at launch")
        }
        j.step("S3", "faked: no helper on the swept port, Bonjour off (TestSeam.noBonjour)") {
            j.aExpect("Mote Helper not found on this network", timeoutMs = 12_000)
            j.expect("Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:")
            j.expect("curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash")
            j.expect("or download it from tusharpandey13.github.io/mote")
            j.expect("Retry"); j.expect("Enter address")
        }
        j.step("S4") {
            j.tap("Use Bluetooth instead (no helper)")
            j.expect("How do you want to connect?")
            j.expect(gateAsk); j.expect("Connect with Bluetooth"); j.expect("Connect to your Mac instead"); j.expect(wifiNote)
        }
        var asked = listOf<String>()
        j.step("S5") {
            j.tap("Connect with Bluetooth")
            if (!j.systemShown("Allow", 6_000)) throw AssertionError("no permission dialog")
        }
        j.step("S6", adapterOn) {
            asked = j.aAllowAll()
            if (asked.isEmpty()) throw AssertionError("no permission dialog answered")
            (btPerms + Manifest.permission.POST_NOTIFICATIONS).forEach { if (!j.aPerm(it)) throw AssertionError("$it not granted (dialogs: $asked)") }
            j.aRemote()
            j.expect("Device: Choose a device. Tap to choose")
        }
    }

    @Test fun btOff() {
        j.path("setup-phone.bt-off")
        val a = launch(enabled = false)
        toGate()
        j.tap("Connect with Bluetooth")
        j.step("B1", "faked: adapter off (the emulator's real adapter is on, so Android may skip its enable dialog)") {
            j.aAllowAll()
            j.aDismissEnableDialog()
            j.expect(gateOff, timeoutMs = 6_000); j.expect("Turn on Bluetooth"); j.expect("Connect to your Mac instead")
        }
        j.step("B2", "faked: adapter flow → enabled = true") { a.value = a.value.copy(enabled = true); j.aRemote() }
        j.step("B3", "faked: adapter flow → enabled = false") { a.value = a.value.copy(enabled = false); j.expect("Turn on Bluetooth", timeoutMs = 6_000) }
    }

    @Test fun deniedOnce() {
        j.path("setup-phone.denied-once")
        launch()
        toGate()
        j.tap("Connect with Bluetooth")
        j.step("B1") {
            if (j.aDenyAll().isEmpty()) throw AssertionError("no permission dialog")
            j.expect(gateAsk); j.expect("Connect with Bluetooth"); j.expectNot("Open settings")
        }
        j.step("B2", "faked: adapter on") {
            j.tap("Connect with Bluetooth")
            if (j.aAllowAll().isEmpty()) throw AssertionError("no permission dialog on the second request")
            j.aRemote()
        }
    }

    @Test fun deniedTwice() {
        j.path("setup-phone.denied-twice")
        launch()
        toGate()
        j.step("B1") {
            j.tap("Connect with Bluetooth"); j.aDenyAll()
            j.tap("Connect with Bluetooth"); j.aDenyAll()
            j.expect(gateSettings, timeoutMs = 6_000); j.expect("Open settings")
        }
        j.step("B2") {
            j.tap("Open settings")
            val ok = j.device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.android.settings")), 6_000)
            if (ok != true) throw AssertionError("Settings app not shown")
        }
        j.step("B3", "faked: grantRuntimePermission stands in for the Settings toggle; adapter on") {
            j.grant(*btPerms)
            j.device.pressBack()
            j.aRemote()
        }
    }

    @Test fun notifDenied() {
        j.path("setup-phone.notif-denied")
        launch()
        toGate()
        j.tap("Connect with Bluetooth")
        j.step("B1", "faked: adapter on") {
            val asked = j.aAnswerPermissions { !aIsNotificationDialog(it) }
            if (asked.none(::aIsNotificationDialog)) throw AssertionError("no Notifications dialog (dialogs: $asked)")
            j.aRemote()
            btPerms.forEach { if (!j.aPerm(it)) throw AssertionError("$it not granted") }
            if (j.aPerm(Manifest.permission.POST_NOTIFICATIONS)) throw AssertionError("POST_NOTIFICATIONS granted")
        }
    }

    @Test fun unsupported() {
        j.path("setup-phone.unsupported")
        launch(supported = false)
        j.step("B1", "faked: adapter unsupported") {
            toGate()
            j.expect("This phone has no Bluetooth adapter Mote can use.")
            j.expectNot("Connect with Bluetooth"); j.expectNot("Turn on Bluetooth")
            j.expect("Connect to your Mac")
        }
        j.step("B2") {
            j.tap("Connect to your Mac")
            j.expect("Connect to your Mac", timeoutMs = 6_000); j.expect("Use Bluetooth instead (no helper)")
            if (j.aSystemShown("Allow", 800)) throw AssertionError("a permission dialog was shown")
        }
    }

    @Test fun backToWifi() {
        j.path("setup-phone.back-to-wifi")
        launch()
        toGate()
        j.step("B1") {
            j.tap("Connect to your Mac instead")
            j.expect("Use Bluetooth instead (no helper)", timeoutMs = 6_000)
            j.expect("Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.")
            if (j.aSystemShown("Allow", 800)) throw AssertionError("a permission dialog was shown")
        }
    }

    @Test fun legacyPermitted() {
        j.path("setup-phone.legacy-permitted")
        j.grant(*btPerms)
        val a = launch()
        j.step("B1", "faked: adapter on; permissions pre-granted") {
            j.aRemote()
            j.expect("Device: Choose a device. Tap to choose")
            j.expectNot("Connect to your Mac")
        }
        j.step("B2", "faked: adapter") { a.value = a.value.copy(enabled = false); j.expect("Turn on Bluetooth", timeoutMs = 6_000) }
    }

    @Test fun api28to30() {
        j.path("setup-phone.api-28-30")
        j.manual("B1", "needs an API 28-30 emulator image (the harness is API 35)")
    }
}
