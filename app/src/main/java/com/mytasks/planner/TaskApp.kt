package com.mytasks.planner

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TaskApp : Application() {
    lateinit var repo: TaskRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = TaskRepository(this, AppDatabase.get(this).dao())
        Notifier.ensureChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(6, TimeUnit.HOURS).build()
        )
    }
}
