package dev.mote.remote.diag

import android.util.Log
import org.json.JSONObject

/**
 * The app's event log (docs/plans/journey-testing.md, phase 1): every state change and decision is one
 * structured event `{t, seq, side:"app", area, event, journeyStep?, corr, fields}`, kept in a 5,000-event
 * in-memory ring buffer and mirrored to logcat under the tag `Mote/<area>`. `fields` carry sizes, counts,
 * status codes and names; never secrets (tokens, keys, nonces, codes), typed keys or message payloads.
 *
 * Call sites use string literals for [area] and [event]: `EventsCoverageTest` scans the sources and keeps
 * [CATALOG] equal to what the code emits.
 */
object Events {
    const val SIDE = "app"
    const val CAPACITY = 5000

    /** Every area and the events it may emit. Adding an event means adding it here (a unit test checks both directions). */
    val CATALOG: Map<String, Set<String>> = mapOf(
        "lifecycle" to setOf("init", "foreground", "service"),
        "permission" to setOf("check", "request", "result"),
        "discovery" to setOf(
            "sweep.start", "sweep.open", "sweep.probe", "sweep.done", "known.probe", "ble.start", "ble.found", "ble.failed",
            "nsd.start", "nsd.found", "nsd.resolved", "nsd.lost", "nsd.failed", "network",
        ),
        "transport" to setOf(
            "select", "connect.try", "connect.fail", "connect.hello", "connect.auth", "connect.ready", "connect.failed",
            "connect.dropped", "connect.backoff", "connect.mismatch", "bt.state", "tailscale.prompt",
        ),
        "session" to setOf("open", "authed", "closed", "paired", "pair.failed", "state"),
        "protocol" to setOf("out", "in", "error", "bad"),
        "input" to setOf("summary", "dropped"),
        "labs" to setOf("toggle", "subscribe", "push", "blocked"),
        "ui" to setOf("screen", "banner", "tap"),
    )

    /** One log entry; [fields] values are JSON-safe (String, Number, Boolean, null, or a List of those). */
    class Event(
        val t: Long, val seq: Long, val area: String, val event: String,
        val journeyStep: String?, val corr: String?, val fields: Map<String, Any?>,
    ) {
        fun toJson(): JSONObject = JSONObject().put("t", t).put("seq", seq).put("side", SIDE).put("area", area).put("event", event)
            .apply { if (journeyStep != null) put("journeyStep", journeyStep) }
            .put("corr", corr ?: JSONObject.NULL)
            .put("fields", JSONObject().also { o -> fields.forEach { (k, v) -> o.put(k, jsonValue(v)) } })

        fun field(k: String): Any? = fields[k]
    }

    private fun jsonValue(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Collection<*> -> org.json.JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }

    private val lock = Any()
    private val ring = ArrayDeque<Event>()
    private var seq = 0L
    @Volatile private var logcatOk = true
    /** Test hook: replaces the wall clock. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }
    /** Called after every event (the Diagnostics screen refreshes from this). */
    @Volatile var onEvent: (() -> Unit)? = null

    fun log(area: String, event: String, corr: String? = null, step: String? = null, fields: Map<String, Any?> = emptyMap()) {
        val e: Event
        synchronized(lock) {
            e = Event(clock(), ++seq, area, event, step, corr, fields)
            ring.addLast(e)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        if (logcatOk) try {
            Log.d("Mote/$area", "$event ${if (corr != null) "corr=$corr " else ""}${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
        } catch (_: RuntimeException) { logcatOk = false } // plain JVM unit tests: android.util.Log is a stub
        onEvent?.invoke()
    }

    fun log(area: String, event: String, vararg fields: Pair<String, Any?>) = log(area, event, null, null, fields.toMap())

    /** Same as [log] with a correlation id. */
    fun logc(area: String, event: String, corr: String?, vararg fields: Pair<String, Any?>) = log(area, event, corr, null, fields.toMap())

    private val lastByKey = HashMap<String, Long>()
    private val skipped = HashMap<String, Int>()

    /**
     * High-rate events (`protocol.in` / `protocol.out`): at most one per second per [key]; the next one that
     * is let through carries `skipped`. The first event of a key is always logged.
     */
    fun coalesced(key: String, area: String, event: String, corr: String?, fields: Map<String, Any?>) {
        val now = clock()
        val skip: Int
        synchronized(lock) {
            val last = lastByKey[key]
            if (last != null && now - last < 1000) { skipped[key] = (skipped[key] ?: 0) + 1; return }
            lastByKey[key] = now
            skip = skipped.remove(key) ?: 0
        }
        log(area, event, corr, null, if (skip > 0) fields + ("skipped" to skip) else fields)
    }

    fun snapshot(): List<Event> = synchronized(lock) { ring.toList() }
    val size: Int get() = synchronized(lock) { ring.size }
    val head: Long get() = synchronized(lock) { seq }

    /** The buffer as JSONL, oldest first (Settings › Diagnostics › Share log). */
    fun toJsonl(): String = snapshot().joinToString("\n") { it.toJson().toString() } + "\n"

    fun clear() { synchronized(lock) { ring.clear(); lastByKey.clear(); skipped.clear() } }

    // ---- input counts: one `input.summary` per 5 s of activity, never per key ----

    private val counts = LinkedHashMap<String, Int>()
    private var lastSummary = 0L

    /** Counts one input of [kind] (`key`, `text`, `move`, `scroll`, `button`, `consumer`, `clickAt`, `spaces`, `labs`). */
    fun input(kind: String) {
        val now = clock()
        val flush: Map<String, Int>?
        synchronized(lock) {
            if (lastSummary == 0L) lastSummary = now
            counts[kind] = (counts[kind] ?: 0) + 1
            flush = if (now - lastSummary >= 5000) { lastSummary = now; LinkedHashMap(counts).also { counts.clear() } } else null
        }
        if (flush != null) log("input", "summary", null, null, mapOf("counts" to flush.entries.map { "${it.key}=${it.value}" }))
    }

    /** Emits the pending counts now (app going to the background). */
    fun flushInput() {
        val flush = synchronized(lock) { if (counts.isEmpty()) null else LinkedHashMap(counts).also { counts.clear(); lastSummary = clock() } } ?: return
        log("input", "summary", null, null, mapOf("counts" to flush.entries.map { "${it.key}=${it.value}" }))
    }
}

/** Correlation ids: one per user action, sent in the envelope of every client message and logged on both sides. */
object Corr {
    private var n = 0L
    private val base = java.lang.Long.toString(System.nanoTime() and 0xffffff, 36)
    private var action: String? = null
    private var actionAt = 0L

    /** A fresh id, or the current user action's id when one began in the last second. */
    @Synchronized fun next(): String {
        val now = System.nanoTime() / 1_000_000
        action?.let { if (now - actionAt < 1000) return it }
        return "a$base-${(++n).toString(36)}"
    }

    /** A UI action begins (button tapped): messages sent in the next second share its id. Returns it. */
    @Synchronized fun begin(): String {
        action = null
        val id = next()
        action = id; actionAt = System.nanoTime() / 1_000_000
        return id
    }

    @Synchronized fun reset() { action = null }
}
