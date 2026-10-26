package app.urv.manager.patcher.reseam

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ReseamRuntimeLoaderCacheTest {
    @Test fun switchingAwayAndBackKeepsTheOriginalNativeOwner() {
        val cache = ReseamRuntimeLoaderCache<Any>()
        val first = cache.getOrCreate(File("runtime-a")) { Any() }
        val second = cache.getOrCreate(File("runtime-b")) { Any() }
        assertNotSame(first, second)
        assertSame(first, cache.getOrCreate(File("runtime-a")) { error("Recreated a JNI owner") })
    }

    @Test fun concurrentLookupsCreateOnlyOneNativeOwner() {
        val cache = ReseamRuntimeLoaderCache<Any>()
        val creations = AtomicInteger()
        val workers = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..32).map {
                workers.submit<Any> { cache.getOrCreate(File("runtime-a")) { creations.incrementAndGet(); Any() } }
            }
            val first = calls.first().get(5, TimeUnit.SECONDS)
            calls.forEach { assertSame(first, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, creations.get())
        } finally { workers.shutdownNow() }
    }

    @Test fun pathAliasesCannotCreateCompetingOwners() {
        val directory = Files.createTempDirectory("reseam-loader-").toFile()
        try {
            val cache = ReseamRuntimeLoaderCache<Any>()
            val first = cache.getOrCreate(directory.resolve("native")) { Any() }
            assertSame(first, cache.getOrCreate(directory.resolve("other/../native")) { error("Aliased JNI owner") })
        } finally { directory.deleteRecursively() }
    }
}
