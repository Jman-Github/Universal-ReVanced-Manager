/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import app.urv.manager.patcher.host.RuntimeProtocol
import kotlin.concurrent.thread

/** Disposable ART worker for Android JNI engines and embedded JVM startup fallback. */
open class PatcherService : Service() {
    private var events: ParcelFileDescriptor? = null
    private var launch: JvmLaunch? = null
    private val binder = object : IPatcherService.Stub() {
        @Synchronized
        override fun prepare(launch: JvmLaunch, bundles: Array<String>): Int {
            check(this@PatcherService.launch == null) { "The ART worker runs once per process" }
            require(launch.libjvm.isEmpty()) { "The embedded JVM belongs to the main app process" }
            this@PatcherService.launch = launch
            return Process.myPid()
        }

        @Synchronized
        override fun run(requestFile: String, events: ParcelFileDescriptor) {
            val launch = checkNotNull(this@PatcherService.launch) { "The runtime was not prepared" }
            check(this@PatcherService.events == null) { "The ART worker runs once per process" }
            this@PatcherService.events = events
            thread(name = "BoundArtRuntime") {
                try {
                    RuntimeProtocol.run(requestFile, "/proc/self/fd/" + events.fd) { params, send ->
                        android.system.Os.setenv("TMPDIR", params["cacheDir"] as? String ?: cacheDir.path, true)
                        val loader = launch.runtimeClassPath?.let { path ->
                            dalvik.system.DexClassLoader(path, codeCacheDir.path, launch.nativeLibraryDir,
                                classLoader.parent)
                        } ?: classLoader
                        // Embedded legacy DEX hosts use the same portable device facts as JVM hosts.
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
