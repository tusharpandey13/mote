package dev.mote.remote.net

import dev.mote.remote.hid.Host
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** How the phone reaches a host. BT = Bluetooth HID (any host); the others go through Mote Helper. */
enum class Route(val badge: String) { BT("BT"), WIFI("Wi-Fi"), TAILNET("Tailscale") }

/**
 * A Mac paired with Mote Helper. Keyed by the helper's stable `hostId`, so one Mac keeps one entry
 * across LAN IP, `<local>.local` and tailnet addresses. The token itself is in [Vault].
 */
data class SavedHost(
    val hostId: String,
    val name: String,
    val deviceId: String,
    val lan: String? = null,       // last LAN address seen (NSD or a successful connect)
    val local: String? = null,     // Bonjour host name, without ".local"
    val tailnet: String? = null,   // MagicDNS name or 100.x
    val manual: String? = null,    // another user-entered address
    val port: Int = Proto.DEFAULT_PORT,
    val wrapped: String? = null,   // AES-GCM wrapped token, only when the Keystore HMAC import failed
    val btAddress: String? = null, // the same Mac as a Bluetooth HID host, once linked
) {
    /** Connection order: fresh NSD result, cached IP, `<name>.local`, then the tailnet / manual address. */
    fun candidates(discovered: String? = null): List<String> =
        listOfNotNull(discovered, lan, local?.let { "$it.local" }, tailnet, manual).distinct()

    val routes: List<Route> get() = buildList {
        if (lan != null || local != null || (manual != null && routeOf(manual) == Route.WIFI)) add(Route.WIFI)
        if (tailnet != null || (manual != null && routeOf(manual) == Route.TAILNET)) add(Route.TAILNET)
    }

    /** Records an address that just worked (or was typed) in the right slot. */
    fun withAddress(address: String): SavedHost = when {
        // Prefer the MagicDNS name (stable across re-registration); a 100.x next to it goes to `manual`.
        routeOf(address) == Route.TAILNET ->
            if (isIpLiteral(address) && tailnet != null && !isIpLiteral(tailnet)) copy(manual = address) else copy(tailnet = address)
        address.endsWith(".local", ignoreCase = true) -> copy(local = address.removeSuffix(".local").removeSuffix(".LOCAL"))
        isIpLiteral(address) -> copy(lan = address)
        else -> copy(manual = address)
    }

    companion object {
        fun encode(list: List<SavedHost>): String = JSONArray(list.map { h ->
            JSONObject().put("hostId", h.hostId).put("name", h.name).put("deviceId", h.deviceId).put("port", h.port)
                .putOpt("lan", h.lan).putOpt("local", h.local).putOpt("tailnet", h.tailnet).putOpt("manual", h.manual)
                .putOpt("wrapped", h.wrapped).putOpt("bt", h.btAddress)
        }).toString()

        fun decode(json: String?): List<SavedHost> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                fun s(k: String) = o.optString(k).ifEmpty { null }
                SavedHost(
                    o.getString("hostId"), o.getString("name"), o.getString("deviceId"),
                    s("lan"), s("local"), s("tailnet"), s("manual"), o.optInt("port", Proto.DEFAULT_PORT), s("wrapped"), s("bt"),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** A helper seen on the LAN by NSD. [hostId] comes from the TXT record (helper 1.1+). */
data class Found(val service: String, val hostId: String?, val address: String, val port: Int)

/** What the pill / picker is pointed at. Persisted as "bt:<address>" or "ws:<hostId>". */
sealed interface Selection {
    data class Bt(val address: String) : Selection
    data class Helper(val hostId: String) : Selection

    fun encode(): String = when (this) { is Bt -> "bt:$address"; is Helper -> "ws:$hostId" }

    companion object {
        fun decode(s: String?): Selection? = when {
            s == null -> null
            s.startsWith("bt:") -> Bt(s.removePrefix("bt:"))
            s.startsWith("ws:") -> Helper(s.removePrefix("ws:"))
            else -> null
        }
    }
}

/** One row of the host picker: one physical machine, with every route we know for it. */
data class HostRow(
    val key: String,
    val name: String,
    val bt: Host? = null,
    val saved: SavedHost? = null,
    val found: Found? = null,
) {
    val routes: List<Route> get() = buildList {
        if (found != null && saved == null) add(Route.WIFI)
        saved?.let { s -> addAll(s.routes); if (found != null && Route.WIFI !in s.routes) add(0, Route.WIFI) }
        if (bt != null) add(Route.BT)
    }

    /** Default route when the row is tapped: the helper (more capabilities) when paired, else Bluetooth. */
    val defaultSelection: Selection? get() = when {
        saved != null -> Selection.Helper(saved.hostId)
        bt != null -> Selection.Bt(bt.address)
        else -> null
    }

    fun matches(sel: Selection?): Boolean = when (sel) {
        is Selection.Bt -> bt?.address == sel.address
        is Selection.Helper -> saved?.hostId == sel.hostId
        null -> false
    }
}

/**
 * Pure reducer for the picker. Paired helpers come first, each merged with its NSD result (same
 * hostId) and its Bluetooth twin (linked address, else the same name: macOS uses the computer name
 * for both). Then the remaining Bluetooth hosts, then helpers found nearby but not paired yet.
 */
fun hostRows(bt: List<Host>, saved: List<SavedHost>, found: List<Found>): List<HostRow> {
    val btLeft = bt.toMutableList()
    val foundLeft = found.toMutableList()
    fun takeBt(address: String?, name: String): Host? {
        val h = btLeft.firstOrNull { it.address == address } ?: btLeft.firstOrNull { sameName(it.name, name) }
        if (h != null) btLeft.remove(h)
        return h
    }
    val rows = ArrayList<HostRow>()
    for (s in saved) {
        val f = foundLeft.filter { it.hostId == s.hostId }
        foundLeft.removeAll(f)
        rows += HostRow("h:${s.hostId}", s.name, takeBt(s.btAddress, s.name), s, f.firstOrNull())
    }
    // Unpaired helpers on the LAN; one row per hostId, merged with a same-named Bluetooth host.
    val nearby = foundLeft.groupBy { it.hostId ?: "n:${it.service}" }.map { (id, fs) ->
        val f = fs.first()
        HostRow("f:$id", f.service, takeBt(null, f.service), null, f)
    }
    rows += btLeft.map { HostRow("bt:${it.address}", it.name, it) }
    rows += nearby
    return rows
}

/**
 * The "Pair with code" list: helpers found on the LAN that aren't paired yet, one per helper.
 * Bonjour renames on a name conflict ("Mac (2)", "Mac (3)") and Android's NSD keeps the stale
 * instances, so one helper can show up under several service names: key by hostId (TXT `id`),
 * else by address:port.
 */
fun nearbyUnpaired(found: List<Found>, saved: List<SavedHost>): List<Found> =
    found.filter { f -> saved.none { it.hostId == f.hostId } }
        .distinctBy { it.hostId ?: "${it.address}:${it.port}" }

internal fun sameName(a: String, b: String): Boolean {
    fun n(s: String) = s.replace('’', '\'').trim().lowercase()
    return n(a).isNotEmpty() && n(a) == n(b)
}

fun isIpLiteral(s: String): Boolean =
    Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(s) || (s.contains(':') && Regex("""^[0-9a-fA-F:.%]+$""").matches(s))

/** Tailscale: MagicDNS (`*.ts.net`), 100.64/10 or fd7a:115c:a1e0::/48. Everything else counts as LAN. */
fun routeOf(address: String): Route {
    val a = address.lowercase().removeSuffix(".")
    if (a.endsWith(".ts.net")) return Route.TAILNET
    if (a.startsWith("fd7a:115c:a1e0:")) return Route.TAILNET
    val p = a.split('.')
    if (p.size == 4 && p[0] == "100") p[1].toIntOrNull()?.let { if (it in 64..127) return Route.TAILNET }
    return Route.WIFI
}

/** "host", "host:port", "[v6]:port" or a URL, as typed by the user. */
fun parseAddress(input: String): Pair<String, Int>? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if ("://" in s) s = s.substringAfter("://")
    s = s.substringBefore('/').substringBefore('#')
    if (s.startsWith("[")) {
        val host = s.substringAfter('[').substringBefore(']')
        val port = s.substringAfter("]:", "").toIntOrNull() ?: Proto.DEFAULT_PORT
        return if (host.isNotEmpty()) host to port else null
    }
    if (s.count { it == ':' } > 1) return s to Proto.DEFAULT_PORT // bare IPv6
    val host = s.substringBefore(':')
    val port = if (':' in s) s.substringAfter(':').toIntOrNull() ?: return null else Proto.DEFAULT_PORT
    if (host.isEmpty() || port !in 1..65535 || !Regex("""^[A-Za-z0-9.\-]+$""").matches(host)) return null
    return host to port
}

/** A pairing link from the helper's QR: `http://h:p/#code=…&id=…&ts=…` or `mote://pair?h=h:p&code=…`. */
data class PairLink(val host: String, val port: Int, val code: String, val hostId: String? = null, val tailnet: String? = null) {
    companion object {
        fun parse(link: String): PairLink? = runCatching {
            val u = URI(link.trim())
            val (params, hostPort) = when (u.scheme?.lowercase()) {
                "mote" -> {
                    val q = query(u.rawQuery ?: return null)
                    q to parseAddress(q["h"] ?: return null)
                }
                "http" -> query(u.rawFragment ?: return null) to parseAddress(u.rawAuthority ?: return null)
                else -> return null
            }
            val code = params["code"]?.takeIf { Regex("""^\d{6}$""").matches(it) } ?: return null
            val (host, port) = hostPort ?: return null
            PairLink(host, port, code, params["id"], params["ts"]?.takeIf { it.isNotBlank() })
        }.getOrNull()

        private fun query(s: String): Map<String, String> = s.split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }.toMap()
    }
}
