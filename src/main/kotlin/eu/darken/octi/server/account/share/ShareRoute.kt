package eu.darken.octi.server.account.share

import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.device.DeviceRepo
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRoute @Inject constructor(
    private val accountRepo: AccountRepo,
    private val deviceRepo: DeviceRepo,
    private val shareRepo: ShareRepo,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.post("/v1/account/share") {
            try {
                createShare()
            } catch (e: Exception) {
                log(TAG, ERROR) { "createShare failed: ${e.asLog()}" }
                call.respond(HttpStatusCode.InternalServerError, "Share code creation failed")
            }
        }
    }

    private suspend fun RoutingContext.createShare() {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return

        val account = accountRepo.getAccount(callerDevice.accountId)
            ?: throw IllegalStateException("Account not found for $callerDevice")

        val share = shareRepo.createShare(account)
        val response = ShareResponse(code = share.code)
        call.respond(response).also { log(TAG, INFO) { "createShare(${callerDevice.id.shortId()}): Share created" } }
    }

    companion object {
        private val TAG = logTag("Account", "Share", "Route")
    }
}