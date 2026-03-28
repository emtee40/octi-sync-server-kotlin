package eu.darken.octi.kserver.common

import eu.darken.octi.kserver.account.AccountId
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.DeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IpDeviceTracker @Inject constructor(
    private val appScope: AppScope,
) : IpAccountLookup {

    data class Config(
        val ttl: Duration = Duration.ofMinutes(5),
        val maxEntries: Int = 10_000,
    )

    internal var config: Config = Config()

    constructor(appScope: AppScope, config: Config) : this(appScope) {
        this.config = config
    }

    internal data class Entry(
        val ip: String,
        val accountId: AccountId,
        val deviceId: DeviceId,
        val lastSeen: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        appScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                cleanup()
            }
        }
    }

    fun record(clientIp: String, accountId: AccountId, deviceId: DeviceId) {
        val key = "$clientIp|$deviceId"
        entries[key] = Entry(clientIp, accountId, deviceId, Instant.now())

        if (entries.size > config.maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.lastSeen }
            if (oldest != null) entries.remove(oldest.key)
        }
    }

    override fun getContextForIp(ip: String): IpAccountLookup.IpContext? {
        val now = Instant.now()
        val cutoff = now.minus(config.ttl)

        val active = entries.values.filter { it.ip == ip && it.lastSeen > cutoff }
        if (active.isEmpty()) return null

        return IpAccountLookup.IpContext(
            accounts = active.map { it.accountId }.toSet(),
            devices = active.map { it.deviceId }.toSet(),
        )
    }

    internal fun cleanup() {
        val cutoff = Instant.now().minus(config.ttl)
        val stale = entries.entries.filter { it.value.lastSeen < cutoff }
        if (stale.isNotEmpty()) {
            stale.forEach { entries.remove(it.key) }
            log(TAG, VERBOSE) { "Cleaned up ${stale.size} expired IP tracking entries" }
        }
    }

    companion object {
        private const val CLEANUP_INTERVAL_MS = 2 * 60 * 1000L
        private val TAG = logTag("IpDeviceTracker")
    }
}
