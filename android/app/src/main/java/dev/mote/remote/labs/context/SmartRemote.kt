package dev.mote.remote.labs.context

import android.os.SystemClock
import dev.mote.remote.labs.LabsBus
import dev.mote.remote.net.Msg
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Labs › Smart remote (preview): the Mac's context and now playing, from the helper's `labs.ctx` /
 * `labs.np` (docs/labs/context.md). Subscribes while the lab is on and a helper session is up.
 */
object SmartRemote {
    /** Labs store key: open Keys automatically on a Mac text field (the "Stop auto-keyboard" chip clears it). */
    const val AUTO_KEYBOARD = "smart_remote.auto_keyboard"

    private val _context = MutableStateFlow(MacContext())
    val context: StateFlow<MacContext> = _context.asStateFlow()
    private val _nowPlaying = MutableStateFlow(NpState())
    val nowPlaying: StateFlow<NpState> = _nowPlaying.asStateFlow()
    /** Set when Keys was opened for a Mac text field: the type row takes focus (soft keyboard) and clears it. */
    val pendingFocus = MutableStateFlow(false)
    /** Keys is showing because a Mac text field has focus (shows the "Stop auto-keyboard" chip). */
    val autoOpened = MutableStateFlow(false)
    @Volatile private var active = false

    /** Runs for as long as the caller's composition: (un)subscribes and feeds [context] / [nowPlaying]. */
    suspend fun run(enabled: Flow<Boolean>) = coroutineScope {
        launch { LabsBus.messages.collect { if (active) onMessage(it) } }
        combine(enabled, LabsBus.session) { on, s -> on to s }.collect { (on, s) ->
            active = on && s != 0
            if (s != 0) {
                LabsBus.send(JSONObject().put("t", "labs.ctx.sub").put("on", on).toString())
                LabsBus.send(JSONObject().put("t", "labs.np.sub").put("on", on).toString())
            }
            if (!active) { _context.value = MacContext(); _nowPlaying.value = NpState() }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun reduce(e: NpEvent) = _nowPlaying.update { NpReducer.reduce(it, e) }

    internal fun onMessage(m: Msg) {
        when (m) {
            is Msg.Labs -> when (m.t) {
                "labs.ctx" -> _context.value = parseContext(m.o)
                "labs.np" -> reduce(NpEvent.Update(m.o, now()))
                "labs.np.seek" -> reduce(
                    NpEvent.SeekReply(
                        m.o.optString("via"), if (m.o.has("to")) m.o.optDouble("to") else null,
                        m.o.optString("note").ifEmpty { null }, now(),
                    ),
                )
            }
            is Msg.Err -> if (m.ref == "labs.np.seek") reduce(NpEvent.Error(m.msg))
            else -> Unit
        }
    }

    /** Absolute seek (the scrubber). */
    fun seekTo(seconds: Double) {
        val to = seconds.coerceAtLeast(0.0)
        reduce(NpEvent.SeekSent(to, now()))
        LabsBus.send(JSONObject().put("t", "labs.np.seek").put("to", to).toString())
    }

    /** Relative seek (±10 s): absolute when the position is known, so the Mac lands where the card says. */
    fun seekBy(seconds: Int) {
        val st = _nowPlaying.value
        val pos = st.displayPosition(now())
        if (st.canScrub && pos != null) seekTo(pos + seconds)
        else LabsBus.send(JSONObject().put("t", "labs.np.seek").put("by", seconds).toString())
    }

    fun parseContext(o: JSONObject): MacContext {
        if (!o.optBoolean("enabled", false)) return MacContext(enabled = false)
        return MacContext(
            enabled = true,
            app = o.optString("app").take(64),
            bundle = o.optString("bundle").take(128),
            host = o.optString("host").ifEmpty { null },
            url = o.optString("url").ifEmpty { null },
            title = o.optString("title").ifEmpty { null }?.take(200),
            browser = o.optBoolean("browser"),
            text = o.optBoolean("text"),
            secure = o.optBoolean("secure"),
            automation = o.optString("automation", "n/a"),
        )
    }
}
