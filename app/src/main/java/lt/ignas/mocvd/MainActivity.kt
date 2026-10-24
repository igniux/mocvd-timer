package lt.ignas.mocvd

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import lt.ignas.mocvd.ui.MocvdScreen

class MainActivity : ComponentActivity() {
    private val controller get() = (application as MocvdApplication).controller
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            DisposableEffect(state.saved.preferences.keepAwake) {
                if (state.saved.preferences.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            MocvdScreen(state, controller,
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                },
                onExactAlarms = {
                    if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        "package:$packageName".toUri()))
                },
            )
        }
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open the app's permissions in Android Settings.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); controller.refresh() }
}
