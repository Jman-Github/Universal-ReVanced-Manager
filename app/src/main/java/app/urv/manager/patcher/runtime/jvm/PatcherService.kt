/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import app.urv.manager.patcher.host.RuntimeProtocol
import app.urv.manager.patcher.runtime.JvmHeapPolicy
import kotlin.concurrent.thread

/** Disposable worker: one ART or embedded JVM run, isolated from the manager. */
open class PatcherService : Service() {
    private var events: ParcelFileDescriptor? = null
    private var launch: JvmLaunch? = null
    private val binder = object : IPatcherService.Stub() {
        @Synchronized
        override fun prepare(launch: JvmLaunch, bundles: Array<String>): Int {
            check(this@PatcherService.launch == null) { "The worker runs once per process" }
            this@PatcherService.launch = launch
            return Process.myPid()
        }

        @Synchronized
        override fun run(requestFile: String, events: ParcelFileDescriptor) {
            val launch = checkNotNull(this@PatcherService.launch) { "The runtime was not prepared" }
            check(this@PatcherService.events == null) { "The worker runs once per process" }
            this@PatcherService.events = events
            thread(name = "BoundPatcherRuntime") {
                if (launch.libjvm.isNotEmpty()) {
                    try {
                        // Reserve in the worker's address space, not in the manager's.
                        val capacity = if (Process.is64Bit()) 0 else EmbeddedJvm.largestReservationMb(1152)
                        val options = launch.options.map { option ->
                            if (!Process.is64Bit() && option.startsWith("-Xmx") && option.endsWith("m")) {
                                val requested = option.removePrefix("-Xmx").removeSuffix("m").toInt()
                                val heap = JvmHeapPolicy.fit32Bit(requested, capacity)
                                check(heap >= 512) { "Insufficient contiguous address space for the embedded JVM" }
                                "-Xmx${heap}m"
                            } else option
                        }
                        val code = EmbeddedJvm.start(launch.copy(options = options),
                            listOf(requestFile, "/proc/self/fd/" + events.fd, launch.entryClass))
                        check(code == 0) { "The embedded JVM could not start ($code)" }
                        // HostMain exits this worker after completion or GC exhaustion.
                        return@thread
                    } catch (error: Throwable) {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(events).bufferedWriter().use { writer ->
                                writer.write(RuntimeProtocol.encode(mapOf(
                                    "type" to "startup_failed",
                                    "error" to (error.message ?: error.javaClass.simpleName)
                                )).toString())
                                writer.newLine()
                                writer.flush()
                            }
                        } finally { Process.killProcess(Process.myPid()) }
                        return@thread
                    }
                }
                try {
                    RuntimeProtocol.run(requestFile, "/proc/self/fd/" + events.fd) { params, send ->
                        android.system.Os.setenv("TMPDIR", params["cacheDir"] as? String ?: cacheDir.path, true)
                        val loader = launch.runtimeClassPath?.let { path ->
                            dalvik.system.DexClassLoader(path, codeCacheDir.path, launch.nativeLibraryDir,
                                classLoader.parent)
                        } ?: classLoader
                        val device = try {
                            loader.loadClass("app.urv.manager.patcher.runtime.RuntimeDevice")
                        } catch (_: ClassNotFoundException) { null }
                        device?.methods?.firstOrNull { it.name == "configure" && it.parameterCount == 1 }
                            ?.invoke(device.getField("INSTANCE").get(null), params["device"])
                        RuntimeProtocol.invoke(loader.loadClass(launch.entryClass), params, send)
                    }
                } finally { Process.killProcess(Process.myPid()) }
            }
        }
    }
    override fun onBind(intent: Intent) = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
class PatcherService1 : PatcherService()
class PatcherService2 : PatcherService()
class PatcherService3 : PatcherService()
