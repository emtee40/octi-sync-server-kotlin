package eu.darken.octi.kserver.ws

import eu.darken.octi.*
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.kserver.common.debug.logging.log
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.*
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class WsFlowTest : TestRunner() {

    /** Like runTest2 but uses runBlocking — WebSocket tests need real time, not virtual time */
    private fun runWsTest(test: suspend TestEnvironment.() -> Unit) {
        val appConfig = baseConfig.copy(port = 16024)
        Files.createDirectories(appConfig.dataPath)
        val app = eu.darken.octi.kserver.App.createComponent(appConfig).application()
        thread { app.launch() }
        while (!app.isRunning()) Thread.sleep(100)

        val client = HttpClient(CIO) {
            defaultRequest {
                url {
                    protocol = URLProtocol.HTTP
                    host = "127.0.0.1"
                    port = appConfig.port
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }

        val env = TestEnvironment(appConfig, app, client)
        try {
            runBlocking { test(env) }
        } finally {
            client.close()
            app.shutdown()
            log(VERBOSE) { "Cleaning up stored data" }
            appConfig.dataPath.deleteRecursively()
        }
    }

    private fun createWsClient(): HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    private fun wsUrl(): String = "ws://127.0.0.1:16024/v1/ws"

    @Test
    fun `connect with valid credentials`() = runWsTest {
        val creds = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds) }) {
            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `connect without credentials fails`() = runWsTest {
        val wsClient = createWsClient()

        var closedWithPolicy = false
        wsClient.webSocket(urlString = wsUrl()) {
            val reason = closeReason.await()
            if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) closedWithPolicy = true
        }
        closedWithPolicy shouldBe true
        wsClient.close()
    }

    @Test
    fun `connect with invalid credentials fails`() = runWsTest {
        createDevice()
        val wsClient = createWsClient()

        var closedWithPolicy = false
        wsClient.webSocket(
            urlString = wsUrl(),
            request = {
                addDeviceId(UUID.randomUUID())
                addAuth(Auth("fake", "fake"))
            }
        ) {
            val reason = closeReason.await()
            if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) closedWithPolicy = true
        }
        closedWithPolicy shouldBe true
        wsClient.close()
    }

    @Test
    fun `receive notification when peer writes module`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)

            writeModule(creds1, "eu.darken.octi.module.power", data = "battery_data")

            // Wait for debounce (500ms) + dispatch
            Thread.sleep(2000)
            val result = incoming.tryReceive()
            val frame = result.getOrNull()
            if (frame == null) {
                val err = result.exceptionOrNull()
                throw AssertionError("No frame received. Channel closed=${result.isClosed}, error=$err")
            }
            val text = (frame as Frame.Text).readText()
            text shouldContain "module_changed"
            text shouldContain "eu.darken.octi.module.power"

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `receive notification when peer deletes module`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        writeModule(creds1, "eu.darken.octi.module.power", data = "data")
        Thread.sleep(1000) // Let debounce from write fire before WS connects

        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)
            deleteModuleRaw(creds1, "eu.darken.octi.module.power")

            Thread.sleep(2000)
            val frame = incoming.tryReceive().getOrNull()
            frame shouldNotBe null
            val text = (frame as Frame.Text).readText()
            text shouldContain "deleted"

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `burst writes produce single batched notification`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)

            writeModule(creds1, "eu.darken.octi.module.power", data = "d1")
            writeModule(creds1, "eu.darken.octi.module.meta", data = "d2")
            writeModule(creds1, "eu.darken.octi.module.wifi", data = "d3")

            Thread.sleep(2000)
            val frame = incoming.tryReceive().getOrNull()
            frame shouldNotBe null
            val payload = Json.decodeFromString<BroadcastDebouncer.EventPayload>(
                (frame as Frame.Text).readText()
            )
            payload.events.size shouldBe 3

            val extra = withTimeoutOrNull(1500) { incoming.receive() }
            extra shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `writer does not receive own notifications`() = runWsTest {
        val creds1 = createDevice()
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds1) }) {
            Thread.sleep(500)
            writeModule(creds1, "eu.darken.octi.module.power", data = "data")

            val frame = withTimeoutOrNull(2000) { incoming.receive() }
            frame shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }

    @Test
    fun `different accounts do not receive each others notifications`() = runWsTest {
        val creds1 = createDevice()
        val creds2 = createDevice() // Different account
        val wsClient = createWsClient()

        wsClient.webSocket(urlString = wsUrl(), request = { addCredentials(creds2) }) {
            Thread.sleep(500)
            writeModule(creds1, "eu.darken.octi.module.power", data = "data")

            val frame = withTimeoutOrNull(2000) { incoming.receive() }
            frame shouldBe null

            close(CloseReason(CloseReason.Codes.NORMAL, "Test done"))
        }
        wsClient.close()
    }
}
