package dev.mote.remote.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunTest {
    private fun start(
        helper: Boolean = false, btSel: Boolean = false, mac: Boolean = false, bt: Boolean = false,
        permitted: Boolean = false, ready: Boolean = false, skip: Boolean = false,
    ) = startScreen(helper, btSel, mac, bt, permitted, ready, skip)

    @Test fun freshInstallOpensIntoFindingTheMacWithoutAskingForBluetooth() {
        assertEquals(StartScreen.FIND_MAC, start())
        assertEquals(StartScreen.FIND_MAC, start(mac = true))
        assertEquals(listOf(FirstRunAction.CONNECT_MAC, FirstRunAction.USE_BLUETOOTH), FIRST_RUN_ACTIONS)
    }

    @Test fun bluetoothIsTheSecondaryPath() {
        assertEquals(StartScreen.BT_GATE, start(bt = true))
        assertEquals(StartScreen.REMOTE, start(bt = true, permitted = true, ready = true))
        // "Connect to your Mac instead" from the gate goes back to finding the Mac
        assertEquals(StartScreen.FIND_MAC, start(mac = true, permitted = true))
    }

    @Test fun existingUsersKeepTheirPath() {
        assertEquals(StartScreen.REMOTE, start(helper = true))
        assertEquals(StartScreen.REMOTE, start(helper = true, mac = true))
        assertEquals(StartScreen.REMOTE, start(btSel = true, permitted = true, ready = true))
        assertEquals(StartScreen.BT_GATE, start(btSel = true, permitted = true)) // Bluetooth turned off
        assertEquals(StartScreen.BT_GATE, start(permitted = true))             // granted Bluetooth before 0.6
        assertEquals(StartScreen.REMOTE, start(skip = true))
    }

    /** L4 finding 2026-11-14: Bluetooth host selected, Bluetooth off, "Connect to your Mac instead" looped back to the gate. */
    @Test fun connectToMacInsteadLeavesTheGateEvenWithABluetoothHostSelected() {
        assertEquals(StartScreen.BT_GATE, start(btSel = true, bt = true, permitted = true))
        assertEquals(StartScreen.FIND_MAC, start(btSel = true, mac = true, permitted = true))
        assertEquals(StartScreen.REMOTE, start(helper = true, btSel = true, mac = true, permitted = true))
    }

    private val mac = FoundMac("0123456789abcdef", "Air", null, listOf("192.168.1.2"), 8790, setOf(Source.LAN_SCAN))

    @Test fun discoveryNeverWaitsForever() {
        assertEquals(FindUi.Searching, findUi(300, onWifi = true, running = true, found = emptyList()))
        assertEquals(FindUi.Found(listOf(mac)), findUi(900, onWifi = true, running = true, found = listOf(mac)))
        assertEquals(FindUi.NotFound, findUi(NOT_FOUND_MS, onWifi = true, running = true, found = emptyList()))
        assertEquals(FindUi.NotFound, findUi(2000, onWifi = true, running = false, found = emptyList())) // every source done
        assertEquals(FindUi.Searching, findUi(0, onWifi = true, running = false, found = emptyList()))   // not started yet
        assertEquals(FindUi.WifiOff, findUi(100, onWifi = false, running = true, found = emptyList()))
        // a Mac found over BLE / Tailscale still shows with Wi-Fi off; found wins over the timeout
        assertEquals(FindUi.Found(listOf(mac)), findUi(60_000, onWifi = false, running = false, found = listOf(mac)))
    }
}
