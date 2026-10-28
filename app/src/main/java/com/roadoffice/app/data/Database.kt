package com.roadoffice.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val distanceKm: Double,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val destinationName: String?,
    val destinationAddress: String?,
    val tollFareEstimate: Int?,
    /** BUSINESS / PERSONAL / UNKNOWN */
    val purpose: String,
    val clientId: Long?,
    /** 자동 분류인지 사용자가 정했는지 — 증빙 신뢰도 표시용 */
    val purposeByUser: Boolean = false,
)

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Double = 300.0,
    val createdAt: Long,
)

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY startAt DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE startAt >= :from AND startAt < :to ORDER BY startAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TripEntity>>

    @Query("UPDATE trips SET purpose = :purpose, purposeByUser = 1 WHERE id = :id")
    suspend fun setPurpose(id: Long, purpose: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

@Dao
interface ClientDao {
    @Insert
    suspend fun insert(client: ClientEntity): Long

    @Query("SELECT * FROM clients ORDER BY name")
    fun observeAll(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients")
    suspend fun all(): List<ClientEntity>

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [TripEntity::class, ClientEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun clients(): ClientDao

    companion object {
        /** SQLCipher 로 DB 파일 전체를 암호화. 암호키는 [DbKeyStore] 가 Keystore 로 감싸 보관한다. */
        fun open(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DbKeyStore(context).passphrase()
            return Room.databaseBuilder(context, AppDatabase::class.java, "roadoffice.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
