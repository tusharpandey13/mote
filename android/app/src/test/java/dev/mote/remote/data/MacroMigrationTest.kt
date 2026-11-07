package dev.mote.remote.data

import dev.mote.remote.hid.Mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroMigrationTest {
    private val v1Defaults = listOf(
        Macro(id = "spaces", name = "Spaces", icon = "spaces", kind = Macro.Kind.SPACES, star = true),
        Macro(id = "spotlight", name = "Spotlight", icon = "search", steps = listOf(Step.Chord(Mod.CMD, "space")), star = true),
        Macro(id = "lock", name = "Lock", icon = "lock", steps = listOf(Step.Chord(Mod.CTRL or Mod.CMD, "q")), star = true, confirm = true),
        Macro(id = "mission", name = "Mission Control", icon = "apps", steps = listOf(Step.Chord(Mod.CTRL, "up"))),
    )

    private fun v1(list: List<Macro>) = Macros.encode(list).replace("\"v\":3", "\"v\":1")
    private fun v2(list: List<Macro>) = Macros.encode(list).replace("\"v\":3", "\"v\":2")
    private fun stars(l: List<Macro>) = l.filter { it.star }.map { it.id }

    @Test fun defaultsStarSpacesThenReturn() = assertEquals(listOf("spaces", "return"), stars(Macros.defaults))

    @Test fun oldDefaultFavouritesMigrateToSpacesOnly() {
        val out = Macros.load(v1(v1Defaults))!!
        // v1 -> v2 leaves Spaces only, then v2 -> v3 adds Return after it
        assertEquals(listOf("spaces", "return"), stars(out))
        assertEquals(listOf("spaces", "return", "spotlight", "escape", "lock", "mission"), out.map { it.id })
    }

    @Test fun customFavouritesAreLeftAlone() {
        val custom = v1Defaults.map { if (it.id == "mission") it.copy(star = true) else it }
        assertEquals(custom, Macros.load(v1(custom)))
        val fewer = v1Defaults.map { if (it.id == "lock") it.copy(star = false) else it }
        assertEquals(fewer, Macros.load(v1(fewer)))
    }

    @Test fun savedV3IsNeverMigrated() {
        // a v3 user who re-stars exactly the old set keeps it
        assertEquals(stars(v1Defaults), stars(Macros.load(Macros.encode(v1Defaults))!!))
        val spacesOnly = Macros.defaults.map { if (it.id == "return") it.copy(star = false) else it }
        assertEquals(spacesOnly, Macros.load(Macros.encode(spacesOnly)))
    }

    @Test fun v2DefaultFavouritesGainReturnAfterSpaces() {
        val v2Defaults = Macros.defaults.filter { it.id != "return" }
        val out = Macros.load(v2(v2Defaults))!!
        assertEquals(listOf("spaces", "return"), stars(out))
        assertEquals(Macros.defaults, out)
    }

    @Test fun v2CustomFavouritesGetNoReturn() {
        val custom = Macros.defaults.filter { it.id != "return" }.map { if (it.id == "lock") it.copy(star = true) else it }
        assertEquals(custom, Macros.load(v2(custom)))
        val none = Macros.defaults.filter { it.id != "return" }.map { it.copy(star = false) }
        assertEquals(none, Macros.load(v2(none)))
    }

    @Test fun migrationIsIdempotent() {
        val once = Macros.migrate(v1Defaults)
        assertEquals(once, Macros.migrate(once))
        assertTrue(once.count { it.id == "escape" } == 1)
    }
}
