package dev.mote.remote.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One SVG sub-path from the design icon set: filled and/or stroked (1.75 on a 24 grid). */
internal data class P(val d: String, val fill: Boolean, val stroke: Boolean)

private val cache = HashMap<List<P>, ImageVector>()

private fun vectorOf(parts: List<P>): ImageVector = cache.getOrPut(parts) {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (p in parts) {
        b.addPath(
            pathData = PathParser().parsePathString(p.d).toNodes(),
            fill = if (p.fill) SolidColor(Color.Black) else null,
            stroke = if (p.stroke) SolidColor(Color.Black) else null,
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    b.build()
}

/** Icons by name; unknown names fall back to "apps". Names match design/mote-v1/icons.js. */
internal object MoteIcons {
    private val byName: Map<String, List<P>> = with(IconData) {
        mapOf(
            "playPause" to playPause, "prev" to prev, "next" to next, "volDown" to volDown, "volUp" to volUp, "mute" to mute,
            "sunDown" to sunDown, "sunUp" to sunUp, "modeMedia" to modeMedia, "modeMouse" to modeMouse, "modeKeys" to modeKeys,
            "modeMacros" to modeMacros, "more" to more, "chevDown" to chevDown, "chevUp" to chevUp, "chevLeft" to chevLeft,
            "chevRight" to chevRight, "close" to close, "check" to check, "plus" to plus, "star" to star, "starFill" to starFill,
            "lock" to lock, "bluetooth" to bluetooth, "laptop" to laptop, "backspace" to backspace, "ret" to ret,
            "camera" to camera, "search" to search, "spaces" to spaces, "power" to power, "edit" to edit, "trash" to trash,
            "theme" to theme, "apps" to apps, "hold" to hold, "desktop" to desktop, "refresh" to refresh,
            "arrowUp" to arrowUp, "arrowDown" to arrowDown, "triUp" to triUp, "triDown" to triDown, "speaker" to speaker,
            "escape" to escape, "windows" to windows, "gear" to gear, "haptic" to haptic, "info" to info, "devices" to devices,
        ) + extra
    }
    // Not in design/mote-v1/icons.js yet (transport badges). Same 24 grid, 1.75 stroke.
    private val extra: Map<String, List<P>> get() = mapOf(
        "wifi" to listOf(
            P("M3.5 9.5a12 12 0 0 1 17 0M6.5 12.5a7.8 7.8 0 0 1 11 0M9.4 15.4a3.7 3.7 0 0 1 5.2 0", false, true),
            P("M11 18.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", true, false),
        ),
        "globe" to listOf(P("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0zM4 12h16M12 4c2.2 2.3 3.2 5 3.2 8s-1 5.7-3.2 8c-2.2-2.3-3.2-5-3.2-8s1-5.7 3.2-8z", false, true)),
        "pointer" to listOf(P("M12 3v4M12 17v4M3 12h4M17 12h4M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", false, true)),
    )
    /** Icons offered in the macro editor. */
    val macroChoices = listOf("apps", "search", "lock", "camera", "power", "spaces", "windows", "escape", "star", "refresh")

    fun get(name: String): ImageVector = vectorOf(byName[name] ?: IconData.apps)
}

@Composable
fun MIcon(name: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    Icon(MoteIcons.get(name), contentDescription = description, tint = tint, modifier = modifier.size(size))
}
