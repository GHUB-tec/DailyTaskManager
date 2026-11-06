@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytasks.planner

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

const val EDITOR_NEW = 0
const val EDITOR_TASK = 1
const val EDITOR_SERIES = 2

private val CATEGORY_SUGGESTIONS = listOf("General", "Work", "Personal", "Health", "Study", "Home", "Finance")
private val REPEAT_LABELS = listOf("Doesn't repeat", "Every day", "Selected days", "Every week", "Every N days")
private const val DEFAULT_DURATION_MIN = 60

@Composable
fun EditorScreen(kind: Int, id: Long, repo: TaskRepository, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val now = LocalTime.now()
    val defaultStart = (((now.hour + 1).coerceAtMost(22)) * 60)

    var loaded by rememberSaveable { mutableStateOf(kind == EDITOR_NEW) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("General") }
    var priority by rememberSaveable { mutableStateOf(Priority.MEDIUM) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var startMin by rememberSaveable { mutableStateOf(defaultStart) }
    var endMin by rememberSaveable { mutableStateOf((defaultStart + DEFAULT_DURATION_MIN).coerceAtMost(1439)) }
    var reminderMask by rememberSaveable { mutableStateOf(0b00010) } // bit i = REMINDER_OPTIONS[i]; default 10 min
    var repeatKind by rememberSaveable { mutableStateOf(0) }        // index into REPEAT_LABELS
    var weekdayMask by rememberSaveable { mutableStateOf(0b0011111) }
    var intervalText by rememberSaveable { mutableStateOf("2") }
    var endDate by rememberSaveable { mutableStateOf(-1L) }
    var showScopeDialog by rememberSaveable { mutableStateOf(false) }

    var original by remember { mutableStateOf<TaskEntity?>(null) }
    var series by remember { mutableStateOf<SeriesEntity?>(null) }

    fun maskFrom(reminders: String): Int {
        var mask = 0
        reminders.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach {
            val i = AlarmScheduler.REMINDER_OPTIONS.indexOf(it)
            if (i >= 0) mask = mask or (1 shl i)
        }
        return mask
    }

    fun fillRepeat(s: SeriesEntity) {
        repeatKind = s.repeatType + 1
        weekdayMask = s.weekdayMask
        intervalText = s.intervalDays.toString()
        endDate = s.endDate
    }

    LaunchedEffect(kind, id) {
        when (kind) {
            EDITOR_TASK -> {
                val t = repo.getTask(id)
                original = t
                if (t != null) {
                    series = t.seriesId?.let { repo.getSeries(it) }
                    if (!loaded) {
                        title = t.title; description = t.description; notes = t.notes
                        category = t.category; priority = t.priority; date = t.date
                        startMin = t.startMinute; endMin = t.endMinute
                        reminderMask = maskFrom(t.reminders)
                        series?.let { fillRepeat(it) }
                        loaded = true
                    }
                }
            }
            EDITOR_SERIES -> {
                val s = repo.getSeries(id)
                series = s
                if (s != null && !loaded) {
                    title = s.title; description = s.description; notes = s.notes
                    category = s.category; priority = s.priority; date = s.startDate
                    startMin = s.startMinute; endMin = s.endMinute
                    reminderMask = maskFrom(s.reminders)
                    fillRepeat(s)
                    loaded = true
                }
            }
        }
    }

    val isInstance = kind == EDITOR_TASK && series != null
    val showRepeat = kind != EDITOR_TASK || isInstance

    fun reminderString(): String =
        AlarmScheduler.REMINDER_OPTIONS.filterIndexed { i, _ -> ((reminderMask shr i) and 1) == 1 }.joinToString(",")

    fun validate(): String? = when {
        title.isBlank() -> "Please enter a task name"
        endMin <= startMin -> "End time must be after the start time"
        showRepeat && repeatKind == 2 && weekdayMask == 0 -> "Choose at least one weekday"
        showRepeat && repeatKind == 4 && (intervalText.toIntOrNull() ?: 0) < 1 -> "Enter a number of days (1 or more)"
        showRepeat && repeatKind > 0 && endDate >= 0 && endDate < date -> "The end date is before the start date"
        else -> null
    }

    fun buildTask(base: TaskEntity?): TaskEntity =
        (base ?: TaskEntity(title = "", date = date, startMinute = startMin, endMinute = endMin)).copy(
            title = title.trim(),
            description = description.trim(),
            notes = notes.trim(),
            category = category.trim().ifBlank { "General" },
            priority = priority,
            date = date,
            startMinute = startMin,
            endMinute = endMin,
            reminders = reminderString()
        )

    fun buildSeries(base: SeriesEntity?, startDate: Long): SeriesEntity =
        (base ?: SeriesEntity(title = "", startMinute = startMin, endMinute = endMin, startDate = startDate)).copy(
            title = title.trim(),
            description = description.trim(),
            notes = notes.trim(),
            category = category.trim().ifBlank { "General" },
            priority = priority,
            startMinute = startMin,
            endMinute = endMin,
            startDate = startDate,
            endDate = endDate,
            repeatType = (repeatKind - 1).coerceAtLeast(0),
            weekdayMask = weekdayMask,
            intervalDays = (intervalText.toIntOrNull() ?: 1).coerceAtLeast(1),
            reminders = reminderString()
        )

    fun save() {
        val error = validate()
        if (error != null) {
            Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
            return
        }
        when {
            kind == EDITOR_NEW -> scope.launch {
                if (repeatKind == 0) repo.addOneOff(buildTask(null)) else repo.addSeries(buildSeries(null, date))
                onClose()
            }
            kind == EDITOR_SERIES -> scope.launch {
                repo.updateSeries(buildSeries(series, date))
                onClose()
            }
            isInstance -> showScopeDialog = true
            else -> scope.launch {
                val base = original ?: return@launch
                if (repo.updateTask(buildTask(base))) onClose()
                else Toast.makeText(ctx, "Another task already uses that day", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (kind) {
                            EDITOR_NEW -> "New task"
                            EDITOR_SERIES -> "Edit recurring series"
                            else -> "Edit task"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = { save() }, enabled = loaded) { Text("Save") } }
            )
        }
    ) { inner ->
        if (!loaded) {
            Text("Loading…", modifier = Modifier.padding(inner).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Task name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth()
            )

            Text("Category", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = category, onValueChange = { category = it },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORY_SUGGESTIONS.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }

            Text("Priority", style = MaterialTheme.typography.titleMedium)
            val priorities = listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                priorities.forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = priority == p,
                        onClick = { priority = p },
                        shape = SegmentedButtonDefaults.itemShape(index, priorities.size)
                    ) { Text(priorityName(p)) }
                }
            }

            Text(
                if (kind == EDITOR_SERIES) "Starts on" else "When",
                style = MaterialTheme.typography.titleMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val d = LocalDate.ofEpochDay(date)
                        DatePickerDialog(ctx, { _, y, m, day -> date = LocalDate.of(y, m + 1, day).toEpochDay() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text(formatDay(date)) }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(ctx, { _, h, m -> startMin = h * 60 + m }, startMin / 60, startMin % 60, DateFormat.is24HourFormat(ctx)).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(formatMinute(startMin)) }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(ctx, { _, h, m -> endMin = h * 60 + m }, endMin / 60, endMin % 60, DateFormat.is24HourFormat(ctx)).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(formatMinute(endMin)) }
            }
            Text(
                "Date · start time · end time (deadline)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isInstance) {
                Text(
                    "The date applies to this day only. When you save you can choose “this day only” or “entire series”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Remind me before it starts", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmScheduler.REMINDER_OPTIONS.forEachIndexed { i, m ->
                    val on = ((reminderMask shr i) and 1) == 1
                    FilterChip(
                        selected = on,
                        onClick = { reminderMask = reminderMask xor (1 shl i) },
                        label = { Text("$m min") }
                    )
                }
            }
            Text(
                "You are always alarmed when the task starts and when the deadline arrives.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (showRepeat) {
                Text("Repeat", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REPEAT_LABELS.forEachIndexed { i, label ->
                        FilterChip(selected = repeatKind == i, onClick = { repeatKind = i }, label = { Text(label) })
                    }
                }
                if (repeatKind == 2) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WEEKDAY_SHORT.forEachIndexed { i, name ->
                            val on = ((weekdayMask shr i) and 1) == 1
                            FilterChip(selected = on, onClick = { weekdayMask = weekdayMask xor (1 shl i) }, label = { Text(name) })
                        }
                    }
                }
                if (repeatKind == 4) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(3) },
                        label = { Text("Repeat every … days") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (repeatKind > 0) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val base = if (endDate >= 0) endDate else maxOf(date, LocalDate.now().toEpochDay())
                            val d = LocalDate.ofEpochDay(base)
                            DatePickerDialog(ctx, { _, y, m, day -> endDate = LocalDate.of(y, m + 1, day).toEpochDay() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }) { Text(if (endDate >= 0) "Ends ${formatDay(endDate)}" else "Never ends") }
                        if (endDate >= 0) TextButton(onClick = { endDate = -1L }) { Text("Clear end date") }
                    }
                }
            }
            Text("", modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (showScopeDialog) {
        AlertDialog(
            onDismissRequest = { showScopeDialog = false },
            title = { Text("Edit recurring task") },
            text = { Text("Apply your changes to only this day, or to the entire series (all upcoming days)?") },
            confirmButton = {
                TextButton(onClick = {
                    showScopeDialog = false
                    scope.launch {
                        val s = series ?: return@launch
                        repo.updateSeries(buildSeries(s, s.startDate))
                        onClose()
                    }
                }) { Text("Entire series") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScopeDialog = false
                    scope.launch {
                        val base = original ?: return@launch
                        if (repo.updateTask(buildTask(base).copy(customized = true))) onClose()
                        else Toast.makeText(ctx, "Another task already uses that day", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Only this day") }
            }
        )
    }
}
