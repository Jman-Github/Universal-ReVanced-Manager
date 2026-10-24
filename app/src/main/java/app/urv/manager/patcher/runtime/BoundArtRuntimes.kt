/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import android.content.*
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.urv.manager.patcher.runtime.jvm.IPatcherService
import app.urv.manager.patcher.runtime.jvm.JvmLaunch
import app.urv.manager.patcher.runtime.jvm.PatcherService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/** Disposable ART workers for Android JNI engines and JVM startup fallback. */
class BoundArtRuntimes private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Channel<Int>(4).apply { repeat(4) { trySend(it) } }

    suspend fun take(engine: RuntimeEngine, runtimeClassPath: String?, nativeLibraryDir: String?): ArtRuntime {
        val slot = slots.receive()
        val connection = try { PatcherConnection.connect(context, SERVICES[slot]) }
            catch (error: Throwable) { slots.trySend(slot); throw error }
        connection.onClosed = {
            scope.launch {
                while (connection.service.asBinder().isBinderAlive) delay(10)
                slots.send(slot)
            }
        }
        try {
            val pid = connection.service.prepare(JvmLaunch("", emptyList(), emptyList(), "", engine.entry,
                runtimeClassPath, nativeLibraryDir), emptyArray())
            return ArtRuntime(connection, pid)
        } catch (error: Throwable) { connection.close(); throw error }
    }

    class ArtRuntime internal constructor(private val connection: PatcherConnection, val pid: Int) : Closeable {
        fun run(requestFile: String, events: ParcelFileDescriptor) = connection.service.run(requestFile, events)
        override fun close() = connection.close()
    }

    /** A binding to [PatcherService] for a single run, which ends the service's process once closed. */
    internal class PatcherConnection private constructor(private val context: Context) : ServiceConnection, Closeable {
        private var continuation: CancellableContinuation<IPatcherService>? = null
        lateinit var service: IPatcherService
            private set

        @Synchronized
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            continuation?.takeIf { it.isActive }?.resume(IPatcherService.Stub.asInterface(binder))
            continuation = null
        }

        // A process that dies is noticed through the events of the run instead
        override fun onServiceDisconnected(name: ComponentName) = Unit

        override fun onNullBinding(name: ComponentName) = fail("bound to nothing")

        override fun onBindingDied(name: ComponentName) = fail("binding died")

        @Synchronized
        private fun fail(reason: String) {
            continuation?.takeIf { it.isActive }?.resumeWithException(JvmUnavailableException("Patcher service $reason"))
            continuation = null
        }

        var onClosed: () -> Unit = {}
        private val bound = java.util.concurrent.atomic.AtomicBoolean(false)

        @Synchronized
        override fun close() {
            continuation = null
            if (bound.compareAndSet(true, false)) {
                runCatching { context.unbindService(this) }; onClosed()
            }
        }

        companion object {
            suspend fun connect(context: Context, component: Class<out PatcherService>): PatcherConnection {
                val connection = PatcherConnection(context)
                val service = try {
                    withTimeoutOrNull(CONNECT_TIMEOUT) {
                        suspendCancellableCoroutine<IPatcherService> { continuation ->
                            connection.continuation = continuation
                            // Android can register a dispatcher even when bindService returns false.
                            connection.bound.set(true)
                            val bound = context.bindService(
                                Intent(context, component),
                                connection,
                                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                            )
                            if (!bound) connection.fail("could not be bound")
                        }
                    }
                } catch (e: Throwable) {
                    connection.close()
                    throw e
                }

                if (service == null) {
                    connection.close()
                    throw JvmUnavailableException("Patcher service did not connect within $CONNECT_TIMEOUT")
                }
                return connection.apply { this.service = service }
            }
        }
    }

    companion object {
        private val SERVICES = listOf(PatcherService::class.java,
            app.urv.manager.patcher.runtime.jvm.PatcherService1::class.java,
            app.urv.manager.patcher.runtime.jvm.PatcherService2::class.java,
            app.urv.manager.patcher.runtime.jvm.PatcherService3::class.java)
        private val CONNECT_TIMEOUT = 30.seconds
        @Volatile private var instance: BoundArtRuntimes? = null
        fun get(context: Context): BoundArtRuntimes = instance ?: synchronized(this) {
            instance ?: BoundArtRuntimes(context.applicationContext).also { instance = it }
        }
    }
}
