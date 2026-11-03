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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** 미팅 회고 — 원문(말한 내용)과 정리 결과를 함께 보관. */
@Entity(tableName = "meeting_notes")
data class MeetingNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long?,
    val clientName: String?,
    val tripId: Long?,
    val createdAt: Long,
    val rawText: String,
    val summary: String,
    /** 할 일, 줄바꿈 구분 */
    val tasks: String,
    val followUpAt: Long?,
    /** rule / llm / server */
    val source: String,
    val tasksDone: Boolean = false,
    /** 음성·Gleo 로 들어온 정리 — 정차 중에 사람이 확인하기 전까지 "확인 필요" */
    val needsReview: Boolean = false,
)

/** 주유·충전 기록 — 주차 중 배터리·연료가 오른 것을 차량 신호로 포착. 금액은 사용자가 넣는다. */
@Entity(tableName = "refills")
data class RefillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** EV_CHARGE / FUEL */
    val kind: String,
    val startedAt: Long,
    val fromPct: Int,
    val toPct: Int,
    val lat: Double?,
    val lon: Double?,
    /** 직전 운행의 목적지(방문처) — 어디서 주유·충전했는지 */
    val place: String?,
    val amount: Long? = null,
    /** BUSINESS / PERSONAL / UNKNOWN — 직전 운행이 업무면 업무로 시작 */
    val purpose: String,
)

@Dao
interface RefillDao {
    @Insert
    suspend fun insert(r: RefillEntity): Long

    @Query("UPDATE refills SET toPct = :toPct WHERE id = :id")
    suspend fun setTo(id: Long, toPct: Int)

    @Query("UPDATE refills SET amount = :amount WHERE id = :id")
    suspend fun setAmount(id: Long, amount: Long?)

    @Query("UPDATE refills SET purpose = :purpose WHERE id = :id")
    suspend fun setPurpose(id: Long, purpose: String)

    @Query("SELECT * FROM refills WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<RefillEntity>>

    @Query("SELECT * FROM refills WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<RefillEntity>
}

@Dao
interface MeetingNoteDao {
    @Insert
    suspend fun insert(note: MeetingNoteEntity): Long

    @Query("SELECT * FROM meeting_notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MeetingNoteEntity>>

    @Query("SELECT * FROM meeting_notes WHERE clientId = :clientId ORDER BY createdAt DESC")
    suspend fun forClient(clientId: Long): List<MeetingNoteEntity>

    @Query("SELECT * FROM meeting_notes ORDER BY createdAt DESC")
    suspend fun all(): List<MeetingNoteEntity>

    @Query("SELECT COUNT(*) FROM meeting_notes WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    @Query("UPDATE meeting_notes SET summary = :summary, tasks = :tasks, needsReview = 0 WHERE id = :id")
    suspend fun review(id: Long, summary: String, tasks: String)

    @Query("UPDATE meeting_notes SET tasksDone = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)
}

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

    @Query("SELECT * FROM trips ORDER BY endAt DESC LIMIT 1")
    suspend fun latest(): TripEntity?

    @Query("SELECT * FROM trips WHERE startAt >= :from AND startAt < :to ORDER BY startAt")
    suspend fun between(from: Long, to: Long): List<TripEntity>

}

@Dao
interface ClientDao {
    @Insert
    suspend fun insert(client: ClientEntity): Long

    @Query("SELECT * FROM clients ORDER BY name")
    fun observeAll(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients")
    suspend fun all(): List<ClientEntity>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun byId(id: Long): ClientEntity?

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [TripEntity::class, ClientEntity::class, MeetingNoteEntity::class, RefillEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun clients(): ClientDao
    abstract fun notes(): MeetingNoteDao
    abstract fun refills(): RefillDao

    companion object {
        /** v4: 상담 일지 "확인 필요" 표시 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `meeting_notes` ADD COLUMN `needsReview` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: 주유·충전 기록 테이블 추가 — 기존 운행·상담 일지는 그대로 둔다 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `refills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `fromPct` INTEGER NOT NULL, `toPct` INTEGER NOT NULL, `lat` REAL, `lon` REAL, " +
                        "`place` TEXT, `amount` INTEGER, `purpose` TEXT NOT NULL)",
                )
            }
        }

        /** SQLCipher 로 DB 파일 전체를 암호화. 암호키는 [DbKeyStore] 가 Keystore 로 감싸 보관한다. */
        fun open(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DbKeyStore(context).passphrase()
            return Room.databaseBuilder(context, AppDatabase::class.java, "roadoffice.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // v1 등 이전 개발 스키마만 초기화 (v2→v3 는 위 Migration 으로 데이터 유지)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
