package eu.darken.octi.kserver.common

import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.Device
import eu.darken.octi.kserver.device.DeviceId
import eu.darken.octi.kserver.device.DeviceRepo
import eu.darken.octi.kserver.device.DeviceCredentials
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.*

private val TAG = logTag("Auth")

fun parseDeviceId(header: String?): DeviceId? {
    if (header.isNullOrBlank()) return null
    return try {
        UUID.fromString(header)
    } catch (e: IllegalArgumentException) {
        log(TAG, WARN) { "Invalid device ID" }
        null
    }
}

val RoutingCall.headerDeviceId: DeviceId?
    get() = parseDeviceId(request.header("X-Device-ID"))

sealed interface AuthResult {
    data class Success(val deviceId: DeviceId, val device: Device) : AuthResult
    data class Failure(val reason: String) : AuthResult
}

suspend fun authenticateDevice(
    deviceIdHeader: String?,
    authHeader: String?,
    deviceRepo: DeviceRepo,
): AuthResult {
    val deviceId = parseDeviceId(deviceIdHeader)
        ?: return AuthResult.Failure("X-Device-ID header is missing")

    val creds = DeviceCredentials.parseFromHeader(authHeader)
        ?: return AuthResult.Failure("Device credentials are missing")

    val device = deviceRepo.getDevice(deviceId)
        ?: return AuthResult.Failure("Authentication failed")

    if (!device.isAuthorized(creds)) {
        return AuthResult.Failure("Authentication failed")
    }

    deviceRepo.updateDevice(device.id) {
        it.copy(lastSeen = Instant.now())
    }

    return AuthResult.Success(deviceId, device)
}

suspend fun RoutingContext.verifyCaller(tag: String, deviceRepo: DeviceRepo): Device? {
    val result = authenticateDevice(
        deviceIdHeader = call.request.header("X-Device-ID"),
        authHeader = call.request.header("Authorization"),
        deviceRepo = deviceRepo,
    )
    return when (result) {
        is AuthResult.Success -> result.device
        is AuthResult.Failure -> {
            log(tag, WARN) { "verifyAuth(): ${result.reason}" }
            val statusCode = when (result.reason) {
                "X-Device-ID header is missing" -> HttpStatusCode.BadRequest
                "Device credentials are missing" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.Unauthorized
            }
            call.respond(statusCode, result.reason)
            null
        }
    }
}