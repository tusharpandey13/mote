package dev.mote.remote.labs

import dev.mote.remote.net.Msg
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `labs.*` traffic of the authenticated helper session (Wi-Fi / Tailscale). Labs subscribe again
 * whenever [session] changes (0 = no helper session), and read [messages] (`labs.*` plus `err`s
 * whose `ref` is a `labs.*` message).
 */
object LabsBus {
    private val _session = MutableStateFlow(0)
    val session: StateFlow<Int> = _session.asStateFlow()
    private val _messages = MutableSharedFlow<Msg>(extraBufferCapacity = 64)
    val messages: SharedFlow<Msg> = _messages.asSharedFlow()

    @Volatile private var owner: Any? = null
    @Volatile private var sender: ((String) -> Unit)? = null
    private var counter = 0

    @Synchronized fun linked(owner: Any, send: (String) -> Unit) {
        this.owner = owner; sender = send
        _session.value = ++counter
    }

    @Synchronized fun unlinked(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null; sender = null
        _session.value = 0
    }

    fun receive(m: Msg) { _messages.tryEmit(m) }

    /** Sends [json] on the helper session; false when there is none. */
    fun send(json: String): Boolean = sender?.let { it(json); true } ?: false
}
