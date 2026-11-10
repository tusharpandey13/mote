package dev.mote.remote.journeys

import android.Manifest
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.labs.Labs
import dev.mote.remote.labs.context.NpNotifPlan
import dev.mote.remote.labs.context.NpNotifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1b: the Android media notification for the Mac's now playing (Labs › Smart remote).
 * Asserted through `dumpsys notification` / `dumpsys media_session`; the buttons are a MediaController on the
 * app's own session token (the callbacks the system UI would call), and the helper's `DRY ...` lines show what
 * reached the Mac.
 */
@RunWith(AndroidJUnit4::class)
class NpNotificationJourney {
    @get:Rule val j = JourneyRule()

    @org.junit.After fun closeUi() { j.closeActivity() }

    private fun notif() = j.shell("dumpsys notification --noredact")
    private fun posted() = notif().let { d ->
        d.split("NotificationRecord").drop(1).firstOrNull { "pkg=${j.ctx.packageName}" in it && "channel=nowplaying" in it }
    }
    private fun sessions() = j.shell("dumpsys media_session")

    private fun waitUntil(what: String, ms: Long = 15_000, f: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (runCatching(f).getOrDefault(false)) return; Thread.sleep(250) }
        throw AssertionError("timed out waiting for: $what\n--- notification:\n${notif().lines().filter { "pkg=${j.ctx.packageName}" in it || "nowplaying" in it }.take(10).joinToString("\n")}")
    }

    private fun controller() = MediaController(j.ctx, NpNotifier.token ?: throw AssertionError("no media session"))

    @Test fun main() {
        j.path("np-notification")
        j.shell("cmd statusbar collapse")
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlLabs(Labs.SMART_REMOTE)
        j.dlConnect(mac, name)

        // Notifications are not allowed yet (Android 13+): nothing is posted, the panel asks in context, Diagnostics says why.
        j.step("N1") {
            j.expect("Fake Song", timeoutMs = 8_000)
            j.expect("Show what is playing, with controls, in the notification shade and on the lock screen?")
            waitUntil("plan blocked") { (NpNotifier.plan.value as? NpNotifPlan.Hide)?.reason == "no-permission" }
            if (posted() != null) throw AssertionError("a notification was posted without permission")
        }
        j.step("N2") {
            // Allow, in context: the system dialog, then the notification appears.
            j.tap("Allow notifications")
            // The permission dialog's own button (the panel's "Allow" is app text, matched by text it would be stale).
            (j.device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("com.android.permissioncontroller:id/permission_allow_button")), 8_000)
                ?: throw AssertionError("no notification permission dialog; window: " + j.device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.Button")).map { "${it.applicationPackage}|${it.resourceName}|${it.text}" } + " " + j.shell("dumpsys window | grep -i mCurrentFocus").trim())).click()
            waitUntil("notification posted") { posted() != null }
            val n = posted()!!
            if ("Fake Song" !in n) throw AssertionError("title missing:\n$n")
            if ("Mote Labs" !in n) throw AssertionError("artist missing:\n$n")
            j.expectGone("Show what is playing, with controls, in the notification shade and on the lock screen?")
        }
        j.step("N3") {
            val s = sessions()
            if ("MoteNowPlaying" !in s) throw AssertionError("no MoteNowPlaying session:\n${s.take(1500)}")
            val st = controller().playbackState!!
            if (st.state != PlaybackState.STATE_PLAYING) throw AssertionError("state ${st.state}")
            if (st.actions and PlaybackState.ACTION_SEEK_TO == 0L) throw AssertionError("no seek action (no seek bar): ${st.actions}")
            if (st.actions and PlaybackState.ACTION_SKIP_TO_NEXT == 0L || st.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS == 0L) throw AssertionError("no skip actions")
            val md = controller().metadata!!
            if (md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) != "Fake Song") throw AssertionError("metadata title")
            if (md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) != 300_000L) throw AssertionError("duration")
        }
        j.step("N4") {
            val tc = controller().transportControls
            mac.mark(); tc.skipToNext(); mac.await("DRY consumer next")
            mac.mark(); tc.skipToPrevious(); mac.await("DRY consumer prev")
            mac.mark(); tc.pause(); mac.await("DRY consumer play") // playing -> pause is a toggle
            mac.mark(); tc.play(); mac.assertNo("DRY consumer play", 1_500) // already playing: a stale Play is not sent
            mac.mark(); tc.seekTo(150_000); mac.await("DRY labs seek via=fake to=150\\.0")
        }
        j.step("N5") {
            // The notification's buttons are broadcasts to NpActionReceiver (the PendingIntents' targets). Tapping them in
            // the shade is MANUAL (media controls live in the Quick Settings panel on Android 13+, not driveable reliably).
            val send = { a: String -> j.ctx.sendBroadcast(android.content.Intent(a).setClass(j.ctx, dev.mote.remote.labs.context.NpActionReceiver::class.java)) }
            mac.mark(); send(NpNotifier.ACTION_NEXT); mac.await("DRY consumer next")
            mac.mark(); send(NpNotifier.ACTION_PREV); mac.await("DRY consumer prev")
            mac.mark(); send(NpNotifier.ACTION_PLAY); mac.await("DRY consumer play")
        }
        j.step("N6") {
            // The Mac goes away: the notification is removed and the session released.
            mac.stop()
            waitUntil("notification removed on disconnect", 70_000) { posted() == null }
            waitUntil("session released") { "MoteNowPlaying" !in sessions() }
        }
    }

    @Test fun labOff() {
        j.path("np-notification.lab-off")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlLabs(Labs.SMART_REMOTE)
        j.dlConnect(mac, name)
        waitUntil("notification posted") { posted() != null }
        j.step("B1") {
            j.dlLabs(Labs.SMART_REMOTE, on = false)
            waitUntil("notification removed when the lab is turned off") { posted() == null }
        }
    }

    @Test fun backgroundTimeout() {
        j.path("np-notification.background")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlLabs(Labs.SMART_REMOTE)
        j.dlConnect(mac, name)
        waitUntil("notification posted") { posted() != null }
        j.step("B2") {
            // The app closes the helper socket 30 s after it leaves the screen (no foreground service): the session ends with it.
            j.closeActivity()
            waitUntil("notification removed after the socket closed", 60_000) { posted() == null }
        }
    }

    @Test fun bluetoothOnly() {
        j.path("np-notification.bluetooth")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        j.dlLabs(Labs.SMART_REMOTE)
        j.dlBluetoothOnly()
        j.step("B3", "faked: TestSeam.transport = FakeTransport (HID caps)") {
            j.expect("SMART REMOTE", timeoutMs = 8_000)
            waitUntil("plan: no helper") { (NpNotifier.plan.value as? NpNotifPlan.Hide)?.reason == "no-helper" }
            Thread.sleep(1_500)
            if (posted() != null) throw AssertionError("a notification over Bluetooth only")
        }
    }
}
