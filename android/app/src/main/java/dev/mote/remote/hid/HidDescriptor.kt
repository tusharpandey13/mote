package dev.mote.remote.hid

/**
 * Composite HID report descriptor: keyboard (ID 1), mouse with wheel + AC Pan (ID 2),
 * consumer control (ID 3).
 *
 * Byte layout adapted from Linkpad's HidDescriptors.kt (MIT, Copyright (c) 2026 Devdas Kumar,
 * https://github.com/Devdas-gupta/linkpad). See NOTICE at the repository root.
 */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3

    const val SDP_NAME = "Mote"
    const val SDP_DESCRIPTION = "Mote remote"
    const val SDP_PROVIDER = "Mote"
    /** 0xC0 = combo keyboard + pointing device (HID 1.1 SDP subclass). */
    const val SUBCLASS: Byte = 0xC0.toByte()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val DESCRIPTOR: ByteArray = bytes(
        // Keyboard, report ID 1: [mods][reserved][k1..k6] = 8 bytes
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_KEYBOARD,
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0xE0,       //   Usage Min (LeftCtrl)
        0x29, 0xE7,       //   Usage Max (RightGUI)
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08,
        0x81, 0x02,       //   Input (Data, Var, Abs): modifier bits
        0x95, 0x01, 0x75, 0x08,
        0x81, 0x01,       //   Input (Const): reserved byte
        0x95, 0x05, 0x75, 0x01,
        0x05, 0x08,       //   Usage Page (LEDs)
        0x19, 0x01, 0x29, 0x05,
        0x91, 0x02,       //   Output (Data, Var, Abs): LEDs
        0x95, 0x01, 0x75, 0x03,
        0x91, 0x01,       //   Output (Const): pad
        0x95, 0x06, 0x75, 0x08,
        0x15, 0x00, 0x25, 0x65,
        0x05, 0x07,
        0x19, 0x00, 0x29, 0x65,
        0x81, 0x00,       //   Input (Data, Array): 6 key slots
        0xC0,

        // Mouse, report ID 2: [buttons][dx][dy][wheel][pan] = 5 bytes
        0x05, 0x01,
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,
        0x85, ID_MOUSE,
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Buttons)
        0x19, 0x01, 0x29, 0x05,
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x05,
        0x81, 0x02,
        0x75, 0x03, 0x95, 0x01,
        0x81, 0x01,
        0x05, 0x01,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // X, Y, Wheel
        0x15, 0x81, 0x25, 0x7F,
        0x75, 0x08, 0x95, 0x03,
        0x81, 0x06,       //     Input (Data, Var, Rel)
        0x05, 0x0C,       //     Usage Page (Consumer)
        0x0A, 0x38, 0x02, //     Usage (AC Pan)
        0x15, 0x81, 0x25, 0x7F,
        0x95, 0x01,
        0x81, 0x06,
        0xC0,
        0xC0,

        // Consumer, report ID 3: 12-bit usage + 4-bit pad = 2 bytes LE
        0x05, 0x0C,
        0x09, 0x01,
        0xA1, 0x01,
        0x85, ID_CONSUMER,
        0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x0C, 0x95, 0x01,
        0x15, 0x00, 0x26, 0xFF, 0x03,
        0x81, 0x00,
        0x75, 0x04, 0x95, 0x01,
        0x81, 0x01,
        0xC0,
    )

    fun keyboardReport(mods: Int, keys: List<Int>): ByteArray {
        val r = ByteArray(8)
        r[0] = mods.toByte()
        keys.take(6).forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        return r
    }

    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray = byteArrayOf(
        buttons.toByte(), clamp(dx), clamp(dy), clamp(wheel), clamp(pan),
    )

    fun consumerReport(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0x0F).toByte())

    private fun clamp(v: Int): Byte = v.coerceIn(-127, 127).toByte()
}

/** Consumer page (0x0C) usages. */
object Consumer {
    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    const val PREV = 0xB6
    const val MUTE = 0xE2
    const val VOL_UP = 0xE9
    const val VOL_DOWN = 0xEA
    const val BRIGHTNESS_UP = 0x6F
    const val BRIGHTNESS_DOWN = 0x70
}
