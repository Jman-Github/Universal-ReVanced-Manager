package app.urv.manager.ui.component.patches

import app.urv.manager.patcher.patch.ExplicitOptionKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionFieldsTest {
    @Test
    fun validatesSelectedDocumentExtensionsAfterNormalization() {
        assertTrue(isDocumentExtensionAllowed("PNG", listOf(".png", "jpg")))
        assertFalse(isDocumentExtensionAllowed("gif", listOf(".png", "jpg")))
        assertTrue(isDocumentExtensionAllowed("", null))
    }

    @Test
    fun usesWildcardForExplicitImageExtensionsWhenAnyMimeTypeIsUnknown() {
        val mimeTypes = resolvePickerMimeTypes(
            extensions = listOf("png", "custom"),
            imageOnly = true,
            mimeTypeForExtension = { extension ->
                "image/png".takeIf { extension == "png" }
            }
        )

        assertContentEquals(arrayOf("*/*"), mimeTypes)
    }

    @Test
    fun usesImageWildcardWhenImageOptionDoesNotRestrictExtensions() {
        assertContentEquals(
            arrayOf("image/*"),
            resolvePickerMimeTypes(extensions = null, imageOnly = true)
        )
    }

    @Test
    fun returnsDistinctMimeTypesWhenEveryExtensionCanBeMapped() {
        val mimeTypes = extensionsToMimeTypes(
            extensions = listOf("jpg", "jpeg", "png"),
            mimeTypeForExtension = { extension ->
                when (extension) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    else -> null
                }
            }
        )

        assertContentEquals(arrayOf("image/jpeg", "image/png"), mimeTypes)
    }

    @Test
    fun folderImportBudgetCountsDirectoriesAndFiles() {
        val budget = FolderImportBudget(maxEntries = 2)

        budget.recordEntry()
        budget.recordEntry()

        assertFailsWith<IllegalStateException> {
            budget.recordEntry()
        }
    }

    @Test
    fun folderImportBudgetRejectsExcessiveDepthAndBytes() {
        val budget = FolderImportBudget(maxDepth = 1, maxBytes = 4)

        budget.checkDepth(1)
        budget.recordBytes(4)

        assertFailsWith<IllegalStateException> {
            budget.checkDepth(2)
        }
        assertFailsWith<IllegalStateException> {
            budget.recordBytes(1)
        }
    }

    @Test
    fun folderImportPreservesValidDisplayNamesExactly() {
        val name = " café \"original\" file.png "

        assertEquals(name, safeDocumentName(name, index = 3))
    }

    @Test
    fun folderImportReplacesOnlyPathSeparatorsAndNul() {
        assertEquals(
            "nested_name_part_.txt",
            safeDocumentName("nested/name\\part\u0000.txt", index = 3)
        )
    }

    @Test
    fun folderImportUsesFallbackForUnsafeEmptyOrDotNames() {
        assertEquals("item_1", safeDocumentName("", index = 1))
        assertEquals("item_2", safeDocumentName(".", index = 2))
        assertEquals("item_3", safeDocumentName("..", index = 3))
        assertEquals("item_4", safeDocumentName(null, index = 4))
    }

    @Test
    fun filesOptionDropsNullAndBlankPaths() {
        assertEquals(
            listOf("/managed/first", "/managed/second"),
            normalizeListOptionValues(
                values = listOf("/managed/first", "", "  ", null, "/managed/second"),
                explicitKind = ExplicitOptionKind.Files
            )
        )
    }

    @Test
    fun ordinaryStringListsPreserveBlankValues() {
        assertEquals(
            listOf("", "value"),
            normalizeListOptionValues(
                values = listOf("", "value"),
                explicitKind = null
            )
        )
    }

    @Test
    fun requiredFilesOptionDoesNotSubmitAnEmptyList() {
        assertFalse(
            shouldSubmitListOption(
                currentValues = emptyList<String>(),
                nextValues = normalizeListOptionValues(
                    values = listOf<String?>(null),
                    explicitKind = ExplicitOptionKind.Files
                ),
                explicitKind = ExplicitOptionKind.Files,
                required = true
            )
        )
        assertFalse(
            shouldSubmitListOption(
                currentValues = listOf("/managed/original"),
                nextValues = emptyList<String>(),
                explicitKind = ExplicitOptionKind.Files,
                required = true
            )
        )
        assertTrue(
            shouldSubmitListOption(
                currentValues = listOf(""),
                nextValues = emptyList<String>(),
                explicitKind = ExplicitOptionKind.Files,
                required = false
            )
        )
    }

    @Test
    fun pendingDialogInputsSurviveConfigurationRecreation() {
        assertFalse(shouldDeletePendingDialogInputs(isChangingConfigurations = true))
        assertTrue(shouldDeletePendingDialogInputs(isChangingConfigurations = false))
    }
}
