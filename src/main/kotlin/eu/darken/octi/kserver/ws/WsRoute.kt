package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.Device
import eu.darken.octi.kserver.device.DeviceCredentials
import eu.darken.octi.kserver.device.DeviceId
import eu.darken.octi.kserver.device.DeviceRepo
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsRoute @Inject constructor(
    private val deviceRepo: DeviceRepo,
    private val connectionRegistry: ConnectionRegistry,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.webSocket("/v1/ws") {
            val result = authenticate() ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return@webSocket
            }

            val connectedAt = Instant.now()
            val deviceSession = connectionRegistry.register(result.deviceId, result.device.accountId)
            log(TAG, INFO) { "Connected: device=${result.deviceId}, account=${result.device.accountId}" }

            // Forward outbox messages to the WebSocket — must happen in session scope
            val forwarder = launch {
                try {
                    for (message in deviceSession.outbox) {
                        send(Frame.Text(message))
                    }
                } catch (e: Exception) {
                    when {
                        e is kotlinx.coroutines.channels.ClosedReceiveChannelException -> {
                            log(TAG) { "Outbox closed for device=${result.deviceId}" }
                        }
                        else -> {
                            log(TAG, WARN) { "Forwarder failed for device=${result.deviceId}: ${e.message}" }
                        }
                    }
                }
            }

            try {
                for (frame in incoming) {
                    // Notification-only: ignore incoming frames from client
                }
            } finally {
                forwarder.cancel()
                connectionRegistry.unregister(result.deviceId)
                val duration = Duration.between(connectedAt, Instant.now())
                val durationStr = formatDuration(duration)
                log(TAG, INFO) { "Disconnected: device=${result.deviceId}, duration=$durationStr" }
            }
        }
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    private data class AuthResult(val deviceId: DeviceId, val device: Device)

    private suspend fun DefaultWebSocketServerSession.authenticate(): AuthResult? {
        val deviceIdHeader = call.request.headers["X-Device-ID"]
        if (deviceIdHeader.isNullOrBlank()) {
            log(TAG, WARN) { "WS auth failed: Missing X-Device-ID header" }
            return null
        }

        val deviceId: DeviceId = try {
            UUID.fromString(deviceIdHeader)
        } catch (e: IllegalArgumentException) {
            log(TAG, WARN) { "WS auth failed: Invalid device ID format: $deviceIdHeader" }
            return null
        }

        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            log(TAG, WARN) { "WS auth failed: Missing/invalid Authorization header for device=$deviceId" }
            return null
        }

        val creds = try {
            val decoded = Base64.getDecoder().decode(authHeader.removePrefix("Basic "))
                .toString(StandardCharsets.UTF_8)
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) return null
            DeviceCredentials(
                accountId = UUID.fromString(parts[0]),
                devicePassword = parts[1],
            )
        } catch (e: Exception) {
            log(TAG, WARN) { "WS auth failed: Credential parse error for device=$deviceId" }
            return null
        }

        val device = deviceRepo.getDevice(deviceId)
        if (device == null) {
            log(TAG, WARN) { "WS auth failed: Unknown device=$deviceId" }
            return null
        }

        if (!device.isAuthorized(creds)) {
            log(TAG, WARN) { "WS auth failed: Unauthorized device=$deviceId" }
            return null
        }

        deviceRepo.updateDevice(device.id) {
            it.copy(lastSeen = Instant.now())
        }

        return AuthResult(deviceId, device)
    }

    companion object {
        private val TAG = logTag("WS", "Route")
    }
}
