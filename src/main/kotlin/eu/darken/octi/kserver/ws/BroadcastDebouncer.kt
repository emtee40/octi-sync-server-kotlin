package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.account.AccountId
import eu.darken.octi.kserver.common.AppScope
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.kserver.common.debug.logging.asLog
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.device.DeviceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastDebouncer @Inject constructor(
    private val appScope: AppScope,
    private val connectionRegistry: ConnectionRegistry,
    private val json: Json,
) {

    @Serializable
    data class EventPayload(
        val events: List<Event>,
    ) {
        @Serializable
        sealed interface Event {
            @Serializable
            @SerialName("module_changed")
            data class ModuleChanged(
                val deviceId: String,
                val moduleId: String,
                val modifiedAt: String,
                val action: String,
            ) : Event
        }
    }

    private data class PendingBroadcast(
        val accountId: AccountId,
        val excludeDevice: DeviceId,
        val events: MutableList<EventPayload.Event>,
        var job: Job,
    )

    private val lock = Mutex()
    private val pending = mutableMapOf<AccountId, PendingBroadcast>()

    suspend fun enqueueModuleChanged(
        accountId: AccountId,
        sourceDeviceId: DeviceId,
        moduleId: String,
        action: String = "updated",
    ) {
        val event = EventPayload.Event.ModuleChanged(
            deviceId = sourceDeviceId.toString(),
            moduleId = moduleId,
            modifiedAt = Instant.now().toString(),
            action = action,
        )

        lock.withLock {
            val existing = pending[accountId]
            if (existing != null) {
                existing.events.add(event)
                existing.job.cancel()
                existing.job = launchBroadcast(accountId)
                log(TAG) { "enqueue(): Merged event for account=$accountId, pending=${existing.events.size}" }
            } else {
                val job = launchBroadcast(accountId)
                pending[accountId] = PendingBroadcast(
                    accountId = accountId,
                    excludeDevice = sourceDeviceId,
                    events = mutableListOf(event),
                    job = job,
                )
                log(TAG) { "enqueue(): New batch for account=$accountId" }
            }
        }
    }

    private fun launchBroadcast(accountId: AccountId): Job = appScope.launch {
        try {
            delay(DEBOUNCE_MS)

            val broadcast = lock.withLock {
                pending.remove(accountId)
            } ?: return@launch

            val payload = json.encodeToString(EventPayload(events = broadcast.events))
            val peers = connectionRegistry.getAccountPeers(accountId, broadcast.excludeDevice)

            if (peers.isEmpty()) {
                log(TAG) { "broadcast(): No peers for account=$accountId, dropping ${broadcast.events.size} events" }
                return@launch
            }

            log(TAG) { "broadcast(): Sending ${broadcast.events.size} events to ${peers.size} peers for account=$accountId" }

            peers.forEach { peer ->
                try {
                    peer.outbox.trySend(payload)
                } catch (e: Exception) {
                    log(TAG, WARN) { "broadcast(): Failed to send to device=${peer.deviceId}: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "broadcast(): Broadcast cancelled for account=$accountId: ${e.message}" }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private val TAG = logTag("WS", "BroadcastDebouncer")
    }
}
