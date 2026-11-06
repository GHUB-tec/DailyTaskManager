@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytasks.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** All the things a task card can do. Wired up in MainActivity. */
class TaskActions(
    val onStart: (TaskEntity) -> Unit,
    val onPause: (TaskEntity) -> Unit,
    val onStop: (TaskEntity) -> Unit,
    val onDone: (TaskEntity) -> Unit,
    val onReopen: (TaskEntity) -> Unit,
    val onSkip: (TaskEntity) -> Unit,
    val onDelete: (TaskEntity) -> Unit,
    val onDeleteSeries: (Long) -> Unit,
    val onEdit: (TaskEntity) -> Unit
)

fun statusColor(t: TaskEntity, now: Long): Color = when {
    t.status == Status.DONE -> AppColors.Done
    t.status == Status.SKIPPED -> AppColors.Skipped
    t.isOverdue(now) -> AppColors.Overdue
    t.status == Status.RUNNING -> AppColors.Running
    t.status == Status.PAUSED -> AppColors.Paused
    else -> AppColors.Todo
}

fun priorityColor(p: Int): Color = when (p) {
    Priority.HIGH -> AppColors.High
    Priority.LOW -> AppColors.Low
    else -> AppColors.Medium
}

fun priorityName(p: Int): String = when (p) {
    Priority.HIGH -> "High"
    Priority.LOW -> "Low"
    else -> "Medium"
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun TaskCard(
    t: TaskEntity,
    now: Long,
    actions: TaskActions,
    modifier: Modifier = Modifier,
    showDate: Boolean = false
) {
    val accent = statusColor(t, now)
    val closed = !t.isOpen()
    val today = LocalDate.now().toEpochDay()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableIntStateOf(0) } // 1 = delete task, 2 = delete series

    ElevatedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.weight(1f).padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (closed) TextDecoration.LineThrough else null,
                            color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        )
                        val whenText = (if (showDate) dayLabel(t.date, today) + " · " else "") +
                            "${formatMinute(t.startMinute)} – ${formatMinute(t.endMinute)}"
                        Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; actions.onEdit(t) })
                            if (t.seriesId != null) {
                                if (t.isOpen()) {
                                    DropdownMenuItem(text = { Text("Skip this day") }, onClick = { menu = false; actions.onSkip(t) })
                                }
                                DropdownMenuItem(text = { Text("Delete entire series") }, onClick = { menu = false; confirm = 2 })
                            } else {
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirm = 1 })
                            }
                        }
                    }
                }

                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(priorityName(t.priority), priorityColor(t.priority))
                    Pill(t.category, MaterialTheme.colorScheme.primary)
                    when {
                        t.status == Status.DONE -> Pill("Done", AppColors.Done)
                        t.status == Status.SKIPPED -> Pill("Skipped", AppColors.Skipped)
                        t.isOverdue(now) -> Pill("Overdue", AppColors.Overdue)
                        t.status == Status.RUNNING -> Pill("Running", AppColors.Running)
                        t.status == Status.PAUSED -> Pill("Paused", AppColors.Paused)
                    }
                    if (t.seriesId != null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recurring", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }

                if (t.description.isNotBlank()) {
                    Text(
                        t.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                // Timer information
                val elapsed = t.elapsedMs(now)
                val lines = buildList {
                    if (elapsed > 0 || t.status == Status.RUNNING) add("Spent ${formatDuration(elapsed)}")
                    if (t.isOpen()) {
                        val start = t.startMillis()
                        val end = t.endMillis()
                        when {
                            now < start && start - now < 24 * 3_600_000L -> add("Starts in ${formatDuration(start - now)}")
                            now in start..end -> add("Left ${formatDuration(end - now)}")
                            now > end -> add("Overdue by ${formatDuration(now - end)}")
                        }
                    }
                }
                if (lines.isNotEmpty()) {
                    Text(
                        lines.joinToString("   ·   "),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t.isOverdue(now)) AppColors.Overdue else accent,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Row(
                    Modifier.padding(top = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pad = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    val h = Modifier.height(36.dp)
                    when (t.status) {
                        Status.TODO, Status.PAUSED -> {
                            FilledTonalButton(onClick = { actions.onStart(t) }, modifier = h, contentPadding = pad) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Text(if (t.status == Status.PAUSED) "Resume" else "Start")
                            }
                            if (t.status == Status.PAUSED) {
                                OutlinedButton(onClick = { actions.onStop(t) }, modifier = h, contentPadding = pad) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                    Text("Stop")
                                }
                            }
                            Button(onClick = { actions.onDone(t) }, modifier = h, contentPadding = pad) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Text("Done")
                            }
                        }
                        Status.RUNNING -> {
                            FilledTonalButton(onClick = { actions.onPause(t) }, modifier = h, contentPadding = pad) {
                                Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                                Text("Pause")
                            }
                            OutlinedButton(onClick = { actions.onStop(t) }, modifier = h, contentPadding = pad) {
                                Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                Text("Stop")
                            }
                            Button(onClick = { actions.onDone(t) }, modifier = h, contentPadding = pad) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Text("Done")
                            }
                        }
                        else -> TextButton(onClick = { actions.onReopen(t) }, modifier = h, contentPadding = pad) {
                            Text("Undo")
                        }
                    }
                }
            }
        }
    }

    if (confirm != 0) {
        AlertDialog(
            onDismissRequest = { confirm = 0 },
            title = { Text(if (confirm == 2) "Delete entire series?" else "Delete task?") },
            text = {
                Text(
                    if (confirm == 2) "All upcoming, not-started tasks of this recurring schedule will be removed. Completed history is kept."
                    else "\"${t.title}\" will be permanently deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirm == 2) t.seriesId?.let { actions.onDeleteSeries(it) } else actions.onDelete(t)
                    confirm = 0
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = 0 }) { Text("Cancel") } }
        )
    }
}
