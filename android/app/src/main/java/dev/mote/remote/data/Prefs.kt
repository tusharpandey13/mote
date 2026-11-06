package dev.mote.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeChoice { LIGHT, DARK, SYSTEM }

/** All persisted state lives in one Preferences DataStore. */
object Prefs {
    private val Context.store by preferencesDataStore("mote")
    private val HOST = stringPreferencesKey("selected_host")
    private val THEME = stringPreferencesKey("theme")
    private val MACROS = stringPreferencesKey("macros_json")

    fun selectedHost(c: Context): Flow<String?> = c.store.data.map { it[HOST] }
    suspend fun setSelectedHost(c: Context, address: String) { c.store.edit { it[HOST] = address } }

    fun theme(c: Context): Flow<ThemeChoice> = c.store.data.map { p ->
        p[THEME]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.LIGHT
    }
    suspend fun setTheme(c: Context, t: ThemeChoice) { c.store.edit { it[THEME] = t.name } }

    fun macrosJson(c: Context): Flow<String?> = c.store.data.map { it[MACROS] }
    suspend fun setMacrosJson(c: Context, json: String) { c.store.edit { it[MACROS] = json } }
}
