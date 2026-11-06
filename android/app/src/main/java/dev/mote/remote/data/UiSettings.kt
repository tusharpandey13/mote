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
    )

    fun flow(c: Context): Flow<UiSettings> = c.store.data.map(::read)

    suspend fun save(c: Context, s: UiSettings) {
        c.store.edit {
            it[POINTER] = clampSpeed(s.pointerSpeed); it[SCROLL] = clampSpeed(s.scrollSpeed)
            it[NATURAL] = s.naturalScroll; it[RIGHT] = s.rightClick.name
            it[CLEAR_MODS] = s.clearModsOnSwitch; it[IDLE_MODS] = s.releaseModsIdle
            it[SCREEN_ON] = s.keepScreenOn; it[DIM] = s.extraDim
            it[H_STRENGTH] = s.hapticStrength.name; it[H_KEYS] = s.hapticKeys
            it[H_DETENTS] = s.hapticDetents; it[H_LINK] = s.hapticLink
        }
    }
}
