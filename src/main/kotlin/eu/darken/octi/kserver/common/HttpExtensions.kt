package eu.darken.octi.kserver.common

import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.shortId
import eu.darken.octi.kserver.device.Device
import eu.darken.octi.kserver.device.DeviceId
import eu.darken.octi.kserver.device.DeviceRepo
import eu.darken.octi.kserver.device.deviceCredentials
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.*

val RoutingCall.headerDeviceId: DeviceId?
    get() = request.header("X-Device-ID")
        ?.takeIf { it.isNotBlank() }
        ?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                log(WARN) { "Invalid device ID" }
                null
            }
        }

suspend fun RoutingContext.verifyCaller(tag: String, deviceRepo: DeviceRepo): Device? {
    val deviceId = call.headerDeviceId

    if (deviceId == null) {
        log(tag, WARN) { "verifyAuth(): Missing header ID" }
        call.respond(HttpStatusCode.BadRequest, "X-Device-ID header is missing")
        return null
    }

    val creds = deviceCredentials
    if (creds == null) {
        log(tag, WARN) { "verifyAuth(${deviceId.shortId()}): credentials missing" }
        call.respond(HttpStatusCode.BadRequest, "Device credentials are missing")
        return null
    }

    // Check credentials
    val device = deviceRepo.getDevice(deviceId)
    if (device == null) {
        log(tag, WARN) { "verifyAuth(${deviceId.shortId()}): not found" }
        call.respond(HttpStatusCode.NotFound, "Unknown device: $deviceId")
        return null
    }

    if (!device.isAuthorized(creds)) {
        log(tag, WARN) { "verifyAuth(${deviceId.shortId()}): credentials not authorized" }
        call.respond(HttpStatusCode.Unauthorized, "Device credentials not found or insufficient")
        return null
    }

    deviceRepo.updateDevice(device.id) {
        it.copy(lastSeen = Instant.now())
    }

    return device
}