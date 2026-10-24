package lt.ignas.mocvd.domain

import kotlin.math.PI
import kotlin.math.sin

/** Two distinct double beeps, in real milliseconds even during accelerated timer tests. */
object StageAlarmPattern {
    val durations = listOf(140L, 100L, 140L, 500L, 140L, 100L, 140L)
    val totalMillis get() = durations.sum()
    val vibration = longArrayOf(0, 140, 100, 140, 500, 140, 100, 140)

    fun pcm(sampleRate: Int = 22050): ShortArray {
        val pcm = ShortArray((totalMillis * sampleRate / 1000).toInt())
        var cursor = 0
        durations.forEachIndexed { index, milliseconds ->
            val samples = (milliseconds * sampleRate / 1000).toInt()
            if (index % 2 == 0) repeat(samples) { i ->
                val fade = minOf(1.0, i / (sampleRate * .005), (samples - i - 1) / (sampleRate * .005)).coerceAtLeast(0.0)
                if (cursor + i < pcm.size) pcm[cursor + i] = (sin(2 * PI * 1050 * i / sampleRate) * 20000 * fade).toInt().toShort()
            }
            cursor += samples
        }
        return pcm
    }
}
