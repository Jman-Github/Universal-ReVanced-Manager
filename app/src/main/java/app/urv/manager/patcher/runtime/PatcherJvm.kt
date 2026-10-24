/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import app.universal.revanced.manager.BuildConfig
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.patcher.host.RuntimeProtocol
import app.urv.manager.patcher.patch.PatchBundleType
import app.urv.manager.patcher.runtime.jvm.EmbeddedJvm
import app.urv.manager.patcher.runtime.jvm.JvmInstallation
import app.urv.manager.patcher.runtime.jvm.JvmLaunch
import app.urv.manager.util.managerStorageContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One JVM in the main app process; the host isolates engine classes and serializes its runs. */
class PatcherJvm private constructor(private val app: Application, private val fs: Filesystem,
    private val bundles: PatchBundleRepository) {
    private val installation = JvmInstallation(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val runLock = Mutex()
    private var host: Host? = null
    private var startFailure: JvmUnavailableException? = null
    private var expiry: Job? = null
    private val errorFile = app.managerStorageContext.filesDir.resolve("patcher-jvm-error.log")
    private var actualHeapMb = 0

    fun startAhead() = scope.launch {
        if (!installation.isAvailable) return@launch
        try {
            lock.withLock {
                val enabled = bundles.enabledBundlesInfoFlow.first().keys
                val files = bundles.bundlesByType(PatchBundleType.MORPHE).first()
                    .filterKeys { it in enabled }.values.map { it.patchesJar }
                if (files.isEmpty()) return@withLock
                val host = host()
                val installed = installation.install("urv-patcher-core")
                host.send(mapOf("type" to "preload", "host" to installed.hostJar.path,
                    "entry" to RuntimeEngine.MORPHE.entry, "bundles" to files))
                expiry?.cancel()
                expiry = scope.launch {
                    delay(5 * 60_000L)
                    lock.withLock { host.send(mapOf("type" to "preload", "bundles" to emptyList<String>())) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("PatcherJvm", "Could not preload the embedded JVM", error)
        }
    }

    suspend fun run(engine: RuntimeEngine, runtimeClassPath: String?, request: File,
        cancelled: AtomicBoolean, cancelAction: AtomicReference<(() -> Unit)?>,
        onMessage: (String, Map<String, Any?>) -> Unit) = withContext(Dispatchers.IO) {
        runLock.withLock {
            if (cancelled.get()) throw CancellationException("Runtime cancelled")
            val installed = installation.install(requireNotNull(engine.host),
                if (engine == RuntimeEngine.REVANCED21 || engine == RuntimeEngine.REVANCED_LEGACY) runtimeClassPath?.let(::File) else null)
            // Keep the expiry alive until a later preload replaces it. Setup may be cancelled
            // before the host receives a run command and consumes the prepared bundles.
            val host = lock.withLock { host() }
            if (cancelled.get()) throw CancellationException("Runtime cancelled")
            onMessage("log", mapOf("level" to "INFO", "message" to
                ("Runtime mode: in-app JVM (OpenJDK ${BuildConfig.EMBEDDED_JVM_FEATURE}); memory budget: " + actualHeapMb + "MB")))
            onMessage("log", mapOf("level" to "INFO", "message" to
                ("Patcher process: pid=${Process.myPid()} vm=JVM engine=${engine.name} heap=${actualHeapMb}MB " +
                    "bits=${if (Process.is64Bit()) 64 else 32} abis=${android.os.Build.SUPPORTED_ABIS.joinToString(",")} " +
                    "age=${(android.os.SystemClock.elapsedRealtime() - host.startedAt) / 1000}s")))
            onMessage("log", mapOf("level" to "INFO", "message" to
                "Patcher JVM: libjvm=${host.libjvm.substringAfter("/lib/")} options=${host.tuning.joinToString(" ")}"))
            onMessage("log", mapOf("level" to "INFO", "message" to "Memory override: disabled"))
            onMessage("log", mapOf("level" to "INFO", "message" to ("Memory limit: " + actualHeapMb + "MB")))
            if (errorFile.isFile) {
                onMessage("log", mapOf("level" to "ERROR", "message" to errorFile.reader().use { reader ->
                    val buffer = CharArray(262144)
                    val count = reader.read(buffer)
                    if (count > 0) String(buffer, 0, count) else ""
                }))
                errorFile.delete()
            }
            val id = UUID.randomUUID().toString()
            val (events, sink) = ParcelFileDescriptor.createPipe()
            val channel = readEvents(events)
            val cancel: () -> Unit = { host.send(mapOf("type" to "cancel", "id" to id)) }
            cancelAction.set(cancel)
            var sent = false
            var finished = false
            try {
                // Keep the sink alive until the host opens its descriptor in this same process.
                host.send(mapOf("type" to "run", "id" to id, "host" to installed.hostJar.path,
                    "entry" to engine.entry, "request" to request.path, "events" to ("/proc/self/fd/" + sink.fd)))
                sent = true
                if (cancelled.get()) cancel()
                for (event in channel) {
                    val type = event.getValue("type") as String
                    if (type == "finished") {
                        finished = true
                        if (cancelled.get()) throw CancellationException("Runtime cancelled")
                        (event["error"] as? String)?.let { throw BoundRuntimeFailureException(it, originalStackTrace = it) }
                        break
                    }
                    if (cancelled.get()) throw CancellationException("Runtime cancelled")
                    onMessage(type, event)
                }
                check(finished) { "The embedded JVM stopped reporting before the run finished" }
            } finally {
                // Wait before the canceled caller releases its files or another run starts.
                try {
                    if (sent && !finished) withContext(NonCancellable) {
                        runCatching { cancel() }
                        for (event in channel) if (event["type"] == "finished") break
                    }
                } finally {
                    cancelAction.compareAndSet(cancel, null)
                    channel.cancel()
                    events.close()
                    sink.close()
                }
            }
        }
    }

    private fun readEvents(events: ParcelFileDescriptor): Channel<Map<String, Any?>> {
        val channel = Channel<Map<String, Any?>>(64)
        thread(isDaemon = true, name = "JvmEvents") {
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
        return channel
    }

    private suspend fun host(): Host {
        host?.let { return it }
        startFailure?.let { throw it }
        try {
            if (!installation.isAvailable) throw JvmUnavailableException("No embedded JVM for this app ABI")
            val installed = installation.install("urv-patcher-core")
            actualHeapMb = heapLimitMb(app)
            if (!Process.is64Bit()) {
                actualHeapMb = JvmHeapPolicy.fit32Bit(actualHeapMb, EmbeddedJvm.largestReservationMb(1152))
            }
            if (actualHeapMb < 512) throw JvmUnavailableException("Insufficient contiguous address space for the embedded JVM")
            val debug = if (BuildConfig.DEBUG) app.managerStorageContext.filesDir.resolve("jvm-options.txt")
                .takeIf { it.isFile }?.readLines()?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty() else emptyList()
            val tuning = JvmTuning.options(Process.is64Bit()) + debug
            val options = listOf(
                "-Djava.class.path=" + installed.hostJar, "-Djava.io.tmpdir=" + fs.tempDir,
                "-Djdk.lang.Process.launchMechanism=FORK", "-Xmx" + actualHeapMb + "m", "-Xrs",
                "-XX:-UsePerfData", "-XX:ErrorFile=" + errorFile
            ) + tuning
            val (commands, sink) = ParcelFileDescriptor.createPipe()
            val fd = commands.detachFd()
            val launch = JvmLaunch(installed.libjvm.path, installed.preload, options,
                "app/urv/manager/patcher/host/HostMainKt", "")
            val code = try { EmbeddedJvm.start(launch, listOf("/proc/self/fd/" + fd)) }
                catch (error: Throwable) { sink.close(); ParcelFileDescriptor.adoptFd(fd).close(); throw error }
            if (code != 0) {
                sink.close()
                ParcelFileDescriptor.adoptFd(fd).close()
                throw JvmUnavailableException("The embedded JVM could not start (" + code + ")")
            }
            return Host(sink, installed.libjvm.path, tuning).also { host = it }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw (error as? JvmUnavailableException ?: JvmUnavailableException("Could not start the embedded JVM", error))
                .also { startFailure = it }
        }
    }

    private class Host(commands: ParcelFileDescriptor, val libjvm: String, val tuning: List<String>) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        private val writer = ParcelFileDescriptor.AutoCloseOutputStream(commands).bufferedWriter()
        @Synchronized fun send(command: Map<String, Any?>) {
            writer.write(RuntimeProtocol.encode(command).toString())
            writer.newLine()
            writer.flush()
        }
    }

    companion object {
        fun heapLimitMb(context: Context): Int {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            val limit = (info.totalMem / 1048576 / 3).toInt().coerceIn(1024, 3072)
            return if (Process.is64Bit()) limit else limit.coerceAtMost(1024)
        }
        @Volatile private var instance: PatcherJvm? = null
        fun get(context: Context): PatcherJvm = instance ?: synchronized(this) {
            instance ?: run {
                val koin = org.koin.core.context.GlobalContext.get()
                PatcherJvm(context.applicationContext as Application, koin.get(), koin.get())
                    .also { instance = it }
            }
        }
    }
}
