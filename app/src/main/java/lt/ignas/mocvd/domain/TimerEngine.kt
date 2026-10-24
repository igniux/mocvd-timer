package lt.ignas.mocvd.domain

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToLong

enum class Stage(val title: String) {
    IDLE("Ready when you are"),
    VACUUM_1("Vacuum"), ATMOSPHERE_1("Atmosphere"),
    VACUUM_2("Vacuum"), ATMOSPHERE_2("Atmosphere"),
    VACUUM_3("Vacuum"), ATMOSPHERE_3("Atmosphere"),
    GROWTH_READY("Growth ready"), GROWTH_RUNNING("Growth"), GROWTH_FINISHED("Process complete");

    val isPreparation get() = ordinal in 1..6
    val isVacuum get() = isPreparation && ordinal % 2 == 1
    val cycle get() = if (isPreparation) (ordinal + 1) / 2 else 0
    val durationSeconds get() = if (isVacuum) 110L else 47L
    val next: Stage get() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

enum class Phase { RUNNING, PAUSED, WAITING }
enum class Conversion { MANUAL, LINEAR }

data class TimingConfig(
    val reactorSeconds: Long = 600,
    val growthSeconds: Long = 4500,
    val conversion: Conversion = Conversion.LINEAR,
    val referenceReactorSeconds: Long = 600,
    val referenceTotalSeconds: Long = 4500,
    val adjustmentFactor: Double = 1.0,
) {
    fun validated(): TimingConfig {
        require(reactorSeconds in 0..604800) { "Reactor setting must be 0–604800 seconds." }
        require(growthSeconds in 1..604800) { "Growth estimate must be 1–604800 seconds." }
        require(referenceReactorSeconds in 0..604800 && referenceTotalSeconds in 1..604800) {
            "Reference values are outside the supported range."
        }
        require(adjustmentFactor.isFinite() && adjustmentFactor in 0.0..100.0) {
            "Adjustment factor must be between 0 and 100."
        }
        return this
    }

    fun resolved(): TimingConfig {
        val total = if (conversion == Conversion.LINEAR) {
            (referenceTotalSeconds + (reactorSeconds - referenceReactorSeconds) * adjustmentFactor).roundToLong()
        } else growthSeconds
        return copy(growthSeconds = total).validated()
    }

    companion object {
        /** User-confirmed provisional model: fixed 3900 s overhead plus the peak-temperature hold. */
        fun forReactor(seconds: Long): TimingConfig = TimingConfig(reactorSeconds = seconds,
            conversion = Conversion.LINEAR, referenceReactorSeconds = 600,
            referenceTotalSeconds = 4500, adjustmentFactor = 1.0).resolved()
    }
}

data class Preferences(
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val keepAwake: Boolean = false,
    val simulation: Boolean = false,
)

data class ClockReading(val elapsed: Long, val wall: Long, val boot: Int)

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val stage: Stage = Stage.IDLE,
    val phase: Phase = Phase.WAITING,
    val config: TimingConfig = TimingConfig(),
    val speed: Int = 1,
    val stageStartedElapsed: Long = 0,
    val stageStartedWall: Long = 0,
    val growthStartedElapsed: Long = 0,
    val growthStartedWall: Long = 0,
    val deadlineElapsed: Long = 0,
    val deadlineWall: Long = 0,
    val pausedRemaining: Long = 0,
    val boot: Int = -1,
    val generation: Int = 0,
    val delivered: Set<String> = emptySet(),
    val missed: Set<String> = emptySet(),
    val earlyTransitions: Int = 0,
    val lastAction: String = "",
    val recoveryRequired: Boolean = false,
    val overdueByEdit: Boolean = false,
    val readyForNextGrowth: Boolean = false,
) {
    val isGrowth get() = stage == Stage.GROWTH_RUNNING || stage == Stage.GROWTH_FINISHED
    val isActive get() = stage != Stage.IDLE
    val isCounting get() = phase == Phase.RUNNING && !recoveryRequired
    val logicalDuration get() = if (isGrowth) config.growthSeconds else stage.durationSeconds

    fun remainingMillis(now: ClockReading): Long = when {
        stage == Stage.GROWTH_READY -> config.growthSeconds * 1000
        stage == Stage.IDLE -> 0
        phase == Phase.PAUSED -> pausedRemaining * speed
        else -> (deadlineElapsed - now.elapsed) * speed
    }

    fun remainingSeconds(now: ClockReading): Long = ceil(remainingMillis(now) / 1000.0).toLong()
    fun realMillis(seconds: Long): Long = seconds * 1000 / speed
}

enum class EventKind(val message: String) {
    STAGE_COMPLETE("Stage timer completed."),
    GROWTH_READY("Growth ready. Start growth when ready."), // Legacy identifier; no push notification.
    MEASUREMENT_WARNING("Measurement starts in 5 minutes."), // Legacy identifier; never scheduled in v1.1.
    MEASUREMENT_START("Start measurement."),
    GROWTH_WARNING("Growth finishes in 5 minutes."),
    GROWTH_COMPLETE("Estimated growth time finished."),
}

data class Reminder(val id: String, val kind: EventKind, val atElapsed: Long) {
    fun token(session: Session) = "${session.id}:$id:$atElapsed"
}
data class Transition(val session: Session, val events: List<Reminder> = emptyList())

/** Pure state machine. Every deadline is monotonic within one boot. */
object TimerEngine {
    fun start(config: TimingConfig, preferences: Preferences, now: ClockReading): Session =
        startStage(Session(config = config.resolved(), speed = if (preferences.simulation) 30 else 1), Stage.VACUUM_1, now)

    private fun startStage(s: Session, stage: Stage, now: ClockReading): Session = s.copy(
        stage = stage, phase = Phase.RUNNING, boot = now.boot,
        stageStartedElapsed = now.elapsed, stageStartedWall = now.wall,
        deadlineElapsed = now.elapsed + s.realMillis(stage.durationSeconds),
        deadlineWall = now.wall + s.realMillis(stage.durationSeconds),
        pausedRemaining = 0, generation = s.generation + 1, recoveryRequired = false,
    )

    fun advance(s: Session, now: ClockReading, confirmEarly: Boolean = false): Transition {
        require(s.stage.isPreparation && !s.recoveryRequired) { "This stage cannot be advanced." }
        val early = s.phase == Phase.PAUSED || (s.phase == Phase.RUNNING && s.remainingMillis(now) > 0)
        require(!early || confirmEarly) { "Confirm advancing before the timer has completed." }
        val next = s.copy(
            earlyTransitions = s.earlyTransitions + if (early) 1 else 0,
            lastAction = if (early) "Advanced early from ${s.stage.title}, cycle ${s.stage.cycle}." else "Stage timer completed; next stage started manually.",
        )
        return if (s.stage == Stage.ATMOSPHERE_3) {
            Transition(next.copy(stage = Stage.GROWTH_READY, phase = Phase.WAITING))
        } else Transition(startStage(next, s.stage.next, now))
    }

    fun completeGrowth(s: Session): Session {
        require(s.stage == Stage.GROWTH_FINISHED && !s.recoveryRequired) { "Growth has not finished yet." }
        return Session(config = s.config, speed = s.speed, readyForNextGrowth = true,
            lastAction = "Process complete. Prepare another growth or start full preparation.")
    }

    fun prepareNextGrowth(s: Session, preferences: Preferences, now: ClockReading): Session {
        require(s.stage == Stage.IDLE && s.readyForNextGrowth) { "Complete the current growth first." }
        return Session(stage = Stage.GROWTH_READY, phase = Phase.WAITING,
            config = TimingConfig.forReactor(s.config.reactorSeconds),
            speed = if (preferences.simulation) 30 else 1, boot = now.boot,
            lastAction = "Next growth prepared without repeating vacuum/atmosphere cycles.")
    }

    fun startGrowth(s: Session, now: ClockReading): Session {
        require(s.stage == Stage.GROWTH_READY && !s.recoveryRequired)
        return s.copy(stage = Stage.GROWTH_RUNNING, phase = Phase.RUNNING,
            boot = now.boot, growthStartedElapsed = now.elapsed, growthStartedWall = now.wall,
            deadlineElapsed = now.elapsed + s.realMillis(s.config.growthSeconds),
            deadlineWall = now.wall + s.realMillis(s.config.growthSeconds),
            overdueByEdit = false, lastAction = "Growth timer started manually.")
    }

    fun pause(s: Session, now: ClockReading): Session {
        require(s.stage.isPreparation && s.phase == Phase.RUNNING && !s.recoveryRequired)
        require(s.remainingMillis(now) > 0) { "The stage timer has already completed." }
        return s.copy(phase = Phase.PAUSED, pausedRemaining = s.deadlineElapsed - now.elapsed,
            lastAction = "App timer paused. Reactor operation is unchanged.")
    }

    fun resume(s: Session, now: ClockReading): Session {
        require(s.stage.isPreparation && s.phase == Phase.PAUSED && !s.recoveryRequired)
        return s.copy(phase = Phase.RUNNING, boot = now.boot,
            deadlineElapsed = now.elapsed + s.pausedRemaining, deadlineWall = now.wall + s.pausedRemaining,
            pausedRemaining = 0, lastAction = "App timer resumed.")
    }

    fun restart(s: Session, now: ClockReading): Session {
        require(s.stage.isPreparation)
        return startStage(s, s.stage, now).copy(lastAction = "Current stage timer restarted manually.")
    }

    fun edit(s: Session, config: TimingConfig, now: ClockReading): Session {
        val checked = config.resolved()
        if (!s.isGrowth) return s.copy(config = checked)
        val deadline = s.growthStartedElapsed + s.realMillis(checked.growthSeconds)
        return s.copy(config = checked, deadlineElapsed = deadline,
            deadlineWall = now.wall + deadline - now.elapsed,
            stage = if (deadline <= now.elapsed) Stage.GROWTH_FINISHED else Stage.GROWTH_RUNNING,
            phase = if (deadline <= now.elapsed) Phase.WAITING else Phase.RUNNING,
            missed = s.missed - buildSet {
                if (deadline > now.elapsed) add("growth-complete")
                if (deadline - s.realMillis(300) > now.elapsed) add("growth-warning")
            },
            overdueByEdit = deadline <= now.elapsed,
            lastAction = "Growth estimate updated; original start and measurement deadline retained.")
    }

    fun pending(s: Session): List<Reminder> {
        if (s.recoveryRequired) return emptyList()
        val reminders = when {
            s.stage.isPreparation && s.phase == Phase.RUNNING -> listOf(
                Reminder("stage-${s.stage.name}-${s.generation}", EventKind.STAGE_COMPLETE, s.deadlineElapsed))
            s.isGrowth -> buildList {
                add(Reminder("measurement-start", EventKind.MEASUREMENT_START,
                    s.growthStartedElapsed + s.realMillis(690)))
                if (s.config.growthSeconds > 300 && "growth-complete" !in s.delivered) add(Reminder("growth-warning", EventKind.GROWTH_WARNING,
                    s.deadlineElapsed - s.realMillis(300)))
                add(Reminder("growth-complete", EventKind.GROWTH_COMPLETE, s.deadlineElapsed))
            }
            else -> emptyList()
        }
        return reminders.filter { it.id !in s.delivered && it.id !in s.missed }.sortedBy { it.atElapsed }
    }

    fun reconcile(s: Session, now: ClockReading): Transition {
        if (s.recoveryRequired) return Transition(s)
        val due = pending(s).filter { it.atElapsed <= now.elapsed }
        // Timer state must finish even if its one-time completion notification was already handled
        // before the user extended a previously finished estimate.
        if (due.isEmpty()) return Transition(if (s.stage == Stage.GROWTH_RUNNING && s.deadlineElapsed <= now.elapsed)
            s.copy(stage = Stage.GROWTH_FINISHED, phase = Phase.WAITING) else s)
        val kinds = due.map { it.kind }.toSet()
        val events = due.filter { reminder ->
            when {
                EventKind.GROWTH_COMPLETE in kinds -> reminder.kind == EventKind.GROWTH_COMPLETE
                reminder.kind == EventKind.MEASUREMENT_WARNING && EventKind.MEASUREMENT_START in kinds -> false
                else -> true
            }
        }
        val next = s.copy(
            stage = if (s.isGrowth && s.deadlineElapsed <= now.elapsed) Stage.GROWTH_FINISHED else s.stage,
            phase = if (EventKind.STAGE_COMPLETE in kinds || (s.isGrowth && s.deadlineElapsed <= now.elapsed)) Phase.WAITING else s.phase,
            delivered = s.delivered + events.map { it.id },
            missed = s.missed + due.filter { it !in events }.map { it.id },
        )
        return Transition(next, events)
    }

    /** Called after editing directly into the past; the completion is still only an estimate. */
    fun editedCompletion(s: Session, now: ClockReading): Transition {
        return reconcile(s, now)
    }

    fun recover(s: Session, now: ClockReading): Session {
        if (!s.isActive || s.boot == now.boot) return s
        val deadline = now.elapsed + (s.deadlineWall - now.wall)
        return s.copy(boot = now.boot, deadlineElapsed = deadline,
            stageStartedElapsed = now.elapsed - (now.wall - s.stageStartedWall),
            growthStartedElapsed = if (s.isGrowth) deadline - s.realMillis(s.config.growthSeconds) else s.growthStartedElapsed,
            recoveryRequired = s.phase == Phase.RUNNING || s.recoveryRequired,
            lastAction = "Device restarted. Recovered timing is a wall-clock estimate; review before continuing.")
    }

    fun acknowledgeRecovery(s: Session): Session = s.copy(recoveryRequired = false,
        lastAction = "Recovered timing estimate accepted manually.")
}

fun formatTime(seconds: Long): String {
    val positive = seconds.coerceAtLeast(0)
    return if (positive >= 3600) "%d:%02d:%02d".format(positive / 3600, positive / 60 % 60, positive % 60)
    else "%02d:%02d".format(positive / 60, positive % 60)
}
