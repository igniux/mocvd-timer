package lt.ignas.mocvd.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import lt.ignas.mocvd.MainActivity
import lt.ignas.mocvd.domain.*

class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private var scheduledKey: String? = null
    fun isAvailable() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()

    private fun operation(token: String = "") = PendingIntent.getBroadcast(context, 10,
        Intent(context, AlarmReceiver::class.java).putExtra("token", token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun sync(s: Session, now: ClockReading, force: Boolean = false): String? {
        val next = TimerEngine.pending(s).firstOrNull()
        val key = "${isAvailable()}:${next?.token(s)}"
        if (key == scheduledKey && !force) return null
        manager.cancel(operation())
        scheduledKey = null
        if (next == null) { scheduledKey = key; return null }
        if (!isAvailable()) return "Exact alarms are unavailable. Enable Alarms & reminders in system settings."
        return try {
            val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Alarm-clock scheduling avoids Doze batching of the two measurement reminders 5 minutes apart.
            // Its system-visible clock is intentional for this user-facing timer.
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(
                now.wall + (next.atElapsed - now.elapsed).coerceAtLeast(1), show), operation(next.token(s)))
            scheduledKey = key
            null
        } catch (_: SecurityException) {
            "Android denied exact-alarm access. Check Alarms & reminders."
        } catch (e: RuntimeException) {
            "Alarm scheduling failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun cancel() { manager.cancel(operation()); scheduledKey = null }
}
