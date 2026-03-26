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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRegistry @Inject constructor(
    private val appScope: AppScope,
) {

    data class Config(
        val maxPerAccount: Int = 64,
        val maxPerIp: Int = 32,
        val maxGlobal: Int = 10_000,
    )

    private var config: Config = Config()

    constructor(appScope: AppScope, config: Config) : this(appScope) {
        this.config = config
    }

    data class DeviceSession(
        val deviceId: DeviceId,
        val accountId: AccountId,
        val clientIp: String,
        val outbox: Channel<String> = Channel(Channel.BUFFERED),
        val connectedAt: Instant = Instant.now(),
    )

    sealed interface RegisterResult {
        data class Accepted(val session: DeviceSession) : RegisterResult
        data class Rejected(val reason: String) : RegisterResult
    }

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

    fun register(deviceId: DeviceId, accountId: AccountId, clientIp: String): RegisterResult {
        // Global limit
        if (sessions.size >= config.maxGlobal) {
            log(TAG, WARN) { "register(): Global connection limit reached ($config.maxGlobal), rejecting device=$deviceId" }
            return RegisterResult.Rejected("Server connection limit reached")
        }

        // Per-IP limit
        val ipCount = sessions.values.count { it.clientIp == clientIp }
        if (ipCount >= config.maxPerIp) {
            log(TAG, WARN) { "register(): Per-IP limit reached ($config.maxPerIp) for ip=$clientIp, rejecting device=$deviceId" }
            return RegisterResult.Rejected("Too many connections from this IP")
        }

        // Per-account limit — evict oldest if exceeded
        val accountSessions = sessions.values.filter { it.accountId == accountId }
        if (accountSessions.size >= config.maxPerAccount) {
            val oldest = accountSessions.minByOrNull { it.connectedAt }
            if (oldest != null) {
                sessions.remove(oldest.deviceId)?.outbox?.close()
                log(TAG, WARN) { "register(): Per-account limit reached ($config.maxPerAccount), evicted oldest device=${oldest.deviceId} for account=$accountId" }
            }
        }

        val deviceSession = DeviceSession(deviceId, accountId, clientIp)
        val old = sessions.put(deviceId, deviceSession)
        if (old != null) {
            old.outbox.close()
            log(TAG, WARN) { "register(): Evicted existing session for device=$deviceId" }
        }
        val stats = stats()
        log(TAG, INFO) { "register(): device=$deviceId, account=$accountId | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
        return RegisterResult.Accepted(deviceSession)
    }

    fun unregister(deviceId: DeviceId) {
        sessions.remove(deviceId)?.outbox?.close()
        val stats = stats()
        log(TAG, INFO) { "unregister(): device=$deviceId | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
    }

    fun getAccountSessions(accountId: AccountId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId }
    }

    fun getAccountPeers(accountId: AccountId, excludeDevice: DeviceId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId && it.deviceId != excludeDevice }
    }

    fun stats(): Stats = Stats(
        totalDevices = sessions.size,
        totalAccounts = sessions.values.map { it.accountId }.distinct().size,
    )

    fun cleanupStaleSessions() {
        val stale = sessions.values.filter { session ->
            session.outbox.isClosedForSend
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
        private val TAG = logTag("WS", "ConnectionRegistry")
    }
}
