package eu.darken.octi.kserver.ws

import eu.darken.octi.kserver.common.AppScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class ConnectionRegistryTest {

    private lateinit var registry: ConnectionRegistry
    private lateinit var appScope: AppScope
    private val accountA: UUID = UUID.randomUUID()
    private val accountB: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val device3: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        appScope = AppScope()
        registry = ConnectionRegistry(appScope)
    }

    @Test
    fun `empty registry returns no peers`() {
        registry.getAccountPeers(accountA, device1).shouldBeEmpty()
    }

    @Test
    fun `register and get peers excludes self`() {
        registry.register(device1, accountA)
        registry.register(device2, accountA)

        val peers = registry.getAccountPeers(accountA, excludeDevice = device1)
        peers shouldHaveSize 1
        peers.first().deviceId shouldBe device2
    }

    @Test
    fun `get peers filters by account`() {
        registry.register(device1, accountA)
        registry.register(device2, accountB)

        registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
        registry.getAccountPeers(accountB, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
    }

    @Test
    fun `unregister removes session`() {
        registry.register(device1, accountA)
        registry.getAccountPeers(accountA, excludeDevice = device2) shouldHaveSize 1

        registry.unregister(device1)
        registry.getAccountPeers(accountA, excludeDevice = device2).shouldBeEmpty()
    }

    @Test
    fun `re-register replaces existing session`() {
        registry.register(device1, accountA)
        registry.register(device1, accountA)

        val peers = registry.getAccountPeers(accountA, excludeDevice = device2)
        peers shouldHaveSize 1
    }

    @Test
    fun `multiple devices on same account`() {
        registry.register(device1, accountA)
        registry.register(device2, accountA)
        registry.register(device3, accountA)

        registry.getAccountPeers(accountA, excludeDevice = device1) shouldHaveSize 2
        registry.getAccountPeers(accountA, excludeDevice = device2) shouldHaveSize 2
    }

    @Test
    fun `stats returns correct counts`() {
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 0, totalAccounts = 0)

        registry.register(device1, accountA)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 1, totalAccounts = 1)

        registry.register(device2, accountA)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 2, totalAccounts = 1)

        registry.register(device3, accountB)
        registry.stats() shouldBe ConnectionRegistry.Stats(totalDevices = 3, totalAccounts = 2)
    }

    @Nested
    inner class `zombie cleanup` {

        @Test
        fun `stale session is cleaned up after idle timeout`() {
            val session = registry.register(device1, accountA)
            session.lastActivityAt = Instant.now().minusSeconds(360) // 6 minutes ago

            registry.cleanupStaleSessions()

            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()).shouldBeEmpty()
            registry.stats().totalDevices shouldBe 0
        }

        @Test
        fun `active session is not cleaned up`() {
            val session = registry.register(device1, accountA)
            session.lastActivityAt = Instant.now() // just now

            registry.cleanupStaleSessions()

            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
        }

        @Test
        fun `session with closed outbox is cleaned up`() {
            val session = registry.register(device1, accountA)
            session.outbox.close()

            registry.cleanupStaleSessions()

            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()).shouldBeEmpty()
        }

        @Test
        fun `mixed stale and active sessions`() {
            val stale = registry.register(device1, accountA)
            stale.lastActivityAt = Instant.now().minusSeconds(360)

            val active = registry.register(device2, accountA)
            active.lastActivityAt = Instant.now()

            val closed = registry.register(device3, accountB)
            closed.outbox.close()

            registry.cleanupStaleSessions()

            registry.stats().totalDevices shouldBe 1
            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()) shouldHaveSize 1
            registry.getAccountPeers(accountA, excludeDevice = UUID.randomUUID()).first().deviceId shouldBe device2
        }
    }
}
