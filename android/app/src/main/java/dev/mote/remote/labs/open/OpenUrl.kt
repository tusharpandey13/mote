package dev.mote.remote.labs.open

import java.net.URI
import java.net.URLEncoder

/**
 * Pure URL handling for the Open… sheet. Only http(s) links with a host ever leave the phone; the
 * helper checks again (docs/labs/open-tv.md). Everything else typed into the field is a YouTube search.
 */
object OpenUrl {
    const val MAX = 2048

    sealed interface Input {
        data class Link(val url: String) : Input
        data class Search(val query: String) : Input
    }

    private val BARE = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(:\d{1,5})?([/?#].*)?$""")
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private val IN_TEXT = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /**
     * A safe, absolute http(s) URL for [input], or null. Bare domains ("netflix.com/browse") get
     * https://. Refuses other schemes (javascript:, file:, data:, intent:, content:, ...), user:pass@,
     * whitespace and control characters, non-ASCII hosts and anything over [MAX] characters.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.length > MAX) return null
        if (s.any { it.code <= 0x20 || it.code == 0x7F }) return null
        if (!SCHEME.containsMatchIn(s) || BARE.matches(s)) {
            if (!BARE.matches(s)) return null
            s = "https://$s"
        }
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.rawUserInfo != null) return null
        val host = u.host ?: return null
        if (host.isEmpty() || host.startsWith(".") || host.endsWith("..")) return null
        return if (s.length > MAX) null else s
    }

    /** Link if [input] is a URL, else a YouTube search for it; null when blank. */
    fun classify(input: String): Input? {
        val t = input.trim()
        if (t.isEmpty()) return null
        normalize(t)?.let { return Input.Link(it) }
        // Looks like a link but isn't allowed (javascript:, file:, ...): never search for it either.
        if (SCHEME.containsMatchIn(t) && !t.contains(' ')) return null
        return Input.Search(t.take(200))
    }

    fun host(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    fun isYouTube(url: String): Boolean {
        val h = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }

    /** YouTube results URL for [query] (spaces as %20: the helper maps it to leanback search). */
    fun youTubeSearch(query: String): String =
        "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    /** `tv` for YouTube (leanback in the TV window), else `browser`. */
    fun modeFor(url: String): String = if (isYouTube(url)) "tv" else "browser"

    /**
     * The link in an ACTION_SEND share: EXTRA_TEXT is often "Title https://…" or "Watch this: https://…".
     * Returns the first safe http(s) URL in [text] (then [subject]), trailing punctuation trimmed.
     */
    fun fromShare(text: String?, subject: String? = null): String? {
        for (src in listOfNotNull(text, subject)) {
            if (src.length > 8 * MAX) continue
            for (m in IN_TEXT.findAll(src)) {
                val cand = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                normalize(cand)?.let { return it }
            }
            normalize(src)?.let { return it }
        }
        return null
    }
}
