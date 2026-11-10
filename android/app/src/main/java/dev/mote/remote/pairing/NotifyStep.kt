package dev.mote.remote.pairing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.diag.Events
import dev.mote.remote.diag.Perms
import dev.mote.remote.labs.context.NpNotifier
import dev.mote.remote.ui.PrimaryButton
import dev.mote.remote.ui.SecondaryButton
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.launch

/** Pure (U4): ask for POST_NOTIFICATIONS in first-run setup only on Android 13+, only if not granted, and only once. */
fun shouldAskNotifications(sdk: Int, granted: Boolean, alreadyAsked: Boolean) = sdk >= 33 && !granted && !alreadyAsked

/**
 * First-run setup step (U4): explains what notifications are for, then asks once. Denied: no media notification;
 * Settings › Diagnostics shows the permission and the now-playing row says why.
 */
@Composable
fun NotifyStep() {
    val ctx = LocalContext.current
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Perms.state(ctx)["notifications"] == true) }
    val asked by remember { UiPrefs.notifAsked(ctx) }.collectAsState(initial = true)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Perms.result(ctx, "setup")
        granted = ok
        NpNotifier.refreshPermission()
        NpNotifier.askedAndDenied.value = !ok
        scope.launch { UiPrefs.setNotifAsked(ctx) }
    }
    if (!shouldAskNotifications(Build.VERSION.SDK_INT, granted, asked)) return
    Column(
        Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Allow notifications?", style = T.label, color = c.ink)
        Text(
            "Mote shows what your Mac is playing, with play, pause and skip, on the lock screen, and keeps the link alive while the screen is off. You can turn it off any time in Settings.",
            style = T.small, color = c.muted,
        )
        PrimaryButton("Allow notifications") {
            Perms.requested(listOf(Manifest.permission.POST_NOTIFICATIONS), "setup")
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        SecondaryButton("Not now") {
            Events.log("permission", "result", "from" to "setup", "choice" to "not-now")
            scope.launch { UiPrefs.setNotifAsked(ctx) }
        }
    }
}
