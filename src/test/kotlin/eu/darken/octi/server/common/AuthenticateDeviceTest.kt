package eu.darken.octi.server.common

import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.*

class AuthenticateDeviceTest {

    private val accountId: UUID = UUID.randomUUID()
    private val deviceId: UUID = UUID.randomUUID()
    private val password = "test-password-12345"

    private lateinit var deviceRepo: DeviceRepo
    private lateinit var ipTracker: IpDeviceTracker
    private lateinit var device: Device

    private val clientIp = "10.0.0.1"

    @BeforeEach
    fun setup() {
        device = Device(
            data = Device.Data(id = deviceId, password = password, version = null),
            path = Path.of("/tmp/test"),
            accountId = accountId,
        )
        deviceRepo = mockk(relaxed = true) {
            coEvery { getDevice(DeviceKey(accountId, deviceId)) } returns device
        }
        ipTracker = mockk(relaxed = true)
    }

    private fun basicAuth(account: UUID = accountId, pw: String = password): String {
        val encoded = Base64.getEncoder().encodeToString("$account:$pw".toByteArray())
        return "Basic $encoded"
    }

    @Test
    fun `success records to tracker`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Success>()
        verify(exactly = 1) { ipTracker.record(clientIp, accountId, deviceId) }
    }

    @Test
    fun `success without tracker does not fail`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = null,
        )

        result.shouldBeInstanceOf<AuthResult.Success>()
    }

    @Test
    fun `success without clientIp does not record`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = null,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Success>()
        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `missing device ID does not record`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = null,
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `missing auth header does not record`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = null,
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `unknown device does not record`() = runTest {
        val unknownId = UUID.randomUUID()
        coEvery { deviceRepo.getDevice(DeviceKey(accountId, unknownId)) } returns null

        val result = authenticateDevice(
            deviceIdHeader = unknownId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `wrong password does not record`() = runTest {
        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(pw = "wrong-password"),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Failure>()
        verify(exactly = 0) { ipTracker.record(any(), any(), any()) }
    }

    @Test
    fun `metadata label passed to updateDevice`() = runTest {
        val actionSlot = slot<(Device.Data) -> Device.Data>()
        coEvery { deviceRepo.updateDevice(any(), capture(actionSlot)) } returns Unit

        authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            metadata = DeviceMetadataPatch(label = "My Phone"),
        )

        coVerify(exactly = 1) { deviceRepo.updateDevice(any(), any()) }
        val updated = actionSlot.captured(device.data)
        updated.label shouldBe "My Phone"
    }

    @Test
    fun `metadata absent preserves existing label`() = runTest {
        val deviceWithLabel = device.copy(data = device.data.copy(label = "Old Label"))
        coEvery { deviceRepo.getDevice(DeviceKey(accountId, deviceId)) } returns deviceWithLabel

        val actionSlot = slot<(Device.Data) -> Device.Data>()
        coEvery { deviceRepo.updateDevice(any(), capture(actionSlot)) } returns Unit

        authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            metadata = DeviceMetadataPatch(),
        )

        val updated = actionSlot.captured(deviceWithLabel.data)
        updated.label shouldBe "Old Label"
    }

    @Test
    fun `tracker exception does not break auth`() = runTest {
        io.mockk.every { ipTracker.record(any(), any(), any()) } throws RuntimeException("tracker broke")

        val result = authenticateDevice(
            deviceIdHeader = deviceId.toString(),
            authHeader = basicAuth(),
            deviceRepo = deviceRepo,
            clientIp = clientIp,
            ipTracker = ipTracker,
        )

        result.shouldBeInstanceOf<AuthResult.Success>()
        result.deviceId shouldBe deviceId
    }
}
