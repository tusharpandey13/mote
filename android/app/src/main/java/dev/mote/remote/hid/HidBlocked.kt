package dev.mote.remote.hid

private const val RELEASE = "dev.mote.remote"
private const val DEV = "dev.mote.remote.dev"

const val HID_BLOCKED_GENERIC = "Bluetooth HID is unavailable. Another app may be registered as a keyboard; close it and reopen Mote."
const val HID_BLOCKED_OTHER_MOTE = "Only one app can be a Bluetooth keyboard. Mote is also installed; close or uninstall it."

/** The other Mote build (release vs "Mote Dev"), or null for any other package. */
fun otherMotePackage(own: String): String? = when (own) { RELEASE -> DEV; DEV -> RELEASE; else -> null }

/** B8: Phase.BLOCKED text. Names the likely cause when the other Mote build is installed ([installed] asks the package manager). */
fun hidBlockedText(ownPackage: String, installed: (String) -> Boolean): String =
    if (otherMotePackage(ownPackage)?.let(installed) == true) HID_BLOCKED_OTHER_MOTE else HID_BLOCKED_GENERIC
