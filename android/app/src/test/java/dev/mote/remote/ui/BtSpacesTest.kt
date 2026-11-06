package dev.mote.remote.ui

import dev.mote.remote.hid.Mod
import dev.mote.remote.ui.BtSpaces.Chord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtSpacesTest {
    @Test fun sameDesktopSendsNothing() = assertTrue(BtSpaces.plan(3, 3, ctrlNumbers = true).isEmpty())

    @Test fun ctrlNumberJumpsDirectlyUpToNine() {
        assertEquals(listOf(Chord("7", Mod.CTRL)), BtSpaces.plan(1, 7, ctrlNumbers = true))
        assertEquals(listOf(Chord("1", Mod.CTRL)), BtSpaces.plan(9, 1, ctrlNumbers = true))
    }

    @Test fun beyondNineOrWithoutShortcutsSteps() {
        assertEquals(List(3) { Chord("right") }, BtSpaces.plan(1, 4, ctrlNumbers = false))
        assertEquals(List(2) { Chord("left") }, BtSpaces.plan(4, 2, ctrlNumbers = false))
        assertEquals(List(2) { Chord("right") }, BtSpaces.plan(9, 11, ctrlNumbers = true))
    }
}
