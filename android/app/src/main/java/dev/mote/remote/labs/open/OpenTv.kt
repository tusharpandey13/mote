package dev.mote.remote.labs.open

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.mote.remote.hid.Consumer
import dev.mote.remote.hid.Mod
import dev.mote.remote.labs.Blockers
import dev.mote.remote.labs.LabsBus
import dev.mote.remote.net.Net
import dev.mote.remote.net.Remote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** `labs.tv.state` from the helper. [page]: home | youtube | youtube-desktop | web. */
data class TvState(val enabled: Boolean, val on: Boolean, val page: String, val url: String?)

/** Pure codec for `labs.open.*` / `labs.tv.*` (docs/labs/open-tv.md). */
object OpenTvProto {
    val TV_KEYS = setOf("up", "down", "left", "right", "ok", "back", "home", "playpause", "search")
    val ACTIONS = setOf("closeTab", "exitFullscreen", "pauseAll")

    private fun obj(t: String) = JSONObject().put("t", t)

    /** Null unless [url] passes [OpenUrl.normalize] and [mode] is browser / tv. */
    fun openUrl(url: String, mode: String, fullscreen: Boolean = false): String? {
        val u = OpenUrl.normalize(url) ?: return null
        if (mode != "browser" && mode != "tv") return null
        return obj("labs.open.url").put("url", u).put("mode", mode).apply { if (fullscreen && mode == "browser") put("fullscreen", true) }.toString()
    }

    fun action(a: String): String? = if (a in ACTIONS) obj("labs.open.action").put("a", a).toString() else null
    fun tvKey(k: String): String? = if (k in TV_KEYS) obj("labs.tv.key").put("k", k).toString() else null
    fun tvOpen(pins: List<Site>): String = obj("labs.tv.open").put("pins", JSONArray().apply {
        pins.take(Sites.PINS_MAX).forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
    }).toString()
    fun tvExit(): String = obj("labs.tv.exit").toString()
    fun tvGet(): String = obj("labs.tv.get").toString()

    fun parseState(o: JSONObject): TvState? {
        if (o.optString("t") != "labs.tv.state") return null
        val url = o.optString("url").takeIf { it.isNotEmpty() && OpenUrl.normalize(it) != null }
        return TvState(o.optBoolean("enabled"), o.optBoolean("on"), o.optString("page", "home").take(32), url)
    }

    /** Message for a helper `err` about a labs request, else null. */
    fun errorText(o: JSONObject): String? {
        if (o.optString("t") != "err") return null
        val ref = o.optString("ref").ifEmpty { null }
        if (o.optString("code") == "disabled") return Blockers.helperLabDisabled(ref, "TV mode").text
        if (!LabsBus.isLabs(ref)) return null
        return when (o.optString("code")) {
            "bad" -> "The Mac refused that link: ${o.optString("msg").take(120)}"
            "tv-off" -> "TV mode is closed on the Mac."
            "paused" -> "Input is paused on the Mac."
            "ax" -> "Mote Helper needs Accessibility permission on the Mac."
            "unknown" -> "This Mote Helper has no Open & TV mode yet. Update it."
            else -> o.optString("msg").take(160).ifBlank { "The Mac refused that." }
        }
    }
}

/** Process-wide state of the Open & TV preview: TV state pushed by the helper, last error, shared link. */
object OpenTv {
    private val _state = MutableStateFlow<TvState?>(null)
    /** Latest `labs.tv.state`; null while unlinked or before the helper answered. */
    val state: StateFlow<TvState?> = _state.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    /** A link shared to Mote (ACTION_SEND); the sheet opens pre-filled and the user confirms. */
    val pendingShare = MutableStateFlow<String?>(null)

    private var started = false

    fun start() {
        if (started) return
        started = true
        Net.scope.launch {
            LabsBus.incoming.collect { o ->
                OpenTvProto.parseState(o)?.let { _state.value = it }
                OpenTvProto.errorText(o)?.let { error.value = it }
            }
        }
        Net.scope.launch { Remote.linked.collect { if (!it) _state.value = null } }
    }

    /** Over Bluetooth there is no helper: nothing labs-specific can be sent. */
    fun send(json: String?): Boolean {
        if (json == null) return false
        val ok = Remote.sendLabs(json)
        if (!ok) error.value = "Open & TV mode needs Mote Helper over Wi-Fi or Tailscale."
        else error.value = null
        return ok
    }

    fun refresh() { Remote.sendLabs(OpenTvProto.tvGet()) }

    /** Quick actions: helper message, or plain keys over Bluetooth (⌘W, Esc, play/pause). */
    fun quick(a: String) {
        if (Remote.sendLabs(OpenTvProto.action(a) ?: return)) { error.value = null; return }
        when (a) {
            "closeTab" -> Remote.tap("w", Mod.CMD)
            "exitFullscreen" -> Remote.tap("esc")
            "pauseAll" -> Remote.consumer(Consumer.PLAY_PAUSE)
        }
    }

    /** ACTION_SEND text from another app: remember its link for the sheet (never opens anything by itself). */
    fun onIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val url = OpenUrl.fromShare(i.getStringExtra(Intent.EXTRA_TEXT), i.getStringExtra(Intent.EXTRA_SUBJECT)) ?: return
        pendingShare.value = url
    }

    /** The share-target alias in the manifest is enabled only while the Labs flag is on. */
    fun syncShareTarget(c: Context, on: Boolean) {
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val cn = ComponentName(c, "dev.mote.remote.labs.open.ShareTarget")
        runCatching {
            if (c.packageManager.getComponentEnabledSetting(cn) != want) c.packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        }
    }
}
