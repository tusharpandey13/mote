package dev.mote.remote.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderTest {
    @Test fun keyboardReportLayout() {
        val r = HidDescriptor.keyboardReport(Mod.CMD or Mod.SHIFT, listOf(Key.usage("4")))
        assertArrayEquals(byteArrayOf(0x0A, 0, 0x21, 0, 0, 0, 0, 0), r)
    }

    @Test fun mouseReportClamps() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 1, -1), HidDescriptor.mouseReport(1, 500, -500, 1, -1))
    }

    @Test fun consumerIsLittleEndian12Bit() {
        assertArrayEquals(byteArrayOf(0x6F, 0), HidDescriptor.consumerReport(Consumer.BRIGHTNESS_UP))
        assertArrayEquals(byteArrayOf(0x38, 0x02), HidDescriptor.consumerReport(0x238))
    }

    @Test fun usTyping() {
        assertEquals("a" to 0, Key.forChar('a'))
        assertEquals("a" to Mod.SHIFT, Key.forChar('A'))
        assertEquals("1" to Mod.SHIFT, Key.forChar('!'))
        assertEquals("/" to Mod.SHIFT, Key.forChar('?'))
        assertEquals("space" to 0, Key.forChar(' '))
        assertNull(Key.forChar('é'))
    }

    @Test fun usages() {
        assertEquals(0x04, Key.usage("a"))
        assertEquals(0x27, Key.usage("0"))
        assertEquals(0x45, Key.usage("f12"))
        assertEquals(0x52, Key.usage("up"))
    }

    @Test fun descriptorCollectionsBalanced() {
        val d = HidDescriptor.DESCRIPTOR.map { it.toInt() and 0xFF }
        // count 0xA1 (Collection) items vs 0xC0 (End Collection) by walking short items
        var i = 0; var open = 0
        while (i < d.size) {
            val b = d[i]; val size = when (b and 0x03) { 3 -> 4; else -> b and 0x03 }
            if (b == 0xA1) open++
            if (b == 0xC0) open--
            i += 1 + size
        }
        assertEquals(0, open)
    }
}
