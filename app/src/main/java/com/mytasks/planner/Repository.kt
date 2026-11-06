package com.mytasks.planner

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class TaskRepository(private val ctx: Context, private val dao: TaskDao) {

    companion object {
        /** How many days ahead recurring tasks are expanded into real task rows. */
        const val GENERATE_AHEAD_DAYS = 30L
    }

    fun observeDay(day: Long): Flow<List<TaskEntity>> = dao.observeDay(day)
    fun observeOpenBefore(day: Long): Flow<List<TaskEntity>> = dao.observeOpenBefore(day)
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()
    fun observeSeries(): Flow<List<SeriesEntity>> = dao.observeSeries()
    suspend fun getTask(id: Long): TaskEntity? = dao.getTask(id)
    suspend fun getSeries(id: Long): SeriesEntity? = dao.getSeries(id)

    // ---------- create / edit / delete ----------

    suspend fun addOneOff(t: TaskEntity) {
        val id = dao.insertTask(t)
        if (id > 0) AlarmScheduler.schedule(ctx, t.copy(id = id))
    }

    suspend fun addSeries(s: SeriesEntity) {
        val sid = dao.insertSeries(s)
        val today = LocalDate.now().toEpochDay()
        generateFor(s.copy(id = sid), today, today + GENERATE_AHEAD_DAYS)
        rescheduleAll()
    }

    /** Edit a single task row. Returns false if it collides with another instance of the same series. */
    suspend fun updateTask(t: TaskEntity): Boolean = try {
        dao.updateTask(t)
        AlarmScheduler.schedule(ctx, t)
        true
    } catch (e: SQLiteConstraintException) {
        false
    }

    /** Edit the whole series: untouched future instances are rebuilt from the new schedule. */
    suspend fun updateSeries(s: SeriesEntity) {
        dao.updateSeries(s)
        val today = LocalDate.now().toEpochDay()
        dao.regenerable(s.id, today).forEach { AlarmScheduler.cancel(ctx, it.id) }
        dao.deleteRegenerable(s.id, today)
        generateFor(s, today, today + GENERATE_AHEAD_DAYS)
        rescheduleAll()
    }

    /** Deleting one instance of a series = skipping that day (the schedule stays). */
    suspend fun deleteTask(t: TaskEntity) {
        AlarmScheduler.cancel(ctx, t.id)
        Notifier.dismiss(ctx, t.id)
        if (t.seriesId != null) skip(t.id) else dao.deleteTask(t.id)
    }

    suspend fun deleteSeries(seriesId: Long) {
        val today = LocalDate.now().toEpochDay()
        dao.futureTodo(seriesId, today).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            Notifier.dismiss(ctx, it.id)
        }
        dao.deleteFutureTodo(seriesId, today)
        dao.deleteSeries(seriesId)
    }

    suspend fun skip(id: Long) {
        val t = dao.getTask(id) ?: return
        val now = System.currentTimeMillis()
        dao.updateTask(t.copy(status = Status.SKIPPED, timerAccumulatedMs = t.elapsedMs(now), timerStartedAt = 0))
        AlarmScheduler.cancel(ctx, id)
        Notifier.dismiss(ctx, id)
    }

    // ---------- timer ----------

    suspend fun startTimer(id: Long) {
        val t = dao.getTask(id) ?: return
        if (!t.isOpen() || t.status == Status.RUNNING) return
        dao.updateTask(t.copy(status = Status.RUNNING, timerStartedAt = System.currentTimeMillis()))
    }

    suspend fun pauseTimer(id: Long) {
        val t = dao.getTask(id) ?: return
        if (t.status != Status.RUNNING) return
        val now = System.currentTimeMillis()
        dao.updateTask(t.copy(status = Status.PAUSED, timerAccumulatedMs = t.elapsedMs(now), timerStartedAt = 0))
    }

    /** Stop = end the session, keep the time spent, return to "to do". */
    suspend fun stopTimer(id: Long) {
        val t = dao.getTask(id) ?: return
        if (!t.isOpen()) return
        val now = System.currentTimeMillis()
        dao.updateTask(t.copy(status = Status.TODO, timerAccumulatedMs = t.elapsedMs(now), timerStartedAt = 0))
    }

    suspend fun complete(id: Long) {
        val t = dao.getTask(id) ?: return
        if (!t.isOpen()) return
        val now = System.currentTimeMillis()
        dao.updateTask(
            t.copy(status = Status.DONE, timerAccumulatedMs = t.elapsedMs(now), timerStartedAt = 0, completedAt = now)
        )
        AlarmScheduler.cancel(ctx, id)
        Notifier.dismiss(ctx, id)
    }

    suspend fun reopen(id: Long) {
        val t = dao.getTask(id) ?: return
        if (t.status != Status.DONE && t.status != Status.SKIPPED) return
        val reopened = t.copy(status = Status.TODO, completedAt = 0)
        dao.updateTask(reopened)
        AlarmScheduler.schedule(ctx, reopened)
    }

    // ---------- recurring generation ----------

    /**
     * Creates task rows for [from]..[to]. The unique index on (seriesId, date) plus
     * OnConflictStrategy.IGNORE guarantees no duplicates, and skipped days stay as
     * "skipped" rows so they are never regenerated.
     */
    private suspend fun generateFor(s: SeriesEntity, from: Long, to: Long) {
        val first = maxOf(from, s.startDate)
        val last = if (s.endDate >= 0) minOf(to, s.endDate) else to
        var day = first
        while (day <= last) {
            if (s.occursOn(day)) {
                dao.insertTask(
                    TaskEntity(
                        title = s.title, description = s.description, notes = s.notes,
                        category = s.category, priority = s.priority, date = day,
                        startMinute = s.startMinute, endMinute = s.endMinute,
                        seriesId = s.id, reminders = s.reminders
                    )
                )
            }
            day++
        }
    }

    suspend fun generateAll() {
        val today = LocalDate.now().toEpochDay()
        dao.allSeries().forEach { generateFor(it, today, today + GENERATE_AHEAD_DAYS) }
    }

    suspend fun rescheduleAll() = AlarmScheduler.scheduleAll(ctx, dao)

    suspend fun refresh() {
        generateAll()
        rescheduleAll()
    }
}

/** Runs every few hours in the background: generates new daily instances and re-arms alarms. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        (applicationContext as TaskApp).repo.refresh()
        return Result.success()
    }
}
