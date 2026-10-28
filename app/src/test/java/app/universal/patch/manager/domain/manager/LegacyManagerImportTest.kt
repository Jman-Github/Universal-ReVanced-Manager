package app.universal.patch.manager.domain.manager

import app.universal.patch.manager.domain.repository.RuntimePluginInstalledTrust
import app.universal.patch.manager.domain.repository.RuntimePluginSigners
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LegacyManagerImportTest {
    private val oldRepo = "https://github.com/Jman-Github/Universal-ReVanced-Manager"
    private val newRepo = "https://github.com/Jman-Github/Universal-Patch-Manager"

    @Test fun historicalApiUrlsResolveToThePatchApi() {
        for (repo in listOf(oldRepo, newRepo, oldRepo.lowercase() + "/",
            "https://api.github.com/repos/Jman-Github/Universal-ReVanced-Manager",
            "https://api.github.com/repos/Jman-Github/Universal-Patch-Manager/")) {
            assertEquals("https://api.revanced.app", normalizeLegacyManagerApi(repo))
        }
    }

    @Test fun customApiUrlsAndUnrelatedRepositoriesStayUntouched() {
        for (url in listOf("https://api.example.com", oldRepo.replace("Jman-Github", "somebody"),
            oldRepo + "-other", oldRepo + "/releases", "not a URI", "file:///tmp/backup",
            "https://github.com.evil/Jman-Github/Universal-ReVanced-Manager",
            "https://github.com@evil/Jman-Github/Universal-ReVanced-Manager",
            "https://github.com/Jman-Github//Universal-ReVanced-Manager")) {
            assertEquals(url, normalizeLegacyManagerApi(url))
        }
    }

    @Test fun encodedPathSeparatorsDoNotCrashOrChangeTheDestination() {
        for (url in listOf(
            "https://github.com/Jman-Github%2FUniversal-ReVanced-Manager",
            "https://api.github.com/repos/Jman-Github%2FUniversal-ReVanced-Manager",
            "$oldRepo%2Freleases"
        )) {
            assertEquals(url, migrateManagerRepositoryUrl(url))
            assertEquals(url, normalizeLegacyManagerApi(url))
        }
    }

    @Test fun mixedCaseSchemesStillMigrateTheManagerApi() {
        assertEquals("https://api.revanced.app", normalizeLegacyManagerApi(oldRepo.replace("https:", "HTTPS:")))
    }

    @Test fun legacySigningFilesStayOutOfCloudBackupsButAllowDeviceTransfer() {
        val resources = File(System.getProperty("upm.resourceDir") ?: "src/main/res")
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val full = factory.newDocumentBuilder().parse(File(resources, "xml/backup_rules.xml"))
        val extraction = factory.newDocumentBuilder().parse(File(resources, "xml/data_extraction_rules.xml"))
        fun excludes(parent: org.w3c.dom.Element, name: String): Boolean {
            val nodes = parent.getElementsByTagName("exclude")
            return (0 until nodes.length).any { index ->
                val entry = nodes.item(index) as org.w3c.dom.Element
                entry.getAttribute("domain") == "file" && entry.getAttribute("path") == name
            }
        }
        val cloud = extraction.getElementsByTagName("cloud-backup").item(0) as org.w3c.dom.Element
        val transfer = extraction.getElementsByTagName("device-transfer").item(0) as org.w3c.dom.Element
        for (name in listOf("manager.keystore", "urv_keystore.keystore")) {
            assertTrue("$name must be excluded from old Android cloud backups", excludes(full.documentElement, name))
            assertTrue("$name must be excluded from Android 12+ cloud backups", excludes(cloud, name))
            assertFalse("$name must remain eligible for device transfer", excludes(transfer, name))
        }
    }

    @Test fun pluginRepositoryMigrationPreservesReleasePathAndQuery() {
        assertEquals(newRepo + "/releases/download/v1/runtime.apk?name=a%20b#asset",
            migrateManagerRepositoryUrl(oldRepo + "/releases/download/v1/runtime.apk?name=a%20b#asset"))
        assertEquals(newRepo + ".git", migrateManagerRepositoryUrl(oldRepo + ".git"))
    }

    @Test fun sourceMigrationPreservesIdsPinsTrustAndUnknownFields() {
        val raw = """[{"id":"old-source-id","repoUrl":"$oldRepo","assetSelector":"reseam-plugin","autoUpdate":false,"versionKey":"v1:reseam-plugin.apk","trustedSignatureHex":"test-pin","futureField":42}]"""
        val before = Json.parseToJsonElement(raw).jsonArray.single().jsonObject
        val after = Json.parseToJsonElement(migrateManagerPluginSources(raw)).jsonArray.single().jsonObject
        assertEquals(newRepo, after.getValue("repoUrl").jsonPrimitive.content)
        assertEquals(before - "repoUrl", after - "repoUrl")
    }

    @Test fun malformedPluginSettingsArePreservedForExistingErrorHandling() {
        for (raw in listOf("", "broken", "{}", "[null,1]")) {
            assertEquals(raw, migrateManagerPluginSources(raw))
        }
        assertEquals("broken", migrateManagerRuntimeTrust("broken"))
    }

    @Test fun runtimePackageMigrationIsLimitedToKnownManagerRuntimes() {
        for (suffix in listOf(".morphe.runtime", ".reseam.runtime", ".revanced.v21.runtime")) {
            assertEquals("app.universal.patch.manager" + suffix,
                migrateManagerRuntimePackage("app.universal.revanced.manager" + suffix))
        }
        for (name in listOf("third.party.runtime", ".reseam.runtime", "app.universal.revanced.manager.custom")) {
            assertEquals(name, migrateManagerRuntimePackage(name))
        }
    }

    @Test fun existingUpmTrustTakesPrecedenceWithoutChangingSignerPins() {
        val raw = """{"app.universal.revanced.manager.reseam.runtime":"old-pin","app.universal.patch.manager.reseam.runtime":"new-pin","third.party.runtime":"other-pin"}"""
        val migrated = Json.parseToJsonElement(migrateManagerRuntimeTrust(raw)).jsonObject
        assertEquals("new-pin", migrated.getValue("app.universal.patch.manager.reseam.runtime").jsonPrimitive.content)
        assertEquals("other-pin", migrated.getValue("third.party.runtime").jsonPrimitive.content)
        assertFalse(migrated.containsKey("app.universal.revanced.manager.reseam.runtime"))
    }

    @Test fun legacyRuntimeTrustRetainsTheExactSignerPin() {
        val raw = """{"app.universal.revanced.manager.revanced.v21.runtime":"exact-old-signer"}"""
        assertEquals("exact-old-signer", Json.parseToJsonElement(migrateManagerRuntimeTrust(raw))
            .jsonObject.getValue("app.universal.patch.manager.revanced.v21.runtime").jsonPrimitive.content)
    }

    @Test fun settingsMigrationPreservesUserValuesAndIsIdempotent() {
        val snapshot = PreferencesManager.SettingsSnapshot(
            api = oldRepo,
            customBackgroundImageUri = "content://old-provider/image",
            patchedAppExportFormat = "URV-{app-name}",
            installerPrimary = ":shizuku:",
            keystoreAlias = "my URV key",
            autoPatchEnabledPackages = setOf("app.universal.revanced.manager.userapp"),
            acknowledgedPatcherRuntimePlugins = setOf("app.universal.revanced.manager.reseam.runtime")
        )
        val migrated = normalizeImportedManagerSettings(snapshot)
        assertEquals(snapshot.copy(
            api = "https://api.revanced.app",
            acknowledgedPatcherRuntimePlugins = setOf("app.universal.patch.manager.reseam.runtime")
        ), migrated)
        assertEquals(migrated, normalizeImportedManagerSettings(migrated))
    }

    @Test fun recoveryChecksUrVAndOlderNamesOnlyInsideProvidedStorage() {
        val signing = File("test/signing")
        val files = File("test/files")
        val backup = File("test/backup")
        val candidates = legacyManagerKeystoreFiles(signing, files, backup)
        assertEquals(listOf(
            File(signing, "urv_keystore.keystore"), File(backup, "urv_keystore.keystore"),
            File(files, "urv_keystore.keystore"), File(signing, "manager.keystore"),
            File(backup, "manager.keystore"), File(files, "manager.keystore")
        ), candidates)
        assertEquals(4, legacyManagerKeystoreFiles(signing, files, null).size)
        assertFalse(candidates.any { it.path.contains("app.universal.revanced.manager") })
    }

    @Test fun renamedInstallationTrustFallsBackToTheExactSignerPin() {
        val legacy = "app.universal.revanced.manager.reseam.runtime"
        val current = "app.universal.patch.manager.reseam.runtime"
        for (key in listOf(legacy, current)) {
            val raw = JsonObject(mapOf(key to JsonPrimitive("v1|$legacy|12345|1000|old-signer"))).toString()
            val migrated = Json.parseToJsonElement(migrateManagerRuntimeTrust(raw)).jsonObject
            assertEquals("old-signer", migrated.getValue(current).jsonPrimitive.content)
            val approval = RuntimePluginInstalledTrust.decode(migrated.getValue(current).jsonPrimitive.content)!!
            assertNull(approval.packageName)
            assertFalse(approval.trusts(current, 12345, 1000, RuntimePluginSigners(listOf("different-signer"))))
            assertTrue(approval.trusts(current, 99999, 2000, RuntimePluginSigners(listOf("old-signer"))))
            assertEquals(migrateManagerRuntimeTrust(raw), migrateManagerRuntimeTrust(migrateManagerRuntimeTrust(raw)))
        }
    }

    @Test fun currentAndThirdPartyInstallationTrustRemainUnchanged() {
        val current = "app.universal.patch.manager.reseam.runtime"
        val raw = JsonObject(mapOf(
            current to JsonPrimitive("v1|$current|12345|1000|current-signer"),
            "third.party.runtime" to JsonPrimitive("v1|third.party.runtime|23456|2000|third-signer")
        )).toString()
        assertEquals(raw, migrateManagerRuntimeTrust(raw))
    }

    @Test fun explicitUpmInstallationTrustWinsOverMigratedLegacyApproval() {
        val legacy = "app.universal.revanced.manager.reseam.runtime"
        val current = "app.universal.patch.manager.reseam.runtime"
        val currentApproval = "v1|$current|23456|2000|current-signer"
        val raw = JsonObject(mapOf(
            legacy to JsonPrimitive("v1|$legacy|12345|1000|old-signer"),
            current to JsonPrimitive(currentApproval)
        )).toString()
        val migrated = Json.parseToJsonElement(migrateManagerRuntimeTrust(raw)).jsonObject
        assertEquals(currentApproval, migrated.getValue(current).jsonPrimitive.content)
    }
}
