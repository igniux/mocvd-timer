package lt.ignas.mocvd

import android.content.Intent
import android.app.NotificationManager
import android.widget.Chronometer
import android.widget.TextView
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import lt.ignas.mocvd.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import lt.ignas.mocvd.platform.Notifications

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MocvdApplication
    private val controller get() = app.controller
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 12_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("State change timed out", condition())
    }

    @Before fun prepare() {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        shell("pm grant lt.ignas.mocvd android.permission.POST_NOTIFICATIONS")
        shell("appops set lt.ignas.mocvd SCHEDULE_EXACT_ALARM allow")
        device.wakeUp()
        shell("wm dismiss-keyguard")
        waitFor { controller.state.value.loaded }
        controller.reset()
        waitFor { controller.state.value.saved.session.stage == Stage.IDLE }
        controller.reactor(600)
        waitFor { controller.state.value.saved.session.config.reactorSeconds == 600L }
        controller.preferences(Preferences(simulation = true, sound = false, vibration = false))
        waitFor { controller.state.value.saved.preferences.simulation }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.text("Start process")), 10_000)
    }

    @After fun cleanUp() {
        controller.reset()
        waitFor { controller.state.value.saved.session.stage == Stage.IDLE }
        controller.preferences(Preferences())
        waitFor { !controller.state.value.saved.preferences.simulation }
    }

    @Test fun stageCompletionKeepsTheStageOnScreen() {
        device.findObject(By.text("Start process")).click()
        waitFor { controller.state.value.saved.session.stage == Stage.VACUUM_1 }
        waitFor { controller.state.value.saved.session.phase == Phase.WAITING }
        assertEquals(Stage.VACUUM_1, controller.state.value.saved.session.stage)
        assertNotNull(device.wait(Until.findObject(By.text("Start next stage · Atmosphere")), 6000))
        assertTrue(controller.state.value.saved.session.delivered.any { it.startsWith("stage-") })
        assertTrue("Stage completion must not post a separate push notification",
            app.getSystemService(NotificationManager::class.java).activeNotifications.all { it.id == Notifications.STATUS_ID })
    }

    @Test fun earlyTransitionCanBeCancelledThenConfirmed() {
        // Normal speed keeps the early-transition dialog open longer than the simulation stage.
        controller.preferences(Preferences())
        waitFor { !controller.state.value.saved.preferences.simulation }
        device.findObject(By.text("Start process")).click()
        assertNotNull(device.wait(Until.findObject(By.text("Advance early")), 6000))
        device.findObject(By.text("Advance early")).click()
        device.wait(Until.hasObject(By.text("Cancel")), 4000)
        device.findObject(By.text("Cancel")).click()
        assertEquals(Stage.VACUUM_1, controller.state.value.saved.session.stage)
        device.findObject(By.text("Advance early")).click()
        device.wait(Until.hasObject(By.text("Confirm")), 4000)
        device.findObject(By.text("Confirm")).click()
        waitFor { controller.state.value.saved.session.stage == Stage.ATMOSPHERE_1 }
        assertEquals(1, controller.state.value.saved.session.earlyTransitions)
    }

    @Test fun growthEditRetainsStartAndMeasurementDeadline() {
        controller.start()
        waitFor { controller.state.value.saved.session.stage == Stage.VACUUM_1 }
        repeat(6) {
            val old = controller.state.value.saved.session.stage
            controller.advance(true)
            waitFor { controller.state.value.saved.session.stage != old }
        }
        assertEquals(Stage.GROWTH_READY, controller.state.value.saved.session.stage)
        controller.startGrowth()
        waitFor { controller.state.value.saved.session.stage == Stage.GROWTH_RUNNING }
        val before = controller.state.value.saved.session
        val measurement = TimerEngine.pending(before).first { it.kind == EventKind.MEASUREMENT_START }.atElapsed
        controller.reactor(2100)
        waitFor { controller.state.value.saved.session.config.growthSeconds == 6000L }
        val after = controller.state.value.saved.session
        assertEquals(before.growthStartedElapsed, after.growthStartedElapsed)
        assertEquals(measurement, TimerEngine.pending(after).first { it.kind == EventKind.MEASUREMENT_START }.atElapsed)
        assertEquals(Stage.GROWTH_RUNNING, after.stage)
    }

    @Test fun testAlarmDoesNotAlterProcessState() {
        val before = controller.state.value.saved.session
        controller.testAlarm()
        Thread.sleep(400)
        assertEquals(before, controller.state.value.saved.session)
        controller.dismissError()
    }

    @Test fun reactorDialogHasOneEditableFieldAndCalculatedTotal() {
        device.findObject(By.text("Configure timing")).click()
        device.wait(Until.hasObject(By.text("Save reactor setting")), 5000)
        val inputs = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals("Only the peak-temperature hold should be editable", 1, inputs.size)
        inputs.single().text = "900"
        assertNotNull(device.wait(Until.findObject(By.text("1:20:00  ·  4800 seconds")), 5000))
        device.findObject(By.text("Save reactor setting")).click()
        waitFor { controller.state.value.saved.session.config.reactorSeconds == 900L }
        assertEquals(4800L, controller.state.value.saved.session.config.growthSeconds)
    }

    @Test fun compactNotificationContainsLiveClockWithoutExpandedPanel() {
        controller.preferences(Preferences(sound = false, vibration = false))
        waitFor { !controller.state.value.saved.preferences.simulation }
        controller.start()
        waitFor { controller.state.value.saved.session.stage == Stage.VACUUM_1 }
        waitFor { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == Notifications.STATUS_ID } }
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == Notifications.STATUS_ID }.notification
        instrumentation.runOnMainSync {
            assertNull("No separate expanded card", notification.bigContentView)
            val compact = notification.contentView.apply(app, null)
            val process = compact.findViewById<TextView>(R.id.process_name)
            assertTrue(process.text.toString().contains("Vacuum"))
            val clock = compact.findViewById<Chronometer>(R.id.countdown)
            assertTrue(clock.textSize > process.textSize)
            assertTrue(clock.isCountDown)
            assertTrue(clock.base > android.os.SystemClock.elapsedRealtime())
            assertEquals(android.view.View.VISIBLE, clock.visibility)
            assertNull("No colored content panel", compact.background)
        }
    }

    @Test fun growthReadyHasNoPushAndCompletionNeedsOneClickBeforeNextGrowth() {
        controller.start()
        waitFor { controller.state.value.saved.session.stage == Stage.VACUUM_1 }
        repeat(6) {
            val previous = controller.state.value.saved.session.stage
            controller.advance(true)
            waitFor { controller.state.value.saved.session.stage != previous }
        }
        assertEquals(Stage.GROWTH_READY, controller.state.value.saved.session.stage)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.all {
            it.id == Notifications.STATUS_ID
        })
        controller.startGrowth()
        waitFor { controller.state.value.saved.session.stage == Stage.GROWTH_RUNNING }
        val previous = controller.state.value.saved.session
        // Use the legacy short estimate API to exercise real UI completion without a 150 s wait.
        controller.edit(TimingConfig(growthSeconds = 1, conversion = Conversion.MANUAL))
        waitFor { controller.state.value.saved.session.stage == Stage.GROWTH_FINISHED }
        assertTrue(device.wait(Until.hasObject(By.text("Process complete")), 6000))
        // Compose exposes the label separately from its clickable button container.
        device.findObjects(By.text("Process complete")).last().click()
        waitFor { controller.state.value.saved.session.readyForNextGrowth }
        assertFalse(device.hasObject(By.text("Confirm")))
        waitFor { app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty() }
        val prepareNext = device.wait(Until.findObject(By.text("Prepare next growth")), 6000)
        assertNotNull(prepareNext)
        prepareNext.click()
        waitFor { controller.state.value.saved.session.stage == Stage.GROWTH_READY }
        assertTrue(TimerEngine.pending(controller.state.value.saved.session).isEmpty())
        device.wait(Until.findObject(By.text("Start Growth")), 6000).click()
        waitFor { controller.state.value.saved.session.stage == Stage.GROWTH_RUNNING }
        val next = controller.state.value.saved.session
        assertNotEquals(previous.id, next.id)
        assertTrue(next.growthStartedElapsed > previous.growthStartedElapsed)
        assertEquals(4500L, next.config.growthSeconds)
        assertEquals(3, TimerEngine.pending(next).size)
    }
}
