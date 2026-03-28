package eu.darken.octi.server.common

import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.device.DeviceId

fun interface IpAccountLookup {

    data class IpContext(
        val accounts: Set<AccountId>,
        val devices: Set<DeviceId>,
    )

    fun getContextForIp(ip: String): IpContext?
}
