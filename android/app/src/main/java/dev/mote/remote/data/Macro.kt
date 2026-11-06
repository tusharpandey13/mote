package dev.mote.remote.data

import android.content.Context
import dev.mote.remote.hid.Hid
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
}

/**
 * A macro is a named list of steps. [Kind.SPACES] is the built-in gesture tile
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
        kind == Kind.SPACES -> "⌃← ⌃→"
        steps.size == 1 && steps[0] is Step.Chord -> (steps[0] as Step.Chord).let { Mod.glyphs(it.mods) + dev.mote.remote.hid.Key.label(it.key) }
        else -> "${steps.size} steps"
    }
}

object Macros {
    private const val VERSION = 1

    val defaults: List<Macro> = listOf(
        Macro(id = "spaces", name = "Spaces", icon = "spaces", kind = Macro.Kind.SPACES, star = true),
        Macro(id = "spotlight", name = "Spotlight", icon = "search", steps = listOf(Step.Chord(Mod.CMD, "space")), star = true),
        Macro(id = "lock", name = "Lock", icon = "lock", steps = listOf(Step.Chord(Mod.CTRL or Mod.CMD, "q")), star = true, confirm = true),
        Macro(id = "mission", name = "Mission Control", icon = "apps", steps = listOf(Step.Chord(Mod.CTRL, "up"))),
        Macro(id = "shot", name = "Screenshot area", icon = "camera", steps = listOf(Step.Chord(Mod.CMD or Mod.SHIFT, "4")), pointer = true),
    )

    fun flow(c: Context): Flow<List<Macro>> = Prefs.macrosJson(c).map { it?.let(::decode) ?: defaults }

    suspend fun save(c: Context, list: List<Macro>) = Prefs.setMacrosJson(c, encode(list))

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
        }
    }.getOrNull()

    private fun decodeStep(o: JSONObject): Step? = when (o.optString("t")) {
        "chord" -> {
            val mods = o.optJSONArray("mods") ?: JSONArray()
            Step.Chord((0 until mods.length()).fold(0) { acc, i -> acc or Mod.fromName(mods.getString(i)) }, o.getString("key"))
        }
        "key" -> Step.Chord(0, o.getString("key"))
        "text" -> Step.Text(o.getString("s"))
        "delay" -> Step.Delay(o.getLong("ms"))
        else -> null
    }

    /** Runs the steps in order; cancellable between steps. Returns skipped character count. */
    suspend fun run(m: Macro): Int {
        Hid.setMods(Hid.Mods())
        var skipped = 0
        for (s in m.steps) when (s) {
            is Step.Chord -> { Hid.tapAwait(s.key, s.mods); delay(10) }
            is Step.Text -> skipped += Hid.typeText(s.text)
            is Step.Delay -> delay(s.ms)
        }
        return skipped
    }
}
