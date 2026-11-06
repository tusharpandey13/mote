package dev.mote.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mote.remote.hid.Hid
import dev.mote.remote.service.HidService
import dev.mote.remote.ui.MoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MoteApp() }
    }

    override fun onStart() {
        super.onStart()
        Hid.foreground = true
        HidService.start(this)
    }

    override fun onStop() {
        Hid.foreground = false // the service stops itself unless a host is connected
        super.onStop()
    }
}
