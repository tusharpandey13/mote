package dev.mote.remote.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.data.Prefs
import dev.mote.remote.e2e.Call
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/macros.md */
@RunWith(AndroidJUnit4::class)
class MacrosJourney {
    @get:Rule val j = JourneyRule()

    private val subtitle = "Tap to run. Spaces opens a desktop carousel. Use Edit to change or star."
    private val keyField = "Type a key (letter, digit, symbol)"

    private fun macros() = j.tap("Macros")

    @Test fun main() {
        j.path("macros")
        val (mac, _) = j.cLinkHelper()
        j.step("S1") {
            macros()
            j.expect(subtitle); j.expect("Edit")
            j.expect("Spaces: choose a desktop")
            val tiles = listOf("Return" to "⏎", "Spotlight" to "⌘space", "Escape" to "esc", "Mission Control" to "⌃↑", "Screenshot area" to "⇧⌘4",
                "Open Safari" to "5 steps", "Lock" to "⌃⌘Q", "Force quit" to "⌥⌘esc", "Hide others" to "⌥⌘H")
            for ((n, h) in tiles) { j.expect(n); j.expect(h) }
            j.expect("New macro")
            if (j.compose.onAllNodes(hasContentDescription("Favourite"), useUnmergedTree = true).fetchSemanticsNodes().size != 2)
                throw AssertionError("Spaces and Return should be starred")
        }
        j.step("S2") {
            j.cClick("New macro")
            j.expect("Name"); j.expect("ACTION"); j.expect("Shortcut"); j.expect("SHORTCUT"); j.expect("Click at point"); j.expect(" + key")
            for (m in listOf("⌃ ctrl", "⌥ opt", "⇧ shift", "⌘ cmd")) j.expect(m)
            j.expect(keyField)
            for (k in listOf("⏎", "esc", "⇥", "space", "⌫", "del", "↑", "↓", "←", "→", "home", "end", "pg up", "pg dn", "F1", "F12")) j.expect(k)
            j.expect("★ Favourite"); j.expect("Confirm before run"); j.expect("Add a name and a key")
        }
        j.step("S3") {
            j.cType("Name", "Area shot")
            j.cClick("⌘ cmd"); j.cClick("⇧ shift")
            j.cType(keyField, "4")
            j.expect("⇧⌘4"); j.expect("Save")
        }
        j.step("S4") {
            j.cClick("★ Favourite"); j.cClick("Save")
            j.expectGone(keyField)
            j.expect("Area shot")
            val json = runBlocking { Prefs.macrosJson(j.ctx).first() } ?: throw AssertionError("macros_json not written")
            for (part in listOf("\"v\":3", "\"name\":\"Area shot\"", "\"star\":true", "\"mods\":[\"shift\",\"cmd\"]", "\"key\":\"4\""))
                if (part !in json) throw AssertionError("macros_json lacks $part: $json")
        }
        j.step("S5") {
            j.tap("Media")
            j.expect("Return"); j.expect("Area shot"); j.expect("Add favourite")
        }
        j.step("S6") {
            mac.mark(); j.cClick("Area shot")
            mac.await("DRY key usage=33 mods=10 action=tap")
            j.expect("Brightness up")
        }
    }

    @Test fun confirm() {
        j.path("macros.confirm")
        val (mac, _) = j.cLinkHelper()
        macros()
        j.step("B1") { mac.mark(); j.cClick("Lock"); j.expect("Tap again"); mac.assertNo("DRY key", 500) }
        j.step("B2") { j.cClick("Lock"); mac.await("DRY key usage=20 mods=9 action=tap"); j.expectGone("Tap again") }
        j.step("B3") {
            mac.mark(); j.cClick("Lock"); j.expect("Tap again")
            j.cAdvance(2_100)
            j.expectGone("Tap again"); mac.assertNo("DRY key", 500)
            j.cClick("Lock"); j.expect("Tap again"); mac.assertNo("DRY key", 500)
        }
    }

    @Test fun clickat() {
        j.path("macros.clickat")
        val (mac, _) = j.cLinkHelper()
        macros()
        j.step("B1") {
            j.cClick("New macro"); j.cType("Name", "Join call")
            j.cClick("Click at point")
            j.expect("Click point 50% across, 50% down")
            j.expect("Tap or drag where to click on the main display: 50% across, 50% down.")
        }
        j.step("B2") {
            j.compose.onNode(hasContentDescription("Click point", substring = true)).performTouchInput { click(Offset(width * 0.725f, height * 0.185f)) }
            j.expect("Tap or drag where to click on the main display: 72% across, 18% down.")
        }
        j.step("B3", "real (point values depend on the dev Mac's display: compute from `CGDisplayBounds`)") {
            j.cClick("★ Favourite"); j.cClick("Save"); j.expect("Join call"); j.expect("click 72%, 18%")
            mac.mark(); j.cClick("Join call")
            mac.await("DRY clickAt \\(-?[0-9.]+, -?[0-9.]+\\) b=left count=1")
        }
    }

    @Test fun bt() {
        j.path("macros.bt")
        // a click macro saved earlier over the helper (B4)
        runBlocking {
            Prefs.setMacrosJson(j.ctx, """{"v":3,"macros":[{"id":"spaces","name":"Spaces","icon":"spaces","star":true,"confirm":false,"pointer":false,"kind":"spaces","steps":[]},""" +
                """{"id":"jc","name":"Join call","icon":"apps","star":false,"confirm":false,"pointer":false,"kind":"steps","steps":[{"t":"clickAt","x":0.72,"y":0.18}]}]}""")
        }
        val fake = cHid()
        j.cLaunchBt(fake)
        macros()
        j.step("B1", C_BT) { j.cClick("New macro"); j.expect("Click at point needs a Mac with Mote Helper (Wi-Fi or Tailscale). On Bluetooth hosts it is skipped.") }
        j.step("B2", C_BT) { j.cClick("Click at point"); j.expect(" + key"); j.expectNot("Tap or drag where to click", substring = true) }
        j.step("B3", C_BT) {
            j.cType("Name", "Area shot"); j.cClick("⌘ cmd"); j.cClick("⇧ shift"); j.cType(keyField, "4")
            j.cClick("Save"); j.expect("Area shot")
            fake.clear(); j.cClick("Area shot")
            j.cAwaitCall(fake, "SetMods + TapAwait(4, 10)") { it == listOf(Call.SetMods(0, 0), Call.TapAwait("4", 10)) }
        }
        j.step("B4", "$C_BT; Toast not asserted (UNDEFINED)") {
            fake.clear(); j.cClick("Join call"); j.cAdvance(200)
            val c = fake.settle()
            if (c != listOf(Call.SetMods(0, 0))) throw AssertionError("click step must be skipped on BT: $c")
        }
    }

    @Test fun pointer() {
        j.path("macros.pointer")
        val (mac, _) = j.cLinkHelper()
        macros()
        val banner = "Turn on drag lock and move to select; turn it off to capture."
        j.step("B1") {
            mac.mark(); j.cClick("Screenshot area")
            mac.await("DRY key usage=33 mods=10 action=tap")
            j.cAdvance(100) // the macro's 10 ms step delays run on the Compose clock
            j.expect(banner); j.expect("Escape"); j.expect("Done")
        }
        j.step("B2") {
            mac.mark(); j.cClick("Escape")
            mac.await("DRY key usage=41 mods=0 action=tap")
            j.expect(subtitle); j.expectNot(banner)
        }
        j.step("B3") {
            j.cClick("Screenshot area"); j.cAdvance(100); j.expect(banner)
            j.cClick("Done"); j.expect(subtitle); j.expectNot(banner)
        }
    }

    @Test fun edit() {
        j.path("macros.edit")
        j.cLaunchBt(cHid())
        macros()
        j.step("B1") {
            j.cClick("Edit"); j.expect("Done")
            j.expect("Tap a tile to edit it; tap a star to add or remove it from Media.")
            j.expect("Edit Spotlight")
        }
        j.step("B2") {
            val before = j.compose.onAllNodes(hasContentDescription("Favourite"), useUnmergedTree = true).fetchSemanticsNodes().size
            j.compose.onAllNodes(hasContentDescription("Not favourite") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
            j.cWait("3 favourites", 3_000) { j.compose.onAllNodes(hasContentDescription("Favourite"), useUnmergedTree = true).fetchSemanticsNodes().size == before + 1 }
            val json = runBlocking { Prefs.macrosJson(j.ctx).first() } ?: ""
            if (!Regex("\"id\":\"spotlight\",\"name\":\"Spotlight\",\"icon\":\"search\",\"star\":true").containsMatchIn(json)) throw AssertionError("Spotlight star not persisted: $json")
        }
        j.step("B3") {
            j.cClick("Edit Open Safari")
            j.expect("Edit macro"); j.expect("This macro has 5 steps. Only its name and options can be edited here."); j.expect("Delete macro")
        }
        j.step("B4") { j.tap("Delete macro"); j.expectGone("Edit Open Safari") }
        j.step("B5") {
            j.cClick("Edit Spaces")
            j.expect("Edit macro"); j.expect("★ Favourite"); j.expect("Confirm before run"); j.expect("Save")
            j.expectNot("Action"); j.expectNot("Delete macro")
            j.device.pressBack(); j.expectGone("Edit macro")
        }
        j.step("B6") { j.cClick("Done"); j.expect(subtitle) }
    }

    @Test fun invalid() {
        j.path("macros.invalid")
        j.cLaunchBt(cHid())
        macros()
        j.step("B1") {
            j.cClick("New macro"); j.cClick("Add a name and a key"); j.expect("Add a name and a key")
            j.cType("Name", "Nameonly"); j.cClick("Add a name and a key")
            j.expect("Add a name and a key"); j.expect(keyField) // sheet still open
            val json = runBlocking { Prefs.macrosJson(j.ctx).first() } ?: ""
            if ("Nameonly" in json) throw AssertionError("an invalid macro was saved: $json")
        }
    }

    @Test fun mediaFull() {
        j.path("macros.media-full")
        j.cLaunchBt(cHid())
        macros()
        fun star(vararg names: String) {
            j.cClick("Edit")
            // stars are in grid order; star the tile right after each name's edit cap by index among "Not favourite"
            for (n in names) {
                val idx = listOf("Spotlight", "Escape", "Mission Control", "Screenshot area", "Open Safari", "Lock", "Force quit", "Hide others")
                    .filter { it !in starred }.indexOf(n)
                val notFav = hasContentDescription("Not favourite") and hasClickAction()
                val before = j.compose.onAllNodes(notFav).fetchSemanticsNodes().size
                j.compose.onAllNodes(notFav)[idx].performSemanticsAction(SemanticsActions.OnClick)
                starred += n
                // the star flips after the DataStore round-trip, not on waitForIdle: wait for it, or the next index is stale
                j.compose.waitUntil(5_000) { j.compose.onAllNodes(notFav).fetchSemanticsNodes().size == before - 1 }
            }
            j.cClick("Done")
        }
        j.step("B2") {
            star("Spotlight", "Escape")
            j.tap("Media")
            for (d in listOf("Return", "Spotlight", "Escape")) j.expect(d)
            j.expectNot("Add favourite")
        }
        j.step("B1") {
            macros(); star("Mission Control", "Hide others")
            j.tap("Media")
            for (d in listOf("Return", "Spotlight", "Escape", "Mission Control")) j.expect(d)
            j.expectNot("Hide others"); j.expectNot("Add favourite")
        }
    }

    private val starred = mutableSetOf<String>()

    @Test fun unicode() {
        j.path("macros.unicode")
        val (mac, _) = j.cLinkHelper()
        macros()
        j.step("B1") {
            mac.mark(); j.cClick("Open Safari")
            mac.await("DRY key usage=44 mods=8 action=tap")
            j.cAdvance(300)
            mac.await("DRY text \"Safari\"")
            j.cAdvance(200)
            mac.await("DRY key usage=40 mods=0 action=tap")
        }
    }

    @Test fun unicodeBt() {
        j.path("macros.unicode")
        val fake = cHid()
        j.cLaunchBt(fake)
        macros()
        j.step("B2", C_BT) {
            fake.clear(); j.cClick("Open Safari")
            j.cAwaitCall(fake, "Open Safari steps") { it.size >= 4 }
            val want = listOf(Call.SetMods(0, 0), Call.TapAwait("space", 8), Call.TypeText("Safari"), Call.TapAwait("return", 0))
            if (fake.settle() != want) throw AssertionError("expected $want, got ${fake.calls}")
        }
    }
}
