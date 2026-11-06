package dev.mote.remote.net

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mote.remote.hid.Mod
import kotlin.math.abs

/**
 * macOS Spaces on the Mac's main display, as reported by the helper (protocol 1.3 `spaces`).
 * [count] and 1-based [current] are 0 when unknown. [versions] holds the helper's thumbnail
 * version per desktop (index 1 first). [thumbs] maps a 1-based index to the newest thumbnail
 * received; it may lag [versions] briefly while a fetch is in flight.
 */
data class SpacesState(
    val count: Int,
    val current: Int,
    val capture: String,
    val versions: List<Int>,
    val thumbs: Map<Int, SpaceThumb>,
)

/**
 * A desktop thumbnail. Immutable: a new version is a new instance, so [image] (decoded lazily,
 * once) is effectively cached by index + version.
 */
class SpaceThumb(val index: Int, val version: Int, val jpeg: ByteArray) {
    val image: ImageBitmap? by lazy { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
}

/** Desktop switching over Bluetooth HID, where the Mac reports nothing back. Pure. */
object SpacesNav {
    /**
     * Key taps (key name, [Mod] bits) that move from [current] to [target], both 1-based:
     * ⌃N when [ctrlNumberShortcuts] is on and the target is 1-9 ("Switch to Desktop N" enabled on
     * the Mac), else |target - current| taps of ⌃← / ⌃→. [target] is clamped to 1..[count].
     */
    fun keys(target: Int, current: Int, count: Int, ctrlNumberShortcuts: Boolean): List<Pair<String, Int>> {
        val n = count.coerceIn(1, Proto.SPACES_MAX)
        val to = target.coerceIn(1, n)
        if (ctrlNumberShortcuts && to <= 9) return listOf(to.toString() to Mod.CTRL)
        val from = current.coerceIn(1, n)
        return List(abs(to - from)) { (if (to > from) "right" else "left") to Mod.CTRL }
    }
}
