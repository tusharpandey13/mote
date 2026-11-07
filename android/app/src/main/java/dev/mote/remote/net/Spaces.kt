package dev.mote.remote.net

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * macOS Spaces as reported by the helper (protocol 1.3 / 1.4 `spaces`). [count], 1-based [current],
 * [versions] and [thumbs] describe the **active** display (a 1.3 helper: the main display); they
 * follow the active display live. [count] and [current] are 0 when unknown. [versions] holds the
 * helper's thumbnail version per desktop (index 1 first). [thumbs] maps a 1-based index to the
 * newest thumbnail received; it may lag [versions] briefly while a fetch is in flight.
 *
 * Protocol 1.4: [displays] lists every display in macOS order (empty from a 1.3 helper),
 * [activeDisplay] is the [DisplaySpaces.uuid] of the active one, [shared] is true when
 * "Displays have separate Spaces" is off (one list, uuid `Main`).
 */
data class SpacesState(
    val count: Int,
    val current: Int,
    val capture: String,
    val versions: List<Int>,
    val thumbs: Map<Int, SpaceThumb>,
    val displays: List<DisplaySpaces> = emptyList(),
    val activeDisplay: String? = null,
    val shared: Boolean = false,
) {
    /** The active display's entry, if the helper reports displays (1.4). */
    val active: DisplaySpaces? get() = displays.firstOrNull { it.uuid == activeDisplay }
}

/** One Mac display's Spaces (protocol 1.4): [uuid] (or `Main` when shared), its name, desktop [count] and 1-based [current]. */
data class DisplaySpaces(val uuid: String, val name: String, val count: Int, val current: Int)

/**
 * A desktop thumbnail. Immutable: a new version is a new instance, so [image] (decoded lazily,
 * once) is effectively cached by index + version.
 */
class SpaceThumb(val index: Int, val version: Int, val jpeg: ByteArray) {
    val image: ImageBitmap? by lazy { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
}
