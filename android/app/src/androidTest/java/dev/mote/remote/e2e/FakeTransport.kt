package dev.mote.remote.e2e

import dev.mote.remote.hid.TestSeam
import java.util.concurrent.CopyOnWriteArrayList

/** One HID report as the host would see it: report ID + unsigned payload bytes. */
data class Report(val id: Int, val bytes: List<Int>) {
    override fun toString() = "#$id[" + bytes.joinToString(" ") { "%02X".format(it) } + "]"
}

/** Records every report the app sends, in order. Installed through [TestSeam.sink] by [MoteRule]. */
class FakeTransport : TestSeam.Sink {
    private val sent = CopyOnWriteArrayList<Report>()

    override fun send(id: Int, data: ByteArray) {
        sent += Report(id, data.map { it.toInt() and 0xFF })
    }

    val reports: List<Report> get() = sent.toList()

    fun clear() = sent.clear()

    /**
     * Waits until the HID thread has gone quiet (no new report for [quietMs]) and returns everything
     * recorded so far. Reports are produced asynchronously on the single "mote-hid" thread.
     */
    fun settle(quietMs: Long = 250, timeoutMs: Long = 10_000): List<Report> {
        val end = System.currentTimeMillis() + timeoutMs
        var n = -1
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < end) {
            val now = sent.size
            if (now != n) { n = now; since = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - since >= quietMs) break
            Thread.sleep(20)
        }
        return reports
    }

    /** Waits until at least [count] reports have been recorded, then settles. */
    fun awaitAtLeast(count: Int, timeoutMs: Long = 10_000): List<Report> {
        val end = System.currentTimeMillis() + timeoutMs
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(10)
        return settle()
    }
}

/** Expected-report builders. Written from the HID spec (independent of HidDescriptor). */
object Rpt {
    const val KEYBOARD = 1
    const val MOUSE = 2
    const val CONSUMER = 3

    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val CMD = 0x08

    /** Keyboard: [mods][reserved][k1..k6]. */
    fun kb(mods: Int = 0, vararg keys: Int) = Report(KEYBOARD, listOf(mods, 0) + (keys.toList() + List(6) { 0 }).take(6))

    /** Mouse: [buttons][dx][dy][wheel][pan], signed bytes as two's complement. */
    fun mouse(buttons: Int = 0, dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0) =
        Report(MOUSE, listOf(buttons, dx and 0xFF, dy and 0xFF, wheel and 0xFF, pan and 0xFF))

    /** Consumer: 12-bit usage, little-endian. */
    fun consumer(usage: Int) = Report(CONSUMER, listOf(usage and 0xFF, (usage shr 8) and 0x0F))

    /** A keyboard tap of [usage] with chord modifiers, exactly as Hid.tapNow emits it. */
    fun tap(usage: Int, mods: Int = 0): List<Report> =
        if (mods == 0) listOf(kb(0, usage), kb(0))
        else listOf(kb(mods), kb(mods, usage), kb(mods), kb(0))

    fun signed(b: Int) = b.toByte().toInt()
}

/** HID keyboard usages (page 0x07) used by the tests. */
object Usage {
    fun letter(c: Char) = 0x04 + (c.lowercaseChar() - 'a')
    const val N1 = 0x1E
    const val N4 = 0x21
    const val RETURN = 0x28
    const val ESC = 0x29
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}
