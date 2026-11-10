package dev.mote.remote.journeys

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Call
import dev.mote.remote.net.Net
import dev.mote.remote.net.WsStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/spaces.md */
@RunWith(AndroidJUnit4::class)
class SpacesJourney {
    @get:Rule val j = JourneyRule()

    private val hint = "Jump straight to a desktop. On the Mac, turn on Switch to Desktop 1-9 in System Settings, Keyboard Shortcuts, Mission Control. Until then Mote steps with ⌃← and ⌃→."
    private val sheetBody = "Swipe the row or tap a desktop to jump to it. The lit card is the desktop you are on."

    private fun openSheet() {
        j.tap("Macros")
        j.tap("Spaces: choose a desktop")
        j.expect(sheetBody)
    }

    private fun closeSheet() { j.tap("Close"); j.expectGone(sheetBody) }

    private fun right(n: Int) = List(n) { Call.Tap("right", 1) }

    @Test fun main() {
        j.path("spaces")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.step("S1", C_BT) {
            j.expect("Spaces"); j.expect("swipe or tap a desktop")
            for (d in listOf("Desktop 1, current", "Desktop 2", "Desktop 3", "Desktop 4")) j.expect(d)
            j.expectNot("Desktop 5")
        }
        j.step("S2", C_BT) {
            fake.clear()
            j.cClick("Desktop 3")
            j.expect("Desktop 3, current")
            j.cAwaitCall(fake, "2 x Tap(right, ctrl)") { it == right(2) }
        }
        j.step("S3", C_BT) {
            fake.clear()
            j.cClick("Desktop 1")
            j.expect("Desktop 1, current")
            j.cAwaitCall(fake, "2 x Tap(left, ctrl)") { it == List(2) { Call.Tap("left", 1) } }
        }
        j.step("S4", C_BT) {
            openSheet()
            j.expect("Close"); j.expect("OVER BLUETOOTH"); j.expect("NUMBERED")
            j.expect(hint); j.expect("Done"); j.expect("Remind me later")
        }
        j.step("S5", C_BT) {
            j.tap("Remind me later"); j.expectGone(hint)
            closeSheet(); openSheet(); j.expect(hint)
        }
        j.step("S6", C_BT) {
            j.tap("Done"); j.expectGone(hint)
            closeSheet(); openSheet(); j.expectNot(hint)
        }
    }

    @Test fun ctrln() {
        j.path("spaces.ctrln")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.tap("Menu"); j.tap("Settings")
        j.tap("Use control 1 to 9 shortcuts")
        j.device.pressBack(); j.expect("Brightness up")
        j.step("B1", C_BT) {
            fake.clear(); j.cClick("Desktop 3")
            j.cAwaitCall(fake, "Tap(3, ctrl)") { it == listOf(Call.Tap("3", 1)) }
            Thread.sleep(500)
            if (fake.calls != listOf(Call.Tap("3", 1))) throw AssertionError("extra calls: ${fake.calls}")
        }
        j.step("B2", C_BT) { openSheet(); j.expectNot(hint); closeSheet() }
        j.step("B3", C_BT) {
            j.tap("Menu"); j.tap("Settings")
            j.cSlider("Desktops", 11, 16)
            j.expect("12 on the Mac. Sets the cards in the Spaces carousel over Bluetooth.")
            j.device.pressBack(); j.tap("Media"); j.expect("Brightness up")
            j.cScrollRow(11)
            fake.clear(); j.cClick("Desktop 12")
            j.cAwaitCall(fake, "9 x Tap(right, ctrl)", 8_000) { it == right(9) }
        }
    }

    @Test fun helper() {
        j.path("spaces.helper")
        val (mac, _) = j.cLinkHelper("--fake-spaces", "6")
        j.step("B1", "real (dry-run, --fake-spaces)") { j.expect("Desktop 1, current", timeoutMs = 8_000) }
        j.step("B2") {
            for (i in 2..3) j.expect("Desktop $i")
            if (j.cCount("Not visited yet") < 2) throw AssertionError("unvisited desktops should be dashed \"Not visited yet\"")
            if (!j.cClickable("Desktop 1, current")) throw AssertionError("desktop 1 card not clickable")
        }
        j.step("B3") {
            mac.mark(); j.cClick("Desktop 1, current")
            mac.await("DRY spaces goto=1 from=1 via=step display=fake-1")
            mac.assertNo("DRY key usage=79", 800)
        }
        j.step("B4") {
            // spec = code: the placeholder has no click action (spaces-D1)
            if (j.compose.onAllNodes(hasText("Desktop 3") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
                throw AssertionError("Desktop 3 placeholder became clickable (spaces-D1 fixed? update the spec)")
            mac.mark(); mac.assertNo("DRY spaces goto=3", 800)
        }
        j.step("B6") {
            openSheet()
            j.expect("WITH THE MOTE HELPER"); j.expect("THUMBNAILS")
            j.expect("The helper saves a picture of each desktop when you visit it. It needs Screen Recording permission on the Mac.")
            j.expectNot(hint)
        }
    }

    @Test fun displays() {
        j.path("spaces.displays")
        val (mac, _) = j.cLinkHelper("--fake-spaces", "6,3")
        j.step("B1") { j.expect("Display 1 of 2", timeoutMs = 8_000); j.expect("Display: Fake Display 1. Choose display") }
        j.step("B2") { j.tap("Display: Fake Display 1. Choose display"); j.expect("Fake Display 1 (active)"); j.expect("Fake Display 2") }
        j.step("B3") {
            j.tap("Fake Display 2")
            j.expect("Display 2 of 2"); j.expect("Display: Fake Display 2. Choose display")
            j.expectNot("Desktop 4")
            if (j.cCount("Not visited yet") != 3) throw AssertionError("3 dashed cards expected, got ${j.cCount("Not visited yet")}")
        }
        j.step("B4", "faked: stdin-control `display 2` (log `[mote] spaces: fake active display 2`)") {
            mac.stdin("display 2"); mac.await("spaces: fake active display 2")
            j.tap("Display: Fake Display 2. Choose display")
            j.expect("Fake Display 2 (active)")
            j.device.pressBack()
        }
        j.step("B5") {
            openSheet()
            j.expect("Each display has its own desktops. The row follows the display you're using; tap the chip to pick another. The helper saves a picture of each desktop when you visit it (needs Screen Recording permission).")
        }
    }

    @Test fun nocapture() {
        j.path("spaces.nocapture")
        j.cLinkHelper()
        j.step("B1", "real (dry-run never captures)") {
            j.expect("Not visited yet", timeoutMs = 8_000)
            if (j.cClickable("Desktop 1, current")) throw AssertionError("a card is tappable without thumbnails")
        }
    }

    @Test fun paused() {
        j.path("spaces.paused")
        val (mac, _) = j.cLinkHelper("--fake-spaces", "6")
        j.expect("Desktop 1, current", timeoutMs = 8_000)
        j.step("B1", "faked: stdin-control `pause`") { mac.mark(); mac.stdin("pause"); mac.await("input paused") }
        j.step("B2") {
            j.cWait("paused", 5_000) { (Net.wsStatus.value as? WsStatus.Ready)?.paused == true }
            mac.mark(); j.cClick("Desktop 1, current"); mac.assertNo("DRY spaces goto", 2_000)
        }
    }
}
