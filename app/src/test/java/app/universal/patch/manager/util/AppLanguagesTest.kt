package app.universal.patch.manager.util

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLanguagesTest {
    @Test
    fun `saved language is pending until its locale is active`() {
        assertFalse(isAppLanguageApplied("it", "en"))
        assertFalse(isAppLanguageApplied("it", ""))
        assertTrue(isAppLanguageApplied("it", "it"))
        assertFalse(isAppLanguageApplied("system", "it"))
        assertTrue(isAppLanguageApplied("system", ""))
        assertTrue(isAppLanguageApplied("in", "id"))
        assertTrue(isAppLanguageApplied("zh-CN", "zh-CN"))
        assertFalse(isAppLanguageApplied("pt-BR", "pt-PT"))
    }

    @Test
    fun `every picker language can be resolved without falling back to English`() {
        val options = appLanguageOptions.filterNot { it.code == "system" }
        assertEquals(options.size, options.map { it.code.lowercase(Locale.ROOT) }.toSet().size)
        options.forEach { option ->
            assertEquals(option.code.lowercase(Locale.ROOT), resolveAppLanguageCode(option.code))
        }
    }

    @Test
    fun `regional variants of newly listed languages resolve correctly`() {
        mapOf("it-IT" to "it", "ar-SA" to "ar", "el-GR" to "el", "fil-PH" to "fil",
            "id-ID" to "in", "zh_CN" to "zh-cn", "pt_BR" to "pt-br",
            "unknown" to "en").forEach { (input, expected) ->
            val resolved = resolveAppLanguageCode(input)
            // Java canonicalizes the historical Indonesian language code to id.
            assertEquals(Locale.forLanguageTag(expected), Locale.forLanguageTag(resolved))
        }
    }

    @Test
    fun `every nonempty translation locale appears in the picker`() {
        val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        assertTrue(resources.isDirectory, "Cannot find app resources")
        val listedTags = appLanguageOptions.filterNot { it.code == "system" }
            .map { Locale.forLanguageTag(it.code).toLanguageTag().lowercase(Locale.ROOT) }
        val localeDirectory = Regex("values-([a-z]{2,3})(?:-r([A-Z]{2}))?")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val translatedTags = resources.listFiles().orEmpty().mapNotNull { directory ->
            val match = localeDirectory.matchEntire(directory.name) ?: return@mapNotNull null
            val strings = File(directory, "strings.xml")
            if (!strings.isFile) return@mapNotNull null
            val document = parser.parse(strings)
            if (document.getElementsByTagName("string").length == 0) return@mapNotNull null
            val language = match.groupValues[1]
            val region = match.groupValues[2]
            Locale.forLanguageTag(if (region.isEmpty()) language else "$language-$region")
                .toLanguageTag().lowercase(Locale.ROOT)
        }
        assertTrue(translatedTags.isNotEmpty())
        translatedTags.forEach { tag ->
            assertTrue(
                tag in listedTags || tag.substringBefore('-') in listedTags,
                "Missing picker language for translated locale $tag"
            )
        }
    }
}
