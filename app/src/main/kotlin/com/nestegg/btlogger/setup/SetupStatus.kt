package com.nestegg.btlogger.setup

enum class SetupIssue {
    MISSING_BLUETOOTH_CONNECT,
    NOT_BATTERY_EXEMPT,
}

data class SetupStatus(
    val bluetoothConnectGranted: Boolean,
    val batteryExempt: Boolean,
) {
    val issues: List<SetupIssue> = buildList {
        if (!bluetoothConnectGranted) add(SetupIssue.MISSING_BLUETOOTH_CONNECT)
        if (!batteryExempt) add(SetupIssue.NOT_BATTERY_EXEMPT)
    }

    val isHealthy: Boolean get() = issues.isEmpty()
}
