package id.gatra.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import id.gatra.app.GatraApp
import id.gatra.app.MainActivity

/**
 * Layanan latar depan agar video/audio tetap berjalan saat layar mati atau aplikasi ditinggal.
 * Pemutarnya sendiri milik PlaybackController; layanan ini hanya membungkusnya dengan sesi media.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as GatraApp).playback.uiPlayer
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = MediaSession.Builder(this, player).setSessionActivity(open).build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Aplikasi digeser dari daftar terbaru: hentikan pemutaran supaya tidak jalan tanpa kendali. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as GatraApp).playback.stopAll()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
