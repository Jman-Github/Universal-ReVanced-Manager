package app.urv.manager.patcher.split

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplitManifestInspectorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun apk(split: String? = null, dexFirst: Boolean = false): ByteArray {
        val manifest = AndroidManifestBlock().apply {
            getOrCreateElement("manifest")
            packageName = "test.splits"
            versionCode = 1
            if (split != null) setSplit(split, true)
            refreshFull()
        }.bytes
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                val entries = linkedMapOf<String, ByteArray>()
                if (dexFirst) entries["classes.dex"] = ByteArray(1024 * 1024)
                entries["AndroidManifest.xml"] = manifest
                // Inspection must never load this deliberately invalid resource table.
                entries["resources.arsc"] = byteArrayOf(1, 2, 3)
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File =
        temporary.newFile("bundle.apks").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                }
            }
        }

    @Test fun selectsBaseByManifestInsteadOfFilenameAndDoesNotLoadResources() {
        val directory = temporary.newFolder()
        directory.resolve("base.apk").writeBytes(apk("feature"))
        directory.resolve("app.apk").writeBytes(apk())
        assertEquals(listOf("app.apk", "base.apk"), SplitManifestInspector.directoryOrder(directory))
    }

    @Test fun inspectsNestedManifestsWithoutExtractingFullComponents() {
        val source = archive("splits/config.en.apk" to apk("config.en"), "splits/app.apk" to apk())
        val before = temporary.root.listFiles()!!.map { it.name }.toSet()
        assertEquals(listOf("app.apk", "config.en.apk"),
            SplitManifestInspector.archiveOrder(source, linkedSetOf("splits/config.en.apk", "splits/app.apk")))
        assertEquals(before, temporary.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun baseMetadataCacheSupportsNoncanonicalBaseNames() {
        val base = apk()
        val source = archive("app.apk" to base, "config.en.apk" to apk("config.en"))
        val original = source.readBytes()
        val cached = SplitApkFiles.baseApk(source, temporary.newFolder())
        assertArrayEquals(base, cached.readBytes())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun baseMetadataCacheIgnoresApkResourcesOutsideTheComponentSet() {
        val base = apk()
        val source = archive(
            "splits/app.apk" to base,
            "splits/config.en.apk" to apk("config.en"),
            "assets/base.apk" to byteArrayOf(1, 2, 3)
        )
        val cached = SplitApkFiles.baseApk(
            source,
            temporary.newFolder(),
            SplitApkPreparer.splitApkEntryNames(source)
        )
        assertArrayEquals(base, cached.readBytes())
    }

    @Test fun findsManifestAfterDexEntry() {
        val source = archive("base.apk" to apk(dexFirst = true))
        assertEquals(listOf("base.apk"), SplitManifestInspector.archiveOrder(source, setOf("base.apk")))
    }

    @Test fun cancellationWhileSkippingEarlierDexEntryPropagates() {
        val source = archive("base.apk" to apk(dexFirst = true))
        var checkpoints = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SplitManifestInspector.archiveOrder(source, setOf("base.apk")) {
                if (++checkpoints > 10) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertTrue(source.delete()) // All archive streams have been closed.
    }

    @Test fun rejectsAmbiguousBase() {
        val source = archive("base.apk" to apk(), "other.apk" to apk())
        assertThrows(IllegalArgumentException::class.java) {
            SplitManifestInspector.archiveOrder(source, setOf("base.apk", "other.apk"))
        }
    }

    @Test fun rejectsMissingBaseAndCollidingNames() {
        val source = archive("one/base.apk" to apk("one"), "two/BASE.apk" to apk("two"))
        assertThrows(IllegalArgumentException::class.java) {
            SplitManifestInspector.archiveOrder(source, setOf("one/base.apk", "two/BASE.apk"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplitManifestInspector.archiveOrder(source, setOf("one/base.apk"))
        }
    }
}
