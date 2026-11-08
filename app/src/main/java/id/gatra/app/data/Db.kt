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
}

@Dao
interface RecDao {
    @Query(
        "SELECT * FROM recommendations WHERE url NOT IN (SELECT url FROM history) " +
            "ORDER BY savedAt DESC, rank ASC LIMIT 60"
    )
    fun visible(): Flow<List<RecEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecEntity>)

    @Query(
        "DELETE FROM recommendations WHERE url NOT IN " +
            "(SELECT url FROM recommendations ORDER BY savedAt DESC, rank ASC LIMIT 120)"
    )
    suspend fun prune()
}

@Database(
    entities = [SubscriptionEntity::class, HistoryEntity::class, RecEntity::class],
    version = 2,
    exportSchema = false
)
abstract class GatraDb : RoomDatabase() {
    abstract fun subs(): SubscriptionDao
    abstract fun history(): HistoryDao
    abstract fun recs(): RecDao
}

/** Versi 2: rekomendasi menyimpan tautan channel dan foto profilnya. Data lama tidak dihapus. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recommendations ADD COLUMN channelUrl TEXT")
        db.execSQL("ALTER TABLE recommendations ADD COLUMN avatar TEXT")
    }
}
