package dev.mote.remote.hid

/** Modifier bits in the keyboard report (left-hand variants). */
object Mod {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val CMD = 0x08

    /** Display order on Mac: ⌃ ⌥ ⇧ ⌘. */
    val ALL = listOf(CTRL, ALT, SHIFT, CMD)
    val names = mapOf(CTRL to "ctrl", ALT to "opt", SHIFT to "shift", CMD to "cmd")
    val glyphs = mapOf(CTRL to "⌃", ALT to "⌥", SHIFT to "⇧", CMD to "⌘")

    fun glyphs(mask: Int) = ALL.filter { mask and it != 0 }.joinToString("") { glyphs.getValue(it) }
    fun fromName(n: String): Int = names.entries.firstOrNull { it.value == n }?.key ?: 0
    fun toNames(mask: Int): List<String> = ALL.filter { mask and it != 0 }.map { names.getValue(it) }
}

/**
 * HID keyboard usages (page 0x07) by stable name. Names are layout-independent and are
 * what the macro JSON stores.
 */
object Key {
    val usages: Map<String, Int> = buildMap {
        ('a'..'z').forEachIndexed { i, c -> put(c.toString(), 0x04 + i) }
        "1234567890".forEachIndexed { i, c -> put(c.toString(), 0x1E + i) }
        put("return", 0x28); put("esc", 0x29); put("backspace", 0x2A); put("tab", 0x2B); put("space", 0x2C)
        put("-", 0x2D); put("=", 0x2E); put("[", 0x2F); put("]", 0x30); put("\\", 0x31)
        put(";", 0x33); put("'", 0x34); put("`", 0x35); put(",", 0x36); put(".", 0x37); put("/", 0x38)
        put("capslock", 0x39)
        for (n in 1..12) put("f$n", 0x3A + n - 1)
        put("home", 0x4A); put("pageup", 0x4B); put("delete", 0x4C); put("end", 0x4D); put("pagedown", 0x4E)
        put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
    }

    /** Short legends for display. */
    val labels: Map<String, String> = mapOf(
        "return" to "⏎", "esc" to "esc", "backspace" to "⌫", "tab" to "⇥", "space" to "space",
        "delete" to "del", "home" to "home", "end" to "end", "pageup" to "pg up", "pagedown" to "pg dn",
        "left" to "←", "right" to "→", "up" to "↑", "down" to "↓",
    )

    fun usage(name: String): Int = usages[name] ?: 0
    fun label(name: String): String = labels[name] ?: name.uppercase()

    private const val BASE = "1234567890-=[]\\;'`,./"

    /** US-ANSI character to (key name, modifiers); null when not typeable. */
    fun forChar(c: Char): Pair<String, Int>? = when {
        c in 'a'..'z' -> c.toString() to 0
        c in 'A'..'Z' -> c.lowercaseChar().toString() to Mod.SHIFT
        c == ' ' -> "space" to 0
        c == '\n' -> "return" to 0
        c == '\t' -> "tab" to 0
        c in BASE -> c.toString() to 0
        else -> SHIFTED_TO_BASE[c]?.let { it.toString() to Mod.SHIFT }
    }

    private val SHIFTED_TO_BASE: Map<Char, Char> = mapOf(
        '!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5', '^' to '6', '&' to '7', '*' to '8',
        '(' to '9', ')' to '0', '_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\', ':' to ';',
        '"' to '\'', '~' to '`', '<' to ',', '>' to '.', '?' to '/',
    )
}
