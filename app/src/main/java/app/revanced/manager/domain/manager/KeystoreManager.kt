package app.revanced.manager.domain.manager

import android.app.Application
import android.content.Context
import app.revanced.library.ApkSigner as RevancedApkSigner
import com.android.apksig.ApkSigner as AndroidApkSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.days

class KeystoreManager(app: Application, private val prefs: PreferencesManager) {
    companion object Constants {
        /**
         * Default alias and password for the keystore.
         */
        const val DEFAULT = "ReVanced"
        private val eightYearsFromNow get() = Date(System.currentTimeMillis() + (365.days * 8).inWholeMilliseconds * 24)
    }

    private val keystorePath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve("manager.keystore")
    private val credentialsPath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve("keystore.txt")

    private data class KeystoreCredentials(
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val type: String?
    )

    private data class KeyMaterial(
        val alias: String,
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
        val storeType: String
    )

    private suspend fun updatePrefs(alias: String, storePass: String, keyPass: String, type: String?) {
        prefs.edit {
            prefs.keystoreAlias.value = alias
            prefs.keystorePass.value = storePass
            prefs.keystoreKeyPass.value = keyPass
        }
        writeCredentials(alias, storePass, keyPass, type)
    }

    private suspend fun resolveCredentials(): KeystoreCredentials {
        val fileCreds = readCredentials()
        val alias = fileCreds?.alias?.takeIf { it.isNotBlank() } ?: prefs.keystoreAlias.get()
        val storePass = fileCreds?.storePass?.takeIf { it.isNotBlank() } ?: prefs.keystorePass.get()
        val keyPass = fileCreds?.keyPass?.takeIf { it.isNotBlank() } ?: prefs.keystoreKeyPass.get()
        val resolvedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: storePass
        val resolvedStorePass = storePass.takeIf { it.isNotBlank() } ?: resolvedKeyPass
        return KeystoreCredentials(alias, resolvedStorePass, resolvedKeyPass, fileCreds?.type)
    }

    private suspend fun readCredentials(): KeystoreCredentials? = withContext(Dispatchers.IO) {
        if (!credentialsPath.exists()) return@withContext null
        val props = Properties()
        credentialsPath.inputStream().use { props.load(it) }
        val alias = props.getProperty("alias")?.trim().orEmpty()
        val storePass = props.getProperty("storePassword")?.trim()
            ?: props.getProperty("password")?.trim()
            ?: ""
        val keyPass = props.getProperty("keyPassword")?.trim().orEmpty()
        val type = props.getProperty("type")?.trim()?.takeIf { it.isNotEmpty() }
        if (alias.isEmpty() || (storePass.isEmpty() && keyPass.isEmpty())) return@withContext null
        val resolvedKeyPass = if (keyPass.isBlank()) storePass else keyPass
        KeystoreCredentials(alias, storePass, resolvedKeyPass, type)
    }

    private suspend fun writeCredentials(
        alias: String,
        storePass: String,
        keyPass: String,
        type: String?
    ) =
        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                setProperty("alias", alias)
                setProperty("storePassword", storePass)
                setProperty("keyPassword", keyPass)
                setProperty("password", storePass)
                if (!type.isNullOrBlank()) setProperty("type", type)
            }
            credentialsPath.outputStream().use { output ->
                props.store(output, "Keystore credentials")
            }
        }

    suspend fun sign(input: File, output: File) = withContext(Dispatchers.Default) {
        try {
            signWithApksig(input, output)
            return@withContext
        } catch (e: Exception) {
            val sanitized = sanitizeZipIfNeeded(input)
            if (sanitized == input) {
                throw e
            }
            try {
                signWithApksig(sanitized, output)
            } finally {
                sanitized.delete()
            }
        }
    }

    suspend fun regenerate() = withContext(Dispatchers.Default) {
        val keyCertPair = RevancedApkSigner.newPrivateKeyCertificatePair(
            prefs.keystoreAlias.get(),
            eightYearsFromNow
        )
        val ks = RevancedApkSigner.newKeyStore(
            setOf(
                RevancedApkSigner.KeyStoreEntry(
                    DEFAULT, DEFAULT, keyCertPair
                )
            )
        )
        withContext(Dispatchers.IO) {
            keystorePath.outputStream().use {
                ks.store(it, null)
            }
        }

        updatePrefs(DEFAULT, DEFAULT, DEFAULT, "BKS")
    }

    /**
     * Some APKs (often from third-party downloads) contain malformed ZIP headers that trigger
     * ApkSigner errors like "Data Descriptor presence mismatch". Repackage the archive to fix
     * header inconsistencies before signing.
     */
    private suspend fun sanitizeZipIfNeeded(input: File): File = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("apk-sanitized-", ".apk", input.parentFile)
            ZipFile(input).use { zip ->
                ZipOutputStream(tempFile.outputStream()).use { zos ->
                    zip.entries().asSequence().forEach { entry ->
                        val cleanEntry = ZipEntry(entry.name).apply {
                            method = entry.method
                            time = entry.time
                            comment = entry.comment
                            size = entry.size
                            compressedSize = -1 // let ZipOutputStream compute
                            crc = entry.crc
                            extra = entry.extra
                        }
                        zos.putNextEntry(cleanEntry)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { inputStream ->
                                BufferedInputStream(inputStream).copyTo(zos)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            tempFile
        }.getOrElse { input }
    }

    suspend fun import(
        alias: String,
        storePass: String,
        keyPass: String,
        keystore: InputStream
    ): Boolean {
        val keystoreData = withContext(Dispatchers.IO) { keystore.readBytes() }
        val resolvedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: storePass

        val keyMaterial = tryLoadKeyMaterial(keystoreData, alias, storePass, resolvedKeyPass)
            ?: return false

        withContext(Dispatchers.IO) {
            Files.write(keystorePath.toPath(), keystoreData)
        }

        updatePrefs(alias, storePass, resolvedKeyPass, keyMaterial.storeType)
        return true
    }

    fun hasKeystore() = keystorePath.exists()

    suspend fun export(target: OutputStream) {
        withContext(Dispatchers.IO) {
            Files.copy(keystorePath.toPath(), target)
        }
    }

    private fun keyStoreTypes(preferred: String?): List<String> {
        val base = listOf("PKCS12", "JKS", "BKS")
        val preferredNormalized = preferred?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        return (listOfNotNull(preferredNormalized) + base).distinct()
    }

    private fun tryLoadKeyMaterial(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String,
        preferredType: String? = null
    ): KeyMaterial? {
        val keyCandidates = buildList {
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            if (storePass.isNotBlank()) add(storePass.toCharArray())
        }.distinctBy { it.concatToString() }
        if (keyCandidates.isEmpty()) return null
        val storeCandidates = buildList<CharArray?> {
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }
        var lastError: Exception? = null
        for (type in keyStoreTypes(preferredType)) {
            for (storeCandidate in storeCandidates) {
                try {
                    val ks = KeyStore.getInstance(type)
                    ks.load(ByteArrayInputStream(keystoreData), storeCandidate)
                    val aliases = ks.aliases().toList()
                    val aliasCandidates = when {
                        alias.isNotBlank() && aliases.contains(alias) -> listOf(alias)
                        alias.isNotBlank() && aliases.size == 1 -> listOf(aliases.first())
                        alias.isBlank() -> aliases
                        else -> listOf(alias)
                    }
                    for (candidateAlias in aliasCandidates) {
                        for (keyCandidate in keyCandidates) {
                            val privateKey = ks.getKey(candidateAlias, keyCandidate) as? PrivateKey
                                ?: continue
                            val certs = ks.getCertificateChain(candidateAlias)
                                ?.mapNotNull { it as? X509Certificate }
                                ?.takeIf { it.isNotEmpty() }
                                ?: continue
                            return KeyMaterial(candidateAlias, privateKey, certs, type)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        if (lastError is UnrecoverableKeyException || lastError is IllegalArgumentException) {
            return null
        }
        return null
    }

    private suspend fun signWithApksig(input: File, output: File) {
        val credentials = resolveCredentials()
        val keystoreData = withContext(Dispatchers.IO) {
            Files.readAllBytes(keystorePath.toPath())
        }
        val keyMaterial = tryLoadKeyMaterial(
            keystoreData,
            credentials.alias,
            credentials.storePass,
            credentials.keyPass,
            credentials.type
        ) ?: throw IllegalArgumentException("Invalid keystore credentials")

        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            keyMaterial.alias,
            keyMaterial.privateKey,
            keyMaterial.certificates
        ).build()

        AndroidApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }
}
