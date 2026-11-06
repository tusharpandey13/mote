package dev.mote.remote.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Type-checked read. `prefs[key]` is an unchecked cast, so a value stored with another type (an older
 * or newer schema, a hand-edited file) threw ClassCastException at the call site on every launch.
 */
inline fun <reified T> Preferences.typed(key: Preferences.Key<T>): T? = asMap()[key] as? T

enum class ThemeChoice { LIGHT, DARK, SYSTEM }

/** All persisted state lives in one Preferences DataStore. */
object Prefs {
    /** Shared by every DataStore (mote, mote_settings, hosts): a corrupt file (bad flash, killed mid-write, tampering) resets to defaults instead of crashing every read. */
    internal val corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
    private val Context.store by preferencesDataStore("mote", corruptionHandler = corruptionHandler)
    private val HOST = stringPreferencesKey("selected_host")
    private val THEME = stringPreferencesKey("theme")
    private val MACROS = stringPreferencesKey("macros_json")

    fun selectedHost(c: Context): Flow<String?> = c.store.data.map { it.typed(HOST) }
    suspend fun setSelectedHost(c: Context, address: String) { c.store.edit { it[HOST] = address } }
    suspend fun clearSelectedHost(c: Context) { c.store.edit { it.remove(HOST) } }

    fun theme(c: Context): Flow<ThemeChoice> = c.store.data.map { p ->
        p.typed(THEME)?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.LIGHT
    }
    suspend fun setTheme(c: Context, t: ThemeChoice) { c.store.edit { it[THEME] = t.name } }

    fun macrosJson(c: Context): Flow<String?> = c.store.data.map { it.typed(MACROS) }
    suspend fun setMacrosJson(c: Context, json: String) { c.store.edit { it[MACROS] = json } }
}
