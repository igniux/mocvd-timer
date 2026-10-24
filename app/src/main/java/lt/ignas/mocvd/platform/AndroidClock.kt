package lt.ignas.mocvd.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import lt.ignas.mocvd.domain.ClockReading

class AndroidClock(private val context: Context) {
    fun read() = ClockReading(SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
}
