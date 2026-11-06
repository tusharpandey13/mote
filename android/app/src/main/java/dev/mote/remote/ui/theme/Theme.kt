package dev.mote.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.mote.remote.R

/** Token set from design/mote-v1/mote.css (revision 2): Silver (light, default) and Titanium (dark). */
@Immutable
data class MoteColors(
    val dark: Boolean,
    val chassisTop: Color, val chassisBot: Color, val surface1: Color, val well: Color,
    val keyTop: Color, val keyBot: Color, val keyEdge: Color,
    val ink: Color, val muted: Color, val subtle: Color, val outline: Color,
    val primTop: Color, val primBot: Color, val onPrim: Color,
    val accent: Color, val accentContainer: Color,
    val modTop: Color, val modBot: Color, val onMod: Color,
    val danger: Color, val dangerContainer: Color,
    val linkOk: Color, val linkWait: Color, val linkBad: Color,
    val hi: Color, val lo: Color, val shadow: Color, val wellShade: Color,
    val engrave: Color, val grain: Color, val dot: Color,
)

val Silver = MoteColors(
    dark = false,
    chassisTop = Color(0xFFEEEFF1), chassisBot = Color(0xFFD9DBDF), surface1 = Color(0xFFE9EAED), well = Color(0xFFCBCED3),
    keyTop = Color(0xFFFBFBFC), keyBot = Color(0xFFE6E7EA), keyEdge = Color(0xFFB4B8BE),
    ink = Color(0xFF1C1D20), muted = Color(0xFF50535A), subtle = Color(0xFF62656C), outline = Color(0xFFC3C6CB),
    primTop = Color(0xFF43464D), primBot = Color(0xFF26282C), onPrim = Color(0xFFF3F4F6),
    accent = Color(0xFF1D6FB8), accentContainer = Color(0xFFD3E3F3),
    modTop = Color(0xFFFFE14D), modBot = Color(0xFFF0C400), onMod = Color(0xFF1A1A1D),
    danger = Color(0xFFB3261E), dangerContainer = Color(0xFFF6D9D6),
    linkOk = Color(0xFF1F9A55), linkWait = Color(0xFFA87F00), linkBad = Color(0xFFC4302A),
    hi = Color(0xF2FFFFFF), lo = Color(0x29283C3C), shadow = Color(0x47263240), wellShade = Color(0x42141C28),
    engrave = Color(0xFFBFC2C7), grain = Color(0x05000000), dot = Color(0x14000000),
)

val Titanium = MoteColors(
    dark = true,
    chassisTop = Color(0xFF2C2E33), chassisBot = Color(0xFF1C1D21), surface1 = Color(0xFF27292E), well = Color(0xFF141518),
    keyTop = Color(0xFF3E4148), keyBot = Color(0xFF31343A), keyEdge = Color(0xFF15161A),
    ink = Color(0xFFE9EAEC), muted = Color(0xFFA9ACB2), subtle = Color(0xFF8F939A), outline = Color(0xFF3A3D44),
    primTop = Color(0xFF4B5059), primBot = Color(0xFF24272C), onPrim = Color(0xFF8FD3FF),
    accent = Color(0xFF6CC4FF), accentContainer = Color(0xFF1B2E3D),
    modTop = Color(0xFFFFE14D), modBot = Color(0xFFF0C400), onMod = Color(0xFF1A1400),
    danger = Color(0xFFFF6B66), dangerContainer = Color(0xFF3E1A1C),
    linkOk = Color(0xFF3DDC84), linkWait = Color(0xFFFFB020), linkBad = Color(0xFFFF6B66),
    hi = Color(0x1AFFFFFF), lo = Color(0x59000000), shadow = Color(0x8C000000), wellShade = Color(0xB3000000),
    engrave = Color(0xFF16171A), grain = Color(0x05FFFFFF), dot = Color(0x0FFFFFFF),
)

val LocalMote = staticCompositionLocalOf { Silver }

/** Unbounded Black (OFL), A-Z/0-9 subset: wordmark only. */
val Wordmark = FontFamily(Font(R.font.unbounded_black, FontWeight.Black))

@Composable
fun MoteTheme(colors: MoteColors, content: @Composable () -> Unit) {
    val scheme = if (colors.dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color(0xFF06121C), surface = colors.surface1, onSurface = colors.ink,
            onSurfaceVariant = colors.muted, background = colors.chassisBot, surfaceContainerLow = colors.surface1,
            outline = colors.outline, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White, surface = colors.surface1, onSurface = colors.ink,
            onSurfaceVariant = colors.muted, background = colors.chassisBot, surfaceContainerLow = colors.surface1,
            outline = colors.outline, error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalMote provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
