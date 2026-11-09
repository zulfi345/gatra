package id.gatra.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val url: String,
    val avatar: String?,
    val addedAt: Long
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String,
    val thumbnail: String?,
    val duration: Long,
    val watchedAt: Long
)

/** Video terkait yang disimpan saat menonton, dipakai untuk rekomendasi di beranda. */
@Entity(tableName = "recommendations")
data class RecEntity(
    @PrimaryKey val url: String,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val duration: Long,
    val views: Long,
    val savedAt: Long,
    val rank: Int,
    val channelUrl: String? = null,
    val avatar: String? = null
)

/** Minat yang dipelajari dari kebiasaanmu: "ch:<channel>" atau "kw:<kata kunci>", dengan bobot yang meluruh seiring waktu. */
@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val name: String,
    val weight: Double,
    val updatedAt: Long
)

/** Video yang disimpan untuk ditonton nanti. */
@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumbnail: String?,
    val duration: Long,
    val addedAt: Long
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "url"])
data class PlaylistItemEntity(
    val playlistId: Long,
    val url: String,
    val title: String,
    val channel: String,
    val channelUrl: String?,
    val thumbnail: String?,
    val duration: Long,
    val addedAt: Long
)

const val DL_RUNNING = 0
const val DL_DONE = 1
const val DL_FAILED = 2

/** Video yang diunduh ke HP untuk ditonton tanpa internet. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val url: String,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val duration: Long,
    val quality: Int,
    val videoPath: String?,
    val audioPath: String?,
    val totalBytes: Long,
    val doneBytes: Long,
    val status: Int,
    val createdAt: Long
)

class PlaylistCount(val playlistId: Long, val n: Int)

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun all(): Flow<List<WatchLaterEntity>>

    @Query("SELECT COUNT(*) FROM watch_later WHERE url = :url")
    fun count(url: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun all(): Flow<List<PlaylistEntity>>

    @Query("SELECT playlistId AS playlistId, COUNT(*) AS n FROM playlist_items GROUP BY playlistId")
    fun counts(): Flow<List<PlaylistCount>>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun deleteItems(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY addedAt DESC")
    fun items(id: Long): Flow<List<PlaylistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(i: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id AND url = :url")
    suspend fun removeItem(id: Long, url: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE url = :url")
    suspend fun get(url: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: DownloadEntity)

    @Query("UPDATE downloads SET doneBytes = :done, totalBytes = :total WHERE url = :url")
    suspend fun progress(url: String, done: Long, total: Long)

    @Query("UPDATE downloads SET status = :status WHERE url = :url")
    suspend fun setStatus(url: String, status: Int)

    @Query("DELETE FROM downloads WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<SubscriptionEntity>>

    @Query("SELECT COUNT(*) FROM subscriptions WHERE channelId = :id OR url = :url")
    fun count(id: String, url: String): Flow<Int>

    @Query("SELECT * FROM subscriptions")
    suspend fun snapshot(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(s: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE channelId = :id OR url = :url")
    suspend fun delete(id: String, url: String)

    @Query("UPDATE subscriptions SET avatar = :avatar WHERE channelId = :id OR url = :url")
    suspend fun setAvatar(id: String, url: String, avatar: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT 300")
    fun all(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HistoryEntity)

    @Query("DELETE FROM history WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT channel FROM history WHERE channel != '' GROUP BY channel ORDER BY MAX(watchedAt) DESC LIMIT 12")
    suspend fun recentChannels(): List<String>

    @Query("SELECT channel FROM history WHERE channelUrl = :url AND channel != '' LIMIT 1")
    suspend fun channelNameFor(url: String): String?
}

@Dao
interface SignalDao {
    @Query("SELECT * FROM signals")
    fun allFlow(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals")
    suspend fun all(): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE name = :name")
    suspend fun get(name: String): SignalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SignalEntity)

    @Query("DELETE FROM signals WHERE weight > -0.05 AND weight < 0.05")
    suspend fun pruneZero()

    @Query("DELETE FROM signals")
    suspend fun clear()
}

@Dao
interface RecDao {
    @Query(
        "SELECT * FROM recommendations WHERE url NOT IN (SELECT url FROM history) " +
            "ORDER BY savedAt DESC, rank ASC LIMIT 60"
    )
    fun visible(): Flow<List<RecEntity>>

    /** Kumpulan calon rekomendasi (belum ditonton). Urutan akhirnya ditentukan oleh [Ranking]. */
    @Query("SELECT * FROM recommendations WHERE url NOT IN (SELECT url FROM history) ORDER BY savedAt DESC, rank ASC LIMIT 200")
    fun pool(): Flow<List<RecEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecEntity>)

    @Query(
        "DELETE FROM recommendations WHERE url NOT IN " +
            "(SELECT url FROM recommendations ORDER BY savedAt DESC, rank ASC LIMIT 250)"
    )
    suspend fun prune()
}

@Database(
    entities = [
        SubscriptionEntity::class, HistoryEntity::class, RecEntity::class,
        WatchLaterEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, DownloadEntity::class,
        SignalEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class GatraDb : RoomDatabase() {
    abstract fun subs(): SubscriptionDao
    abstract fun history(): HistoryDao
    abstract fun recs(): RecDao
    abstract fun watchLater(): WatchLaterDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun signals(): SignalDao
}

/** Versi 2: rekomendasi menyimpan tautan channel dan foto profilnya. Data lama tidak dihapus. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recommendations ADD COLUMN channelUrl TEXT")
        db.execSQL("ALTER TABLE recommendations ADD COLUMN avatar TEXT")
    }
}

/** Versi 3: tonton nanti, playlist, dan unduhan. Data lama tidak dihapus. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_later` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`channel` TEXT NOT NULL, `channelUrl` TEXT, `thumbnail` TEXT, `duration` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_items` (`playlistId` INTEGER NOT NULL, `url` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `channel` TEXT NOT NULL, `channelUrl` TEXT, `thumbnail` TEXT, " +
                "`duration` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `url`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `downloads` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`channel` TEXT NOT NULL, `thumbnail` TEXT, `duration` INTEGER NOT NULL, " +
                "`quality` INTEGER NOT NULL, `videoPath` TEXT, `audioPath` TEXT, `totalBytes` INTEGER NOT NULL, " +
                "`doneBytes` INTEGER NOT NULL, `status` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`url`))"
        )
    }
}

/** Versi 4: tabel minat untuk algoritma rekomendasi. Data lama tidak dihapus. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signals` (`name` TEXT NOT NULL, `weight` REAL NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
        )
    }
}
