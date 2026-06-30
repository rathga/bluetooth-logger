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
