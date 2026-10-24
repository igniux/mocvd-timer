package lt.ignas.mocvd.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import lt.ignas.mocvd.domain.Preferences
import lt.ignas.mocvd.domain.StageAlarmPattern
import java.util.concurrent.Executors

/** Audio runs separately so starting the next stage is not delayed by the beep pattern.
 * AlarmReceiver's goAsync result remains outstanding until playback completes. */
class StageAlarm(private val context: Context, private val onError: (String) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    fun play(preferences: Preferences) {
        executor.execute {
            try { playBlocking(preferences) }
            catch (e: Exception) { onError(e.message ?: "Unable to play the stage alarm.") }
        }
    }

    fun afterPlayback(callback: () -> Unit) { executor.execute(callback) }

    private fun playBlocking(preferences: Preferences) {
        if (preferences.vibration) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            vibrator.vibrate(VibrationEffect.createWaveform(StageAlarmPattern.vibration, -1))
        }
        if (!preferences.sound) return
        val audioManager = context.getSystemService(AudioManager::class.java)
        check(audioManager.getStreamVolume(AudioManager.STREAM_ALARM) > 0) {
            "Stage alarm volume is zero. Raise the phone's alarm volume and use Test stage alarm."
        }
        val sampleRate = 22050
        val pcm = StageAlarmPattern.pcm(sampleRate)
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        try {
            // Static tracks are STATE_NO_STATIC_DATA until their PCM has been written.
            check(audio.state != AudioTrack.STATE_UNINITIALIZED) { "Unable to initialize stage alarm audio." }
            check(audio.write(pcm, 0, pcm.size) == pcm.size) { "Unable to load stage alarm sound." }
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "Stage alarm audio is not ready after loading sound." }
            audio.play()
            Thread.sleep(StageAlarmPattern.totalMillis + 100)
        } finally { audio.release() }
    }
}
