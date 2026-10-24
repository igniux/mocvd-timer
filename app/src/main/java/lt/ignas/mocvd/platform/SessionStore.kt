package lt.ignas.mocvd.platform

import android.content.Context
import android.util.AtomicFile
import lt.ignas.mocvd.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedState(val session: Session = Session(), val preferences: Preferences = Preferences())

/** One atomic snapshot, including handled reminder IDs; no separate preference/session writes. */
class SessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "mocvd-session.json"))
    fun read(): SavedState = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
        file.openRead().bufferedReader().use { StateCodec.decode(it.readText()) }
    } else SavedState()

    fun write(state: SavedState) {
        val stream = file.startWrite()
        try {
            stream.write(StateCodec.encode(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

object StateCodec {
    fun encode(state: SavedState): String = JSONObject().apply {
        put("version", 1)
        val s = state.session
        put("session", JSONObject().apply {
            put("id", s.id); put("stage", s.stage.name); put("phase", s.phase.name)
            put("cycle", s.stage.cycle); put("speed", s.speed)
            put("stageStartedElapsed", s.stageStartedElapsed); put("stageStartedWall", s.stageStartedWall)
            put("growthStartedElapsed", s.growthStartedElapsed); put("growthStartedWall", s.growthStartedWall)
            put("deadlineElapsed", s.deadlineElapsed); put("deadlineWall", s.deadlineWall)
            put("pausedRemaining", s.pausedRemaining); put("boot", s.boot); put("generation", s.generation)
            put("delivered", JSONArray(s.delivered.sorted())); put("missed", JSONArray(s.missed.sorted()))
            put("earlyTransitions", s.earlyTransitions); put("lastAction", s.lastAction)
            put("recoveryRequired", s.recoveryRequired); put("overdueByEdit", s.overdueByEdit)
            put("readyForNextGrowth", s.readyForNextGrowth)
            put("config", JSONObject().apply {
                val c = s.config
                put("reactorSeconds", c.reactorSeconds); put("growthSeconds", c.growthSeconds)
                put("conversion", c.conversion.name); put("referenceReactorSeconds", c.referenceReactorSeconds)
                put("referenceTotalSeconds", c.referenceTotalSeconds); put("adjustmentFactor", c.adjustmentFactor)
            })
            put("scheduledReminders", JSONArray().apply {
                TimerEngine.pending(s).forEach { r -> put(JSONObject().apply {
                    put("id", r.id); put("kind", r.kind.name); put("atElapsed", r.atElapsed)
                }) }
            })
        })
        put("preferences", JSONObject().apply {
            put("sound", state.preferences.sound); put("vibration", state.preferences.vibration)
            put("keepAwake", state.preferences.keepAwake); put("simulation", state.preferences.simulation)
        })
    }.toString(2)

    fun decode(text: String): SavedState {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "Unsupported saved session version." }
        val s = root.getJSONObject("session")
        val c = s.getJSONObject("config")
        val p = root.getJSONObject("preferences")
        fun ids(key: String): Set<String> = s.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val config = TimingConfig(c.getLong("reactorSeconds"), c.getLong("growthSeconds"),
            Conversion.valueOf(c.getString("conversion")), c.getLong("referenceReactorSeconds"),
            c.getLong("referenceTotalSeconds"), c.getDouble("adjustmentFactor")).validated()
        val speed = s.getInt("speed")
        require(speed == 1 || speed == 30) { "Invalid simulation speed." }
        return SavedState(Session(
            id = s.getString("id"), stage = Stage.valueOf(s.getString("stage")), phase = Phase.valueOf(s.getString("phase")),
            config = config, speed = speed, stageStartedElapsed = s.getLong("stageStartedElapsed"),
            stageStartedWall = s.getLong("stageStartedWall"), growthStartedElapsed = s.getLong("growthStartedElapsed"),
            growthStartedWall = s.getLong("growthStartedWall"), deadlineElapsed = s.getLong("deadlineElapsed"),
            deadlineWall = s.getLong("deadlineWall"), pausedRemaining = s.getLong("pausedRemaining"),
            boot = s.getInt("boot"), generation = s.getInt("generation"), delivered = ids("delivered"), missed = ids("missed"),
            earlyTransitions = s.getInt("earlyTransitions"), lastAction = s.getString("lastAction"),
            recoveryRequired = s.getBoolean("recoveryRequired"), overdueByEdit = s.getBoolean("overdueByEdit"),
            readyForNextGrowth = s.optBoolean("readyForNextGrowth", false),
        ), Preferences(p.getBoolean("sound"), p.getBoolean("vibration"), p.getBoolean("keepAwake"), p.getBoolean("simulation")))
    }
}
