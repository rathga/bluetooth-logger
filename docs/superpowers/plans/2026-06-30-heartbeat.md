# Daily Liveness Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit a daily `HEARTBEAT` record into the per-device CSV during Bluetooth silence so the reconciler can tell genuine no-car-use from a logger fault, with each heartbeat carrying an `OK`/`DEGRADED:<tokens>` precondition verdict.

**Architecture:** A new `EventType.HEARTBEAT` rides the existing `EventStore → DriveSyncWorker → CSV` pipeline. On each hourly sync, if nothing has been logged for ≥24h, the worker runs a pure precondition audit and appends a heartbeat (verdict in `device_name`, empty `device_mac`) before its existing early-outs, so the normal sync loop uploads it. Heartbeats count as log records, so they reset the 24h clock — one per silent day.

**Tech Stack:** Kotlin 2.0, Android (minSdk 31), WorkManager, JUnit 4 (JVM unit tests only — no Robolectric/instrumentation).

## Global Constraints

- Source under `app/src/main/kotlin/`, tests under `app/src/test/kotlin/`. Package `com.nestegg.btlogger`.
- No new JSON/CSV libraries; no foreground service (project `CLAUDE.md`).
- Only Domain code is unit-tested. Infra (worker, `SetupChecks`, `MainActivity`) is verified by build + manual on-device, matching the existing project.
- Status token format is fixed: `OK`, or `DEGRADED:` + tokens joined by `+` in this exact order — `perm-missing`, `no-doze-exemption`, `bt-off`. No commas (keeps the CSV field unquoted).
- Heartbeat `BtEvent`: `eventType = HEARTBEAT`, `deviceName = <status token>`, `deviceMac = ""`.
- CSV header and real-event row schema are unchanged.

---

### Task 1: Heartbeat domain — decision + verdict (pure, unit-tested)

**Files:**
- Create: `app/src/main/kotlin/com/nestegg/btlogger/sync/Heartbeat.kt`
- Test: `app/src/test/kotlin/com/nestegg/btlogger/sync/HeartbeatTest.kt`

**Interfaces:**
- Consumes: `SetupStatus` and `SetupIssue` from `com.nestegg.btlogger.setup` (existing — `SetupStatus(bluetoothConnectGranted: Boolean, batteryExempt: Boolean)` with a `.issues: List<SetupIssue>` property; `SetupIssue.MISSING_BLUETOOTH_CONNECT`, `SetupIssue.NOT_BATTERY_EXEMPT`).
- Produces:
  - `const val HEARTBEAT_INTERVAL_MILLIS: Long`
  - `fun shouldEmitHeartbeat(nowMillis: Long, lastRecordMillis: Long?): Boolean`
  - `fun heartbeatStatus(setup: SetupStatus, bluetoothAdapterEnabled: Boolean): String`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/nestegg/btlogger/sync/HeartbeatTest.kt`:

```kotlin
package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {

    @Test fun `emits when there is no prior record`() {
        assertTrue(shouldEmitHeartbeat(nowMillis = 1_000_000, lastRecordMillis = null))
    }

    @Test fun `does not emit before the interval has elapsed`() {
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - (HEARTBEAT_INTERVAL_MILLIS - 1)
        assertFalse(shouldEmitHeartbeat(now, last))
    }

    @Test fun `emits exactly at the interval boundary`() {
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - HEARTBEAT_INTERVAL_MILLIS
        assertTrue(shouldEmitHeartbeat(now, last))
    }

    @Test fun `emits well past the interval`() {
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - (3 * HEARTBEAT_INTERVAL_MILLIS)
        assertTrue(shouldEmitHeartbeat(now, last))
    }

    @Test fun `status is OK when all preconditions are healthy`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals("OK", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing permission`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertEquals("DEGRADED:perm-missing", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing doze exemption`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertEquals("DEGRADED:no-doze-exemption", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a disabled adapter`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals("DEGRADED:bt-off", heartbeatStatus(setup, bluetoothAdapterEnabled = false))
    }

    @Test fun `status lists every failing precondition in a fixed order`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = false)
        assertEquals(
            "DEGRADED:perm-missing+no-doze-exemption+bt-off",
            heartbeatStatus(setup, bluetoothAdapterEnabled = false),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.sync.HeartbeatTest"`
Expected: FAIL — `Heartbeat.kt` does not exist (unresolved reference `shouldEmitHeartbeat` / `heartbeatStatus` / `HEARTBEAT_INTERVAL_MILLIS`).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/kotlin/com/nestegg/btlogger/sync/Heartbeat.kt`:

```kotlin
package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupIssue
import com.nestegg.btlogger.setup.SetupStatus

/** Minimum silence before a liveness heartbeat is written: 24 hours. */
const val HEARTBEAT_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

/**
 * True when a heartbeat should be written now: nothing has been logged yet, or
 * the most recent record (real event or prior heartbeat) is at least
 * [HEARTBEAT_INTERVAL_MILLIS] old. Pure elapsed-millis — timezone-agnostic.
 */
fun shouldEmitHeartbeat(nowMillis: Long, lastRecordMillis: Long?): Boolean =
    lastRecordMillis == null || (nowMillis - lastRecordMillis) >= HEARTBEAT_INTERVAL_MILLIS

/**
 * Precondition verdict carried in the heartbeat's device_name column: "OK" when
 * every capture precondition is healthy, otherwise "DEGRADED:" plus the failing
 * tokens joined by '+' in a fixed order. This proves the logger was alive and
 * the conditions capture depends on were green — never that capture succeeded.
 */
fun heartbeatStatus(setup: SetupStatus, bluetoothAdapterEnabled: Boolean): String {
    val tokens = buildList {
        if (SetupIssue.MISSING_BLUETOOTH_CONNECT in setup.issues) add("perm-missing")
        if (SetupIssue.NOT_BATTERY_EXEMPT in setup.issues) add("no-doze-exemption")
        if (!bluetoothAdapterEnabled) add("bt-off")
    }
    return if (tokens.isEmpty()) "OK" else "DEGRADED:" + tokens.joinToString("+")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.sync.HeartbeatTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/sync/Heartbeat.kt app/src/test/kotlin/com/nestegg/btlogger/sync/HeartbeatTest.kt
git commit -m "Add heartbeat decision and precondition-verdict domain"
```

---

### Task 2: Storage — `HEARTBEAT` event type + last-record / heartbeat / connections readers

**Files:**
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/storage/BtEvent.kt:10-13` (add enum value)
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/storage/EventStore.kt:52-63` (refactor `recent`, add readers)
- Test: `app/src/test/kotlin/com/nestegg/btlogger/storage/EventStoreTest.kt`

**Interfaces:**
- Consumes: existing `BtEvent`, `EventType`, `parseJsonLineOrNull` (storage package).
- Produces:
  - `EventType.HEARTBEAT`
  - `EventStore.lastRecordMillis(): Long?`
  - `EventStore.lastHeartbeat(): BtEvent?`
  - `EventStore.recentConnections(limit: Int): List<BtEvent>` (real connect/disconnect only, newest first)

- [ ] **Step 1: Write the failing test**

Append these tests to `app/src/test/kotlin/com/nestegg/btlogger/storage/EventStoreTest.kt` (inside the class, before the private `utc` helper):

```kotlin
    @Test fun `lastRecordMillis is null for an empty store`() {
        assertNull(store().lastRecordMillis())
    }

    @Test fun `lastRecordMillis returns the newest timestamp including heartbeats`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        val beat = utc(2026, 4, 26, 9, 0)
        s.append(BtEvent(beat, EventType.HEARTBEAT, "OK", ""))
        assertEquals(beat, s.lastRecordMillis())
    }

    @Test fun `recentConnections excludes heartbeat records`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 25, 10, 0), EventType.HEARTBEAT, "OK", ""))
        s.append(BtEvent(utc(2026, 4, 25, 11, 0), EventType.DISCONNECTED, "Car", "AA:BB:CC:00:00:01"))

        val recent = s.recentConnections(10)
        assertEquals(listOf(EventType.DISCONNECTED, EventType.CONNECTED), recent.map { it.eventType })
    }

    @Test fun `lastHeartbeat returns the most recent heartbeat`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.HEARTBEAT, "DEGRADED:bt-off", ""))
        s.append(BtEvent(utc(2026, 4, 26, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 27, 9, 0), EventType.HEARTBEAT, "OK", ""))

        val beat = s.lastHeartbeat()
        assertEquals("OK", beat?.deviceName)
        assertEquals(utc(2026, 4, 27, 9, 0), beat?.utcTimestamp)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.storage.EventStoreTest"`
Expected: FAIL — unresolved references `EventType.HEARTBEAT`, `lastRecordMillis`, `recentConnections`, `lastHeartbeat`.

- [ ] **Step 3a: Add the enum value**

In `app/src/main/kotlin/com/nestegg/btlogger/storage/BtEvent.kt`, extend `EventType`:

```kotlin
enum class EventType {
    CONNECTED,
    DISCONNECTED,
    HEARTBEAT,
}
```

- [ ] **Step 3b: Refactor `recent` and add the readers**

In `app/src/main/kotlin/com/nestegg/btlogger/storage/EventStore.kt`, replace the existing `recent` function (lines 52-63) with the delegating version plus the new readers and a shared private scan:

```kotlin
    /**
     * Most recent [limit] events of any type, newest first. Reads month files
     * from newest to oldest until the cap is reached.
     */
    fun recent(limit: Int): List<BtEvent> = recentMatching(limit) { true }

    /** Most recent [limit] real connect/disconnect events (heartbeats excluded), newest first. */
    fun recentConnections(limit: Int): List<BtEvent> =
        recentMatching(limit) { it.eventType != EventType.HEARTBEAT }

    /** UTC millis of the newest record of any type, or null if the log is empty. */
    fun lastRecordMillis(): Long? = recent(1).firstOrNull()?.utcTimestamp

    /** The most recent heartbeat record, or null if none has been written. */
    fun lastHeartbeat(): BtEvent? =
        recentMatching(1) { it.eventType == EventType.HEARTBEAT }.firstOrNull()

    private fun recentMatching(limit: Int, predicate: (BtEvent) -> Boolean): List<BtEvent> {
        if (limit <= 0) return emptyList()
        val out = ArrayDeque<BtEvent>(limit)
        for (yearMonth in months().reversed()) {
            val lines = monthFile(yearMonth).readLines()
            for (i in lines.indices.reversed()) {
                val event = parseJsonLineOrNull(lines[i]) ?: continue
                if (!predicate(event)) continue
                out.addLast(event)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nestegg.btlogger.storage.EventStoreTest"`
Expected: PASS (existing tests + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/storage/BtEvent.kt app/src/main/kotlin/com/nestegg/btlogger/storage/EventStore.kt app/src/test/kotlin/com/nestegg/btlogger/storage/EventStoreTest.kt
git commit -m "Add HEARTBEAT event type and last-record/heartbeat/connections readers"
```

---

### Task 3: Worker wiring — adapter reader + emit heartbeat before early-outs

**Files:**
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt` (add adapter reader)
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt:26-71` (reorder `doWork`)

**Interfaces:**
- Consumes: `shouldEmitHeartbeat`, `heartbeatStatus` (Task 1); `EventStore.lastRecordMillis`, `EventType.HEARTBEAT` (Task 2); `readSetupStatus` (existing).
- Produces: `fun isBluetoothAdapterEnabled(context: Context): Boolean` in the `setup` package.

This task is infrastructure: no unit test (consistent with the project — the worker and `SetupChecks` are not unit-tested). Verified by build + the manual on-device check below.

- [ ] **Step 1: Add the adapter-state reader**

In `app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt`, add the import and function (leave `readSetupStatus` unchanged so the setup-health banner contract is untouched):

```kotlin
import android.bluetooth.BluetoothManager
```

```kotlin
/** True when the Bluetooth adapter is present and currently enabled. */
fun isBluetoothAdapterEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.isEnabled == true
}
```

- [ ] **Step 2: Reorder `doWork` to hoist setup and emit the heartbeat first**

In `app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt`, replace the body of `doWork` (current lines 26-71) so the heartbeat is written before the account check and the `months().isEmpty()` early-out. Add the imports `com.nestegg.btlogger.setup.isBluetoothAdapterEnabled`, `com.nestegg.btlogger.storage.BtEvent`, and `com.nestegg.btlogger.storage.EventType`:

```kotlin
    override suspend fun doWork(): Result {
        val setup = readSetupStatus(applicationContext)
        SetupNotifier.update(applicationContext, setup)

        val store = EventStore(applicationContext)
        maybeWriteHeartbeat(store, setup)

        val state = SyncState.from(applicationContext)
        val accountName = state.accountName ?: run {
            Log.i(TAG, "No Google account configured; skipping sync")
            return Result.success()
        }

        val months = store.months()
        if (months.isEmpty()) return Result.success()

        val client = DriveClient.forAccountName(applicationContext, accountName)
        val deviceTag = DeviceTag.forContext(applicationContext)
        var totalAppended = 0

        return try {
            for (yearMonth in months) {
                val offset = state.offsetFor(yearMonth)
                val chunk = store.unsynced(yearMonth, offset)
                if (chunk.events.isEmpty()) continue
                val rows = chunk.events.map(CsvFormat::row)
                val appended = client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
                state.setOffsetFor(yearMonth, chunk.newByteOffset)
                totalAppended += appended
                Log.i(TAG, "Appended $appended row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
            }
            state.lastSyncMillis = System.currentTimeMillis()
            Result.success()
        } catch (e: UserRecoverableAuthIOException) {
            // Token expired or scope was revoked. The offset is untouched, so the
            // next sync after re-auth will pick up exactly where we left off.
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            Result.failure()
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            Result.failure()
        } catch (e: IOException) {
            Log.w(TAG, "Transient sync failure; will retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure()
        }
    }

    /**
     * Write a liveness heartbeat if nothing has been logged for [HEARTBEAT_INTERVAL_MILLIS].
     * Runs before the account / empty-log early-outs so the first-ever heartbeat is
     * created even on a fresh install; it uploads on this or a later sync run.
     */
    private fun maybeWriteHeartbeat(store: EventStore, setup: com.nestegg.btlogger.setup.SetupStatus) {
        val now = System.currentTimeMillis()
        if (!shouldEmitHeartbeat(now, store.lastRecordMillis())) return
        val status = heartbeatStatus(setup, isBluetoothAdapterEnabled(applicationContext))
        store.append(BtEvent(now, EventType.HEARTBEAT, status, ""))
        Log.i(TAG, "Wrote liveness heartbeat: $status")
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests across the module).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/setup/SetupChecks.kt app/src/main/kotlin/com/nestegg/btlogger/sync/DriveSyncWorker.kt
git commit -m "Emit liveness heartbeat from sync worker during Bluetooth silence"
```

**Manual on-device verification (run during the Verifier pass, not blocking the commit):** with the app signed in, temporarily set `HEARTBEAT_INTERVAL_MILLIS` low (e.g. `60_000`) on a debug build, trigger "Sync now" twice a minute apart with no BT activity, and confirm a `…,HEARTBEAT,OK,` row reaches `bluetooth-log-<tag>-YYYY-MM.csv` in Drive. Then revoke the battery exemption and confirm the next heartbeat reads `DEGRADED:no-doze-exemption`. Restore the interval to `24L * 60 * 60 * 1000` before merging.

---

### Task 4: UI — show last alive check, keep heartbeats out of the recent list

**Files:**
- Modify: `app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt:191` (use `recentConnections`), `:207` (add a status line), `:293-295` (add `formatHeartbeat`)

**Interfaces:**
- Consumes: `EventStore.recentConnections`, `EventStore.lastHeartbeat` (Task 2); existing `BtEvent`.
- Produces: in-app "Last alive check" line. No public API for later tasks.

Infrastructure (Compose UI; no Robolectric in this project): verified by build + the on-device look below.

- [ ] **Step 1: Source the recent list from `recentConnections` and read the last heartbeat**

In `StatusScreen` (`MainActivity.kt`), change the `recent` line and add a `lastHeartbeat` read:

Replace:
```kotlin
    val recent = remember(refreshTick) { store.recent(RECENT_LIMIT) }
```
with:
```kotlin
    val recent = remember(refreshTick) { store.recentConnections(RECENT_LIMIT) }
    val lastHeartbeat = remember(refreshTick) { store.lastHeartbeat() }
```

- [ ] **Step 2: Add the status line**

In the same composable, after the existing `Text("Last sync: ${formatLastSync(lastSync)}")` line, add:

```kotlin
        Text("Last alive check: ${formatHeartbeat(lastHeartbeat)}")
```

- [ ] **Step 3: Add the formatter**

At the bottom of the file, beside `formatLastSync`, add:

```kotlin
private fun formatHeartbeat(event: BtEvent?): String {
    if (event == null) return "never"
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(event.utcTimestamp))
    return "$time — ${event.deviceName ?: "OK"}"
}
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/nestegg/btlogger/ui/MainActivity.kt
git commit -m "Show last alive check in-app and exclude heartbeats from recent list"
```

**Manual on-device verification (Verifier pass):** open the app after a heartbeat has been written and confirm the "Last alive check" line shows the time and `OK`/`DEGRADED:…`, and that heartbeats do not appear in the "Recent events" list.

---

### Task 5: Documentation — reconciler contract + project memory

**Files:**
- Modify: `bluetooth-logger/CLAUDE.md` (source layout, gotchas, implementation status)
- Modify: `bluetooth-logger/README.md` (reconciler-facing heartbeat contract)

No code; no test. Commit closes the slice.

- [ ] **Step 1: Update project `CLAUDE.md`**

In `bluetooth-logger/CLAUDE.md`:

- Under **Source layout** (`sync/` list), add:
  - `sync/Heartbeat.kt` — pure domain: `shouldEmitHeartbeat` (24h-since-last-record) + `heartbeatStatus` (precondition verdict). Unit-tested.
  - Note on `setup/SetupChecks.kt`: now also exposes `isBluetoothAdapterEnabled`.
  - Note on `storage/EventStore.kt`: now exposes `lastRecordMillis`, `lastHeartbeat`, `recentConnections`.
  - Under the test list, add `sync/HeartbeatTest.kt` — decision truth table + verdict mapping.
- Add a **Known gotchas** bullet:
  > **Liveness heartbeat** — during ≥24h of Bluetooth silence the hourly worker appends a `HEARTBEAT` row (`event_type=HEARTBEAT`, verdict in `device_name`, empty MAC) so the reconciler can tell genuine no-car-use from a logger fault. It proves the logger was *alive* and capture *preconditions* were green (`OK`) or not (`DEGRADED:perm-missing|no-doze-exemption|bt-off`) — **never that capture succeeded** (the deep-sleep case decouples WorkManager from ACL delivery). The reconciler must exclude `HEARTBEAT` rows from drive-matching/dedup; **heartbeat absent across a gap = total death, gap untrustworthy.**
- Under **Implementation status**, add a line: liveness heartbeat added Jun 2026 (see `docs/superpowers/plans/2026-06-30-heartbeat.md`).

- [ ] **Step 2: Update `README.md`**

Add a short "Liveness heartbeat" subsection documenting the same reconciler contract for a human reader: the `HEARTBEAT` row shape, the `OK` / `DEGRADED:<tokens>` verdict and what each token means, the daily-during-silence cadence, and the rule that an *absent* heartbeat across a gap means the logger was down (untrustworthy gap) while a present `OK` heartbeat means the gap is trustworthy no-car-use.

- [ ] **Step 3: Commit**

```bash
git add bluetooth-logger/CLAUDE.md bluetooth-logger/README.md
git commit -m "Document the liveness heartbeat and its reconciler contract"
```

---

## Self-Review

**Spec coverage:**
- Acceptance criterion 1 (emit after ≥24h silence, upload) → Task 3 (`maybeWriteHeartbeat` before early-outs) + existing sync loop.
- Criterion 2 (one per silent day) → Task 1 `shouldEmitHeartbeat` keyed on `lastRecordMillis`, which includes heartbeats (Task 2).
- Criterion 3 (none on active days) → Task 1 decision; covered by `does not emit before the interval` test.
- Criterion 4 (`OK`/`DEGRADED` in `device_name`, empty MAC) → Task 1 `heartbeatStatus` + Task 3 `BtEvent(now, HEARTBEAT, status, "")`.
- Criterion 5 (header/real-row schema unchanged) → no `CsvFormat`/`HEADER` change; HEARTBEAT rides existing `CsvFormat.row`.
- Criterion 6 (first heartbeat on empty log, not blocked by early-out) → Task 3 ordering; `lastRecordMillis null → emit` test (Task 1) + empty-store test (Task 2).
- Criterion 7 (pure, unit-tested decision + verdict) → Task 1 `HeartbeatTest`.
- Spec components 1–8 all mapped: domain (T1), audit verdict (T1), adapter reader (T3), last-record (T2), event model (T2), worker (T3), UI (T4), reconciler contract (T5).

**Placeholder scan:** No TBD/TODO; every code step shows full code; README step describes exact content to write (prose section, no code) — acceptable as it's documentation.

**Type consistency:** `shouldEmitHeartbeat(Long, Long?)`, `heartbeatStatus(SetupStatus, Boolean)`, `HEARTBEAT_INTERVAL_MILLIS`, `lastRecordMillis(): Long?`, `lastHeartbeat(): BtEvent?`, `recentConnections(Int)`, `isBluetoothAdapterEnabled(Context)` — names/signatures identical across the tasks that produce and consume them. `EventType.HEARTBEAT` and the `BtEvent(ts, type, name, mac)` constructor order match the existing data class.
