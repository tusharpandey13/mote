package dev.mote.remote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.mote.remote.MainActivity
import dev.mote.remote.R
import dev.mote.remote.hid.Consumer
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.HidState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * connectedDevice foreground service: owns the HID registration so the link survives the
 * screen turning off. It stops itself when the app is in the background and no host is connected.
 */
class HidService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW))
        val n = notification(Hid.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
        dev.mote.remote.diag.Events.log("lifecycle", "service", "state" to "created")
        Hid.start(this)
        scope.launch { Hid.state.collect { nm.notify(NOTIF_ID, notification(it)) } }
        scope.launch {
            combine(Hid.state, Hid.inForeground) { s, fg -> fg || s.isConnected || s.connecting }
                .collect { keep -> if (!keep) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dev.mote.remote.diag.Events.log("lifecycle", "service", "state" to "command", "action" to (intent?.action?.substringAfterLast('.') ?: "start"))
        when (intent?.action) {
            ACTION_STOP -> { Hid.disconnect(); stopSelf() }
            ACTION_PLAY -> Hid.consumer(Consumer.PLAY_PAUSE)
            else -> Hid.start(this) // idempotent; re-registers after Bluetooth comes back on
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dev.mote.remote.diag.Events.log("lifecycle", "service", "state" to "destroyed")
        scope.cancel()
        Hid.stop()
        super.onDestroy()
    }

    private fun notification(s: HidState): Notification {
        val text = when {
            s.connected != null -> getString(R.string.notif_connected, s.connected.name)
            s.selected != null -> getString(R.string.notif_searching, s.selected.name)
            else -> getString(R.string.notif_idle)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, label: Int) = Notification.Action.Builder(
            null, getString(label),
            PendingIntent.getService(this, a.hashCode(), Intent(this, HidService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mote)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(ACTION_PLAY, R.string.play_pause))
            .addAction(action(ACTION_STOP, R.string.stop))
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.mote.remote.STOP"
        private const val ACTION_PLAY = "dev.mote.remote.PLAY"

        fun hasConnectPermission(c: Context) = Build.VERSION.SDK_INT < 31 ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(c: Context) {
            if (hasConnectPermission(c)) c.startForegroundService(Intent(c, HidService::class.java))
        }
    }
}
