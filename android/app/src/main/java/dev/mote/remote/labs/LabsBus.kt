package dev.mote.remote.labs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * Labs messages from the helper (`t` starting with `labs.`, plus `err` whose `ref` is a labs message),
 * as raw JSON. Fed by the Wi-Fi transport; previews filter for their own prefix.
 */
object LabsBus {
    private val _incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val incoming: SharedFlow<JSONObject> = _incoming.asSharedFlow()

    fun isLabs(t: String?) = t != null && t.startsWith("labs.")

    fun deliver(json: String) {
        runCatching { JSONObject(json) }.getOrNull()?.let { _incoming.tryEmit(it) }
    }
}
