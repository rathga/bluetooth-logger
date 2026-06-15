# Bluetooth Logger

Lightweight Android app that records every Bluetooth ACL connect/disconnect event the phone sees and uploads them as a monthly CSV to Google Drive. No foreground service, no notifications, no UI noise — it sits silently in the background and produces an authoritative timeline of "what was this phone connected to, and when".

## What you might use it for

- **MileIQ reconciliation** — the original use case. MileIQ classifies drives based on patterns/locations; cross-referencing against "the phone was paired to my car's hands-free during this drive window" lets a separate reconciler reassign drives MileIQ got wrong.
- **"Was I in my car at time X?"** — a personal audit trail of vehicle Bluetooth connections, useful for expense disputes, alibi reconstruction, etc.
- **Headphone / wearable usage tracking** — when did I last use those earbuds, when did I pair with that watch.
- **Building blocks for any Bluetooth-presence-based automation** — the JSONL store + Drive CSV are easy inputs for downstream scripts, dashboards, or home-automation triggers.

## How it works

- Statically-registered `BroadcastReceiver` on `BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED`. Fires at link-layer level — earlier than A2DP, earlier than anything MileIQ sees. Disk write happens via `goAsync()` on a single-thread executor so the broadcast thread is never blocked.
- Each event is appended to a JSONL file in app-private storage: `{utc_timestamp, event_type, device_name, device_mac}`.
- A `WorkManager` periodic job (~hourly, `NetworkType.CONNECTED`) appends new rows to a per-device monthly CSV in Google Drive (`bluetooth-log-<device-tag>-YYYY-MM.csv`). Per-device filenames mean two phones syncing under the same Google account don't race on a single shared file.
- Idempotency: per-month last-synced byte offset persisted in `SharedPreferences`. Manual "Sync now" button in the UI re-runs the worker immediately for debugging.
- A **setup-health check** runs when you open the app and on every sync: if the app lacks battery-optimisation exemption or the `BLUETOOTH_CONNECT` permission — the two conditions that silently stop capture — it shows an in-app banner with a one-tap fix and posts a notification, so a phone going dark surfaces even if you haven't opened the app for weeks.

No foreground service required — events are push, not poll.

## Build

Requires:

- Android Studio Iguana (2024.1.1) or later, OR Gradle 8.10+ on PATH
- JDK 17+ (Android Studio's bundled JBR works fine)
- Android SDK 35

The Gradle wrapper jar is **not** committed (binary blob). On first checkout, generate it with one of:

```bash
# Option A: open in Android Studio — it'll offer to generate the wrapper on first sync
# Option B: if Gradle is on PATH:
gradle wrapper --gradle-version 8.10.2
```

Then:

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # run JVM unit tests (storage + device-tag)
./gradlew :app:installDebug           # build + install on connected adb device
```

## Install on a phone

You install the same debug APK on every phone you want to log from. Both phones can sign in with the same Google account — per-device filenames (see above) keep their CSVs separate.

### Easiest path (USB cable + adb)

1. Enable Developer Options on the phone: Settings → About phone → tap **Build number** 7×.
2. Settings → System → Developer options → enable **USB debugging**.
3. Plug the phone in and approve the "Allow USB debugging from this computer?" prompt (tick "Always allow" so it sticks).
4. From the repo root:
   ```bash
   ./gradlew :app:installDebug
   ```
   (Or `adb install -r app/build/outputs/apk/debug/app-debug.apk` if you've built the APK already.)

If `adb devices` shows the phone as `unauthorized`, you missed step 3 — replug and watch the phone for the prompt.

### No-cable path (sideload an APK)

1. Build the APK once: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
2. Get that file onto the phone: email, AirDroid, Google Drive, USB-MTP copy, etc.
3. On the phone, the first time you tap to open an APK from a file manager, Android prompts you to grant that file manager **"Install unknown apps"** — accept once.
4. Tap the APK to install. Tap **Install anyway** if Play Protect warns about an unknown developer (it's your own debug-signed build).

### After installing

1. Open the app, tap **Grant permissions** and allow Bluetooth + Notifications.
2. Tap **Sign in to Google Drive**, pick the Google account that's been added as an OAuth test user (see Cloud setup below), accept the Drive permission.
3. Pair / connect any Bluetooth device — the **Recent events** list should populate immediately.
4. Tap **Sync now** to push events to Drive without waiting for the hourly worker. Check the **Bluetooth Logger** folder in Drive for `bluetooth-log-<device-tag>-YYYY-MM.csv`.

### Samsung / OEM battery-saver gotcha

Samsung's One UI (and most non-Pixel OEMs) aggressively kill background apps. To keep the receiver and sync worker reliable on a Samsung device:

1. Settings → Apps → Bluetooth Logger → **Battery → Unrestricted**.
2. Settings → Battery and device care → Battery → **Background usage limits → Never sleeping apps** → add Bluetooth Logger.

Without these, expect occasional gaps where the OS killed the process before a broadcast woke a fresh one.

The app now detects the **first** half automatically: if it isn't battery-exempt, the main screen shows a banner with a one-tap **Allow background activity** button, and the sync worker raises a notification. That covers Android's Doze exemption (step 1) but **not** Samsung's separate *Never sleeping apps* list (step 2), which has no public API — you still add that one by hand.

### Same APK on multiple phones

Per-device CSV filenames mean you don't need to do anything special — install on phone 2, sign in with the same Google account, and it lands in `bluetooth-log-<device-2-tag>-YYYY-MM.csv`. The reconciler should read every `bluetooth-log-*-YYYY-MM.csv` in the Drive folder.

## Permissions

- `BLUETOOTH_CONNECT` — read paired-device names/addresses (Android 12+). **Required to receive ACL broadcasts at all** on Android 12+, not just to read names.
- `POST_NOTIFICATIONS` — the setup-health warning notification raised by the sync worker.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — lets the in-app "Allow background activity" button open the system battery-exemption dialog directly.
- `INTERNET` / `ACCESS_NETWORK_STATE` — Drive upload.
- `RECEIVE_BOOT_COMPLETED` — receiver re-registers automatically after reboot.

Drive scope acquired separately via Google Sign-In.

## Google Cloud / Sign-In setup

Sign-In + Drive upload won't work until an OAuth 2.0 Android client is registered:

1. **Create / pick a Google Cloud project** at https://console.cloud.google.com.
2. **Enable the Drive API** for that project (APIs & Services → Library → Google Drive API).
3. **OAuth consent screen** — set User Type "External", add the Google account(s) you'll sign in with as **Test users**, add scope `.../auth/drive.file`. Apps in "Testing" mode block any user not on the test-users list, including yourself, until the app is published — that's expected.
4. **Credentials → Create Credentials → OAuth client ID → Android.**
   - Package name: `com.nestegg.btlogger`
   - SHA-1: from your debug keystore. Get it with:
     ```bash
     # macOS / Linux
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
     ```
     ```powershell
     # Windows PowerShell — keytool ships with the JDK bundled in Android Studio
     & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" `
         -list -v `
         -keystore "$env:USERPROFILE\.android\debug.keystore" `
         -alias androiddebugkey -storepass android -keypass android |
         Select-String "SHA1|SHA-1"
     ```
5. Repeat step 4 with the release keystore SHA-1 before publishing a signed build.

The same debug keystore is used to sign the APK on every phone you install to, so **one OAuth client entry covers all your phones** — no per-device Cloud Console work.

No `google-services.json` is needed — Google Sign-In on Android validates against the package name + SHA-1 registered in the Cloud Console.

If sign-in returns `statusCode=10` (`DEVELOPER_ERROR`), the Cloud Console entry is missing or the SHA-1 doesn't match.

## Layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/nestegg/btlogger/
│   ├── BtLoggerApp.kt              # Application — schedules the worker, creates the notification channel
│   ├── receiver/BluetoothEventReceiver.kt
│   ├── setup/                      # SetupStatus (pure issue decision), SetupChecks
│   │                               # (live-state reader), SetupNotifier
│   ├── storage/                    # BtEvent + EventStore (JSONL append/read)
│   ├── sync/                       # DriveClient, DriveSyncWorker, SyncState,
│   │                               # CsvFormat, DeviceTag
│   └── ui/MainActivity.kt          # Sign-in, permissions, sync, recent events, setup-health banner
└── res/...

app/src/test/kotlin/com/nestegg/btlogger/
├── setup/SetupStatusTest.kt
├── storage/EventStoreTest.kt
└── sync/DeviceTagTest.kt
```

## Status

Working end-to-end on real hardware (Galaxy S20, Android 13). Storage layer + device-tag + setup-status decision have JVM unit tests. UI shows recent events for visual verification and a setup-health banner when capture is at risk. Drive sync verified producing per-device monthly CSVs.
