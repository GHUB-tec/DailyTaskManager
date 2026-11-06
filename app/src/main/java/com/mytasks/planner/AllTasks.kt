@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytasks.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun AllTasksScreen(
    all: List<TaskEntity>,
    series: List<SeriesEntity>,
    now: Long,
    actions: TaskActions,
    onEditSeries: (Long) -> Unit,
    onDeleteSeries: (Long) -> Unit,
    padding: PaddingValues
) {
    var filter by rememberSaveable { mutableStateOf(0) } // 0 open, 1 finished, 2 recurring
    val today = LocalDate.now().toEpochDay()

    val shown = when (filter) {
        0 -> all.filter { it.isOpen() }.sortedWith(compareBy({ it.date }, { it.startMinute }))
        1 -> all.filter { !it.isOpen() }.sortedWith(compareBy<TaskEntity>({ -it.date }, { it.startMinute }))
        else -> emptyList()
    }
    val grouped = shown.groupBy { it.date }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("All tasks", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("Open") })
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Finished") })
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Recurring") })
            }
        }

        if (filter == 2) {
            if (series.isEmpty()) {
                item {
                    Text(
                        "No recurring schedules yet. Create a task and choose a repeat option.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(series, key = { "s${it.id}" }) { s ->
                SeriesCard(s, onEdit = { onEditSeries(s.id) }, onDelete = { onDeleteSeries(s.id) })
            }
        } else {
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (filter == 0) "No open tasks. Enjoy the free time!" else "Nothing finished yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            grouped.forEach { (day, list) ->
                item(key = "h$day") { SectionTitle(dayLabel(day, today)) }
                items(list, key = { it.id }) { TaskCard(it, now, actions) }
            }
        }
    }
}

@Composable
private fun SeriesCard(s: SeriesEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatMinute(s.startMinute)} – ${formatMinute(s.endMinute)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                repeatDescription(s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit series") }
                TextButton(onClick = { confirm = true }) { Text("Delete") }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete recurring schedule?") },
            text = { Text("Upcoming, not-started tasks of \"${s.title}\" will be removed. Completed history is kept.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
