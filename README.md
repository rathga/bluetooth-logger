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

Scaffold only. Receiver wiring and storage interface in place; storage/sync/UI bodies are TODO stubs. See `CLAUDE.md` for what's left.

## Reconciler

Lives elsewhere (desktop/cloud). Reads MileIQ drive export + this Drive CSV, overlaps drive windows with BT-connection windows (±2 min slack), and reclassifies any drive where a known-vehicle MAC was connected for >50% of the drive duration.
