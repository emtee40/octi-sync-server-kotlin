package eu.darken.octi.kserver.common.debug.logging

import kotlinx.coroutines.asContextElement
import java.security.SecureRandom

object RequestId {
    private val threadLocal = ThreadLocal<String>()

    val current: String
        get() = threadLocal.get() ?: "-"

    fun contextElement(id: String) = threadLocal.asContextElement(id)

    fun generate(): String {
        val bytes = ByteArray(3)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
