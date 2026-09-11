package app.urv.manager.domain.manager

import app.urv.manager.patcher.split.SplitApkPreparer
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SignatureMetadataInjectorManagerTest {
    @Test
    fun replacesSignatureMetadataWithoutChangingPayload() = withWorkspace { directory ->
        val target = directory.resolve("target.apk")
        val metadata = directory.resolve("metadata.zip")
        val output = directory.resolve("output.apk")
        createZip(
            target,
            linkedMapOf(
                "AndroidManifest.xml" to "manifest".toByteArray(),
                "resources.arsc" to "resources".toByteArray(),
                "classes.dex" to "dex".toByteArray(),
                "lib/arm64-v8a/libsample.so" to ByteArray(257) { it.toByte() },
                "META-INF/MANIFEST.MF" to "old-manifest".toByteArray(),
                "META-INF/CERT.SF" to "old-sf".toByteArray(),
                "META-INF/CERT.RSA" to "old-rsa".toByteArray(),
                "META-INF/services/example.Service" to "service".toByteArray()
            ),
            storedNames = setOf("lib/arm64-v8a/libsample.so")
        )
        createZip(
            metadata,
            linkedMapOf(
                "META-INF/MANIFEST.MF" to "new-manifest".toByteArray(),
                "BNDLTOOL.SF" to "new-sf".toByteArray(),
                "BNDLTOOL.RSA" to "new-rsa".toByteArray()
            )
        )

        val result = SignatureMetadataInjectorArscEngine.execute(
            metadataArchive = metadata,
            targetApk = target,
            outputApk = output,
            mode = SignatureMetadataInjectionMode.REPLACE_EXISTING,
            onProgress = {},
            onLog = {}
        )

        assertEquals(
            listOf(
                "META-INF/MANIFEST.MF",
                "META-INF/BNDLTOOL.SF",
                "META-INF/BNDLTOOL.RSA"
            ),
            result.injectedEntries
        )
        assertEquals(3, result.removedSignatureEntryCount)
        assertTrue(result.skippedEntries.isEmpty())
        assertFalse(SignatureMetadataArchiveEngine.hasApkSigningBlock(output))
        assertPayloadEqual(target, output)
        assertEquals(
            "service",
            readEntry(output, "META-INF/services/example.Service").decodeToString()
        )
        assertFalse(hasEntry(output, "META-INF/CERT.SF"))
        assertFalse(hasEntry(output, "META-INF/CERT.RSA"))
        assertContentEquals(
            "new-sf".toByteArray(),
            readEntry(output, "META-INF/BNDLTOOL.SF")
        )
        assertContentEquals(
            "new-rsa".toByteArray(),
            readEntry(output, "META-INF/BNDLTOOL.RSA")
        )
        ZipFile(output).use { zip ->
            assertEquals(
                ZipEntry.STORED,
                zip.getEntry("lib/arm64-v8a/libsample.so").method
            )
        }
        SignatureMetadataArchiveEngine.requireAlignedStoredEntries(output)
    }

    @Test
    fun addsNewNamesAndSkipsCaseInsensitiveConflicts() = withWorkspace { directory ->
        val target = directory.resolve("target.apk")
        val metadata = directory.resolve("metadata.zip")
        val output = directory.resolve("output.apk")
        createZip(
            target,
            linkedMapOf(
                "AndroidManifest.xml" to "manifest".toByteArray(),
                "classes.dex" to "dex".toByteArray(),
                "META-INF/EXISTING.SF" to "target-sf".toByteArray()
            )
        )
        createZip(
            metadata,
            linkedMapOf(
                "meta-inf/existing.sf" to "incoming-sf".toByteArray(),
                "META-INF/SECOND.RSA" to "incoming-rsa".toByteArray()
            )
        )

        val result = SignatureMetadataInjectorArscEngine.execute(
            metadataArchive = metadata,
            targetApk = target,
            outputApk = output,
            mode = SignatureMetadataInjectionMode.ADD_ALONGSIDE,
            onProgress = {},
            onLog = {}
        )

        assertEquals(listOf("META-INF/SECOND.RSA"), result.injectedEntries)
        assertEquals(listOf("META-INF/existing.sf"), result.skippedEntries)
        assertEquals(0, result.removedSignatureEntryCount)
        assertContentEquals(
            "target-sf".toByteArray(),
            readEntry(output, "META-INF/EXISTING.SF")
        )
        assertContentEquals(
            "incoming-rsa".toByteArray(),
            readEntry(output, "META-INF/SECOND.RSA")
        )
        assertPayloadEqual(target, output)
    }

    @Test
    fun recognizesNestedApkEditorSigningBlockRecords() = withWorkspace { directory ->
        val metadata = directory.resolve("metadata.zip")
        val extractionDirectory = directory.resolve("signing-block")
        val rawRecord = ByteArray(48) { index -> (index * 3).toByte() }
        createZip(
            metadata,
            linkedMapOf(
                "donor/signatures/0_V2.signature.info.bin" to rawRecord
            )
        )

        val inspection = SignatureMetadataArchiveEngine.inspectMetadataArchive(metadata)

        assertTrue(inspection.entries.isEmpty())
        assertEquals(1, inspection.signingBlockEntries.size)
        assertEquals(
            "0_V2.signature.info.bin",
            inspection.signingBlockEntries.single().targetName
        )
        val extracted = SignatureMetadataArchiveEngine.extractApkSigningBlockEntries(
            file = metadata,
            inspection = inspection,
            outputDirectory = extractionDirectory
        )
        assertEquals(1, extracted.size)
        assertContentEquals(rawRecord, extracted.single().readBytes())
    }

    @Test
    fun rejectsDuplicateSigningBlockRecordNames() = withWorkspace { directory ->
        val metadata = directory.resolve("metadata.zip")
        createZip(
            metadata,
            linkedMapOf(
                "first/0_V2.signature.info.bin" to byteArrayOf(1),
                "second/0_v2.signature.info.bin" to byteArrayOf(2)
            )
        )

        assertFailsWith<IllegalArgumentException> {
            SignatureMetadataArchiveEngine.inspectMetadataArchive(metadata)
        }
    }

    @Test
    fun keepsSigningOptionsForMetadataZipSources() = withWorkspace { directory ->
        val source = directory.resolve("metadata.zip")
        createZip(
            source,
            linkedMapOf("META-INF/DONOR.SF" to "signature".toByteArray())
        )
        val analyzer = sourceAnalyzer()

        val info = runBlocking { analyzer.analyzeSignatureSource(source) }

        assertEquals(SignatureMetadataSourceType.METADATA_ZIP, info.sourceType)
        assertFalse(info.sourceType.usesAutomaticSignatureCloning)
        assertEquals(listOf("META-INF/DONOR.SF"), info.entryNames)
    }

    @Test
    fun classifiesApkAndSplitContainersAsAutomaticSignatureDonors() =
        withWorkspace { directory ->
            val donorApk = directory.resolve("donor.apk")
            createZip(
                donorApk,
                linkedMapOf(
                    "AndroidManifest.xml" to "manifest".toByteArray(),
                    "classes.dex" to "dex".toByteArray(),
                    "META-INF/MANIFEST.MF" to "manifest-metadata".toByteArray(),
                    "META-INF/DONOR.SF" to "signature-file".toByteArray(),
                    "META-INF/DONOR.RSA" to "signature-block".toByteArray()
                )
            )
            val splitContainers = listOf("apks", "xapk", "apkm", "zip").map { extension ->
                directory.resolve("donor.$extension").also { container ->
                    createZip(
                        container,
                        linkedMapOf(
                            "base.apk" to donorApk.readBytes(),
                            "split_config.en.apk" to donorApk.readBytes()
                        )
                    )
                }
            }
            val analyzer = sourceAnalyzer()

            val apkInfo = runBlocking { analyzer.analyzeSignatureSource(donorApk) }

            assertEquals(SignatureMetadataSourceType.APK, apkInfo.sourceType)
            assertTrue(apkInfo.sourceType.usesAutomaticSignatureCloning)
            assertEquals("example.package", apkInfo.donorApkInfo?.packageName)
            splitContainers.forEach { splitContainer ->
                val splitInfo = runBlocking {
                    analyzer.analyzeSignatureSource(splitContainer)
                }
                assertEquals(
                    SignatureMetadataSourceType.SPLIT_APK_CONTAINER,
                    splitInfo.sourceType
                )
                assertTrue(splitInfo.sourceType.usesAutomaticSignatureCloning)
                assertEquals(2, splitInfo.apkEntryCount)
                assertEquals("example.package", splitInfo.donorApkInfo?.packageName)
            }
        }

    @Test
    fun preparesSignatureMetadataForEveryDonorSplit() = withWorkspace { directory ->
        val baseApk = directory.resolve("base.apk")
        val configApk = directory.resolve("split_config.en.apk")
        createZip(
            baseApk,
            linkedMapOf(
                "AndroidManifest.xml" to "base-manifest".toByteArray(),
                "META-INF/BASE.SF" to "base-signature".toByteArray()
            )
        )
        createZip(
            configApk,
            linkedMapOf(
                "AndroidManifest.xml" to "config-manifest".toByteArray(),
                "META-INF/CONFIG.SF" to "config-signature".toByteArray()
            )
        )
        val container = directory.resolve("donor.apks")
        createZip(
            container,
            linkedMapOf(
                "splits/base.apk" to baseApk.readBytes(),
                "splits/split_config.en.apk" to configApk.readBytes()
            )
        )

        val sources = runBlocking {
            sourceAnalyzer().prepareSplitDonorModuleSources(
                file = container,
                workspace = directory.resolve("prepared"),
                checkCancelled = {}
            )
        }.associateBy(PreparedSplitSignatureMetadataSource::moduleName)

        assertEquals(setOf("base.apk", "split_config.en.apk"), sources.keys)
        val baseMetadata = sources.getValue("base.apk").metadataArchive
        val configMetadata = sources.getValue("split_config.en.apk").metadataArchive
        assertTrue(hasEntry(baseMetadata, "META-INF/BASE.SF"))
        assertFalse(hasEntry(baseMetadata, "META-INF/CONFIG.SF"))
        assertTrue(hasEntry(configMetadata, "META-INF/CONFIG.SF"))
        assertFalse(hasEntry(configMetadata, "META-INF/BASE.SF"))
    }

    @Test
    fun matchesEachTargetSplitToItsOwnDonorMetadata() = withWorkspace { directory ->
        val baseMetadata = directory.resolve("base-metadata.zip")
        val configMetadata = directory.resolve("config-metadata.zip")
        val donorSources = listOf(
            PreparedSplitSignatureMetadataSource(
                archiveName = "donor/base.apk",
                moduleName = "base.apk",
                metadataArchive = baseMetadata
            ),
            PreparedSplitSignatureMetadataSource(
                archiveName = "donor/split_config.en.apk",
                moduleName = "split_config.en.apk",
                metadataArchive = configMetadata
            )
        )
        val targets = listOf(
            SplitApkPreparer.ExtractedModule(
                archiveName = "target/base.apk",
                name = "base.apk",
                file = directory.resolve("target-base.apk")
            ),
            SplitApkPreparer.ExtractedModule(
                archiveName = "target/split_config.en.apk",
                name = "split_config.en.apk",
                file = directory.resolve("target-config.apk")
            )
        )

        val matches = matchSplitDonorMetadataSources(donorSources, targets)

        assertEquals(
            baseMetadata,
            matches.getValue("target/base.apk").metadataArchive
        )
        assertEquals(
            configMetadata,
            matches.getValue("target/split_config.en.apk").metadataArchive
        )
    }

    @Test
    fun matchesEquivalentSplitsAcrossContainerNamingConventions() =
        withWorkspace { directory ->
            val metadata = directory.resolve("config-metadata.zip")
            val donorIdentity = SplitApkManifestIdentity(
                splitName = "config.arm64_v8a",
                packageName = "donor.package"
            )
            val targetIdentity = SplitApkManifestIdentity(
                splitName = "config.arm64_v8a",
                packageName = "target.package"
            )
            val donorSources = listOf(
                PreparedSplitSignatureMetadataSource(
                    archiveName = "splits/split_config.arm64_v8a.apk",
                    moduleName = "split_config.arm64_v8a.apk",
                    metadataArchive = metadata,
                    manifestIdentity = donorIdentity
                )
            )
            val target = SplitApkPreparer.ExtractedModule(
                archiveName = "config.arm64_v8a.apk",
                name = "config.arm64_v8a.apk",
                file = directory.resolve("target-config.apk")
            )

            val matches = matchSplitDonorMetadataSources(
                donorSources = donorSources,
                targetModules = listOf(target),
                targetManifestIdentities = mapOf(target.archiveName to targetIdentity)
            )

            assertEquals(
                metadata,
                matches.getValue(target.archiveName).metadataArchive
            )
        }

    @Test
    fun rejectsPreservedTargetSplitWithoutMatchingDonorMetadata() =
        withWorkspace { directory ->
            val donorSources = listOf(
                PreparedSplitSignatureMetadataSource(
                    archiveName = "base.apk",
                    moduleName = "base.apk",
                    metadataArchive = directory.resolve("base-metadata.zip")
                )
            )
            val targets = listOf(
                SplitApkPreparer.ExtractedModule(
                    archiveName = "base.apk",
                    name = "base.apk",
                    file = directory.resolve("target-base.apk")
                ),
                SplitApkPreparer.ExtractedModule(
                    archiveName = "split_config.en.apk",
                    name = "split_config.en.apk",
                    file = directory.resolve("target-config.apk")
                )
            )

            assertFailsWith<IllegalArgumentException> {
                matchSplitDonorMetadataSources(donorSources, targets)
            }
        }

    @Test
    fun acceptsStandaloneAndSplitTargets() = withWorkspace { directory ->
        val targetApk = directory.resolve("target.apk")
        createZip(
            targetApk,
            linkedMapOf(
                "AndroidManifest.xml" to "manifest".toByteArray(),
                "classes.dex" to "dex".toByteArray()
            )
        )
        val splitTargets = listOf("apks", "xapk", "apkm", "zip").map { extension ->
            directory.resolve("target.$extension").also { container ->
                createZip(
                    container,
                    linkedMapOf(
                        "base.apk" to targetApk.readBytes(),
                        "split_config.en.apk" to targetApk.readBytes()
                    )
                )
            }
        }
        val analyzer = sourceAnalyzer()

        val standalone = runBlocking { analyzer.analyzeTarget(targetApk) }
        assertEquals(SignatureMetadataTargetType.APK, standalone.targetType)
        assertEquals(1, standalone.apkEntryCount)
        assertEquals("example.package", standalone.apkInfo.packageName)

        splitTargets.forEach { splitTarget ->
            val split = runBlocking { analyzer.analyzeTarget(splitTarget) }
            assertEquals(
                SignatureMetadataTargetType.SPLIT_APK_CONTAINER,
                split.targetType
            )
            assertEquals(2, split.apkEntryCount)
            assertEquals("example.package", split.apkInfo.packageName)
        }
    }

    @Test
    fun acceptsConfigurationSplitModulesWithoutStandaloneManifestParsing() =
        withWorkspace { directory ->
            val module = directory.resolve("config.arm64_v8a.apk")
            createZip(
                module,
                linkedMapOf(
                    "AndroidManifest.xml" to "split-manifest".toByteArray(),
                    "resources.arsc" to "split-resources".toByteArray(),
                    "META-INF/OLD.SF" to "old-signature".toByteArray()
                )
            )
            var archiveMetadataRead = false
            val analyzer = SignatureMetadataInputAnalyzer(
                archiveMetadataReader = ApkArchiveMetadataReader {
                    archiveMetadataRead = true
                    null
                }
            )
            val containerInfo = SignatureMetadataApkInfo(
                packageName = "example.package",
                versionName = "1.0",
                versionCode = 1,
                existingSignatureEntries = emptyList(),
                hasApkSigningBlock = false,
                entryCount = 0
            )

            val moduleInfo = analyzer.buildSplitModuleInfoFromContainer(
                file = module,
                containerInfo = containerInfo,
                manifestIdentity = SplitApkManifestIdentity(
                    splitName = "config.arm64_v8a",
                    packageName = containerInfo.packageName,
                    versionCode = containerInfo.versionCode.toInt(),
                    versionName = containerInfo.versionName
                ),
                checkCancelled = {}
            )

            assertFalse(archiveMetadataRead)
            assertEquals(containerInfo.packageName, moduleInfo.packageName)
            assertEquals(containerInfo.versionName, moduleInfo.versionName)
            assertEquals(containerInfo.versionCode, moduleInfo.versionCode)
            assertEquals(listOf("META-INF/OLD.SF"), moduleInfo.existingSignatureEntries)
            assertFalse(moduleInfo.hasApkSigningBlock)
            assertEquals(3, moduleInfo.entryCount)
        }

    @Test
    fun rejectsMismatchedOrDuplicateTargetSplitManifestIdentities() =
        withWorkspace { directory ->
            val modules = listOf(
                SplitApkPreparer.ExtractedModule(
                    archiveName = "base.apk",
                    name = "base.apk",
                    file = directory.resolve("base.apk")
                ),
                SplitApkPreparer.ExtractedModule(
                    archiveName = "feature.apk",
                    name = "feature.apk",
                    file = directory.resolve("feature.apk")
                )
            )
            val containerInfo = SignatureMetadataApkInfo(
                packageName = "example.package",
                versionName = "1.0",
                versionCode = 1,
                existingSignatureEntries = emptyList(),
                hasApkSigningBlock = false,
                entryCount = 0
            )
            val analyzer = sourceAnalyzer()
            val validBase = SplitApkManifestIdentity(
                splitName = null,
                packageName = containerInfo.packageName,
                versionCode = 1,
                versionName = containerInfo.versionName
            )

            assertFailsWith<IllegalArgumentException> {
                analyzer.validateSplitTargetManifestIdentities(
                    modules = modules,
                    identities = mapOf(
                        "base.apk" to validBase,
                        "feature.apk" to SplitApkManifestIdentity(
                            splitName = "feature",
                            packageName = "other.package",
                            versionCode = 1,
                            versionName = "1.0"
                        )
                    ),
                    containerInfo = containerInfo
                )
            }

            assertFailsWith<IllegalArgumentException> {
                analyzer.validateSplitTargetManifestIdentities(
                    modules = modules,
                    identities = mapOf(
                        "base.apk" to validBase.copy(splitName = "feature"),
                        "feature.apk" to validBase.copy(splitName = "feature")
                    ),
                    containerInfo = containerInfo
                )
            }
        }

    @Test
    fun rejectsAmbiguousOrUnsafeSplitContainerEntries() = withWorkspace { directory ->
        val donorApk = directory.resolve("donor.apk")
        createZip(
            donorApk,
            linkedMapOf(
                "AndroidManifest.xml" to "manifest".toByteArray(),
                "META-INF/DONOR.SF" to "signature-file".toByteArray()
            )
        )
        val duplicateNames = directory.resolve("duplicate.apks")
        createZip(
            duplicateNames,
            linkedMapOf(
                "base.apk" to donorApk.readBytes(),
                "BASE.APK" to donorApk.readBytes()
            )
        )
        val nestedDuplicateNames = directory.resolve("nested-duplicate.apks")
        createZip(
            nestedDuplicateNames,
            linkedMapOf(
                "first/base.apk" to donorApk.readBytes(),
                "second/BASE.APK" to donorApk.readBytes()
            )
        )
        val unsafePath = directory.resolve("unsafe.apks")
        createZip(
            unsafePath,
            linkedMapOf("../base.apk" to donorApk.readBytes())
        )
        val analyzer = sourceAnalyzer()

        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeSignatureSource(duplicateNames) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeSignatureSource(nestedDuplicateNames) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeSignatureSource(unsafePath) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeTarget(duplicateNames) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeTarget(nestedDuplicateNames) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeTarget(unsafePath) }
        }
    }

    @Test
    fun rejectsSplitContainersPastTheConfiguredEntryLimit() = withWorkspace { directory ->
        val container = directory.resolve("oversized.apks")
        createZip(
            container,
            linkedMapOf(
                "base.apk" to byteArrayOf(1),
                "split_config.en.apk" to byteArrayOf(2),
                "split_config.xxhdpi.apk" to byteArrayOf(3)
            )
        )
        val analyzer = sourceAnalyzer(maxArchiveEntries = 2)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeSignatureSource(container) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { analyzer.analyzeTarget(container) }
        }
    }

    @Test
    fun splitArchivePredicateReturnsFalsePastTheEntryLimit() = withWorkspace { directory ->
        val container = directory.resolve("oversized-predicate.apks")
        createZip(
            container,
            linkedMapOf(
                "base.apk" to byteArrayOf(1),
                "split_config.en.apk" to byteArrayOf(2),
                "split_config.xxhdpi.apk" to byteArrayOf(3)
            )
        )

        assertFalse(
            SplitApkPreparer.isSplitArchive(
                file = container,
                maxArchiveEntries = 2
            )
        )
    }

    @Test
    fun genericZipDoesNotTreatFeatureNameEndingInBaseAsTheDonorBase() =
        withWorkspace { directory ->
            val donorApk = directory.resolve("YouTube.apk")
            val featureApk = directory.resolve("firebase.apk")
            val configApk = directory.resolve("split_config.en.apk")
            createZip(
                donorApk,
                linkedMapOf(
                    "AndroidManifest.xml" to "manifest".toByteArray(),
                    "classes.dex" to ByteArray(4096) { it.toByte() },
                    "META-INF/YOUTUBE.SF" to "donor-signature".toByteArray()
                )
            )
            createZip(
                featureApk,
                linkedMapOf(
                    "AndroidManifest.xml" to "feature-manifest".toByteArray(),
                    "META-INF/FIREBASE.SF" to "feature-signature".toByteArray()
                )
            )
            createZip(
                configApk,
                linkedMapOf(
                    "AndroidManifest.xml" to "config-manifest".toByteArray(),
                    "META-INF/CONFIG.SF" to "config-signature".toByteArray()
                )
            )
            val container = directory.resolve("donor.zip")
            createZip(
                container,
                linkedMapOf(
                    donorApk.name to donorApk.readBytes(),
                    featureApk.name to featureApk.readBytes(),
                    configApk.name to configApk.readBytes()
                )
            )

            val info = runBlocking { sourceAnalyzer().analyzeSignatureSource(container) }

            assertTrue("META-INF/YOUTUBE.SF" in info.entryNames)
            assertFalse("META-INF/FIREBASE.SF" in info.entryNames)
            assertFalse("META-INF/CONFIG.SF" in info.entryNames)
        }

    @Test
    fun cancellationDeletesIncompleteOutput() = withWorkspace { directory ->
        val target = directory.resolve("target.apk")
        val metadata = directory.resolve("metadata.zip")
        val output = directory.resolve("output.apk")
        createZip(
            target,
            linkedMapOf(
                "AndroidManifest.xml" to "manifest".toByteArray(),
                "classes.dex" to ByteArray(1024)
            )
        )
        createZip(
            metadata,
            linkedMapOf("META-INF/NEW.SF" to "metadata".toByteArray())
        )
        var checks = 0

        assertFailsWith<CancellationException> {
            SignatureMetadataInjectorArscEngine.execute(
                metadataArchive = metadata,
                targetApk = target,
                outputApk = output,
                mode = SignatureMetadataInjectionMode.REPLACE_EXISTING,
                onProgress = {},
                onLog = {},
                checkCancelled = {
                    checks++
                    if (checks > 2) throw CancellationException("cancelled")
                }
            )
        }

        assertFalse(output.exists())
    }

    private fun sourceAnalyzer(
        maxArchiveEntries: Int = SignatureMetadataArchiveEngine.MAX_ENTRY_COUNT
    ) = SignatureMetadataInputAnalyzer(
        archiveMetadataReader = ApkArchiveMetadataReader {
            SignatureMetadataApkMetadata(
                packageName = "example.package",
                versionName = "1.0",
                versionCode = 1,
                isSplitApk = false
            )
        },
        maxArchiveEntries = maxArchiveEntries
    )

    private fun assertPayloadEqual(original: File, output: File) {
        val originalEntries = readPayloadEntries(original)
        val outputEntries = readPayloadEntries(output)
        assertEquals(originalEntries.keys, outputEntries.keys)
        originalEntries.forEach { (name, bytes) ->
            assertContentEquals(bytes, outputEntries.getValue(name), name)
        }
    }

    private fun readPayloadEntries(file: File): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (
                    entry.isDirectory ||
                    SignatureMetadataArchiveEngine.isSignatureMetadataEntry(entry.name)
                ) {
                    continue
                }
                entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return entries
    }

    private fun readEntry(file: File, name: String): ByteArray =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: error("Missing ZIP entry: $name")
            zip.getInputStream(entry).use { it.readBytes() }
        }

    private fun hasEntry(file: File, name: String): Boolean =
        ZipFile(file).use { zip -> zip.getEntry(name) != null }

    private fun createZip(
        file: File,
        entries: LinkedHashMap<String, ByteArray>,
        storedNames: Set<String> = emptySet()
    ) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name in storedNames) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private inline fun withWorkspace(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("signature-metadata-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
