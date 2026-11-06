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

    private fun v1(list: List<Macro>) = Macros.encode(list).replace("\"v\":2", "\"v\":1")
    private fun stars(l: List<Macro>) = l.filter { it.star }.map { it.id }

    @Test fun defaultsStarOnlySpaces() = assertEquals(listOf("spaces"), stars(Macros.defaults))

    @Test fun oldDefaultFavouritesMigrateToSpacesOnly() {
        val out = Macros.load(v1(v1Defaults))!!
        assertEquals(listOf("spaces"), stars(out))
        assertEquals(listOf("spaces", "spotlight", "escape", "lock", "mission"), out.map { it.id })
    }

    @Test fun customFavouritesAreLeftAlone() {
        val custom = v1Defaults.map { if (it.id == "mission") it.copy(star = true) else it }
        assertEquals(custom, Macros.load(v1(custom)))
        val fewer = v1Defaults.map { if (it.id == "lock") it.copy(star = false) else it }
        assertEquals(fewer, Macros.load(v1(fewer)))
    }

    @Test fun savedV2IsNeverMigrated() {
        // a v2 user who re-stars exactly the old set keeps it
        assertEquals(stars(v1Defaults), stars(Macros.load(Macros.encode(v1Defaults))!!))
    }

    @Test fun migrationIsIdempotent() {
        val once = Macros.migrate(v1Defaults)
        assertEquals(once, Macros.migrate(once))
        assertTrue(once.count { it.id == "escape" } == 1)
    }
}
