package eu.darken.octi.kserver.common

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*

class IpDeviceTrackerTest {

    private lateinit var tracker: IpDeviceTracker
    private val accountA: UUID = UUID.randomUUID()
    private val accountB: UUID = UUID.randomUUID()
    private val device1: UUID = UUID.randomUUID()
    private val device2: UUID = UUID.randomUUID()
    private val device3: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        val appScope = AppScope()
        tracker = IpDeviceTracker(appScope, IpDeviceTracker.Config(ttl = Duration.ofMinutes(5)))
    }

    @Test
    fun `record and retrieve for single device`() {
        tracker.record("10.0.0.1", accountA, device1)

        val context = tracker.getContextForIp("10.0.0.1")!!
        context.accounts shouldBe setOf(accountA)
        context.devices shouldBe setOf(device1)
    }

    @Test
    fun `multiple devices on same IP`() {
        tracker.record("10.0.0.1", accountA, device1)
        tracker.record("10.0.0.1", accountA, device2)

        val context = tracker.getContextForIp("10.0.0.1")!!
        context.accounts shouldBe setOf(accountA)
        context.devices.shouldContainExactlyInAnyOrder(device1, device2)
    }

    @Test
    fun `multiple accounts on same IP`() {
        tracker.record("10.0.0.1", accountA, device1)
        tracker.record("10.0.0.1", accountB, device2)

        val context = tracker.getContextForIp("10.0.0.1")!!
        context.accounts.shouldContainExactlyInAnyOrder(accountA, accountB)
        context.devices.shouldContainExactlyInAnyOrder(device1, device2)
    }

    @Test
    fun `different IPs are isolated`() {
        tracker.record("10.0.0.1", accountA, device1)
        tracker.record("10.0.0.2", accountB, device2)

        val ctx1 = tracker.getContextForIp("10.0.0.1")!!
        ctx1.accounts shouldBe setOf(accountA)
        ctx1.devices shouldBe setOf(device1)

        val ctx2 = tracker.getContextForIp("10.0.0.2")!!
        ctx2.accounts shouldBe setOf(accountB)
        ctx2.devices shouldBe setOf(device2)
    }

    @Test
    fun `unknown IP returns null`() {
        tracker.getContextForIp("10.0.0.99").shouldBeNull()
    }

    @Test
    fun `entries expire after TTL`() {
        val shortTtl = IpDeviceTracker.Config(ttl = Duration.ofMillis(1))
        val shortTracker = IpDeviceTracker(AppScope(), shortTtl)

        shortTracker.record("10.0.0.1", accountA, device1)
        Thread.sleep(10)

        shortTracker.getContextForIp("10.0.0.1").shouldBeNull()
    }

    @Test
    fun `cleanup removes expired entries`() {
        val shortTtl = IpDeviceTracker.Config(ttl = Duration.ofMillis(1))
        val shortTracker = IpDeviceTracker(AppScope(), shortTtl)

        shortTracker.record("10.0.0.1", accountA, device1)
        Thread.sleep(10)

        shortTracker.cleanup()
        shortTracker.getContextForIp("10.0.0.1").shouldBeNull()
    }

    @Test
    fun `re-recording updates lastSeen`() {
        val shortTtl = IpDeviceTracker.Config(ttl = Duration.ofMillis(50))
        val shortTracker = IpDeviceTracker(AppScope(), shortTtl)

        shortTracker.record("10.0.0.1", accountA, device1)
        Thread.sleep(30)
        shortTracker.record("10.0.0.1", accountA, device1)
        Thread.sleep(30)

        shortTracker.getContextForIp("10.0.0.1")!!.devices shouldBe setOf(device1)
    }

    @Test
    fun `max entries cap evicts oldest`() {
        val smallCap = IpDeviceTracker.Config(maxEntries = 2)
        val cappedTracker = IpDeviceTracker(AppScope(), smallCap)

        cappedTracker.record("10.0.0.1", accountA, device1)
        cappedTracker.record("10.0.0.2", accountA, device2)
        cappedTracker.record("10.0.0.3", accountB, device3)

        // One of the first two should have been evicted
        val results = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").mapNotNull {
            cappedTracker.getContextForIp(it)
        }
        results.size shouldBe 2
    }
}
