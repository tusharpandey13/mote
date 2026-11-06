package dev.mote.remote.e2e

import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Key
import dev.mote.remote.net.Caps
import dev.mote.remote.net.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** One call the UI made on the active [Transport]. */
sealed interface Call {
    data class SetMods(val armed: Int, val locked: Int) : Call
    data class Tap(val key: String, val mods: Int = 0) : Call
    data class KeyDown(val key: String) : Call
    data class KeyUp(val key: String) : Call
    data class TapAwait(val key: String, val mods: Int = 0) : Call
    data class TypeText(val text: String) : Call
    data class Consumer(val usage: Int) : Call
    data class Move(val dx: Float, val dy: Float) : Call
    data class SetButton(val mask: Int, val down: Boolean) : Call
    data class Click(val mask: Int) : Call
    data class Scroll(val wheel: Int, val pan: Int) : Call
    data class ClickAt(val x: Float, val y: Float) : Call
}

/**
 * A [Transport] that records every call instead of sending it. Installed through
 * [dev.mote.remote.hid.TestSeam.transport] by [MoteRule]. [caps] decides which transport it stands in
 * for: [Caps.HID] (Bluetooth HID) or [Caps.HELPER] (Mote Helper over WebSocket). It follows the
 * Transport contract the real ones implement: one-shot (armed) modifiers clear after the next
 * non-modifier key; text the transport can't type is reported as skipped.
 */
class FakeTransport(override val caps: Caps = Caps.HID) : Transport {
    private val sent = CopyOnWriteArrayList<Call>()
    private fun record(c: Call) { sent += c }

    override val linked = MutableStateFlow(true)
    private val _mods = MutableStateFlow(Hid.Mods())
    override val mods: StateFlow<Hid.Mods> = _mods.asStateFlow()

    private fun consumeArmed() { _mods.value.let { if (it.armed != 0) _mods.value = it.copy(armed = 0) } }

    override fun setMods(m: Hid.Mods) { _mods.value = m; record(Call.SetMods(m.armed, m.locked)) }
    override fun tap(key: String, mods: Int) { record(Call.Tap(key, mods)); consumeArmed() }
    override fun keyDown(key: String) = record(Call.KeyDown(key))
    override fun keyUp(key: String) { record(Call.KeyUp(key)); consumeArmed() }
    override suspend fun tapAwait(key: String, mods: Int) = record(Call.TapAwait(key, mods))
    override suspend fun typeText(text: String): Int {
        record(Call.TypeText(text))
        return if (caps.unicodeText) 0 else text.count { Key.forChar(it) == null }
    }
    override fun consumer(usage: Int) = record(Call.Consumer(usage))
    override fun move(dx: Float, dy: Float) = record(Call.Move(dx, dy))
    override fun setButton(mask: Int, down: Boolean) = record(Call.SetButton(mask, down))
    override fun click(mask: Int) = record(Call.Click(mask))
    override fun scroll(wheel: Int, pan: Int) = record(Call.Scroll(wheel, pan))
    override fun clickAt(x: Float, y: Float): Boolean {
        if (!caps.clickAt) return false
        record(Call.ClickAt(x, y)); return true
    }

    val calls: List<Call> get() = sent.toList()

    fun clear() = sent.clear()

    /** Waits until no new call has arrived for [quietMs] and returns everything recorded so far. */
    fun settle(quietMs: Long = 200, timeoutMs: Long = 10_000): List<Call> {
        val end = System.currentTimeMillis() + timeoutMs
        var n = -1
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < end) {
            val now = sent.size
            if (now != n) { n = now; since = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - since >= quietMs) break
            Thread.sleep(20)
        }
        return calls
    }

    /** Waits until at least [count] calls have been recorded, then settles. */
    fun awaitAtLeast(count: Int, timeoutMs: Long = 10_000): List<Call> {
        val end = System.currentTimeMillis() + timeoutMs
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(10)
        return settle()
    }
}

/** Consumer page usages (written out, independent of the app's constants). */
object Usage {
    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    const val PREV = 0xB6
    const val MUTE = 0xE2
    const val VOL_UP = 0xE9
    const val VOL_DOWN = 0xEA
    const val BRIGHT_UP = 0x6F
    const val BRIGHT_DOWN = 0x70
}

object Mods {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val CMD = 0x08
}
