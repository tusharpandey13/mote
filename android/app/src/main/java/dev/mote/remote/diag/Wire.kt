package dev.mote.remote.diag

/**
 * The one chokepoint for protocol logging and the `corr` envelope: [dev.mote.remote.net.WsSession] passes
 * every outgoing message through [out] and every incoming one through [inn]. Logs the message type, size and
 * `corr`, never the payload. Input messages are counted ([Events.input]) instead of logged one by one.
 * `EventsCoverageTest` keeps [OUT_TYPES] / [IN_TYPES] equal to the message types in `Proto.kt`.
 */
object Wire {
    /** Input messages: counted, one `input.summary` per 5 s. */
    val INPUT_TYPES = setOf("key", "text", "move", "scroll", "button", "clickAt", "consumer")
    /** Every message type the app sends (plus any `labs.*`). */
    val OUT_TYPES = INPUT_TYPES + setOf("pair", "auth", "ping", "spaces.sub", "spaces.thumb", "spaces.goto", "diag", "diag.subscribe")
    /** Every message type the app handles (plus any `labs.*`); anything else is logged as unknown. */
    val IN_TYPES = setOf("hello", "paired", "authed", "state", "err", "pong", "spaces", "spaces.thumb", "diag", "diag.events")

    private val typeRe = Regex("\"t\":\"([^\"]{1,40})\"")
    private val corrRe = Regex("\"corr\":\"([^\"]{1,64})\"")

    fun typeOf(json: String): String? = typeRe.find(json)?.groupValues?.get(1)
    fun corrOf(json: String): String? = corrRe.find(json)?.groupValues?.get(1)

    /** [json] with a `corr` added (kept if it already has one). */
    fun withCorr(json: String, corr: String): String =
        if (corrOf(json) != null || !json.endsWith("}")) json else json.dropLast(1) + ",\"corr\":\"$corr\"}"

    /** Stamps [json] with a correlation id, logs it, and returns what to put on the wire. */
    fun out(json: String): String {
        val t = typeOf(json) ?: "?"
        val wire = withCorr(json, Corr.next())
        if (t in INPUT_TYPES) Events.input(t)
        else Events.coalesced("out.$t", "protocol", "out", corrOf(wire), mapOf("type" to t, "bytes" to wire.length))
        return wire
    }

    /** Logs one received frame: [t] is the parsed type (null = unparsable). */
    fun inn(text: String, t: String?) {
        val corr = corrOf(text)
        if (t == null) { Events.logc("protocol", "bad", corr, "bytes" to text.length); return }
        val known = t in IN_TYPES || t.startsWith("labs.")
        Events.coalesced("in.$t", "protocol", "in", corr, mapOf("type" to t, "bytes" to text.length, "known" to known))
    }

    /** An `err` from the helper: always logged, with the code and the message it answers. */
    fun err(code: String, ref: String?, corr: String?) {
        Events.logc("protocol", "error", corr, "code" to code, "ref" to ref)
    }
}
