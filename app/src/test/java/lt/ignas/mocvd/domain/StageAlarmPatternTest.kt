package lt.ignas.mocvd.domain

import org.junit.Assert.*
import org.junit.Test

class StageAlarmPatternTest {
    @Test fun `stage audio is two double beeps with a long pause between pairs`() {
        val sampleRate = 22050
        val pcm = StageAlarmPattern.pcm(sampleRate)
        fun between(fromMs: Int, toMs: Int) = pcm.sliceArray(fromMs * sampleRate / 1000 until toMs * sampleRate / 1000)
        listOf(20 to 120, 260 to 360, 900 to 1000, 1140 to 1240).forEach { (start, end) ->
            assertTrue("A beep is missing at $start ms", between(start, end).any { it.toInt() != 0 })
        }
        listOf(150 to 230, 390 to 870, 1030 to 1110).forEach { (start, end) ->
            assertTrue("Expected silence at $start ms", between(start, end).all { it.toInt() == 0 })
        }
        assertEquals(1260L, StageAlarmPattern.totalMillis)
        assertEquals(sampleRate * 1260 / 1000, pcm.size)
    }
}
