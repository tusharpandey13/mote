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
import dev.mote.remote.labs.Labs
import dev.mote.remote.net.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the media notification should be, decided from state only (no Android types). */
sealed interface NpNotifPlan {
    /** [reason]: `lab-off` | `no-helper` | `disconnected` | `nothing-playing` | `no-permission`. */
    data class Hide(val reason: String) : NpNotifPlan

    data class Show(
        val title: String, val artist: String, val app: String, val playing: Boolean,
        val durationMs: Long, val positionMs: Long, val canSeek: Boolean, val artKey: String?,
    ) : NpNotifPlan {
        /** Same notification content apart from the position, which the session extrapolates by itself. */
        fun sameContent(o: Show) = copy(positionMs = 0) == o.copy(positionMs = 0)
    }
}

object NpNotifPolicy {
    /**
     * Over Bluetooth only (no helper) there is no metadata and no seek, so there is no notification (UNDEFINED
     * for the owner, docs/plans/journey-testing.md phase 1b). The permission is checked last, so a Diagnostics row
     * says "blocked" only when a notification would otherwise show.
     */
    fun plan(st: NpState, lab: Boolean, overHelper: Boolean, linked: Boolean, allowed: Boolean, nowMs: Long): NpNotifPlan {
        if (!lab) return NpNotifPlan.Hide("lab-off")
        if (!overHelper) return NpNotifPlan.Hide("no-helper")
        if (!linked) return NpNotifPlan.Hide("disconnected")
        val np = st.np ?: return NpNotifPlan.Hide("nothing-playing")
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
        is NpNotifPlan.Hide -> when (p.reason) {
            "lab-off" -> "off (Smart remote is off)" to false
            "no-helper" -> "off (needs Mote Helper over Wi-Fi or Tailscale; none over Bluetooth)" to false
            "disconnected" -> "off (not connected to a helper)" to false
            "nothing-playing" -> "off (nothing is playing on the Mac)" to false
            "no-permission" -> "blocked: notifications are not allowed for Mote" to true
            else -> p.reason to false
        }
    }
}

/**
 * Labs › Smart remote: a plain MediaSession + MediaStyle notification of what the Mac plays (no foreground
 * service: it lives as long as the helper socket, which the app closes 30 s after it goes to the background).
 * Its buttons go to the Mac through [Remote] / [SmartRemote], like the Media screen.
 */
object NpNotifier {
    private const val CHANNEL = "nowplaying"
    private const val NOTIF_ID = 2
    const val ACTION_PREV = "dev.mote.remote.NP_PREV"
    const val ACTION_PLAY = "dev.mote.remote.NP_PLAY"
    const val ACTION_NEXT = "dev.mote.remote.NP_NEXT"

    private lateinit var app: Context
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val allowed = MutableStateFlow(true)
    private val _plan = MutableStateFlow<NpNotifPlan?>(null)
    /** The latest decision, for Diagnostics. */
    val plan: StateFlow<NpNotifPlan?> = _plan.asStateFlow()
    /** True after the user said no to the system dialog: the next "Allow" opens the app's notification settings. */
    val askedAndDenied = MutableStateFlow(false)

    private var session: MediaSession? = null
    private var art: Pair<String, Bitmap?>? = null
    /** The live session's token, for tests (a MediaController built from it drives the same callbacks as the system UI). */
    val token: MediaSession.Token? get() = session?.sessionToken

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.channel_nowplaying), NotificationManager.IMPORTANCE_LOW))
        refreshPermission()
        scope.launch {
            combine(SmartRemote.nowPlaying, Labs.flow(app, Labs.SMART_REMOTE), Remote.overHelper, Remote.linked, allowed) { st, lab, h, l, ok ->
                NpNotifPolicy.plan(st, lab, h, l, ok, SystemClock.elapsedRealtime())
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

    private fun apply(p: NpNotifPlan) {
        val prev = _plan.value
        _plan.value = p
        val nm = app.getSystemService(NotificationManager::class.java)
        when (p) {
            is NpNotifPlan.Hide -> {
                if (prev is NpNotifPlan.Hide && prev.reason == p.reason) return
                if (prev is NpNotifPlan.Show) { nm.cancel(NOTIF_ID); releaseSession() }
                if (p.reason == "no-permission") Events.log("notification", "blocked", "reason" to p.reason)
                else Events.log("notification", "hide", "reason" to p.reason, "wasShown" to (prev is NpNotifPlan.Show))
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
                    nm.notify(NOTIF_ID, notification(p, s, bmp))
                    val f = arrayOf("playing" to p.playing, "art" to (bmp != null), "seek" to p.canSeek)
                    if (prev is NpNotifPlan.Show) Events.log("notification", "update", *f) else Events.log("notification", "show", *f)
                }
            }
        }
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
        val st = (_plan.value as? NpNotifPlan.Show)
        val stale = wantPlaying != null && st != null && st.playing == wantPlaying
        val sent = Remote.linked.value && Remote.overHelper.value && !stale
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
