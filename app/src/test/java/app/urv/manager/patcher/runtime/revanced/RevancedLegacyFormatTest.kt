package app.urv.manager.patcher.runtime.revanced

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RevancedLegacyFormatTest {
    private fun bundle(contents: ByteArray, test: (java.io.File) -> Unit) {
        val file = Files.createTempFile("legacy-format", ".jar").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("Example.class"))
                it.write(contents)
                it.closeEntry()
            }
            test(file)
        } finally { file.delete() }
    }

    private fun classHeader(superclass: String): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(0xCAFEBABE.toInt())
                it.writeShort(0); it.writeShort(61); it.writeShort(3)
                it.writeByte(1); it.writeUTF(superclass)
                it.writeByte(7); it.writeShort(1)
                it.writeShort(1); it.writeShort(2); it.writeShort(2)
            }
        }.toByteArray()

    @Test
    fun detectsOldApiAcrossReadBufferBoundary() {
        val marker = "app/revanced/patcher/fingerprint/MethodFingerprint".toByteArray()
        bundle(ByteArray(8180) + marker) { assertTrue(RevancedLegacyFormat.isLegacyBundle(it)) }
    }

    @Test
    fun detectsConstructorBasedPatchesWithoutAnnotations() {
        bundle(classHeader("app/revanced/patcher/patch/ResourcePatch")) {
            assertTrue(RevancedLegacyFormat.isLegacyBundle(it))
        }
    }

    @Test
    fun preservesDslBundlesThatOnlyReferencePatches() {
        bundle(classHeader("java/lang/Object")) {
            assertFalse(RevancedLegacyFormat.isLegacyBundle(it))
        }
    }

    @Test
    fun refusesToMixIncompatibleApisInOnePatchingRun() {
        bundle(classHeader("app/revanced/patcher/patch/BytecodePatch")) { old ->
            bundle(classHeader("java/lang/Object")) { modern ->
                assertFailsWith<IllegalArgumentException> {
                    RevancedLegacyFormat.selectionUsesLegacy(listOf(old.path, modern.path))
                }
                assertTrue(RevancedLegacyFormat.selectionUsesLegacy(listOf(old.path)))
                assertFalse(RevancedLegacyFormat.selectionUsesLegacy(listOf(modern.path)))
            }
        }
    }
}
