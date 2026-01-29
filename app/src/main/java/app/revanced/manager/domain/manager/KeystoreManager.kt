package app.revanced.manager.domain.manager

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import app.revanced.library.ApkSigner as RevancedApkSigner
import com.android.apksig.ApkSigner as AndroidApkSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.EncryptedPrivateKeyInfo
import kotlin.time.Duration.Companion.days

class KeystoreManager(app: Application, private val prefs: PreferencesManager) {
    companion object Constants {
        /**
         * Default alias and password for the keystore.
         */
        const val DEFAULT = "ReVanced"
        private const val LEGACY_DEFAULT_PASSWORD = "s3cur3p@ssw0rd"
        private const val LOG_TAG = "KeystoreManager"
        private val eightYearsFromNow get() = Date(System.currentTimeMillis() + (365.days * 8).inWholeMilliseconds * 24)
    }

    private val keystorePath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve("manager.keystore")
    private val credentialsPath =
        app.getDir("signing", Context.MODE_PRIVATE).resolve("keystore.txt")
    private val legacyKeystorePath = app.filesDir.resolve("manager.keystore")
    private val backupKeystorePath = app.getExternalFilesDir("keystore")?.resolve("manager.keystore")
    private val keystoreMutex = Mutex()
    private val appContext = app.applicationContext

    private data class KeystoreCredentials(
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val type: String?,
        val fingerprint: String?
    )

    data class KeystoreDiagnostics(
        val keystorePath: String,
        val keystoreSize: Long,
        val credentialsPath: String,
        val credentialsExists: Boolean,
        val backupPath: String?,
        val backupSize: Long?,
        val legacyPath: String,
        val legacySize: Long,
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val type: String?,
        val fingerprint: String?
    )

    private data class KeyMaterial(
        val alias: String,
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
        val storeType: String
    )

    private suspend fun updatePrefs(
        alias: String,
        storePass: String,
        keyPass: String,
        type: String?,
        fingerprint: String?
    ) {
        prefs.edit {
            prefs.keystoreAlias.value = alias
            prefs.keystorePass.value = storePass
            prefs.keystoreKeyPass.value = keyPass
        }
        writeCredentials(alias, storePass, keyPass, type, fingerprint)
    }

    private suspend fun resolveCredentials(): KeystoreCredentials {
        val fileCreds = readCredentials()
        val alias = fileCreds?.alias?.takeIf { it.isNotBlank() } ?: prefs.keystoreAlias.get()
        val storePass = fileCreds?.storePass?.takeIf { it.isNotBlank() } ?: prefs.keystorePass.get()
        val prefKeyPass = prefs.keystoreKeyPass.get()
        val keyPass = fileCreds?.keyPass?.takeIf { it.isNotBlank() }
            ?: prefKeyPass.takeIf { it.isNotBlank() }
        val resolvedKeyPass = when {
            fileCreds == null &&
                prefKeyPass == DEFAULT &&
                storePass.isNotBlank() &&
                storePass != DEFAULT -> storePass
            keyPass.isNullOrBlank() -> storePass
            else -> keyPass
        }
        val resolvedStorePass = storePass.takeIf { it.isNotBlank() } ?: resolvedKeyPass
        return KeystoreCredentials(
            alias,
            resolvedStorePass,
            resolvedKeyPass,
            fileCreds?.type,
            fileCreds?.fingerprint
        )
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
        val fingerprint = props.getProperty("fingerprint")?.trim()?.takeIf { it.isNotEmpty() }
        if (alias.isEmpty() || (storePass.isEmpty() && keyPass.isEmpty())) return@withContext null
        val resolvedKeyPass = if (keyPass.isBlank()) storePass else keyPass
        KeystoreCredentials(alias, storePass, resolvedKeyPass, type, fingerprint)
    }

    private suspend fun writeCredentials(
        alias: String,
        storePass: String,
        keyPass: String,
        type: String?,
        fingerprint: String?
    ) =
        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                setProperty("alias", alias)
                setProperty("storePassword", storePass)
                setProperty("keyPassword", keyPass)
                setProperty("password", storePass)
                if (!type.isNullOrBlank()) setProperty("type", type)
                if (!fingerprint.isNullOrBlank()) setProperty("fingerprint", fingerprint)
            }
            credentialsPath.outputStream().use { output ->
                props.store(output, "Keystore credentials")
            }
        }

    suspend fun sign(input: File, output: File) = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            try {
                signWithApksig(input, output)
                return@withLock
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
    }

    suspend fun regenerate() = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            regenerateLocked()
        }
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
    ): Boolean = withContext(Dispatchers.Default) {
        keystoreMutex.withLock {
            val keystoreData = withContext(Dispatchers.IO) { keystore.readBytes() }
            val resolvedKeyPass = keyPass.takeIf { it.isNotBlank() } ?: storePass

            val keyMaterial = tryLoadKeyMaterial(keystoreData, alias, storePass, resolvedKeyPass)
                ?: run {
                    Log.w(LOG_TAG, "Keystore import failed: unable to load key material for alias=$alias")
                    return@withLock false
                }

            val persistedType = if (keyMaterial.storeType == "JKS") "PKCS12" else keyMaterial.storeType
            val persistedBytes = if (keyMaterial.storeType == "JKS") {
                buildPkcs12Keystore(
                    keyMaterial.alias,
                    resolvedKeyPass,
                    storePass,
                    keyMaterial.privateKey,
                    keyMaterial.certificates
                )
            } else {
                keystoreData
            }

            val fingerprint = keystoreFingerprint(persistedBytes)
            withContext(Dispatchers.IO) {
                Files.write(keystorePath.toPath(), persistedBytes)
                writeBackupKeystore(persistedBytes)
            }

            updatePrefs(keyMaterial.alias, storePass, resolvedKeyPass, persistedType, fingerprint)
            Log.i(LOG_TAG, "Keystore imported (alias=${keyMaterial.alias}, type=$persistedType)")
            true
        }
    }

    fun hasKeystore(): Boolean {
        if (keystorePath.exists() && keystorePath.length() > 0L) return true
        if (backupKeystorePath?.let { it.exists() && it.length() > 0L } == true) return true
        return legacyKeystorePath.exists() && legacyKeystorePath.length() > 0L
    }

    suspend fun export(target: OutputStream) = withContext(Dispatchers.IO) {
        keystoreMutex.withLock {
            requireKeystoreReady()
            Files.copy(keystorePath.toPath(), target)
        }
    }

    suspend fun getDiagnostics(): KeystoreDiagnostics = withContext(Dispatchers.IO) {
        val fileCreds = readCredentials()
        val resolved = resolveCredentials()
        val backupFile = backupKeystorePath
        KeystoreDiagnostics(
            keystorePath = keystorePath.absolutePath,
            keystoreSize = if (keystorePath.exists()) keystorePath.length() else 0L,
            credentialsPath = credentialsPath.absolutePath,
            credentialsExists = credentialsPath.exists(),
            backupPath = backupFile?.absolutePath,
            backupSize = backupFile?.takeIf { it.exists() }?.length(),
            legacyPath = legacyKeystorePath.absolutePath,
            legacySize = if (legacyKeystorePath.exists()) legacyKeystorePath.length() else 0L,
            alias = resolved.alias,
            storePass = resolved.storePass,
            keyPass = resolved.keyPass,
            type = fileCreds?.type,
            fingerprint = fileCreds?.fingerprint
        )
    }

    private suspend fun requireKeystoreReady() {
        if (keystorePath.exists() && keystorePath.length() > 0L) return
        Log.d(LOG_TAG, "Keystore missing at ${keystorePath.absolutePath}; attempting restore")
        if (tryRestoreKeystore()) return
        Log.w(LOG_TAG, "Keystore still missing; user action required")
        throw IllegalStateException("Keystore missing. Regenerate or import it in settings.")
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
        val keyCandidates = buildList<CharArray?> {
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }
        if (keyCandidates.isEmpty()) return null
        val storeCandidates = buildList<CharArray?> {
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }
        for (type in keyStoreTypes(preferredType)) {
            for (storeCandidate in storeCandidates) {
                try {
                    val ks = KeyStore.getInstance(type)
                    ks.load(ByteArrayInputStream(keystoreData), storeCandidate)
                    val aliases = ks.aliases().toList()
                    val aliasCandidates = when {
                        aliases.isEmpty() -> listOf(alias)
                        alias.isNotBlank() && aliases.contains(alias) -> listOf(alias)
                        else -> aliases
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
                    // Try next store/type candidate.
                }
            }
        }

        val jksMaterial = tryLoadJksKeyMaterial(keystoreData, alias, storePass, keyPass)
        if (jksMaterial != null) return jksMaterial

        return null
    }

    private suspend fun signWithApksig(input: File, output: File) {
        requireKeystoreReady()
        val keystoreData = withContext(Dispatchers.IO) {
            Files.readAllBytes(keystorePath.toPath())
        }
        val fingerprint = keystoreFingerprint(keystoreData)
        var credentials = resolveCredentials()
        if (!credentials.fingerprint.isNullOrBlank() && credentials.fingerprint != fingerprint) {
            throw IllegalArgumentException("Keystore changed. Re-import or regenerate it.")
        }

        fun loadStrict(creds: KeystoreCredentials): StrictLoadResult? {
            val signingType = resolveSigningType(creds) ?: return null
            return loadKeyMaterialStrict(
                keystoreData,
                creds.alias,
                creds.storePass,
                creds.keyPass,
                signingType,
                creds.fingerprint.isNullOrBlank()
            )
        }

        var strictResult = loadStrict(credentials)
        if (strictResult == null && credentials.fingerprint.isNullOrBlank()) {
            if (rehydrateCredentials(keystoreData)) {
                credentials = resolveCredentials()
                strictResult = loadStrict(credentials)
            }
        }

        if (strictResult == null) {
            val recovered = recoverManagerKeystoreCredentials(keystoreData)
            if (recovered == null) {
                notifyInvalidKeystoreCredentials()
                throw IllegalArgumentException("Invalid keystore credentials")
            }
            updatePrefs(
                recovered.keyMaterial.alias,
                recovered.storePass,
                recovered.keyPass,
                recovered.keyMaterial.storeType,
                fingerprint
            )
            strictResult = StrictLoadResult(recovered.keyMaterial, null)
        }

        val keyMaterial = strictResult.keyMaterial
        if (credentials.fingerprint.isNullOrBlank() || strictResult.storePassOverride != null) {
            val updatedStorePass = strictResult.storePassOverride ?: credentials.storePass
            updatePrefs(
                keyMaterial.alias,
                updatedStorePass,
                credentials.keyPass,
                keyMaterial.storeType,
                fingerprint
            )
        }

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

    private data class StrictLoadResult(
        val keyMaterial: KeyMaterial,
        val storePassOverride: String?
    )

    private data class RecoveredCredentials(
        val keyMaterial: KeyMaterial,
        val storePass: String,
        val keyPass: String,
    )

    private fun resolveSigningType(credentials: KeystoreCredentials): String? {
        val type = credentials.type?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (type != null) return type
        return if (credentials.alias == DEFAULT) "BKS" else null
    }

    private fun recoverManagerKeystoreCredentials(keystoreData: ByteArray): RecoveredCredentials? {
        val aliasCandidates = listOf(DEFAULT, "alias", "ReVanced Key")
        val passCandidates = listOf(DEFAULT, LEGACY_DEFAULT_PASSWORD, "")
        val types = keyStoreTypes(null)

        for (type in types) {
            for (storePass in passCandidates) {
                val storeChars = storePass.takeIf { it.isNotEmpty() }?.toCharArray()
                val ks = runCatching {
                    KeyStore.getInstance(type).apply {
                        load(ByteArrayInputStream(keystoreData), storeChars)
                    }
                }.getOrNull() ?: continue
                val aliasesInStore = ks.aliases().toList()
                if (aliasesInStore.isEmpty()) continue
                for (alias in aliasCandidates) {
                    val aliasSearch = if (aliasesInStore.contains(alias)) {
                        listOf(alias)
                    } else {
                        aliasesInStore
                    }
                    for (candidateAlias in aliasSearch) {
                        for (keyPass in passCandidates) {
                            val keyChars = keyPass.takeIf { it.isNotEmpty() }?.toCharArray()
                            val privateKey = ks.getKey(candidateAlias, keyChars) as? PrivateKey ?: continue
                            val certs = ks.getCertificateChain(candidateAlias)
                                ?.mapNotNull { it as? X509Certificate }
                                ?.takeIf { it.isNotEmpty() }
                                ?: continue
                            val material = KeyMaterial(candidateAlias, privateKey, certs, type)
                            return RecoveredCredentials(material, storePass, keyPass)
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun notifyInvalidKeystoreCredentials() {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                appContext,
                "Keystore credentials are not correct. Re-import or regenerate the keystore.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun regenerateLocked() {
        Log.i(LOG_TAG, "Regenerating keystore")
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
        val bytes = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { output ->
                ks.store(output, DEFAULT.toCharArray())
                output.toByteArray()
            }
        }
        val fingerprint = keystoreFingerprint(bytes)
        withContext(Dispatchers.IO) {
            Files.write(keystorePath.toPath(), bytes)
            writeBackupKeystore(bytes)
        }

        updatePrefs(DEFAULT, DEFAULT, DEFAULT, "BKS", fingerprint)
        Log.i(LOG_TAG, "Keystore regenerated at ${keystorePath.absolutePath}")
    }

    private suspend fun writeBackupKeystore(bytes: ByteArray) {
        val backupPath = backupKeystorePath ?: return
        withContext(Dispatchers.IO) {
            backupPath.parentFile?.mkdirs()
            Files.write(backupPath.toPath(), bytes)
        }
        Log.d(LOG_TAG, "Keystore backup saved to ${backupPath.absolutePath}")
    }

    private suspend fun tryRestoreKeystore(): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(
            backupKeystorePath?.takeIf { it.exists() && it.length() > 0L },
            legacyKeystorePath.takeIf { it.exists() && it.length() > 0L }
        )
        val source = candidates.firstOrNull() ?: run {
            Log.w(LOG_TAG, "No keystore backup candidates found")
            return@withContext false
        }
        keystorePath.parentFile?.mkdirs()
        Files.copy(source.toPath(), keystorePath.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Log.i(LOG_TAG, "Keystore restored from ${source.absolutePath}")
        val bytes = Files.readAllBytes(keystorePath.toPath())
        if (!rehydrateCredentials(bytes)) {
            Log.w(LOG_TAG, "Restored keystore but could not infer credentials")
        }
        true
    }

    private suspend fun rehydrateCredentials(keystoreData: ByteArray): Boolean {
        val aliasCandidates = listOf(
            prefs.keystoreAlias.get(),
            DEFAULT,
            "alias",
            "ReVanced Key"
        ).filter { it.isNotBlank() }.distinct()
        val passCandidates = listOf(
            prefs.keystorePass.get(),
            prefs.keystoreKeyPass.get(),
            DEFAULT,
            LEGACY_DEFAULT_PASSWORD
        ).filter { it.isNotBlank() }.distinct()
        val fingerprint = keystoreFingerprint(keystoreData)
        val aliasSearch = (aliasCandidates + "").distinct()

        for (alias in aliasSearch) {
            for (storePass in passCandidates) {
                for (keyPass in passCandidates) {
                    val material = tryLoadKeyMaterial(
                        keystoreData,
                        alias,
                        storePass,
                        keyPass
                    ) ?: continue
                    val resolvedStorePass = storePass.ifBlank { keyPass }.trim()
                    val resolvedKeyPass = keyPass.ifBlank { storePass }.trim()
                    if (resolvedStorePass.isBlank() || resolvedKeyPass.isBlank()) continue
                    val persistedType = if (material.storeType == "JKS") "PKCS12" else material.storeType
                    updatePrefs(material.alias, resolvedStorePass, resolvedKeyPass, persistedType, fingerprint)
                    Log.i(
                        LOG_TAG,
                        "Rehydrated keystore credentials (alias=${material.alias}, type=$persistedType)"
                    )
                    return true
                }
            }
        }
        return false
    }

    private fun loadKeyMaterialStrict(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String,
        type: String,
        allowNullStorePassFallback: Boolean
    ): StrictLoadResult? {
        val trimmedAlias = alias.trim()
        if (trimmedAlias.isEmpty()) return null
        fun loadWithStorePass(storePassChars: CharArray?): KeyMaterial? {
            val ks = KeyStore.getInstance(type)
            ks.load(ByteArrayInputStream(keystoreData), storePassChars)
            val privateKey = ks.getKey(
                trimmedAlias,
                keyPass.takeIf { it.isNotBlank() }?.toCharArray()
            ) as? PrivateKey ?: return null
            val certs = ks.getCertificateChain(trimmedAlias)
                ?.mapNotNull { it as? X509Certificate }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return KeyMaterial(trimmedAlias, privateKey, certs, type)
        }

        val primary = runCatching {
            loadWithStorePass(storePass.takeIf { it.isNotBlank() }?.toCharArray())
        }.getOrNull()
        if (primary != null) return StrictLoadResult(primary, null)
        if (!allowNullStorePassFallback || storePass.isBlank()) return null
        val fallback = runCatching { loadWithStorePass(null) }.getOrNull()
        return fallback?.let { StrictLoadResult(it, "") }
    }

    private fun tryLoadJksKeyMaterial(
        keystoreData: ByteArray,
        alias: String,
        storePass: String,
        keyPass: String
    ): KeyMaterial? {
        val entries = readJksEntries(keystoreData) ?: return null
        if (entries.isEmpty()) return null
        val entryCandidates = when {
            alias.isNotBlank() -> entries.filter { it.alias.equals(alias, ignoreCase = true) }
                .ifEmpty { entries }
            else -> entries
        }

        val keyCandidates = buildList<CharArray?> {
            if (keyPass.isNotBlank()) add(keyPass.toCharArray())
            if (storePass.isNotBlank()) add(storePass.toCharArray())
            add(null)
        }.distinctBy { it?.concatToString() }

        for (entry in entryCandidates) {
            val protectedCandidates = protectedKeyCandidates(entry.protectedKey)
            val keyBytes = keyCandidates.firstNotNullOfOrNull { candidate ->
                protectedCandidates.firstNotNullOfOrNull { protectedKey ->
                    unprotectJksKey(protectedKey, candidate)
                }
            } ?: continue
            val algorithm = entry.certificates.firstOrNull()?.publicKey?.algorithm ?: continue
            val privateKey = KeyFactory.getInstance(algorithm)
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            return KeyMaterial(entry.alias, privateKey, entry.certificates, "JKS")
        }
        return null
    }

    private data class JksEntry(
        val alias: String,
        val protectedKey: ByteArray,
        val certificates: List<X509Certificate>
    )

    private fun readJksEntries(data: ByteArray): List<JksEntry>? {
        val input = DataInputStream(ByteArrayInputStream(data))
        val magic = input.readInt()
        if (magic != 0xFEEDFEED.toInt()) return null
        val version = input.readInt()
        if (version != 1 && version != 2) return null
        val entryCount = input.readInt()
        val certFactoryCache = HashMap<String, CertificateFactory>()
        val entries = mutableListOf<JksEntry>()

        repeat(entryCount) {
            when (input.readInt()) {
                1 -> {
                    val alias = input.readUTF()
                    input.readLong()
                    val keyLen = input.readInt()
                    val protectedKey = ByteArray(keyLen).also { input.readFully(it) }
                    val chainLen = input.readInt()
                    val chain = ArrayList<X509Certificate>(chainLen)
                    repeat(chainLen) {
                        val certType = input.readUTF()
                        val certLen = input.readInt()
                        val certBytes = ByteArray(certLen).also { input.readFully(it) }
                        val factory = certFactoryCache.getOrPut(certType) {
                            CertificateFactory.getInstance(certType)
                        }
                        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                        chain.add(cert)
                    }
                    entries.add(JksEntry(alias, protectedKey, chain))
                }
                2 -> {
                    input.readUTF()
                    input.readLong()
                    val certType = input.readUTF()
                    val certLen = input.readInt()
                    input.skipBytes(certLen)
                }
                3 -> {
                    input.readUTF()
                    input.readLong()
                    val keyLen = input.readInt()
                    input.skipBytes(keyLen)
                }
                else -> return null
            }
        }
        return entries
    }

    private fun unprotectJksKey(protectedKey: ByteArray, password: CharArray?): ByteArray? {
        if (protectedKey.size < 40) return null
        val salt = protectedKey.copyOfRange(0, 20)
        val encrypted = protectedKey.copyOfRange(20, protectedKey.size - 20)
        val checksum = protectedKey.copyOfRange(protectedKey.size - 20, protectedKey.size)
        val passwordBytes = jksPasswordBytes(password)
        val md = MessageDigest.getInstance("SHA-1")
        var digest = md.digest(passwordBytes + salt)
        val plain = ByteArray(encrypted.size)
        var offset = 0
        while (offset < encrypted.size) {
            for (i in digest.indices) {
                if (offset >= encrypted.size) break
                plain[offset] = (encrypted[offset].toInt() xor digest[i].toInt()).toByte()
                offset++
            }
            md.reset()
            md.update(passwordBytes)
            md.update(digest)
            digest = md.digest()
        }
        md.reset()
        md.update(passwordBytes)
        md.update(plain)
        val computed = md.digest()
        return if (computed.contentEquals(checksum)) plain else null
    }

    private fun jksPasswordBytes(password: CharArray?): ByteArray {
        if (password == null) return ByteArray(0)
        val bytes = ByteArray(password.size * 2)
        var idx = 0
        for (ch in password) {
            bytes[idx++] = (ch.code shr 8).toByte()
            bytes[idx++] = ch.code.toByte()
        }
        return bytes
    }

    private fun keystoreFingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(String.format("%02x", byte))
        }
        return hex.toString()
    }

    private fun protectedKeyCandidates(raw: ByteArray): List<ByteArray> {
        val extracted = extractEncryptedPrivateKeyData(raw)
        return if (extracted == null || extracted.contentEquals(raw)) {
            listOf(raw)
        } else {
            listOf(extracted, raw)
        }
    }

    private fun extractEncryptedPrivateKeyData(raw: ByteArray): ByteArray? {
        return runCatching {
            val info = EncryptedPrivateKeyInfo(raw)
            info.encryptedData.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun buildPkcs12Keystore(
        alias: String,
        keyPass: String,
        storePass: String,
        privateKey: PrivateKey,
        certificates: List<X509Certificate>
    ): ByteArray {
        val pkcs12 = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                alias,
                privateKey,
                keyPass.toCharArray(),
                certificates.toTypedArray()
            )
        }
        val output = ByteArrayOutputStream()
        pkcs12.store(output, storePass.toCharArray())
        return output.toByteArray()
    }
}
