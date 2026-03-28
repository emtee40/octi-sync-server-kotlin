package eu.darken.octi.server.common

import java.security.SecureRandom


@OptIn(ExperimentalStdlibApi::class)
fun generateRandomKey(length: Int = 64): String {
    val random = SecureRandom()
    val keyBytes = ByteArray(length)
    random.nextBytes(keyBytes)
    return keyBytes.toHexString()
}