package dev.mote.remote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The two material primitives from the design (revision 1/2):
 * raised cap = vertical gradient + top bevel highlight + bottom shade + drop shadow;
 * recessed well = darker fill with an inner shade at the top lip and a light bottom edge.
 */
fun Modifier.raised(
    shape: Shape,
    pressed: Boolean = false,
    tone: Tone = Tone.NORMAL,
    depth: Float = if (pressed) 1f else 0f,
): Modifier = composed {
    val c = LocalMote.current
    val (top, bot, edge) = when (tone) {
        Tone.NORMAL -> Triple(c.keyTop, c.keyBot, c.keyEdge)
        Tone.PRIMARY -> Triple(c.primTop, c.primBot, Color.Black)
        Tone.MODIFIER -> Triple(c.modTop, c.modBot, Color(0xFFB08C00))
        Tone.DANGER -> Triple(c.dangerContainer, c.dangerContainer, c.danger)
    }
    this
        .graphicsLayer {
            // press: sink 1dp and shrink to 0.96 (design 5.1); depth is spring-animated by callers
            translationY = depth * 1.dp.toPx()
            val s = 1f - 0.04f * depth
            scaleX = s; scaleY = s
        }
        .shadow(3.dp * (1f - depth), shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(Brush.verticalGradient(if (pressed) listOf(bot, top) else listOf(top, bot)))
        .border(0.75.dp, edge.copy(alpha = if (c.dark) 0.9f else 0.55f), shape)
        .drawWithContent {
            drawContent()
            val px = 1.dp.toPx()
            if (pressed) {
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent), endY = 4.dp.toPx()))
            } else {
                val hi = if (tone == Tone.PRIMARY) Color.White.copy(alpha = 0.22f) else c.hi
                drawLine(hi, Offset(size.height / 4, px), Offset(size.width - size.height / 4, px), px)
                drawLine(c.lo, Offset(size.height / 4, size.height - px), Offset(size.width - size.height / 4, size.height - px), px)
            }
        }
}

enum class Tone { NORMAL, PRIMARY, MODIFIER, DANGER }

fun Modifier.well(shape: Shape): Modifier = composed {
    val c = LocalMote.current
    this
        .clip(shape)
        .background(c.well)
        .drawWithContent {
            drawRect(Brush.verticalGradient(listOf(c.wellShade, Color.Transparent), endY = 6.dp.toPx()))
            drawContent()
            val px = 1.dp.toPx()
            drawLine(c.hi.copy(alpha = c.hi.alpha * 0.8f), Offset(0f, size.height - px / 2), Offset(size.width, size.height - px / 2), px)
        }
}

/** Chassis: vertical gradient with a faint brushed vertical hairline grain. */
fun Modifier.chassis(): Modifier = composed {
    val c = LocalMote.current
    this
        .background(Brush.verticalGradient(listOf(c.chassisTop, c.chassisBot)))
        .drawWithContent {
            val step = 3.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(c.grain, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            drawContent()
        }
}
