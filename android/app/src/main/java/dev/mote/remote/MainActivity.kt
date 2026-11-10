package dev.mote.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mote.remote.hid.Hid
import dev.mote.remote.net.Net
import dev.mote.remote.net.PairLink
import dev.mote.remote.service.HidService
import dev.mote.remote.ui.MoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        dev.mote.remote.diag.Events.log("lifecycle", "init", "activity" to "created", "restored" to (savedInstanceState != null))
        if (savedInstanceState == null) handleLink(intent)
        dev.mote.remote.labs.context.NpNotifier.start(this)
        enableEdgeToEdge()
        setContent { MoteApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** mote://pair links (helper pairing page) open the pairing sheet. */
    private fun handleLink(i: Intent?) {
        dev.mote.remote.labs.open.OpenTv.onIntent(i) // Labs: shared links pre-fill the Open… sheet
        val data = i?.dataString ?: return
        PairLink.parse(data)?.let { Net.pendingLink.value = it }
    }

    override fun onStart() {
        super.onStart()
        dev.mote.remote.diag.Perms.check(this, "start")
        dev.mote.remote.labs.context.NpNotifier.refreshPermission()
        Hid.foreground = true
        Net.foreground = true
        HidService.start(this)
    }

    override fun onStop() {
        Hid.foreground = false // the service stops itself unless a host is connected
        Net.foreground = false // the helper socket closes 30 s later
        super.onStop()
    }
}
