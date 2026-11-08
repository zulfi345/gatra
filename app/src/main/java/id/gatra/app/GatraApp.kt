package id.gatra.app

import android.app.Application
import androidx.room.Room
import id.gatra.app.data.GatraDb
import id.gatra.app.data.GatraDownloader
import id.gatra.app.data.Repo
import id.gatra.app.player.PlaybackController
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

class GatraApp : Application() {

    lateinit var http: OkHttpClient
        private set
    lateinit var db: GatraDb
        private set
    lateinit var repo: Repo
        private set
    lateinit var playback: PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        NewPipe.init(GatraDownloader(http), Localization("id", "ID"), ContentCountry("ID"))

        db = Room.databaseBuilder(this, GatraDb::class.java, "gatra.db")
            .fallbackToDestructiveMigration()
            .build()
        repo = Repo(db, http)
        playback = PlaybackController(this, repo, http)
    }
}
