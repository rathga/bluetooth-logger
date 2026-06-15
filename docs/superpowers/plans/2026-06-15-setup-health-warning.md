# Setup-health Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect the two conditions that silently stop Bluetooth capture (battery-optimisation exemption missing, `BLUETOOTH_CONNECT` not granted) and surface them via an in-app banner plus a proactive notification from the hourly sync worker.

**Architecture:** A pure `SetupStatus` value (the only unit-tested piece) decides which issues exist from two booleans. A thin infrastructure reader builds it from the Android framework. The UI renders a banner with one-tap fix buttons; the `DriveSyncWorker` posts/cancels a notification each run.

**Tech Stack:** Kotlin 2.0, Jetpack Compose (Material3), WorkManager, JUnit 4 (JVM unit tests). Build with Gradle.

**Spec:** `docs/superpowers/specs/2026-06-15-setup-health-warning-design.md`

**Branch:** `setup-health-warning` (already checked out; spec already committed).

---

## File structure

- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupStatus.kt` — **new, pure.** Enum + data class; the issues decision. Unit-tested.
- `app/src/test/kotlin/com/nestegg/btlogger/setup/SetupStatusTest.kt` — **new.** Truth-table tests.
- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt` — **new, infra.** Reads `PowerManager` + permission into a `SetupStatus`.
- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupNotifier.kt` — **new, infra.** Notification channel + post/cancel.
- `app/src/main/AndroidManifest.xml` — **modify.** Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- `app/src/main/kotlin/com/nestegg/btlogger/BtLoggerApp.kt` — **modify.** Create the notification channel on startup.
- `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt` — **modify.** Update notification each run.
- `app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt` — **modify.** Banner + fix actions.

Project rule reminder: only the pure `SetupStatus` gets unit tests. Everything else is plumbing — verified by a clean build and on-device behaviour.

---

### Task 1: Pure `SetupStatus` decision (TDD)

**Files:**
- Create: `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupStatus.kt`
- Test: `app/src/test/kotlin/com/nestegg/btlogger/setup/SetupStatusTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/nestegg/btlogger/setup/SetupStatusTest.kt`:

```kotlin
package com.nestegg.btlogger.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {

    @Test fun `no issues when granted and exempt`() {
        val status = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertTrue(status.isHealthy)
        assertEquals(emptyList<SetupIssue>(), status.issues)
    }

    @Test fun `missing bluetooth connect is reported`() {
        val status = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertFalse(status.isHealthy)
        assertEquals(listOf(SetupIssue.MISSING_BLUETOOTH_CONNECT), status.issues)
    }

    @Test fun `missing battery exemption is reported`() {
        val status = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertFalse(status.isHealthy)
        assertEquals(listOf(SetupIssue.NOT_BATTERY_EXEMPT), status.issues)
    }

    @Test fun `both issues reported in order`() {
        val status = SetupStatus(bluetoothConnectGranted = false, batteryExempt = false)
        assertFalse(status.isHealthy)
        assertEquals(
            listOf(SetupIssue.MISSING_BLUETOOTH_CONNECT, SetupIssue.NOT_BATTERY_EXEMPT),
            status.issues,
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.setup.SetupStatusTest"`
Expected: FAIL — compilation error, `SetupStatus` / `SetupIssue` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupStatus.kt`:

```kotlin
package com.nestegg.btlogger.setup

enum class SetupIssue {
    MISSING_BLUETOOTH_CONNECT,
    NOT_BATTERY_EXEMPT,
}

data class SetupStatus(
    val bluetoothConnectGranted: Boolean,
    val batteryExempt: Boolean,
) {
    val issues: List<SetupIssue> = buildList {
        if (!bluetoothConnectGranted) add(SetupIssue.MISSING_BLUETOOTH_CONNECT)
        if (!batteryExempt) add(SetupIssue.NOT_BATTERY_EXEMPT)
    }

    val isHealthy: Boolean get() = issues.isEmpty()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.setup.SetupStatusTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/setup/SetupStatus.kt \
        app/src/test/kotlin/com/nestegg/btlogger/setup/SetupStatusTest.kt
git commit -m "Add pure SetupStatus issue decision with tests"
```

---

### Task 2: Live-state reader + manifest permission

**Files:**
- Create: `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add the manifest permission**

In `app/src/main/AndroidManifest.xml`, add this line directly after the existing `RECEIVE_BOOT_COMPLETED` permission (line 8):

```xml
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

- [ ] **Step 2: Write the reader**

Create `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt`:

```kotlin
package com.nestegg.btlogger.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

fun readSetupStatus(context: Context): SetupStatus {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    val bluetoothConnectGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) == PackageManager.PERMISSION_GRANTED
    return SetupStatus(
        bluetoothConnectGranted = bluetoothConnectGranted,
        batteryExempt = batteryExempt,
    )
}
```

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt \
        app/src/main/AndroidManifest.xml
git commit -m "Add setup-status reader and battery-exemption permission"
```

---

### Task 3: Notification channel + notifier

**Files:**
- Create: `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupNotifier.kt`
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/BtLoggerApp.kt`

- [ ] **Step 1: Write the notifier**

Create `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupNotifier.kt`:

```kotlin
package com.nestegg.btlogger.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nestegg.btlogger.R
import com.nestegg.btlogger.ui.MainActivity

object SetupNotifier {

    private const val CHANNEL_ID = "setup-health"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Setup warnings",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns when the logger cannot capture Bluetooth events"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun update(context: Context, status: SetupStatus) {
        val manager = NotificationManagerCompat.from(context)
        if (status.isHealthy) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openApp,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bluetooth Logger may be missing events")
            .setContentText("Tap to fix setup so connections are captured.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
```

Note: `setSmallIcon(R.mipmap.ic_launcher)` reuses the existing launcher icon — functional for a personal app; a dedicated monochrome status icon can be added later but is not required. The `areNotificationsEnabled()` guard avoids a `SecurityException` when `POST_NOTIFICATIONS` has not been granted (the banner is the fallback in that case).

- [ ] **Step 2: Create the channel on startup**

In `app/src/main/kotlin/com/nestegg/btlogger/BtLoggerApp.kt`, add the import and the channel-creation call in `onCreate`. The file becomes:

```kotlin
package com.nestegg.btlogger

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.sync.DriveSyncWorker
import java.util.concurrent.TimeUnit

class BtLoggerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SetupNotifier.createChannel(this)
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DriveSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
```

- [ ] **Step 3: Verify the build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/setup/SetupNotifier.kt \
        app/src/main/kotlin/com/nestegg/btlogger/BtLoggerApp.kt
git commit -m "Add setup-health notification channel and notifier"
```

---

### Task 4: Worker posts/cancels the notification each run

**Files:**
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt`

- [ ] **Step 1: Call the notifier at the top of `doWork`**

In `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt`, add these imports alongside the existing ones:

```kotlin
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.setup.readSetupStatus
```

Then add this as the first statement inside `doWork()`, before the `val state = ...` line:

```kotlin
        SetupNotifier.update(applicationContext, readSetupStatus(applicationContext))
```

For clarity, the top of `doWork()` should read:

```kotlin
    override suspend fun doWork(): Result {
        SetupNotifier.update(applicationContext, readSetupStatus(applicationContext))

        val state = SyncState.from(applicationContext)
        val accountName = state.accountName ?: run {
            Log.i(TAG, "No Google account configured; skipping sync")
            return Result.success()
        }
        // ... unchanged ...
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt
git commit -m "Update setup-health notification on each sync run"
```

---

### Task 5: In-app banner + one-tap fix actions

**Files:**
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt`

- [ ] **Step 1: Add fix-action methods and wire the deep-link on permanent denial**

In `MainActivity`, add these imports:

```kotlin
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nestegg.btlogger.setup.SetupIssue
import com.nestegg.btlogger.setup.readSetupStatus
```

Replace the existing `requestPermissions` launcher (lines 53-58) with this version, which refreshes the UI and deep-links to settings when a permission is permanently denied:

```kotlin
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permissions denied: $denied")
            val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
            if (permanentlyDenied) openAppSettings()
        }
        refreshTick.intValue++
    }
```

Add these two methods to `MainActivity` (next to `requestNeededPermissions`):

```kotlin
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
```

- [ ] **Step 2: Pass the new callbacks into `StatusScreen`**

In `onCreate`, update the `StatusScreen(...)` call to pass two new callbacks:

```kotlin
                    StatusScreen(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        refreshTick = refreshTick.intValue,
                        onGrantPermissions = ::requestNeededPermissions,
                        onFixBattery = ::requestBatteryExemption,
                        onSignIn = ::launchSignIn,
                        onSignOut = ::signOut,
                        onSyncNow = ::triggerSyncNow,
                    )
```

- [ ] **Step 3: Add the banner to `StatusScreen` and its signature**

Update the `StatusScreen` signature to add `onFixBattery`:

```kotlin
@Composable
private fun StatusScreen(
    modifier: Modifier,
    refreshTick: Int,
    onGrantPermissions: () -> Unit,
    onFixBattery: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
```

Inside `StatusScreen`, after the existing `val recent = ...` line, read the setup status:

```kotlin
    val setup = remember(refreshTick) { readSetupStatus(context) }
```

Then, as the first child inside the `Column` (before the `Text("Bluetooth Logger", ...)` line), render the banner:

```kotlin
        SetupWarningBanner(
            issues = setup.issues,
            onFixBattery = onFixBattery,
            onFixPermission = onGrantPermissions,
        )
```

- [ ] **Step 4: Add the `SetupWarningBanner` composable**

Add this composable to the file (e.g. directly after `StatusScreen`):

```kotlin
@Composable
private fun SetupWarningBanner(
    issues: List<SetupIssue>,
    onFixBattery: () -> Unit,
    onFixPermission: () -> Unit,
) {
    if (issues.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Setup needs attention", style = MaterialTheme.typography.titleMedium)
            issues.forEach { issue ->
                when (issue) {
                    SetupIssue.NOT_BATTERY_EXEMPT -> {
                        Text("Battery optimisation is on — Android may stop the app capturing connections.")
                        Button(onClick = onFixBattery, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow background activity")
                        }
                    }
                    SetupIssue.MISSING_BLUETOOTH_CONNECT -> {
                        Text("Bluetooth permission is missing — connections won't be recorded.")
                        Button(onClick = onFixPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant Bluetooth permission")
                        }
                    }
                }
            }
        }
    }
}
```

Add these imports for the banner:

```kotlin
import androidx.compose.material3.Surface
```

(`Button`, `Column`, `Text`, `MaterialTheme`, `Modifier`, `Arrangement`, `fillMaxWidth`, `padding`, `dp` are already imported.)

- [ ] **Step 5: Verify the build compiles and unit tests still pass**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt
git commit -m "Add in-app setup-health banner with one-tap fix actions"
```

---

### Task 6: On-device verification

**No code.** Confirm the feature behaves on the real phone (`R3CNB0184LM`, SM-G981B).

- [ ] **Step 1: Install the debug build**

Run: `./gradlew :app:installDebug`
Expected: installs on the connected device.

- [ ] **Step 2: Force the unhealthy state and verify the banner**

Remove the battery exemption, then open the app:

```bash
export PATH="/c/Users/rathg/AppData/Local/Android/Sdk/platform-tools:$PATH"
adb shell dumpsys deviceidle whitelist -com.nestegg.btlogger
adb shell monkey -p com.nestegg.btlogger -c android.intent.category.LAUNCHER 1
```

Expected: the red "Setup needs attention" banner shows the battery-optimisation warning with an "Allow background activity" button.

- [ ] **Step 3: Verify the one-tap fix**

Tap "Allow background activity". Expected: the system "Allow to run in background?" dialog appears. Tap **Allow**. Expected: returning to the app, the banner has cleared (the `onResume` refresh re-reads the now-healthy status).

- [ ] **Step 4: Verify the exemption stuck**

Run: `adb shell dumpsys deviceidle whitelist | grep -i btlogger`
Expected: `com.nestegg.btlogger` is listed again.

- [ ] **Step 5: (Optional) Verify the notification**

Trigger a sync while unhealthy to confirm the worker posts the notification:

```bash
adb shell dumpsys deviceidle whitelist -com.nestegg.btlogger
# In the app, tap "Sync now", then check the notification shade for
# "Bluetooth Logger may be missing events". Re-add the exemption afterwards:
adb shell dumpsys deviceidle whitelist +com.nestegg.btlogger
```

---

## Notes for the implementer

- **Gradle wrapper jar is not in the repo.** See `README.md` first-time setup and the project memory note "Running Gradle builds without the wrapper" — invoke Gradle from the Android-Studio-populated cache with the bundled JBR if `./gradlew` is unavailable.
- **Do not widen visibility for tests.** Only `SetupStatus`/`SetupIssue` need to be public (consumed across packages); they already are. Keep `SetupWarningBanner` private to `MainActivity.kt`.
- **After Task 5, leave the device battery-exempt** (re-add via `adb shell dumpsys deviceidle whitelist +com.nestegg.btlogger`) so the real logger keeps working, and remind Richard to set Samsung's "Never sleeping apps" manually — that list is not covered by this feature.
</content>
