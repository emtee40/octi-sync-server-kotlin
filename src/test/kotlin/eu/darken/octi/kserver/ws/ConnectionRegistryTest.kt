package eu.darken.octi.kserver.ws

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class ConnectionRegistryTest {

    private lateinit var registry: ConnectionRegistry
    private val accountA: UUID = UUID.randomUUID()
    private val accountB: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val device3: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        registry = ConnectionRegistry()
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
}
