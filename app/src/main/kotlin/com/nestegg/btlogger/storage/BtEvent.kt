package com.nestegg.btlogger.storage

data class BtEvent(
    val utcTimestamp: Long,
    val eventType: EventType,
    val deviceName: String?,
    val deviceMac: String,
)

enum class EventType {
    CONNECTED,
    DISCONNECTED,
}
