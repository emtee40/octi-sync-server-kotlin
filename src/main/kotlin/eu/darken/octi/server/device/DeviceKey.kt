package eu.darken.octi.server.device

import eu.darken.octi.server.account.AccountId

data class DeviceKey(val accountId: AccountId, val deviceId: DeviceId)
