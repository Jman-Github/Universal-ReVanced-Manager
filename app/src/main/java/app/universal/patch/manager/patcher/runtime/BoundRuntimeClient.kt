/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import android.content.Context
import android.os.ParcelFileDescriptor
import app.universal.patch.manager.patcher.host.RuntimeProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BoundRuntimeClient(private val context: Context) {
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<BoundArtRuntimes.ArtRuntime?>()
    private val cancelJvm = AtomicReference<(() -> Unit)?>()
    fun cancel() {
        cancelled.set(true)
        runCatching { cancelJvm.get()?.invoke() }
        active.getAndSet(null)?.close()
    }
    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Runtime cancelled")
    }

    suspend fun execute(engine: RuntimeEngine, params: Map<String, Any?>,
        bundles: List<String> = emptyList(), runtimeClassPath: String? = null,
        nativeLibraryDir: String? = null,
        onMessage: (String, Map<String, Any?>) -> Unit) {
        checkCancelled()
        // Keep ownership outside the cancellable dispatcher switch so a canceled write cannot
        // discard the only reference to its temporary file.
        val request = File.createTempFile("runtime-request-", ".json", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                request.writeText(RuntimeProtocol.encode(params + ("device" to deviceFacts())).toString())
            }
            if (engine.host != null && PatcherJvm.get(context).isEnabled()) {
                try {
                    PatcherJvm.get(context).run(engine, runtimeClassPath, request, cancelled, cancelJvm, onMessage)
                    return
                } catch (error: JvmUnavailableException) {
                    checkCancelled()
                    onMessage("log", mapOf("level" to "WARN", "message" to
                        ("JVM unavailable; using a bound ART service: " + error.message +
                            " (cause=" + (error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName) + ")")))
                }
            }
            val runtime = BoundArtRuntimes.get(context).take(engine, runtimeClassPath, nativeLibraryDir)
            active.set(runtime)
            runtime.use {
                checkCancelled()
                onMessage("log", mapOf("level" to "INFO", "message" to
                    "Runtime mode: bound ART service; memory budget: Android managed"))
                onMessage("log", mapOf("level" to "INFO", "message" to
                    ("Patcher process: pid=${runtime.pid} vm=ART engine=${engine.name} heap=Android-managed " +
                        "bits=${if (android.os.Process.is64Bit()) 64 else 32} " +
                        "abis=${android.os.Build.SUPPORTED_ABIS.joinToString(",")} age=0s")))
                onMessage("log", mapOf("level" to "INFO", "message" to "Memory override: disabled"))
                val (events, sink) = ParcelFileDescriptor.createPipe()
                try {
                    sink.use { runtime.run(request.path, it) }
                    relay(events, onMessage)
                } finally { events.close() }
            }
        } finally {
            active.getAndSet(null)?.close()
            withContext(NonCancellable + Dispatchers.IO) { request.delete() }
        }
    }

    private suspend fun relay(events: ParcelFileDescriptor, onMessage: (String, Map<String, Any?>) -> Unit) {
        val channel = Channel<Map<String, Any?>>(64)
        thread(isDaemon = true, name = "RuntimeEvents") {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(events).bufferedReader().useLines { lines ->
                    lines.forEach {
                        @Suppress("UNCHECKED_CAST")
                        val value = RuntimeProtocol.decode(Json.parseToJsonElement(it)) as Map<String, Any?>
                        runBlocking { channel.send(value) }
                    }
                }
                channel.close()
            } catch (error: Exception) { channel.close(error) }
        }
        var started = false
        var memoryReported = false
        try {
            val first = withTimeoutOrNull(30_000L) { channel.receiveCatching().getOrNull() }
            checkCancelled()
            var event = first
                ?: throw JvmUnavailableException("The ART worker did not start reporting within 30 seconds")
            while (true) {
                checkCancelled()
                val type = event.getValue("type") as String
                if (type == "started") started = true
                if (type == "finished") {
                    (event["error"] as? String)?.let { throw BoundRuntimeFailureException(it, originalStackTrace = it) }
                    return
                }
                if (type == "memory" && !memoryReported) {
                    memoryReported = true
                    onMessage("log", mapOf("level" to "INFO", "message" to "Memory limit: ${event["max"]}MB"))
                }
                onMessage(type, event)
                event = channel.receiveCatching().getOrNull() ?: break
            }
        } finally { channel.cancel(); events.close() }
        checkCancelled()
        if (!started) throw JvmUnavailableException("The runtime ended before the job started")
        throw BoundRuntimeFailureException("The runtime ended during the job. Android may have stopped its process.")
    }

    private fun deviceFacts(): Map<String, Any?> = mapOf(
        "sdk" to RuntimeDevice.sdk, "fingerprint" to RuntimeDevice.fingerprint,
        "abis" to RuntimeDevice.supportedAbis, "locales" to RuntimeDevice.locales.map { it.toLanguageTag() },
        "density" to RuntimeDevice.densityDpi)
}