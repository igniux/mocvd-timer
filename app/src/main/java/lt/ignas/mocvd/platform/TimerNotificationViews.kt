package lt.ignas.mocvd.platform

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import lt.ignas.mocvd.R
import lt.ignas.mocvd.domain.*

/** A compact neutral row. The system owns the running chronometer even after process death. */
object TimerNotificationViews {
    fun create(context: Context, s: Session, now: ClockReading, process: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_timer_small)
        views.setTextViewText(R.id.process_name, process)
        val live = s.isCounting && s.speed == 1
        views.setViewVisibility(R.id.countdown, if (live) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.static_time, if (live) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.static_time, formatTime(s.remainingSeconds(now)))
        views.setChronometerCountDown(R.id.countdown, true)
        views.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + (s.deadlineElapsed - now.elapsed).coerceAtLeast(0), null, live)
        return views
    }
}
