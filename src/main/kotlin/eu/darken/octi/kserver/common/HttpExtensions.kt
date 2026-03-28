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
import io.ktor.util.*
import java.time.Instant
import java.util.*

val IpDeviceTrackerKey = AttributeKey<IpDeviceTracker>("IpDeviceTracker")

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
    data class Failure(val reason: String, val status: HttpStatusCode) : AuthResult
}

suspend fun authenticateDevice(
    deviceIdHeader: String?,
    authHeader: String?,
    deviceRepo: DeviceRepo,
    clientIp: String? = null,
    ipTracker: IpDeviceTracker? = null,
): AuthResult {
    val deviceId = parseDeviceId(deviceIdHeader)
        ?: return AuthResult.Failure("X-Device-ID header is missing", HttpStatusCode.BadRequest)

    val creds = DeviceCredentials.parseFromHeader(authHeader)
        ?: return AuthResult.Failure("Device credentials are missing", HttpStatusCode.BadRequest)

    val device = deviceRepo.getDevice(deviceId)
        ?: return AuthResult.Failure("Unknown device: $deviceId", HttpStatusCode.NotFound)

    if (!device.isAuthorized(creds)) {
        return AuthResult.Failure("Device credentials not found or insufficient", HttpStatusCode.Unauthorized)
    }

    deviceRepo.updateDevice(device.id) {
        it.copy(lastSeen = Instant.now())
    }

    if (clientIp != null && ipTracker != null) {
        try {
            ipTracker.record(clientIp, creds.accountId, deviceId)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to record IP device tracking: ${e.message}" }
        }
    }

    return AuthResult.Success(deviceId, device)
}

suspend fun RoutingContext.verifyCaller(tag: String, deviceRepo: DeviceRepo): Device? {
    val tracker = call.application.attributes.getOrNull(IpDeviceTrackerKey)
    val result = authenticateDevice(
        deviceIdHeader = call.request.header("X-Device-ID"),
        authHeader = call.request.header("Authorization"),
        deviceRepo = deviceRepo,
        clientIp = call.request.clientIp(),
        ipTracker = tracker,
    )
    return when (result) {
        is AuthResult.Success -> result.device
        is AuthResult.Failure -> {
            log(tag, WARN) { "verifyAuth(): ${result.reason}" }
            call.respond(result.status, result.reason)
            null
        }
    }
}