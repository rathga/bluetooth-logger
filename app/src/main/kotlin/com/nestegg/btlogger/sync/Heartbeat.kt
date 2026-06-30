package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupIssue
import com.nestegg.btlogger.setup.SetupStatus

const val HEARTBEAT_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

fun shouldEmitHeartbeat(nowMillis: Long, lastRecordMillis: Long?): Boolean =
    lastRecordMillis == null || (nowMillis - lastRecordMillis) >= HEARTBEAT_INTERVAL_MILLIS

enum class DegradedReason {
    MISSING_BLUETOOTH_CONNECT,
    NOT_BATTERY_EXEMPT,
    BLUETOOTH_OFF,
}

sealed interface HeartbeatStatus {
    data object Ok : HeartbeatStatus

    /** Always non-empty: build via [of], which collapses no-reasons to [Ok]. */
    @ConsistentCopyVisibility
    data class Degraded internal constructor(val reasons: List<DegradedReason>) : HeartbeatStatus

    companion object {
        fun of(reasons: List<DegradedReason>): HeartbeatStatus =
            if (reasons.isEmpty()) Ok else Degraded(reasons)
    }
}

fun heartbeatStatus(setup: SetupStatus, bluetoothAdapterEnabled: Boolean): HeartbeatStatus {
    val reasons = buildList {
        if (SetupIssue.MISSING_BLUETOOTH_CONNECT in setup.issues) add(DegradedReason.MISSING_BLUETOOTH_CONNECT)
        if (SetupIssue.NOT_BATTERY_EXEMPT in setup.issues) add(DegradedReason.NOT_BATTERY_EXEMPT)
        if (!bluetoothAdapterEnabled) add(DegradedReason.BLUETOOTH_OFF)
    }
    return HeartbeatStatus.of(reasons)
}
