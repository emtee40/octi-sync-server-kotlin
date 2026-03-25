package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.common.AppScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SyncNotifierTest {

    private lateinit var notifier: SyncNotifier
    private lateinit var registry: ConnectionRegistry
    private lateinit var appScope: AppScope

    private val accountId: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        appScope = AppScope()
        registry = ConnectionRegistry(appScope)
        notifier = SyncNotifier(appScope, registry, json)
    }

    @Test
    fun `single event is broadcast after debounce delay`() = runBlocking {
        val session = registry.register(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.power")

        val payload = withTimeout(3000) { session.outbox.receive() }
        payload shouldContain "module_changed"
        payload shouldContain "eu.darken.octi.module.power"
        payload shouldContain "updated"
    }

    @Test
    fun `burst writes are batched into single notification`() = runBlocking {
        val session = registry.register(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.power")
        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.meta")
        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.wifi")

        val payload = withTimeout(3000) { session.outbox.receive() }
        val parsed = json.decodeFromString<SyncNotifier.EventPayload>(payload)
        parsed.events.size shouldBe 3
    }

    @Test
    fun `broadcast excludes source device`() = runBlocking {
        val session1 = registry.register(device1, accountId)
        val session2 = registry.register(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.power")

        val payload = withTimeout(3000) { session2.outbox.receive() }
        payload shouldContain "module_changed"

        // Source device should have empty outbox
        session1.outbox.tryReceive().getOrNull() shouldBe null
    }

    @Test
    fun `no peers means no broadcast`() = runBlocking {
        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.power")
        Thread.sleep(1500)
        // No exceptions = success
    }

    @Test
    fun `delete action is included in notification`() = runBlocking {
        val session = registry.register(device2, accountId)

        notifier.enqueueModuleChanged(accountId, device1, "eu.darken.octi.module.power", action = "deleted")

        val payload = withTimeout(3000) { session.outbox.receive() }
        payload shouldContain "deleted"
    }
}
