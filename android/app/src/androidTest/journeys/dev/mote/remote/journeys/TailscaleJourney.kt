package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.net.Net
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/tailscale.md. Most steps need a Tailscale seam (installed app, VPN link, MagicDNS): not automated. */
@RunWith(AndroidJUnit4::class)
class TailscaleJourney {
    @get:Rule val j = JourneyRule()

    private val seed = "faked: saved host seeded by instant pairing + Net.addAddress(hostId, \"mac.example.ts.net\"); no Tailscale app on the emulator"

    /** Instant-pairs the helper, skips the Bluetooth offer and adds a tailnet name to the saved host. Returns the Mac name. */
    private fun seedTailnetHost(mac: Helper): String {
        val name = mac.info().getString("n")
        j.launch(fakeProximity = true)
        j.tap("Connect to $name", timeoutMs = 6_000)
        j.tap("Skip", timeoutMs = 10_000)
        j.expect("Device: $name via ${Ctl.link}. Tap to choose", timeoutMs = 8_000)
        val id = Net.saved.value.first().hostId
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Net.addAddress(id, "mac.example.ts.net") }
        if (Net.saved.value.first().tailnet != "mac.example.ts.net") throw AssertionError("seeding the tailnet address failed")
        return name
    }

    @Test fun main() { j.path("tailscale") } // needs TestSeam.tailscale (installed app, CONNECT_VPN, VPN link, DNS): not automated
    @Test fun notNow() { j.path("tailscale.not-now") } // same seam

    @Test fun notInstalled() {
        j.path("tailscale.not-installed")
        val mac = j.helper("--fake-proximity")
        val name = seedTailnetHost(mac)
        j.step("B1", seed) {
            j.wifi(false)
            j.tap("Device: $name", substring = true, timeoutMs = 8_000)
            j.tap("Use $name")
            j.expect("Tailscale is off", timeoutMs = 5_000)
            j.expect("Turn it on to reach $name.")
            j.expect("Tailscale isn't installed on this phone.")
            j.expect("Get Tailscale"); j.expect("Not now")
        }
    }

    @Test fun openFails() { j.path("tailscale.open-fails") } // needs TestSeam.tailscale: not automated

    @Test fun lanAvailable() {
        j.path("tailscale.lan-available")
        val mac = j.helper("--fake-proximity")
        val name = seedTailnetHost(mac)
        j.step("B1", seed) {
            j.tap("Device: $name", substring = true, timeoutMs = 8_000)
            j.tap("Use $name")
            Thread.sleep(1_000)
            j.expectNot("Tailscale is off")
            j.expect("Device: $name via ${Ctl.link}. Tap to choose", timeoutMs = 8_000)
        }
        j.step("B2", seed) {
            j.wifi(false)
            j.tap("Device: $name", substring = true, timeoutMs = 8_000)
            j.tap("Use $name")
            j.expect("Tailscale is off", timeoutMs = 5_000)
        }
    }

    // Off Wi-Fi the emulator's mobile data still reaches 10.0.2.2, so the socket goes Ready on the LAN
    // address ("via Wi-Fi") and the not-Ready Tailscale badge never shows: needs a network seam. Not automated.
    @Test fun launchNoPrompt() { j.path("tailscale.launch-no-prompt") }

    @Test fun helperRefuses() {
        j.path("tailscale.helper-refuses")
        j.manual("B1", "needs a real tailnet peer (the emulator's peer is 127.0.0.1)")
        j.manual("B2", "needs a real tailnet peer")
    }

    @Test fun enterAddress() { j.path("tailscale.enter-address") } // needs TestSeam.tailscale (VPN up): not automated
}
