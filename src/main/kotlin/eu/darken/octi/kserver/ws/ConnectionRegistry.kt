package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.account.AccountId
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.DeviceId
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRegistry @Inject constructor() {

    data class DeviceSession(
        val deviceId: DeviceId,
        val accountId: AccountId,
        val outbox: Channel<String> = Channel(Channel.BUFFERED),
    )

    private val sessions = ConcurrentHashMap<DeviceId, DeviceSession>()

    fun register(deviceId: DeviceId, accountId: AccountId): DeviceSession {
        val deviceSession = DeviceSession(deviceId, accountId)
        sessions[deviceId] = deviceSession
        log(TAG) { "register(): device=$deviceId, account=$accountId, total=${sessions.size}" }
        return deviceSession
    }

    fun unregister(deviceId: DeviceId) {
        sessions.remove(deviceId)?.outbox?.close()
        log(TAG) { "unregister(): device=$deviceId, total=${sessions.size}" }
    }

    fun getAccountPeers(accountId: AccountId, excludeDevice: DeviceId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId && it.deviceId != excludeDevice }
    }

    companion object {
        private val TAG = logTag("WS", "ConnectionRegistry")
    }
}
