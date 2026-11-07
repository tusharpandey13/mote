package dev.mote.remote.labs.video

import android.os.SystemClock
import dev.mote.remote.labs.LabsBus
import dev.mote.remote.labs.video.VideoState.Event
import dev.mote.remote.net.Net
import dev.mote.remote.net.Remote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Labs: the browser video bridge over the helper session (docs/labs/video-bridge.md). Reads
 * `labs.video.state` from [LabsBus], sends through [Remote.sendLabs], and (re)subscribes whenever
 * the link comes up while [setEnabled] (the Labs flag) is on. The UI reads [state] and calls [command].
 */
object VideoBridge {
    private val _state = MutableStateFlow<VideoState?>(null)
    /** Latest `labs.video.state`; null while unlinked, disabled or before the helper answers. */
    val state: StateFlow<VideoState?> = _state.asStateFlow()

    @Volatile private var enabled = false

    private fun now() = SystemClock.elapsedRealtime()

    init {
        Net.scope.launch { LabsBus.incoming.collect { if (it.optString("t") == "labs.video.state") onState(it) } }
        Net.scope.launch {
            Remote.linked.collect { up ->
                if (up) { if (enabled) Remote.sendLabs(VideoState.sub(true)) } else _state.update { VideoState.reduce(it, Event.Disconnected) }
            }
        }
    }

    @Synchronized fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        Remote.sendLabs(VideoState.sub(on))
        if (!on) _state.value = null
    }

    private fun onState(o: JSONObject) {
        if (!enabled) return
        val st = VideoState.parse(o, now())
        _state.update { VideoState.reduce(it, Event.Received(st)) }
    }

    /** Sends `labs.video.cmd` and shows its effect at once. False if not linked over the helper. */
    fun command(cmd: String, value: Any? = null): Boolean {
        if (!Remote.sendLabs(VideoState.cmd(cmd, value))) return false
        _state.update { VideoState.reduce(it, Event.Optimistic(cmd, value, now())) }
        return true
    }
}
