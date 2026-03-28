package eu.darken.octi.server.common.debug.logging

import java.util.*

fun logTag(vararg tags: String): String {
    val sb = StringBuilder("\uD83D\uDC19:")
    for (i in tags.indices) {
        sb.append(tags[i])
        if (i < tags.size - 1) sb.append(":")
    }
    return sb.toString()
}

fun UUID.shortId(): String = toString().substring(0, 8)