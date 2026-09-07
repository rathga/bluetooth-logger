package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupIssue

private const val HEARTBEAT_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

internal fun shouldEmitHeartbeat(nowMillis: Long, lastRecordMillis: Long?): Boolean =
    lastRecordMillis == null || (nowMillis - lastRecordMillis) >= HEARTBEAT_INTERVAL_MILLIS

internal enum class DegradedReason(val wireName: String) {
    DEVICE_STATE_UNREADABLE("state-unreadable"),
    MISSING_BLUETOOTH_CONNECT("perm-missing"),
    NOT_BATTERY_EXEMPT("no-doze-exemption"),
    BLUETOOTH_OFF("bt-off"),
}

internal sealed interface HeartbeatStatus {
    data object Ok : HeartbeatStatus

    data class Degraded(val reasons: List<DegradedReason>) : HeartbeatStatus

    companion object {
        fun of(reasons: List<DegradedReason>): HeartbeatStatus =
            if (reasons.isEmpty()) Ok else Degraded(reasons)
    }
}

internal sealed interface CapturePreconditions {
    data class Measured(
        val setupIssues: List<SetupIssue>,
        val bluetoothAdapterEnabled: Boolean,
    ) : CapturePreconditions

    data object Unreadable : CapturePreconditions
}

internal fun heartbeatStatus(preconditions: CapturePreconditions): HeartbeatStatus =
    HeartbeatStatus.of(
        buildList {
            when (preconditions) {
                CapturePreconditions.Unreadable -> add(DegradedReason.DEVICE_STATE_UNREADABLE)
                is CapturePreconditions.Measured -> {
                    val issues = preconditions.setupIssues
                    if (SetupIssue.MISSING_BLUETOOTH_CONNECT in issues) add(DegradedReason.MISSING_BLUETOOTH_CONNECT)
                    if (SetupIssue.NOT_BATTERY_EXEMPT in issues) add(DegradedReason.NOT_BATTERY_EXEMPT)
                    if (!preconditions.bluetoothAdapterEnabled) add(DegradedReason.BLUETOOTH_OFF)
                }
            }
        },
    )
