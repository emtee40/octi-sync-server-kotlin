package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.account.AccountId
import eu.darken.octi.kserver.common.AppScope
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.DeviceId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRegistry @Inject constructor(
    private val appScope: AppScope,
) {

    data class DeviceSession(
        val deviceId: DeviceId,
        val accountId: AccountId,
        val outbox: Channel<String> = Channel(Channel.BUFFERED),
        val connectedAt: Instant = Instant.now(),
        @Volatile var lastActivityAt: Instant = Instant.now(),
    )

    data class Stats(
        val totalDevices: Int,
        val totalAccounts: Int,
    )

    private val sessions = ConcurrentHashMap<DeviceId, DeviceSession>()

    init {
        appScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupStaleSessions()
            }
        }
    }

    fun register(deviceId: DeviceId, accountId: AccountId): DeviceSession {
        val deviceSession = DeviceSession(deviceId, accountId)
        sessions[deviceId] = deviceSession
        val stats = stats()
        log(TAG, INFO) { "register(): device=$deviceId, account=$accountId | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
        return deviceSession
    }

    fun unregister(deviceId: DeviceId) {
        sessions.remove(deviceId)?.outbox?.close()
        val stats = stats()
        log(TAG, INFO) { "unregister(): device=$deviceId | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
    }

    fun getAccountPeers(accountId: AccountId, excludeDevice: DeviceId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId && it.deviceId != excludeDevice }
    }

    fun stats(): Stats = Stats(
        totalDevices = sessions.size,
        totalAccounts = sessions.values.map { it.accountId }.distinct().size,
    )

    fun cleanupStaleSessions() {
        val now = Instant.now()
        val stale = sessions.values.filter { session ->
            session.outbox.isClosedForSend ||
                Duration.between(session.lastActivityAt, now) > MAX_IDLE
        }
        if (stale.isNotEmpty()) {
            log(TAG, WARN) { "Cleaning up ${stale.size} stale sessions" }
            stale.forEach { unregister(it.deviceId) }
        }
        val stats = stats()
        log(TAG) { "Cleanup done | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
    }

    companion object {
        private const val CLEANUP_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private val MAX_IDLE: Duration = Duration.ofMinutes(5)
        private val TAG = logTag("WS", "ConnectionRegistry")
    }
}
