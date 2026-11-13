package dev.mote.remote.journeys

import android.Manifest
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.data.UiPrefs
import kotlinx.coroutines.runBlocking
import dev.mote.remote.labs.context.NpNotifPlan
import dev.mote.remote.labs.context.NpNotifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android media notification (core, Settings › Media notification; no Labs gate): the Mac's now playing over Mote
 * Helper (U3), a mediaPlayback foreground service while it plays (U2), transport-only over Bluetooth (U1), public on the lock screen (U20).
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
        d.split("NotificationRecord").drop(1).firstOrNull { "pkg=${j.ctx.packageName}" in it && "channel=nowplaying" in it && "id=2147483647" !in it }
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
        j.dlConnect(mac, name)

        // Notifications are not allowed yet (Android 13+): nothing is posted, the panel asks in context, Diagnostics says why.
        j.step("N1") {
            j.expect("Fake Song", timeoutMs = 8_000)
            j.expect("Show what is playing, with controls, in the notification shade and on the lock screen?")
            waitUntil("plan blocked") { (NpNotifier.plan.value as? NpNotifPlan.Hide)?.reason == "no-permission" }
            if (posted() != null) throw AssertionError("a notification was posted without permission")
            j.appEvent("notification", "blocked", "reason" to "no-permission")   // DU-18
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
            j.appEvent("notification", "show", "kind" to "helper")   // DU-14
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
        j.step("N3b") { // U2 + U20: a mediaPlayback foreground service holds it; the channel shows on the lock screen
            val svc = j.shell("dumpsys activity services ${j.ctx.packageName}/dev.mote.remote.service.NpService")
            if ("isForeground=true" !in svc || "types=0x00000002" !in svc) throw AssertionError("NpService is not a mediaPlayback FGS:\n${svc.take(1500)}")
            if ("vis=PUBLIC" !in svc) throw AssertionError("notification not public on the lock screen:\n${svc.take(1500)}")
        }
        j.step("N4") {
            j.appMark(); mac.mark()
            val ev0 = mac.evIndex   // input/summary is emitted per 5 s window, possibly before the marks below
            val tc = controller().transportControls
            mac.mark(); tc.skipToNext(); mac.await("DRY consumer next")
            mac.mark(); tc.skipToPrevious(); mac.await("DRY consumer prev")
            mac.mark(); tc.pause(); mac.await("DRY consumer play") // playing -> pause is a toggle
            mac.mark(); tc.play(); mac.assertNo("DRY consumer play", 1_500) // already playing: a stale Play is not sent
            mac.mark(); tc.seekTo(150_000); mac.await("DRY labs seek via=fake to=150\\.0")
            for (n in listOf("next", "prev", "pause")) j.appEvent("notification", "action", "name" to n, "sent" to true)   // DU-16
            j.appEvent("notification", "action", "name" to "play", "sent" to false)   // the stale Play is not sent
            j.appEvent("notification", "action", "name" to "seek", "sent" to true)
            mac.event("input", "summary", since = ev0)
        }
        j.step("N5") {
            // The notification's buttons are broadcasts to NpActionReceiver (the PendingIntents' targets). Tapping them in
            // the shade is MANUAL (media controls live in the Quick Settings panel on Android 13+, not driveable reliably).
            val send = { a: String -> j.ctx.sendBroadcast(android.content.Intent(a).setClass(j.ctx, dev.mote.remote.labs.context.NpActionReceiver::class.java)) }
            mac.mark(); send(NpNotifier.ACTION_NEXT); mac.await("DRY consumer next")
            mac.mark(); send(NpNotifier.ACTION_PREV); mac.await("DRY consumer prev")
            mac.mark(); send(NpNotifier.ACTION_PLAY); mac.await("DRY consumer play")
            j.appEvent("notification", "action", "name" to "playpause", "sent" to true)   // DU-16
        }
        j.step("N6") {
            // The Mac goes away: the notification is removed and the session released.
            j.appMark()
            mac.stop()
            waitUntil("notification removed on disconnect", 70_000) { posted() == null }
            j.appEvent("notification", "hide", "reason" to "disconnected", "wasShown" to true)   // DU-20
            waitUntil("session released") { "MoteNowPlaying" !in sessions() }
        }
    }

    @Test fun settingOff() {
        j.path("np-notification.setting-off")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlConnect(mac, name)
        waitUntil("notification posted") { posted() != null }
        j.step("B1") {
            runBlocking { UiPrefs.update(j.ctx) { it.copy(mediaNotification = false) } }
            waitUntil("notification removed when the setting is turned off") { posted() == null }
            waitUntil("plan: setting-off") { (NpNotifier.plan.value as? NpNotifPlan.Hide)?.reason == "setting-off" }
        }
    }

    /** U2: the foreground service keeps the socket (and the notification) beyond the 30 s background close. */
    @Test fun backgroundKeepsPlaying() {
        j.path("np-notification.background")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlConnect(mac, name)
        waitUntil("notification posted") { posted() != null }
        j.step("B2") {
            j.appMark()
            j.closeActivity()
            Thread.sleep(40_000) // past Net's 30 s background close
            j.appEvent("lifecycle", "foreground", "on" to false)   // DU-20: the app left the screen, the service keeps it
            j.noAppEvent("notification", "hide")
            if (posted() == null) throw AssertionError("notification gone after 40 s in the background while playing")
            mac.mark(); controller().transportControls.skipToNext(); mac.await("DRY consumer next")
        }
    }

    /** U2: paused for the timeout (shortened here) stops the service and removes the notification. */
    @Test fun pausedTimeout() {
        j.path("np-notification.paused")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        NpNotifier.pausedStopMs = 4_000
        val (mac, name) = j.dlHelper("--fake-nowplaying")
        j.dlConnect(mac, name)
        waitUntil("notification posted") { posted() != null }
        j.step("B4") {
            // The fake Mac doesn't toggle on play/pause: deliver the paused state the way the helper would.
            val now = System.currentTimeMillis()
            dev.mote.remote.labs.LabsBus.deliver("""{"t":"labs.np","enabled":true,"source":"mediaremote","seek":["mediaremote"],"now":$now,"np":{"title":"Fake Song","artist":"Mote Labs","app":"Music","duration":300,"elapsed":30,"rate":0,"at":$now}}""")
            waitUntil("paused state reaches the notification") { controller().playbackState?.state == PlaybackState.STATE_PAUSED }
            waitUntil("removed after the paused timeout", 20_000) { posted() == null }
            waitUntil("plan: idle-timeout") { (NpNotifier.plan.value as? NpNotifPlan.Hide)?.reason == "idle-timeout" }
            j.appEvent("notification", "hide", "reason" to "idle-timeout", "wasShown" to true)   // DU-20
            val svc = j.shell("dumpsys activity services ${j.ctx.packageName}/dev.mote.remote.service.NpService")
            if ("isForeground=true" in svc) throw AssertionError("service still foreground:\n${svc.take(800)}")
        }
    }

    /** U1: Bluetooth only: "Mote · Bluetooth" with transport buttons and no metadata. */
    @Test fun bluetoothOnly() {
        j.path("np-notification.bluetooth")
        j.grant(Manifest.permission.POST_NOTIFICATIONS)
        j.dlBluetoothOnly()
        j.step("B3", "faked: TestSeam.transport = FakeTransport (HID caps)") {
            waitUntil("bluetooth notification") { posted() != null }
            val n = posted()!!
            if ("Mote · Bluetooth" !in n) throw AssertionError("title:\n$n")
            if (controller().playbackState!!.actions and PlaybackState.ACTION_SKIP_TO_NEXT == 0L) throw AssertionError("no next action")
            if (controller().metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) != null) throw AssertionError("metadata over Bluetooth")
        }
    }
}
