# Bluetooth Logger

Independent ground-truth log of which Bluetooth device an Android phone was connected to and when, so a MileIQ reconciler can correctly classify drives that MileIQ's auto-assign missed.

## How it works

- Statically-registered `BroadcastReceiver` on `BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED`. Fires at link-layer level — earlier than A2DP, earlier than anything MileIQ sees.
- Each event is appended to a JSONL file in app-private storage: `{utc_timestamp, event_type, device_name, device_mac}`.
- A `WorkManager` periodic job (~hourly, `NetworkType.CONNECTED`) appends new rows to a monthly CSV in Google Drive (`bluetooth-log-YYYY-MM.csv`).
- Idempotency: last-synced byte offset persisted in `SharedPreferences`.

No foreground service required — events are push, not poll.

## Build

Requires:
- Android Studio Iguana (2024.1.1) or later
- JDK 17
- Android SDK 35

The Gradle wrapper jar is **not** committed (it's a binary blob). On first checkout, generate it with one of:

```bash
# Option A: open in Android Studio — it'll offer to generate the wrapper
# Option B: if Gradle 8.10+ is on PATH:
gradle wrapper --gradle-version 8.10.2
```

Then:

```bash
./gradlew :app:assembleDebug
```

## Permissions

- `BLUETOOTH_CONNECT` — read paired-device names/addresses (Android 12+)
- `POST_NOTIFICATIONS` — sync-status icon
- `INTERNET` / `ACCESS_NETWORK_STATE` — Drive upload

Drive scope acquired separately via Google Sign-In.

## Google Cloud / Sign-In setup

Sign-In + Drive upload won't work until an OAuth 2.0 client is registered:

1. **Create / pick a Google Cloud project** at https://console.cloud.google.com.
2. **Enable the Drive API** for that project (APIs & Services → Library → Google Drive API).
3. **OAuth consent screen** — set User Type "External", add your account as a test user, add scope `.../auth/drive.file`.
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
5. Repeat step 4 with the release keystore SHA-1 before publishing.

No `google-services.json` is needed — Google Sign-In on Android validates against the package name + SHA-1 registered in the Cloud Console.

If sign-in returns `statusCode=10` (`DEVELOPER_ERROR`), the Cloud Console entry is missing or the SHA-1 doesn't match.

## Layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/nestegg/btlogger/
│   ├── BtLoggerApp.kt              # Application class — schedules sync worker
│   ├── receiver/
│   │   └── BluetoothEventReceiver.kt
│   ├── storage/
│   │   ├── BtEvent.kt              # data class
│   │   └── EventStore.kt           # JSONL append + read
│   ├── sync/
│   │   ├── DriveClient.kt          # Drive REST wrapper
│   │   └── DriveSyncWorker.kt      # WorkManager periodic job
│   └── ui/
│       └── MainActivity.kt         # Sign-in + permission grant + status
└── res/...
```

## Status

Storage, sync worker, Drive client, and single-screen UI are implemented. Pending: real-device end-to-end test, and the Google Cloud OAuth client setup above. See `CLAUDE.md` for design notes.

## Reconciler

Lives elsewhere (desktop/cloud). Reads MileIQ drive export + this Drive CSV, overlaps drive windows with BT-connection windows (±2 min slack), and reclassifies any drive where a known-vehicle MAC was connected for >50% of the drive duration.
