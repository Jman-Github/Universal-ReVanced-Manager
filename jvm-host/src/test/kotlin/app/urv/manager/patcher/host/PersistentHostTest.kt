package app.urv.manager.patcher.host

import kotlinx.coroutines.async
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*
import kotlinx.serialization.json.Json

class PersistentHostTest {
    private fun fixture(dir: File): File {
        val classes = dir.resolve("classes").apply { mkdirs() }
        val device = dir.resolve("RuntimeDevice.java").apply {
            writeText("""
                package app.urv.manager.patcher.runtime;
                import java.util.Map;
                public class RuntimeDevice {
                    public static final RuntimeDevice INSTANCE = new RuntimeDevice();
                    public void configure(Map values) {}
                }
            """.trimIndent())
        }
        val entry = dir.resolve("Entry.java").apply {
            writeText("""
                package fixture;
                import java.util.*;
                import java.io.*;
                import java.util.concurrent.CancellationException;
                public class Entry {
                    public interface Callback {
                        void event(Map<String, Object> event);
                        void log(String level, String message);
                        boolean isCancelled();
                    }
                    private static int count, preloaded;
                    public static void preload(String path) { preloaded++; }
                    public static String runPatcher(Map<String, Object> params, Callback callback) throws Exception {
                        if ("fail".equals(params.get("mode"))) throw new IllegalStateException("fixture failure");
                        if ("block".equals(params.get("mode"))) {
                            new File((String)params.get("started")).createNewFile();
                            while (!callback.isCancelled()) Thread.sleep(10);
                            Thread.sleep(120);
                            new File((String)params.get("cleaned")).createNewFile();
                            throw new CancellationException("fixture cancelled");
                        }
                        if (params.containsKey("cleaned") && !new File((String)params.get("cleaned")).isFile())
                            throw new IllegalStateException("previous run still owns its files");
                        Map<String, Object> result = new HashMap<>();
                        result.put("count", ++count);
                        result.put("preloaded", preloaded);
                        result.put("number", params.get("number"));
                        callback.event(result);
                        return null;
                    }
                }
            """.trimIndent())
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.path, device.path, entry.path))
        return dir.resolve("engine.jar").also { jar ->
            JarOutputStream(jar.outputStream()).use { output ->
                classes.walkTopDown().filter { it.isFile }.forEach { file ->
                    output.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }

    private fun run(host: PersistentHost, dir: File, engine: File, id: String,
        params: Map<String, Any?> = emptyMap()): File {
        val request = dir.resolve("$id.json").apply {
            writeText(RuntimeProtocol.encode(params + ("device" to emptyMap<String, Any?>())).toString())
        }
        val events = dir.resolve("$id.events")
        host.handle(mapOf("type" to "run", "id" to id, "host" to engine.path,
            "entry" to "fixture.Entry", "request" to request.path, "events" to events.path))
        return events
    }
    private fun messages(file: File): List<Map<String, Any?>> =
        if (!file.isFile) emptyList() else file.readLines().mapNotNull {
            @Suppress("UNCHECKED_CAST")
            runCatching { RuntimeProtocol.decode(Json.parseToJsonElement(it)) as Map<String, Any?> }.getOrNull()
        }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for the persistent host" }
            Thread.sleep(10)
        }
    }
    private fun finished(file: File): List<Map<String, Any?>> {
        await { messages(file).lastOrNull()?.get("type") == "finished" }
        return messages(file)
    }

    @Test fun isolatesRunsAndConsumesPreloadsOnce() {
        val dir = Files.createTempDirectory("urv-host-isolation").toFile()
        try {
            val engine = fixture(dir)
            PersistentHost().use { host ->
                host.handle(mapOf("type" to "preload", "host" to engine.path, "entry" to "fixture.Entry",
                    "bundles" to listOf("bundle.jar")))
                for (index in 1..3) {
                    val events = finished(run(host, dir, engine, "run$index", mapOf("number" to 42L)))
                    assertEquals(null, events.last()["error"])
                    val result = events.single { it["type"] == "event" }["event"] as Map<*, *>
                    assertEquals(1, result["count"])
                    assertEquals(if (index == 1) 1 else 0, result["preloaded"])
                    assertEquals(42L, result["number"])
                }
                host.handle(mapOf("type" to "preload", "bundles" to emptyList<String>()))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun switchingEnginesDiscardsTheUnusedPreload() {
        val dir = Files.createTempDirectory("urv-host-switch").toFile()
        try {
            val first = fixture(dir.resolve("first").apply { mkdirs() })
            val second = fixture(dir.resolve("second").apply { mkdirs() })
            PersistentHost().use { host ->
                host.handle(mapOf("type" to "preload", "host" to first.path,
                    "entry" to "fixture.Entry", "bundles" to listOf("bundle.jar")))
                assertEquals(null, finished(run(host, dir, second, "other")).last()["error"])
                val result = finished(run(host, dir, first, "original"))
                    .single { it["type"] == "event" }["event"] as Map<*, *>
                assertEquals(0, result["preloaded"], "An unused engine preload must be discarded when switching engines")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun engineFailureDoesNotEndTheHost() {
        val dir = Files.createTempDirectory("urv-host-failure").toFile()
        try {
            val engine = fixture(dir)
            PersistentHost().use { host ->
                host.handle(mapOf("type" to "preload", "host" to engine.path,
                    "entry" to "missing.Entry", "bundles" to listOf("bundle.jar")))
                val failure = finished(run(host, dir, engine, "failure", mapOf("mode" to "fail")))
                assertTrue((failure.last()["error"] as String).contains("fixture failure"))
                assertEquals(null, finished(run(host, dir, engine, "recovery")).last()["error"])
                Thread.sleep(100)
                assertEquals("finished", messages(dir.resolve("failure.events")).last()["type"])
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationIsRetainedAndNextRunWaitsForCleanup() {
        val dir = Files.createTempDirectory("urv-host-cancel").toFile()
        try {
            val engine = fixture(dir)
            val started = dir.resolve("started")
            val cleaned = dir.resolve("cleaned")
            PersistentHost().use { host ->
                val first = run(host, dir, engine, "first", mapOf("mode" to "block",
                    "started" to started.path, "cleaned" to cleaned.path))
                await { started.isFile }
                val queued = run(host, dir, engine, "queued")
                host.handle(mapOf("type" to "cancel", "id" to "queued"))
                val next = run(host, dir, engine, "next", mapOf("cleaned" to cleaned.path))
                host.handle(mapOf("type" to "cancel", "id" to "first"))
                assertTrue((finished(first).last()["error"] as String).contains("cancelled"))
                assertTrue((finished(queued).last()["error"] as String).contains("cancelled"))
                assertTrue(messages(queued).none { it["type"] == "event" })
                assertEquals(null, finished(next).last()["error"])
                assertTrue(cleaned.isFile)
            }
        } finally { dir.deleteRecursively() }
    }
}

class BlockingCleanupTest {
    @Test fun cancellationWaitsForTheTaskToReleaseItsFiles() = kotlinx.coroutines.runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val cleaned = java.util.concurrent.atomic.AtomicBoolean()
        val task = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            app.urv.manager.patcher.runCancellableBlockingIo {
                started.countDown()
                try {
                    java.util.concurrent.CountDownLatch(1).await()
                } finally {
                    val deadline = System.nanoTime() + 150_000_000L
                    while (System.nanoTime() < deadline) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) {}
                    }
                    cleaned.set(true)
                }
            }
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        task.cancel()
        task.join()
        assertTrue(cleaned.get(), "The cancelled caller returned before blocking I/O released its files")
    }
}
