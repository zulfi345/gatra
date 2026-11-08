package id.gatra.app

import android.app.PictureInPictureParams
import android.content.Intent
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
        setContent {
            GatraTheme {
                CompositionLocalProvider(LocalInPip provides inPip) {
                    GatraRoot(gatra.playback, gatra.repo)
                }
            }
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
