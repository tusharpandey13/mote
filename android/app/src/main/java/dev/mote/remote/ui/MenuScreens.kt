package dev.mote.remote.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.data.HapticStrength
import dev.mote.remote.data.RightClick
import dev.mote.remote.data.ThemeChoice
import dev.mote.remote.data.UiSettings
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.well

/** Full-screen pages opened from the menu sheet. */
enum class MenuPage { SETTINGS, HAPTICS, ABOUT, SCREEN }

@Composable
fun appVersion(): String {
    val ctx = LocalContext.current
    return remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
}

/** ⋯ menu: Theme (segmented), Manage devices, Haptics, Settings, About Mote. */
@Composable
fun MenuSheet(
    theme: ThemeChoice,
    devicesSummary: String,
    onTheme: (ThemeChoice) -> Unit,
    onDevices: () -> Unit,
    onPage: (MenuPage) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalMote.current
    val s = LocalSettings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mote", style = T.headline, color = c.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
                MIcon("close", c.ink, description = "Close")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("theme", c.ink)
            Spacer(Modifier.width(24.dp))
            Text("Theme", style = T.label.copy(fontSize = 15.sp), color = c.ink, modifier = Modifier.weight(1f))
            Segmented(
                listOf(ThemeChoice.DARK, ThemeChoice.LIGHT, ThemeChoice.SYSTEM), theme,
                { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }, Modifier.width(216.dp), onSelect = onTheme,
            )
        }
        Spacer(Modifier.height(8.dp))
        MenuRow("devices", "Manage devices", devicesSummary, onDevices)
        MenuRow("haptic", "Haptics", s.hapticStrength.label) { onPage(MenuPage.HAPTICS) }
        MenuRow("gear", "Settings", "Pointer speed, scrolling, keyboard") { onPage(MenuPage.SETTINGS) }
        if (dev.mote.remote.labs.screen.liveScreenOn()) {
            MenuRow("laptop", "Screen (preview)", "See the Mac's screen, tap to click") { onPage(MenuPage.SCREEN) }
        }
        MenuRow("info", "About Mote", "Version ${appVersion()}") { onPage(MenuPage.ABOUT) }
    }
}

@Composable
private fun MenuRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val c = LocalMote.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClickLabel = title, onClick = onClick).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, c.ink)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.label.copy(fontSize = 15.sp), color = c.ink)
            Text(subtitle, style = T.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.muted, maxLines = 1)
        }
        MIcon("chevRight", c.muted, size = 20.dp)
    }
}

/** Page chrome: back chevron + title, the engraved wordmark on the right (same top bar height). */
@Composable
fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalMote.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
                MIcon("chevLeft", c.ink, description = "Back")
            }
            Text(title, style = T.title.copy(fontSize = 20.sp), color = c.ink, modifier = Modifier.weight(1f))
            WordmarkText()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) { content() }
    }
}

@Composable
private fun Overline(text: String) {
    val c = LocalMote.current
    Text(text.uppercase(), style = T.caps, color = c.subtle, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp))
}

private fun speedLabel(v: Float) = if (v == 1f) "1x (default)" else "%.2gx".format(v)

@Composable
fun SettingsPage(onBack: () -> Unit, update: ((UiSettings) -> UiSettings) -> Unit) {
    val s = LocalSettings.current
    PageScaffold("Settings", onBack) {
        Overline("Pointer")
        SettingsCard {
            SettingRow("Pointer speed", "${speedLabel(s.pointerSpeed)}. Higher moves farther per swipe.") {}
            MetalSlider(s.pointerSpeed, UiSettings.SPEED_MIN, UiSettings.SPEED_MAX, 8, "Pointer speed") { update { st -> st.copy(pointerSpeed = it) } }
            Spacer(Modifier.height(4.dp))
            SettingRow("Scroll speed", "${speedLabel(s.scrollSpeed)}. Two-finger scrolling on the touchpad.") {}
            MetalSlider(s.scrollSpeed, UiSettings.SPEED_MIN, UiSettings.SPEED_MAX, 8, "Scroll speed") { update { st -> st.copy(scrollSpeed = it) } }
            Spacer(Modifier.height(4.dp))
            SettingRow("Natural scrolling", "Content follows your fingers") {
                Switch(s.naturalScroll, "Natural scrolling") { update { st -> st.copy(naturalScroll = it) } }
            }
            Hairline()
            SettingRow("Right-click") {
                Segmented(RightClick.entries, s.rightClick, { if (it == RightClick.HOLD) "Hold" else "2-finger tap" }, Modifier.width(190.dp)) {
                    update { st -> st.copy(rightClick = it) }
                }
            }
        }
        Overline("Spaces")
        SettingsCard {
            SettingRow("Desktops", "${s.desktopCount} on the Mac. Sets the cards in the Spaces carousel over Bluetooth.") {}
            MetalSlider(s.desktopCount.toFloat(), 1f, 16f, 16, "Desktops") { update { st -> st.copy(desktopCount = it.toInt()) } }
            Spacer(Modifier.height(4.dp))
            SettingRow(
                "Use ⌃1–⌃9 shortcuts",
                "Turn on Switch to Desktop 1-9 on the Mac (System Settings, Keyboard Shortcuts, Mission Control). Off: Mote steps with ⌃← and ⌃→.",
            ) { Switch(s.ctrlNumberShortcuts, "Use control 1 to 9 shortcuts") { update { st -> st.copy(ctrlNumberShortcuts = it) } } }
        }
        Overline("Keyboard")
        SettingsCard {
            SettingRow("Clear modifiers on mode switch") { Switch(s.clearModsOnSwitch, "Clear modifiers on mode switch") { update { st -> st.copy(clearModsOnSwitch = it) } } }
            Hairline()
            SettingRow("Release modifiers after 30 s idle") { Switch(s.releaseModsIdle, "Release modifiers after 30 s idle") { update { st -> st.copy(releaseModsIdle = it) } } }
        }
        Overline("Screen")
        SettingsCard {
            SettingRow("Keep screen on while connected") { Switch(s.keepScreenOn, "Keep screen on while connected") { update { st -> st.copy(keepScreenOn = it) } } }
            Hairline()
            SettingRow("Extra dim", "For dark rooms and presentations") { Switch(s.extraDim, "Extra dim") { update { st -> st.copy(extraDim = it) } } }
        }
        dev.mote.remote.labs.LabsSection()
    }
}

@Composable
fun HapticsPage(onBack: () -> Unit, update: ((UiSettings) -> UiSettings) -> Unit) {
    val c = LocalMote.current
    val s = LocalSettings.current
    val haptics = LocalHaptics.current
    PageScaffold("Haptics", onBack) {
        Overline("Strength")
        Segmented(HapticStrength.entries, s.hapticStrength, { it.label }, Modifier.fillMaxWidth(), height = 48.dp) { update { st -> st.copy(hapticStrength = it) } }
        Overline("Try them")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Tick" to Haptic.TICK, "Click" to Haptic.CLICK, "Heavy" to Haptic.HEAVY, "Lock" to Haptic.LOCK, "Reject" to Haptic.REJECT).forEach { (label, hp) ->
                Cap(Modifier.weight(1f).height(48.dp), RoundedCornerShape(14.dp), needsLink = false, haptic = null, description = "Try $label",
                    onDown = { haptics?.preview(hp) }) {
                    Text(label, style = T.label.copy(fontWeight = FontWeight.Medium), color = c.ink, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            SettingRow("Key presses", "Tick on every key and D-pad step") { Switch(s.hapticKeys, "Key presses") { update { st -> st.copy(hapticKeys = it) } } }
            Hairline()
            SettingRow("Slider detents", "Ticks as scroll and volume speed change") { Switch(s.hapticDetents, "Slider detents") { update { st -> st.copy(hapticDetents = it) } } }
            Hairline()
            SettingRow("Connect and disconnect", "Distinct pulses when the link changes") { Switch(s.hapticLink, "Connect and disconnect") { update { st -> st.copy(hapticLink = it) } } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(16.dp)) {
            MIcon("info", c.muted, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Haptics follow Android's touch feedback setting. If you feel nothing, turn on Settings, then Sound & vibration, then Touch feedback.",
                style = T.body, color = c.muted,
            )
        }
    }
}

@Composable
fun AboutPage(onBack: () -> Unit) {
    val c = LocalMote.current
    val ctx = LocalContext.current
    PageScaffold("About Mote", onBack) {
        Overline("Version")
        SettingsCard {
            SettingRow("Mote ${appVersion()}", "Keyboard, mouse and media remote over Bluetooth HID") {}
        }
        Overline("Open-source licences")
        SettingsCard {
            SettingRow("Unbounded", "Wordmark font. SIL Open Font License 1.1") {}
            Hairline()
            SettingRow("Inter", "UI font. SIL Open Font License 1.1") {}
            Hairline()
            SettingRow("AndroidX and Jetpack Compose", "Apache License 2.0") {}
        }
        Overline("Source")
        SettingsCard {
            Box(Modifier.clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tusharpandey13/mote"))) }
            }) {
                SettingRow("github.com/tusharpandey13/mote", "Issues and releases") { MIcon("chevRight", c.muted, size = 20.dp) }
            }
        }
    }
}
