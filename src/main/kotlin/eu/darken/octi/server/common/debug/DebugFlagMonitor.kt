package eu.darken.octi.server.common.debug

import eu.darken.octi.server.App
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.ConsoleLogger
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.exists

@Singleton
class DebugFlagMonitor @Inject constructor(
    private val config: App.Config,
    appScope: AppScope,
) {

    private val flagFile = config.dataPath.resolve("debug.flag")

    var checkInterval: Duration = Duration.ofSeconds(10)

    init {
        appScope.launch {
            while (true) {
                delay(checkInterval.toMillis())
                checkFlag()
            }
        }
    }

    internal fun checkFlag() {
        val wantsDebug = flagFile.exists() || config.isDebug
        val currentlyDebug = ConsoleLogger.logLevel == VERBOSE
        if (wantsDebug != currentlyDebug) {
            ConsoleLogger.logLevel = if (wantsDebug) VERBOSE else INFO
            log(TAG, INFO) { "Debug flag file ${if (wantsDebug) "detected" else "removed"}, log level set to ${ConsoleLogger.logLevel}" }
        }
    }

    companion object {
        private val TAG = logTag("Debug", "FlagMonitor")
    }
}
