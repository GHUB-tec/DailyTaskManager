# Daily Tasks – Version 1

Offline Android task manager: Kotlin, Jetpack Compose, Material 3, Room, AlarmManager, WorkManager.
No account, no ads, no paid services.

## What Version 1 contains
- Home dashboard: date, greeting, totals, progress %, next task with live countdown, quick "Add task"
- Create / edit / delete tasks (name, description, notes, category, priority, date, start/end time)
- Recurring tasks: daily, selected weekdays, weekly, every N days, start and end dates
- Edit "only this day" or "entire series"; "Skip this day" keeps the schedule
- No duplicates: a unique database index on (series, date)
- Timer: Start / Pause / Resume / Stop / Done, time spent, time left, overdue (all based on clock timestamps, so it survives app close and reboot)
- Alarms: at start, N minutes before (5/10/15/30/60, several at once), at deadline, overdue reminder 15 min later
- Notification buttons: Done, Snooze 10 min, Dismiss
- Alarms restored after reboot, app update, time or time-zone change; re-armed when you edit or delete tasks
- Setup card for notification permission, exact-alarm permission, battery optimisation, Do Not Disturb
- Light/dark theme follows the phone

Coming in later versions: calendar and planner, reports and charts, settings screen (custom sound, vibration, default duration), backup/restore/export.

## How to get the APK using only your phone (free)

You cannot run Android Studio on a phone, so GitHub's free build servers compile the app for you.

### 1. One-time accounts and apps
1. Install **Termux** from **F-Droid** (f-droid.org). Do not use the Play Store version, it is outdated.
2. Create a free account at github.com.
3. In GitHub: **+ → New repository**, name it `DailyTaskManager`, keep it empty (no README), Create.
4. Create an access token: GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)** → Generate new token. Tick **repo** and **workflow**. Copy the token and keep it private.

### 2. Put the project on GitHub
Save `DailyTaskManager.zip` to your phone's **Downloads** folder, then in Termux:

```
pkg update -y && pkg install -y git unzip
termux-setup-storage
cd ~
unzip ~/storage/downloads/DailyTaskManager.zip
cd DailyTaskManager
git init -b main
git add .
git -c user.name="me" -c user.email="me@example.com" commit -m "Version 1"
git remote add origin https://github.com/YOUR_USERNAME/DailyTaskManager.git
git push -u origin main
```
When asked for a password, paste the token (not your GitHub password).

### 3. Build and download the APK
1. Open your repository in the phone browser → **Actions** tab. The "Build APK" run starts by itself (about 5–8 minutes). Green tick = success.
2. Open the run → scroll to **Artifacts** → tap **DailyTaskManager-APK** (downloads a zip).
3. Unzip it (Files app), tap `app-debug.apk`, allow "Install unknown apps" for your browser/Files app, install.

If Play Protect warns about an unknown app, that is normal for a self-built app: choose "Install anyway".

### If the build fails (red cross)
Open the run → the failed step → copy the red error lines and send them to me. I will fix the code and give you the corrected file(s). This is the "fix compile errors before the next stage" step. The code was written carefully but could not be compiled where it was generated.

To update the app after a fix: replace the file in Termux (or use the GitHub web editor: open file → pencil icon → paste → Commit), and Actions rebuilds automatically.

## Test checklist for Version 1
1. Open the app, allow notifications; tap **Allow** on each Setup card row (exact alarms, battery).
2. Add a task starting in 2–3 minutes with a 1-minute reminder: you should get a reminder, then a start alarm, then a deadline alarm.
3. Tap **Snooze** on a notification: it returns after 10 minutes. Tap **Done**: task turns green.
4. Start a task timer, pause, resume, force-close the app, reopen: the time spent keeps counting correctly. Restart the phone: running timer and upcoming alarms still work.
5. Create a task that repeats daily; open **All Tasks → Recurring**. Skip today's instance: it stays skipped and does not come back.
6. Edit a recurring instance: choose "Only this day", then edit another and choose "Entire series".
7. Close the app completely and wait for an alarm: it must still ring.

## Project map
```
settings.gradle.kts, build.gradle.kts, gradle.properties   build setup
.github/workflows/build.yml                                 free cloud APK build
app/build.gradle.kts                                        dependencies
app/src/main/AndroidManifest.xml                            permissions, receivers
app/src/main/java/com/mytasks/planner/
  Data.kt          Room entities, DAO, database, recurrence rule
  Repository.kt    all task logic, timer, recurring generation, DailyWorker
  Alarms.kt        AlarmManager scheduling, notifications, boot/alarm/action receivers
  TaskApp.kt       app start: channel + background worker
  MainViewModel.kt UI state
  MainActivity.kt  navigation, permissions
  Home.kt, AllTasks.kt, Editor.kt, Components.kt, Theme.kt, Util.kt   screens
app/src/main/res/  icons, themes, strings
```

## Data safety
All data lives in a local Room (SQLite) database. The database never uses destructive migration, so a future version will add proper migrations instead of wiping data. Backup/restore and export arrive in a later version.
