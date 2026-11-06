package com.mytasks.planner

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Status {
    const val TODO = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val SKIPPED = 4
}

object Priority {
    const val HIGH = 0
    const val MEDIUM = 1
    const val LOW = 2
}

object RepeatType {
    const val DAILY = 0
    const val WEEKDAYS = 1   // selected weekdays (bit mask, bit0 = Monday)
    const val WEEKLY = 2     // same weekday as the start date
    const val INTERVAL = 3   // every N days
}

/**
 * One concrete task on one day. Recurring tasks are expanded into these rows.
 * The unique index (seriesId, date) is what prevents duplicate generation.
 */
@Entity(
    tableName = "tasks",
    indices = [Index(value = ["seriesId", "date"], unique = true), Index(value = ["date"])]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: String = "General",
    val priority: Int = Priority.MEDIUM,
    val date: Long,                 // LocalDate epoch day
    val startMinute: Int,           // minutes after midnight
    val endMinute: Int,
    val status: Int = Status.TODO,
    val seriesId: Long? = null,
    val customized: Boolean = false, // edited on its own ("only this day")
    val timerAccumulatedMs: Long = 0,
    val timerStartedAt: Long = 0,    // epoch millis when the running timer started, 0 = not running
    val completedAt: Long = 0,
    val reminders: String = "10"     // comma separated "minutes before start"
)

/** The recurring schedule ("template") that generates TaskEntity rows. */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: String = "General",
    val priority: Int = Priority.MEDIUM,
    val startMinute: Int,
    val endMinute: Int,
    val startDate: Long,
    val endDate: Long = -1,          // -1 = never ends
    val repeatType: Int = RepeatType.DAILY,
    val weekdayMask: Int = 0b0011111,
    val intervalDays: Int = 1,
    val reminders: String = "10"
)

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(t: TaskEntity): Long

    @Update
    suspend fun updateTask(t: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE date = :day ORDER BY startMinute, endMinute, id")
    fun observeDay(day: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE date < :day AND status IN (0,1,2) ORDER BY date, startMinute")
    fun observeOpenBefore(day: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY date, startMinute, id")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE date >= :from AND date <= :to AND status IN (0,1,2)")
    suspend fun openBetween(from: Long, to: Long): List<TaskEntity>

    /** Future, untouched, not-started instances of a series: safe to delete and regenerate. */
    @Query("SELECT * FROM tasks WHERE seriesId = :sid AND date >= :from AND status = 0 AND customized = 0")
    suspend fun regenerable(sid: Long, from: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE seriesId = :sid AND date >= :from AND status = 0 AND customized = 0")
    suspend fun deleteRegenerable(sid: Long, from: Long)

    @Query("SELECT * FROM tasks WHERE seriesId = :sid AND date >= :from AND status = 0")
    suspend fun futureTodo(sid: Long, from: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE seriesId = :sid AND date >= :from AND status = 0")
    suspend fun deleteFutureTodo(sid: Long, from: Long)

    @Insert
    suspend fun insertSeries(s: SeriesEntity): Long

    @Update
    suspend fun updateSeries(s: SeriesEntity)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteSeries(id: Long)

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getSeries(id: Long): SeriesEntity?

    @Query("SELECT * FROM series ORDER BY title")
    fun observeSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series")
    suspend fun allSeries(): List<SeriesEntity>
}

@Database(entities = [TaskEntity::class, SeriesEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Add a Migration here every time the database version is increased.
         * We never use "destructive" fallback, so user data is never wiped.
         */
        private val MIGRATIONS: Array<Migration> = arrayOf()

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "tasks.db"
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}

// ---------- helpers ----------

fun TaskEntity.isOpen(): Boolean =
    status == Status.TODO || status == Status.RUNNING || status == Status.PAUSED

fun TaskEntity.startMillis(): Long = millisOf(date, startMinute)
fun TaskEntity.endMillis(): Long = millisOf(date, endMinute)
fun TaskEntity.isOverdue(now: Long): Boolean = isOpen() && endMillis() < now

/** Time spent: derived from wall-clock timestamps, so it survives app kills and reboots. */
fun TaskEntity.elapsedMs(now: Long): Long =
    timerAccumulatedMs + if (timerStartedAt > 0) (now - timerStartedAt).coerceAtLeast(0) else 0

fun TaskEntity.reminderList(): List<Int> =
    reminders.split(",").mapNotNull { it.trim().toIntOrNull() }

fun millisOf(epochDay: Long, minute: Int): Long =
    LocalDate.ofEpochDay(epochDay)
        .atTime(LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59)))
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

fun SeriesEntity.occursOn(day: Long): Boolean {
    if (day < startDate) return false
    if (endDate >= 0 && day > endDate) return false
    val date = LocalDate.ofEpochDay(day)
    return when (repeatType) {
        RepeatType.DAILY -> true
        RepeatType.WEEKDAYS -> ((weekdayMask shr (date.dayOfWeek.value - 1)) and 1) == 1
        RepeatType.WEEKLY -> date.dayOfWeek == LocalDate.ofEpochDay(startDate).dayOfWeek
        RepeatType.INTERVAL -> (day - startDate) % intervalDays.coerceAtLeast(1) == 0L
        else -> false
    }
}
