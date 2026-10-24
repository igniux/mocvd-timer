package lt.ignas.mocvd.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lt.ignas.mocvd.MocvdApplication

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        // Reconcile the saved current deadlines, never trust an old PendingIntent to advance a stage.
        (context.applicationContext as MocvdApplication).controller.refresh { pending.finish() }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        (context.applicationContext as MocvdApplication).controller.refresh { pending.finish() }
    }
    companion object {
        private val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
    }
}
