package dev.mote.remote.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.mote.remote.diag.Events
import dev.mote.remote.labs.context.NpNotifier

/**
 * `mediaPlayback` foreground service (Android 14+: needs FOREGROUND_SERVICE_MEDIA_PLAYBACK) that holds the Mac's
 * now-playing notification while something plays over Mote Helper. It keeps the process, and through
 * [dev.mote.remote.net.Net.holdOpen] the helper socket, alive with the app in the background or the screen off.
 * [NpNotifier] starts it when a notification is shown and stops it when playback ends, the Mac disconnects, the user
 * turns the setting off, or playback has been paused for [dev.mote.remote.labs.context.NpNotifPolicy.PAUSED_STOP_MS].
 */
class NpService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NpNotifier.current
        if (n == null) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NpNotifier.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) // type ignored below API 29
        NpNotifier.serviceState(true)
        Events.log("lifecycle", "service", "state" to "np-started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NpNotifier.serviceState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Events.log("lifecycle", "service", "state" to "np-stopped")
        super.onDestroy()
    }

    companion object {
        /** Starts it from the foreground, or from the background while the app still holds the socket open. Denied by the system: the notification still posts, without the service. */
        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, NpService::class.java))
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException (API 31+)
                Events.log("lifecycle", "service", "state" to "np-denied")
            }
        }

        fun stop(c: Context) { c.stopService(Intent(c, NpService::class.java)) }
    }
}
