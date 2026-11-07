package dev.mote.remote.labs.open

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A pinned site or a recent entry. [url] is always an [OpenUrl.normalize]d http(s) link. */
data class Site(val title: String, val url: String)

/** Pure list rules for recents and pins (unit-tested); [OpenStore] persists them. */
object Sites {
    const val RECENTS_MAX = 12
    const val PINS_MAX = 12
    const val TITLE_MAX = 40

    val defaultPins = listOf(
        Site("YouTube", "https://www.youtube.com/tv"),
        Site("Netflix", "https://www.netflix.com/browse"),
        Site("Prime Video", "https://www.primevideo.com"),
        Site("Disney+", "https://www.disneyplus.com"),
        Site("Twitch", "https://www.twitch.tv"),
        Site("Spotify", "https://open.spotify.com"),
    )

    /** Newest first, one entry per URL, at most [RECENTS_MAX]. Unsafe URLs are dropped. */
    fun addRecent(list: List<Site>, s: Site): List<Site> {
        val url = OpenUrl.normalize(s.url) ?: return list
        return (listOf(Site(s.title.take(TITLE_MAX).ifBlank { OpenUrl.host(url) }, url)) + list.filter { it.url != url }).take(RECENTS_MAX)
    }

    /** Appends a pin unless that URL is pinned already or the list is full. */
    fun addPin(list: List<Site>, s: Site): List<Site> {
        val url = OpenUrl.normalize(s.url) ?: return list
        if (list.size >= PINS_MAX || list.any { it.url == url }) return list
        return list + Site(s.title.trim().take(TITLE_MAX).ifBlank { OpenUrl.host(url) }, url)
    }

    fun removePin(list: List<Site>, url: String): List<Site> = list.filter { it.url != url }

    fun encode(list: List<Site>): String = JSONArray().apply { list.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } }.toString()

    /** Tolerant decode: bad JSON is [fallback]; bad or unsafe entries are skipped. */
    fun decode(json: String?, fallback: List<Site>, max: Int): List<Site> {
        if (json == null) return fallback
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return fallback
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = OpenUrl.normalize(o.optString("url")) ?: return@mapNotNull null
            Site(o.optString("title").take(TITLE_MAX).ifBlank { OpenUrl.host(url) }, url)
        }.distinctBy { it.url }.take(max)
    }
}

/** DataStore for the Open… sheet: recents and pinned sites. */
object OpenStore {
    private val Context.store by preferencesDataStore("labs_open", corruptionHandler = Prefs.corruptionHandler)
    private val RECENTS = stringPreferencesKey("recents")
    private val PINS = stringPreferencesKey("pins")

    fun recents(c: Context): Flow<List<Site>> = c.store.data.map { Sites.decode(it.typed(RECENTS), emptyList(), Sites.RECENTS_MAX) }
    fun pins(c: Context): Flow<List<Site>> = c.store.data.map { Sites.decode(it.typed(PINS), Sites.defaultPins, Sites.PINS_MAX) }

    suspend fun addRecent(c: Context, s: Site) = c.store.edit {
        it[RECENTS] = Sites.encode(Sites.addRecent(Sites.decode(it.typed(RECENTS), emptyList(), Sites.RECENTS_MAX), s))
    }
    suspend fun clearRecents(c: Context) = c.store.edit { it.remove(RECENTS) }

    suspend fun editPins(c: Context, f: (List<Site>) -> List<Site>) = c.store.edit {
        it[PINS] = Sites.encode(f(Sites.decode(it.typed(PINS), Sites.defaultPins, Sites.PINS_MAX)))
    }
}
