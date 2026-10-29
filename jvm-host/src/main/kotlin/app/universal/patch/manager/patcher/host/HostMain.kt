/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.host

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223

import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Disposable worker entry point, plus the command-pipe protocol for older managers. */
fun main(args: Array<String>) {
    if (args.size == 3) {
        // A disposable worker owns the VM, so ending an exhausted run leaves the manager alive.
        try {
            // JNI-created threads need the SDK loader for lazy resources and service discovery.
            Thread.currentThread().contextClassLoader = RuntimeProtocol::class.java.classLoader
            val collectors = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
            RuntimeProtocol.runWithGc(args[0], args[1],
                gcMillis = { collectors.map { it.collectionTime }.filter { it >= 0 }.sum() },
                abort = { kotlin.system.exitProcess(0) }
            ) { params, send ->
                val entry = Class.forName(args[2])
                val device = entry.classLoader.loadClass("app.universal.patch.manager.patcher.runtime.RuntimeDevice")
                device.getMethod("configure", Map::class.java)
                    .invoke(device.getField("INSTANCE").get(null), params["device"])
                RuntimeProtocol.invoke(entry, params, send)
            }
        } finally { kotlin.system.exitProcess(0) }
    }
    // Keep the command-pipe entry point compatible with older managers loading plugin hosts.
    PersistentHost().use { host ->
        File(args.single()).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                @Suppress("UNCHECKED_CAST")
                val command = RuntimeProtocol.decode(Json.parseToJsonElement(line)) as Map<String, Any?>
                host.handle(command)
            }
        }
    }
}

internal class PersistentHost : Closeable {
    private val runs = Executors.newSingleThreadExecutor { Thread(it, "PatcherRun") }
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private var prepared: Prepared? = null

    @Synchronized
    fun handle(command: Map<String, Any?>) {
        when (command["type"]) {
            "preload" -> {
                prepared?.close()
                prepared = null
                val bundles = (command["bundles"] as? List<*>)?.map { it as String }.orEmpty()
                if (bundles.isNotEmpty()) {
                    val candidate = try { Prepared(command["host"] as String, command["entry"] as String) }
                        catch (_: Exception) { return } // Preloading is optional; keep serving later runs.
                    try {
                        val thread = Thread.currentThread()
                        val previous = thread.contextClassLoader
                        thread.contextClassLoader = candidate.loader
                        try {
                            candidate.entry.methods.firstOrNull { it.name == "preload" && it.parameterCount == 1 }
                                ?.let { method -> bundles.forEach { method.invoke(null, it) } }
                            prepared = candidate
                        } finally { thread.contextClassLoader = previous }
                    } catch (_: Exception) { candidate.close() }
                } else if (cancellations.isEmpty()) System.gc()
            }
            "run" -> {
                val id = command["id"] as String
                val cancelled = AtomicBoolean()
                check(cancellations.putIfAbsent(id, cancelled) == null) { "Duplicate runtime request" }
                // A preload is used once. Discard unmatched preparation so another engine's
                // patch instances do not retain memory while this run is active.
                val candidate = prepared
                prepared = null
                var warm = candidate?.takeIf {
                    it.host == command["host"] && it.entryName == command["entry"]
                }
                if (warm == null) candidate?.close()
                runs.execute {
                    try {
                        RuntimeProtocol.run(command["request"] as String, command["events"] as String) { params, send ->
                            if (cancelled.get()) throw java.util.concurrent.CancellationException("Patching cancelled")
                            (warm ?: Prepared(command["host"] as String, command["entry"] as String)).use { runtime ->
                                val device = runtime.loader.loadClass("app.universal.patch.manager.patcher.runtime.RuntimeDevice")
                                device.getMethod("configure", Map::class.java)
                                    .invoke(device.getField("INSTANCE").get(null), params["device"])
                                val thread = Thread.currentThread()
                                val previous = thread.contextClassLoader
                                thread.contextClassLoader = runtime.loader
                                try {
                                    RuntimeProtocol.invoke(runtime.entry, params, send, cancelled::get)
                                } finally { thread.contextClassLoader = previous }
                            }
                        }
                    } finally {
                        warm?.close()
                        // The executor still owns this lambda during GC; clear its captured loader.
                        warm = null
                        cancellations.remove(id)
                        // Full collection is when HotSpot returns the previous run's committed heap.
                        System.gc()
                    }
                }
            }
            "cancel" -> cancellations[command["id"] as String]?.set(true)
            else -> error("Unknown runtime command")
        }
    }

    override fun close() {
        synchronized(this) { prepared?.close(); prepared = null }
        cancellations.values.forEach { it.set(true) }
        runs.shutdown()
    }

    private class Prepared(val host: String, val entryName: String) : Closeable {
        // The platform parent prevents v21/v22/Morphe SDKs and Kotlin singletons sharing definitions.
        val loader = URLClassLoader(arrayOf(File(host).toURI().toURL()), ClassLoader.getPlatformClassLoader())
        val entry: Class<*> = try { loader.loadClass(entryName) }
            catch (error: Throwable) { loader.close(); throw error }
        override fun close() = loader.close()
    }
}
