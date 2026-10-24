package lt.ignas.mocvd.platform

import lt.ignas.mocvd.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class StateCodecTest {
    @Test fun `next growth availability survives relaunch and defaults off for legacy files`() {
        val original = SavedState(Session(readyForNextGrowth = true))
        assertEquals(original, StateCodec.decode(StateCodec.encode(original)))
        val legacy = JSONObject(StateCodec.encode(original))
        legacy.getJSONObject("session").remove("readyForNextGrowth")
        assertFalse(StateCodec.decode(legacy.toString()).session.readyForNextGrowth)
    }
    @Test fun `session preferences and handled reminders survive a full round trip`() {
        val now = ClockReading(100_000, 1_800_000_000_000, 4)
        val s = TimerEngine.start(TimingConfig(reactorSeconds = 800), Preferences(simulation = true), now).copy(
            delivered = setOf("measurement-warning", "test"), missed = setOf("missed-event"),
            recoveryRequired = true, earlyTransitions = 2, lastAction = "Advanced early.")
        val original = SavedState(s, Preferences(sound = false, vibration = true, keepAwake = true, simulation = true))
        assertEquals(original, StateCodec.decode(StateCodec.encode(original)))
    }

    @Test fun `relaunch of a completed stage never restarts or advances it`() {
        val now = ClockReading(100_000, 1_800_000_000_000, 4)
        val started = TimerEngine.start(TimingConfig(), Preferences(), now)
        val complete = TimerEngine.reconcile(started, now.copy(elapsed = started.deadlineElapsed)).session
        val restored = StateCodec.decode(StateCodec.encode(SavedState(complete))).session
        val result = TimerEngine.reconcile(restored, now.copy(elapsed = started.deadlineElapsed + 999_000))
        assertEquals(Stage.VACUUM_1, result.session.stage)
        assertEquals(Phase.WAITING, result.session.phase)
        assertTrue(result.events.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun `future storage version is not silently reset`() {
        StateCodec.decode("{\"version\":999}")
    }
}
