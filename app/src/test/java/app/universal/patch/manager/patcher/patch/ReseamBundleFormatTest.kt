package app.universal.patch.manager.patcher.patch

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReseamBundleFormatTest {
    private fun checkMarker(marker: String?, expected: Boolean) {
        val file = Files.createTempFile("patches", ".jar").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.toml"))
                zip.write("bundle".toByteArray())
                zip.closeEntry()
                if (marker != null) {
                    zip.putNextEntry(ZipEntry("mimetype"))
                    zip.write(marker.toByteArray())
                    zip.closeEntry()
                }
            }
            assertEquals(expected, ReseamBundleFormat.isBundle(file))
        } finally { file.delete() }
    }

    @Test fun patchRowsHideOnlyTheTrailingSelectionId() {
        assertEquals("Name [variant]", ReseamBundleFormat.patchDisplayName("Name [variant] [patch-id]"))
        assertEquals("Readable name", ReseamBundleFormat.patchDisplayName("Readable name [patch-id]"))
        assertEquals("Readable name", ReseamBundleFormat.patchDisplayName("Readable name"))
    }

    @Test fun detectsImportedArchiveWithoutReseamExtension() =
        checkMarker("application/vnd.reseam.bundle", true)

    @Test fun originalFileNameRoutesToReseamWithoutTreatingItAsVerified() {
        val file = Files.createTempFile("patches", ".jar").toFile()
        try {
            assertTrue(ReseamBundleFormat.isCandidate(file, "PATCHES.RESEAM"))
            assertFalse(ReseamBundleFormat.isBundle(file))
            assertFalse(ReseamBundleFormat.isCandidate(file, "patches.rvp"))
        } finally { file.delete() }
    }

    @Test fun signedCatalogMarkersRouteRenamedOrMalformedBundlesToReseam() {
        val file = Files.createTempFile("patches", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("manifest.toml", "manifest.pubkey", "manifest.sig").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("test".toByteArray())
                    zip.closeEntry()
                }
            }
            assertTrue(ReseamBundleFormat.isCandidate(file))
            assertFalse(ReseamBundleFormat.isBundle(file))
        } finally { file.delete() }
    }

    @Test fun manifestAloneDoesNotIdentifyReseam() {
        val file = Files.createTempFile("patches", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.toml"))
                zip.write("test".toByteArray())
                zip.closeEntry()
            }
            assertFalse(ReseamBundleFormat.isCandidate(file))
        } finally { file.delete() }
    }

    @Test fun rejectsPrefixAndLargeMarkers() {
        checkMarker("application/vnd.reseam.bundle-extra", false)
        checkMarker("application/vnd.reseam.bundle" + "x".repeat(100000), false)
        checkMarker(null, false)
    }
}
