package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Call
import dev.mote.remote.e2e.Mods
import dev.mote.remote.e2e.Usage
import dev.mote.remote.labs.Labs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/open-tv.md */
@RunWith(AndroidJUnit4::class)
class OpenTvJourney {
    @get:Rule val j = JourneyRule()

    /** Works around a harness teardown crash (Bt.observe awaitClose on the test thread): dispose the UI first. */
    @org.junit.After fun dlCloseUi() { j.closeActivity() }


    private val FIELD = "Search YouTube or paste a link"
    private val PINS = listOf("YouTube", "Netflix", "Prime Video", "Disney+", "Twitch", "Spotify")
    private lateinit var mac: Helper

    /** P1-P4: helper (TV lab forced on unless [tv] false), phone linked, Labs › Open & TV on. */
    private fun setup(tv: Boolean = true) {
        val (m, name) = if (tv) j.dlHelper("--labs-tv") else j.dlHelper()
        mac = m
        j.dlLabs(Labs.OPEN_TV)
        j.dlConnect(mac, name)
        mac.mark()
    }

    private fun openSheet() { j.tap("Open on Mac"); j.expect(FIELD, timeoutMs = 5_000) }

    private fun search(q: String) { openSheet(); j.dlType(FIELD, q); j.tap("Open") }

    private fun expectTvPanel(sub: String) {
        j.expect("TV MODE", timeoutMs = 5_000); j.expect("Exit"); j.expect(sub)
        for (d in listOf("TV direction pad", "Up", "Down", "Left", "Right", "OK", "Back", "Home", "Search", "Volume down", "Play or pause", "Volume up")) j.expect(d)
    }

    @Test fun main() {
        j.path("open-tv")
        setup()
        j.step("S1") { j.expect("Open on Mac", timeoutMs = 5_000) }
        j.step("S2") {
            j.tap("Open on Mac")
            j.expect(FIELD, timeoutMs = 5_000); j.expect("PREVIEW"); j.expect("Paste")
            j.expect("Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak.")
            j.expect("QUICK ACTIONS"); j.expect("Close this tab"); j.expect("Exit fullscreen"); j.expect("Pause everything")
            j.expect("Open TV mode"); j.expect("Open TV mode on the Mac"); j.expect("PINNED"); j.expect("Edit")
            PINS.forEach { j.expect("Open $it") }
            j.expectNot("RECENT")
        }
        j.step("S3") { j.dlType(FIELD, "lofi beats"); j.expect("Open"); j.expectNot("Paste") }
        j.step("S4") {
            j.tap("Open")
            mac.await("DRY labs.tv open page=youtube url=https://www\\.youtube\\.com/tv#/search\\?q=lofi%20beats")
            j.expectGone(FIELD)
        }
        j.step("S5") { expectTvPanel("YouTube") }
        j.step("S6") { j.tap("Down"); mac.await("DRY labs.tv key down"); j.tap("OK"); mac.await("DRY labs.tv key ok") }
        j.step("S7") { j.tap("Back"); mac.await("DRY labs.tv key back"); j.tap("Play or pause"); mac.await("DRY labs.tv key playpause") }
        j.step("S8") { j.tap("Volume up"); mac.await("DRY consumer volup") }
        j.step("S9") { j.tap("Home"); mac.await("DRY labs.tv key home"); Thread.sleep(500); j.expect("YouTube") }
        j.manual("S10", "launcher window needs a real WKWebView (non-headless helper)")
        j.step("S11") { j.tap("Exit"); mac.await("DRY labs.tv exit"); j.expectGone("TV MODE"); j.expect("Play or pause") }
    }

    @Test fun pinBrowser() {
        j.path("open-tv.pin-browser")
        setup()
        j.step("B1") {
            openSheet(); j.tap("Open Netflix")
            mac.await("DRY labs.open.url mode=browser fullscreen=true url=https://www\\.netflix\\.com/browse")
            j.expectGone(FIELD); Thread.sleep(500); j.expectNot("TV MODE")
        }
    }

    @Test fun pinYoutube() {
        j.path("open-tv.pin-youtube")
        setup()
        j.step("B1") { openSheet(); j.tap("Open YouTube"); mac.await("DRY labs.tv open page=youtube url=https://www\\.youtube\\.com/tv$"); expectTvPanel("YouTube") }
    }

    @Test fun tvLauncher() {
        j.path("open-tv.tv-launcher")
        setup()
        j.step("B1") { openSheet(); j.tap("Open TV mode"); mac.await("DRY labs.tv open page=home url=home"); expectTvPanel("Home") }
        j.manual("B2", "launcher tiles live in a real WKWebView window (non-headless helper)")
    }

    @Test fun pasteLink() {
        j.path("open-tv.paste-link")
        setup()
        j.step("B1") { j.dlClip("Watch this: https://youtu.be/dQw4w9WgXcQ.") }
        j.step("B2") {
            openSheet(); j.tap("Paste")
            val t = j.dlFieldText(FIELD); if (t != "https://youtu.be/dQw4w9WgXcQ") throw AssertionError("field = '$t'")
            j.expect("Open")
        }
        j.step("B3") { j.tap("Open"); mac.await("DRY labs.tv open page=youtube url=https://www\\.youtube\\.com/tv#/watch\\?v=dQw4w9WgXcQ") }
    }

    private fun doShare() = j.shell("am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://www.youtube.com/watch?v=dQw4w9WgXcQ -n dev.mote.remote.dev/dev.mote.remote.labs.open.ShareTarget")

    @Test fun share() {
        j.path("open-tv.share")
        setup()
        j.step("B1", "faked: am start stands in for another app's share sheet") {
            val out = doShare(); if (out.contains("Error")) throw AssertionError(out)
        }
        j.step("B2") {
            j.expect(FIELD, timeoutMs = 5_000)
            val t = j.dlFieldText(FIELD); if (t != "https://www.youtube.com/watch?v=dQw4w9WgXcQ") throw AssertionError("field = '$t'")
            j.expect("Open"); mac.assertNo("DRY labs", 1_000)
        }
        j.step("B3") {
            j.tap("Open"); mac.await("DRY labs.tv open page=youtube url=https://www\\.youtube\\.com/tv#/watch\\?v=dQw4w9WgXcQ")
            j.tap("Exit"); j.expectGone("TV MODE"); openSheet(); j.expect("RECENT"); j.expect("youtube.com")
        }
    }

    @Test fun shareDismiss() {
        j.path("open-tv.share-dismiss")
        setup(); doShare(); j.expect(FIELD, timeoutMs = 5_000)
        j.step("B1") {
            j.tap("Close"); j.expectGone(FIELD); mac.assertNo("DRY labs", 1_000)
            openSheet(); val t = j.dlFieldText(FIELD); if (t.isNotEmpty()) throw AssertionError("field = '$t'")
        }
    }

    @Test fun shareTargetOff() {
        j.path("open-tv.share-target-off")
        val (m, name) = j.dlHelper("--labs-tv"); mac = m
        j.dlConnect(mac, name)
        j.step("B1") {
            Thread.sleep(1_000)
            val pm = j.ctx.packageManager
            val st = pm.getComponentEnabledSetting(android.content.ComponentName(j.ctx, "dev.mote.remote.labs.open.ShareTarget"))
            if (st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) throw AssertionError("ShareTarget enabled setting = $st")
            val q = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").setPackage(j.ctx.packageName), 0)
            if (q.isNotEmpty()) throw AssertionError("share targets still resolve: ${q.map { it.activityInfo.name }}")
            j.expectNot("Open on Mac")
            doShare() // the shell may still start the disabled alias (UNDEFINED); no sheet may open
            j.expectNot(FIELD)
        }
    }

    @Test fun recents() {
        j.path("open-tv.recents")
        setup(); search("lofi beats"); mac.await("DRY labs.tv open page=youtube"); j.tap("Exit"); j.expectGone("TV MODE")
        j.step("B1") { openSheet(); j.expect("RECENT"); j.expect("Clear"); j.expect("YouTube: lofi beats"); j.expect("youtube.com") }
        j.step("B2") { j.tap("YouTube: lofi beats"); mac.await("DRY labs.tv open page=youtube url=https://www\\.youtube\\.com/tv#/search\\?q=lofi%20beats") }
        j.step("B3") { j.tap("Exit"); j.expectGone("TV MODE"); openSheet(); j.tap("Clear"); j.expectGone("RECENT") }
    }

    @Test fun pinsEdit() {
        j.path("open-tv.pins-edit")
        setup(); openSheet()
        j.step("B1") { j.dlTapLast("Edit"); j.expect("Done"); PINS.forEach { j.expect("Remove $it") }; j.expect("Pin the link in the field"); j.expect("Pin a link") }
        j.step("B2") { j.tap("Pin the link in the field"); j.expect("Type or paste a link above, then tap + to pin it.") }
        j.step("B3") {
            j.dlType(FIELD, "example.com/x"); j.expect("Pin example.com"); j.tap("Pin the link in the field")
            j.expect("Remove example.com"); if (j.dlFieldText(FIELD).isNotEmpty()) throw AssertionError("field not cleared")
        }
        j.step("B4") { j.tap("Remove Spotify"); j.tap("Done"); j.expectNot("Open Spotify"); j.expect("Open YouTube"); j.expect("Open example.com") }
    }

    @Test fun badScheme() {
        j.path("open-tv.bad-scheme")
        setup()
        j.step("B1") {
            openSheet(); j.dlType(FIELD, "javascript:alert(1)"); j.tap("Open")
            j.expect("Only http and https links can be opened."); j.expect(FIELD); mac.assertNo("DRY labs", 1_000)
        }
    }

    @Test fun quickActions() {
        j.path("open-tv.quick-actions")
        setup(); openSheet()
        j.step("B1") { j.tap("Close this tab"); mac.await("DRY labs.open.action a=closeTab tv=false"); j.expect(FIELD) }
        j.step("B2") { j.tap("Exit fullscreen"); mac.await("DRY labs.open.action a=exitFullscreen tv=false") }
        j.step("B3") { j.tap("Pause everything"); mac.await("DRY labs.open.action a=pauseAll tv=false") }
    }

    @Test fun quickClosesTv() {
        j.path("open-tv.quick-closes-tv")
        setup(); openSheet(); j.tap("Open YouTube"); expectTvPanel("YouTube")
        j.step("B1") {
            j.tap("Search"); j.expect(FIELD, timeoutMs = 5_000); j.tap("Close this tab")
            mac.await("DRY labs.open.action a=closeTab tv=true"); mac.await("DRY labs.tv exit")
            j.tap("Close"); j.expectGone("TV MODE")
        }
    }

    @Test fun tvSearch() {
        j.path("open-tv.tv-search")
        setup(); openSheet(); j.tap("Open YouTube"); expectTvPanel("YouTube"); mac.mark()
        j.step("B1") { j.tap("Search"); j.expect(FIELD, timeoutMs = 5_000); mac.assertNo("DRY labs.tv key search", 1_500) }
    }

    @Test fun helperLabOff() {
        j.path("open-tv.helper-lab-off")
        setup(tv = false)
        j.step("B1") {
            search("lofi beats"); j.expectGone(FIELD); mac.assertNo("DRY labs", 1_500)
            j.expectNot("Turn on Labs › TV mode in the Mote Helper menu on the Mac.")
            openSheet(); j.expectNot("Turn on Labs › TV mode in the Mote Helper menu on the Mac.")
        }
    }

    @Test fun paused() {
        j.path("open-tv.paused")
        setup(); openSheet(); j.tap("Open YouTube"); expectTvPanel("YouTube")
        j.step("B1") { mac.stdin("pause"); mac.await("input paused") }
        j.step("B2") { j.tap("Up"); j.expect("Input is paused on the Mac.", timeoutMs = 5_000); mac.assertNo("DRY labs.tv key up", 500) }
        j.step("B3") {
            mac.stdin("resume"); mac.await("input resumed")
            j.tap("Up"); mac.await("DRY labs.tv key up"); j.expectGone("Input is paused on the Mac.")
        }
    }

    @Test fun bluetooth() {
        j.path("open-tv.bluetooth")
        j.dlLabs(Labs.OPEN_TV)
        val t = j.dlBluetoothOnly()
        val seam = "faked: TestSeam.transport FakeTransport (HID caps)"
        j.step("B1", seam) {
            openSheet(); j.expect("Needs Wi-Fi or Tailscale; connected over Bluetooth. Quick actions work over Bluetooth too.")
            j.expectNot("Open TV mode on the Mac")
        }
        j.step("B2", seam) { j.dlType(FIELD, "lofi beats"); j.tap("Open"); j.expect("Open & TV mode needs Mote Helper over Wi-Fi or Tailscale."); j.expect(FIELD) }
        j.step("B3", seam) {
            t.clear()
            j.tap("Close this tab"); j.tap("Exit fullscreen"); j.tap("Pause everything")
            val calls = t.settle()
            val want = listOf(Call.Tap("w", Mods.CMD), Call.Tap("esc", 0), Call.Consumer(Usage.PLAY_PAUSE))
            if (!calls.containsAll(want)) throw AssertionError("FakeTransport calls $calls, want $want")
        }
    }

    @Test fun unlinked() {
        j.path("open-tv.unlinked")
        val (m, name) = j.dlHelper("--labs-tv"); mac = m
        j.dlLabs(Labs.OPEN_TV)
        j.dlConnect(mac, name)
        j.step("B1") {
            mac.stop()
            j.dlWaitUnlinked()
            openSheet()
            j.expect("Paste"); j.expect("Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak.")
            j.expect("Open TV mode on the Mac") // caps stay HELPER while the helper socket reconnects; the cap is disabled (needsLink)
        }
    }
}
