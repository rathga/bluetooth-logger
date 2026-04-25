# Bluetooth Logger — project memory

Android app: ground-truth log of Bluetooth connect/disconnect events, synced to Google Drive, used by a separate desktop reconciler to fix MileIQ's drive-classification mistakes. Companion to the Nestegg Rentals workflow over at `D:/Nestegg Google Drive/` — Richard runs the reconciler against MileIQ exports for HMRC mileage claims.

## Stack

- Kotlin 2.0, Android Gradle Plugin 8.7
- minSdk 31 (Android 12 — required for `BLUETOOTH_CONNECT`)
- targetSdk 35
- Jetpack Compose for the (single-screen) UI
- WorkManager for periodic Drive sync
- Google Sign-In + Drive REST API for upload auth
- Version catalog at `gradle/libs.versions.toml`

## Architecture

Receiver-only — no foreground service. Events arrive via push from Android.

```
ACL_CONNECTED/DISCONNECTED
  → BluetoothEventReceiver (manifest-registered)
  → EventStore (JSONL in app-private storage)

WorkManager (hourly, NetworkType.CONNECTED)
  → DriveSyncWorker
  → DriveClient → bluetooth-log-YYYY-MM.csv in Drive
```

Idempotency via last-synced-byte-offset in `SharedPreferences`.

## Source layout

`app/src/main/kotlin/com/nestegg/btlogger/`
- `BtLoggerApp.kt` — `Application`. Schedules the periodic worker.
- `receiver/BluetoothEventReceiver.kt` — push-driven event capture.
- `storage/BtEvent.kt`, `storage/EventStore.kt` — JSONL append + read.
- `sync/DriveSyncWorker.kt`, `sync/DriveClient.kt` — periodic upload.
- `ui/MainActivity.kt` — single screen: sign-in + permission grant + status.

## Implementation status

Scaffold only — the wiring is in place but most bodies are TODO. Working order:

1. **`EventStore`** — append + tail-read against `events-YYYY-MM.jsonl` in `filesDir`. Atomic line writes.
2. **End-to-end ACL test** — pair a device, toggle, check the JSONL grows. Use `adb logcat -s BtEventReceiver` to verify the receiver is firing.
3. **Google Sign-In + Drive scope** — `MainActivity` button. Request `https://www.googleapis.com/auth/drive.file` only (we own the files we create).
4. **`DriveClient.appendCsvRows(yearMonth, rows)`** — search for `bluetooth-log-YYYY-MM.csv` in a known folder, create or append. Drive's REST API doesn't support true append, so: download → concat → re-upload OR use `files.update` with media body. Pick one and stick.
5. **`DriveSyncWorker`** — read unsynced events from offset, upload, persist new offset. `Result.retry()` on transient failure.
6. **Schedule the worker** in `BtLoggerApp.onCreate()` — `PeriodicWorkRequest`, 1 hour, `NetworkType.CONNECTED`.
7. **Manual sync button** in `MainActivity` for debugging.

## Known gotchas worth remembering

- **Implicit broadcast restrictions (Android 8+)** don't apply to ACL events for *paired* devices — they remain exempt. Manifest registration is the right call.
- **`BluetoothDevice.getName()` requires `BLUETOOTH_CONNECT`.** Without it, `name` returns null. Receiver wraps in `runCatching` so we still log MAC.
- **Reboot:** receiver re-registers automatically from manifest. Connections that happened during the reboot window are lost — accept this.
- **Bluetooth toggle off/on** generates synthetic disconnect/connect — reconciler should treat short gaps (<60s) as continuous.
- **Multiple simultaneous devices** (headphones + car): log all; reconciler picks the one matching a known-vehicle MAC.
- **Drive REST has no real append.** Either re-upload the whole file, or use Google Sheets API instead (which supports `values.append`). For monthly CSVs the re-upload cost stays small.

## Conventions

- Source under `app/src/main/kotlin/` (not `java/`) — explicit `srcDirs` in `app/build.gradle.kts`.
- Package: `com.nestegg.btlogger`.
- App namespace: `com.nestegg.btlogger`. Application id: same.
- Don't add a foreground service — defeats the "lightweight, push-only" design unless we hit a real reliability issue.

## Running locally

```bash
./gradlew :app:installDebug   # build + push to connected device
adb logcat -s BtEventReceiver # watch events as they fire
```

Wrapper jar is not in the repo — see README for first-time setup.

## Out-of-scope (defer)

- Webhook ping for live reconciliation
- "Now connected to X" notification
- Wider "where was the phone" audit trail
- iOS

## Where the reconciler lives

Not in this repo. Separate desktop/cloud tool that pulls the Drive CSV + MileIQ export and reclassifies drives. Likely a Python or TypeScript script; not yet written. When it exists, link from here.
