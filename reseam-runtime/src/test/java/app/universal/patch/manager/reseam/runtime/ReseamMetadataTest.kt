package app.universal.patch.manager.reseam.runtime

import app.reseam.sdk.*
import org.junit.Assert.*
import org.junit.Test

class ReseamMetadataTest {
    private fun option(type: OptionType, default: OptionValue? = null) =
        OptionDeclaration("key", "Title", "Description", type, default, null, false)

    @Test fun optionNumbersKeepSdkPrecision() {
        val integer = Long.MAX_VALUE
        val decimal = 1.23456789012345
        assertEquals(integer, ReseamMetadata.value(OptionValue.Int(integer)))
        assertEquals(decimal, ReseamMetadata.value(OptionValue.Float(decimal)))
        assertEquals(OptionValue.Int(integer), ReseamMetadata.optionValue(option(OptionType.INT), integer))
        assertEquals(OptionValue.Float(decimal), ReseamMetadata.optionValue(option(OptionType.FLOAT), decimal))
    }

    @Test fun listsAndPathsKeepTheirTypes() {
        val list = listOf("one", "two")
        assertEquals(OptionValue.TextList(list), ReseamMetadata.optionValue(option(OptionType.STRING_LIST), list))
        assertEquals(OptionValue.Path("/cache/file"), ReseamMetadata.optionValue(option(OptionType.PATH), "/cache/file"))
    }

    @Test fun duplicateDisplayNamesHaveDistinctSelections() {
        val first = PatchSpec("b", "a", "Same", false, "", true, emptyList(), Compatibility.Universal, emptyList())
        val second = first.copy(id = "z")
        assertNotEquals(ReseamMetadata.label(first), ReseamMetadata.label(second))
        assertEquals(ReseamMetadata.label(first), ReseamMetadata.toMap(first)["name"])
        assertEquals("Same", ReseamMetadata.toMap(first)["displayName"])
        assertEquals("Same", ReseamMetadata.toMap(second)["displayName"])
        assertNull(ReseamMetadata.toMap(first)["compatiblePackages"])
    }

    @Test fun unrestrictedPackageAndPathMetadataMatchHostContract() {
        val spec = PatchSpec("b", "a", "Name", false, "", true, emptyList(),
            Compatibility.Packages(listOf(CompatiblePackage("app.example", emptyList(), intArrayOf()))),
            listOf(option(OptionType.PATH)))
        val map = ReseamMetadata.toMap(spec)
        val compatible = (map["compatiblePackages"] as List<*>).single() as Map<*, *>
        assertNull(compatible["versions"])
        val path = (map["options"] as List<*>).single() as Map<*, *>
        assertEquals("FilePath", path["explicitKind"])
    }
}
