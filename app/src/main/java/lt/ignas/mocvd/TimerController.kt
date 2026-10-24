package lt.ignas.mocvd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import lt.ignas.mocvd.domain.*
import lt.ignas.mocvd.platform.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AppState(
    val saved: SavedState = SavedState(),
    val loaded: Boolean = false,
    val error: String? = null,
    val storageFailure: Boolean = false,
    val alarmError: String? = null,
    val exactAlarms: Boolean = false,
    val notifications: Boolean = false,
)

/** All changes, alarm callbacks and atomic disk commits share a single serial executor. */
class TimerController(context: Context) {
    val clock = AndroidClock(context)
    private val store = SessionStore(context)
    private val alarms = AlarmScheduler(context)
    private val alerts = Notifications(context) { message -> mutable.update { it.copy(error = message) } }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var lastStatus: Session? = null
    private var lastPrefs: Preferences? = null

    init {
        executor.execute {
            try {
                val saved = store.read()
                // Adopt the reactor-only model for idle sessions; do not silently change a running
                // v1.0 session's estimate during an APK update. Saving the hold time applies the new rule.
                val configured = if (saved.session.stage == Stage.IDLE) saved.session.copy(
                    config = TimingConfig.forReactor(saved.session.config.reactorSeconds)) else saved.session
                val restored = TimerEngine.recover(configured, clock.read())
                mutable.value = AppState(saved.copy(session = restored), loaded = true)
                if (restored.recoveryRequired && !saved.session.recoveryRequired) alerts.recovery(restored, saved.preferences)
                refreshInternal(force = true)
            } catch (e: Exception) {
                mutable.value = AppState(loaded = true, storageFailure = true,
                    error = "Saved session could not be read. It has been left untouched. Reset the session to start again. ${e.message}")
            }
        }
        executor.scheduleWithFixedDelay({
            if (mutable.value.loaded && !mutable.value.storageFailure) safely { refreshInternal() }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            mutable.value = mutable.value.copy(error = e.message ?: "Unable to update the timer.")
        }
    }

    private fun commit(saved: SavedState) {
        try { store.write(saved) } catch (e: Exception) {
            alarms.cancel()
            mutable.value = mutable.value.copy(storageFailure = true,
                error = "Session could not be saved; alerts have been stopped. ${e.message}")
            throw e
        }
        mutable.value = mutable.value.copy(saved = saved, storageFailure = false, error = null)
    }

    private fun refreshInternal(force: Boolean = false) {
        val old = mutable.value.saved
        val now = clock.read()
        val recovered = TimerEngine.recover(old.session, now)
        val transitioned = deliveryStatus(TimerEngine.reconcile(recovered, now), old.preferences)
        val s = transitioned.session.let {
            val alignedWall = now.wall + it.deadlineElapsed - now.elapsed
            if (it.phase == Phase.RUNNING && kotlin.math.abs(it.deadlineWall - alignedWall) > 1000) it.copy(deadlineWall = alignedWall) else it
        }
        val exact = alarms.isAvailable()
        val allowed = alerts.isAllowed() && alerts.remindersEnabled(old.preferences)
        if (s != old.session || force) commit(old.copy(session = s))
        transitioned.events.forEach { alerts.event(s, it, old.preferences) }
        val schedulingError = alarms.sync(s, now, force)
        val permissionChanged = exact != mutable.value.exactAlarms || allowed != mutable.value.notifications
        mutable.value = mutable.value.copy(exactAlarms = exact, notifications = allowed, alarmError = schedulingError)
        if (s != lastStatus || old.preferences != lastPrefs || force || permissionChanged || (s.speed > 1 && s.isCounting)) {
            alerts.active(s, old.preferences, now)
            lastStatus = s; lastPrefs = old.preferences
        }
    }

    private fun deliveryStatus(result: Transition, preferences: Preferences): Transition {
        if (alerts.isAllowed() && alerts.remindersEnabled(preferences)) return result
        val denied = result.events.filter { it.kind != EventKind.STAGE_COMPLETE }
        val ids = denied.map { it.id }.toSet()
        return Transition(result.session.copy(delivered = result.session.delivered - ids,
            missed = result.session.missed + ids), result.events - denied.toSet())
    }

    private fun change(block: (Session, ClockReading) -> Transition) {
        executor.execute { safely {
            check(!mutable.value.storageFailure) { "Reset the unreadable session before starting a new timer." }
            val old = mutable.value.saved
            val result = deliveryStatus(block(old.session, clock.read()), old.preferences)
            commit(old.copy(session = result.session))
            result.events.forEach { alerts.event(result.session, it, old.preferences) }
            refreshInternal(force = true)
        } }
    }

    fun start() = change { s, now ->
        require(s.stage == Stage.IDLE)
        Transition(TimerEngine.start(s.config, mutable.value.saved.preferences, now))
    }
    fun advance(early: Boolean = false) = change { s, now -> TimerEngine.advance(s, now, early) }
    fun startGrowth() = change { s, now -> Transition(TimerEngine.startGrowth(s, now)) }
    fun completeGrowth() = change { s, _ -> Transition(TimerEngine.completeGrowth(s)) }
    fun prepareNextGrowth() = change { s, now ->
        Transition(TimerEngine.prepareNextGrowth(s, mutable.value.saved.preferences, now))
    }
    fun pause() = change { s, now -> Transition(TimerEngine.pause(s, now)) }
    fun resume() = change { s, now -> Transition(TimerEngine.resume(s, now)) }
    fun restart() = change { s, now -> Transition(TimerEngine.restart(s, now)) }
    fun edit(config: TimingConfig) = change { s, now -> TimerEngine.editedCompletion(TimerEngine.edit(s, config, now), now) }
    fun reactor(seconds: Long) = edit(TimingConfig.forReactor(seconds))
    fun acceptRecovery() = change { s, _ -> alerts.clearRecovery(); Transition(TimerEngine.acknowledgeRecovery(s)) }

    fun reset() { executor.execute { safely {
        val old = mutable.value.saved
        commit(SavedState(Session(config = TimingConfig.forReactor(old.session.config.reactorSeconds)), old.preferences))
        alarms.cancel(); alerts.clear(); refreshInternal(force = true)
    } } }

    fun preferences(p: Preferences) { executor.execute { safely {
        val old = mutable.value.saved
        require(!old.session.isActive || p.simulation == old.preferences.simulation) { "End the current session before changing simulation mode." }
        commit(old.copy(preferences = p)); refreshInternal(force = true)
    } } }

    fun refresh(onFinished: (() -> Unit)? = null) { executor.execute {
        try { if (!mutable.value.storageFailure) safely { refreshInternal(force = true) } }
        finally { if (onFinished != null) alerts.afterStageAlarm(onFinished) }
    } }

    fun testAlarm() { executor.execute { safely {
        check(alerts.isAllowed() && alerts.remindersEnabled(mutable.value.saved.preferences)) {
            "Enable notifications and the selected reminder channel before testing the alarm."
        }
        alerts.test(mutable.value.saved.preferences)
    } } }
    fun testStageAlarm() { executor.execute { safely {
        val prefs = mutable.value.saved.preferences
        check(prefs.sound || prefs.vibration) { "Enable sound or vibration before testing the stage alarm." }
        alerts.testStage(prefs)
    } } }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
}
