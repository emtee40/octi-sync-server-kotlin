package eu.darken.octi.server.device

import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import io.ktor.server.routing.*
import java.nio.charset.StandardCharsets
import java.util.*

data class DeviceCredentials(
    val accountId: AccountId,
    val devicePassword: String,
) {
    companion object {
        fun parseFromHeader(authHeader: String?): DeviceCredentials? = try {
            if (authHeader == null) return null

            if (!authHeader.startsWith("Basic ")) {
                log(WARN) { "Invalid Authorization scheme (header length: ${authHeader.length})" }
                return null
            }

            val decoded = Base64.getDecoder().decode(authHeader.removePrefix("Basic "))
                .toString(StandardCharsets.UTF_8)
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) return null

            DeviceCredentials(
                accountId = UUID.fromString(parts[0]),
                devicePassword = parts[1],
            )
        } catch (e: Exception) {
            log(WARN) { "Failed to parse credentials" }
            null
        }
    }
}

val RoutingContext.deviceCredentials: DeviceCredentials?
    get() = DeviceCredentials.parseFromHeader(call.request.headers["Authorization"])
