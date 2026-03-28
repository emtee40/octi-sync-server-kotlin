package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.log
import io.ktor.server.application.*
import io.ktor.server.plugins.bodylimit.*

fun Application.installPayloadLimit(limit: Long) {
    log(INFO) { "Payload limit is set to $limit" }
    install(RequestBodyLimit) {
        bodyLimit { limit }
    }
}