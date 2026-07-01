package com.nestegg.btlogger.storage

internal fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (c in value) when {
        c == '\\' -> append("\\\\")
        c == '"' -> append("\\\"")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c.code < 0x20 -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}

internal fun extractLong(line: String, key: String): Long? {
    val needle = "\"$key\":"
    val start = line.indexOf(needle).takeIf { it >= 0 }?.plus(needle.length) ?: return null
    var i = start
    while (i < line.length && (line[i].isDigit() || line[i] == '-')) i++
    return line.substring(start, i).toLongOrNull()
}

internal fun extractInt(line: String, key: String): Int? = extractLong(line, key)?.toInt()

internal fun extractBoolean(line: String, key: String): Boolean? = when {
    line.contains("\"$key\":true") -> true
    line.contains("\"$key\":false") -> false
    else -> null
}

internal fun extractString(line: String, key: String): String? {
    val needle = "\"$key\":\""
    val start = line.indexOf(needle).takeIf { it >= 0 }?.plus(needle.length) ?: return null
    val sb = StringBuilder()
    var i = start
    while (i < line.length) {
        val c = line[i]
        if (c == '\\' && i + 1 < line.length) {
            when (val n = line[i + 1]) {
                '"', '\\', '/' -> sb.append(n)
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 6 > line.length) return null
                    sb.append(line.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(n)
            }
            i += 2
        } else if (c == '"') {
            return sb.toString()
        } else {
            sb.append(c)
            i++
        }
    }
    return null
}

internal fun extractStringOrNull(line: String, key: String): String? {
    val nullNeedle = "\"$key\":null"
    if (line.contains(nullNeedle)) return null
    return extractString(line, key)
}
