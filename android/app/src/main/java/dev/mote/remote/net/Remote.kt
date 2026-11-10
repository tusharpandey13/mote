package dev.mote.remote.net

import dev.mote.remote.hid.Hid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the UI talks to: every key, text, pointer, scroll, media and macro action goes to the
 * active transport (Bluetooth HID or Mote Helper), chosen by [Net.select].
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Remote {
    private val t: Transport get() = Net.active.value

    val linked: StateFlow<Boolean> = Net.active.flatMapLatest { it.linked }.stateIn(Net.scope, SharingStarted.Eagerly, false)
    val mods: StateFlow<Hid.Mods> = Net.active.flatMapLatest { it.mods }.stateIn(Net.scope, SharingStarted.Eagerly, Hid.Mods())
    /** Mac Spaces with thumbnails from the active transport; null over Bluetooth or a pre-1.3 helper. */
    val spaces: StateFlow<SpacesState?> = Net.active.flatMapLatest { it.spaces }.stateIn(Net.scope, SharingStarted.Eagerly, null)
    /** The active transport is the Mote Helper (Wi-Fi / Tailscale); false over Bluetooth. */
    val overHelper: StateFlow<Boolean> = Net.active.map { it is WsTransport }.stateIn(Net.scope, SharingStarted.Eagerly, false)
    val caps: StateFlow<Caps> = Net.active.map { it.caps }.stateIn(Net.scope, SharingStarted.Eagerly, Caps.HID)

    fun setMods(m: Hid.Mods) = t.setMods(m)
    fun tap(key: String, mods: Int = 0) = t.tap(key, mods)
    fun keyDown(key: String) = t.keyDown(key)
    fun keyUp(key: String) = t.keyUp(key)
    suspend fun tapAwait(key: String, mods: Int) = t.tapAwait(key, mods)
    suspend fun typeText(text: String): Int = t.typeText(text)
    fun consumer(usage: Int) = t.consumer(usage)
    fun consumerHold(usage: Int, down: Boolean) = t.consumerHold(usage, down)
    fun move(dx: Float, dy: Float) = t.move(dx, dy)
    fun setButton(mask: Int, down: Boolean) = t.setButton(mask, down)
    fun click(mask: Int) = t.click(mask)
    fun scroll(wheel: Int, pan: Int) = t.scroll(wheel, pan)
    fun clickAt(x: Float, y: Float): Boolean = t.clickAt(x, y)
    /** Switch the Mac to the 1-based desktop [index] of [display] (null = active) (helper `spaces.goto`, or ⌃N / ⌃arrows over Bluetooth). */
    fun spacesGoto(index: Int, display: String? = null) = t.spacesGoto(index, display)
    /** Labs (preview): raw `labs.*` message to the helper; false over Bluetooth. */
    fun sendLabs(json: String): Boolean = t.sendLabs(json)
}
