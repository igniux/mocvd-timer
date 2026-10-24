package lt.ignas.mocvd

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lt.ignas.mocvd.domain.Preferences
import lt.ignas.mocvd.platform.StageAlarm
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises real Android audio initialization without resetting the user's timer. */
@RunWith(AndroidJUnit4::class)
class StageAlarmPlaybackTest {
    @Test fun staticAlarmLoadsAndPlaysTwiceWithoutInitializationErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        assertTrue("Raise the phone's alarm volume before running this playback test",
            manager.getStreamVolume(AudioManager.STREAM_ALARM) > 0)
        val errors = CopyOnWriteArrayList<String>()
        val alarm = StageAlarm(context) { errors.add(it) }
        val done = CountDownLatch(1)
        repeat(2) { alarm.play(Preferences(sound = true, vibration = false)) }
        alarm.afterPlayback { done.countDown() }
        assertTrue("Playback did not finish", done.await(10, TimeUnit.SECONDS))
        assertTrue("Playback errors: $errors", errors.isEmpty())
    }
}
