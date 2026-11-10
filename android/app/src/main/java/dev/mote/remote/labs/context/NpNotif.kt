package dev.mote.remote.labs.context

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import dev.mote.remote.MainActivity
import dev.mote.remote.R
import dev.mote.remote.diag.Events
import dev.mote.remote.hid.Consumer
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.net.Net
import dev.mote.remote.service.NpService
import dev.mote.remote.net.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the media notification should be, decided from state only (no Android types). */
sealed interface NpNotifPlan {
    /**
     * [reason]: `setting-off` | `disconnected` | `nothing-playing` | `helper-np-off` (the Mac's helper has now playing
     * switched off) | `idle-timeout` (paused for [NpNotifPolicy.PAUSED_STOP_MS]) | `no-permission`.
     */
    data class Hide(val reason: String) : NpNotifPlan

    /** Bluetooth-only link: transport buttons ("Mote · Bluetooth"), no metadata and no state (U1). */
    data object BtOnly : NpNotifPlan

    data class Show(
        val title: String, val artist: String, val app: String, val playing: Boolean,
        val durationMs: Long, val positionMs: Long, val canSeek: Boolean, val artKey: String?,
    ) : NpNotifPlan {
        /** Same notification content apart from the position, which the session extrapolates by itself. */
        fun sameContent(o: Show) = copy(positionMs = 0) == o.copy(positionMs = 0)
    }
}

object NpNotifPolicy {
    /** Paused this long (U2): the foreground service stops and the notification goes. Playing again brings it back. */
    const val PAUSED_STOP_MS = 10 * 60_000L

    /**
     * Now playing and its notification are core (U3): [enabled] is the Settings switch (default on). Over a helper the
     * notification shows what the Mac plays; over Bluetooth only it is [NpNotifPlan.BtOnly] (U1). The permission is
     * checked last, so a Diagnostics row says "blocked" only when a notification would otherwise show.
     */
    fun plan(st: NpState, enabled: Boolean, overHelper: Boolean, linked: Boolean, allowed: Boolean, nowMs: Long, pausedExpired: Boolean = false): NpNotifPlan {
        if (!enabled) return NpNotifPlan.Hide("setting-off")
        if (!linked) return NpNotifPlan.Hide("disconnected")
        if (!overHelper) return if (allowed) NpNotifPlan.BtOnly else NpNotifPlan.Hide("no-permission")
        if (st.received && !st.enabled) return NpNotifPlan.Hide("helper-np-off")
        val np = st.np ?: return NpNotifPlan.Hide("nothing-playing")
        if (!np.playing && pausedExpired) return NpNotifPlan.Hide("idle-timeout")
        if (!allowed) return NpNotifPlan.Hide("no-permission")
        val pos = ((st.displayPosition(nowMs) ?: 0.0) * 1000).toLong().coerceAtLeast(0)
        return NpNotifPlan.Show(
            title = np.title.ifEmpty { np.app.ifEmpty { "Playing" } }, artist = np.artist, app = np.app, playing = np.playing,
            durationMs = (np.duration * 1000).toLong(), positionMs = pos, canSeek = st.canScrub && st.seek.isNotEmpty(), artKey = np.artKey,
        )
    }

    /** The Diagnostics row text for a plan, and whether it is a problem. */
    fun describe(p: NpNotifPlan?): Pair<String, Boolean> = when (p) {
        null -> "not started" to false
        is NpNotifPlan.Show -> "shown" to false
        NpNotifPlan.BtOnly -> "shown (Bluetooth: transport buttons only)" to false
        is NpNotifPlan.Hide -> when (p.reason) {
            "setting-off" -> "off (Settings › Media notification is off)" to false
            "disconnected" -> "off (not connected to a Mac)" to false
            "nothing-playing" -> "off (nothing is playing on the Mac)" to false
            "helper-np-off" -> "blocked: Now playing is off in the Mote Helper menu on the Mac" to true
            "idle-timeout" -> "off (paused for 10 minutes)" to false
            "no-permission" -> "blocked: notifications are not allowed for Mote" to true
            else -> p.reason to false
        }
    }
}

/**
 * The media notification (core, Settings › Media notification): a plain MediaSession + MediaStyle notification of
 * what the Mac plays over Mote Helper, or "Mote · Bluetooth" transport buttons over a Bluetooth-only link.
 * Over a helper it is held by [NpService], a `mediaPlayback` foreground service that runs while something plays
 * (and for up to [NpNotifPolicy.PAUSED_STOP_MS] paused), so it works on the lock screen and with the app in the
 * background; the helper socket is kept open meanwhile ([Net.holdOpen]). Over Bluetooth, HidService is the foreground
 * service. Its buttons go to the Mac through [Remote] / [SmartRemote], like the Media screen.
 */
object NpNotifier {
    private const val CHANNEL = "nowplaying"
    const val NOTIF_ID = 2
    const val ACTION_PREV = "dev.mote.remote.NP_PREV"
    const val ACTION_PLAY = "dev.mote.remote.NP_PLAY"
    const val ACTION_NEXT = "dev.mote.remote.NP_NEXT"

    private lateinit var app: Context
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val allowed = MutableStateFlow(true)
    private val pausedExpired = MutableStateFlow(false)
    private val _plan = MutableStateFlow<NpNotifPlan?>(null)
    /** The latest decision, for Diagnostics. */
    val plan: StateFlow<NpNotifPlan?> = _plan.asStateFlow()
    /** True after the user said no to the system dialog: the next "Allow" opens the app's notification settings. */
    val askedAndDenied = MutableStateFlow(false)
    /** Paused this long stops the service (tests shorten it). */
    @Volatile var pausedStopMs = NpNotifPolicy.PAUSED_STOP_MS

    private var session: MediaSession? = null
    private var art: Pair<String, Bitmap?>? = null
    /** What [NpService] puts in the foreground (null: nothing to show, the service stops). */
    @Volatile var current: Notification? = null
        private set
    @Volatile private var serviceUp = false
    /** The live session's token, for tests (a MediaController built from it drives the same callbacks as the system UI). */
    val token: MediaSession.Token? get() = session?.sessionToken

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_nowplaying), NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }, // U20: controls on the lock screen
        )
        refreshPermission()
        scope.launch {
            SmartRemote.nowPlaying.map { it.np?.playing == false }.distinctUntilChanged().collectLatest { paused ->
                pausedExpired.value = false
                if (paused) { delay(pausedStopMs); pausedExpired.value = true }
            }
        }
        scope.launch {
            val setting = UiPrefs.flow(app).map { it.mediaNotification }.distinctUntilChanged()
            combine(SmartRemote.nowPlaying, setting, Remote.overHelper, Remote.linked, allowed, pausedExpired) { a ->
                NpNotifPolicy.plan(a[0] as NpState, a[1] as Boolean, a[2] as Boolean, a[3] as Boolean, a[4] as Boolean, SystemClock.elapsedRealtime(), a[5] as Boolean)
            }.collect { apply(it) }
        }
    }

    /** Call when the app comes to the front and after the permission dialog: the user may have changed it in Settings. */
    fun refreshPermission() {
        if (!started) return
        allowed.value = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    /** True when a notification could be shown if something played (the Smart panel asks for it otherwise). */
    val canNotify: StateFlow<Boolean> get() = allowed

    /** [NpService] reports whether it is in the foreground. */
    internal fun serviceState(up: Boolean) { serviceUp = up; Net.holdOpen = up }

    private fun apply(p: NpNotifPlan) {
        val prev = _plan.value
        _plan.value = p
        val nm = app.getSystemService(NotificationManager::class.java)
        when (p) {
            is NpNotifPlan.Hide -> {
                if (prev is NpNotifPlan.Hide && prev.reason == p.reason) return
                val wasShown = prev is NpNotifPlan.Show || prev == NpNotifPlan.BtOnly
                if (wasShown) { remove(nm); releaseSession() }
                if (p.reason == "no-permission") Events.log("notification", "blocked", "reason" to p.reason)
                else Events.log("notification", "hide", "reason" to p.reason, "wasShown" to wasShown)
            }
            NpNotifPlan.BtOnly -> {
                if (prev == NpNotifPlan.BtOnly) return
                if (prev is NpNotifPlan.Show) remove(nm)
                val s = session ?: newSession().also { session = it }
                s.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f, SystemClock.elapsedRealtime())
                        .build(),
                )
                s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, app.getString(R.string.notif_bluetooth_title)).build())
                if (!s.isActive) s.isActive = true
                nm.notify(NOTIF_ID, btNotification(s))
                Events.log("notification", "show", "kind" to "bluetooth")
            }
            is NpNotifPlan.Show -> {
                val s = session ?: newSession().also { session = it }
                val changed = prev !is NpNotifPlan.Show || !prev.sameContent(p)
                s.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or (if (p.canSeek) PlaybackState.ACTION_SEEK_TO else 0))
                        .setState(if (p.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, p.positionMs, if (p.playing) 1f else 0f, SystemClock.elapsedRealtime())
                        .build(),
                )
                val bmp = bitmapFor(p.artKey)
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, p.title).putString(MediaMetadata.METADATA_KEY_ARTIST, p.artist)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, if (p.durationMs > 0) p.durationMs else -1L)
                        .apply { if (bmp != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp) }
                        .build(),
                )
                if (!s.isActive) s.isActive = true
                if (changed) {
                    val n = notification(p, s, bmp)
                    current = n
                    if (!serviceUp) NpService.start(app)
                    nm.notify(NOTIF_ID, n)
                    val f = arrayOf("playing" to p.playing, "art" to (bmp != null), "seek" to p.canSeek, "service" to serviceUp)
                    if (prev is NpNotifPlan.Show) Events.log("notification", "update", *f) else Events.log("notification", "show", "kind" to "helper", *f)
                }
            }
        }
    }

    /** Takes the notification down and stops the foreground service holding it. */
    private fun remove(nm: NotificationManager) {
        current = null
        if (serviceUp) NpService.stop(app)
        nm.cancel(NOTIF_ID)
    }

    private fun newSession() = MediaSession(app, "MoteNowPlaying").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = act("play", Consumer.PLAY_PAUSE, wantPlaying = true)
            override fun onPause() = act("pause", Consumer.PLAY_PAUSE, wantPlaying = false)
            override fun onSkipToNext() = act("next", Consumer.NEXT)
            override fun onSkipToPrevious() = act("prev", Consumer.PREV)
            override fun onSeekTo(pos: Long) {
                Events.log("notification", "action", "name" to "seek", "sent" to Remote.linked.value)
                if (Remote.linked.value) SmartRemote.seekTo(pos / 1000.0)
            }
        })
    }

    private fun releaseSession() { session?.run { isActive = false; release() }; session = null }

    /** Notification buttons and media-session callbacks end here. [wantPlaying] makes a stale play/pause a no-op. */
    fun act(name: String, usage: Int, wantPlaying: Boolean? = null) {
        val p = _plan.value
        val st = p as? NpNotifPlan.Show
        val stale = wantPlaying != null && st != null && st.playing == wantPlaying
        val sent = Remote.linked.value && (st != null || p == NpNotifPlan.BtOnly) && !stale
        Events.log("notification", "action", "name" to name, "sent" to sent)
        if (sent) Remote.consumer(usage)
    }

    private fun bitmapFor(key: String?): Bitmap? {
        if (key == null) return null
        art?.let { if (it.first == key) return it.second }
        val bytes = SmartRemote.nowPlaying.value.np?.art ?: return null
        val b = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        art = key to b
        return b
    }

    /** "Mote · Bluetooth": one play/pause toggle, previous, next; sent as HID consumer keys, no metadata and no state. */
    private fun btNotification(s: MediaSession): Notification {
        fun pi(a: String) = PendingIntent.getBroadcast(app, a.hashCode(), Intent(a).setClass(app, NpActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(icon: Int, label: String, a: String) = Notification.Action.Builder(Icon.createWithResource("android", icon), label, pi(a)).build()
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mote)
            .setContentTitle(app.getString(R.string.notif_bluetooth_title))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(action(android.R.drawable.ic_media_play, "Play/Pause", ACTION_PLAY))
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun notification(p: NpNotifPlan.Show, s: MediaSession, bmp: Bitmap?): Notification {
        fun pi(a: String) = PendingIntent.getBroadcast(app, a.hashCode(), Intent(a).setClass(app, NpActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(icon: Int, label: String, a: String) = Notification.Action.Builder(Icon.createWithResource("android", icon), label, pi(a)).build()
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mote)
            .setContentTitle(p.title)
            .setContentText(listOf(p.artist, p.app).filter { it.isNotEmpty() }.distinct().joinToString(" · ").ifEmpty { null })
            .setLargeIcon(bmp)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                if (p.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_PLAY)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}

/** Notification button taps (pre-Android 13 notifications and the lock screen's fallback layout). */
class NpActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NpNotifier.ACTION_PREV -> NpNotifier.act("prev", Consumer.PREV)
            NpNotifier.ACTION_NEXT -> NpNotifier.act("next", Consumer.NEXT)
            NpNotifier.ACTION_PLAY -> NpNotifier.act("playpause", Consumer.PLAY_PAUSE)
        }
    }
}
