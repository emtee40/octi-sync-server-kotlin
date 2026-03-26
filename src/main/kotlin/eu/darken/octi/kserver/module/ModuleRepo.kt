package eu.darken.octi.kserver.module

import eu.darken.octi.kserver.App
import eu.darken.octi.kserver.common.AppScope
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.kserver.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.kserver.common.debug.logging.asLog
import eu.darken.octi.kserver.common.debug.logging.log
import eu.darken.octi.kserver.common.debug.logging.logTag
import eu.darken.octi.kserver.common.debug.logging.shortId
import eu.darken.octi.kserver.device.Device
import eu.darken.octi.kserver.device.DeviceRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@OptIn(ExperimentalPathApi::class)
@Singleton
class ModuleRepo @Inject constructor(
    appScope: AppScope,
    private val config: App.Config,
    private val serializer: Json,
    private val deviceRepo: DeviceRepo,
) {

    init {
        appScope.launch(Dispatchers.IO) {
            delay(config.moduleGCInterval.toMillis() / 10)
            while (currentCoroutineContext().isActive) {
                deviceRepo.allDevices().forEach { device: Device ->
                    device.sync.withLock {
                        try {
                            if (!device.modulesPath.exists()) return@withLock

                            val now = Instant.now()
                            val staleModules = device.modulesPath.listDirectoryEntries().filter { path ->
                                val metaFile = path.resolve(META_FILENAME)
                                val lastAccessed = metaFile.getLastModifiedTime().toInstant()
                                Duration.between(lastAccessed, now) > config.moduleExpiration
                            }
                            if (staleModules.isNotEmpty()) {
                                log(TAG) { "Deleting ${staleModules.size} stale modules for ${device.id}" }
                                staleModules.forEach { it.deleteRecursively() }
                            }
                        } catch (e: IOException) {
                            log(TAG, ERROR) { "Module expiration check failed for $device\n${e.asLog()}" }
                        }
                    }
                }
                delay(config.moduleGCInterval.toMillis())
            }
        }
    }

    private fun Device.getModulePath(moduleId: ModuleId): Path {
        val digest = MessageDigest.getInstance("SHA-1")
        val hashBytes = digest.digest(moduleId.toByteArray())
        val safeName = hashBytes.joinToString("") { "%02x".format(it) }
        return modulesPath.resolve(safeName)
    }

    suspend fun read(caller: Device, target: Device, moduleId: ModuleId): Module.Read {
        val modulePath = target.getModulePath(moduleId)

        return try {
            target.sync.withLock {
                if (!modulePath.exists()) {
                    Module.Read()
                } else {
                    Module.Read(
                        modifiedAt = modulePath.resolve(BLOB_FILENAME).getLastModifiedTime().toInstant(),
                        payload = modulePath.resolve(BLOB_FILENAME).readBytes(),
                    )
                }
            }.also {
                log(TAG, VERBOSE) { "read(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${it.size}B" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "read(${caller.id}, ${target.id}, $moduleId) failed: ${e.asLog()}" }
            throw e
        }
    }

    suspend fun write(caller: Device, target: Device, moduleId: ModuleId, write: Module.Write) {
        val modulePath = target.getModulePath(moduleId)
        val info = Module.Info(
            id = moduleId,
            source = caller.id,
        )
        try {
            target.sync.withLock {
                modulePath.apply {
                    if (!parent.exists()) parent.createDirectory() // modules dir
                    if (!exists()) createDirectory() // specific module dir
                    resolve(BLOB_FILENAME).writeBytes(write.payload)
                    resolve(META_FILENAME).writeText(serializer.encodeToString(info))
                }
            }
            log(TAG, VERBOSE) { "write(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${write.size}B" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "write(${caller.id}, ${target.id}, $moduleId) failed: ${e.asLog()}" }
            throw e
        }
    }

    suspend fun delete(caller: Device, target: Device, moduleId: ModuleId) {
        log(TAG, VERBOSE) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId)" }
        val modulePath = target.getModulePath(moduleId)

        target.sync.withLock {
            if (!modulePath.exists()) {
                log(TAG) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId): didn't exist" }
                return
            }
            modulePath.deleteRecursively()
            log(TAG) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId): deleted" }
        }
    }

    suspend fun clear(caller: Device, targets: Set<Device>) {
        log(TAG, VERBOSE) { "clear(${caller.id.shortId()}): Wiping ${targets.size} targets" }
        targets.forEach { target ->
            target.sync.withLock {
                if (target.modulesPath.exists()) {
                    target.modulesPath.deleteRecursively()
                }
            }
        }
    }

    private val Device.modulesPath: Path
        get() = path.resolve(MODULES_DIR)

    companion object {
        private const val MODULES_DIR = "modules"
        private const val META_FILENAME = "module.json"
        private const val BLOB_FILENAME = "payload.blob"
        private val TAG = logTag("Module", "Repo")
    }
}