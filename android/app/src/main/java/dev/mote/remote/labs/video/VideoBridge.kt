package dev.mote.remote.labs.video

import android.os.SystemClock
import dev.mote.remote.labs.video.VideoState.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Labs: the browser video bridge over the helper session (docs/labs/video-bridge.md). WsTransport
 * calls [onLinked] / [onMessage] / [onUnlinked]; the UI reads [state] and calls [command].
 * Subscribes only while [setEnabled] (the Labs flag) is on.
 */
object VideoBridge {
    private val _state = MutableStateFlow<VideoState?>(null)
    /** Latest `labs.video.state`; null while unlinked, disabled or before the helper answers. */
    val state: StateFlow<VideoState?> = _state.asStateFlow()

    @Volatile private var enabled = false
    @Volatile private var send: ((String) -> Boolean)? = null

    private fun now() = SystemClock.elapsedRealtime()

    @Synchronized fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        send?.invoke(VideoState.sub(on))
        if (!on) _state.value = null
    }

    /** The helper session is authenticated; [s] sends one text frame on it. */
    @Synchronized fun onLinked(s: (String) -> Boolean) {
        send = s
        if (enabled) s(VideoState.sub(true))
    }

    /** A `labs.video.*` frame from the helper. Errors (`err` with a `labs.video` ref) are ignored: the next state tells. */
    fun onMessage(json: String) {
        if (!enabled) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (o.optString("t") != "labs.video.state") return
        val st = VideoState.parse(o, now())
        _state.update { VideoState.reduce(it, Event.Received(st)) }
    }

    @Synchronized fun onUnlinked() {
        send = null
        _state.update { VideoState.reduce(it, Event.Disconnected) }
    }

    /** Sends `labs.video.cmd` and shows its effect at once. False if not linked. */
    fun command(cmd: String, value: Any? = null): Boolean {
        val s = send ?: return false
        if (!s(VideoState.cmd(cmd, value))) return false
        _state.update { VideoState.reduce(it, Event.Optimistic(cmd, value, now())) }
        return true
    }
}
