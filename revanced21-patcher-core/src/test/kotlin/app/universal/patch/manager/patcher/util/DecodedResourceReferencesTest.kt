package app.universal.patch.manager.patcher.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DecodedResourceReferencesTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("decoded-resources-").toFile()
        try {
            root.resolve("res").mkdirs()
            xml(root, "AndroidManifest.xml", "<manifest/>")
            test(root)
        } finally { root.deleteRecursively() }
    }
    private fun xml(root: File, path: String, content: String) {
        root.resolve(path).apply { requireNotNull(parentFile).mkdirs(); writeText(content) }
    }

    @Test fun extensionlessRawFilesAndQualifiedNinePatchFilesAreIndexed() = fixture { root ->
        xml(root, "res/raw/payload", "binary data")
        xml(root, "res/drawable-hdpi/button.9.png", "image data")
        xml(root, "AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            android:data="@raw/payload" android:icon="@drawable/button"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun idsAreCollectedBeforeReferencesAcrossFilesAndQualifiers() = fixture { root ->
        xml(root, "res/layout/use.xml", """<View xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@id/checked" android:tag="@id/from_manifest"/>""")
        xml(root, "res/drawable-v21/state.xml", """<item xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/checked"/>""")
        xml(root, "AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/from_manifest"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun valuesItemsArraysAliasesAndPublicIdsAreRecognized() = fixture { root ->
        xml(root, "res/values/resources.xml", """<resources>
            <public name="custom" type="id" id="0x7f010000"/>
            <item name="another_id" type="id"/>
            <string name="label">Label</string>
            <item type="drawable" name="alias">@android:drawable/sym_def_app_icon</item>
            <integer-array name="numbers"><item>1</item></integer-array>
            </resources>""")
        xml(root, "res/layout/test.xml", """<View a="@id/custom" b="@id/another_id"
            c="@string/label" d="@drawable/alias" e="@array/numbers"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun externalNumericAndLiteralAttributesAreNotLocalReferences() = fixture { root ->
        xml(root, "res/layout/test.xml", """<View a="@android:id/cancel" b="@*android:attr/foo"
            c="@other.package:string/label" d="@0x7f010001" e="literal @string/not_a_reference"
            f="@null" g="?attr/theme"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun rawXmlRemainsArbitraryData() = fixture { root ->
        xml(root, "res/raw/data.xml", "arbitrary non-XML @id/not_an_android_reference")
        xml(root, "AndroidManifest.xml", """<manifest data="@raw/data"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun missingResourcesRemainErrorsEvenWithPublicDeclarations() = fixture { root ->
        xml(root, "res/values/public.xml", """<resources><public name="missing" type="raw" id="0x7f010000"/></resources>""")
        xml(root, "res/layout/view.xml", """<View a="@id/absent" b="@raw/missing" c="@strings/label"/>""")
        xml(root, "res/values/strings.xml", """<resources><string name="label">Label</string></resources>""")
        val missing = DecodedResourceReferences.findMissing(root)
        assertEquals(3, missing.size)
        assertTrue(missing.any { it.endsWith("@raw/missing") })
        assertTrue(missing.any { it.endsWith("@id/absent") })
        assertTrue(missing.any { it.endsWith("@strings/label") })
    }

    @Test fun malformedResourceXmlIsReportedInsteadOfSilentlyDroppingDefinitions() = fixture { root ->
        xml(root, "res/values/broken.xml", "<resources><string")
        val error = assertThrows(IOException::class.java) { DecodedResourceReferences.findMissing(root) }
        assertTrue(error.message.orEmpty().contains("broken.xml"))
    }

    @Test fun utf16ResourcesAndNestedStyleableAttributesAreRecognized() = fixture { root ->
        root.resolve("res/values").mkdirs()
        root.resolve("res/values/attrs.xml").writeBytes("""<?xml version="1.0" encoding="UTF-16"?>
            <resources><declare-styleable name="Widget"><attr name="custom" format="string"/></declare-styleable></resources>
            """.toByteArray(Charsets.UTF_16))
        xml(root, "res/layout/test.xml", """<View a="@attr/custom"/>""")
        assertTrue(DecodedResourceReferences.findMissing(root).isEmpty())
    }

    @Test fun doctypeIsRejectedBeforeEntityResolution() = fixture { root ->
        xml(root, "res/layout/test.xml", """<!DOCTYPE View [<!ENTITY external SYSTEM "file:///must-not-be-read">]>
            <View a="&external;"/>""")
        val error = assertThrows(IOException::class.java) { DecodedResourceReferences.findMissing(root) }
        assertTrue(error.cause?.message.orEmpty().contains("DTD"))
    }
}
