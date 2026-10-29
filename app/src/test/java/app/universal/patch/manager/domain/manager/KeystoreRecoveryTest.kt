package app.universal.patch.manager.domain.manager

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeystoreRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    companion object {
        @ClassRule @JvmField val fixture = TemporaryFolder()
        private const val PASSWORD = "fixture-password"
        private lateinit var keyBytes: ByteArray

        @BeforeClass @JvmStatic fun generateSigningKey() {
            val bin = File(System.getProperty("java.home"), "bin")
            val keytool = File(bin, "keytool.exe").takeIf { it.isFile } ?: File(bin, "keytool")
            val key = File(fixture.root, "test-signing-key.p12")
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair", "-alias", "legacy-fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=UPM Recovery Test", "-storetype", "PKCS12",
                "-keystore", key.absolutePath, "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-noprompt"
            ).redirectErrorStream(true).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Timed out generating a disposable signing-key fixture")
            }
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            keyBytes = key.readBytes()
        }

        private fun unlocked(bytes: ByteArray, password: String = PASSWORD): Boolean = runCatching {
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(bytes), password.toCharArray())
            store.getKey("legacy-fixture", password.toCharArray()) != null &&
                store.getCertificate("legacy-fixture") != null
        }.getOrDefault(false)
    }

    @Test fun corruptFirstCandidateDoesNotDestroyTheValidLegacyKey() = runBlocking {
        val corrupt = files.newFile("urv_keystore.keystore").apply { writeText("truncated key") }
        val valid = files.newFile("manager.keystore").apply { writeBytes(keyBytes) }
        val destination = File(files.root, "signing/upm_keystore.keystore")
        val backup = File(files.root, "backup/upm_keystore.keystore")
        val restored = recoverManagerKeystore(listOf(corrupt, valid), destination, ::unlocked) { bytes ->
            assertTrue(unlocked(destination.readBytes()), "Cleanup must follow validated destination persistence")
            backup.parentFile.mkdirs()
            backup.writeBytes(bytes)
            corrupt.delete()
            valid.delete()
        }
        assertTrue(restored)
        assertContentEquals(keyBytes, destination.readBytes())
        assertContentEquals(keyBytes, backup.readBytes())
        assertTrue(unlocked(destination.readBytes()))
        assertFalse(corrupt.exists())
        assertFalse(valid.exists())
    }

    @Test fun wrongCredentialsPreserveEveryCandidateWithoutCreatingANewKey() = runBlocking {
        val original = files.newFile("urv_keystore.keystore").apply { writeBytes(keyBytes) }
        val destination = File(files.root, "signing/upm_keystore.keystore")
        var backupWritten = false
        assertFailsWith<IllegalStateException> {
            recoverManagerKeystore(listOf(original), destination, { unlocked(it, "wrong-password") }) {
                backupWritten = true
            }
        }
        assertContentEquals(keyBytes, original.readBytes())
        assertFalse(destination.exists())
        assertFalse(backupWritten)
    }

    @Test fun absentBackupsAllowNormalFirstLaunchKeyGeneration() = runBlocking {
        val destination = File(files.root, "signing/upm_keystore.keystore")
        val restored = recoverManagerKeystore(listOf(File(files.root, "missing")), destination,
            { error("No bytes should be validated") }, { error("No backup should be written") })
        assertFalse(restored)
        assertFalse(destination.exists())
    }

    @Test fun cancellationCannotRemoveTheOriginalKey() = runBlocking {
        val original = files.newFile("urv_keystore.keystore").apply { writeBytes(keyBytes) }
        val destination = File(files.root, "signing/upm_keystore.keystore")
        assertFailsWith<CancellationException> {
            recoverManagerKeystore(listOf(original), destination,
                { throw CancellationException("cancelled") }, { error("No backup should be written") })
        }
        assertContentEquals(keyBytes, original.readBytes())
        assertFalse(destination.exists())
    }

    @Test fun backupWriteFailureLeavesTheOriginalKeyAvailable() = runBlocking {
        val original = files.newFile("urv_keystore.keystore").apply { writeBytes(keyBytes) }
        val destination = File(files.root, "signing/upm_keystore.keystore")
        assertFailsWith<IOException> {
            recoverManagerKeystore(listOf(original), destination, ::unlocked) {
                throw IOException("Backup storage unavailable")
            }
        }
        assertContentEquals(keyBytes, original.readBytes())
        assertContentEquals(keyBytes, destination.readBytes())
    }
}
