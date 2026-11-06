package dev.mote.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression (chaos storage scenario): stored macros are untrusted input. */
class MacroDecodeTest {
    @Test fun duplicateIdsKeepTheFirst() {
        // ids are LazyGrid keys; a duplicate threw "Key ... was already used" and crashed the Macros screen
        val json = """{"v":2,"macros":[{"id":"a","name":"one"},{"id":"a","name":"two","kind":"spaces"},{"id":"b","name":"three"}]}"""
        assertEquals(listOf("a" to "one", "b" to "three"), Macros.decode(json)!!.map { it.id to it.name })
    }

    @Test fun malformedJsonIsNullNotACrash() {
        assertNull(Macros.decode("""{"v":2,"macros":[{"id":"""))
        assertNull(Macros.decode(""))
    }
}
