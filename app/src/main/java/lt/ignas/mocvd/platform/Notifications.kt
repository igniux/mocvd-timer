package lt.ignas.mocvd.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import lt.ignas.mocvd.MainActivity
import lt.ignas.mocvd.R
import lt.ignas.mocvd.domain.*

class Notifications(private val context: Context, onStageAlarmError: (String) -> Unit) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val stageAlarm = StageAlarm(context, onStageAlarmError)
    init {
        manager.createNotificationChannel(NotificationChannel("status", "Active process", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Current app timer and next milestone"; setSound(null, null); enableVibration(false)
        })
        listOf(false, true).forEach { sound -> listOf(false, true).forEach { vibration ->
            manager.createNotificationChannel(NotificationChannel(channel(sound, vibration),
                "Reminders · ${if (sound) "sound" else "silent"} · ${if (vibration) "vibrate" else "no vibration"}",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "MOCVD timer milestones; delivery also follows your system settings"
                setSound(if (sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                enableVibration(vibration)
                if (vibration) vibrationPattern = longArrayOf(0, 250, 150, 250)
            })
        } }
    }

    fun isAllowed() = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun remindersEnabled(p: Preferences): Boolean = manager.getNotificationChannel(channel(p.sound, p.vibration))?.importance != NotificationManager.IMPORTANCE_NONE
    private fun channel(sound: Boolean, vibrate: Boolean) = "reminders-${if (sound) 1 else 0}-${if (vibrate) 1 else 0}"
    private fun contentIntent() = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentIntent(contentIntent())
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    fun event(s: Session, reminder: Reminder, p: Preferences) {
        if (reminder.kind == EventKind.GROWTH_READY) return
        if (reminder.kind == EventKind.STAGE_COMPLETE) {
            stageAlarm.play(p)
            return
        }
        if (!isAllowed()) return
        val title = if (s.speed > 1) "SIMULATION · MOCVD" else "MOCVD Timer"
        val late = (AndroidClock(context).read().elapsed - reminder.atElapsed) * s.speed > 10_000
        val message = when {
            late && reminder.kind == EventKind.MEASUREMENT_WARNING -> "Measurement starts soon. Check the current timer."
            late && reminder.kind == EventKind.GROWTH_WARNING -> "Growth is within its final estimated 5 minutes."
            else -> reminder.kind.message
        }
        val detail = if (reminder.kind == EventKind.STAGE_COMPLETE) "${s.stage.title} · Cycle ${s.stage.cycle}/3. Confirm the next stage manually." else message
        manager.notify(eventId(s, reminder), builder(channel(p.sound, p.vibration))
            .setContentTitle("$title · $message").setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build())
    }

    fun active(s: Session, p: Preferences, now: ClockReading) {
        // Remove obsolete v1.0 pre-reminder / preparation pop-ups after an in-place upgrade.
        (s.delivered.filter { it.startsWith("stage-") } + listOf("measurement-warning", "growth-ready")).forEach { id ->
            manager.cancel(eventId(s, Reminder(id, EventKind.STAGE_COMPLETE, 0)))
        }
        if (!s.isActive) { clear(); return }
        if (!isAllowed()) return
        val prefix = if (s.speed > 1) "SIMULATION ×30 · " else ""
        val stateLabel = when {
            s.recoveryRequired -> " · Review"
            s.phase == Phase.PAUSED -> " · Paused"
            s.stage.isPreparation && s.phase == Phase.WAITING -> " · Done"
            else -> ""
        }
        val title = "$prefix${s.stage.title}${if (s.stage.isPreparation) " · ${s.stage.cycle}/3" else ""}$stateLabel"
        val next = TimerEngine.pending(s).firstOrNull()
        val text = when {
            s.recoveryRequired -> "Device restarted. Open the app to review recovered timing."
            s.phase == Phase.PAUSED -> "App timer paused · ${formatTime(s.remainingSeconds(now))} remaining"
            s.stage == Stage.GROWTH_FINISHED -> "Estimated time finished. Reactor completion is unconfirmed."
            s.stage == Stage.GROWTH_READY -> "Waiting for you to start the growth timer."
            s.phase == Phase.WAITING -> "Stage timer completed. Waiting for your confirmation."
            else -> "Next: ${next?.kind?.message ?: "No pending reminder"}"
        }
        val notification = builder("status").setContentTitle(title)
            .setContentText("${formatTime(s.remainingSeconds(now))} remaining · $text")
            .setCustomContentView(TimerNotificationViews.create(context, s, now, title))
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        notification.setShowWhen(false)
        manager.notify(STATUS_ID, notification.build())
    }

    fun recovery(s: Session, p: Preferences) {
        if (!isAllowed()) return
        manager.notify(RECOVERY_ID, builder(channel(p.sound, p.vibration))
            .setContentTitle("MOCVD · Review recovered session")
            .setContentText("Device restarted. Check the estimated timing before resuming alerts.")
            .setAutoCancel(true).build())
    }

    fun test(p: Preferences) {
        if (!isAllowed()) return
        manager.notify(TEST_ID, builder(channel(p.sound, p.vibration)).setContentTitle("MOCVD · Test alarm")
            .setContentText("This is a test. Your process timer is unchanged.")
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    fun clearRecovery() = manager.cancel(RECOVERY_ID)
    fun testStage(p: Preferences) = stageAlarm.play(p)
    fun afterStageAlarm(callback: () -> Unit) = stageAlarm.afterPlayback(callback)
    fun clear() = manager.cancelAll()
    private fun eventId(s: Session, r: Reminder) = 1000 + ((s.id + r.id).hashCode() and 0x3fffffff)
    companion object { const val STATUS_ID = 1; const val TEST_ID = 2; const val RECOVERY_ID = 3 }
}
