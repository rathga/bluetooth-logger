# Bluetooth Logger — project memory

Android app: ground-truth log of Bluetooth connect/disconnect events, synced to Google Drive, used by a separate desktop reconciler to fix MileIQ's drive-classification mistakes. Companion to the Nestegg Rentals workflow over at `D:/Nestegg Google Drive/` — Richard runs the reconciler against MileIQ exports for HMRC mileage claims.

## Stack

- Kotlin 2.0, Android Gradle Plugin 8.7
- minSdk 31 (Android 12 — required for `BLUETOOTH_CONNECT`)
- targetSdk 35
- Jetpack Compose for the (single-screen) UI
- WorkManager for periodic Drive sync
- Google Sign-In + Drive REST API for upload auth
- JUnit 4 for JVM unit tests; no Robolectric / instrumentation tests yet
- Version catalog at `gradle/libs.versions.toml`

## Architecture

Receiver-only — no foreground service. Events arrive via push from Android.

```
ACL_CONNECTED/DISCONNECTED
  → BluetoothEventReceiver (manifest-registered, goAsync + single-thread executor)
  → EventStore (JSONL in app-private storage)

WorkManager (hourly, NetworkType.CONNECTED)
  → DriveSyncWorker
  → DriveClient → bluetooth-log-<deviceTag>-YYYY-MM.csv in Drive
```

Idempotency via per-month last-synced-byte-offset in `SharedPreferences`. Per-device CSV filenames so two phones on the same Google account never race on a single shared file.

## Source layout

`app/src/main/kotlin/com/nestegg/btlogger/`
- `BtLoggerApp.kt` — `Application`. Schedules the periodic worker (KEEP policy).
- `receiver/BluetoothEventReceiver.kt` — push-driven event capture; goAsync to a single-thread `BtLogger-disk` executor so the broadcast thread never blocks on disk.
- `storage/BtEvent.kt` — data class + hand-rolled JSON encode/decode.
- `storage/EventStore.kt` — JSONL append + tail-read with per-month byte offset; constructor accepts a `File` baseDir for JVM tests.
- `sync/DriveClient.kt` — `appendCsvRows(yearMonth, deviceTag, header, rows)`; Drive `files.update` re-upload.
- `sync/DriveSyncWorker.kt` — iterates months, uploads new chunks, persists offset; distinct handling for `UserRecoverableAuthIOException`.
- `sync/SyncState.kt` — `SharedPreferences` wrapper (account name, per-month offsets, last-sync timestamp).
- `sync/CsvFormat.kt` — ISO-8601 UTC + RFC 4180 row formatter.
- `sync/DeviceTag.kt` — stable per-device suffix (`<sanitised-model>-<8-char-android-id>`).
- `ui/MainActivity.kt` — single screen: sign-in, permission grant, manual sync, sign out, recent events list (last 10).

`app/src/test/kotlin/com/nestegg/btlogger/`
- `storage/EventStoreTest.kt` — append, multi-month bucketing, offset advance, partial-line tolerance, missing file, `recent()`, name escaping.
- `sync/DeviceTagTest.kt` — model sanitisation, truncation, fallbacks.

## Implementation status

**Working end-to-end on real hardware** (Galaxy S20 Android 13 + a second phone). All 7 original working-order steps done; per-device filenames added afterwards.

If something here drifts, **trust git, not this section** — `git log --oneline` is the source of truth for what landed when.

## Known gotchas worth remembering

- **Implicit broadcast restrictions (Android 8+)** don't apply to ACL events for *paired* devices — they remain exempt. Manifest registration is the right call.
- **`BLUETOOTH_CONNECT` is required to receive ACL broadcasts at all** on Android 12+, not just to read names. Without it the receiver never fires; logcat is silent. Verified on the S20.
- **Reboot:** receiver re-registers automatically from manifest. Connections that happened during the reboot window are lost — accept this.
- **Toggling the BT adapter off/on does NOT fire per-device ACL events** on Samsung One UI (Galaxy S20 Android 13). Only `ACTION_STATE_CHANGED` for the adapter. The reconciler can't infer connect/disconnect from adapter state. Real ACL events fire only on actual link transitions (device entering/leaving range, pairing, explicit per-device disconnect from settings, BT device power-cycle).
- **Each connect/disconnect produces *two* ACL events for paired devices** — one on the BR/EDR transport and one on LE — within ~10s of each other. Reconciler must dedupe consecutive same-state events from the same MAC inside a small window.
- **`BluetoothDevice.getName()` requires `BLUETOOTH_CONNECT`.** Without it, `name` returns null. Receiver wraps in `runCatching` so we still log MAC.
- **Multiple simultaneous devices** (headphones + car): log all; reconciler picks the one matching a known-vehicle MAC.
- **Drive REST has no real append.** We re-upload the whole file via `files.update`. For monthly CSVs the size stays small (<200 KB).
- **Per-device CSV filenames matter** — `drive.file` scope means two phones on the same Google account otherwise race on a single shared file. Filename includes `<sanitised-model>-<8-char-android-id>` (see `DeviceTag.kt`). Reconciler should glob `bluetooth-log-*-YYYY-MM.csv`.
- **Samsung battery saver kills the app** unless the user explicitly excludes it (Settings → Apps → Battery → Unrestricted, AND Battery → Background usage limits → Never sleeping apps). Without this, expect gaps. README documents the steps.
- **OAuth "Testing" mode blocks anyone not on the test-users list** — including the developer's own account. Symptom: "access blocked, app has not completed verification". Add the account in the OAuth consent screen → Test users.
- **`statusCode=10` (`DEVELOPER_ERROR`) at sign-in** = the OAuth Android client in Cloud Console is missing or its SHA-1 doesn't match the signing keystore.

## Conventions

- Source under `app/src/main/kotlin/` (not `java/`) — explicit `srcDirs` in `app/build.gradle.kts`. Same for `src/test/kotlin/`.
- Package: `com.nestegg.btlogger`.
- App namespace: `com.nestegg.btlogger`. Application id: same.
- Don't add a foreground service — defeats the "lightweight, push-only" design unless we hit a real reliability issue.
- Don't introduce JSON or CSV libraries — the schemas are tiny and well-known; hand-rolled keeps deps minimal.

## Running locally

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # run JVM unit tests
./gradlew :app:installDebug           # build + push to connected device
adb logcat -d -s BtEventReceiver BtLoggerUi DriveSyncWorker  # snapshot
```

Wrapper jar is not in the repo. README has first-time setup. Memory has notes on running Gradle from the Android-Studio-populated cache when no wrapper jar exists.

## Out-of-scope (defer)

- Webhook ping for live reconciliation
- "Now connected to X" notification
- Wider "where was the phone" audit trail
- iOS
- Migrating off the deprecated `GoogleSignIn` API to Credential Manager (works fine for now; will need attention before Google removes the old API)

## Where the reconciler lives

Not in this repo. Separate desktop/cloud tool that pulls the Drive CSVs + MileIQ export and reclassifies drives. Likely a Python or TypeScript script; not yet written. When it exists, link from here.
