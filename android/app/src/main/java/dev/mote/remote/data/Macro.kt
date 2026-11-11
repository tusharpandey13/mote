package dev.mote.remote.data

import android.content.Context
import dev.mote.remote.hid.Hid
import dev.mote.remote.net.Remote
import dev.mote.remote.hid.Mod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface Step {
    data class Chord(val mods: Int, val key: String) : Step
    data class Text(val text: String) : Step
    data class Delay(val ms: Long) : Step
    /** Click at a normalised point (0..1) on the main display. Mote Helper hosts only. */
    data class ClickAt(val x: Float, val y: Float) : Step
}

/**
 * A macro is a named list of steps. [Kind.SPACES] is the built-in Spaces carousel
 * (hold + slide = ⌃←/⌃→, tap = ⌃↑) and has no steps.
 */
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "apps",
    val steps: List<Step> = emptyList(),
    val star: Boolean = false,
    val confirm: Boolean = false,
    val pointer: Boolean = false,
    val kind: Kind = Kind.STEPS,
) {
    enum class Kind { STEPS, SPACES }

    /** Chord shown under the tile name, e.g. "⌘⇧4". */
    val hint: String get() = when {
        kind == Kind.SPACES -> "swipe or tap a desktop"
        steps.size == 1 && steps[0] is Step.Chord -> (steps[0] as Step.Chord).let { Mod.glyphs(it.mods) + dev.mote.remote.hid.Key.label(it.key) }
        steps.size == 1 && steps[0] is Step.ClickAt -> (steps[0] as Step.ClickAt).let { "click ${(it.x * 100).toInt()}%, ${(it.y * 100).toInt()}%" }
        else -> "${steps.size} steps"
    }
}

object Macros {
    /** 2: favourites follow design rev 3+ (Spaces only by default); 3: rev 7 adds a starred Return. See [migrate]. */
    private const val VERSION = 3

    /** The favourites every install had before v2: Spaces + Spotlight + Lock filled the 4-column strip. */
    private val OLD_DEFAULT_FAVOURITES = setOf("spaces", "spotlight", "lock")

    /** The favourites of v2 defaults (rev 4-6): Spaces only. */
    private val V2_DEFAULT_FAVOURITES = setOf("spaces")

    /**
     * Revision 4 defaults: esc left Media and lives here as "Escape". Revision 7: the D-pad's OK became
     * the joystick, so Return is a starred macro right after Spaces.
     */
    val defaults: List<Macro> = listOf(
        Macro(id = "spaces", name = "Spaces", icon = "spaces", kind = Macro.Kind.SPACES, star = true),
        Macro(id = "return", name = "Return", icon = "ret", steps = listOf(Step.Chord(0, "return")), star = true),
        Macro(id = "spotlight", name = "Spotlight", icon = "search", steps = listOf(Step.Chord(Mod.CMD, "space"))),
        Macro(id = "escape", name = "Escape", icon = "escape", steps = listOf(Step.Chord(0, "esc"))),
        Macro(id = "mission", name = "Mission Control", icon = "windows", steps = listOf(Step.Chord(Mod.CTRL, "up"))),
        Macro(id = "shot", name = "Screenshot area", icon = "camera", steps = listOf(Step.Chord(Mod.CMD or Mod.SHIFT, "4")), pointer = true),
        Macro(id = "safari", name = "Open Safari", icon = "apps", steps = listOf(Step.Chord(Mod.CMD, "space"), Step.Delay(250), Step.Text("Safari"), Step.Delay(150), Step.Chord(0, "return"))),
        Macro(id = "lock", name = "Lock", icon = "lock", steps = listOf(Step.Chord(Mod.CTRL or Mod.CMD, "q")), confirm = true),
        Macro(id = "forcequit", name = "Force quit", icon = "power", steps = listOf(Step.Chord(Mod.ALT or Mod.CMD, "esc"))),
        Macro(id = "hideothers", name = "Hide others", icon = "windows", steps = listOf(Step.Chord(Mod.ALT or Mod.CMD, "h"))),
    )

    fun flow(c: Context): Flow<List<Macro>> = Prefs.macrosJson(c).map { it?.let(::load) ?: defaults }

    /** Decodes stored JSON and applies [migrate] for older format versions. */
    fun load(json: String): List<Macro>? {
        val list = decode(json) ?: return null
        val v = runCatching { JSONObject(json).optInt("v", 1) }.getOrDefault(1)
        return if (v < 3) migrateV3(if (v < 2) migrate(list) else list) else list
    }

    /**
     * v2 -> v3: if the favourites are still exactly the v2 defaults (Spaces only), add the starred
     * Return macro right after Spaces (or star an existing one). Custom favourites are untouched.
     */
    fun migrateV3(list: List<Macro>): List<Macro> {
        if (list.filter { it.star }.map { it.id }.toSet() != V2_DEFAULT_FAVOURITES) return list
        val ret = list.firstOrNull { it.id == "return" }?.copy(star = true) ?: defaults.first { it.id == "return" }
        val out = list.filter { it.id != "return" }.toMutableList()
        out.add((out.indexOfFirst { it.id == "spaces" } + 1).coerceIn(0, out.size), ret)
        return out
    }

    /**
     * v1 -> v2: if the stored favourites are still exactly the old defaults, keep only Spaces starred
     * (the strip then shows the "+ Add favourite" slot, so newly starred macros appear) and add the
     * Escape macro that replaced Media's esc cap. Any other favourites are the user's choice: untouched.
     * Applied on every read of a v1 list; the next save writes v2, which is never migrated again.
     */
    fun migrate(list: List<Macro>): List<Macro> {
        if (list.filter { it.star }.map { it.id }.toSet() != OLD_DEFAULT_FAVOURITES) return list
        val out = list.map { if (it.id == "spaces") it else it.copy(star = false) }.toMutableList()
        if (out.none { it.id == "escape" }) {
            val esc = defaults.first { it.id == "escape" }
            out.add((out.indexOfFirst { it.id == "spotlight" } + 1).coerceIn(0, out.size), esc)
        }
        return out
    }

    /** Applies [f] to the stored list atomically (see [Prefs.updateMacrosJson]). */
    suspend fun update(c: Context, f: (List<Macro>) -> List<Macro>) =
        Prefs.updateMacrosJson(c) { json -> encode(f(json?.let(::load) ?: defaults)) }

    fun encode(list: List<Macro>): String = JSONObject()
        .put("v", VERSION)
        .put("macros", JSONArray(list.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("icon", m.icon).put("star", m.star)
                .put("confirm", m.confirm).put("pointer", m.pointer).put("kind", m.kind.name.lowercase())
                .put("steps", JSONArray(m.steps.map(::encodeStep)))
        }))
        .toString()

    private fun encodeStep(s: Step): JSONObject = when (s) {
        is Step.Chord -> JSONObject().put("t", "chord").put("mods", JSONArray(Mod.toNames(s.mods))).put("key", s.key)
        is Step.Text -> JSONObject().put("t", "text").put("s", s.text)
        is Step.Delay -> JSONObject().put("t", "delay").put("ms", s.ms)
        is Step.ClickAt -> JSONObject().put("t", "clickAt").put("x", s.x.toDouble()).put("y", s.y.toDouble())
    }

    fun decode(json: String): List<Macro>? = runCatching {
        val arr = JSONObject(json).getJSONArray("macros")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val steps = o.optJSONArray("steps") ?: JSONArray()
            Macro(
                id = o.getString("id"), name = o.getString("name"), icon = o.optString("icon", "apps"),
                star = o.optBoolean("star"), confirm = o.optBoolean("confirm"), pointer = o.optBoolean("pointer"),
                kind = if (o.optString("kind") == "spaces") Macro.Kind.SPACES else Macro.Kind.STEPS,
                steps = (0 until steps.length()).mapNotNull { decodeStep(steps.getJSONObject(it)) },
            )
        }.distinctBy { it.id } // ids are lazy-grid keys: a duplicate crashes composition
    }.getOrNull()

    private fun decodeStep(o: JSONObject): Step? = when (o.optString("t")) {
        "chord" -> {
            val mods = o.optJSONArray("mods") ?: JSONArray()
            Step.Chord((0 until mods.length()).fold(0) { acc, i -> acc or Mod.fromName(mods.getString(i)) }, o.getString("key"))
        }
        "key" -> Step.Chord(0, o.getString("key"))
        "text" -> Step.Text(o.getString("s"))
        "delay" -> Step.Delay(o.getLong("ms"))
        "clickAt" -> Step.ClickAt(o.getDouble("x").toFloat().coerceIn(0f, 1f), o.getDouble("y").toFloat().coerceIn(0f, 1f))
        else -> null
    }

    /** Runs the steps on the active transport; cancellable between steps. Returns the skipped count (characters, or clicks on a Bluetooth host). */
    suspend fun run(m: Macro): Int {
        Remote.setMods(Hid.Mods())
        var skipped = 0
        for (s in m.steps) when (s) {
            is Step.Chord -> { Remote.tapAwait(s.key, s.mods); delay(10) }
            is Step.Text -> skipped += Remote.typeText(s.text)
            is Step.Delay -> delay(s.ms)
            is Step.ClickAt -> { if (!Remote.clickAt(s.x, s.y)) skipped++; delay(10) }
        }
        return skipped
    }
}
