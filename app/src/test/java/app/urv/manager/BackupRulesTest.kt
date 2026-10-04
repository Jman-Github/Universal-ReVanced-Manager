package app.urv.manager

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    private val signingLocations = setOf(
        "root" to "app_signing",
        "file" to "manager.keystore",
        "external" to "keystore"
    )

    @Test
    fun cloudBackupExcludesEverySigningLocation() {
        val policies = listOf(
            rules("backup_rules.xml"),
            rules("data_extraction_rules.xml").getElementsByTagName("cloud-backup").item(0) as Element
        )
        policies.forEach { policy ->
            val excluded = exclusions(policy)
            signingLocations.forEach { location ->
                assertTrue("Cloud backup includes signing material at $location", location in excluded)
            }
        }
    }

    @Test
    fun modernDeviceTransferPreservesSigningMaterial() {
        val policy = rules("data_extraction_rules.xml")
            .getElementsByTagName("device-transfer").item(0) as Element
        val excluded = exclusions(policy)
        signingLocations.forEach { location ->
            assertFalse("Device transfer excludes signing material at $location", location in excluded)
        }
    }

    @Test
    fun importedPatchOptionFilesAreExcludedFromEveryBackupPolicy() {
        val policies = listOf(
            rules("backup_rules.xml"),
            rules("data_extraction_rules.xml").getElementsByTagName("cloud-backup").item(0) as Element,
            rules("data_extraction_rules.xml").getElementsByTagName("device-transfer").item(0) as Element
        )
        policies.forEach { policy ->
            assertTrue("Backup includes potentially large option imports",
                ("root" to "app_patch-option-inputs") in exclusions(policy))
        }
    }

    private fun rules(name: String): Element {
        val file = listOf(
            File("src/main/res/xml", name),
            File("app/src/main/res/xml", name)
        ).firstOrNull(File::isFile) ?: error("Cannot find backup resource $name")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun exclusions(policy: Element): Set<Pair<String, String>> {
        val nodes = policy.getElementsByTagName("exclude")
        return (0 until nodes.length).mapTo(mutableSetOf()) { index ->
            val node = nodes.item(index) as Element
            node.getAttribute("domain") to node.getAttribute("path")
        }
    }
}
