package app.urv.manager.patcher.split

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplitApkFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, ByteArray>): File =
        temporary.newFile().also { source ->
            ZipOutputStream(source.outputStream()).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }

    @Test fun extractionKeepsOnlySelectedComponentsAndPreservesTheirBytes() {
        val base = byteArrayOf(0, 4, 8, -1)
        val split = ByteArray(4096) { it.toByte() }
        val source = archive("nested/base.apk" to base, "nested/config.en.apk" to split,
            "config.fr.apk" to byteArrayOf(2), "manifest.json" to byteArrayOf(3))
        val files = SplitApkFiles.extract(source, temporary.newFolder(),
            setOf("nested/base.apk", "nested/config.en.apk"))
        assertEquals(setOf("base.apk", "config.en.apk"), files.map { it.name }.toSet())
        assertArrayEquals(base, files.single { it.name == "base.apk" }.readBytes())
        assertArrayEquals(split, files.single { it.name == "config.en.apk" }.readBytes())
    }

    @Test fun extractionReportsBytesDuringCopyAndPreservesInput() {
        val bytes = ByteArray(3 * 1024 * 1024) { it.toByte() }
        val source = archive("base.apk" to byteArrayOf(7), "config.en.apk" to bytes)
        val original = source.readBytes()
        val updates = mutableListOf<Pair<Long, Long>>()
        val files = SplitApkFiles.extract(source, temporary.newFolder(), setOf("base.apk", "config.en.apk")) { current, total ->
            updates += current to total
        }
        val expected = bytes.size.toLong() + 1
        assertEquals(0L to expected, updates.first())
        assertEquals(expected to expected, updates.last())
        assertTrue(updates.any { it.first in 1 until expected })
        assertTrue(updates.zipWithNext().all { (a, b) -> a.first <= b.first && a.second == b.second })
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(bytes, files.single { it.name == "config.en.apk" }.readBytes())
    }

    @Test fun packingRoundTripsEveryComponentByteForByte() {
        val directory = temporary.newFolder()
        val base = directory.resolve("base.apk").apply { writeBytes(ByteArray(91) { it.toByte() }) }
        val split = directory.resolve("config.en.apk").apply { writeBytes(ByteArray(2001) { (it * 7).toByte() }) }
        val packed = temporary.newFile("patched.apks")
        SplitApkFiles.pack(listOf(split, base), packed)
        val unpacked = SplitApkFiles.extract(packed, temporary.newFolder(), setOf(base.name, split.name))
        assertEquals("base.apk", unpacked.first().name)
        listOf(base, split).forEach { original ->
            assertArrayEquals(original.readBytes(), unpacked.single { it.name == original.name }.readBytes())
        }
    }

    @Test fun packingReportsCumulativeBytesAfterClosingTheArchive() {
        val directory = temporary.newFolder()
        val base = directory.resolve("base.apk").apply { writeBytes(ByteArray(2 * 1024 * 1024) { it.toByte() }) }
        val split = directory.resolve("config.en.apk").apply { writeBytes(ByteArray(2 * 1024 * 1024) { (it * 3).toByte() }) }
        val output = temporary.newFile("progress.apks")
        val updates = mutableListOf<Pair<Long, Long>>()
        SplitApkFiles.pack(listOf(split, base), output) { current, total ->
            updates += current to total
        }
        val total = base.length() + split.length()
        assertEquals(0L to total, updates.first())
        assertEquals(total to total, updates.last())
        assertTrue(updates.any { it.first in 1 until total })
        assertTrue(updates.zipWithNext().all { (a, b) -> b.first >= a.first && b.second == a.second })
        val extracted = SplitApkFiles.extract(output, temporary.newFolder(), setOf(base.name, split.name))
        listOf(base, split).forEach { apk ->
            assertArrayEquals(apk.readBytes(), extracted.single { it.name == apk.name }.readBytes())
        }
    }

    @Test fun packagingPropagatesCancellationAndClosesItsOutput() {
        val directory = temporary.newFolder()
        val base = directory.resolve("base.apk").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        val output = temporary.newFile("cancelled.apks")
        assertThrows(IllegalStateException::class.java) {
            SplitApkFiles.pack(listOf(base), output) { current, _ ->
                if (current > 0L) error("cancelled")
            }
        }
        assertTrue(output.delete())
        assertTrue(base.isFile)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        SplitApkFiles.extract(archive("../base.apk" to byteArrayOf(1)), temporary.newFolder(), setOf("../base.apk"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateFlattenedNames() {
        val source = archive("one/base.apk" to byteArrayOf(1), "two/BASE.apk" to byteArrayOf(2))
        SplitApkFiles.extract(source, temporary.newFolder(), setOf("one/base.apk", "two/BASE.apk"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingSelectedEntry() {
        SplitApkFiles.extract(archive("base.apk" to byteArrayOf(1)), temporary.newFolder(), setOf("missing.apk"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOutputWithoutBase() {
        val split = temporary.newFile("config.en.apk").apply { writeBytes(byteArrayOf(1)) }
        SplitApkFiles.pack(listOf(split), temporary.newFile())
    }

    @Test fun baseCacheUpdatesWhenArchiveChangesWithSameLengthAndTimestamp() {
        val source = archive("base.apk" to byteArrayOf(1, 2, 3))
        val cache = temporary.newFolder()
        val first = SplitApkFiles.baseApk(source, cache)
        val oldLength = source.length()
        val oldTime = source.lastModified()
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        val replacement = archive("base.apk" to byteArrayOf(4, 5, 6))
        replacement.copyTo(source, overwrite = true)
        assertEquals(oldLength, source.length())
        assertTrue(source.setLastModified(oldTime))
        val second = SplitApkFiles.baseApk(source, cache)
        assertEquals(first, second)
        assertArrayEquals(byteArrayOf(4, 5, 6), second.readBytes())
    }
}
