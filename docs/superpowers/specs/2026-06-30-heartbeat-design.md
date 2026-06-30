# Daily liveness heartbeat — design

**Date:** 2026-06-30
**Status:** Approved design, pre-implementation

## Goals

**Outcome:** The reconciler can tell a *genuine* "car not used for N days" gap apart from a *logger fault*, because the logger writes a daily "I'm alive" marker into the same per-device CSV during periods of Bluetooth silence. Each marker also carries a quick verdict on whether the on-device *preconditions* for capture were healthy.

**Acceptance criteria:**

1. When no record (real BT event *or* prior heartbeat) has been written to the log for ≥ 24h, the next hourly sync run appends one `HEARTBEAT` record and uploads it to `bluetooth-log-<deviceTag>-YYYY-MM.csv`.
2. During a multi-day drought, exactly one heartbeat appears per ~24h (the heartbeat resets its own clock).
3. When real BT activity has occurred within the last 24h, **no** heartbeat is emitted.
4. Each heartbeat row carries a status of `OK` (all preconditions green) or `DEGRADED:<tokens>` (one or more preconditions failing) in the `device_name` column, with an empty `device_mac`.
5. The CSV header and the schema of real BT-event rows are unchanged; existing reconciler parsing of real events is unaffected.
6. The first-ever heartbeat is created even on a fresh install with an empty log (subject to the same 24h rule), and is not blocked by the worker's "nothing to sync" early-out.
7. The heartbeat decision and the status verdict are pure, unit-tested domain functions.

## Background

The app is a push-only ACL connect/disconnect logger synced to Drive (see project `CLAUDE.md`). A gap in the CSV is currently ambiguous: "no car use" and "logger dead" look identical, which breaks MileIQ reconciliation when a car sits unused for days.

Two distinct failure modes exist:

- **Total death** — phone off, app force-stopped, account lost, no network. The CSV stops entirely. A heartbeat catches this fully.
- **Capture-only death** — the documented Samsung deep-sleep case (`sm-g981b`, Jun 2026): ACL broadcasts stop, but the deferrable WorkManager sync still runs occasionally; `BLUETOOTH_CONNECT` stays granted. A heartbeat that rides WorkManager **cannot** prove capture is healthy here.

A normal app has **no public API** to see *past* connections it missed (the `dumpsys bluetooth_manager` history needs shell/root). It *can* cheaply read the *preconditions* for capture: `BLUETOOTH_CONNECT` grant, Doze/battery-optimisation exemption, BT adapter on. So the heartbeat carries a precondition verdict, not a capture guarantee.

**The reconciler must read the heartbeat as "logger confirmed alive," not "capture guaranteed."** A point-in-time current-connection cross-check was considered and rejected: the car is parked/disconnected when the overnight heartbeat fires, so it would almost never catch a car-connection miss.

## Design overview

The heartbeat is a new `EventType.HEARTBEAT` record that rides the existing `EventStore → DriveSyncWorker → CSV` pipeline. On each hourly sync run, the worker:

1. Reads the timestamp of the most recent record in the log (event or heartbeat).
2. If `now − lastRecord ≥ 24h` (or the log is empty), runs the precondition audit and appends a `HEARTBEAT` record whose `device_name` is the status verdict and whose `device_mac` is empty.
3. Proceeds with the normal sync loop, which uploads the new heartbeat row like any other record.

Because the heartbeat is itself a logged record, it resets the "time since last record" clock, giving one heartbeat per silent day through any drought.

## Components

### 1. Domain — heartbeat decision (pure, unit-tested)

New file `sync/Heartbeat.kt` (domain layer, no I/O) — holds both pure functions in this section and the next:

```kotlin
const val HEARTBEAT_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

fun shouldEmitHeartbeat(nowMillis: Long, lastRecordMillis: Long?): Boolean =
    lastRecordMillis == null || (nowMillis - lastRecordMillis) >= HEARTBEAT_INTERVAL_MILLIS
```

Pure elapsed-millis comparison → timezone/DST-agnostic.

### 2. Domain — precondition audit verdict (pure, unit-tested)

Also in `sync/Heartbeat.kt`. Maps the existing `SetupStatus` plus a BT-adapter-enabled flag to the status token written into the heartbeat row. Reuses `SetupStatus.issues` so the two `SetupIssue` values aren't duplicated.

- All green → `"OK"`
- Otherwise → `"DEGRADED:"` + tokens joined by `+`, in a deterministic order:
  - `MISSING_BLUETOOTH_CONNECT` → `perm-missing`
  - `NOT_BATTERY_EXEMPT` → `no-doze-exemption`
  - adapter off → `bt-off`

e.g. `DEGRADED:perm-missing+no-doze-exemption`. No commas (keeps the CSV field unquoted and human-readable).

### 3. Infra — BT adapter state reader

Add `fun isBluetoothAdapterEnabled(context: Context): Boolean` to `setup/SetupChecks.kt` (the existing live-state reader). Infra, not unit-tested. `SetupStatus` is left untouched so the existing setup-health banner contract is unchanged.

### 4. Storage — last-record timestamp

Add `fun lastRecordMillis(): Long?` to `EventStore`, returning the newest record's `utcTimestamp` across month files, or `null` if the log is empty. Implemented via the existing `recent(1)` path (cheap — only touches the current month's file).

### 5. Event model

Add `HEARTBEAT` to `EventType`. It round-trips through the existing hand-rolled JSONL encode/decode and `CsvFormat.row` with no changes (type matched by `.name`). A heartbeat `BtEvent` has `deviceName = <status token>`, `deviceMac = ""`.

### 6. Worker wiring (`DriveSyncWorker`)

Reorder `doWork` so the heartbeat is written **before** the account check and the `months().isEmpty()` early-out:

1. `val setup = readSetupStatus(context)` (hoisted; reused by both the notifier and the audit).
2. `SetupNotifier.update(context, setup)`.
3. `val now = System.currentTimeMillis()`, `val last = store.lastRecordMillis()`.
4. If `shouldEmitHeartbeat(now, last)`: compute status from `setup` + `isBluetoothAdapterEnabled(context)`, `store.append(BtEvent(now, HEARTBEAT, status, ""))`.
5. Account check → if none, `Result.success()` (heartbeat is at least persisted locally; it uploads once an account exists).
6. Existing sync loop uploads the heartbeat row with everything else.

### 7. UI (vertical slice)

`MainActivity`'s "recent events (last 10)" list is about real connect/disconnect, so **filter `HEARTBEAT` out of that list** to avoid crowding it. Add a single status line — *"Last alive check: \<relative time> — OK / DEGRADED:…"* — derived from the most recent `HEARTBEAT` record, giving the user direct in-app visibility of liveness. (Small; may be trimmed at review if deemed scope creep.)

### 8. Reconciler contract (documentation only)

The reconciler lives in a separate repo. Document the contract in this project's `CLAUDE.md` gotchas and `README`:

- Glob is unchanged (`bluetooth-log-*-YYYY-MM.csv`).
- Rows with `event_type=HEARTBEAT` are **liveness markers**, not BT events — exclude them from drive-matching/dedup.
- `device_name` holds the verdict: `OK` = high trust; `DEGRADED:<tokens>` = logger alive but capture preconditions were compromised that day → treat a surrounding gap with suspicion; **heartbeat absent across a gap** = total death → gap untrustworthy.
- A heartbeat proves the logger was *alive*, never that capture *succeeded*.

## Data flow

```
hourly WorkManager tick
  → DriveSyncWorker.doWork
      readSetupStatus ─┬→ SetupNotifier.update
                       └→ heartbeat audit verdict
      EventStore.lastRecordMillis ─→ shouldEmitHeartbeat?
          yes → EventStore.append(HEARTBEAT, status, "")
      sync loop → DriveClient.appendCsvRows → bluetooth-log-<tag>-YYYY-MM.csv
```

## Error handling / edge cases

- **Empty log / fresh install:** `lastRecordMillis() == null` → heartbeat emitted (criterion 6). Written before the early-outs so it isn't skipped.
- **No account yet:** heartbeat persisted locally, uploaded when an account is configured. Harmless accumulation (one tiny row/day).
- **Deep-sleep lateness:** if WorkManager is throttled, the heartbeat is late or missing for a day — that absence is itself signal and is acceptable.
- **Month boundary:** heartbeat buckets into the current UTC month via existing `yearMonthOf`; `lastRecordMillis` reads newest month first.
- **Hourly granularity:** the heartbeat fires on the first hourly run *after* the 24h mark, so spacing is ~24–25h. Fine for daily resolution.

## Testing

- **`HeartbeatTest` (new, JVM):** `shouldEmitHeartbeat` truth table — null last record → true; `< 24h` → false; exactly `24h` boundary → true; `> 24h` → true. Status verdict mapping — all-green → `OK`; each single failing condition; multiple conditions in deterministic order.
- **`EventStoreTest` (extend):** `lastRecordMillis` — empty → null; returns newest across multiple months; includes a `HEARTBEAT` record as the newest.
- **Manual / on-device:** force a 24h+ silence (or temporarily shrink the interval), confirm a `HEARTBEAT,OK,` row reaches Drive; revoke the Doze exemption and confirm `DEGRADED:no-doze-exemption`.
- `DriveSyncWorker` remains infra (not unit-tested), consistent with the project.

## Out of scope (deferred)

- Point-in-time current-connection cross-check (rejected above).
- Any capture-success proof / mode-2 (capture-only death) detection beyond the precondition verdict.
- Configurable interval / per-event-type heartbeats.
- Reconciler-side changes (separate repo; only the contract is documented here).
