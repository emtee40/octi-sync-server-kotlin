package eu.darken.octi.server.account.share

import eu.darken.octi.server.common.generateRandomKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShareResponse(
    @SerialName("code") val code: ShareCode = generateRandomKey(),
)