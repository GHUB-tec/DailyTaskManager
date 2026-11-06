@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytasks.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SetupState(val notifications: Boolean, val exactAlarms: Boolean, val battery: Boolean) {
    val allGood: Boolean get() = notifications && exactAlarms && battery
}

class SetupActions(
    val onNotifications: () -> Unit,
    val onExactAlarms: () -> Unit,
    val onBattery: () -> Unit,
    val onChannelSettings: () -> Unit
)

@Composable
fun HomeScreen(
    tasks: List<TaskEntity>,
    backlog: List<TaskEntity>,
    now: Long,
    setup: SetupState,
    setupActions: SetupActions,
    actions: TaskActions,
    padding: PaddingValues
) {
    val active = tasks.filter { it.status != Status.SKIPPED }
    val total = active.size
    val done = active.count { it.status == Status.DONE }
    val overdue = active.count { it.isOverdue(now) } + backlog.size
    val pending = active.count { it.isOpen() && !it.isOverdue(now) }
    val pct = if (total == 0) 0 else done * 100 / total
    val next = active.filter { it.isOpen() && it.startMillis() > now }.minByOrNull { it.startMillis() }
    val running = active.filter { it.status == Status.RUNNING }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HeaderCard(now, total, done, pending, overdue) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Total", total.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatTile("Done", done.toString(), AppColors.Done, Modifier.weight(1f))
                StatTile("Pending", pending.toString(), AppColors.Paused, Modifier.weight(1f))
                StatTile("Overdue", overdue.toString(), AppColors.Overdue, Modifier.weight(1f))
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Daily progress", style = MaterialTheme.typography.titleMedium)
                        Text("$pct%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }
        item { NextUpCard(next, running, now) }
        if (!setup.allGood) item { SetupCard(setup, setupActions) }

        if (backlog.isNotEmpty()) {
            item { SectionTitle("Overdue from earlier days") }
            items(backlog, key = { "b${it.id}" }) { TaskCard(it, now, actions, showDate = true) }
        }

        item { SectionTitle("Today's schedule") }
        if (tasks.isEmpty()) {
            item {
                Text(
                    "Nothing scheduled today. Tap “Add task” to plan your day.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        items(tasks, key = { it.id }) { TaskCard(it, now, actions) }
    }
}

@Composable
private fun HeaderCard(now: Long, total: Int, done: Int, pending: Int, overdue: Int) {
    val dt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val overview = when {
        total == 0 -> "Your day is open. Plan something great."
        done == total -> "All $total tasks done. Well played!"
        else -> "$total tasks today · $done done · ${pending + overdue} to go"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
                RoundedCornerShape(28.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Text(greeting(dt.hour), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
            Text(
                dt.format(DateTimeFormatter.ofPattern("EEEE")),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                dt.format(DateTimeFormatter.ofPattern("d MMMM yyyy")),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                overview,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f))
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NextUpCard(next: TaskEntity?, running: List<TaskEntity>, now: Long) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            when {
                next != null -> {
                    Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(next.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${formatMinute(next.startMinute)} – ${formatMinute(next.endMinute)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Starts in ${formatDuration(next.startMillis() - now)}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                running.isNotEmpty() -> {
                    val r = running.first()
                    Text("IN PROGRESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(r.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Spent ${formatDuration(r.elapsedMs(now))}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                else -> {
                    Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "No upcoming tasks today",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState, a: SetupActions) {
    var hidden by rememberSaveable { mutableStateOf(false) }
    if (hidden) return
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Finish setup so alarms ring reliably",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            SetupRow("Notifications", setup.notifications, a.onNotifications)
            SetupRow("Exact alarms", setup.exactAlarms, a.onExactAlarms)
            SetupRow("Unrestricted battery", setup.battery, a.onBattery)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Do Not Disturb: allow “Task alarms” in the channel settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = a.onChannelSettings) { Text("Open") }
            }
            TextButton(onClick = { hidden = true }) { Text("Hide for now") }
        }
    }
}

@Composable
private fun SetupRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) AppColors.Done else AppColors.Overdue,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        if (!ok) Button(onClick = onFix) { Text("Allow") }
    }
}
