package id.gatra.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.gatra.app.ui.GatraRoot
import id.gatra.app.ui.LocalInPip
import id.gatra.app.ui.theme.GatraTheme

class MainActivity : ComponentActivity() {

    private val gatra get() = application as GatraApp
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        askNotificationPermission()
        setContent {
            GatraTheme {
                CompositionLocalProvider(LocalInPip provides inPip) {
                    GatraRoot(gatra.playback, gatra.repo)
                }
            }
        }
    }

    /** Android 13+: izin notifikasi agar kemajuan unduhan terlihat. Ditanyakan sekali saja. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("gatra", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notif", false)) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Buka tautan YouTube yang dibagikan / diketuk dari aplikasi lain. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val text = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val match = LINK.find(text) ?: return
        gatra.playback.open(match.value)
    }

    /** Tekan Home saat video diputar: otomatis jadi jendela kecil (Picture-in-Picture). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = gatra.playback.state.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && s.active && s.expanded && s.isPlaying) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    private companion object {
        val LINK = Regex("""https?://(?:(?:www|m)\.)?(?:youtube\.com/(?:watch|shorts|live|embed)\S*|youtu\.be/\S+)""")
    }
}
