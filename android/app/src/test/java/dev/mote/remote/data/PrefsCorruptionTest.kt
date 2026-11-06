package dev.mote.remote.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Regression (chaos storage scenario): a corrupt DataStore file crashed the app on every launch. */
class PrefsCorruptionTest {
    @Test fun corruptFileResetsToDefaultsAndStaysWritable() = runBlocking {
        val dir = File.createTempFile("mote-prefs", "").apply { delete(); mkdirs() }
        val file = File(dir, "mote.preferences_pb").apply { writeBytes(Random(7).nextBytes(4096)) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(corruptionHandler = Prefs.corruptionHandler, scope = scope) { file }
            assertEquals(emptyPreferences(), store.data.first())
            val k = stringPreferencesKey("theme")
            store.edit { it[k] = "DARK" }
            assertEquals("DARK", store.data.first()[k])
        } finally {
            scope.cancel(); dir.deleteRecursively()
        }
    }

    /** Regression (chaos storage-settings-hostile): a String stored under a Float key crash-looped UiPrefs. */
    @Test fun wrongTypeReadsAsNull() {
        val stored = androidx.datastore.preferences.core.preferencesOf(stringPreferencesKey("pointer_speed") to "fast")
        val asFloat = androidx.datastore.preferences.core.floatPreferencesKey("pointer_speed")
        assertEquals(null, stored.typed(asFloat))
        assertEquals("fast", stored.typed(stringPreferencesKey("pointer_speed")))
    }
}
