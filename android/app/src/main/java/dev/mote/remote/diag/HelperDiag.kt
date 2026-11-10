package dev.mote.remote.diag

import dev.mote.remote.net.Proto
import dev.mote.remote.net.Remote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The helper's `diag` snapshot and `diag.events` stream (protocol 1.5) as the Diagnostics screen sees them.
 * Fed by [dev.mote.remote.net.WsTransport]; an older helper answers `err unknown`, which is [Status.UNSUPPORTED]
 * (not retried within the session).
 */
object HelperDiag {
    enum class Status { IDLE, LOADING, OK, UNSUPPORTED, NO_LINK }

    data class State(
        val status: Status = Status.IDLE,
        val snapshot: JSONObject? = null,
        val events: List<JSONObject> = emptyList(),
        val subscribed: Boolean = false,
        val dropped: Int = 0,
    )

    const val MAX_EVENTS = 300

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Sends a raw message; false = no helper link (Bluetooth, offline). Replaceable in tests. */
    @Volatile var sender: (String) -> Boolean = { Remote.sendLabs(it) }

    /** Fetch the snapshot and follow the live events. Does nothing once the helper said `unknown`. */
    fun open() {
        if (_state.value.status == Status.UNSUPPORTED) return
        Events.log("labs", "subscribe", "feature" to "diag", "on" to true)
        if (!sender(Proto.diag())) { _state.value = _state.value.copy(status = Status.NO_LINK); return }
        val s = _state.value
        _state.value = s.copy(status = if (s.snapshot == null) Status.LOADING else s.status)
        sender(Proto.diagSubscribe(true, s.events.lastOrNull()?.optLong("seq")))
    }

    fun refresh() { if (_state.value.status != Status.UNSUPPORTED) sender(Proto.diag()) }

    fun close() {
        if (_state.value.subscribed) {
            Events.log("labs", "subscribe", "feature" to "diag", "on" to false)
            sender(Proto.diagSubscribe(false))
        }
        _state.value = _state.value.copy(subscribed = false)
    }

    /** `diag` / `diag.events` from the helper. */
    fun deliver(raw: String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (o.optString("t")) {
            "diag" -> _state.value = _state.value.copy(status = Status.OK, snapshot = o)
            "diag.events" -> {
                val a = o.optJSONArray("events")
                val add = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
                val s = _state.value
                _state.value = s.copy(
                    events = (s.events + add).takeLast(MAX_EVENTS),
                    subscribed = if (o.has("subscribed")) o.optBoolean("subscribed") else s.subscribed,
                    dropped = s.dropped + o.optInt("dropped"),
                )
            }
        }
    }

    /** `err unknown` for `diag` / `diag.subscribe`: a helper older than 1.5. */
    fun onErr(code: String, ref: String?) {
        if ((ref == "diag" || ref == "diag.subscribe") && code == "unknown") _state.value = State(status = Status.UNSUPPORTED)
    }

    /** Session ended: the snapshot is stale and the subscription is gone (the next helper may be newer). */
    fun reset() {
        val s = _state.value
        _state.value = when (s.status) {
            Status.UNSUPPORTED -> State()
            Status.OK -> s.copy(subscribed = false, status = Status.LOADING)
            else -> s.copy(subscribed = false)
        }
    }

    fun clear() { _state.value = State() }
}
