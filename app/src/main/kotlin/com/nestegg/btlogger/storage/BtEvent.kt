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
    HEARTBEAT,
}

internal fun BtEvent.toJsonLine(): String = buildString {
    append('{')
    append("\"ts\":").append(utcTimestamp).append(',')
    append("\"type\":\"").append(eventType.name).append("\",")
    append("\"name\":")
    if (deviceName == null) append("null") else appendJsonString(deviceName)
    append(',')
    append("\"mac\":")
    appendJsonString(deviceMac)
    append('}')
}

internal fun parseJsonLineOrNull(line: String): BtEvent? = runCatching {
    val ts = extractLong(line, "ts") ?: return null
    val typeName = extractString(line, "type") ?: return null
    val type = EventType.entries.firstOrNull { it.name == typeName } ?: return null
    val name = extractStringOrNull(line, "name")
    val mac = extractString(line, "mac") ?: return null
    BtEvent(utcTimestamp = ts, eventType = type, deviceName = name, deviceMac = mac)
}.getOrNull()
