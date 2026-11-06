package com.mytasks.planner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo: TaskRepository = (app as TaskApp).repo

    private val todayFlow = MutableStateFlow(LocalDate.now().toEpochDay())
    val today: StateFlow<Long> = todayFlow

    val todayTasks: StateFlow<List<TaskEntity>> =
        todayFlow.flatMapLatest { repo.observeDay(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val backlog: StateFlow<List<TaskEntity>> =
        todayFlow.flatMapLatest { repo.observeOpenBefore(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTasks: StateFlow<List<TaskEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesList: StateFlow<List<SeriesEntity>> =
        repo.observeSeries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        // Detect midnight while the app is open.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val d = LocalDate.now().toEpochDay()
                if (d != todayFlow.value) {
                    todayFlow.value = d
                    repo.refresh()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val d = LocalDate.now().toEpochDay()
            if (d != todayFlow.value) todayFlow.value = d
            repo.refresh()
        }
    }

    fun start(t: TaskEntity) = viewModelScope.launch { repo.startTimer(t.id) }
    fun pause(t: TaskEntity) = viewModelScope.launch { repo.pauseTimer(t.id) }
    fun stop(t: TaskEntity) = viewModelScope.launch { repo.stopTimer(t.id) }
    fun done(t: TaskEntity) = viewModelScope.launch { repo.complete(t.id) }
    fun reopen(t: TaskEntity) = viewModelScope.launch { repo.reopen(t.id) }
    fun skip(t: TaskEntity) = viewModelScope.launch { repo.skip(t.id) }
    fun delete(t: TaskEntity) = viewModelScope.launch { repo.deleteTask(t) }
    fun deleteSeries(id: Long) = viewModelScope.launch { repo.deleteSeries(id) }
}
