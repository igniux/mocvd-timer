package lt.ignas.mocvd.domain

import org.junit.Assert.*
import org.junit.Test

class TimerEngineTest {
    private val now = ClockReading(100_000, 1_800_000_000_000, 8)
    private fun after(seconds: Long, base: ClockReading = now) = base.copy(elapsed = base.elapsed + seconds * 1000, wall = base.wall + seconds * 1000)
    private fun atDeadline(s: Session) = now.copy(elapsed = s.deadlineElapsed, wall = s.deadlineWall)
    private fun ready(preferences: Preferences = Preferences()): Session {
        var s = TimerEngine.start(TimingConfig(), preferences, now)
        repeat(6) { s = TimerEngine.advance(s, atDeadline(s)).session }
        return s
    }
    private fun growth(preferences: Preferences = Preferences()) = TimerEngine.startGrowth(ready(preferences), now)

    @Test fun `start creates a 110 second vacuum timer`() {
        val s = TimerEngine.start(TimingConfig(), Preferences(), now)
        assertEquals(Stage.VACUUM_1, s.stage)
        assertEquals(1, s.stage.cycle)
        assertEquals(110, s.remainingSeconds(now))
        assertEquals(now.elapsed, s.stageStartedElapsed)
    }

    @Test fun `all three cycles follow the correct order with manual advancement`() {
        var s = TimerEngine.start(TimingConfig(), Preferences(), now)
        val visited = mutableListOf(s.stage)
        repeat(6) {
            val expired = TimerEngine.reconcile(s, atDeadline(s))
            assertEquals(s.stage, expired.session.stage)
            assertEquals(Phase.WAITING, expired.session.phase)
            assertEquals(listOf(EventKind.STAGE_COMPLETE), expired.events.map { it.kind })
            s = TimerEngine.advance(expired.session, atDeadline(s)).session
            visited += s.stage
        }
        assertEquals(listOf(Stage.VACUUM_1, Stage.ATMOSPHERE_1, Stage.VACUUM_2, Stage.ATMOSPHERE_2,
            Stage.VACUUM_3, Stage.ATMOSPHERE_3, Stage.GROWTH_READY), visited)
    }

    @Test fun `atmosphere lasts 47 seconds`() {
        val first = TimerEngine.start(TimingConfig(), Preferences(), now)
        val second = TimerEngine.advance(first, atDeadline(first)).session
        assertEquals(47_000, second.deadlineElapsed - second.stageStartedElapsed)
    }

    @Test fun `expiry waits indefinitely and only notifies once`() {
        val first = TimerEngine.start(TimingConfig(), Preferences(), now)
        val completed = TimerEngine.reconcile(first, after(110)).session
        val later = TimerEngine.reconcile(completed, after(100_000))
        assertEquals(Stage.VACUUM_1, later.session.stage)
        assertEquals(Phase.WAITING, later.session.phase)
        assertTrue(later.events.isEmpty())
        assertTrue(TimerEngine.pending(later.session).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun `early advancement requires explicit confirmation`() {
        TimerEngine.advance(TimerEngine.start(TimingConfig(), Preferences(), now), after(10))
    }

    @Test fun `early advancement is recorded and starts a fresh next stage`() {
        val s = TimerEngine.advance(TimerEngine.start(TimingConfig(), Preferences(), now), after(10), true).session
        assertEquals(Stage.ATMOSPHERE_1, s.stage)
        assertEquals(1, s.earlyTransitions)
        assertTrue(s.lastAction.startsWith("Advanced early"))
        assertEquals(47, s.remainingSeconds(after(10)))
    }

    @Test fun `growth ready waits silently for a manual start`() {
        var s = TimerEngine.start(TimingConfig(), Preferences(), now)
        repeat(5) { s = TimerEngine.advance(s, atDeadline(s)).session }
        val result = TimerEngine.advance(s, atDeadline(s))
        assertEquals(Stage.GROWTH_READY, result.session.stage)
        assertTrue(result.events.isEmpty())
        assertFalse("growth-ready" in result.session.delivered)
        assertTrue(TimerEngine.pending(result.session).isEmpty())
        assertEquals(0, result.session.growthStartedElapsed)
    }

    @Test fun `growth starts only on explicit action`() {
        val waiting = TimerEngine.reconcile(ready(), after(9999)).session
        assertEquals(Stage.GROWTH_READY, waiting.stage)
        val s = TimerEngine.startGrowth(waiting, after(100))
        assertEquals(Stage.GROWTH_RUNNING, s.stage)
        assertEquals(after(100).elapsed, s.growthStartedElapsed)
        assertEquals(4500, s.remainingSeconds(after(100)))
    }

    @Test fun `completion clears reminders and next growth skips preparation with fresh deadlines`() {
        val running = growth().copy(config = TimingConfig.forReactor(900))
        val finished = TimerEngine.reconcile(running, atDeadline(running)).session
        val complete = TimerEngine.completeGrowth(finished)
        assertEquals(Stage.IDLE, complete.stage)
        assertTrue(complete.readyForNextGrowth)
        assertFalse(complete.isActive)
        assertTrue(TimerEngine.pending(complete).isEmpty())
        assertTrue(complete.delivered.isEmpty())
        assertNotEquals(finished.id, complete.id)
        assertEquals(900L, complete.config.reactorSeconds)
        val preparedAt = after(8000)
        val prepared = TimerEngine.prepareNextGrowth(complete, Preferences(), preparedAt)
        assertEquals(Stage.GROWTH_READY, prepared.stage)
        assertEquals(Phase.WAITING, prepared.phase)
        assertEquals(4800L, prepared.config.growthSeconds)
        assertEquals(0L, prepared.growthStartedElapsed)
        assertTrue(TimerEngine.pending(prepared).isEmpty())
        val nextStart = after(8100)
        val next = TimerEngine.startGrowth(prepared, nextStart)
        assertEquals(Stage.GROWTH_RUNNING, next.stage)
        assertEquals(nextStart.elapsed, next.growthStartedElapsed)
        assertEquals(nextStart.elapsed + 690_000, TimerEngine.pending(next).first().atElapsed)
        assertEquals(3, TimerEngine.pending(next).size)
        assertNotEquals(finished.id, next.id)
    }

    @Test(expected = IllegalArgumentException::class) fun `running growth cannot be acknowledged as complete`() {
        TimerEngine.completeGrowth(growth())
    }

    @Test(expected = IllegalArgumentException::class) fun `first run cannot skip preparation`() {
        TimerEngine.prepareNextGrowth(Session(), Preferences(), now)
    }

    @Test fun `full preparation remains available after completion`() {
        val complete = TimerEngine.completeGrowth(TimerEngine.reconcile(growth(), after(4500)).session)
        val next = TimerEngine.start(complete.config, Preferences(), after(5000))
        assertEquals(Stage.VACUUM_1, next.stage)
        assertFalse(next.readyForNextGrowth)
    }

    @Test fun `measurement has no early five minute warning`() {
        val s = growth()
        assertTrue(TimerEngine.reconcile(s, after(389)).events.isEmpty())
        val result = TimerEngine.reconcile(s, after(390))
        assertTrue(result.events.isEmpty())
        assertEquals(EventKind.MEASUREMENT_START, TimerEngine.pending(result.session).first().kind)
        assertTrue(TimerEngine.reconcile(s, after(689)).events.isEmpty())
        assertEquals(Stage.GROWTH_RUNNING, result.session.stage)
        assertEquals(4110, result.session.remainingSeconds(after(390)))
    }

    @Test fun `measurement at eleven minutes thirty seconds leaves growth running`() {
        val s = TimerEngine.reconcile(growth(), after(390)).session
        val result = TimerEngine.reconcile(s, after(690))
        assertEquals(listOf(EventKind.MEASUREMENT_START), result.events.map { it.kind })
        assertEquals(Stage.GROWTH_RUNNING, result.session.stage)
        assertEquals(3810, result.session.remainingSeconds(after(690)))
        assertEquals(now.elapsed, result.session.growthStartedElapsed)
    }

    @Test fun `default growth completion reminders are seventy and seventy five minutes`() {
        val s = growth()
        val pending = TimerEngine.pending(s)
        assertEquals(now.elapsed + 4_200_000, pending.first { it.kind == EventKind.GROWTH_WARNING }.atElapsed)
        val result = TimerEngine.reconcile(s, after(4500))
        assertEquals(Stage.GROWTH_FINISHED, result.session.stage)
        assertEquals(listOf(EventKind.GROWTH_COMPLETE), result.events.map { it.kind })
        assertEquals(2, result.session.missed.size)
    }

    @Test fun `editing before measurement preserves original start and fixed reminders`() {
        val original = growth()
        val edited = TimerEngine.edit(original, TimingConfig.forReactor(1100), after(100))
        assertEquals(original.growthStartedElapsed, edited.growthStartedElapsed)
        assertEquals(original.growthStartedWall, edited.growthStartedWall)
        assertEquals(4900, edited.remainingSeconds(after(100)))
        assertEquals(now.elapsed + 690_000, TimerEngine.pending(edited).first { it.kind == EventKind.MEASUREMENT_START }.atElapsed)
        assertEquals(now.elapsed + 4_700_000, TimerEngine.pending(edited).first { it.kind == EventKind.GROWTH_WARNING }.atElapsed)
    }

    @Test fun `editing after measurement does not duplicate delivered reminders`() {
        val s = TimerEngine.reconcile(TimerEngine.reconcile(growth(), after(390)).session, after(690)).session
        val edited = TimerEngine.edit(s, TimingConfig.forReactor(2100), after(900))
        assertEquals(s.delivered, edited.delivered)
        assertFalse(TimerEngine.pending(edited).any { it.kind == EventKind.MEASUREMENT_START || it.kind == EventKind.MEASUREMENT_WARNING })
        assertEquals(5100, edited.remainingSeconds(after(900)))
    }

    @Test fun `legacy manual mode still restores without changing its total`() {
        val s = growth()
        val edited = TimerEngine.edit(s, s.config.copy(reactorSeconds = 900, conversion = Conversion.MANUAL), after(10))
        assertEquals(4500, edited.config.growthSeconds)
        assertEquals(s.deadlineElapsed, edited.deadlineElapsed)
    }

    @Test fun `linear mode implements configurable provisional relationship`() {
        assertEquals(4600, TimingConfig(reactorSeconds = 700, conversion = Conversion.LINEAR).resolved().growthSeconds)
        assertEquals(5200, TimingConfig(reactorSeconds = 700, conversion = Conversion.LINEAR,
            referenceTotalSeconds = 5000, adjustmentFactor = 2.0).resolved().growthSeconds)
    }

    @Test fun `past estimate produces an explicit overdue state and one completion reminder`() {
        val s = growth()
        val edited = TimerEngine.edit(s, TimingConfig.forReactor(0), after(4300))
        assertEquals(Stage.GROWTH_FINISHED, edited.stage)
        assertTrue(edited.overdueByEdit)
        assertEquals(-400, edited.remainingSeconds(after(4300)))
        val result = TimerEngine.editedCompletion(edited, after(4300))
        assertEquals(listOf(EventKind.GROWTH_COMPLETE), result.events.map { it.kind })
        assertTrue(TimerEngine.reconcile(result.session, after(4301)).events.isEmpty())
    }

    @Test fun `legacy short manual estimate retains its original measurement deadline`() {
        val s = TimerEngine.edit(growth(), TimingConfig(growthSeconds = 300, conversion = Conversion.MANUAL), after(100))
        assertEquals(now.elapsed + 690_000, TimerEngine.pending(s).first { it.kind == EventKind.MEASUREMENT_START }.atElapsed)
    }

    @Test fun `pause and resume preserve remaining time across long waits`() {
        val started = TimerEngine.start(TimingConfig(), Preferences(), now)
        val paused = TimerEngine.pause(started, after(30))
        assertEquals(80, paused.remainingSeconds(after(5000)))
        assertTrue(TimerEngine.pending(paused).isEmpty())
        val resumed = TimerEngine.resume(paused, after(5000))
        assertEquals(80, resumed.remainingSeconds(after(5000)))
        assertEquals(1, TimerEngine.pending(resumed).size)
    }

    @Test(expected = IllegalArgumentException::class) fun `growth cannot be accidentally paused`() {
        TimerEngine.pause(growth(), after(50))
    }

    @Test fun `restart invalidates the old stage alarm identity`() {
        val started = TimerEngine.start(TimingConfig(), Preferences(), now)
        val old = TimerEngine.pending(started).single().token(started)
        val restarted = TimerEngine.restart(started, after(40))
        assertNotEquals(old, TimerEngine.pending(restarted).single().token(restarted))
        assertTrue(TimerEngine.reconcile(restarted, after(110)).events.isEmpty())
    }

    @Test fun `rescheduling completion invalidates its old deadline token`() {
        val started = growth()
        val old = TimerEngine.pending(started).first { it.kind == EventKind.GROWTH_COMPLETE }.token(started)
        val edited = TimerEngine.edit(started, TimingConfig.forReactor(2100), after(20))
        assertNotEquals(old, TimerEngine.pending(edited).first { it.kind == EventKind.GROWTH_COMPLETE }.token(edited))
    }

    @Test fun `late measurement reminder fires once without resurrecting the removed warning`() {
        val result = TimerEngine.reconcile(growth(), after(800))
        assertEquals(listOf(EventKind.MEASUREMENT_START), result.events.map { it.kind })
        assertTrue(result.session.missed.isEmpty())
        assertTrue("measurement-warning" !in result.session.delivered)
        assertTrue(TimerEngine.reconcile(result.session, after(801)).events.isEmpty())
    }

    @Test fun `delivered completion warning is not repeated after extending estimate`() {
        val s = TimerEngine.reconcile(growth(), after(4200)).session
        assertTrue("growth-warning" in s.delivered)
        val edited = TimerEngine.edit(s, TimingConfig.forReactor(2100), after(4300))
        assertFalse(TimerEngine.pending(edited).any { it.kind == EventKind.GROWTH_WARNING })
    }

    @Test fun `extending a finished estimate finishes again without duplicating its notification`() {
        val completed = TimerEngine.reconcile(growth(), after(4500)).session
        val extended = TimerEngine.edit(completed, TimingConfig.forReactor(2100), after(4600))
        assertEquals(Stage.GROWTH_RUNNING, extended.stage)
        val finishedAgain = TimerEngine.reconcile(extended, after(6000))
        assertEquals(Stage.GROWTH_FINISHED, finishedAgain.session.stage)
        assertTrue(finishedAgain.events.isEmpty())
    }

    @Test fun `extending an estimate reschedules undelivered future completion reminders`() {
        val missed = growth().copy(missed = setOf("growth-warning", "growth-complete", "measurement-warning"))
        val extended = TimerEngine.edit(missed, TimingConfig.forReactor(2100), after(4500))
        assertFalse("growth-warning" in extended.missed)
        assertFalse("growth-complete" in extended.missed)
        assertTrue("measurement-warning" in extended.missed)
        assertTrue(TimerEngine.pending(extended).any { it.kind == EventKind.GROWTH_WARNING })
    }

    @Test fun `same boot wall clock change never changes elapsed timing`() {
        val s = growth()
        val jumped = after(30).copy(wall = now.wall + 9_000_000)
        assertEquals(4470, TimerEngine.recover(s, jumped).remainingSeconds(jumped))
        assertEquals(s.growthStartedElapsed, TimerEngine.recover(s, jumped).growthStartedElapsed)
    }

    @Test fun `reboot reconstructs estimate and requires explicit review`() {
        val s = growth()
        val rebootedNow = ClockReading(15_000, now.wall + 1000_000, 9)
        val recovered = TimerEngine.recover(s, rebootedNow)
        assertTrue(recovered.recoveryRequired)
        assertEquals(3500, recovered.remainingSeconds(rebootedNow))
        assertTrue(TimerEngine.pending(recovered).isEmpty())
        val accepted = TimerEngine.acknowledgeRecovery(recovered)
        assertFalse(accepted.recoveryRequired)
        assertEquals(3500, accepted.remainingSeconds(rebootedNow))
    }

    @Test fun `paused timer survives reboot without losing remaining duration`() {
        val s = TimerEngine.pause(TimerEngine.start(TimingConfig(), Preferences(), now), after(10))
        val later = ClockReading(15_000, now.wall + 1000_000, 9)
        val recovered = TimerEngine.recover(s, later)
        assertEquals(100, recovered.remainingSeconds(later))
        assertEquals(Phase.PAUSED, recovered.phase)
    }

    @Test fun `accelerated mode scales stages and all growth deadlines together`() {
        val prefs = Preferences(simulation = true)
        val s = TimerEngine.start(TimingConfig(), prefs, now)
        assertEquals(110_000 / 30, s.deadlineElapsed - now.elapsed)
        val g = growth(prefs)
        assertEquals(23_000, TimerEngine.pending(g).first().atElapsed - now.elapsed)
        assertEquals(150_000, g.deadlineElapsed - now.elapsed)
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid growth duration is rejected`() {
        TimingConfig(growthSeconds = 0, conversion = Conversion.MANUAL).resolved()
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid linear output is rejected`() {
        TimingConfig(reactorSeconds = 0, conversion = Conversion.LINEAR, referenceTotalSeconds = 100).resolved()
    }

    @Test fun `time formatting supports hours and clamps completed countdowns`() {
        assertEquals("01:50", formatTime(110))
        assertEquals("1:15:00", formatTime(4500))
        assertEquals("00:00", formatTime(-1))
    }

    @Test fun `reactor hold is the editable variable and total follows the provisional model`() {
        assertEquals(4500, TimingConfig.forReactor(600).growthSeconds)
        assertEquals(4800, TimingConfig.forReactor(900).growthSeconds)
        assertEquals(3900, TimingConfig.forReactor(0).growthSeconds)
        val original = growth()
        val updated = TimerEngine.edit(original, TimingConfig.forReactor(900), after(700))
        assertEquals(4100, updated.remainingSeconds(after(700)))
        assertEquals(original.growthStartedElapsed, updated.growthStartedElapsed)
    }

    @Test fun `old persisted measurement warning never returns after a reactor edit`() {
        val old = growth().copy(delivered = setOf("measurement-warning"))
        val edited = TimerEngine.edit(old, TimingConfig.forReactor(900), after(10))
        assertTrue(TimerEngine.pending(edited).none { it.kind == EventKind.MEASUREMENT_WARNING })
        assertEquals(now.elapsed + 690_000, TimerEngine.pending(edited).first().atElapsed)
    }

    @Test(expected = IllegalArgumentException::class) fun `negative peak temperature hold is rejected`() {
        TimingConfig.forReactor(-1)
    }
}
