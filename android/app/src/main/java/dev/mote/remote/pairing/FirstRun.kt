package dev.mote.remote.pairing

/** What the app opens into. */
enum class StartScreen { FIND_MAC, BT_GATE, REMOTE }

/**
 * Pure: the first screen. A fresh install opens into finding the Mac (Wi-Fi / Tailscale via Mote
 * Helper), with no permission asked; Bluetooth HID is the secondary path. People who already use
 * Bluetooth (they chose it, selected a Bluetooth host, or granted the Bluetooth HID permissions
 * before 0.6) keep the Bluetooth gate and the remote as before.
 */
fun startScreen(
    hasHelperHost: Boolean, btSelected: Boolean, macChosen: Boolean, btChosen: Boolean, btPermitted: Boolean, btReady: Boolean, skip: Boolean,
): StartScreen = when {
    skip -> StartScreen.REMOTE                       // debug demo, or a pairing link opened the app
    hasHelperHost && !btSelected -> StartScreen.REMOTE
    btSelected -> if (btReady) StartScreen.REMOTE else StartScreen.BT_GATE
    macChosen -> if (hasHelperHost) StartScreen.REMOTE else StartScreen.FIND_MAC
    btChosen || btPermitted -> if (btReady) StartScreen.REMOTE else StartScreen.BT_GATE
    hasHelperHost -> StartScreen.REMOTE
    else -> StartScreen.FIND_MAC
}

/** First-run choices, primary first. */
enum class FirstRunAction { CONNECT_MAC, USE_BLUETOOTH }
val FIRST_RUN_ACTIONS = listOf(FirstRunAction.CONNECT_MAC, FirstRunAction.USE_BLUETOOTH)

/** The discovery card's state; never an open-ended wait. */
sealed interface FindUi {
    data object Searching : FindUi
    data class Found(val macs: List<FoundMac>) : FindUi
    /** The phone isn't on Wi-Fi: "Turn on Wi-Fi" (Tailscale-only Macs still show up as Found). */
    data object WifiOff : FindUi
    /** Nothing after [NOT_FOUND_MS] (or every source finished): Retry, install, Enter address. */
    data object NotFound : FindUi
}

const val NOT_FOUND_MS = 5_000L

/** Pure: discovery card state from what was found so far. */
fun findUi(elapsedMs: Long, onWifi: Boolean, running: Boolean, found: List<FoundMac>): FindUi = when {
    found.isNotEmpty() -> FindUi.Found(found)
    !onWifi -> FindUi.WifiOff
    elapsedMs >= NOT_FOUND_MS || (!running && elapsedMs >= 1_000) -> FindUi.NotFound
    else -> FindUi.Searching
}

/**
 * Pure (U13): after a runtime-permission request came back denied, only the app's Settings can grant it when Android
 * no longer shows the dialog (denied twice, or "don't allow again"), i.e. none of the permissions has a rationale to show.
 */
fun needsAppSettings(permitted: Boolean, anyRationale: Boolean): Boolean = !permitted && !anyRationale
