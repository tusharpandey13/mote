package dev.mote.remote.labs.context

import dev.mote.remote.hid.Mod

/** What is in front on the Mac (`labs.ctx`). [host] is always set for a browser tab; [url] / [title] only with full details on. */
data class MacContext(
    val enabled: Boolean = false,
    val app: String = "",
    val bundle: String = "",
    val host: String? = null,
    val url: String? = null,
    val title: String? = null,
    val browser: Boolean = false,
    val text: Boolean = false,
    val secure: Boolean = false,
    val automation: String = "n/a",
)

/** One button of a profile: a key ([dev.mote.remote.hid.Key] name) with modifiers. */
data class QuickAction(val label: String, val description: String, val key: String, val mods: Int = 0)

enum class ProfileId { NETFLIX, YOUTUBE, VIDEO_SITE, VIDEO_APP, BROWSER_MEDIA }

/** A context profile: the title shown and the quick actions offered. */
data class Profile(val id: ProfileId, val title: String, val actions: List<QuickAction>)

/**
 * The rules table: app / host -> profile. First match wins; no match = the default Media screen
 * (the panel then only shows now playing, if anything plays).
 */
object ContextRules {
    private val FULLSCREEN_F = QuickAction("Fullscreen", "Toggle fullscreen (F)", key = "f")
    private val SPACE = QuickAction("Play", "Play or pause (space)", key = "space")
    private val BACK_5 = QuickAction("−5 s", "Back 5 seconds (left arrow)", key = "left")
    private val FWD_5 = QuickAction("+5 s", "Forward 5 seconds (right arrow)", key = "right")

    val NETFLIX = Profile(
        ProfileId.NETFLIX, "Netflix",
        listOf(
            QuickAction("Skip intro", "Skip intro or recap (S)", key = "s"),
            FULLSCREEN_F,
            // Netflix on the web has no subtitles shortcut; Esc leaves fullscreen so the menu can be reached.
            QuickAction("Exit full", "Leave fullscreen (Esc)", key = "esc"),
            QuickAction("Mute", "Mute (M)", key = "m"),
        ),
    )

    val YOUTUBE = Profile(
        ProfileId.YOUTUBE, "YouTube",
        listOf(
            FULLSCREEN_F,
            QuickAction("Theater", "Theater mode (T)", key = "t"),
            QuickAction("Next", "Next video (shift N)", key = "n", mods = Mod.SHIFT),
            QuickAction("Captions", "Captions on or off (C)", key = "c"),
            QuickAction("Slower", "Slower (shift comma)", key = ",", mods = Mod.SHIFT),
            QuickAction("Faster", "Faster (shift period)", key = ".", mods = Mod.SHIFT),
        ),
    )

    val VIDEO_SITE = Profile(ProfileId.VIDEO_SITE, "Video", listOf(FULLSCREEN_F, SPACE, BACK_5, FWD_5))

    /** Native players: QuickTime, IINA, TV use ⌃⌘F for fullscreen. */
    val VIDEO_APP = Profile(
        ProfileId.VIDEO_APP, "Video",
        listOf(QuickAction("Fullscreen", "Toggle fullscreen (control command F)", key = "f", mods = Mod.CTRL or Mod.CMD), SPACE, BACK_5, FWD_5),
    )

    /** A browser tab that is the system's now-playing source but has no profile of its own. */
    val BROWSER_MEDIA = Profile(ProfileId.BROWSER_MEDIA, "Web media", listOf(FULLSCREEN_F, SPACE, BACK_5, FWD_5))

    private val hostRules: List<Pair<List<String>, Profile>> = listOf(
        listOf("netflix.com") to NETFLIX,
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com") to YOUTUBE,
        listOf("vimeo.com", "twitch.tv", "dailymotion.com", "primevideo.com", "disneyplus.com", "hulu.com", "max.com", "tv.apple.com", "crunchyroll.com", "jiocinema.com", "hotstar.com") to VIDEO_SITE,
    )

    private val appRules: Map<String, Profile> = mapOf(
        "com.netflix.Netflix" to NETFLIX,
        "com.apple.QuickTimePlayerX" to VIDEO_APP,
        "com.colliderli.iina" to VIDEO_APP,
        "com.apple.TV" to VIDEO_APP,
        "org.videolan.vlc" to VIDEO_SITE, // VLC: F toggles fullscreen too
    )

    /** True when [host] is [domain] or a subdomain of it. */
    fun hostMatches(host: String, domain: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == domain || h.endsWith(".$domain")
    }

    /** The profile for [ctx]; [nowPlayingBundle] is the app the Mac reports as playing (null = nothing). */
    fun match(ctx: MacContext, nowPlayingBundle: String? = null): Profile? {
        if (!ctx.enabled || ctx.bundle.isEmpty()) return null
        appRules[ctx.bundle]?.let { return it }
        val host = ctx.host
        if (ctx.browser && host != null) {
            hostRules.firstOrNull { (domains, _) -> domains.any { hostMatches(host, it) } }?.let { return it.second }
        }
        if (ctx.browser && nowPlayingBundle == ctx.bundle) return BROWSER_MEDIA
        return null
    }
}

/**
 * Auto-keyboard: opens Keys when a text field gains focus on the Mac and goes back when it loses it
 * (only if it was opened automatically). Pure, so it can be unit tested.
 */
data class AutoKeyboard(val lastText: Boolean = false, val opened: Boolean = false) {
    enum class Action { NONE, OPEN, CLOSE }

    fun next(ctx: MacContext, allowed: Boolean, onKeys: Boolean): Pair<AutoKeyboard, Action> {
        val text = ctx.enabled && ctx.text
        return when {
            text && !lastText && allowed && !onKeys -> AutoKeyboard(true, opened = true) to Action.OPEN
            !text && lastText && opened -> AutoKeyboard(false, opened = false) to (if (onKeys) Action.CLOSE else Action.NONE)
            else -> copy(lastText = text, opened = opened && text) to Action.NONE
        }
    }
}
