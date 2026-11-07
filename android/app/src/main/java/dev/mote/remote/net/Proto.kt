package dev.mote.remote.net

import dev.mote.remote.hid.Consumer
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Messages from the helper (docs/protocol.md section 1). Unknown fields are ignored. */
sealed interface Msg {
    data class Hello(
        val v: Int, val host: String, val hostId: String?, val local: String?, val nonce: String, val pairing: Boolean,
    ) : Msg
    data class Paired(val id: String, val token: String) : Msg
    data class Authed(val id: String, val name: String, val paused: Boolean, val ax: Boolean) : Msg
    data class State(val paused: Boolean, val ax: Boolean) : Msg
    data class Err(val code: String, val msg: String, val retryAfter: Int? = null, val ref: String? = null) : Msg
    data class Pong(val n: Int) : Msg
    /** Any other type; [raw] is the whole frame (e.g. `labs.video.*` for the Labs bridge). */
    data class Other(val t: String, val raw: String = "") : Msg

    /**
     * `spaces` (protocol 1.3): [count] desktops on the active display (1.3 helpers: main display; 0 =
     * unknown, clamped to 0..16), 1-based [current] (0 = unknown / full-screen Space), [thumbs] =
     * thumbnail version per desktop (exactly [count] entries, 0 = none yet), [capture] = `on` / `off` /
     * `denied` / `unavailable`. Protocol 1.4: every display's list in [displays] (empty from a 1.3
     * helper), [display] = id of the active one, [shared] = one list for all screens.
     */
    data class Spaces(
        val count: Int, val current: Int, val thumbs: List<Int>, val capture: String,
        val displays: List<SpacesDisplay> = emptyList(), val display: String? = null, val shared: Boolean = false,
    ) : Msg

    /** One display in `spaces.displays` (1.4); same clamping as [Spaces]. */
    data class SpacesDisplay(val id: String, val name: String, val count: Int, val current: Int, val thumbs: List<Int>)

    /** `spaces.thumb` reply: JPEG thumbnail [jpeg] (decoded from base64) of desktop [i] of [display] (null from 1.3) at version [v]. */
    class SpacesThumb(val i: Int, val v: Int, val w: Int, val h: Int, val jpeg: ByteArray, val display: String? = null) : Msg
}

/** Pure codec for the Mote helper protocol v1.x: JSON text frames and the HMAC challenge. */
object Proto {
    const val VERSION = 1
    const val DEFAULT_PORT = 8790
    const val TEXT_MAX = 2000 // UTF-16 units per `text` message

    private fun obj(t: String) = JSONObject().put("t", t)

    fun pair(code: String, name: String): String = obj("pair").put("code", code).put("name", name.take(64)).toString()
    fun auth(id: String, mac: String): String = obj("auth").put("id", id).put("mac", mac).toString()
    fun ping(n: Int): String = obj("ping").put("n", n).toString()

    /** [mods] uses the HID modifier byte layout, the same bits as [dev.mote.remote.hid.Mod]. */
    fun key(usage: Int, mods: Int = 0, action: String = "tap"): String = keyObj(usage, mods, action).toString()
    private fun keyObj(usage: Int, mods: Int, action: String): JSONObject {
        val o = obj("key").put("usage", usage).put("mods", mods)
        if (action != "tap") o.put("action", action)
        return o
    }

    fun move(dx: Int, dy: Int): String = obj("move").put("dx", dx).put("dy", dy).toString()
    fun scroll(dx: Int, dy: Int): String = obj("scroll").put("dx", dx).put("dy", dy).toString()
    fun button(b: String, action: String = "click", count: Int = 1): String =
        obj("button").put("b", b).put("action", action).put("count", count).toString()

    /** Normalised click on display 0 (main): x, y in 0..1. */
    fun clickAt(x: Double, y: Double, b: String = "left", count: Int = 1): String =
        obj("clickAt").put("x", x.coerceIn(0.0, 1.0)).put("y", y.coerceIn(0.0, 1.0)).put("unit", "norm")
            .put("display", 0).put("b", b).put("count", count).toString()

    /** [action]: null = tap (protocol default); "down" / "up" hold a key (hold-to-seek, protocol 1.x). */
    fun consumer(key: String, action: String? = null): String =
        obj("consumer").put("key", key).apply { if (action != null) put("action", action) }.toString()

    /** One or more `text` messages, each at most [TEXT_MAX] UTF-16 units, never splitting a surrogate pair. */
    fun text(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            var end = minOf(i + TEXT_MAX, s.length)
            if (end < s.length && Character.isHighSurrogate(s[end - 1])) end--
            out += obj("text").put("s", s.substring(i, end)).toString()
            i = end
        }
        return out
    }

    /** `spaces.sub` (protocol 1.3): [on] = true subscribes to `spaces` updates, false stops them. */
    fun spacesSub(on: Boolean = true): String = obj("spaces.sub").put("on", on).toString()
    /** `spaces.thumb` request for the 1-based desktop [i] of [display] (1.4; null = the active display). */
    fun spacesThumb(i: Int, display: String? = null): String = obj("spaces.thumb").put("i", i).apply { if (display != null) put("display", display) }.toString()
    /** `spaces.goto`: switch the Mac to the 1-based desktop [i] of [display] (1.4; null = the active display). */
    fun spacesGoto(i: Int, display: String? = null): String = obj("spaces.goto").put("i", i).apply { if (display != null) put("display", display) }.toString()

    const val SPACES_MAX = 16
    const val DISPLAYS_MAX = 8

    /** [o]'s `count` / `current` / `thumbs`, clamped: count 0..16, current 0..count, exactly count versions >= 0. */
    private fun spaceList(o: JSONObject): Triple<Int, Int, List<Int>> {
        val count = o.optInt("count").coerceIn(0, SPACES_MAX)
        val a = o.optJSONArray("thumbs")
        return Triple(count, o.optInt("current").coerceIn(0, count), List(count) { i -> (a?.optInt(i) ?: 0).coerceAtLeast(0) })
    }

    /** HID consumer usage (what the BT path sends) to the protocol's consumer key. */
    fun consumerKey(usage: Int): String? = when (usage) {
        Consumer.PLAY_PAUSE -> "play"
        Consumer.NEXT -> "next"
        Consumer.PREV -> "prev"
        Consumer.MUTE -> "mute"
        Consumer.VOL_UP -> "volup"
        Consumer.VOL_DOWN -> "voldown"
        Consumer.BRIGHTNESS_UP -> "brightup"
        Consumer.BRIGHTNESS_DOWN -> "brightdown"
        Consumer.FAST_FORWARD -> "fast"
        Consumer.REWIND -> "rewind"
        else -> null
    }

    /** Mouse button bit (1 left, 2 right, 4 middle) to the protocol name. */
    fun buttonName(mask: Int): String = when {
        mask and 2 != 0 -> "right"
        mask and 4 != 0 -> "middle"
        else -> "left"
    }

    fun parse(json: String): Msg? = runCatching {
        val o = JSONObject(json)
        when (val t = o.getString("t")) {
            "hello" -> Msg.Hello(
                o.optInt("v", 0), o.optString("host"), o.optString("hostId").ifEmpty { null },
                o.optString("local").ifEmpty { null }, o.getString("nonce"), o.optBoolean("pairing"),
            )
            "paired" -> Msg.Paired(o.getString("id"), o.getString("token"))
            "authed" -> Msg.Authed(o.getString("id"), o.optString("name"), o.optBoolean("paused"), o.optBoolean("ax", true))
            "state" -> Msg.State(o.optBoolean("paused"), o.optBoolean("ax", true))
            "err" -> Msg.Err(
                o.optString("code"), o.optString("msg"),
                if (o.has("retryAfter")) o.optInt("retryAfter") else null, o.optString("ref").ifEmpty { null },
            )
            "pong" -> Msg.Pong(o.optInt("n"))
            "spaces" -> {
                val (count, current, thumbs) = spaceList(o)
                val arr = o.optJSONArray("displays")
                val displays = (0 until (arr?.length() ?: 0)).mapNotNull { k ->
                    val d = arr?.optJSONObject(k) ?: return@mapNotNull null
                    val id = d.optString("id").ifEmpty { return@mapNotNull null }
                    val (n, cur, v) = spaceList(d)
                    Msg.SpacesDisplay(id, d.optString("name").take(64), n, cur, v)
                }.distinctBy { it.id }.take(DISPLAYS_MAX)
                Msg.Spaces(
                    count, current, thumbs, o.optString("capture", "unavailable"),
                    displays, o.optString("display").ifEmpty { null }, o.optBoolean("shared"),
                )
            }
            "spaces.thumb" -> {
                val i = o.getInt("i")
                val jpeg = java.util.Base64.getDecoder().decode(o.getString("data"))
                if (i !in 1..SPACES_MAX || jpeg.isEmpty()) null
                else Msg.SpacesThumb(i, o.optInt("v"), o.optInt("w"), o.optInt("h"), jpeg, o.optString("display").ifEmpty { null })
            }
            else -> Msg.Other(t, json)
        }
    }.getOrNull()

    /** mac = hex(HMAC-SHA256(key = token bytes, msg = nonce ASCII)). */
    fun hmacHex(key: ByteArray, nonce: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(nonce.toByteArray(Charsets.US_ASCII)).toHex()
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
