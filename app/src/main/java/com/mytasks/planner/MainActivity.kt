@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytasks.planner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var resumeTick by mutableIntStateOf(0)
    private var askedNotificationPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val setupActions = SetupActions(
            onNotifications = ::askNotifications,
            onExactAlarms = ::askExactAlarms,
            onBattery = ::askBattery,
            onChannelSettings = ::openChannelSettings
        )
        setContent {
            TaskTheme {
                val tick = resumeTick
                val setup = remember(tick) { readSetup() }
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm, setup, setupActions)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        vm.refresh() // re-arms alarms (e.g. after the exact-alarm permission was just granted)
    }

    private fun readSetup(): SetupState {
        val notif = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val exact = AlarmScheduler.canExact(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        return SetupState(notif, exact, battery)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotificationPermission) {
            askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun askExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openChannelSettings() {
        Notifier.ensureChannel(this)
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_ID)
        )
    }
}

@Composable
fun AppRoot(vm: MainViewModel, setup: SetupState, setupActions: SetupActions) {
    val todayTasks by vm.todayTasks.collectAsStateWithLifecycle()
    val backlog by vm.backlog.collectAsStateWithLifecycle()
    val allTasks by vm.allTasks.collectAsStateWithLifecycle()
    val seriesList by vm.seriesList.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorKind by rememberSaveable { mutableStateOf(EDITOR_NEW) }
    var editorId by rememberSaveable { mutableStateOf(0L) }

    // One shared clock: drives the live countdowns and timers once per second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val actions = TaskActions(
        onStart = { vm.start(it) },
        onPause = { vm.pause(it) },
        onStop = { vm.stop(it) },
        onDone = { vm.done(it) },
        onReopen = { vm.reopen(it) },
        onSkip = { vm.skip(it) },
        onDelete = { vm.delete(it) },
        onDeleteSeries = { vm.deleteSeries(it) },
        onEdit = { editorKind = EDITOR_TASK; editorId = it.id; editorOpen = true }
    )

    if (editorOpen) {
        BackHandler { editorOpen = false }
        EditorScreen(kind = editorKind, id = editorId, repo = vm.repo, onClose = { editorOpen = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) }, label = { Text("All Tasks") }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editorKind = EDITOR_NEW; editorId = 0L; editorOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add task") }
            )
        }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(
                tasks = todayTasks, backlog = backlog, now = now,
                setup = setup, setupActions = setupActions,
                actions = actions, padding = padding
            )
            else -> AllTasksScreen(
                all = allTasks, series = seriesList, now = now, actions = actions,
                onEditSeries = { editorKind = EDITOR_SERIES; editorId = it; editorOpen = true },
                onDeleteSeries = { vm.deleteSeries(it) },
                padding = padding
            )
        }
    }
}
