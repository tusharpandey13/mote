package dev.mote.remote.net

import dev.mote.remote.hid.Hid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What a transport can do beyond the HID baseline (docs/research/network-transport.md section 5). */
data class Caps(
    val unicodeText: Boolean = false,       // layout-independent `text`
    val clickAt: Boolean = false,           // absolute click at a point
    val preciseBrightness: Boolean = false, // helper brightness path (DisplayServices fallback)
) {
    companion object {
        val HID = Caps()
        val HELPER = Caps(unicodeText = true, clickAt = true, preciseBrightness = true)
    }
}

/**
 * Everything the UI sends to the Mac. Key names are [dev.mote.remote.hid.Key] names, modifiers
 * are [dev.mote.remote.hid.Mod] bits, mouse buttons are [Hid.BUTTON_LEFT] / [Hid.BUTTON_RIGHT],
 * consumer keys are [dev.mote.remote.hid.Consumer] usages. Calls are fire-and-forget and ordered.
 */
interface Transport {
    val caps: Caps
    val linked: StateFlow<Boolean>
    /** Sticky modifiers (armed one-shot / locked). */
    val mods: StateFlow<Hid.Mods>
    fun setMods(m: Hid.Mods)

    fun tap(key: String, mods: Int = 0)
    fun keyDown(key: String)
    fun keyUp(key: String)
    suspend fun tapAwait(key: String, mods: Int)
    /** Returns the number of characters it couldn't type. */
    suspend fun typeText(text: String): Int
    fun consumer(usage: Int)
    /**
     * Press ([down] = true) or release a held consumer key: [dev.mote.remote.hid.Consumer.FAST_FORWARD] /
     * [dev.mote.remote.hid.Consumer.REWIND] for hold-to-seek. No-op by default.
     */
    fun consumerHold(usage: Int, down: Boolean) {}

    fun move(dx: Float, dy: Float)
    fun setButton(mask: Int, down: Boolean)
    fun click(mask: Int)
    /** Positive wheel = fingers up (content toward the end with natural scrolling), positive pan = right. */
    fun scroll(wheel: Int, pan: Int)
    /** Normalised point on the main display. False when the transport can't do it. */
    fun clickAt(x: Float, y: Float): Boolean

    /** Mac Spaces (protocol 1.3) with thumbnails; null when unknown (Bluetooth, older helper, not linked). */
    val spaces: StateFlow<SpacesState?> get() = NO_SPACES
    /**
     * Switch the Mac to the 1-based desktop [index] (helper: `spaces.goto`). No-op by default: over
     * Bluetooth the UI drives ⌃N / ⌃←⌃→ itself (`ui/BtSpaces`), since HID gets no Spaces feedback.
     */
    fun spacesGoto(index: Int) {}
}

private val NO_SPACES: StateFlow<SpacesState?> = MutableStateFlow(null)

/** Bluetooth HID: a thin wrapper over [Hid], which stays the owner of the HID registration and pipe. */
class BtHidTransport(scope: CoroutineScope) : Transport {
    override val caps = Caps.HID
    override val linked: StateFlow<Boolean> = Hid.state.map { it.isConnected }.stateIn(scope, SharingStarted.Eagerly, false)
    override val mods: StateFlow<Hid.Mods> get() = Hid.mods
    override fun setMods(m: Hid.Mods) = Hid.setMods(m)
    override fun tap(key: String, mods: Int) = Hid.tap(key, mods)
    override fun keyDown(key: String) = Hid.keyDown(key)
    override fun keyUp(key: String) = Hid.keyUp(key)
    override suspend fun tapAwait(key: String, mods: Int) = Hid.tapAwait(key, mods)
    override suspend fun typeText(text: String): Int = Hid.typeText(text)
    override fun consumer(usage: Int) = Hid.consumer(usage)
    override fun consumerHold(usage: Int, down: Boolean) = Hid.consumerHold(usage, down)
    override fun move(dx: Float, dy: Float) = Hid.move(dx, dy)
    override fun setButton(mask: Int, down: Boolean) = Hid.setButton(mask, down)
    override fun click(mask: Int) = Hid.click(mask)
    override fun scroll(wheel: Int, pan: Int) = Hid.scroll(wheel, pan)
    override fun clickAt(x: Float, y: Float) = false
}
