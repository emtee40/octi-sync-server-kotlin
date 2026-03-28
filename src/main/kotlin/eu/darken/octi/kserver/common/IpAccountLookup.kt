package eu.darken.octi.kserver.common

import eu.darken.octi.kserver.account.AccountId
import eu.darken.octi.kserver.device.DeviceId

fun interface IpAccountLookup {

    data class IpContext(
        val accounts: Set<AccountId>,
        val devices: Set<DeviceId>,
    )

    fun getContextForIp(ip: String): IpContext?
}
