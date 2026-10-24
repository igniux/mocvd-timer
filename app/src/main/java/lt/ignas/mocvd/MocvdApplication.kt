package lt.ignas.mocvd

import android.app.Application

class MocvdApplication : Application() {
    val controller: TimerController by lazy { TimerController(this) }
    override fun onCreate() { super.onCreate(); controller }
}
