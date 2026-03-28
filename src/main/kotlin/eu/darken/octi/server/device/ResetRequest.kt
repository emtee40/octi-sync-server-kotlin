package eu.darken.octi.server.device

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ResetRequest(
    val targets: Set<@Contextual DeviceId>,
)