package eu.darken.octi.kserver.common.debug

import eu.darken.octi.kserver.App
import eu.darken.octi.kserver.common.AppScope
import eu.darken.octi.kserver.common.debug.logging.ConsoleLogger
import eu.darken.octi.kserver.common.debug.logging.Logging
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists

class DebugFlagMonitorTest {

    @TempDir
    lateinit var tempDir: Path

    private val originalLogLevel = ConsoleLogger.logLevel

    @BeforeEach
    fun setup() {
        ConsoleLogger.logLevel = Logging.Priority.INFO
    }

    @AfterEach
    fun tearDown() {
        ConsoleLogger.logLevel = originalLogLevel
    }

    private fun createMonitor(): DebugFlagMonitor {
        val config = App.Config(dataPath = tempDir, port = 0)
        return DebugFlagMonitor(config, AppScope())
    }

    @Test
    fun `log level stays INFO when no flag file exists`() {
        val monitor = createMonitor()
        monitor.checkFlag()
        ConsoleLogger.logLevel shouldBe Logging.Priority.INFO
    }

    @Test
    fun `log level switches to VERBOSE when flag file is created`() {
        val monitor = createMonitor()
        tempDir.resolve("debug.flag").createFile()

        monitor.checkFlag()

        ConsoleLogger.logLevel shouldBe Logging.Priority.VERBOSE
    }

    @Test
    fun `log level switches back to INFO when flag file is removed`() {
        val flagFile = tempDir.resolve("debug.flag").createFile()
        val monitor = createMonitor()

        monitor.checkFlag()
        ConsoleLogger.logLevel shouldBe Logging.Priority.VERBOSE

        flagFile.deleteIfExists()
        monitor.checkFlag()

        ConsoleLogger.logLevel shouldBe Logging.Priority.INFO
    }
}
