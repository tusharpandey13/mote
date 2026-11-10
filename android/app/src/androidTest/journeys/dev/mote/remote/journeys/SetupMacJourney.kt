package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/journeys/setup-mac.md. Mac-side: only the helper stand-in (S4) runs here; installer steps are
 * `static:` (e2e/web/tests/install.spec.ts), Setup window / menu / TCC are manual (no Mac UI in tests).
 */
@RunWith(AndroidJUnit4::class)
class SetupMacJourney {
    @get:Rule val j = JourneyRule()

    @Test fun main() {
        j.path("setup-mac")
        j.step("S4", "faked: dry-run headless helper stands in for the installed app") {
            val mac = j.helper()
            mac.await("instant pairing: on \\(key [0-9a-f]{8}\\)")
            mac.from = 0; mac.await("Mote Helper starting \\(dry run\\) on .+\\.local:${mac.port}$")
            mac.from = 0; mac.await("hostId [0-9a-f]{16}, tailscale connections refused$")
            mac.from = 0; mac.await("wifi: listening on :${mac.port}$")
            val n = mac.info().getString("n")
            if (n.isBlank()) throw AssertionError("LABS PAIR INFO without a Mac name")
            mac.from = 0; mac.assertNo("PAIRING CODE", 1_000)
        }
    }
}
