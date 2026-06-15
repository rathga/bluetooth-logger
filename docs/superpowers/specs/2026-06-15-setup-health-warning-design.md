# Setup-health warning — design

**Date:** 2026-06-15
**Status:** Approved (design); implementation pending

## Problem

The logger on the second phone (`sm-g981b`) went dark after 2 June 2026 and nobody
noticed for ~two weeks. Root cause (confirmed on-device, not guessed): the app was
**not exempt from battery optimisation**, so once idle Samsung dropped it into a deep-sleep
state where Android stopped delivering `ACL_CONNECTED`/`DISCONNECTED` broadcasts to the
manifest-registered receiver. The Bluetooth stack still connected to the car (stack-level
connections at 06:06 and 07:06 on 15 Jun were recorded by `bluetooth_manager`), but the
broadcast never reached the app. The hourly sync worker kept running occasionally (last
sync 13 Jun), so there was no visible symptom — the CSV simply stopped growing.

A second, equally silent capture-killer is documented in CLAUDE.md: without
`BLUETOOTH_CONNECT`, "the receiver never fires; logcat is silent."

Both failures stop **capture** with no visible symptom. The app needs to detect them and
alert the user — both in-app and proactively, because the failure mode is precisely "you
never open the app for weeks."

## Scope

Detect and warn about the two conditions that silently stop capture:

1. **Battery-optimisation exemption** missing (`PowerManager.isIgnoringBatteryOptimizations`).
2. **`BLUETOOTH_CONNECT`** not granted.

Out of scope: Drive sign-in status (stops *upload*, already visible via the stale
"Last sync" line and the existing sign-in button).

## Honest limitations

- `isIgnoringBatteryOptimizations()` reads the **Doze whitelist** (== Samsung "Unrestricted").
  It does **not** read Samsung's separate "Sleeping apps / Deep sleeping apps" list — there is
  no public API for that. Marking the app exempt is what normally keeps Samsung from
  deep-sleeping it, so this is the best programmatic signal available, but it is not a literal
  read of the sleeping-apps list. The README's manual steps remain the authoritative cure.
- The proactive notification is a **backstop, not a guarantee**: if the app is already fully
  deep-asleep, the worker may not run. But in practice the worker gets a few more runs as the
  app sinks (e.g. the 13 Jun sync), which is enough to fire the warning before it goes fully
  dark. The in-app banner covers whatever the notification misses.

## Design

### 1. Pure decision — `setup/SetupStatus.kt`

The only business-logic piece, and the only unit-tested one (per project rules):

```kotlin
enum class SetupIssue { MISSING_BLUETOOTH_CONNECT, NOT_BATTERY_EXEMPT }

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

No Android dependencies → JVM unit tests cover the truth table.

### 2. Reading live state (infrastructure) — `setup/SetupChecks.kt`

A small shared function that reads the two facts from the framework and builds a
`SetupStatus`. Shared because both the UI and the worker consume it.

```kotlin
fun readSetupStatus(context: Context): SetupStatus
// PowerManager.isIgnoringBatteryOptimizations(context.packageName)
// ContextCompat.checkSelfPermission(context, BLUETOOTH_CONNECT) == GRANTED
```

Not unit-tested (plumbing).

### 3. In-app banner — `ui/MainActivity.kt` (`StatusScreen`)

When `status.issues` is non-empty, render a prominent error-coloured card at the top of the
screen, above the existing status lines. Each issue gets a one-line description and a **Fix**
button. The existing `refreshTick` / `onResume` path already re-reads state, so the banner
clears itself as soon as the user fixes the condition.

- **Fix battery exemption** → launch `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with a
  `package:<pkg>` data URI. This pops the system "Allow to run in background?" dialog with a
  one-tap **Allow** — the straight-to-the-setting button the user asked for.
- **Fix permission** → the existing `requestNeededPermissions()` flow; if `BLUETOOTH_CONNECT`
  has been permanently denied (`shouldShowRequestPermissionRationale` is false after a denial),
  deep-link to the app's details settings page (`ACTION_APPLICATION_DETAILS_SETTINGS`) instead,
  since the runtime prompt will no longer appear.

### 4. Proactive notification — `DriveSyncWorker` + `setup/SetupNotifier.kt`

At the top of `doWork()`, build the same `SetupStatus`:

- If `!isHealthy` → post a notification: "Bluetooth Logger may be missing events — tap to fix",
  with a `PendingIntent` that opens `MainActivity`.
- If healthy → cancel that notification (so it clears once fixed, even if the user fixed it
  outside the app).

A dedicated notification channel is created in `BtLoggerApp.onCreate()`. `POST_NOTIFICATIONS`
is already requested by the app, so no new permission is needed there.

### 5. Manifest

Add:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

Acceptable for a personal sideloaded app (Play Store policy restrictions on this permission
do not apply here).

## Rejected alternatives

- **Battery fix opens the optimisation *list*** (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`)
  rather than the request dialog — more taps, worse UX, no upside for a sideloaded personal app.
- **In-app banner only** / **notification only** — rejected in favour of both: the banner is
  invisible during the exact failure (never opening the app), and the notification alone leaves
  nothing visible in-app when you do open it.
- **Including Drive sign-in in the health check** — sign-in stops upload (visible via stale
  "Last sync"), not capture; already has its own UI. Out of scope.

## Testing

- **Unit:** `SetupStatusTest` — the issues truth table (both granted, each missing, both missing).
- **Not tested (plumbing, per project rules):** `readSetupStatus` reads, intent launching,
  notification posting/cancellation, manifest permission, Compose banner.

## Files touched

- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupStatus.kt` (new, pure)
- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt` (new, infra)
- `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupNotifier.kt` (new, infra)
- `app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt` (banner + fix actions)
- `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt` (health check + notify)
- `app/src/main/kotlin/com/nestegg/btlogger/BtLoggerApp.kt` (notification channel)
- `app/src/main/AndroidManifest.xml` (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
- `app/src/test/kotlin/com/nestegg/btlogger/setup/SetupStatusTest.kt` (new)
</content>
</invoke>
