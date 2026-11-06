package dev.mote.remote.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RightClick { HOLD, TWO_FINGER }
enum class HapticStrength(val label: String, val scale: Float) { OFF("Off", 0f), LIGHT("Light", 0.55f), STANDARD("Standard", 0.85f), STRONG("Strong", 1f) }

/** Settings and Haptics screens (menu sheet). Kept in its own DataStore file, separate from [Prefs]. */
data class UiSettings(
    val pointerSpeed: Float = 1f,   // 0.25x-2x of the base touchpad gain
    val scrollSpeed: Float = 1f,    // 0.25x-2x of the base two-finger scroll rate
    val naturalScroll: Boolean = true,
    val rightClick: RightClick = RightClick.HOLD,
    val clearModsOnSwitch: Boolean = false,
    val releaseModsIdle: Boolean = true,
    val keepScreenOn: Boolean = true,
    val extraDim: Boolean = false,
    val hapticStrength: HapticStrength = HapticStrength.STANDARD,
    val hapticKeys: Boolean = true,
    val hapticDetents: Boolean = true,
    val hapticLink: Boolean = true,
    /** Spaces over Bluetooth: how many desktops to show (1-16). */
    val desktopCount: Int = 4,
    /** The Mac has "Switch to Desktop N" enabled, so a card can send ⌃N instead of stepping. */
    val ctrlNumberShortcuts: Boolean = false,
    /** The one-time ⌃N hint card was dismissed with Done. */
    val spacesHintDone: Boolean = false,
) {
    companion object {
        const val SPEED_MIN = 0.25f
        const val SPEED_MAX = 2f
    }
}

object UiPrefs {
    private val Context.store by preferencesDataStore("mote_settings")
    private val POINTER = floatPreferencesKey("pointer_speed")
    private val SCROLL = floatPreferencesKey("scroll_speed")
    private val NATURAL = booleanPreferencesKey("natural_scroll")
    private val RIGHT = stringPreferencesKey("right_click")
    private val CLEAR_MODS = booleanPreferencesKey("clear_mods_on_switch")
    private val IDLE_MODS = booleanPreferencesKey("release_mods_idle")
    private val SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    private val DIM = booleanPreferencesKey("extra_dim")
    private val H_STRENGTH = stringPreferencesKey("haptic_strength")
    private val H_KEYS = booleanPreferencesKey("haptic_keys")
    private val H_DETENTS = booleanPreferencesKey("haptic_detents")
    private val H_LINK = booleanPreferencesKey("haptic_link")
    private val DESKTOPS = androidx.datastore.preferences.core.intPreferencesKey("desktop_count")
    private val CTRL_N = booleanPreferencesKey("ctrl_number_shortcuts")
    private val SPACES_HINT = booleanPreferencesKey("spaces_hint_done")

    private fun clampSpeed(v: Float?) = (v ?: 1f).coerceIn(UiSettings.SPEED_MIN, UiSettings.SPEED_MAX)

    private fun read(p: Preferences) = UiSettings(
        pointerSpeed = clampSpeed(p[POINTER]),
        scrollSpeed = clampSpeed(p[SCROLL]),
        naturalScroll = p[NATURAL] ?: true,
        rightClick = p[RIGHT]?.let { runCatching { RightClick.valueOf(it) }.getOrNull() } ?: RightClick.HOLD,
        clearModsOnSwitch = p[CLEAR_MODS] ?: false,
        releaseModsIdle = p[IDLE_MODS] ?: true,
        keepScreenOn = p[SCREEN_ON] ?: true,
        extraDim = p[DIM] ?: false,
        hapticStrength = p[H_STRENGTH]?.let { runCatching { HapticStrength.valueOf(it) }.getOrNull() } ?: HapticStrength.STANDARD,
        hapticKeys = p[H_KEYS] ?: true,
        hapticDetents = p[H_DETENTS] ?: true,
        hapticLink = p[H_LINK] ?: true,
        desktopCount = (p[DESKTOPS] ?: 4).coerceIn(1, 16),
        ctrlNumberShortcuts = p[CTRL_N] ?: false,
        spacesHintDone = p[SPACES_HINT] ?: false,
    )

    fun flow(c: Context): Flow<UiSettings> = c.store.data.map(::read)

    /**
     * Changes settings atomically: [f] runs on the stored value inside one DataStore edit, so two quick
     * changes (e.g. two toggles before the first write lands) both survive. Writing a whole snapshot
     * taken from the UI would overwrite the first change with stale values.
     */
    suspend fun update(c: Context, f: (UiSettings) -> UiSettings) {
        c.store.edit { p -> write(p, f(read(p))) }
    }

    suspend fun save(c: Context, s: UiSettings) = update(c) { s }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: UiSettings) {
        p.apply {
            this[POINTER] = clampSpeed(s.pointerSpeed); this[SCROLL] = clampSpeed(s.scrollSpeed)
            this[NATURAL] = s.naturalScroll; this[RIGHT] = s.rightClick.name
            this[CLEAR_MODS] = s.clearModsOnSwitch; this[IDLE_MODS] = s.releaseModsIdle
            this[SCREEN_ON] = s.keepScreenOn; this[DIM] = s.extraDim
            this[H_STRENGTH] = s.hapticStrength.name; this[H_KEYS] = s.hapticKeys
            this[H_DETENTS] = s.hapticDetents; this[H_LINK] = s.hapticLink
            this[DESKTOPS] = s.desktopCount.coerceIn(1, 16); this[CTRL_N] = s.ctrlNumberShortcuts; this[SPACES_HINT] = s.spacesHintDone
        }
    }
}
