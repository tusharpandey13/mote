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

/**
 * Token set from design/mote-v1/mote.css (revision 4): Silver (light, default) and Titanium (dark).
 * Revision 4 gives each control family its own material: backplate, plastic (key*), rubber (rub*),
 * metal (metal*), glass (glass*) and well; see docs/design/design-language.md.
 */
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
    // revision 4 materials
    val dshadow: Color, val vignette: Color, val vignetteStop: Float,
    // revision 5 (docs/design/tokens.json themes.*.grainAlpha / brushedAlpha / sheen / color.bezel* / ringMetalSweep)
    val grainAlpha: Grain, val brushedPlate: Float, val brushedMetal: Float,
    val sheenPlate: List<Pair<Float, Color>>, val sheenMetal: List<Pair<Float, Color>>,
    val bezelTop: Color, val bezelBot: Color, val ringSweep: List<Color>, val glassTopLine: Color,
    val rubTop: Color, val rubBot: Color, val rubWall: Color,
    val metal: List<Color>, val metalEdge: Color, val metalInk: Color,
    val glassTop: Color, val glassBot: Color, val glassInk: Color, val glassGlow: Color,
    val glossTop: Float, val glossMid: Float, val glassSpec: Float, val glassRim: Float,
    val engraveLabel: Color, val engraveDark: Color, val engraveLight: Color,
)

/** Grain (Overlay) alpha per material, tokens.json themes.*.grainAlpha. */
@Immutable
data class Grain(val plate: Float, val plastic: Float, val rubber: Float, val metal: Float, val glass: Float)

private fun w(a: Int) = Color(a shl 24 or 0xFFFFFF)

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
    engrave = Color(0xFFC4C7CC), grain = Color(0x05000000), dot = Color(0x24000000),
    dshadow = Color(0x421E2632), vignette = Color(0x241E2632), vignetteStop = 0.55f,
    grainAlpha = Grain(plate = 0.11f, plastic = 0.08f, rubber = 0.12f, metal = 0.06f, glass = 0.04f), brushedPlate = 0.32f, brushedMetal = 0.42f,
    sheenPlate = listOf(0f to w(0), 0.30f to w(0x61), 0.52f to w(0), 0.74f to w(0x2E), 0.90f to w(0)),
    sheenMetal = listOf(0f to w(0), 0.35f to w(0x8C), 0.60f to w(0)),
    bezelTop = Color(0xFFB9BDC3), bezelBot = Color(0xFFD6D9DD), glassTopLine = w(0xE6),
    ringSweep = listOf(0xFFF2F3F5, 0xFFB8BCC2, 0xFFF7F8F9, 0xFFAEB2B9, 0xFFEEF0F2, 0xFFB3B7BE, 0xFFF5F6F7, 0xFFADB1B8, 0xFFF2F3F5).map { Color(it) },
    rubTop = Color(0xFFD3D5D9), rubBot = Color(0xFFC3C6CB), rubWall = Color(0xFF9EA2A9),
    metal = listOf(Color(0xFFFDFDFE), Color(0xFFD4D7DC), Color(0xFFBFC3C9), Color(0xFFE6E8EB)),
    metalEdge = Color(0xFF8D9198), metalInk = Color(0xFF3A3D43),
    glassTop = Color(0xEBFFFFFF), glassBot = Color(0xDBE2E8F0), glassInk = Color(0xFF1C1D20), glassGlow = Color(0x3840A0FF),
    glossTop = 0.95f, glossMid = 0.35f, glassSpec = 0.9f, glassRim = 0.25f,
    engraveLabel = Color(0xFFC4C7CC), engraveDark = Color(0x6B141C28), engraveLight = Color(0xFFFFFFFF),
)

val Titanium = MoteColors(
    dark = true,
    chassisTop = Color(0xFF26282D), chassisBot = Color(0xFF17181B), surface1 = Color(0xFF27292E), well = Color(0xFF0B0C0E),
    keyTop = Color(0xFF474A52), keyBot = Color(0xFF363940), keyEdge = Color(0xFF101114),
    ink = Color(0xFFE9EAEC), muted = Color(0xFFA9ACB2), subtle = Color(0xFF8F939A), outline = Color(0xFF3A3D44),
    primTop = Color(0xFF4B5059), primBot = Color(0xFF24272C), onPrim = Color(0xFF8FD3FF),
    accent = Color(0xFF6CC4FF), accentContainer = Color(0xFF1B2E3D),
    modTop = Color(0xFFFFE14D), modBot = Color(0xFFF0C400), onMod = Color(0xFF1A1400),
    danger = Color(0xFFFF6B66), dangerContainer = Color(0xFF3E1A1C),
    linkOk = Color(0xFF3DDC84), linkWait = Color(0xFFFFB020), linkBad = Color(0xFFFF6B66),
    hi = Color(0x1AFFFFFF), lo = Color(0x59000000), shadow = Color(0x8C000000), wellShade = Color(0xB3000000),
    engrave = Color(0xFF1C1D21), grain = Color(0x05FFFFFF), dot = Color(0x1AFFFFFF),
    dshadow = Color(0x8C000000), vignette = Color(0x73000000), vignetteStop = 0.50f,
    grainAlpha = Grain(plate = 0.15f, plastic = 0.11f, rubber = 0.15f, metal = 0.08f, glass = 0.06f), brushedPlate = 0.42f, brushedMetal = 0.42f,
    sheenPlate = listOf(0f to w(0), 0.30f to w(0x12), 0.52f to w(0), 0.74f to w(0x0A), 0.90f to w(0)),
    sheenMetal = listOf(0f to w(0), 0.35f to w(0x59), 0.60f to w(0)),
    bezelTop = Color(0xFF08090A), bezelBot = Color(0xFF16171A), glassTopLine = w(0x59),
    ringSweep = listOf(0xFF8E939A, 0xFF4B4F56, 0xFF9A9FA6, 0xFF464A51, 0xFF8B9097, 0xFF484C53, 0xFF969BA2, 0xFF43474E, 0xFF8E939A).map { Color(it) },
    rubTop = Color(0xFF2B2D32), rubBot = Color(0xFF232529), rubWall = Color(0xFF0E0F11),
    metal = listOf(Color(0xFFC9CDD3), Color(0xFF8C9198), Color(0xFF6F747C), Color(0xFFA3A8AF)),
    metalEdge = Color(0xFF2A2C30), metalInk = Color(0xFF26282C),
    glassTop = Color(0xC7464E5C), glassBot = Color(0xEB161A20), glassInk = Color(0xFF9ED9FF), glassGlow = Color(0x596CC4FF),
    glossTop = 0.28f, glossMid = 0.08f, glassSpec = 0.35f, glassRim = 0.06f,
    engraveLabel = Color(0xFF4F535B), engraveDark = Color(0xD9000000), engraveLight = Color(0x17FFFFFF),
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
