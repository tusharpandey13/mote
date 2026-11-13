package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/mac-update.md. Installer steps are `static:` (install.spec.ts); Mac UI / TCC / Keychain are manual. */
@RunWith(AndroidJUnit4::class)
class MacUpdateJourney {
    @get:Rule val j = JourneyRule()

    /** P1: first-run connect to a --fake-proximity helper; no Bluetooth step (adapter unsupported). */
    private fun connected(vararg flags: String): Pair<Helper, String> {
        val mac = j.helper("--fake-proximity", *flags)
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true, transport = aHidFake(), adapter = aAdapter(supported = false))
        j.aConnect(name)
        j.aRemote(12_000)
        j.expect("Device: $name via ${Ctl.link}. Tap to choose", timeoutMs = 8_000)
        return mac to name
    }

    @Test fun main() {
        j.path("mac-update")
        val (mac, name) = connected()
        j.step("S1", "faked: stdin-control `tailscale on` stands in for the menu click (menu lines headless-unobservable)") {
            mac.mark(); mac.stdin("tailscale on")
            mac.await("tailscale connections allowed$")
            j.appEvent("transport", "connect.ready")   // UP-7: the phone was Ready before the helper went away
        }
        j.step("S4", "faked: stand-in helper stopped by the controller (SIGTERM)") {
            j.appMark()
            mac.stop()
            j.appEvent("transport", "connect.dropped")   // UP-7: the update restart drops the link; connect.ready follows the restart (helper/tests/restart.mjs, RC-17)
            j.tap("Device: $name via ${Ctl.link}. Tap to choose")
            j.expect("Devices")
            j.expect("Connecting…", timeoutMs = 10_000)
        }
    }

    @Test fun tailscaleOff() {
        j.path("mac-update.tailscale-off")
        val (mac, name) = connected("--allow-tailscale")
        j.step("B1", "faked: stdin-control `tailscale off` stands in for the menu click") {
            mac.mark(); mac.stdin("tailscale off")
            mac.await("tailscale connections refused$")
            Thread.sleep(1_500)
            j.tap("Device: $name via ${Ctl.link}. Tap to choose")
            j.expect("Connected · ${Ctl.link}", timeoutMs = 6_000)
        }
    }
}
