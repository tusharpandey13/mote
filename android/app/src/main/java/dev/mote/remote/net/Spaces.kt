package dev.mote.remote.net

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

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
